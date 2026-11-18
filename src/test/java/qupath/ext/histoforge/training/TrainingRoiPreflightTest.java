package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingDatasetLimits;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class TrainingRoiPreflightTest {
    private static final TrainingTask TASK =
            new TrainingTask(
                    "fixture",
                    "field",
                    new ModelOutput("instance_segmentation", List.of("Leydig cell"), List.of()),
                    true,
                    0.5,
                    Map.of("Raw label", "Leydig cell"));

    @Test
    void ordinaryAnnotationsAndDetectionsAreNotTrainingRois() {
        var hierarchy = new PathObjectHierarchy();
        assertFalse(TrainingProjectCapture.hasRoiMarkers(hierarchy));
        var roi = ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane());
        hierarchy.addObjects(
                List.of(
                        PathObjects.createAnnotationObject(roi),
                        PathObjects.createDetectionObject(roi)));
        assertFalse(TrainingProjectCapture.hasRoiMarkers(hierarchy));
    }

    @Test
    void nestedMarkerIsFoundWithoutGeometryValidation() {
        var hierarchy = new PathObjectHierarchy();
        var parent =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        var nested =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(10, 10, 10, 10, ImagePlane.getDefaultPlane()));
        nested.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        parent.addChildObject(nested);
        hierarchy.getRootObject().addChildObject(parent);
        assertTrue(TrainingProjectCapture.hasRoiMarkers(hierarchy));
    }

    @Test
    void malformedMarkedDetectionMustReachFullValidationNotBeSkipped() {
        var hierarchy = new PathObjectHierarchy();
        var object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()));
        object.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        hierarchy.addObject(object);
        assertTrue(TrainingProjectCapture.hasRoiMarkers(hierarchy));
        assertThrows(
                RuntimeException.class,
                () -> qupath.ext.histoforge.roi.RoiRegistry.fromHierarchy(hierarchy));
    }

    @Test
    void countMatchesCaptureForMappedAnnotationsDetectionsAndCrossingObjects() {
        var hierarchy = hierarchyWithRoi();
        var crossing = label(90, 10, 20, 20);
        var annotation =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(20, 20, 5, 5, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Raw label"));
        hierarchy
                .getRootObject()
                .addChildObjects(
                        List.of(
                                crossing,
                                annotation,
                                label(100, 20, 5, 5),
                                label(150, 20, 5, 5),
                                PathObjects.createCellObject(
                                        ROIs.createRectangleROI(
                                                30, 30, 5, 5, ImagePlane.getDefaultPlane()),
                                        null,
                                        PathClass.fromString("Raw label")),
                                PathObjects.createTileObject(
                                        ROIs.createRectangleROI(
                                                40, 40, 5, 5, ImagePlane.getDefaultPlane()),
                                        PathClass.fromString("Raw label")),
                                PathObjects.createDetectionObject(
                                        ROIs.createRectangleROI(
                                                50, 50, 5, 5, ImagePlane.getDefaultPlane())),
                                PathObjects.createDetectionObject(
                                        ROIs.createRectangleROI(
                                                60, 60, 5, 5, ImagePlane.getPlane(1, 0)),
                                        PathClass.fromString("Raw label")),
                                PathObjects.createDetectionObject(
                                        ROIs.createRectangleROI(
                                                70, 70, 5, 5, ImagePlane.getDefaultPlane()),
                                        PathClass.fromString("Not mapped"))));
        assertCountParity(hierarchy, 2);
    }

    @Test
    void usesExactIntersectionForAHoledGeometryNotItsBoundingBox() {
        var hierarchy = hierarchyWithRoi();
        var factory = new org.locationtech.jts.geom.GeometryFactory();
        var outer =
                new org.locationtech.jts.geom.Coordinate[] {
                    new org.locationtech.jts.geom.Coordinate(-20, -20),
                    new org.locationtech.jts.geom.Coordinate(140, -20),
                    new org.locationtech.jts.geom.Coordinate(140, 140),
                    new org.locationtech.jts.geom.Coordinate(-20, 140),
                    new org.locationtech.jts.geom.Coordinate(-20, -20)
                };
        var inner =
                new org.locationtech.jts.geom.Coordinate[] {
                    new org.locationtech.jts.geom.Coordinate(-10, -10),
                    new org.locationtech.jts.geom.Coordinate(110, -10),
                    new org.locationtech.jts.geom.Coordinate(110, 110),
                    new org.locationtech.jts.geom.Coordinate(-10, 110),
                    new org.locationtech.jts.geom.Coordinate(-10, -10)
                };
        var polygon =
                factory.createPolygon(
                        factory.createLinearRing(outer),
                        new org.locationtech.jts.geom.LinearRing[] {
                            factory.createLinearRing(inner)
                        });
        hierarchy
                .getRootObject()
                .addChildObject(
                        PathObjects.createDetectionObject(
                                qupath.lib.roi.GeometryTools.geometryToROI(
                                        polygon, ImagePlane.getDefaultPlane()),
                                PathClass.fromString("Raw label")));
        assertCountParity(hierarchy, 0);
    }

    @Test
    void mixedRoisNameTheOversizedRoiAndReportItsEntirePopulation() {
        var hierarchy = hierarchyWithRoi();
        var other = trainingRoi(120, 0, 60, 100);
        hierarchy
                .getRootObject()
                .addChildObjects(
                        List.of(
                                other,
                                label(1, 1, 2, 2),
                                label(125, 10, 5, 5),
                                label(130, 20, 5, 5),
                                label(140, 30, 5, 5),
                                label(150, 40, 5, 5)));
        var exact = capture(hierarchy);
        var expected =
                assertThrows(
                        TrainingProjectCapture.RoiObjectLimitException.class,
                        () ->
                                TrainingProjectCapture.requireWithinLimits(
                                        List.of(exact), limits(2)));
        var actual =
                assertThrows(
                        TrainingProjectCapture.RoiObjectLimitException.class,
                        () -> preflight(hierarchy, 2, () -> {}));
        assertEquals(expected.getMessage(), actual.getMessage());
        assertTrue(actual.getMessage().contains("contains 4 target objects"));
        assertTrue(actual.getMessage().contains(other.getID().toString()));
    }

    @Test
    void aCrossingObjectCountsOnceInEachNonoverlappingRoi() {
        var hierarchy = hierarchyWithRoi();
        hierarchy
                .getRootObject()
                .addChildObjects(List.of(trainingRoi(100, 0, 100, 100), label(90, 10, 20, 10)));
        assertEquals(
                List.of(1, 1),
                capture(hierarchy).rois().stream().map(roi -> roi.targetObjects().size()).toList());
        assertDoesNotThrow(() -> preflight(hierarchy, 1, () -> {}));
    }

    @Test
    void acceptedCaptureStillRejectsInvalidGeometryOutsideTrainingRois() {
        var hierarchy = hierarchyWithRoi();
        var invalid = org.mockito.Mockito.mock(qupath.lib.roi.interfaces.ROI.class);
        org.mockito.Mockito.when(invalid.isArea()).thenReturn(true);
        org.mockito.Mockito.when(invalid.getImagePlane()).thenReturn(ImagePlane.getDefaultPlane());
        org.mockito.Mockito.when(invalid.getBoundsX()).thenReturn(150.0);
        org.mockito.Mockito.when(invalid.getBoundsY()).thenReturn(150.0);
        org.mockito.Mockito.when(invalid.getBoundsWidth()).thenReturn(10.0);
        org.mockito.Mockito.when(invalid.getBoundsHeight()).thenReturn(10.0);
        org.mockito.Mockito.when(invalid.getGeometry())
                .thenReturn(new org.locationtech.jts.geom.GeometryFactory().createPolygon());
        hierarchy
                .getRootObject()
                .addChildObject(
                        PathObjects.createDetectionObject(
                                invalid, PathClass.fromString("Raw label")));
        assertDoesNotThrow(() -> preflight(hierarchy, 1, () -> {}));
        org.mockito.Mockito.verify(invalid, org.mockito.Mockito.never()).getGeometry();
        assertThrows(IllegalArgumentException.class, () -> capture(hierarchy));
    }

    @Test
    void preflightChecksCancellationDuringPopulationTraversal() {
        var hierarchy = hierarchyWithRoi();
        hierarchy.getRootObject().addChildObjects(List.of(label(1, 1, 2, 2), label(4, 4, 2, 2)));
        var checks = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        preflight(
                                hierarchy,
                                10,
                                () -> {
                                    if (checks.incrementAndGet() == 3)
                                        throw new CancellationException("cancelled");
                                }));
        assertEquals(3, checks.get());
    }

    @Test
    void preflightDoesNotHideDuplicateIdsBadPlanesOrOverlappingRois() {
        var duplicate = hierarchyWithRoi();
        var first = label(1, 1, 2, 2);
        var second = label(4, 4, 2, 2);
        second.setID(first.getID());
        duplicate.getRootObject().addChildObjects(List.of(first, second));
        assertThrows(IllegalArgumentException.class, () -> preflight(duplicate, 10, () -> {}));

        var overlapping = hierarchyWithRoi();
        overlapping.getRootObject().addChildObject(trainingRoi(50, 50, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> preflight(overlapping, 10, () -> {}));

        var badPlane = new PathObjectHierarchy();
        var roi =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getPlane(1, 0)));
        roi.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        badPlane.getRootObject().addChildObject(roi);
        assertThrows(IllegalArgumentException.class, () -> preflight(badPlane, 10, () -> {}));
    }

    @Test
    void deterministicPopulationsPreserveAcceptedCapturesAndCountBoundaries() {
        var random = new java.util.Random(47);
        for (int sample = 0; sample < 12; sample++) {
            var hierarchy = hierarchyWithRoi();
            for (int i = 0; i < 60; i++) {
                var object =
                        label(
                                random.nextDouble() * 190,
                                random.nextDouble() * 190,
                                random.nextDouble() * 10 + 1,
                                random.nextDouble() * 10 + 1);
                object.setID(new UUID(sample + 1, i + 1));
                hierarchy.getRootObject().addChildObject(object);
            }
            var before = capture(hierarchy);
            int count = before.rois().getFirst().targetObjects().size();
            assertCountParity(hierarchy, count);
            assertEquals(before, capture(hierarchy));
        }
    }

    private static void assertCountParity(PathObjectHierarchy hierarchy, int expected) {
        var captured = capture(hierarchy);
        assertEquals(expected, captured.rois().getFirst().targetObjects().size());
        assertDoesNotThrow(() -> preflight(hierarchy, Math.max(1, expected), () -> {}));
        if (expected > 1) {
            var old =
                    assertThrows(
                            TrainingProjectCapture.RoiObjectLimitException.class,
                            () ->
                                    TrainingProjectCapture.requireWithinLimits(
                                            List.of(captured), limits(expected - 1)));
            var early =
                    assertThrows(
                            TrainingProjectCapture.RoiObjectLimitException.class,
                            () -> preflight(hierarchy, expected - 1, () -> {}));
            assertEquals(old.getMessage(), early.getMessage());
        }
    }

    private static void preflight(PathObjectHierarchy hierarchy, long maximum, Runnable check) {
        TrainingRoiPreflight.requireWithinObjectLimit(
                "entry", hierarchy, 200, 200, TASK, maximum, check);
    }

    private static TrainingProjectCapture.CapturedImage capture(PathObjectHierarchy hierarchy) {
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "fixture",
                        "a".repeat(64),
                        Optional.empty(),
                        200,
                        200,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.5, 0.5));
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                "entry",
                                SourceIdentityCodec.imageSourceIdentityDigest(source),
                                "b".repeat(64)),
                        source);
        return TrainingProjectCapture.captureImage("entry", null, revision, hierarchy, TASK)
                .orElseThrow();
    }

    private static TrainingDatasetLimits limits(long maximum) {
        return new TrainingDatasetLimits(2000, maximum, 500000, 100000, 6500000, 1024, 1024, 1024);
    }

    private static PathObjectHierarchy hierarchyWithRoi() {
        var hierarchy = new PathObjectHierarchy();
        hierarchy.getRootObject().addChildObject(trainingRoi(0, 0, 100, 100));
        return hierarchy;
    }

    private static PathObject trainingRoi(double x, double y, double width, double height) {
        var object =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane()));
        object.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        return object;
    }

    private static PathObject label(double x, double y, double width, double height) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane()),
                PathClass.fromString("Raw label"));
    }
}
