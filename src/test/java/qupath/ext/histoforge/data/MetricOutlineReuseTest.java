package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes.AggregationType;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ScriptHeaderParser;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.ROIs;

/** Exact immutable outline sharing without sharing mutable hierarchy or script objects. */
class MetricOutlineReuseTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    @Test
    void builderCaptureAndRepeatedScriptsRemainIsolatedFromTheSourceHierarchy() throws Exception {
        PolygonROI original = polygon();
        var parent = PathObjects.createAnnotationObject(original, PathClass.fromString("Parent"));
        var child =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(2, 2, 0.25, 0.25, PLANE),
                        PathClass.fromString("Cell"));
        parent.setID(MetricTestData.objectId(1));
        child.setID(MetricTestData.objectId(2));
        parent.addChildObject(child);
        var hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(parent);
        var targetGeometry = ROIs.createRectangleROI(0, 0, 100, 100, PLANE).getGeometry();
        var target =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Slide(),
                        targetGeometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(targetGeometry));
        var input =
                MetricSnapshotBuilder.prepare(
                                MetricTestData.clientBuild(10, 20),
                                module(),
                                List.of(target),
                                sourceRevision(),
                                hierarchy)
                        .snapshot()
                        .metricInput();
        var region = input.regions().getFirst();
        assertSame(original, region.objects().getFirst().scriptRoi(PLANE));
        assertEquals(Optional.of(parent.getID()), region.objects().getLast().parentObjectId());
        byte[] expectedInput = MetricInputEncodingTest.encoded(input);
        var script =
                MetricTestData.script(
                        """
                        return children.collect { object ->
                            def before = [object.ID.toString(), object.pathClass.toString(),
                                          object.parent.ID.toString(), object.ROI.allPoints,
                                          Double.doubleToRawLongBits(object.ROI.getArea())]
                            object.setPathClass(qupath.lib.objects.classes.PathClass.fromString('Changed'))
                            def geometry = object.ROI.geometry
                            def minX = geometry.envelopeInternal.minX
                            geometry.apply(org.locationtech.jts.geom.util.AffineTransformation.translationInstance(100, 0))
                            assert Math.abs(geometry.envelopeInternal.minX - minX - 100) < 0.0001
                            if (object.ROI.shape instanceof java.awt.geom.Path2D) {
                                object.ROI.shape.reset()
                            }
                            try {
                                object.ROI.allPoints.clear()
                            } catch (UnsupportedOperationException ignored) {
                                // Some QuPath outline types return an immutable point list.
                            }
                            object.setROI(object.ROI.translate(100, 100))
                            roi.addChildObject(object)
                            return before
                        }
                        """,
                        MetricTypesTest.scalar("count", AggregationType.SUM));
        var runner = new MetricScriptRunner();
        runner.prepare(script);
        Object expectedScript = runner.run(script, region, "image", 0.25, 0.25);

        ((PathROIObject) parent).setROI(original.translate(50, 50));
        parent.setPathClass(PathClass.fromString("Changed parent"));
        ((PathROIObject) child).setROI(child.getROI().translate(50, 50));
        child.setPathClass(PathClass.fromString("Changed cell"));
        hierarchy.getRootObject().addChildObject(child);

        assertEquals(expectedScript, runner.run(script, region, "image", 0.25, 0.25));
        assertArrayEquals(expectedInput, MetricInputEncodingTest.encoded(input));
        assertEquals("Parent", region.objects().getFirst().pathClass());
        assertEquals(Optional.of(parent.getID()), region.objects().getLast().parentObjectId());
        assertEquals("Changed parent", parent.getPathClass().toString());
        assertEquals(hierarchy.getRootObject(), child.getParent());
    }

    private static PolygonROI polygon() throws Exception {
        return (PolygonROI)
                GeometryTools.geometryToROI(
                        new WKTReader(GeometryTools.getDefaultFactory())
                                .read("POLYGON ((0.25 0.5,1.75 8.25,8.125 1.5,0.25 0.5))")
                                .norm(),
                        PLANE);
    }

    private static ModuleSnapshot module() {
        String source =
                """
                /* @histoforge-metrics
                [{"name":"count","label":"Count","type":"scalar","decimals":0,"aggregation":"sum"}]
                */
                return [count: children.size()]
                """;
        return new ModuleSnapshot(
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
                                        source,
                                        CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source))),
                                ScriptHeaderParser.parseMetrics(source))));
    }

    private static RasterInputCodec.ActionSourceRevision sourceRevision() {
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
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        "metric-image",
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        "8".repeat(64)),
                source);
    }
}
