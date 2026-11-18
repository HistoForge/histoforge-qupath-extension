package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SlideFileSetCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.DerivedRegion;
import qupath.ext.histoforge.contract.SourceArtifactCodec.OriginalWsi;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec.IntRect;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.TargetSourcePlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.regions.RegionRequest;

class SourceArtifactWriterTest {

    private static final int CHUNK_SIZE = 8 * 1024 * 1024;
    private static final long LARGE_LOCAL_LIMIT = 8L * 1024 * 1024 * 1024;

    @TempDir Path temporaryDirectory;

    @Test
    void boundedFilesPreserveSelectedLevelPixelsAndOriginalCoordinates() throws Exception {
        for (var container :
                java.util.List.of(
                        SourceArtifactCodec.SourceContainer.PNG,
                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY)) {
            TargetSourcePlan complete = SourceArtifactWriterTestData.derivedPlan(container);
            var capture =
                    new SourceInputPlan.IncrementalSourcePlan(
                                    complete.sourceLevel(),
                                    complete.target().plane(),
                                    complete.sourceEnvelopeAtLevel(),
                                    complete.levelZeroPixelsToSlide())
                            .capture(
                                    complete.sourceRevision().pixelSource(),
                                    SourceArtifactWriterTestData.WRITER_BUILD);
            var envelope = capture.sourceRectAtLevel();
            var rectangle = new IntRect(envelope.x() + 7, envelope.y() + 9, 64, 32);
            var imagePlan =
                    SourceArtifactPlanning.capturedImage(
                            capture,
                            rectangle,
                            Set.of(container),
                            new SourcePlanningLimits(
                                    32_000_000, LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT));
            Path destination =
                    temporaryDirectory.resolve(
                            container == SourceArtifactCodec.SourceContainer.PNG
                                    ? "bounded.png"
                                    : "bounded.ome.tif");
            AtomicInteger checks = new AtomicInteger();
            PreparedSourceArtifact prepared;
            try (var server = SourceArtifactWriterTestData.sourceServer()) {
                prepared =
                        writer(LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT)
                                .materializeCapturedImage(
                                        capture,
                                        imagePlan,
                                        server,
                                        destination,
                                        SourceArtifactWriterTestData.ACTION_ID,
                                        SourceArtifactWriterTestData.ARTIFACT_ID,
                                        identity -> {
                                            assertEquals(capture.source(), identity);
                                            checks.incrementAndGet();
                                        });
            }
            assertEquals(2, checks.get());
            assertEquals(
                    SourceCaptureCodec.imageOrigin(capture, rectangle),
                    prepared.startRequest().origin());
            if (container == SourceArtifactCodec.SourceContainer.PNG) {
                assertExactSelectedPixels(ImageIO.read(destination.toFile()), imagePlan);
            } else {
                try (var decoded =
                        ImageServerProvider.buildServer(
                                destination.toString(), BufferedImage.class)) {
                    assertEquals(1, decoded.nResolutions());
                    assertExactSelectedPixels(
                            decoded.readRegion(RegionRequest.createInstance(decoded)), imagePlan);
                }
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            SourceArtifactPlanning.capturedImage(
                                    capture,
                                    new IntRect(envelope.x() - 1, envelope.y(), 64, 32),
                                    Set.of(container),
                                    new SourcePlanningLimits(
                                            32_000_000, LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT)));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            SourceArtifactPlanning.capturedImage(
                                    capture,
                                    rectangle,
                                    Set.of(container),
                                    new SourcePlanningLimits(32_000_000, 1, LARGE_LOCAL_LIMIT)));
        }
    }

    @Test
    void boundedImageRequiresTheCapturedWriterBuildBeforeCreatingAFile() throws Exception {
        TargetSourcePlan complete =
                SourceArtifactWriterTestData.derivedPlan(SourceArtifactCodec.SourceContainer.PNG);
        var capture =
                new SourceInputPlan.IncrementalSourcePlan(
                                complete.sourceLevel(),
                                complete.target().plane(),
                                complete.sourceEnvelopeAtLevel(),
                                complete.levelZeroPixelsToSlide())
                        .capture(
                                complete.sourceRevision().pixelSource(),
                                new qupath.ext.histoforge.contract.BuildIdentityCodec
                                        .SoftwareBuildIdentity(
                                        "different-writer", "c".repeat(64), "d".repeat(64)));
        Path destination = temporaryDirectory.resolve("wrong-build.png");
        try (var server = SourceArtifactWriterTestData.sourceServer()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            writer(LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT)
                                    .materializeCapturedImage(
                                            capture,
                                            (DerivedArtifactPlan) complete.artifact(),
                                            server,
                                            destination,
                                            SourceArtifactWriterTestData.ACTION_ID,
                                            SourceArtifactWriterTestData.ARTIFACT_ID,
                                            ignored -> {}));
        }
        assertTrue(Files.notExists(destination));
    }

    @Test
    void writesExactSelectedLevelPixelsAsLosslessPng() throws Exception {
        TargetSourcePlan plan =
                SourceArtifactWriterTestData.derivedPlan(SourceArtifactCodec.SourceContainer.PNG);
        Path destination = temporaryDirectory.resolve("derived.png");
        AtomicInteger checks = new AtomicInteger();
        PreparedSourceArtifact prepared;

        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            prepared =
                    writer(LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT)
                            .materialize(
                                    plan,
                                    source,
                                    destination,
                                    null,
                                    SourceArtifactWriterTestData.ACTION_ID,
                                    SourceArtifactWriterTestData.ARTIFACT_ID,
                                    expected -> checks.incrementAndGet());
        }

        assertEquals(2, checks.get());
        assertEquals(destination.toAbsolutePath(), prepared.path());
        assertEquals(Files.size(destination), prepared.startRequest().totalBytes());
        assertEquals(CHUNK_SIZE, prepared.startRequest().chunkSizeBytes());
        assertEquals(sha256(destination), prepared.sealRequest().bytesDigest());
        DerivedRegion origin =
                assertInstanceOf(DerivedRegion.class, prepared.startRequest().origin());
        DerivedArtifactPlan artifact = assertInstanceOf(DerivedArtifactPlan.class, plan.artifact());
        assertEquals(artifact.sourceResolutionLevel(), origin.sourceResolutionLevel());
        assertEquals(artifact.sourceRectAtLevel(), origin.sourceRectAtLevel());
        assertEquals(artifact.artifactPixelsToSlide(), origin.artifactPixelsToSlide());
        assertEquals(SourceArtifactWriterTestData.WRITER_BUILD, origin.writerBuild());
        assertEquals(
                artifact.sourceRectAtLevel().width(),
                prepared.startRequest().requiredLevels().getFirst().width());

        BufferedImage decoded = ImageIO.read(destination.toFile());
        assertExactSelectedPixels(decoded, artifact);
    }

    @Test
    void writesExactSelectedLevelPixelsAsOneLevelTiledBigTiff() throws Exception {
        TargetSourcePlan plan =
                SourceArtifactWriterTestData.derivedPlan(
                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY);
        DerivedArtifactPlan artifact = assertInstanceOf(DerivedArtifactPlan.class, plan.artifact());
        Path destination = temporaryDirectory.resolve("derived.ome.tif");
        PreparedSourceArtifact prepared;

        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            prepared =
                    writer(LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT)
                            .materialize(
                                    plan,
                                    source,
                                    destination,
                                    null,
                                    SourceArtifactWriterTestData.ACTION_ID,
                                    SourceArtifactWriterTestData.ARTIFACT_ID,
                                    expected -> {});
        }

        assertEquals(
                SourceArtifactCodec.SourceContainer.TIFF_FAMILY,
                prepared.startRequest().container());
        assertEquals(sha256(destination), prepared.sealRequest().bytesDigest());
        try (var decoded =
                ImageServerProvider.buildServer(destination.toString(), BufferedImage.class)) {
            assertEquals(artifact.sourceRectAtLevel().width(), decoded.getWidth());
            assertEquals(artifact.sourceRectAtLevel().height(), decoded.getHeight());
            assertEquals(1, decoded.nResolutions());
            assertEquals(0.5, decoded.getPixelCalibration().getPixelWidthMicrons());
            assertEquals(0.75, decoded.getPixelCalibration().getPixelHeightMicrons());
            BufferedImage pixels = decoded.readRegion(RegionRequest.createInstance(decoded));
            assertExactSelectedPixels(pixels, artifact);
        }
    }

    @Test
    void repeatedDerivedCaptureProducesIdenticalMemberBytes() throws Exception {
        for (SourceArtifactCodec.SourceContainer container :
                new SourceArtifactCodec.SourceContainer[] {
                    SourceArtifactCodec.SourceContainer.PNG,
                    SourceArtifactCodec.SourceContainer.TIFF_FAMILY
                }) {
            TargetSourcePlan plan =
                    container == SourceArtifactCodec.SourceContainer.TIFF_FAMILY
                            ? SourceArtifactWriterTestData.fullDerivedPlan(container)
                            : SourceArtifactWriterTestData.derivedPlan(container);
            String filename =
                    container == SourceArtifactCodec.SourceContainer.PNG
                            ? "source.png"
                            : "source.ome.tif";
            byte[] expectedBytes = null;
            for (int capture = 0; capture < 6; capture++) {
                Path destination =
                        temporaryDirectory.resolve(Integer.toString(capture)).resolve(filename);
                try (var source = SourceArtifactWriterTestData.sourceServer()) {
                    writer(1, LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT)
                            .materialize(
                                    plan,
                                    source,
                                    destination,
                                    null,
                                    SourceArtifactWriterTestData.ACTION_ID,
                                    SourceArtifactWriterTestData.ARTIFACT_ID,
                                    expected -> {});
                }
                byte[] actualBytes = Files.readAllBytes(destination);
                if (expectedBytes == null) {
                    expectedBytes = actualBytes;
                } else {
                    assertArrayEquals(expectedBytes, actualBytes, container + " member bytes");
                }
            }
        }
    }

    @Test
    void exposesAnEligibleOriginalWithoutCopyingOrRewritingIt() throws Exception {
        Path original = temporaryDirectory.resolve("original.tif");
        byte[] bytes = new byte[4_097];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31);
        }
        Files.write(original, bytes);
        TargetSourcePlan plan = SourceArtifactWriterTestData.originalPlan(original);
        AtomicInteger checks = new AtomicInteger();

        PreparedSourceArtifact prepared;
        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            prepared =
                    writer(1, bytes.length)
                            .materialize(
                                    plan,
                                    source,
                                    original,
                                    original,
                                    SourceArtifactWriterTestData.ACTION_ID,
                                    SourceArtifactWriterTestData.ARTIFACT_ID,
                                    expected -> checks.incrementAndGet());
        }

        assertEquals(2, checks.get());
        assertEquals(original.toRealPath(), prepared.path());
        assertArrayEquals(bytes, Files.readAllBytes(original));
        assertEquals(sha256(original), prepared.sealRequest().bytesDigest());
        OriginalWsi origin = assertInstanceOf(OriginalWsi.class, prepared.startRequest().origin());
        assertEquals(0, origin.pyramidSeriesIndex());
        assertEquals(
                plan.sourceRevision().pixelSource().contentVersion(),
                origin.source().contentVersion());
    }

    @Test
    void packagesOriginalCompanionsAndBindsActualMemberHashesWithoutReadingPixels()
            throws Exception {
        var originals =
                Map.of(
                        "tissue.mrxs", new byte[] {1, 2, 3},
                        "tissue/tile-é.dat", new byte[] {9, 8, 7, 6},
                        "tissue/empty.dat", new byte[0]);
        for (var member : originals.entrySet()) {
            Path path = temporaryDirectory.resolve(member.getKey());
            Files.createDirectories(path.getParent());
            Files.write(path, member.getValue());
        }
        Path entry = temporaryDirectory.resolve("tissue.mrxs");
        var files =
                SlideFileInventory.capture(
                        entry, List.of(), Optional.of(temporaryDirectory.resolve("tissue")));
        TargetSourcePlan single = SourceArtifactWriterTestData.originalPlan(entry);
        var previous = single.sourceRevision().pixelSource();
        var sourceIdentity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        previous.sourceAdapterId(),
                        previous.credentialFreeDescriptorDigest(),
                        Optional.of(files.contentVersion()),
                        previous.width(),
                        previous.height(),
                        previous.zSlices(),
                        previous.timepoints(),
                        previous.calibration());
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                single.sourceRevision().imageDataRevision().imageId(),
                                SourceIdentityCodec.imageSourceIdentityDigest(sourceIdentity),
                                single.sourceRevision()
                                        .imageDataRevision()
                                        .imageDataContentDigest()),
                        sourceIdentity);
        var original = (SourceInputPlan.OriginalArtifactPlan) single.artifact();
        var artifact =
                new SourceInputPlan.OriginalArtifactPlan(
                        OriginalSlideArchive.size(files.members()),
                        original.pyramidSeriesIndex(),
                        original.levelZeroPixelsToSlide(),
                        original.requiredLevels(),
                        SourceArtifactCodec.SourceContainer.SLIDE_FILE_SET,
                        files.entryPath(),
                        original.readerSettings());
        var plan =
                new TargetSourcePlan(
                        single.target(),
                        revision,
                        single.levelZeroPixelsToSlide(),
                        single.protocol(),
                        single.sourceLevel(),
                        single.sourceEnvelopeAtLevel(),
                        artifact,
                        single.fieldInputs(),
                        single.cropInputs());
        Path destination = temporaryDirectory.resolve("upload.zip");
        AtomicInteger checks = new AtomicInteger();
        PreparedSourceArtifact prepared;
        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            prepared =
                    writer(1, LARGE_LOCAL_LIMIT)
                            .materialize(
                                    plan,
                                    source,
                                    destination,
                                    entry,
                                    SourceArtifactWriterTestData.ACTION_ID,
                                    SourceArtifactWriterTestData.ARTIFACT_ID,
                                    expected -> {
                                        assertEquals(sourceIdentity, expected);
                                        files.requireCurrent();
                                        checks.incrementAndGet();
                                    });
            assertEquals(0, source.regionReads());
        }
        assertEquals(2, checks.get());
        assertEquals(destination.toAbsolutePath(), prepared.path());
        assertEquals(artifact.totalBytes(), Files.size(destination));
        assertEquals(sha256(destination), prepared.sealRequest().bytesDigest());
        artifact.requireMatchingStart(prepared.startRequest());
        OriginalWsi origin = assertInstanceOf(OriginalWsi.class, prepared.startRequest().origin());
        assertEquals(sourceIdentity, origin.source());
        assertEquals(files.entryPath(), origin.entryPath());
        assertEquals(original.readerSettings(), origin.readerSettings());
        assertEquals(
                files.members().stream().map(SourceIdentityCodec.LocalFileMember::path).toList(),
                origin.members().stream().map(SlideFileSetCodec.Member::path).toList());
        try (var zip = new ZipFile(destination.toFile())) {
            for (var member : origin.members()) {
                var zipped = zip.getEntry(member.path());
                assertEquals(ZipEntry.STORED, zipped.getMethod());
                assertEquals(originals.get(member.path()).length, member.sizeBytes());
                assertEquals(sha256(temporaryDirectory.resolve(member.path())), member.sha256());
                try (var input = zip.getInputStream(zipped)) {
                    assertArrayEquals(originals.get(member.path()), input.readAllBytes());
                }
            }
        }
        files.requireCurrent();
        for (var member : originals.entrySet())
            assertArrayEquals(
                    member.getValue(),
                    Files.readAllBytes(temporaryDirectory.resolve(member.getKey())));
    }

    @Test
    void copiesAnAdmittedOriginalIntoAPackageOwnedPath() throws Exception {
        Path original = temporaryDirectory.resolve("original.tif");
        byte[] bytes = new byte[4_097];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 17);
        }
        Files.write(original, bytes);
        TargetSourcePlan plan = SourceArtifactWriterTestData.originalPlan(original);
        Path packageCopy = temporaryDirectory.resolve("package/source.tif");
        AtomicInteger checks = new AtomicInteger();

        SourceArtifactWriter.MaterializedSourceArtifact materialized;
        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            materialized =
                    writer(1, bytes.length)
                            .materializePackageCopy(
                                    plan.sourceRevision(),
                                    plan.levelZeroPixelsToSlide(),
                                    plan.sourceLevel(),
                                    plan.sourceEnvelopeAtLevel(),
                                    plan.artifact(),
                                    source,
                                    packageCopy,
                                    original,
                                    expected -> checks.incrementAndGet());
        }

        assertEquals(3, checks.get());
        assertEquals(packageCopy.toAbsolutePath(), materialized.path());
        assertArrayEquals(bytes, Files.readAllBytes(original));
        assertArrayEquals(bytes, Files.readAllBytes(packageCopy));
        assertEquals(sha256(packageCopy), materialized.sha256());
        assertInstanceOf(OriginalWsi.class, materialized.origin());
    }

    @Test
    void revisionFailureLeavesThePriorDestinationAndNoIncompleteFile() throws Exception {
        TargetSourcePlan plan =
                SourceArtifactWriterTestData.derivedPlan(SourceArtifactCodec.SourceContainer.PNG);
        Path destination = temporaryDirectory.resolve("derived.png");
        byte[] prior = {4, 3, 2, 1};
        Files.write(destination, prior);
        AtomicInteger checks = new AtomicInteger();

        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            assertThrows(
                    IOException.class,
                    () ->
                            writer(LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT)
                                    .materialize(
                                            plan,
                                            source,
                                            destination,
                                            null,
                                            SourceArtifactWriterTestData.ACTION_ID,
                                            SourceArtifactWriterTestData.ARTIFACT_ID,
                                            expected -> {
                                                if (checks.incrementAndGet() == 2) {
                                                    throw new IOException("source changed");
                                                }
                                            }));
        }

        assertEquals(2, checks.get());
        assertArrayEquals(prior, Files.readAllBytes(destination));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void suppressedLaterTiffTileFailureCannotReplaceThePriorDestination() throws Exception {
        TargetSourcePlan plan =
                SourceArtifactWriterTestData.fullDerivedPlan(
                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY);
        Path destination = temporaryDirectory.resolve("derived.ome.tif");
        byte[] prior = {9, 8, 7, 6};
        Files.write(destination, prior);

        try (var source = SourceArtifactWriterTestData.sourceServerWithOneLaterTileFailure()) {
            assertThrows(
                    IOException.class,
                    () ->
                            writer(LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT)
                                    .materialize(
                                            plan,
                                            source,
                                            destination,
                                            null,
                                            SourceArtifactWriterTestData.ACTION_ID,
                                            SourceArtifactWriterTestData.ARTIFACT_ID,
                                            expected -> {}));
            assertTrue(source.laterTileFailureObserved());
        }

        assertArrayEquals(prior, Files.readAllBytes(destination));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void rejectsConservativelyOversizedCaptureBeforeWriting() throws Exception {
        TargetSourcePlan plan =
                SourceArtifactWriterTestData.derivedPlan(SourceArtifactCodec.SourceContainer.PNG);
        Path destination = temporaryDirectory.resolve("too-large.png");

        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            assertThrows(
                    IOException.class,
                    () ->
                            writer(1, LARGE_LOCAL_LIMIT)
                                    .materialize(
                                            plan,
                                            source,
                                            destination,
                                            null,
                                            SourceArtifactWriterTestData.ACTION_ID,
                                            SourceArtifactWriterTestData.ARTIFACT_ID,
                                            expected -> {}));
        }

        assertTrue(Files.notExists(destination));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsAnOriginalWhoseCapturedFileVersionChanged() throws Exception {
        Path original = temporaryDirectory.resolve("original.tif");
        Files.write(original, new byte[] {1, 2, 3});
        TargetSourcePlan plan = SourceArtifactWriterTestData.originalPlan(original);
        Files.write(original, new byte[] {1, 2, 3, 4});

        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            assertThrows(
                    IOException.class,
                    () ->
                            writer(LARGE_LOCAL_LIMIT, LARGE_LOCAL_LIMIT)
                                    .materialize(
                                            plan,
                                            source,
                                            original,
                                            original,
                                            SourceArtifactWriterTestData.ACTION_ID,
                                            SourceArtifactWriterTestData.ARTIFACT_ID,
                                            expected -> {}));
        }
    }

    @Test
    void rejectsAnOriginalAboveTheIndependentBackendSourceCeiling() throws Exception {
        Path original = temporaryDirectory.resolve("original.tif");
        Files.write(original, new byte[] {1, 2, 3, 4});
        TargetSourcePlan plan = SourceArtifactWriterTestData.originalPlan(original);

        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            assertThrows(
                    IOException.class,
                    () ->
                            writer(1, 3)
                                    .materialize(
                                            plan,
                                            source,
                                            original,
                                            original,
                                            SourceArtifactWriterTestData.ACTION_ID,
                                            SourceArtifactWriterTestData.ARTIFACT_ID,
                                            expected -> {}));
        }
    }

    private static SourceArtifactWriter writer(long maxDerivedBytes, long maxSourceBytes) {
        return writer(4, maxDerivedBytes, maxSourceBytes);
    }

    private static SourceArtifactWriter writer(
            int omeWriterThreads, long maxDerivedBytes, long maxSourceBytes) {
        return new SourceArtifactWriter(
                SourceArtifactWriterTestData.WRITER_BUILD,
                CHUNK_SIZE,
                omeWriterThreads,
                maxDerivedBytes,
                maxSourceBytes);
    }

    private static void assertExactSelectedPixels(
            BufferedImage image, DerivedArtifactPlan artifact) {
        assertEquals(artifact.sourceRectAtLevel().width(), image.getWidth());
        assertEquals(artifact.sourceRectAtLevel().height(), image.getHeight());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                assertEquals(
                        SourceArtifactWriterTestData.expectedRgb(
                                artifact.sourceResolutionLevel(),
                                artifact.sourceRectAtLevel().x() + x,
                                artifact.sourceRectAtLevel().y() + y),
                        image.getRGB(x, y),
                        "pixel " + x + "," + y);
            }
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
