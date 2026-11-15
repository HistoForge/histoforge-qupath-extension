package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.readerSettings;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.contract.BuildIdentityCodec.SoftwareBuildIdentity;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.OriginalReaderSettingsCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SlideFileSetCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.DerivedRegion;
import qupath.ext.histoforge.contract.SourceArtifactCodec.OriginalWsi;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceLevelRequirement;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry.Dimensions;
import qupath.ext.histoforge.contract.TrainingDatasetCodec;
import qupath.ext.histoforge.contract.TrainingGeometryCodec.TrainingObject;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingDatasetLimits;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingDatasetTransferLimits;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingReportLimits;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.MaterializedSourceArtifact;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingImageSourcePlan;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingRoiSourcePlan;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PlannedImage;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedImage;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedRoi;
import qupath.ext.histoforge.training.TrainingProjectCapture.LabelCounts;

class TrainingDatasetPackageWriterTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final String ROI_A = "11111111-1111-4111-8111-111111111111";
    private static final String ROI_B = "22222222-2222-4222-8222-222222222222";
    private static final String OBJECT_A = "33333333-3333-4333-8333-333333333333";
    private static final String OBJECT_B = "44444444-4444-4444-8444-444444444444";
    private static final SoftwareBuildIdentity WRITER_BUILD =
            new SoftwareBuildIdentity("test", "a".repeat(64), "b".repeat(64));
    private static final SourceIdentityCodec.ImageSourceIdentity SOURCE = sourceIdentity();
    private static final PinnedSourceLevel LEVEL =
            new PinnedSourceLevel(
                    0,
                    0,
                    new Dimensions(1_000, 800),
                    new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0));

    @TempDir Path temporaryDirectory;

    @Test
    void writesCanonicalDerivedPackageIndependentOfInputImageOrder() throws Exception {
        PlannedImage imageA =
                derivedImage("entry-a", "Control", ROI_A, OBJECT_A, rectangle(10, 20, 80, 90));
        PlannedImage imageB =
                derivedImage("entry-b", "Dose", ROI_B, OBJECT_B, rectangle(100, 120, 190, 210));
        var factory = derivedSessions();

        var first =
                TrainingDatasetPackageWriter.write(
                        temporaryDirectory.resolve("first.zip"),
                        List.of(imageB, imageA),
                        limits(),
                        factory);
        var second =
                TrainingDatasetPackageWriter.write(
                        temporaryDirectory.resolve("second.zip"),
                        List.of(imageA, imageB),
                        limits(),
                        factory);

        assertEquals(first.manifest(), second.manifest());
        assertEquals(TrainingDatasetCodec.SCHEMA_VERSION, first.manifest().schemaVersion());
        assertEquals(first.manifest().datasetDigest(), second.manifest().datasetDigest());
        Map<String, byte[]> firstMembers = archiveMembers(first.archivePath());
        Map<String, byte[]> secondMembers = archiveMembers(second.archivePath());
        assertEquals(firstMembers.keySet(), secondMembers.keySet());
        firstMembers.forEach(
                (path, bytes) -> assertArrayEquals(bytes, secondMembers.get(path), path));

        assertEquals(
                List.copyOf(firstMembers.keySet()),
                firstMembers.keySet().stream().sorted().toList());
        var parsed =
                TrainingDatasetCodec.parseManifest(
                        JsonParser.parseString(
                                new String(
                                        firstMembers.get("dataset.json"), StandardCharsets.UTF_8)),
                        limits());
        assertEquals(first.manifest(), parsed);
        assertEquals(List.of("Control", "Dose"), parsed.projectGroups());
        assertEquals(2, parsed.images().size());
        assertEquals(2, parsed.rois().size());
        assertEquals(2, parsed.artifacts().size());
        assertEquals(9, firstMembers.size());
        parsed.files()
                .forEach(
                        file -> {
                            byte[] bytes = firstMembers.get(file.path());
                            assertEquals(file.sizeBytes(), bytes.length, file.path());
                            assertEquals(file.sha256(), CanonicalCodec.sha256(bytes), file.path());
                        });
        assertEquals(Files.size(first.archivePath()), first.archiveBytes());
        assertEquals(sha256(Files.readAllBytes(first.archivePath())), first.archiveBytesDigest());
    }

    @Test
    void omitsRawProjectEntryIdentityFromStructuredPackageMembers() throws Exception {
        String projectEntryId =
                "/Users/pathologist/Patient Name/project.qpdata?access_token=secret";
        PlannedImage image =
                derivedImage(projectEntryId, "Control", ROI_A, OBJECT_A, rectangle(10, 20, 80, 90));

        var prepared =
                TrainingDatasetPackageWriter.write(
                        temporaryDirectory.resolve("private-source.zip"),
                        List.of(image),
                        limits(),
                        derivedSessions());
        Map<String, byte[]> members = archiveMembers(prepared.archivePath());
        String structuredContent =
                members.entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getKey().endsWith(".json")
                                                || entry.getKey().endsWith(".geojson"))
                        .map(entry -> new String(entry.getValue(), StandardCharsets.UTF_8))
                        .collect(java.util.stream.Collectors.joining());

        assertFalse(structuredContent.contains(projectEntryId));
        assertFalse(structuredContent.contains("Patient Name"));
        assertFalse(structuredContent.contains("access_token"));
        assertFalse(structuredContent.contains("secret"));
        assertEquals(
                CanonicalCodec.sha256(projectEntryId.getBytes(StandardCharsets.UTF_8)),
                prepared.manifest().images().getFirst().projectEntryIdDigest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unchanged", "entry", "settings", "fileset", "members"})
    void writesOneOriginalArtifactWithFrozenReaderAndDistinctRoiEnvelopes(String mutation)
            throws Exception {
        byte[] sourceBytes = "unchanged-original-wsi".getBytes(StandardCharsets.UTF_8);
        CapturedImage capture =
                capturedImage(
                        "entry-original",
                        "Control",
                        List.of(
                                capturedRoi(ROI_A, OBJECT_A, rectangle(10, 20, 80, 90)),
                                capturedRoi(ROI_B, OBJECT_B, rectangle(100, 120, 190, 210))));
        var required = List.of(new SourceLevelRequirement(0, 0, 1_000, 800));
        var original =
                new OriginalArtifactPlan(
                        sourceBytes.length,
                        0,
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0),
                        required,
                        SourceContainer.SLIDE_FILE,
                        "slide.tif",
                        readerSettings(SOURCE, 1));
        var plan =
                new TrainingImageSourcePlan(
                        capture.sourceRevision(),
                        capture.task(),
                        List.of(
                                sourcePlan(
                                        capture.rois().get(0),
                                        new SourceIdentityCodec.IntRect(10, 20, 70, 70),
                                        original),
                                sourcePlan(
                                        capture.rois().get(1),
                                        new SourceIdentityCodec.IntRect(100, 120, 90, 90),
                                        original)));
        AtomicInteger writes = new AtomicInteger();
        var settings = (OriginalReaderSettingsCodec.OpenSlide) original.readerSettings();
        var actualSettings =
                "settings".equals(mutation)
                        ? new OriginalReaderSettingsCodec.OpenSlide(
                                settings.levels(),
                                settings.cropX(),
                                settings.cropY(),
                                settings.backgroundRgb(),
                                settings.tileWidth() + 1,
                                settings.tileHeight())
                        : settings;

        java.util.concurrent.Callable<TrainingDatasetPackageWriter.PreparedTrainingDataset> write =
                () ->
                        TrainingDatasetPackageWriter.write(
                                temporaryDirectory.resolve("original.zip"),
                                List.of(new PlannedImage(capture, plan)),
                                limits(),
                                ignored ->
                                        session(
                                                (roi, destination) -> {
                                                    writes.incrementAndGet();
                                                    Files.write(destination, sourceBytes);
                                                    return new MaterializedSourceArtifact(
                                                            destination,
                                                            "fileset".equals(mutation)
                                                                    ? SourceContainer.SLIDE_FILE_SET
                                                                    : SourceContainer.SLIDE_FILE,
                                                            sourceBytes.length,
                                                            new OriginalWsi(
                                                                    SOURCE,
                                                                    0,
                                                                    new SourceIdentityCodec
                                                                            .ScaleTranslate2D(
                                                                            1, 1, 0, 0),
                                                                    "entry".equals(mutation)
                                                                            ? "other.tif"
                                                                            : original.entryPath(),
                                                                    actualSettings,
                                                                    "members".equals(mutation)
                                                                            ? List.of(
                                                                                    new SlideFileSetCodec
                                                                                            .Member(
                                                                                            original
                                                                                                    .entryPath(),
                                                                                            sourceBytes
                                                                                                    .length,
                                                                                            sha256(
                                                                                                    sourceBytes)))
                                                                            : List.of()),
                                                            required,
                                                            sha256(sourceBytes));
                                                }));

        if (!"unchanged".equals(mutation)) {
            assertThrows(IllegalArgumentException.class, write::call);
            return;
        }
        var prepared = write.call();
        assertEquals(1, writes.get());
        assertEquals(TrainingDatasetCodec.SCHEMA_VERSION, prepared.manifest().schemaVersion());
        assertEquals(1, prepared.manifest().artifacts().size());
        assertEquals("original", prepared.manifest().artifacts().getFirst().representation());
        assertEquals(0, prepared.manifest().rois().get(0).sourceEnvelope().artifactIndex());
        assertEquals(0, prepared.manifest().rois().get(1).sourceEnvelope().artifactIndex());
        assertFalse(
                prepared.manifest()
                        .rois()
                        .get(0)
                        .sourceEnvelope()
                        .pixelRect()
                        .equals(prepared.manifest().rois().get(1).sourceEnvelope().pixelRect()));
        assertArrayEquals(
                sourceBytes, archiveMembers(prepared.archivePath()).get("artifacts/0/source.tif"));
        var storedOrigin =
                JsonParser.parseString(
                                new String(
                                        archiveMembers(prepared.archivePath())
                                                .get("artifacts/0/origin.json"),
                                        StandardCharsets.UTF_8))
                        .getAsJsonObject();
        assertEquals("slide.tif", storedOrigin.get("entry_path").getAsString());
        assertEquals(
                OriginalReaderSettingsCodec.toJson(settings), storedOrigin.get("reader_settings"));
    }

    @Test
    void rejectsMaterializerByteDriftWithoutReplacingPriorArchive() throws Exception {
        Path destination = temporaryDirectory.resolve("dataset.zip");
        byte[] prior = "prior".getBytes(StandardCharsets.UTF_8);
        Files.write(destination, prior);
        PlannedImage image =
                derivedImage("entry-a", "Control", ROI_A, OBJECT_A, rectangle(10, 20, 80, 90));

        assertThrows(
                IOException.class,
                () ->
                        TrainingDatasetPackageWriter.write(
                                destination,
                                List.of(image),
                                limits(),
                                ignored ->
                                        session(
                                                (roi, path) -> {
                                                    byte[] bytes = {1, 2, 3};
                                                    Files.write(path, bytes);
                                                    return derivedArtifact(
                                                            roi, path, bytes, "0".repeat(64));
                                                })));

        assertArrayEquals(prior, Files.readAllBytes(destination));
        try (var children = Files.list(temporaryDirectory)) {
            assertEquals(List.of(destination), children.sorted().toList());
        }
    }

    @Test
    void rejectsMismatchedSourceRevisionTaskAndRoiGeometryBeforePixelWork() {
        PlannedImage image =
                derivedImage("entry-a", "Control", ROI_A, OBJECT_A, rectangle(10, 20, 80, 90));
        TrainingRoiSourcePlan roiPlan = image.sourcePlan().rois().getFirst();

        var wrongRevision =
                new TrainingImageSourcePlan(
                        sourceRevision("entry-b"), image.capture().task(), List.of(roiPlan));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlannedImage(image.capture(), wrongRevision));

        TrainingTask wrongTask =
                new TrainingTask(
                        "test-capability",
                        "field",
                        new ModelOutput("instance_segmentation", List.of("Leydig cell"), List.of()),
                        false,
                        0.5,
                        Map.of("Leydig cell", "Leydig cell"));
        var wrongTaskPlan =
                new TrainingImageSourcePlan(
                        image.capture().sourceRevision(), wrongTask, List.of(roiPlan));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlannedImage(image.capture(), wrongTaskPlan));

        var wrongGeometry =
                new TrainingRoiSourcePlan(
                        roiPlan.sourceRoiUuid(),
                        "f".repeat(64),
                        roiPlan.sourceLevel(),
                        roiPlan.sourceEnvelopeAtLevel(),
                        roiPlan.artifact());
        var wrongGeometryPlan =
                new TrainingImageSourcePlan(
                        image.capture().sourceRevision(),
                        image.capture().task(),
                        List.of(wrongGeometry));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlannedImage(image.capture(), wrongGeometryPlan));
    }

    @Test
    void rejectsMaterializedOriginThatDiffersFromTheFrozenSourcePlan() {
        PlannedImage image =
                derivedImage("entry-a", "Control", ROI_A, OBJECT_A, rectangle(10, 20, 80, 90));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingDatasetPackageWriter.write(
                                temporaryDirectory.resolve("wrong-origin.zip"),
                                List.of(image),
                                limits(),
                                ignored ->
                                        session(
                                                (roi, destination) -> {
                                                    byte[] bytes = {1, 2, 3};
                                                    Files.write(destination, bytes);
                                                    DerivedArtifactPlan expected =
                                                            (DerivedArtifactPlan) roi.artifact();
                                                    var wrongRect =
                                                            new SourceIdentityCodec.IntRect(
                                                                    expected.sourceRectAtLevel().x()
                                                                            + 1,
                                                                    expected.sourceRectAtLevel()
                                                                            .y(),
                                                                    expected.sourceRectAtLevel()
                                                                            .width(),
                                                                    expected.sourceRectAtLevel()
                                                                            .height());
                                                    return new MaterializedSourceArtifact(
                                                            destination,
                                                            expected.container(),
                                                            bytes.length,
                                                            new DerivedRegion(
                                                                    SOURCE,
                                                                    expected
                                                                            .sourceResolutionLevel(),
                                                                    expected.plane(),
                                                                    wrongRect,
                                                                    expected
                                                                            .artifactPixelsToSlide(),
                                                                    WRITER_BUILD),
                                                            List.of(
                                                                    new SourceLevelRequirement(
                                                                            0,
                                                                            0,
                                                                            expected.sourceRectAtLevel()
                                                                                    .width(),
                                                                            expected.sourceRectAtLevel()
                                                                                    .height())),
                                                            sha256(bytes));
                                                })));
    }

    @Test
    void permitsBinarySourceLargerThanStructuredMemberLimit() throws Exception {
        byte[] sourceBytes = new byte[4_097];
        TrainingApiLimits limits = limits(4_096);
        PlannedImage image =
                derivedImage("entry-a", "Control", ROI_A, OBJECT_A, rectangle(10, 20, 80, 90));

        assertEquals(4_096, limits.dataset().maxJsonMemberBytes());

        var prepared =
                TrainingDatasetPackageWriter.write(
                        temporaryDirectory.resolve("large-source.zip"),
                        List.of(image),
                        limits,
                        ignored ->
                                session(
                                        (roi, destination) -> {
                                            Files.write(destination, sourceBytes);
                                            return derivedArtifact(
                                                    roi,
                                                    destination,
                                                    sourceBytes,
                                                    sha256(sourceBytes));
                                        }));

        assertArrayEquals(
                sourceBytes, archiveMembers(prepared.archivePath()).get("artifacts/0/source.png"));
    }

    @Test
    void rejectsEveryOversizedCaptureDimensionBeforeOpeningItsPixelSource() {
        PlannedImage oneObject =
                derivedImage("entry-a", "Control", ROI_A, OBJECT_A, rectangle(10, 20, 80, 90));
        PlannedImage secondRoi =
                derivedImage("entry-b", "Dose", ROI_B, OBJECT_B, rectangle(100, 120, 190, 210));
        PlannedImage twoObjects = derivedImageWithTwoObjects();
        AtomicInteger opens = new AtomicInteger();
        assertRejectedBeforeSourceOpen(
                List.of(oneObject, secondRoi), datasetLimits(1, 10, 10, 10, 100), opens);
        assertRejectedBeforeSourceOpen(
                List.of(twoObjects), datasetLimits(1, 1, 10, 10, 100), opens);
        assertRejectedBeforeSourceOpen(
                List.of(twoObjects), datasetLimits(1, 10, 1, 10, 100), opens);
        assertRejectedBeforeSourceOpen(List.of(oneObject), datasetLimits(1, 10, 10, 3, 100), opens);
        assertRejectedBeforeSourceOpen(List.of(oneObject), datasetLimits(1, 10, 10, 10, 7), opens);
        assertEquals(0, opens.get());
    }

    private void assertRejectedBeforeSourceOpen(
            List<PlannedImage> images, TrainingDatasetLimits datasetLimits, AtomicInteger opens) {
        TrainingApiLimits base = limits();
        TrainingApiLimits constrained =
                new TrainingApiLimits(
                        datasetLimits,
                        base.transfer(),
                        base.report(),
                        base.maxPageSize(),
                        base.maxPageEncodedBytes());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingDatasetPackageWriter.write(
                                temporaryDirectory.resolve("oversized.zip"),
                                images,
                                constrained,
                                ignored -> {
                                    opens.incrementAndGet();
                                    return derivedSessions().open(images.getFirst());
                                }));
    }

    private static TrainingDatasetLimits datasetLimits(
            long maxRois,
            long maxObjectsPerRoi,
            long maxTotalObjects,
            long maxVerticesPerGeometry,
            long maxTotalVertices) {
        TrainingDatasetLimits base = limits().dataset();
        return new TrainingDatasetLimits(
                maxRois,
                maxObjectsPerRoi,
                maxTotalObjects,
                maxVerticesPerGeometry,
                maxTotalVertices,
                base.maxJsonMemberBytes(),
                base.maxArchiveBytes(),
                base.maxExtractedBytes());
    }

    private static PlannedImage derivedImageWithTwoObjects() {
        Geometry geometry = rectangle(10, 20, 80, 90);
        CapturedRoi roi =
                new CapturedRoi(
                        ROI_A,
                        geometry,
                        List.of(
                                new TrainingObject(OBJECT_A, "detection", "Leydig cell", geometry),
                                new TrainingObject(OBJECT_B, "detection", "Leydig cell", geometry)),
                        new LabelCounts(2, 0, 0));
        CapturedImage capture = capturedImage("entry-a", "Control", List.of(roi));
        var rect = new SourceIdentityCodec.IntRect(10, 20, 70, 70);
        var artifact =
                new DerivedArtifactPlan(
                        SourceContainer.PNG,
                        0,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        rect,
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, rect.x(), rect.y()),
                        10_000);
        return new PlannedImage(
                capture,
                new TrainingImageSourcePlan(
                        capture.sourceRevision(),
                        capture.task(),
                        List.of(sourcePlan(roi, rect, artifact))));
    }

    private static PlannedImage derivedImage(
            String entryId, String group, String roiId, String objectId, Geometry geometry) {
        CapturedImage capture =
                capturedImage(entryId, group, List.of(capturedRoi(roiId, objectId, geometry)));
        var rect =
                new SourceIdentityCodec.IntRect(
                        (int) geometry.getEnvelopeInternal().getMinX(),
                        (int) geometry.getEnvelopeInternal().getMinY(),
                        (int) geometry.getEnvelopeInternal().getWidth(),
                        (int) geometry.getEnvelopeInternal().getHeight());
        var artifact =
                new DerivedArtifactPlan(
                        SourceContainer.PNG,
                        0,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        rect,
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, rect.x(), rect.y()),
                        10_000);
        return new PlannedImage(
                capture,
                new TrainingImageSourcePlan(
                        capture.sourceRevision(),
                        capture.task(),
                        List.of(sourcePlan(capture.rois().getFirst(), rect, artifact))));
    }

    private static CapturedImage capturedImage(
            String entryId, String group, List<CapturedRoi> rois) {
        return new CapturedImage(
                entryId,
                CanonicalCodec.sha256(entryId.getBytes(StandardCharsets.UTF_8)),
                Optional.ofNullable(group),
                sourceRevision(entryId),
                task(),
                rois,
                java.util.Set.of("Leydig cell"));
    }

    private static TrainingRoiSourcePlan sourcePlan(
            CapturedRoi roi,
            SourceIdentityCodec.IntRect rect,
            SourceInputPlan.SourceArtifactPlan artifact) {
        return new TrainingRoiSourcePlan(
                roi.sourceRoiUuid(),
                CanonicalGeometryCodec.canonicalDigest(roi.geometry()),
                LEVEL,
                rect,
                artifact);
    }

    private static RasterInputCodec.ActionSourceRevision sourceRevision(String imageId) {
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        imageId,
                        SourceIdentityCodec.imageSourceIdentityDigest(SOURCE),
                        "d".repeat(64)),
                SOURCE);
    }

    private static CapturedRoi capturedRoi(String roiId, String objectId, Geometry geometry) {
        return new CapturedRoi(
                roiId,
                geometry,
                List.of(new TrainingObject(objectId, "detection", "Leydig cell", geometry)),
                new LabelCounts(1, 0, 0));
    }

    private static TrainingDatasetPackageWriter.SourceSessionFactory derivedSessions() {
        return ignored ->
                session(
                        (roi, destination) -> {
                            byte[] bytes =
                                    ("pixels:" + roi.sourceRoiUuid())
                                            .getBytes(StandardCharsets.UTF_8);
                            Files.write(destination, bytes);
                            return derivedArtifact(roi, destination, bytes, sha256(bytes));
                        });
    }

    private static MaterializedSourceArtifact derivedArtifact(
            TrainingRoiSourcePlan roi, Path path, byte[] bytes, String digest) {
        DerivedArtifactPlan artifact = (DerivedArtifactPlan) roi.artifact();
        return new MaterializedSourceArtifact(
                path,
                artifact.container(),
                bytes.length,
                new DerivedRegion(
                        SOURCE,
                        artifact.sourceResolutionLevel(),
                        artifact.plane(),
                        artifact.sourceRectAtLevel(),
                        artifact.artifactPixelsToSlide(),
                        WRITER_BUILD),
                List.of(
                        new SourceLevelRequirement(
                                0,
                                0,
                                artifact.sourceRectAtLevel().width(),
                                artifact.sourceRectAtLevel().height())),
                digest);
    }

    private static TrainingDatasetPackageWriter.SourceSession session(Materializer materializer) {
        return new TrainingDatasetPackageWriter.SourceSession() {
            @Override
            public MaterializedSourceArtifact materialize(
                    TrainingRoiSourcePlan sourcePlan, Path destination) throws IOException {
                return materializer.materialize(sourcePlan, destination);
            }

            @Override
            public void close() {}
        };
    }

    private static Map<String, byte[]> archiveMembers(Path archive) throws IOException {
        Map<String, byte[]> members = new LinkedHashMap<>();
        try (var input = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                input.transferTo(bytes);
                assertTrue(members.put(entry.getName(), bytes.toByteArray()) == null);
            }
        }
        return members;
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

    private static TrainingApiLimits limits() {
        return limits(1_000_000);
    }

    private static TrainingApiLimits limits(long maxJsonMemberBytes) {
        return new TrainingApiLimits(
                new TrainingDatasetLimits(
                        1_000,
                        10_000,
                        100_000,
                        100_000,
                        1_000_000,
                        maxJsonMemberBytes,
                        20_000_000,
                        20_000_000),
                new TrainingDatasetTransferLimits(8 * 1024 * 1024, 10_000),
                new TrainingReportLimits(
                        100, 10_000, 10_000, 100_000, 100_000, 32, 10_000, 1_000_000),
                100,
                1_000_000);
    }

    private static SourceIdentityCodec.ImageSourceIdentity sourceIdentity() {
        return new SourceIdentityCodec.ImageSourceIdentity(
                "qupath.openslide.local",
                "c".repeat(64),
                Optional.of(
                        new SourceIdentityCodec.LocalFile(
                                22, 1_700_000_000_000L, Optional.empty())),
                1_000,
                800,
                1,
                1,
                new SourceIdentityCodec.PixelCalibration(0.5, 0.5));
    }

    private static Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.toGeometry(new Envelope(minX, maxX, minY, maxY));
    }

    private static String sha256(byte[] bytes) {
        return CanonicalCodec.sha256(bytes);
    }

    @FunctionalInterface
    private interface Materializer {
        MaterializedSourceArtifact materialize(TrainingRoiSourcePlan sourcePlan, Path destination)
                throws IOException;
    }
}
