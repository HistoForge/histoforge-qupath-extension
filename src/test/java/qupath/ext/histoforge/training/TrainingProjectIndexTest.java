package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ClassChannel;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry.Dimensions;
import qupath.ext.histoforge.contract.TrainingGeometryCodec.TrainingObject;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingImageSourcePlan;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingRoiSourcePlan;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedImage;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedRoi;
import qupath.ext.histoforge.training.TrainingProjectCapture.LabelCounts;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder.ProjectPlan;
import qupath.ext.histoforge.training.TrainingProjectIndex.SplitUnit;

class TrainingProjectIndexTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final String ROI_A = "11111111-1111-4111-8111-111111111111";
    private static final String ROI_B = "22222222-2222-4222-8222-222222222222";
    private static final String ROI_C = "33333333-3333-4333-8333-333333333333";

    @Test
    void canonicalRowsMatchLaterManifestIndexOrder() {
        ProjectPlan project =
                project(
                        plannedImage("image-b", "Dose", ROI_C, 2, 3),
                        plannedImage("image-a", "Control", ROI_B, 1, 4),
                        plannedImage("image-a", "Control", ROI_A, 5, 6));

        TrainingProjectIndex rois = TrainingProjectIndex.from(project, SplitUnit.ROI);
        assertEquals(
                project.images().stream()
                        .flatMap(image -> image.capture().rois().stream())
                        .map(roi -> "roi:" + roi.sourceRoiUuid())
                        .toList(),
                rois.rows().stream().map(TrainingProjectIndex.UnitRow::stableId).toList());
        assertEquals(List.of(0, 1, 2), rois.rows().stream().map(row -> row.unitIndex()).toList());
        assertEquals(
                project.imageNames().get(project.images().getFirst().capture().projectEntryId()),
                rois.rows().getFirst().imageDisplay());

        TrainingProjectIndex images = TrainingProjectIndex.from(project, SplitUnit.IMAGE);
        assertEquals(
                project.images().stream()
                        .map(image -> project.imageNames().get(image.capture().projectEntryId()))
                        .toList(),
                images.rows().stream().map(row -> row.unitLabel()).toList());
        var slideA =
                images.rows().stream()
                        .filter(row -> "Slide A".equals(row.unitLabel()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(2, slideA.roiCount());
        assertEquals(new LabelCounts(2, 6, 10), slideA.counts());

        TrainingProjectIndex groups = TrainingProjectIndex.from(project, SplitUnit.PROJECT_GROUP);
        assertEquals(
                List.of("Control", "Dose"),
                groups.rows().stream().map(row -> row.unitLabel()).toList());
        assertEquals(2, groups.rows().getFirst().roiCount());
    }

    @Test
    void assignmentCanBeEditedAndRefreshedOnlyAcrossTheSameUnits() {
        ProjectPlan project =
                project(
                        plannedImage("image-a", "Control", ROI_A, 0, 0),
                        plannedImage("image-b", "Dose", ROI_B, 0, 0),
                        plannedImage("image-c", "Dose", ROI_C, 0, 0));
        TrainingProjectIndex index = TrainingProjectIndex.from(project, SplitUnit.IMAGE);
        TrainingAssignment generated = TrainingAssignment.generate(index, 20, new Random(7));

        assertTrue(generated.isValid());
        assertEquals(
                "generated",
                ((qupath.ext.histoforge.contract.TrainingSplitCodec.ValidationSplitPlan)
                                generated.requirePlan())
                        .assignmentSource());

        int validation = generated.unitIndices(TrainingAssignment.VALIDATION).getFirst();
        TrainingAssignment invalid =
                generated.move(List.of(validation), TrainingAssignment.TRAINING);
        assertFalse(invalid.isValid());
        assertThrows(IllegalStateException.class, invalid::requirePlan);

        int training = generated.unitIndices(TrainingAssignment.TRAINING).getFirst();
        TrainingAssignment custom =
                generated.move(List.of(training), TrainingAssignment.VALIDATION);
        assertEquals(
                "custom",
                ((qupath.ext.histoforge.contract.TrainingSplitCodec.ValidationSplitPlan)
                                custom.requirePlan())
                        .assignmentSource());

        TrainingProjectIndex refreshed = TrainingProjectIndex.from(project, SplitUnit.IMAGE);
        assertEquals(custom.requirePlan(), custom.refresh(refreshed).requirePlan());

        ProjectPlan changed =
                project(
                        plannedImage("image-a", "Control", ROI_A, 0, 0),
                        plannedImage("image-b", "Dose", ROI_B, 0, 0));
        assertThrows(
                IllegalStateException.class,
                () -> custom.refresh(TrainingProjectIndex.from(changed, SplitUnit.IMAGE)));
    }

    @Test
    void projectGroupProjectionRequiresEveryIncludedImageToHaveAGroup() {
        ProjectPlan project = project(plannedImage("image-a", null, ROI_A, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingProjectIndex.from(project, SplitUnit.PROJECT_GROUP));
    }

    @Test
    void splitReadinessExplainsInsufficientUnitsWithoutChangingTheSelection() {
        ProjectPlan oneImage = project(plannedImage("image-a", "Control", ROI_A, 0, 0));

        TrainingSplitReadiness image =
                TrainingSplitReadiness.evaluate(roiIndex(oneImage), SplitUnit.IMAGE, 20);
        assertFalse(image.ready());
        assertEquals(
                "Image validation requires at least two images. Choose ROI splitting or set"
                        + " Validation size to 0%.",
                image.guidance());
        assertFalse(image.offerGroupAssignment());
        assertTrue(TrainingSplitReadiness.evaluate(roiIndex(oneImage), SplitUnit.IMAGE, 0).ready());

        ProjectPlan twoRois =
                project(
                        plannedImage("image-a", "Control", ROI_A, 0, 0),
                        plannedImage("image-a", "Control", ROI_B, 0, 0));
        assertTrue(TrainingSplitReadiness.evaluate(roiIndex(twoRois), SplitUnit.ROI, 20).ready());
    }

    @Test
    void splitReadinessRefusesAProjectWhoseRoisTeachNoModelClass() {
        ProjectPlan taught =
                project(
                        plannedImage("image-a", "Control", ROI_A, 0, 0),
                        plannedImage("image-b", "Dose", ROI_B, 0, 0));
        assertTrue(TrainingSplitReadiness.hasUsedObjects(roiIndex(taught)));

        // Every ROI holds only objects that no model class is trained from.
        ProjectPlan untaught =
                new ProjectPlan(
                        taught.images().stream()
                                .map(TrainingProjectIndexTest::withoutUsedObjects)
                                .toList(),
                        taught.imageNames());
        assertFalse(TrainingSplitReadiness.hasUsedObjects(roiIndex(untaught)));
        for (SplitUnit unit : SplitUnit.values()) {
            TrainingSplitReadiness readiness =
                    TrainingSplitReadiness.evaluate(roiIndex(untaught), unit, 20);
            assertFalse(readiness.ready());
            assertEquals(
                    "No object inside a training ROI is set to a model class. Check Train as"
                            + " and the classes of your objects.",
                    readiness.guidance());
        }
    }

    @Test
    void trainingRowsMustHoldAnExampleOfEveryNewClass() {
        // QuPath class Myoid trains the model class "Myoid cell"; Sertoli trains its namesake.
        TrainingTask task =
                new TrainingTask(
                        "test-capability",
                        "field",
                        new ModelOutput(
                                "instance_segmentation_and_classification",
                                List.of(),
                                List.of(
                                        new ClassChannel(0, "background", null),
                                        new ClassChannel(1, "emitted", "Sertoli"),
                                        new ClassChannel(2, "emitted", "Myoid cell"))),
                        true,
                        0.5,
                        Map.of("Sertoli", "Sertoli", "Myoid", "Myoid cell"));
        ProjectPlan project =
                project(
                        withObjects(
                                plannedImage("image-a", "Control", ROI_A, 0, 0), task, "Sertoli"),
                        withObjects(
                                plannedImage("image-b", "Dose", ROI_B, 0, 0),
                                task,
                                "Sertoli",
                                "Myoid"));
        List<String> both = List.of("Sertoli", "Myoid cell");

        for (SplitUnit unit : SplitUnit.values()) {
            TrainingProjectIndex index = TrainingProjectIndex.from(project, unit);
            int sertoliOnly = rowWithImage(index, "image-a");
            int withMyoid = rowWithImage(index, "image-b");
            assertEquals(Set.of("Sertoli"), index.rows().get(sertoliOnly).taughtClasses());
            assertEquals(
                    Set.of("Sertoli", "Myoid cell"), index.rows().get(withMyoid).taughtClasses());

            TrainingAssignment everythingTrains =
                    TrainingAssignment.generate(index, 0, new Random(7));
            assertEquals("", everythingTrains.trainingExamplesProblem(both));

            // The only Myoid objects are in the row held out for validation.
            TrainingAssignment myoidHeldOut =
                    TrainingAssignment.generate(index, 20, new Random(7))
                            .move(List.of(sertoliOnly), TrainingAssignment.TRAINING)
                            .move(List.of(withMyoid), TrainingAssignment.VALIDATION);
            assertTrue(myoidHeldOut.trainingHasUsedObjects());
            assertEquals(List.of("Myoid cell"), myoidHeldOut.classesWithoutTrainingObjects(both));
            assertEquals(List.of(), myoidHeldOut.classesWithoutObjects(both));
            assertEquals(
                    "No object in the Training rows is set to Myoid cell, so the model cannot"
                            + " learn it. Move a row with such objects to Training or check Train as.",
                    myoidHeldOut.trainingExamplesProblem(both));
            // A class that keeps its learned weights needs no example.
            assertEquals("", myoidHeldOut.trainingExamplesProblem(List.of()));
            assertEquals(
                    List.of("Peritubular"),
                    myoidHeldOut.classesWithoutObjects(List.of("Sertoli", "Peritubular")));
        }
    }

    @Test
    void splitReadinessCountsImagesThatNeedProjectGroupsAtEveryValidationSize() {
        ProjectPlan project =
                project(
                        plannedImage("image-a", null, ROI_A, 0, 0),
                        plannedImage("image-b", "Dose", ROI_B, 0, 0));

        TrainingSplitReadiness readiness =
                TrainingSplitReadiness.evaluate(roiIndex(project), SplitUnit.PROJECT_GROUP, 20);

        assertFalse(readiness.ready());
        assertEquals(
                "1 image has no Project group. Assign groups in Project before generating this split.",
                readiness.guidance());
        assertTrue(readiness.offerGroupAssignment());
        TrainingSplitReadiness noValidation =
                TrainingSplitReadiness.evaluate(roiIndex(project), SplitUnit.PROJECT_GROUP, 0);
        assertFalse(noValidation.ready());
        assertEquals(readiness.guidance(), noValidation.guidance());
        assertTrue(noValidation.offerGroupAssignment());
    }

    @Test
    void projectGroupValidationRequiresTwoDistinctGroups() {
        ProjectPlan oneGroup =
                project(
                        plannedImage("image-a", "Control", ROI_A, 0, 0),
                        plannedImage("image-b", "Control", ROI_B, 0, 0));
        assertFalse(
                TrainingSplitReadiness.evaluate(roiIndex(oneGroup), SplitUnit.PROJECT_GROUP, 20)
                        .ready());

        ProjectPlan twoGroups =
                project(
                        plannedImage("image-a", "Control", ROI_A, 0, 0),
                        plannedImage("image-b", "Dose", ROI_B, 0, 0));
        assertTrue(
                TrainingSplitReadiness.evaluate(roiIndex(twoGroups), SplitUnit.PROJECT_GROUP, 20)
                        .ready());
    }

    private static ProjectPlan project(TrainingDatasetPackageWriter.PlannedImage... values) {
        Map<String, String> names =
                Map.of("image-a", "Slide A", "image-b", "Slide B", "image-c", "Slide C");
        List<TrainingDatasetPackageWriter.PlannedImage> images =
                java.util.Arrays.stream(values)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        value -> value.capture().projectEntryId(),
                                        value -> value,
                                        (left, right) -> merge(left, right)))
                        .values()
                        .stream()
                        .toList();
        Map<String, String> selectedNames =
                images.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        value -> value.capture().projectEntryId(),
                                        value -> names.get(value.capture().projectEntryId())));
        return new ProjectPlan(images, selectedNames);
    }

    private static int rowWithImage(TrainingProjectIndex index, String imageId) {
        return index.rows().stream()
                .filter(row -> row.imageIds().contains(imageId))
                .findFirst()
                .orElseThrow()
                .unitIndex();
    }

    /** The same image with one object of each given QuPath class in every ROI. */
    private static TrainingDatasetPackageWriter.PlannedImage withObjects(
            TrainingDatasetPackageWriter.PlannedImage image,
            TrainingTask task,
            String... qupathClasses) {
        var capture = image.capture();
        List<CapturedRoi> rois = new java.util.ArrayList<>();
        for (var roi : capture.rois()) {
            List<TrainingObject> objects = new java.util.ArrayList<>();
            for (String qupathClass : qupathClasses) {
                objects.add(
                        new TrainingObject(
                                new java.util.UUID(rois.size() + 1, objects.size() + 1).toString(),
                                "detection",
                                qupathClass,
                                roi.geometry()));
            }
            rois.add(
                    new CapturedRoi(
                            roi.sourceRoiUuid(),
                            roi.geometry(),
                            objects,
                            new LabelCounts(objects.size(), 0, 0)));
        }
        return new TrainingDatasetPackageWriter.PlannedImage(
                new CapturedImage(
                        capture.projectEntryId(),
                        capture.projectEntryIdDigest(),
                        capture.projectGroup(),
                        capture.sourceRevision(),
                        task,
                        rois,
                        Set.of(qupathClasses)),
                new TrainingImageSourcePlan(
                        capture.sourceRevision(), task, image.sourcePlan().rois()));
    }

    private static TrainingDatasetPackageWriter.PlannedImage withoutUsedObjects(
            TrainingDatasetPackageWriter.PlannedImage image) {
        var capture = image.capture();
        List<CapturedRoi> rois =
                capture.rois().stream()
                        .map(
                                roi ->
                                        new CapturedRoi(
                                                roi.sourceRoiUuid(),
                                                roi.geometry(),
                                                List.of(),
                                                new LabelCounts(0, 2, 5)))
                        .toList();
        return new TrainingDatasetPackageWriter.PlannedImage(
                new CapturedImage(
                        capture.projectEntryId(),
                        capture.projectEntryIdDigest(),
                        capture.projectGroup(),
                        capture.sourceRevision(),
                        capture.task(),
                        rois,
                        capture.annotationClasses()),
                image.sourcePlan());
    }

    private static TrainingDatasetPackageWriter.PlannedImage merge(
            TrainingDatasetPackageWriter.PlannedImage left,
            TrainingDatasetPackageWriter.PlannedImage right) {
        List<CapturedRoi> rois = new java.util.ArrayList<>(left.capture().rois());
        rois.addAll(right.capture().rois());
        rois.sort(java.util.Comparator.comparing(CapturedRoi::sourceRoiUuid));
        CapturedImage capture =
                new CapturedImage(
                        left.capture().projectEntryId(),
                        left.capture().projectEntryIdDigest(),
                        left.capture().projectGroup(),
                        left.capture().sourceRevision(),
                        left.capture().task(),
                        rois,
                        left.capture().annotationClasses());
        List<TrainingRoiSourcePlan> plans = new java.util.ArrayList<>(left.sourcePlan().rois());
        plans.addAll(right.sourcePlan().rois());
        plans.sort(java.util.Comparator.comparing(TrainingRoiSourcePlan::sourceRoiUuid));
        return new TrainingDatasetPackageWriter.PlannedImage(
                capture,
                new TrainingImageSourcePlan(capture.sourceRevision(), capture.task(), plans));
    }

    private static TrainingDatasetPackageWriter.PlannedImage plannedImage(
            String imageId, String group, String roiId, int noClass, int notUsed) {
        Geometry geometry = GEOMETRY_FACTORY.toGeometry(new Envelope(10, 30, 20, 40));
        TrainingObject object =
                new TrainingObject(
                        roiId.replace("1111", "4444"), "detection", "Leydig cell", geometry);
        CapturedRoi roi =
                new CapturedRoi(
                        roiId, geometry, List.of(object), new LabelCounts(1, noClass, notUsed));
        RasterInputCodec.ActionSourceRevision revision = sourceRevision(imageId);
        CapturedImage capture =
                new CapturedImage(
                        imageId,
                        CanonicalCodec.sha256(imageId.getBytes(StandardCharsets.UTF_8)),
                        Optional.ofNullable(group),
                        revision,
                        task(),
                        List.of(roi),
                        java.util.Set.of("Leydig cell"));
        var rect = new SourceIdentityCodec.IntRect(10, 20, 20, 20);
        var level =
                new PinnedSourceLevel(
                        0,
                        0,
                        new Dimensions(100, 100),
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0));
        var artifact =
                new DerivedArtifactPlan(
                        SourceContainer.PNG,
                        0,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        rect,
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, rect.x(), rect.y()),
                        10_000);
        TrainingRoiSourcePlan sourcePlan =
                new TrainingRoiSourcePlan(
                        roiId,
                        CanonicalGeometryCodec.canonicalDigest(geometry),
                        level,
                        rect,
                        artifact);
        return new TrainingDatasetPackageWriter.PlannedImage(
                capture,
                new TrainingImageSourcePlan(revision, capture.task(), List.of(sourcePlan)));
    }

    private static RasterInputCodec.ActionSourceRevision sourceRevision(String imageId) {
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "test-source",
                        "a".repeat(64),
                        Optional.empty(),
                        100,
                        100,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.5, 0.5));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        imageId,
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        "b".repeat(64)),
                source);
    }

    private static TrainingTask task() {
        return new TrainingTask(
                "test-capability",
                "field",
                new ModelOutput("instance_segmentation", List.of("Leydig cell"), List.of()),
                true,
                0.5,
                Map.of("Leydig cell", "Leydig cell"));
    }

    private static TrainingProjectIndex roiIndex(ProjectPlan project) {
        return TrainingProjectIndex.from(project, SplitUnit.ROI);
    }
}
