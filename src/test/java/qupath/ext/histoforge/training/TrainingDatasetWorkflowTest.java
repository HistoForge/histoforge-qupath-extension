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
import java.util.function.LongConsumer;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.BuildIdentityCodec.SoftwareBuildIdentity;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits;
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
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
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
                            context.builder(), uploader, temporaryDirectory.resolve("scratch"));
            var captured = context.builder().capture(context.project(), () -> false, ignored -> {});
            var assignment =
                    TrainingAssignment.generate(
                            TrainingProjectIndex.from(captured, SplitUnit.IMAGE),
                            0,
                            new java.util.Random(7));

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
            verify(context.lifecycle(), times(3))
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
