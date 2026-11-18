package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes.MetricPreparation;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ScriptHeaderParser;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/** Capture owns private geometry while unknown ROI implementations keep defensive copying. */
class MetricSnapshotGeometryOwnershipTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    @Test
    void concretePolygonGeometryNeedsNoAdditionalSnapshotCopies() throws Exception {
        PolygonROI original =
                spy(
                        (PolygonROI)
                                GeometryTools.geometryToROI(
                                        read("POLYGON ((1 1,1 8,8 2,1 1))").norm(), PLANE));
        Geometry privateGeometry = spy(original.getGeometry());
        // Observe copies of the already-private return value, not calls inside QuPath itself.
        doReturn(privateGeometry).when(original).getGeometry();

        var preparation = prepare(original);

        // Slide selection lends its private validated geometry to membership without copying.
        verify(privateGeometry, times(0)).copy();
        assertSame(
                original,
                preparation
                        .snapshot()
                        .metricInput()
                        .regions()
                        .getFirst()
                        .objects()
                        .getFirst()
                        .scriptRoi(PLANE));
        assertTrue(preparation.parentNormalization().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON ((1 1,1 8,8 2,1 1))",
                "POLYGON ((1 1,1 9,9 9,9 1,1 1),(3 3,7 3,7 7,3 7,3 3))",
                "MULTIPOLYGON (((1 1,1 3,3 3,3 1,1 1)),((5 5,5 8,8 8,8 5,5 5)))"
            })
    void unknownRoiCopiesSharedGeometryAndKeepsTheSnapshotIsolated(String shape) throws Exception {
        Geometry sharedGeometry = spy(read(shape));
        Geometry expected = read(shape);
        ROI mutableOutline = outlineReturning(sharedGeometry);

        var preparation = prepare(mutableOutline);

        // Snapshot capture owns one defensive copy; membership borrows that same private copy.
        verify(sharedGeometry, times(1)).copy();
        var input = preparation.snapshot().metricInput();
        var captured = input.regions().getFirst().objects().getFirst();
        assertEquals(expected, captured.geometrySlide());
        translate(sharedGeometry);
        translate(captured.geometrySlide());
        translate(captured.scriptRoi(PLANE).getGeometry());
        assertEquals(expected, captured.geometrySlide());
        assertEquals(
                input.metricInputDigest(),
                MetricTypes.MetricInputDraft.build(
                                input.clientBuild(),
                                input.calibration(),
                                input.module(),
                                input.script(),
                                input.regions())
                        .metricInputDigest());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON ((0 0,8 8,8 0,0 8,0 0))",
                "POLYGON EMPTY",
                "POLYGON Z ((1 1 1,1 8 1,8 2 1,1 1 1))"
            })
    void captureStillRejectsInvalidGeometryBeforeTakingOwnership(String shape) throws Exception {
        ROI outline = outlineReturning(read(shape));

        assertThrows(IllegalArgumentException.class, () -> prepare(outline));
    }

    @Test
    void captureStillRejectsNonFiniteCoordinates() throws Exception {
        var geometry = (org.locationtech.jts.geom.Polygon) read("POLYGON ((1 1,1 8,8 2,1 1))");
        geometry.getExteriorRing().getCoordinateSequence().setOrdinate(1, 0, Double.NaN);
        geometry.geometryChanged();

        assertThrows(IllegalArgumentException.class, () -> prepare(outlineReturning(geometry)));
    }

    private static ROI outlineReturning(Geometry geometry) {
        ROI outline = mock(ROI.class);
        when(outline.isArea()).thenReturn(true);
        when(outline.getGeometry()).thenReturn(geometry);
        when(outline.getImagePlane()).thenReturn(PLANE);
        return outline;
    }

    private static MetricPreparation prepare(ROI outline) {
        var object = PathObjects.createDetectionObject(outline, PathClass.fromString("Cell"));
        object.setID(MetricTestData.objectId(1));
        var hierarchy = new PathObjectHierarchy();
        hierarchy.getRootObject().addChildObject(object);
        var targetGeometry = ROIs.createRectangleROI(0, 0, 100, 100, PLANE).getGeometry();
        var target =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Slide(),
                        targetGeometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(targetGeometry));
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "metric-test",
                        "7".repeat(64),
                        Optional.empty(),
                        100,
                        100,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                "metric-image",
                                SourceIdentityCodec.imageSourceIdentityDigest(source),
                                "8".repeat(64)),
                        source);
        String script =
                """
                /* @histoforge-metrics
                [{"name":"count","label":"Count","type":"scalar","decimals":0,"aggregation":"sum"}]
                */
                return [count: children.size()]
                """;
        var module =
                new ModuleSnapshot(
                        "deployment-test",
                        MetricTestData.MODULE,
                        new JsonObject(),
                        "1".repeat(64),
                        Map.of(),
                        Map.of(),
                        Optional.of(
                                new ModuleSnapshot.MetricScriptSnapshot(
                                        new ModuleSnapshot.ScriptSnapshot(
                                                "metrics.groovy",
                                                script,
                                                CanonicalCodec.sha256(
                                                        CanonicalCodec.utf8Bytes(script))),
                                        ScriptHeaderParser.parseMetrics(script))));
        return MetricSnapshotBuilder.prepare(
                MetricTestData.clientBuild(10, 20), module, List.of(target), revision, hierarchy);
    }

    private static Geometry read(String shape) throws Exception {
        return new WKTReader(GeometryTools.getDefaultFactory()).read(shape);
    }

    private static void translate(Geometry geometry) {
        geometry.apply(
                new CoordinateSequenceFilter() {
                    public void filter(CoordinateSequence sequence, int index) {
                        sequence.setOrdinate(index, 0, sequence.getX(index) + 100);
                    }

                    public boolean isDone() {
                        return false;
                    }

                    public boolean isGeometryChanged() {
                        return true;
                    }
                });
    }
}
