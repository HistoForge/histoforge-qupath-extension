package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes.*;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ScriptHeaderParser;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.lib.geom.Point2;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class MetricPointSnapshotTest {
    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();
    private static final String SCORE = InferenceFeatureCodec.DETECTION_SCORE_MEASUREMENT;

    @Test
    void logicalRoiAndLaterSlideSelectionRetainTheirDifferentMembershipRules() throws Exception {
        var lower = new org.locationtech.jts.io.WKTReader().read("POLYGON ((0 0,1 0,0 1,0 0))");
        var nativePoint = ROIs.createPointsROI((float) .600000004, (float) .399999995, PLANE);
        assertFalse(lower.covers(NativeRoiGeometry.convert(nativePoint)));
        var hierarchy = new PathObjectHierarchy();
        var boundary =
                PathObjects.createAnnotationObject(
                        ROIs.createPolygonROI(
                                List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)),
                                PLANE));
        boundary.setID(UUID.fromString(MetricTestData.ROI_A));
        var point = PathObjects.createDetectionObject(nativePoint, PathClass.fromString("Center"));
        qupath.ext.histoforge.contract.ProvenanceCodec.writeMetadata(
                point.getMetadata(),
                new qupath.ext.histoforge.contract.ProvenanceCodec.ObjectProvenance(
                        qupath.ext.histoforge.contract.ProvenanceCodec.SCHEMA_VERSION,
                        new qupath.ext.histoforge.contract.ExecutionScopeCodec.OutputKey(
                                new qupath.ext.histoforge.contract.InferenceIdentityCodec
                                        .ModelStepIdentity(MetricTestData.MODULE_REF, "centers"),
                                new qupath.ext.histoforge.contract.ExecutionScopeCodec.FieldScope(
                                        new qupath.ext.histoforge.contract.ExecutionScopeCodec.Roi(
                                                MetricTestData.ROI_A))),
                        "50000000-0000-4000-8000-000000000001"));
        hierarchy.getRootObject().addChildObjects(List.of(boundary, point));
        var roi =
                new qupath.ext.histoforge.contract.ExecutionScopeCodec.TargetRegion(
                        new qupath.ext.histoforge.contract.ExecutionScopeCodec.Roi(
                                MetricTestData.ROI_A),
                        lower,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        qupath.ext.histoforge.contract.CanonicalGeometryCodec.canonicalDigest(
                                lower));
        var slide =
                new qupath.ext.histoforge.contract.ExecutionScopeCodec.TargetRegion(
                        new qupath.ext.histoforge.contract.ExecutionScopeCodec.Slide(),
                        lower,
                        roi.plane(),
                        roi.geometryDigest());
        assertEquals(
                1,
                capture(hierarchy, roi)
                        .snapshot()
                        .metricInput()
                        .regions()
                        .getFirst()
                        .objects()
                        .size());
        assertTrue(
                capture(hierarchy, slide)
                        .snapshot()
                        .metricInput()
                        .regions()
                        .getFirst()
                        .objects()
                        .isEmpty());
    }

    @Test
    void captureKeepsExactSingletonCoordinatesAndDistinguishesMissingScoreFromZero() {
        var hierarchy = new PathObjectHierarchy();
        var boundary =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 100, 100, PLANE));
        boundary.setID(UUID.fromString(MetricTestData.ROI_A));
        var points = List.of(new Point2(10.123456789, 20.23456789));
        var manual =
                PathObjects.createAnnotationObject(
                        ROIs.createPointsROI(points, PLANE), PathClass.fromString("Manual marks"));
        manual.setID(MetricTestData.objectId(1));
        var detection =
                PathObjects.createDetectionObject(
                        ROIs.createPointsROI(50.5, 60.5, PLANE), PathClass.fromString("Center"));
        detection.setID(MetricTestData.objectId(2));
        hierarchy.getRootObject().addChildObjects(List.of(boundary, manual, detection));
        var missing = capture(hierarchy);
        var snapshots = missing.snapshot().metricInput().regions().getFirst().objects();
        assertEquals(2, snapshots.size());
        assertEquals(points, snapshots.getFirst().scriptRoi(PLANE).getAllPoints());
        assertEquals(1, snapshots.getFirst().geometrySlide().getNumGeometries());
        assertTrue(snapshots.getFirst().score().isEmpty());
        assertTrue(snapshots.getLast().score().isEmpty());
        assertFalse(MetricSnapshotBuilder.canCaptureWithoutReload(hierarchy));
        assertNotEquals(
                manual.getROI().getGeometry().getCoordinates()[0].getX(),
                snapshots.getFirst().geometrySlide().getCoordinates()[0].getX());
        detection.getMeasurementList().put(SCORE, 0.0);
        var zero = capture(hierarchy);
        assertEquals(
                OptionalDouble.of(0),
                zero.snapshot().metricInput().regions().getFirst().objects().getLast().score());
        assertNotEquals(
                missing.snapshot().metricInput().metricInputDigest(),
                zero.snapshot().metricInput().metricInputDigest());
        detection.getMeasurementList().put(SCORE, 0.5);
        var changed = capture(hierarchy);
        assertNotEquals(
                zero.snapshot().metricInput().metricInputDigest(),
                changed.snapshot().metricInput().metricInputDigest());
        var engine = new MetricsEngine();
        var evaluated = engine.evaluate(zero, Map.of(), true, () -> false);
        assertTrue(evaluated.complete());
        engine.publish(evaluated, zero.snapshot().sourceRevision());
        assertTrue(engine.exactComplete(zero).isPresent());
        assertTrue(engine.exactComplete(changed).isEmpty());
        assertTrue(hierarchy.getAllObjects(false).contains(detection));
        assertNull(
                detection.getParent().getParent(),
                "detached normalization must not reparent live points");
    }

    @Test
    void emptyAndCrossRegionCountingContainersStayOutsideModelAndMetricPopulations() {
        var hierarchy = new PathObjectHierarchy();
        var left =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 100, 100, PLANE));
        left.setID(UUID.fromString(MetricTestData.ROI_A));
        var right =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(100, 0, 100, 100, PLANE));
        right.setID(UUID.fromString(MetricTestData.ROI_B));
        var marks = List.of(new Point2(10.123456789, 20.23456789), new Point2(110.003, 40.007));
        var group =
                PathObjects.createAnnotationObject(
                        ROIs.createPointsROI(marks, PLANE), PathClass.fromString("Manual marks"));
        var empty =
                PathObjects.createAnnotationObject(
                        ROIs.createPointsROI(PLANE), PathClass.fromString("Empty class"));
        hierarchy.getRootObject().addChildObjects(List.of(left, right, group, empty));
        var nativeGeometry = NativeRoiGeometry.convert(group.getROI());
        assertEquals(2, nativeGeometry.getNumGeometries());
        assertEquals(marks.get(0).getX(), nativeGeometry.getCoordinates()[0].getX());
        assertEquals(marks.get(1).getX(), nativeGeometry.getCoordinates()[1].getX());
        assertTrue(
                qupath.ext.histoforge.spatial.TargetHierarchyPopulation.select(
                                MetricTestData.target(MetricTestData.ROI_A), hierarchy)
                        .isEmpty());
        assertTrue(
                capture(hierarchy)
                        .snapshot()
                        .metricInput()
                        .regions()
                        .getFirst()
                        .objects()
                        .isEmpty());
        assertEquals(marks, group.getROI().getAllPoints());
        assertEquals(0, empty.getROI().getNumPoints());
    }

    @Test
    void pointInputHasItsOwnTagAndBindsExactCoordinatesPlaneAndScore() throws Exception {
        var roi = ROIs.createPointsROI(10.123456789, 20.23456789, PLANE);
        var geometry = NativeRoiGeometry.convert(roi);
        var scored =
                new MetricObjectSnapshot(
                        MetricTestData.objectId(1),
                        MetricObjectType.DETECTION,
                        "Center",
                        geometry,
                        Optional.empty(),
                        OptionalDouble.of(0.5));
        var bytes = new ByteArrayOutputStream();
        scored.writeOutline(new DataOutputStream(bytes), PLANE);
        assertEquals(4, bytes.toByteArray()[0]);
        var coordinates = scored.scriptRoi(PLANE).getAllPoints();
        assertEquals(roi.getAllPoints(), coordinates);
        var unscored =
                new MetricObjectSnapshot(
                        scored.objectId(),
                        scored.objectType(),
                        scored.pathClass(),
                        geometry,
                        Optional.empty());
        assertNotEquals(scored, unscored);
        var otherPlane = new ByteArrayOutputStream();
        scored.writeOutline(new DataOutputStream(otherPlane), ImagePlane.getPlane(1, 2));
        assertFalse(java.util.Arrays.equals(bytes.toByteArray(), otherPlane.toByteArray()));
        geometry.getCoordinate().x = 50;
        geometry.geometryChanged();
        assertEquals(coordinates, scored.scriptRoi(PLANE).getAllPoints());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MetricObjectSnapshot(
                                scored.objectId(),
                                scored.objectType(),
                                scored.pathClass(),
                                MetricTestData.square(0, 0, 5, 5),
                                Optional.empty(),
                                OptionalDouble.of(0.5)));
    }

    @Test
    void nativeFloatPointsAreEligibleForDirectCaptureAndNoMarkerAreaIsInvented() {
        var point = ROIs.createPointsROI((float) 10.123456789, (float) 20.23456789, PLANE);
        assertTrue(MetricRoiPersistence.isUnchangedBySave(point));
        var object = PathObjects.createDetectionObject(point, PathClass.fromString("Center"));
        assertEquals(0.0, NativeRoiGeometry.convert(point).getArea());
        assertThrows(
                IllegalArgumentException.class,
                () -> qupath.ext.histoforge.pipeline.MetricUtils.INSTANCE.area(object));
        assertThrows(
                IllegalArgumentException.class,
                () -> qupath.ext.histoforge.pipeline.MetricUtils.INSTANCE.eccentricity(object));
        assertThrows(
                IllegalArgumentException.class,
                () -> qupath.ext.histoforge.pipeline.MetricUtils.INSTANCE.solidity(object));
        var parent =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 100, 100, PLANE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        qupath.ext.histoforge.pipeline.MetricUtils.INSTANCE.matchLargestByCentroid(
                                List.of(object), List.of(parent)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        qupath.ext.histoforge.pipeline.MetricUtils.INSTANCE.matchLargestByCentroid(
                                List.of(parent), List.of(object)));
    }

    private static MetricPreparation capture(PathObjectHierarchy hierarchy) {
        return capture(hierarchy, MetricTestData.target(MetricTestData.ROI_A));
    }

    private static MetricPreparation capture(
            PathObjectHierarchy hierarchy,
            qupath.ext.histoforge.contract.ExecutionScopeCodec.TargetRegion target) {
        String source =
                """
                /* @histoforge-metrics
                [{"name":"count","label":"Count","type":"scalar","decimals":0,"aggregation":"sum"}]
                */
                return [count: children.size()]
                """;
        var module =
                new ModuleSnapshot(
                        "fixture",
                        MetricTestData.MODULE,
                        new JsonObject(),
                        "a".repeat(64),
                        Map.of(),
                        Map.of(),
                        Optional.of(
                                new ModuleSnapshot.MetricScriptSnapshot(
                                        new ModuleSnapshot.ScriptSnapshot(
                                                "metrics.groovy",
                                                source,
                                                CanonicalCodec.sha256(
                                                        CanonicalCodec.utf8Bytes(source))),
                                        ScriptHeaderParser.parseMetrics(source))));
        var imageSource =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "fixture",
                        "b".repeat(64),
                        Optional.empty(),
                        100,
                        100,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(.25, .25));
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                MetricTestData.IMAGE_ID,
                                SourceIdentityCodec.imageSourceIdentityDigest(imageSource),
                                "c".repeat(64)),
                        imageSource);
        return MetricSnapshotBuilder.prepare(
                MetricTestData.clientBuild(100, 200), module, List.of(target), revision, hierarchy);
    }
}
