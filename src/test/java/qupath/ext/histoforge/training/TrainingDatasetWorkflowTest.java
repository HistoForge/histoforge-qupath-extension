package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.contract.BuildIdentityCodec.SoftwareBuildIdentity;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.RasterInputCodec.ActionSourceRevision;
import qupath.ext.histoforge.contract.RasterInputCodec.ImageRevisionToken;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec.ImageSourceIdentity;
import qupath.ext.histoforge.contract.SourceIdentityCodec.PixelCalibration;
import qupath.ext.histoforge.contract.SourceIdentityCodec.ScaleTranslate2D;
import qupath.ext.histoforge.contract.SourceViewGeometry.Dimensions;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingDatasetCodec;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingRoi;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
import qupath.ext.histoforge.contract.TrainingGeometryCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingSplitCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.pipeline.ImageLifecycle.ImageDataMaterialization;
import qupath.ext.histoforge.pipeline.ImageLifecycle.ReadOnlyImageCapture;
import qupath.ext.histoforge.roi.RoiManager;
import qupath.ext.histoforge.spatial.ImageSourceCapture.CapturedSource;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PreparedTrainingDataset;
import qupath.ext.histoforge.training.TrainingProjectIndex.SplitUnit;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class TrainingDatasetWorkflowTest {

    private static final String ENTRY_ID = "project-image-1";
    private static final String ROI_ID = "11111111-1111-4111-8111-111111111111";
    private static final String OBJECT_ID = "22222222-2222-4222-8222-222222222222";
    private static final String DATASET_ID = "33333333-3333-4333-8333-333333333333";
    private static final int IMAGE_SIDE = 16;
    private static final double MPP = 0.5;

    @TempDir Path temporaryDirectory;

    @Test
    void localExportAndCloudUploadPreserveOneProjectDatasetIdentity() throws Exception {
        try (Context context = context()) {
            AtomicReference<PreparedTrainingDataset> uploaded = new AtomicReference<>();
            TrainingDatasetUploader uploader = mock(TrainingDatasetUploader.class);
            doAnswer(
                            invocation -> {
                                PreparedTrainingDataset prepared = invocation.getArgument(0);
                                LongConsumer progress = invocation.getArgument(2);
                                uploaded.set(prepared);
                                progress.accept(prepared.archiveBytes());
                                return new TrainingDatasetRecord(
                                        DATASET_ID,
                                        prepared.manifest().datasetDigest(),
                                        prepared.archiveBytes(),
                                        context.capability().taskDisplayName(),
                                        prepared.manifest().images().size(),
                                        prepared.manifest().rois().size(),
                                        Instant.parse("2026-09-03T12:00:00Z"));
                            })
                    .when(uploader)
                    .upload(
                            any(PreparedTrainingDataset.class),
                            any(BooleanSupplier.class),
                            any(LongConsumer.class));

            TrainingDatasetWorkflow workflow =
                    new TrainingDatasetWorkflow(
                            context.builder(),
                            uploader,
                            temporaryDirectory.resolve("scratch"),
                            List.of());
            var preview =
                    TrainingProjectPreview.read(
                                    context.project(),
                                    context.lifecycle(),
                                    () -> false,
                                    ignored -> {})
                            .orElseThrow();
            var previewIndex =
                    preview.roiIndex(context.builder().task(), context.limits().dataset())
                            .forUnit(SplitUnit.IMAGE);
            var assignment = TrainingAssignment.generate(previewIndex, 0, new java.util.Random(7));

            var exported =
                    workflow.export(
                            temporaryDirectory.resolve("ley dig-training"),
                            context.project(),
                            assignment,
                            () -> false,
                            ignored -> {});
            assertTrue(Files.isRegularFile(exported.paths().archive()));
            assertCanonicalMembersMatchGolden(exported.paths().archive());
            assertTrue(Files.isRegularFile(exported.paths().splitSidecar()));
            var sidecar =
                    TrainingSplitCodec.parseSidecar(
                            CanonicalCodec.parseJson(
                                    Files.readAllBytes(exported.paths().splitSidecar())),
                            exported.dataset().manifest(),
                            context.limits());
            assertEquals(exported.dataset().manifest().datasetDigest(), sidecar.datasetDigest());
            assertEquals(exported.assignment().requirePlan(), sidecar.splitPlan());
            assertEquals(previewIndex.rows(), exported.assignment().projectIndex().rows());

            var sealed =
                    workflow.upload(
                            context.project(), exported.assignment(), () -> false, ignored -> {});

            assertEquals(
                    exported.dataset().manifest().datasetDigest(),
                    sealed.manifest().datasetDigest());
            assertEquals(sealed.manifest().datasetDigest(), sealed.record().datasetDigest());
            assertEquals(
                    sealed.manifest().datasetDigest(), uploaded.get().manifest().datasetDigest());
            assertFalse(Files.exists(uploaded.get().archivePath()));
            verify(context.lifecycle()).readProjectAnnotations(eq(context.entry()), any(), any());
            verify(context.lifecycle(), times(2))
                    .captureReadOnlyIf(
                            eq(context.entry()),
                            eq(ENTRY_ID),
                            eq(context.clientLimits()),
                            any(),
                            any(),
                            any());
            verify(context.lifecycle(), times(2))
                    .openStoredSourceSession(context.entry(), ENTRY_ID, context.sourceRevision());
        }
    }

    @Test
    void rejectedOverlapCanBeTrimmedInOneRetryWithoutRecapturing() throws Exception {
        try (Context context = context()) {
            var hierarchy = context.imageData().getHierarchy();
            hierarchy.removeObject(hierarchy.getDetectionObjects().iterator().next(), true);
            var larger = addOutline(context, 4, 4, 4.54, 5.54);
            var smaller = addOutline(context, 8.46, 9.46, 3, 3);
            var originalLargerOutline = larger.getROI().getGeometry();
            var originalSmallerOutline = smaller.getROI().getGeometry();
            double shared = originalLargerOutline.intersection(originalSmallerOutline).getArea();
            var assignment = overlapAssignment(context);
            var uploader = mock(TrainingDatasetUploader.class);
            var attempts = new AtomicInteger();
            var firstDigest = new AtomicReference<String>();
            doAnswer(
                            invocation -> {
                                PreparedTrainingDataset prepared = invocation.getArgument(0);
                                if (attempts.incrementAndGet() == 1) {
                                    firstDigest.set(prepared.manifest().datasetDigest());
                                    throw overlapRejection(TrainingDatasetCodec.roiGeometryPath(0));
                                }
                                assertEquals(2, attempts.get());
                                assertFalse(
                                        firstDigest
                                                .get()
                                                .equals(prepared.manifest().datasetDigest()));
                                try (var stream = Files.newInputStream(prepared.archivePath())) {
                                    var bytes =
                                            archiveMembers(stream)
                                                    .get(TrainingDatasetCodec.roiObjectsPath(0));
                                    var labels =
                                            TrainingGeometryCodec.parseObjectCollection(
                                                    CanonicalCodec.parseJson(bytes));
                                    assertEquals(2, labels.size());
                                    var fixed =
                                            labels.stream()
                                                    .filter(
                                                            item ->
                                                                    item.sourceObjectUuid()
                                                                            .equals(
                                                                                    larger.getID()
                                                                                            .toString()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    var kept =
                                            labels.stream()
                                                    .filter(
                                                            item ->
                                                                    item.sourceObjectUuid()
                                                                            .equals(
                                                                                    smaller.getID()
                                                                                            .toString()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    double removed =
                                            originalLargerOutline.getArea()
                                                    - fixed.geometry().getArea();
                                    assertTrue(removed >= shared && removed < shared + 1e-6);
                                    assertTrue(kept.geometry().equalsTopo(originalSmallerOutline));
                                    assertTrue(fixed.geometry().distance(kept.geometry()) > 0);
                                }
                                return new TrainingDatasetRecord(
                                        DATASET_ID,
                                        prepared.manifest().datasetDigest(),
                                        prepared.archiveBytes(),
                                        context.capability().taskDisplayName(),
                                        1,
                                        1,
                                        Instant.parse("2026-09-03T12:00:00Z"));
                            })
                    .when(uploader)
                    .upload(any(), any(), any());
            var confirmations = new AtomicInteger();
            var workflow =
                    new TrainingDatasetWorkflow(
                            context.builder(),
                            uploader,
                            temporaryDirectory,
                            List.of(),
                            (details, cancelled) -> {
                                assertEquals(1, attempts.get());
                                assertEquals(1, details.count());
                                assertEquals("slide-01.png", details.imageName());
                                confirmations.incrementAndGet();
                                return true;
                            });

            var sealed = workflow.upload(context.project(), assignment, () -> false, ignored -> {});

            assertEquals(1, confirmations.get());
            assertEquals(2, attempts.get());
            assertEquals(2, context.fullCaptures().get());
            assertEquals(assignment.requirePlan(), sealed.assignment().requirePlan());
            assertTrue(larger.getROI().getGeometry().equalsTopo(originalLargerOutline));
            assertTrue(smaller.getROI().getGeometry().equalsTopo(originalSmallerOutline));
            verify(context.entry(), never()).saveImageData(any());
        }
    }

    @Test
    void decliningTheTrimDoesNotRetryTheUpload() throws Exception {
        assertRejectedUploadStops(false, true);
    }

    @Test
    void aNestedOverlapDoesNotOfferATrim() throws Exception {
        assertRejectedUploadStops(true, true);
    }

    @Test
    void aRejectionWithoutARoiDoesNotOfferATrim() throws Exception {
        assertRejectedUploadStops(false, false);
    }

    @Test
    void onePlanCoversAnotherRoiAndEdgeOverlapOnAShiftedGrid() throws Exception {
        try (Context context = context()) {
            var hierarchy = context.imageData().getHierarchy();
            hierarchy.removeObjects(hierarchy.getAnnotationObjects(), true);
            hierarchy.removeObjects(hierarchy.getDetectionObjects(), true);
            addTrainingRoi(context, ROI_ID, 2.25);
            addTrainingRoi(context, DATASET_ID, 8.25);
            addOutline(context, 4, 3, 4.79, 2.79);
            addOutline(context, 8.71, 5.71, 3, 1.5);
            // This pair touches a pixel center on a context-shifted grid, not the admission grid.
            addOutline(context, 4, 9, 4.54, 2.54);
            addOutline(context, 8.46, 11.46, 3, 1.5);

            var project = context.builder().capture(context.project(), () -> false, ignored -> {});
            var plan =
                    TrainingOutlineOverlaps.prepare(
                                    project, new TrainingRoi(ROI_ID, 0, null), () -> false)
                            .orElseThrow();
            assertEquals(2, plan.review().count());
            assertEquals(8.75, plan.review().x());
            assertEquals(5.75, plan.review().y());
            for (var roi : plan.project().images().getFirst().capture().rois()) {
                assertEquals(2, roi.targetObjects().size());
                var first = roi.targetObjects().get(0).geometry();
                var second = roi.targetObjects().get(1).geometry();
                assertEquals(0, first.intersection(second).getArea());
            }
            addOutline(context, 9, 12, 1.4, 0.7);
            var unsafe = context.builder().capture(context.project(), () -> false, ignored -> {});
            assertTrue(
                    TrainingOutlineOverlaps.prepare(
                                    unsafe, new TrainingRoi(ROI_ID, 0, null), () -> false)
                            .isEmpty(),
                    "A repair plan must also reject a larger overlap outside the reported ROI.");
        }
    }

    @Test
    void aSmallTrimThatCouldCutAPixelBridgeIsDeclined() throws Exception {
        try (Context context = context()) {
            var hierarchy = context.imageData().getHierarchy();
            hierarchy.removeObject(hierarchy.getDetectionObjects().iterator().next(), true);
            var plane = ImagePlane.getDefaultPlane();
            var pathClass = PathClass.fromString("Leydig cell");
            var bridge =
                    ROIs.createPolygonROI(
                            new double[] {4, 8, 8, 9, 9, 13, 13, 9, 9, 8, 8, 4},
                            new double[] {3, 3, 7.45, 7.45, 3, 3, 11, 11, 7.65, 7.65, 11, 11},
                            plane);
            var bar =
                    ROIs.createPolygonROI(
                            new double[] {6, 11, 11, 8.54, 8.54, 8.46, 8.46, 6},
                            new double[] {2.2, 2.2, 2.9, 2.9, 7.54, 7.54, 2.9, 2.9},
                            plane);
            hierarchy.addObjects(
                    List.of(
                            PathObjects.createDetectionObject(bridge, pathClass),
                            PathObjects.createDetectionObject(bar, pathClass)));

            var project = context.builder().capture(context.project(), () -> false, ignored -> {});
            var outlines =
                    project.images().getFirst().capture().rois().getFirst().targetObjects().stream()
                            .map(TrainingGeometryCodec.TrainingObject::geometry)
                            .sorted(
                                    java.util.Comparator.comparingDouble(
                                            org.locationtech.jts.geom.Geometry::getArea))
                            .toList();
            var other = outlines.getFirst();
            var original = outlines.getLast();
            var cut = original.intersection(other).buffer(1e-6, 1);
            var trimmed = original.difference(cut);
            assertTrue(trimmed instanceof org.locationtech.jts.geom.Polygon);
            assertTrue(original.covers(trimmed) && trimmed.distance(other) > 5e-7);
            assertTrue(
                    TrainingOutlineOverlaps.prepare(
                                    project, new TrainingRoi(ROI_ID, 0, null), () -> false)
                            .isEmpty());
        }
    }

    private static void addTrainingRoi(Context context, String id, double top) {
        var roi =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(2.25, top, 11.5, 5.5, ImagePlane.getDefaultPlane()),
                        RoiManager.HISTOFORGE_ROI_CLASS);
        roi.setID(UUID.fromString(id));
        roi.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        context.imageData().getHierarchy().addObject(roi);
    }

    private void assertRejectedUploadStops(boolean nested, boolean roiRejection) throws Exception {
        try (Context context = context()) {
            if (nested) {
                addOutline(context, 6, 6, 2, 2);
            } else {
                var hierarchy = context.imageData().getHierarchy();
                hierarchy.removeObject(hierarchy.getDetectionObjects().iterator().next(), true);
                addOutline(context, 4, 4, 4.54, 5.54);
                addOutline(context, 8.46, 9.46, 3, 3);
            }
            var assignment = overlapAssignment(context);
            var uploader = mock(TrainingDatasetUploader.class);
            doThrow(
                            overlapRejection(
                                    roiRejection
                                            ? TrainingDatasetCodec.roiGeometryPath(0)
                                            : DATASET_ID))
                    .when(uploader)
                    .upload(any(), any(), any());
            var confirmations = new AtomicInteger();
            var workflow =
                    new TrainingDatasetWorkflow(
                            context.builder(),
                            uploader,
                            temporaryDirectory,
                            List.of(),
                            (details, cancelled) -> {
                                confirmations.incrementAndGet();
                                return false;
                            });
            boolean confirmable = !nested && roiRejection;
            Class<? extends Throwable> expected =
                    confirmable ? CancellationException.class : InferenceException.class;
            assertThrows(
                    expected,
                    () ->
                            workflow.upload(
                                    context.project(), assignment, () -> false, ignored -> {}));
            assertEquals(confirmable ? 1 : 0, confirmations.get());
            verify(uploader).upload(any(), any(), any());
            verify(context.lifecycle())
                    .openStoredSourceSession(context.entry(), ENTRY_ID, context.sourceRevision());
        }
    }

    private static InferenceException overlapRejection(String resource) {
        return new InferenceException(
                422,
                new GatewayErrorCodec.Error(
                        1,
                        GatewayErrorCodec.Code.VALIDATION_ERROR,
                        "Two training objects overlap near x=8.50, y=9.50 in the image.",
                        Optional.of(resource),
                        Optional.empty()));
    }

    private static PathObject addOutline(
            Context context, double x, double y, double width, double height) {
        var object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Leydig cell"));
        context.imageData().getHierarchy().addObject(object);
        return object;
    }

    private static TrainingAssignment overlapAssignment(Context context) throws IOException {
        var project = context.builder().capture(context.project(), () -> false, ignored -> {});
        return TrainingAssignment.generate(
                TrainingProjectIndex.from(project, SplitUnit.IMAGE), 0, new java.util.Random(7));
    }

    @Test
    void sealValidationErrorNamesTheCapturedImageAndKeepsGatewayDetails() throws Exception {
        try (Context context = context()) {
            String message = "Two training objects overlap near x=8.50, y=8.50 in the image.";
            var gatewayError =
                    new GatewayErrorCodec.Error(
                            1,
                            GatewayErrorCodec.Code.VALIDATION_ERROR,
                            message,
                            Optional.of("rois/0/roi.geojson"),
                            Optional.empty());
            var rejection = new InferenceException(422, gatewayError);
            TrainingDatasetUploader uploader = mock(TrainingDatasetUploader.class);
            when(uploader.upload(any(), any(), any())).thenThrow(rejection);
            Path scratch = temporaryDirectory.resolve("scratch");
            var workflow =
                    new TrainingDatasetWorkflow(context.builder(), uploader, scratch, List.of());
            var project = context.builder().capture(context.project(), () -> false, ignored -> {});
            var assignment =
                    TrainingAssignment.generate(
                            TrainingProjectIndex.from(project, SplitUnit.IMAGE),
                            0,
                            new java.util.Random(7));

            var error =
                    assertThrows(
                            InferenceException.class,
                            () ->
                                    workflow.upload(
                                            context.project(),
                                            assignment,
                                            () -> false,
                                            ignored -> {}));
            assertEquals("slide-01.png\n\n" + message, error.getMessage());
            assertEquals(422, error.statusCode());
            assertEquals(Optional.of(gatewayError), error.gatewayError());
            assertSame(rejection, error.getCause());
            try (var files = Files.list(scratch)) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void exportAndUploadRefuseTrainingRowsThatLostEveryExample() throws Exception {
        try (Context context = context()) {
            TrainingDatasetWorkflow workflow =
                    new TrainingDatasetWorkflow(
                            context.builder(),
                            mock(TrainingDatasetUploader.class),
                            temporaryDirectory.resolve("scratch"),
                            List.of());
            var captured = context.builder().capture(context.project(), () -> false, ignored -> {});
            var assignment =
                    TrainingAssignment.generate(
                            TrainingProjectIndex.from(captured, SplitUnit.IMAGE),
                            0,
                            new java.util.Random(7));
            assertTrue(assignment.trainingHasUsedObjects());

            // The last labelled object is deleted after the split was made from an earlier read.
            var hierarchy = context.imageData().getHierarchy();
            hierarchy.removeObjects(hierarchy.getDetectionObjects(), true);

            var export =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    workflow.export(
                                            temporaryDirectory.resolve("unlabelled"),
                                            context.project(),
                                            assignment,
                                            () -> false,
                                            ignored -> {}));
            assertEquals(TrainingAssignment.NO_TRAINING_EXAMPLES, export.getMessage());
            var upload =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    workflow.upload(
                                            context.project(),
                                            assignment,
                                            () -> false,
                                            ignored -> {}));
            assertEquals(TrainingAssignment.NO_TRAINING_EXAMPLES, upload.getMessage());
        }
    }

    @Test
    void exportAndUploadRefuseANewClassWithoutATrainingExample() throws Exception {
        try (Context context = context()) {
            // The trained model is to gain Myoid, but the dataset read finds only Leydig cells.
            TrainingDatasetWorkflow workflow =
                    new TrainingDatasetWorkflow(
                            context.builder(),
                            mock(TrainingDatasetUploader.class),
                            temporaryDirectory.resolve("scratch"),
                            List.of("Myoid"));
            var captured = context.builder().capture(context.project(), () -> false, ignored -> {});
            var assignment =
                    TrainingAssignment.generate(
                            TrainingProjectIndex.from(captured, SplitUnit.IMAGE),
                            0,
                            new java.util.Random(7));
            assertTrue(assignment.trainingHasUsedObjects());
            String refusal = TrainingAssignment.noTrainingExamplesOf(List.of("Myoid"));

            var export =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    workflow.export(
                                            temporaryDirectory.resolve("no-myoid"),
                                            context.project(),
                                            assignment,
                                            () -> false,
                                            ignored -> {}));
            assertEquals(refusal, export.getMessage());
            var upload =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    workflow.upload(
                                            context.project(),
                                            assignment,
                                            () -> false,
                                            ignored -> {}));
            assertEquals(refusal, upload.getMessage());
            assertFalse(Files.exists(temporaryDirectory.resolve("no-myoid.zip")));
        }
    }

    @Test
    void refreshWithoutTrainingRoisIsEmptyButExportCaptureStillRejectsIt() throws Exception {
        try (Context context = context()) {
            for (var object : context.imageData().getHierarchy().getAnnotationObjects()) {
                object.getMetadata().remove(HistoforgeKeys.ROI_TYPE);
            }
            assertTrue(
                    context.builder()
                            .captureIfRoisPresent(context.project(), () -> false, ignored -> {})
                            .isEmpty());
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    context.builder()
                                            .capture(
                                                    context.project(), () -> false, ignored -> {}));
            assertEquals(
                    TrainingProjectDatasetBuilder.NO_TRAINING_ROIS_GUIDANCE, failure.getMessage());
        }
    }

    @Test
    void emptyProjectRefreshDoesNotOpenAnImage() throws Exception {
        try (Context context = context()) {
            assertTrue(
                    context.builder()
                            .captureIfRoisPresent(
                                    projectWithEntries(List.of()), () -> false, ignored -> {})
                            .isEmpty());
            org.mockito.Mockito.verifyNoInteractions(context.lifecycle());
        }
    }

    @Test
    void oversizedRoiFailsBeforeFullCaptureAndPreservesTheAdmissionMessage() throws Exception {
        try (Context context = context()) {
            int limit = Math.toIntExact(context.limits().dataset().maxObjectsPerRoi());
            for (int i = 0; i < limit; i++) {
                context.imageData()
                        .getHierarchy()
                        .getRootObject()
                        .addChildObject(
                                PathObjects.createDetectionObject(
                                        ROIs.createRectangleROI(
                                                5, 5, 1, 1, ImagePlane.getDefaultPlane()),
                                        PathClass.fromString("Leydig cell")));
            }
            var failure =
                    assertThrows(
                            TrainingProjectCapture.RoiObjectLimitException.class,
                            () ->
                                    context.builder()
                                            .capture(
                                                    context.project(), () -> false, ignored -> {}));
            assertTrue(
                    failure.getMessage()
                            .startsWith(
                                    String.format(
                                            java.util.Locale.ROOT,
                                            "Training ROI contains %,d target objects; the limit is %,d per ROI.",
                                            limit + 1,
                                            limit)));
            assertTrue(failure.getMessage().contains(ROI_ID));
            assertEquals(0, context.fullCaptures().get());
            verify(context.entry(), never()).saveImageData(any());
            verify(context.lifecycle(), never()).openStoredSourceSession(any(), any(), any());
        }
    }

    @Test
    void acceptedRoiStillPerformsFullCapture() throws Exception {
        try (Context context = context()) {
            var plan = context.builder().capture(context.project(), () -> false, ignored -> {});
            assertEquals(1, context.fullCaptures().get());
            assertEquals(
                    context.sourceRevision(), plan.images().getFirst().capture().sourceRevision());
            assertEquals(
                    OBJECT_ID,
                    plan.images()
                            .getFirst()
                            .capture()
                            .rois()
                            .getFirst()
                            .targetObjects()
                            .getFirst()
                            .sourceObjectUuid());
        }
    }

    @Test
    void captureNamesUnreadableImageAndStopsBeforeLaterImages() throws Exception {
        try (Context context = context()) {
            ProjectImageEntry<BufferedImage> broken =
                    projectEntry("project-image-2", "unavailable-slide.svs");
            ProjectImageEntry<BufferedImage> later =
                    projectEntry("project-image-3", "later-slide.svs");
            Project<BufferedImage> project =
                    projectWithEntries(List.of(context.entry(), broken, later));
            RuntimeException original =
                    new RuntimeException(
                            "Failed to load ImageServer",
                            new IOException("cloud source unavailable"));
            doThrow(original)
                    .when(context.lifecycle())
                    .captureReadOnlyIf(
                            eq(broken),
                            eq("project-image-2"),
                            eq(context.clientLimits()),
                            any(),
                            any(),
                            any());

            IllegalStateException reported =
                    assertThrows(
                            IllegalStateException.class,
                            () -> context.builder().capture(project, () -> false, ignored -> {}));

            assertEquals(
                    "Could not read training image \"unavailable-slide.svs\": Failed to load ImageServer",
                    reported.getMessage());
            assertSame(original, reported.getCause());
            verify(context.lifecycle(), never())
                    .captureReadOnlyIf(
                            eq(later),
                            eq("project-image-3"),
                            eq(context.clientLimits()),
                            any(),
                            any(),
                            any());
        }
    }

    @Test
    void captureNamesCheckedImageReadFailure() throws Exception {
        try (Context context = context()) {
            ProjectImageEntry<BufferedImage> broken =
                    projectEntry("project-image-2", "unavailable-slide.svs");
            Project<BufferedImage> project = projectWithEntries(List.of(broken));
            IOException original = new IOException("cloud source unavailable");
            doThrow(original)
                    .when(context.lifecycle())
                    .captureReadOnlyIf(
                            eq(broken),
                            eq("project-image-2"),
                            eq(context.clientLimits()),
                            any(),
                            any(),
                            any());

            IOException reported =
                    assertThrows(
                            IOException.class,
                            () -> context.builder().capture(project, () -> false, ignored -> {}));

            assertEquals(
                    "Could not read training image \"unavailable-slide.svs\": cloud source unavailable",
                    reported.getMessage());
            assertSame(original, reported.getCause());
        }
    }

    @Test
    void capturePreservesCancellation() throws Exception {
        try (Context context = context()) {
            ProjectImageEntry<BufferedImage> entry =
                    projectEntry("project-image-2", "cancelled-slide.svs");
            Project<BufferedImage> project = projectWithEntries(List.of(entry));
            CancellationException original = new CancellationException("cancelled by user");
            doThrow(original)
                    .when(context.lifecycle())
                    .captureReadOnlyIf(
                            eq(entry),
                            eq("project-image-2"),
                            eq(context.clientLimits()),
                            any(),
                            any(),
                            any());

            CancellationException reported =
                    assertThrows(
                            CancellationException.class,
                            () -> context.builder().capture(project, () -> false, ignored -> {}));

            assertSame(original, reported);
        }
    }

    @Test
    void writeNamesImageThatCannotBeReopened() throws Exception {
        try (Context context = context()) {
            var plan = context.builder().capture(context.project(), () -> false, ignored -> {});
            RuntimeException original =
                    new RuntimeException(
                            "Failed to load ImageServer",
                            new IOException("cloud source became unavailable"));
            doThrow(original)
                    .when(context.lifecycle())
                    .openStoredSourceSession(context.entry(), ENTRY_ID, context.sourceRevision());

            IllegalStateException reported =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    context.builder()
                                            .write(
                                                    temporaryDirectory.resolve("dataset.zip"),
                                                    context.project(),
                                                    plan,
                                                    () -> false,
                                                    ignored -> {}));

            assertEquals(
                    "Could not read training image \"slide-01.png\": Failed to load ImageServer",
                    reported.getMessage());
            assertSame(original, reported.getCause());
        }
    }

    private Context context() throws Exception {
        JsonObject fixture = trainingFixture();
        TrainingApiLimits limits = TrainingLimitsCodec.parse(fixture.get("trainingApiLimits"));
        ModelTrainingCapability capability =
                TrainingCapabilityCodec.parseCapability(
                        fixture.getAsJsonObject("capability").get("value"),
                        limits.report().maxTextBytes());
        TrainingTask task =
                TrainingCapabilityCodec.taskFromCapability(
                        capability,
                        new ModelOutput("instance_segmentation", List.of("Leydig cell"), List.of()),
                        MPP,
                        limits.report().maxTextBytes());
        ClientExecutionLimits clientLimits =
                new ClientExecutionLimits(
                        1_000_000,
                        10_000_000,
                        10_000_000,
                        100_000_000,
                        10_000_000,
                        1_000_000,
                        10_000_000,
                        1024L * 1024 * 1024);

        TestImageServer server = new TestImageServer();
        ImageSourceIdentity sourceIdentity =
                new ImageSourceIdentity(
                        "test.rgb",
                        "a".repeat(64),
                        Optional.empty(),
                        IMAGE_SIDE,
                        IMAGE_SIDE,
                        1,
                        1,
                        new PixelCalibration(MPP, MPP));
        ActionSourceRevision sourceRevision =
                new ActionSourceRevision(
                        new ImageRevisionToken(
                                ENTRY_ID,
                                SourceIdentityCodec.imageSourceIdentityDigest(sourceIdentity),
                                "b".repeat(64)),
                        sourceIdentity);
        SourceLevel sourceLevel =
                new SourceLevel(
                        0,
                        new Dimensions(IMAGE_SIDE, IMAGE_SIDE),
                        new ScaleTranslate2D(1, 1, 0, 0));
        CapturedSource capturedSource =
                new CapturedSource(
                        sourceIdentity,
                        0,
                        new ScaleTranslate2D(1, 1, 0, 0),
                        List.of(sourceLevel),
                        expected -> assertEquals(sourceIdentity, expected));

        ImageData<BufferedImage> imageData = new ImageData<>(server, ImageType.UNSET);
        var roi =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(2, 2, 12, 12, ImagePlane.getDefaultPlane()),
                        RoiManager.HISTOFORGE_ROI_CLASS);
        roi.setID(UUID.fromString(ROI_ID));
        roi.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        var object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(5, 5, 4, 5, ImagePlane.getDefaultPlane()),
                        PathClass.fromString(task.targetClasses().getFirst()));
        object.setID(UUID.fromString(OBJECT_ID));
        imageData.getHierarchy().addObjects(List.of(roi, object));

        ProjectImageEntry<BufferedImage> entry = projectEntry(ENTRY_ID, "slide-01.png");
        Project<BufferedImage> project = projectWithEntries(List.of(entry));

        ImageLifecycle lifecycle = mock(ImageLifecycle.class);
        AtomicInteger fullCaptures = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            Runnable checkRunning = invocation.getArgument(1);
                            checkRunning.run();
                            Function<ImageData<BufferedImage>, ?> operation =
                                    invocation.getArgument(2);
                            return operation.apply(imageData);
                        })
                .when(lifecycle)
                .readProjectAnnotations(eq(entry), any(), any());
        doAnswer(
                        invocation -> {
                            ImageLifecycle.ReadOnlyCaptureOperation<?> operation =
                                    invocation.getArgument(5);
                            Runnable checkRunning = invocation.getArgument(4);
                            java.util.function.Predicate<ImageData<BufferedImage>> condition =
                                    invocation.getArgument(3);
                            checkRunning.run();
                            if (!condition.test(imageData)) return Optional.empty();
                            fullCaptures.incrementAndGet();
                            Object value =
                                    operation.capture(imageData, sourceRevision, capturedSource);
                            return Optional.of(
                                    new ReadOnlyImageCapture<>(
                                            ImageDataMaterialization.STORED_QPDATA,
                                            sourceRevision,
                                            value));
                        })
                .when(lifecycle)
                .captureReadOnlyIf(eq(entry), eq(ENTRY_ID), eq(clientLimits), any(), any(), any());
        ImageLifecycle.ReadOnlySourceSession sourceSession =
                mock(ImageLifecycle.ReadOnlySourceSession.class);
        when(sourceSession.server()).thenReturn(server);
        when(sourceSession.sourcePyramid())
                .thenReturn(capturedSource.bind(sourceRevision.imageDataRevision()));
        when(sourceSession.unchangedOriginalPath()).thenReturn(Optional.empty());
        when(sourceSession.sourceRevisionVerifier()).thenReturn(capturedSource.revisionVerifier());
        when(lifecycle.openStoredSourceSession(entry, ENTRY_ID, sourceRevision))
                .thenReturn(sourceSession);

        SoftwareBuildIdentity build =
                new SoftwareBuildIdentity("test", "c".repeat(64), "d".repeat(64));
        long sourceLimit = 1024L * 1024 * 1024;
        TrainingProjectDatasetBuilder builder =
                new TrainingProjectDatasetBuilder(
                        lifecycle,
                        task,
                        capability,
                        limits,
                        clientLimits,
                        new SourcePlanningLimits(10_000_000, sourceLimit, sourceLimit),
                        new SourceArtifactWriter(
                                build,
                                TrainingDatasetUploader.CHUNK_SIZE_BYTES,
                                1,
                                sourceLimit,
                                sourceLimit));
        return new Context(
                project,
                entry,
                lifecycle,
                imageData,
                sourceRevision,
                clientLimits,
                capability,
                limits,
                builder,
                fullCaptures);
    }

    private static ProjectImageEntry<BufferedImage> projectEntry(String id, String name) {
        @SuppressWarnings("unchecked")
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        when(entry.getID()).thenReturn(id);
        when(entry.getImageName()).thenReturn(name);
        when(entry.getMetadata()).thenReturn(Map.of(HistoforgeKeys.GROUP, "Control"));
        return entry;
    }

    private static Project<BufferedImage> projectWithEntries(
            List<ProjectImageEntry<BufferedImage>> entries) {
        @SuppressWarnings("unchecked")
        Project<BufferedImage> project = mock(Project.class);
        when(project.getImageList()).thenReturn(entries);
        return project;
    }

    private static JsonObject trainingFixture() throws IOException {
        try (var input =
                TrainingDatasetWorkflowTest.class.getResourceAsStream(
                        "/contracts/training-datasets-v1.json")) {
            if (input == null) {
                throw new IOException("missing training-datasets-v1.json");
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static void assertCanonicalMembersMatchGolden(Path archive) throws IOException {
        Map<String, byte[]> actual;
        try (var input = Files.newInputStream(archive)) {
            actual = archiveMembers(input);
        }
        Map<String, byte[]> expected;
        try (var input =
                TrainingDatasetWorkflowTest.class.getResourceAsStream(
                        "/contracts/project-training-dataset-v1.zip.b64")) {
            if (input == null) {
                throw new IOException("missing project-training-dataset-v1.zip.b64");
            }
            expected =
                    archiveMembers(
                            new ByteArrayInputStream(
                                    Base64.getMimeDecoder().decode(input.readAllBytes())));
        }
        assertEquals(expected.keySet(), actual.keySet());
        // Keep the shared schema-1 archive unchanged for retained-dataset compatibility.
        // New captures use schema 2; only the version and its bound digest differ. All source,
        // origin, ROI and object members must still match the original bytes exactly.
        var currentManifest =
                CanonicalCodec.parseJson(expected.get("dataset.json")).getAsJsonObject();
        assertEquals(1, currentManifest.get("schema_version").getAsInt());
        assertEquals(
                "f8f961a3bd1e3693bc2922a2bed4155ee336a2989b5f0429c5f3f1aadcf49674",
                currentManifest.get("dataset_digest").getAsString());
        currentManifest.addProperty("schema_version", 2);
        currentManifest.addProperty(
                "dataset_digest",
                "bb82f421c844d38d401ec8ca03fe04cd5fca3aedbf09a3a15d437a0e82a9faf9");
        expected.put("dataset.json", CanonicalCodec.canonicalBytes(currentManifest));
        for (String path : expected.keySet()) {
            assertArrayEquals(
                    expected.get(path),
                    actual.get(path),
                    () ->
                            path.endsWith(".json")
                                    ? path
                                            + "\nexpected: "
                                            + new String(expected.get(path), StandardCharsets.UTF_8)
                                            + "\nactual: "
                                            + new String(actual.get(path), StandardCharsets.UTF_8)
                                    : path);
        }
    }

    private static Map<String, byte[]> archiveMembers(InputStream input) throws IOException {
        Map<String, byte[]> members = new TreeMap<>();
        try (var archive = new ZipInputStream(input)) {
            for (var entry = archive.getNextEntry();
                    entry != null;
                    entry = archive.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (members.put(entry.getName(), archive.readAllBytes()) != null) {
                    throw new IOException("duplicate training package member: " + entry.getName());
                }
            }
        }
        return members;
    }

    private record Context(
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> entry,
            ImageLifecycle lifecycle,
            ImageData<BufferedImage> imageData,
            ActionSourceRevision sourceRevision,
            ClientExecutionLimits clientLimits,
            ModelTrainingCapability capability,
            TrainingApiLimits limits,
            TrainingProjectDatasetBuilder builder,
            AtomicInteger fullCaptures)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            imageData.close();
        }
    }

    private static final class TestImageServer extends AbstractTileableImageServer {

        private final ImageServerMetadata metadata =
                new ImageServerMetadata.Builder()
                        .width(IMAGE_SIDE)
                        .height(IMAGE_SIDE)
                        .sizeZ(1)
                        .sizeT(1)
                        .pixelType(PixelType.UINT8)
                        .channels(ImageChannel.getDefaultRGBChannels())
                        .rgb(true)
                        .pixelSizeMicrons(MPP, MPP)
                        .preferredTileSize(128, 128)
                        .levelsFromDownsamples(1.0)
                        .build();

        @Override
        protected BufferedImage readTile(TileRequest request) {
            BufferedImage image =
                    new BufferedImage(
                            request.getTileWidth(),
                            request.getTileHeight(),
                            BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int sourceX = request.getTileX() + x;
                    int sourceY = request.getTileY() + y;
                    image.setRGB(
                            x,
                            y,
                            ((sourceX & 0xff) << 16)
                                    | ((sourceY & 0xff) << 8)
                                    | ((sourceX + sourceY) & 0xff));
                }
            }
            return image;
        }

        @Override
        public ImageServerMetadata getOriginalMetadata() {
            return metadata;
        }

        @Override
        protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
            return null;
        }

        @Override
        protected String createID() {
            return "histoforge-training-workflow-test";
        }

        @Override
        public String getServerType() {
            return "HistoForge training workflow test source";
        }

        @Override
        public Collection<URI> getURIs() {
            return List.of();
        }
    }
}
