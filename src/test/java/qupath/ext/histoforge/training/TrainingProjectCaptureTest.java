package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.TrainingGeometryCodec.TrainingObject;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingDatasetLimits;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.roi.RoiManager;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class TrainingProjectCaptureTest {

    private static final String ROI_ID = "11111111-1111-4111-8111-111111111111";
    private static final String CROSSING_ID = "22222222-2222-4222-8222-222222222222";
    private static final String TOUCHING_ID = "33333333-3333-4333-8333-333333333333";
    private static final String UNCLASSIFIED_ID = "44444444-4444-4444-8444-444444444444";
    private static final String OTHER_ID = "55555555-5555-4555-8555-555555555555";

    @Test
    void capturesCompleteIntersectingTargetsAndAdvisoryCounts() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathObject roi = roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane());
        roi.setPathClass(PathClass.fromString("Leydig cell"));
        PathObject crossing =
                detection(CROSSING_ID, 90, 20, 20, 10, ImagePlane.getDefaultPlane(), "Leydig cell");
        PathObject touching =
                detection(TOUCHING_ID, 100, 30, 5, 5, ImagePlane.getDefaultPlane(), "Leydig cell");
        PathObject unclassified =
                detection(UNCLASSIFIED_ID, 10, 10, 5, 5, ImagePlane.getDefaultPlane(), null);
        PathObject other =
                detection(OTHER_ID, 20, 20, 5, 5, ImagePlane.getDefaultPlane(), "Sertoli cell");
        hierarchy.addObjects(List.of(other, touching, roi, unclassified, crossing));

        var image =
                TrainingProjectCapture.captureImage(
                                "project-entry-1",
                                " Control ",
                                sourceRevision("project-entry-1", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow();

        assertEquals("Control", image.projectGroup().orElseThrow());
        assertEquals(1, image.rois().size());
        var captured = image.rois().getFirst();
        assertEquals(new TrainingProjectCapture.LabelCounts(1, 1, 1), captured.counts());
        assertEquals(CROSSING_ID, captured.targetObjects().getFirst().sourceObjectUuid());
        assertEquals("detection", captured.targetObjects().getFirst().objectType());
        assertEquals(
                20.0,
                captured.targetObjects().getFirst().geometry().getEnvelopeInternal().getWidth());
        assertFalse(
                captured.targetObjects().stream()
                        .anyMatch(item -> item.sourceObjectUuid().equals(TOUCHING_ID)));
        var summary =
                TrainingProjectCapture.countAnnotations(hierarchy, 200, 200, () -> {})
                        .orElseThrow();
        assertEquals(image.annotationClasses(), summary.annotationClasses());
        var counts = summary.rois().getFirst();
        assertEquals(captured.sourceRoiUuid(), counts.roiId());
        assertEquals(Map.of("Leydig cell", 1, "Sertoli cell", 1), counts.byClass());
        assertEquals(captured.counts().noClass(), counts.noClass());
    }

    @Test
    void capturesAnnotationsButExcludesQuPathCellObjects() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        PathObject annotation =
                PathObjects.createAnnotationObject(
                        ROIs.createPolygonROI(
                                new double[] {10, 30, 10},
                                new double[] {10, 10, 30},
                                ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Leydig cell"));
        annotation.setID(UUID.fromString(CROSSING_ID));
        PathObject cell =
                PathObjects.createCellObject(
                        ROIs.createRectangleROI(40, 40, 20, 20, ImagePlane.getDefaultPlane()),
                        ROIs.createRectangleROI(44, 44, 8, 8, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Leydig cell"));
        cell.setID(UUID.fromString(OTHER_ID));
        hierarchy.addObjects(List.of(annotation, cell));

        var captured =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow()
                        .rois()
                        .getFirst();
        assertEquals(new TrainingProjectCapture.LabelCounts(1, 0, 1), captured.counts());
        assertEquals(
                List.of(CROSSING_ID),
                captured.targetObjects().stream().map(item -> item.sourceObjectUuid()).toList());
        assertEquals("annotation", captured.targetObjects().getFirst().objectType());
        assertEquals(200.0, captured.targetObjects().getFirst().geometry().getArea());
    }

    @Test
    void excludesReservedRegionClassesFromDiscoveryAndTargetCounts() {
        var plane = ImagePlane.getDefaultPlane();
        var trainingRoi = roi(ROI_ID, 0, 0, 100, 100, plane);
        var legacyOutline =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(1, 1, 98, 98, plane),
                        PathClass.fromString("ReproPath:ROI"));
        var currentOutline =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(2, 2, 96, 96, plane),
                        RoiManager.HISTOFORGE_ROI_CLASS);
        var biologicalLabel = detection(CROSSING_ID, 10, 10, 8, 8, plane, "Tissue: ROI");
        var hierarchy = new PathObjectHierarchy();
        hierarchy
                .getRootObject()
                .addChildObjects(
                        List.of(trainingRoi, legacyOutline, currentOutline, biologicalLabel));
        var selectedTask =
                new TrainingTask(
                        "test-capability",
                        "field",
                        task(true).modelOutput(),
                        true,
                        0.5,
                        Map.of(
                                "ReproPath: ROI", "Leydig cell",
                                "HistoForge: ROI", "Leydig cell",
                                "Tissue: ROI", "Leydig cell"));

        TrainingRoiPreflight.requireWithinObjectLimit(
                "entry", hierarchy, 200, 200, selectedTask, 1, () -> {});
        var image =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                selectedTask)
                        .orElseThrow();

        assertEquals(Set.of("Tissue: ROI"), image.annotationClasses());
        assertEquals(1, image.rois().size());
        var captured = image.rois().getFirst();
        assertEquals(new TrainingProjectCapture.LabelCounts(1, 0, 0), captured.counts());
        assertEquals(CROSSING_ID, captured.targetObjects().getFirst().sourceObjectUuid());
        assertEquals("ReproPath: ROI", legacyOutline.getPathClass().toString());
    }

    @Test
    void rejectsNonrectangularAdministrativeRoiBeforeSourceCapture() {
        PathObject annotation =
                PathObjects.createAnnotationObject(
                        ROIs.createPolygonROI(
                                new double[] {0, 100, 0},
                                new double[] {0, 0, 100},
                                ImagePlane.getDefaultPlane()));
        annotation.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(annotation);
        assertThrows(
                qupath.ext.histoforge.roi.RoiRegistry.CorruptHierarchyException.class,
                () ->
                        TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                task(true)));
    }

    @Test
    void rejectsNegativeRoiAndNonzeroPlaneBeforeSourceCapture() {
        PathObjectHierarchy negative = new PathObjectHierarchy();
        negative.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                negative,
                                task(false)));

        PathObjectHierarchy otherPlane = new PathObjectHierarchy();
        otherPlane.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getPlane(1, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                otherPlane,
                                task(true)));
    }

    @Test
    void ignoresObjectsOnOtherPlanes() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        hierarchy.addObject(
                detection(CROSSING_ID, 10, 10, 20, 20, ImagePlane.getPlane(1, 0), "Leydig cell"));
        hierarchy.addObject(
                detection(UNCLASSIFIED_ID, 20, 20, 10, 10, ImagePlane.getPlane(1, 0), null));

        var capture =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow()
                        .rois()
                        .getFirst();
        assertEquals(new TrainingProjectCapture.LabelCounts(0, 0, 0), capture.counts());
    }

    @Test
    void advisoryCountsIncludeObjectsOutsideTargetClasses() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        PathObject annotation =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(10, 10, 20, 20, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Sertoli cell"));
        annotation.setID(UUID.fromString(OTHER_ID));
        hierarchy.addObject(annotation);
        var counts =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow()
                        .rois()
                        .getFirst()
                        .counts();

        assertEquals(new TrainingProjectCapture.LabelCounts(0, 0, 1), counts);
    }

    @Test
    void ignoresQuPathTilesAsAdministrativeObjects() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        hierarchy.addObject(
                PathObjects.createTileObject(
                        ROIs.createRectangleROI(10, 10, 20, 20, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Leydig cell")));
        hierarchy.addObject(
                PathObjects.createTileObject(
                        ROIs.createRectangleROI(40, 40, 20, 20, ImagePlane.getDefaultPlane())));

        var capture =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow()
                        .rois()
                        .getFirst();

        assertEquals(new TrainingProjectCapture.LabelCounts(0, 0, 0), capture.counts());
        assertEquals(List.of(), capture.targetObjects());
    }

    @Test
    void emptyImageIsOmittedAndCaptureIsDeterministic() {
        assertEquals(
                java.util.Optional.empty(),
                TrainingProjectCapture.captureImage(
                        "entry",
                        null,
                        sourceRevision("entry", 200, 200),
                        new PathObjectHierarchy(),
                        task(true)));

        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        var first =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow();
        var second =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow();
        assertEquals(first, second);
        assertEquals(
                CanonicalGeometryCodec.canonicalDigest(first.rois().getFirst().geometry()),
                CanonicalGeometryCodec.canonicalDigest(second.rois().getFirst().geometry()));
    }

    @Test
    void preflightCountsObjectsAndDistinctRingVerticesBeforePixelWork() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        hierarchy.addObject(
                detection(
                        CROSSING_ID, 10, 10, 20, 20, ImagePlane.getDefaultPlane(), "Leydig cell"));
        var image =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow();

        PathObjectHierarchy twoObjectHierarchy = new PathObjectHierarchy();
        twoObjectHierarchy.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        twoObjectHierarchy.addObject(
                detection(
                        CROSSING_ID, 10, 10, 20, 20, ImagePlane.getDefaultPlane(), "Leydig cell"));
        twoObjectHierarchy.addObject(
                detection(
                        TOUCHING_ID, 40, 40, 20, 20, ImagePlane.getDefaultPlane(), "Leydig cell"));
        var twoObjects =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                twoObjectHierarchy,
                                task(true))
                        .orElseThrow();

        TrainingProjectCapture.requireWithinLimits(List.of(image), datasetLimits(1, 1, 1, 4, 8));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingProjectCapture.requireWithinLimits(
                                List.of(image, image), datasetLimits(1, 2, 2, 4, 16)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingProjectCapture.requireWithinLimits(
                                List.of(twoObjects), datasetLimits(1, 1, 2, 4, 12)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingProjectCapture.requireWithinLimits(
                                List.of(twoObjects), datasetLimits(1, 2, 1, 4, 12)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingProjectCapture.requireWithinLimits(
                                List.of(image), datasetLimits(1, 1, 1, 4, 7)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingProjectCapture.requireWithinLimits(
                                List.of(image), datasetLimits(1, 1, 1, 3, 8)));
    }

    @ParameterizedTest
    @CsvSource({"10001, 10000, '10,001', '10,000'", "2001, 2000, '2,001', '2,000'"})
    void oversizedRoiIdentifiesExactCountsImageAndLocationWithoutChangingAdmission(
            int objectCount, int limit, String displayedCount, String displayedLimit) {
        var hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(
                List.of(
                        roi(ROI_ID, 0, 0, 10, 10, ImagePlane.getDefaultPlane()),
                        roi(OTHER_ID, 50.25, 60.5, 40, 30, ImagePlane.getDefaultPlane())));
        var empty =
                TrainingProjectCapture.captureImage(
                                "image-27",
                                null,
                                sourceRevision("image-27", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow();
        var targetGeometry =
                qupath.ext.histoforge.spatial.NativeRoiGeometry.convert(
                        ROIs.createRectangleROI(55, 65, 2, 2, ImagePlane.getDefaultPlane()));
        var objects =
                java.util.stream.IntStream.range(0, objectCount)
                        .mapToObj(
                                index ->
                                        new TrainingObject(
                                                new UUID(0, index + 1).toString(),
                                                "detection",
                                                "Leydig cell",
                                                targetGeometry))
                        .toList();
        var oversizedRoi =
                new TrainingProjectCapture.CapturedRoi(
                        OTHER_ID,
                        empty.rois().getLast().geometry(),
                        objects,
                        new TrainingProjectCapture.LabelCounts(objectCount, 0, 0));
        var image = withRois(empty, List.of(empty.rois().getFirst(), oversizedRoi));
        var limits = datasetLimits(2, limit, objectCount, 4, 1_000_000);

        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TrainingProjectCapture.requireWithinLimits(List.of(image), limits));
        assertEquals(
                "Training ROI contains "
                        + displayedCount
                        + " target objects; the limit is "
                        + displayedLimit
                        + " per ROI.\n"
                        + "Project image ID: image-27\n"
                        + "ROI ID: "
                        + OTHER_ID
                        + "\n"
                        + "Location in slide pixels: x=50.25, y=60.5, width=40.0, height=30.0.\n\n"
                        + "Resize this ROI or replace it with smaller, non-overlapping HistoForge training ROIs, then click Refresh in Train. "
                        + "Existing detections whose classes are mapped to this training task also count as labels.",
                failure.getMessage());
        assertEquals(objectCount, oversizedRoi.targetObjects().size());

        var atLimit =
                new TrainingProjectCapture.CapturedRoi(
                        OTHER_ID,
                        oversizedRoi.geometry(),
                        objects.subList(0, limit),
                        new TrainingProjectCapture.LabelCounts(limit, 0, 0));
        assertDoesNotThrow(
                () ->
                        TrainingProjectCapture.requireWithinLimits(
                                List.of(withRois(empty, List.of(empty.rois().getFirst(), atLimit))),
                                limits));
    }

    @Test
    void otherCaptureLimitFailuresAlsoReportActualAndConfiguredCounts() {
        var hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        var image =
                TrainingProjectCapture.captureImage(
                                "entry",
                                null,
                                sourceRevision("entry", 200, 200),
                                hierarchy,
                                task(true))
                        .orElseThrow();
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                TrainingProjectCapture.requireWithinLimits(
                                        List.of(image, image), datasetLimits(1, 1, 1, 4, 8)));
        assertEquals(
                "Limit exceeded for training ROIs: actual 2; maximum 1.", failure.getMessage());
    }

    private static TrainingProjectCapture.CapturedImage withRois(
            TrainingProjectCapture.CapturedImage image,
            List<TrainingProjectCapture.CapturedRoi> rois) {
        return new TrainingProjectCapture.CapturedImage(
                image.projectEntryId(),
                image.projectEntryIdDigest(),
                image.projectGroup(),
                image.sourceRevision(),
                image.task(),
                rois,
                image.annotationClasses());
    }

    private static TrainingTask task(boolean supportsNegative) {
        return new TrainingTask(
                "test-capability",
                "field",
                new ModelOutput("instance_segmentation", List.of("Leydig cell"), List.of()),
                supportsNegative,
                0.5,
                java.util.Map.of("Leydig cell", "Leydig cell"));
    }

    @Test
    void capturesOriginalNamesAndOmitsOverlappingBackgroundWithoutMutatingHierarchy() {
        var hierarchy = new PathObjectHierarchy();
        var foreground =
                detection(CROSSING_ID, 10, 10, 20, 20, ImagePlane.getDefaultPlane(), "Raw label");
        var background =
                detection(OTHER_ID, 10, 10, 20, 20, ImagePlane.getDefaultPlane(), "Not mapped");
        hierarchy.addObjects(
                List.of(
                        roi(ROI_ID, 0, 0, 100, 100, ImagePlane.getDefaultPlane()),
                        foreground,
                        background));
        var target =
                new TrainingTask(
                        "test-capability",
                        "field",
                        task(true).modelOutput(),
                        true,
                        0.37,
                        java.util.Map.of("Raw label", "Leydig cell"));
        var captured =
                TrainingProjectCapture.captureImage(
                                "entry", null, sourceRevision("entry", 200, 200), hierarchy, target)
                        .orElseThrow();
        assertEquals(java.util.Set.of("Raw label", "Not mapped"), captured.annotationClasses());
        var objects = captured.rois().getFirst().targetObjects();
        assertEquals(1, objects.size());
        assertEquals("Raw label", objects.getFirst().pathClass());
        assertEquals(400.0, objects.getFirst().geometry().getArea());
        assertEquals("Raw label", foreground.getPathClass().toString());
        assertEquals("Not mapped", background.getPathClass().toString());
    }

    private static RasterInputCodec.ActionSourceRevision sourceRevision(
            String imageId, int width, int height) {
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "test-source",
                        "1".repeat(64),
                        Optional.empty(),
                        width,
                        height,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.5, 0.5));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        imageId,
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        "2".repeat(64)),
                source);
    }

    private static TrainingDatasetLimits datasetLimits(
            long maxRois,
            long maxObjectsPerRoi,
            long maxTotalObjects,
            long maxVerticesPerGeometry,
            long maxTotalVertices) {
        return new TrainingDatasetLimits(
                maxRois,
                maxObjectsPerRoi,
                maxTotalObjects,
                maxVerticesPerGeometry,
                maxTotalVertices,
                1024,
                1024,
                1024);
    }

    private static PathObject roi(
            String id, double x, double y, double width, double height, ImagePlane plane) {
        PathObject object =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(x, y, width, height, plane));
        object.setID(UUID.fromString(id));
        object.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        return object;
    }

    private static PathObject detection(
            String id,
            double x,
            double y,
            double width,
            double height,
            ImagePlane plane,
            String pathClass) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(x, y, width, height, plane),
                        pathClass == null ? null : PathClass.fromString(pathClass));
        object.setID(UUID.fromString(id));
        return object;
    }
}
