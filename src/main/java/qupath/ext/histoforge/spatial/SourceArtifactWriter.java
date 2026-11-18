package qupath.ext.histoforge.spatial;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.tiff.TiffParser;
import loci.formats.tiff.TiffSaver;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.DerivedRegion;
import qupath.ext.histoforge.contract.SourceArtifactCodec.OriginalWsi;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactOrigin;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactStartRequest;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceLevelRequirement;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.ext.histoforge.spatial.SourceInputPlan.SourceArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.TargetSourcePlan;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.writers.PngWriter;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

/**
 * Materializes one planned source artifact without owning upload or model execution.
 *
 * <p>An eligible original is exposed unchanged or packaged with its captured companions. Derived
 * artifacts are copied from exact native-level QuPath tiles into either lossless PNG or one-level
 * tiled ZLIB OME-BigTIFF, forced, hashed, and atomically published at a caller-owned path. The
 * caller owns final-file retention and deletion; this class removes only its private incomplete
 * file.
 */
public final class SourceArtifactWriter {

    private static final int DERIVED_TILE_SIZE = 512;
    private static final int HASH_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final Pattern OME_UUID_URN =
            Pattern.compile(
                    "urn:uuid:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final BuildIdentityCodec.SoftwareBuildIdentity writerBuild;
    private final int chunkSizeBytes;
    private final int omeWriterThreads;
    private final long maxDerivedArtifactBytes;
    private final long maxSourceArtifactBytes;

    public SourceArtifactWriter(
            BuildIdentityCodec.SoftwareBuildIdentity writerBuild,
            int chunkSizeBytes,
            int omeWriterThreads,
            long maxDerivedArtifactBytes,
            long maxSourceArtifactBytes) {
        this.writerBuild = Objects.requireNonNull(writerBuild, "writerBuild");
        this.chunkSizeBytes =
                CanonicalCodec.requirePositiveInt32(chunkSizeBytes, "chunk_size_bytes");
        this.omeWriterThreads =
                CanonicalCodec.requirePositiveInt32(omeWriterThreads, "ome_writer_threads");
        this.maxDerivedArtifactBytes =
                CanonicalCodec.requirePositiveSafeInteger(
                        maxDerivedArtifactBytes, "max_derived_source_artifact_bytes");
        this.maxSourceArtifactBytes =
                CanonicalCodec.requirePositiveSafeInteger(
                        maxSourceArtifactBytes, "max_source_artifact_bytes");
        if (maxDerivedArtifactBytes > maxSourceArtifactBytes) {
            throw new IllegalArgumentException(
                    "derived source-artifact ceiling must not exceed the backend ceiling");
        }
    }

    /** Exact local bytes and immutable requests ready for source-artifact publication. */
    public record PreparedSourceArtifact(
            Path path,
            SourceArtifactStartRequest startRequest,
            SourceArtifactTransferCodec.SourceArtifactSealRequest sealRequest) {
        public PreparedSourceArtifact {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            startRequest = Objects.requireNonNull(startRequest, "startRequest");
            sealRequest = Objects.requireNonNull(sealRequest, "sealRequest");
        }
    }

    /** Exact bytes and origin produced before any transport-specific request is built. */
    public record MaterializedSourceArtifact(
            Path path,
            SourceContainer container,
            long sizeBytes,
            SourceArtifactOrigin origin,
            List<SourceLevelRequirement> requiredLevels,
            String sha256) {
        public MaterializedSourceArtifact {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            container = Objects.requireNonNull(container, "container");
            sizeBytes = CanonicalCodec.requirePositiveSafeInteger(sizeBytes, "size_bytes");
            origin = Objects.requireNonNull(origin, "origin");
            requiredLevels = List.copyOf(requiredLevels);
            sha256 = CanonicalCodec.requireSha256(sha256, "sha256");
            if (requiredLevels.isEmpty()) {
                throw new IllegalArgumentException("materialized source levels must be nonempty");
            }
        }
    }

    /**
     * Revalidates the captured pixel-source identity immediately before and after source reads.
     *
     * <p>The source adapter owns how this is proved: local files compare their stable attributes;
     * object stores use a version ID or conditional ETag; an explicitly admitted bounded finite
     * source may use its captured-image lease. The writer only owns the placement of the two checks
     * around its complete capture.
     */
    @FunctionalInterface
    public interface SourceRevisionVerifier {
        void requireCurrent(SourceIdentityCodec.ImageSourceIdentity expected) throws IOException;
    }

    /**
     * Expose or create one exact source file and build its canonical start and seal requests.
     *
     * @param plan immutable source plan for one target
     * @param source caller-owned QuPath pixel server captured by the plan
     * @param artifactPath original single file, or owned destination for prepared pixels/a ZIP
     * @param originalSourcePath captured entry file for originals; null for prepared pixels
     * @param creationActionId action that creates the upload reservation
     * @param artifactId fresh client-assigned source-artifact ID
     * @param revisionVerifier adapter-owned current-source check around all source reads
     */
    public PreparedSourceArtifact materialize(
            TargetSourcePlan plan,
            ImageServer<BufferedImage> source,
            Path artifactPath,
            Path originalSourcePath,
            String creationActionId,
            String artifactId,
            SourceRevisionVerifier revisionVerifier)
            throws IOException {
        TargetSourcePlan target = Objects.requireNonNull(plan, "plan");
        String action = CanonicalCodec.requireCanonicalUuid(creationActionId, "creation_action_id");
        String id = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
        MaterializedSourceArtifact artifact =
                materialize(
                        target.sourceRevision(),
                        target.levelZeroPixelsToSlide(),
                        target.sourceLevel(),
                        target.sourceEnvelopeAtLevel(),
                        target.artifact(),
                        source,
                        artifactPath,
                        originalSourcePath,
                        revisionVerifier);
        return prepared(artifact, action, id);
    }

    /**
     * Materialize source bytes independently of source-artifact upload. Training dataset capture
     * uses the same reader, writer, revision checks, and origin contract as inference.
     */
    public MaterializedSourceArtifact materialize(
            RasterInputCodec.ActionSourceRevision sourceRevision,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            PinnedSourceLevel sourceLevel,
            SourceIdentityCodec.IntRect sourceEnvelopeAtLevel,
            SourceArtifactPlan artifact,
            ImageServer<BufferedImage> source,
            Path artifactPath,
            Path originalSourcePath,
            SourceRevisionVerifier revisionVerifier)
            throws IOException {
        return materialize(
                sourceRevision.pixelSource(),
                levelZeroPixelsToSlide,
                sourceLevel,
                sourceEnvelopeAtLevel,
                artifact,
                source,
                artifactPath,
                originalSourcePath,
                revisionVerifier);
    }

    /** Encode one image from a capture; its origin remains in the original selected-level grid. */
    public PreparedSourceArtifact materializeCapturedImage(
            SourceCaptureCodec.Capture capture,
            DerivedArtifactPlan plan,
            ImageServer<BufferedImage> source,
            Path artifactPath,
            String creationActionId,
            String artifactId,
            SourceRevisionVerifier revisionVerifier)
            throws IOException {
        String action = CanonicalCodec.requireCanonicalUuid(creationActionId, "creation_action_id");
        String id = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
        if (!writerBuild.equals(capture.writerBuild())) {
            throw new IllegalArgumentException("source capture requires another writer build");
        }
        SourceCaptureCodec.requireContained(plan.sourceRectAtLevel(), capture.sourceRectAtLevel());
        var level = capture.sourceLevel();
        var pinned =
                new PinnedSourceLevel(
                        level.seriesIndex(),
                        level.resolutionLevel(),
                        new SourceViewGeometry.Dimensions(level.width(), level.height()),
                        capture.sourceLevelPixelsToLevelZero());
        MaterializedSourceArtifact image =
                materialize(
                        capture.source(),
                        capture.levelZeroPixelsToSlide(),
                        pinned,
                        plan.sourceRectAtLevel(),
                        plan,
                        source,
                        artifactPath,
                        null,
                        revisionVerifier);
        return prepared(image, action, id);
    }

    private MaterializedSourceArtifact materialize(
            SourceIdentityCodec.ImageSourceIdentity sourceIdentity,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            PinnedSourceLevel sourceLevel,
            SourceIdentityCodec.IntRect sourceEnvelopeAtLevel,
            SourceArtifactPlan artifact,
            ImageServer<BufferedImage> source,
            Path artifactPath,
            Path originalSourcePath,
            SourceRevisionVerifier revisionVerifier)
            throws IOException {
        SourceIdentityCodec.ImageSourceIdentity revision =
                Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        SourceIdentityCodec.ScaleTranslate2D levelZeroMapping =
                Objects.requireNonNull(levelZeroPixelsToSlide, "levelZeroPixelsToSlide");
        PinnedSourceLevel level = Objects.requireNonNull(sourceLevel, "sourceLevel");
        SourceIdentityCodec.IntRect envelope =
                Objects.requireNonNull(sourceEnvelopeAtLevel, "sourceEnvelopeAtLevel");
        SourceArtifactPlan planned = Objects.requireNonNull(artifact, "artifact");
        ImageServer<BufferedImage> server = Objects.requireNonNull(source, "source");
        Path path =
                Objects.requireNonNull(artifactPath, "artifactPath").toAbsolutePath().normalize();
        SourceRevisionVerifier verifier =
                Objects.requireNonNull(revisionVerifier, "revisionVerifier");
        requireSourceMatchesPlan(revision, levelZeroMapping, level, envelope, planned, server);

        if (planned instanceof OriginalArtifactPlan original) {
            Path entry =
                    Objects.requireNonNull(originalSourcePath, "originalSourcePath").toRealPath();
            if (!entry.getFileName().toString().equals(original.entryPath()))
                throw new IOException("original entry path differs from the captured plan");
            if (original.container() == SourceContainer.SLIDE_FILE_SET)
                return packageOriginal(revision, entry, path, original, verifier);
            if (!path.toRealPath().equals(entry))
                throw new IOException("single original upload must use its captured file");
            return exposeOriginal(revision, entry, original, verifier);
        }
        return writeDerived(
                revision, level, envelope, server, path, (DerivedArtifactPlan) planned, verifier);
    }

    /**
     * Materialize a package-owned copy. Derived bytes are written directly at the destination; an
     * unchanged original is copied only after its exact source bytes have been admitted.
     */
    public MaterializedSourceArtifact materializePackageCopy(
            RasterInputCodec.ActionSourceRevision sourceRevision,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            PinnedSourceLevel sourceLevel,
            SourceIdentityCodec.IntRect sourceEnvelopeAtLevel,
            SourceArtifactPlan artifact,
            ImageServer<BufferedImage> source,
            Path destination,
            Path originalSourcePath,
            SourceRevisionVerifier revisionVerifier)
            throws IOException {
        if (artifact instanceof DerivedArtifactPlan) {
            if (originalSourcePath != null) {
                throw new IllegalArgumentException(
                        "derived package materialization must not supply an original path");
            }
            return materialize(
                    sourceRevision,
                    levelZeroPixelsToSlide,
                    sourceLevel,
                    sourceEnvelopeAtLevel,
                    artifact,
                    source,
                    destination,
                    null,
                    revisionVerifier);
        }
        if (artifact instanceof OriginalArtifactPlan original
                && original.container() == SourceContainer.SLIDE_FILE_SET)
            throw new IOException(
                    "companion-file originals are not qualified for training datasets");
        MaterializedSourceArtifact admitted =
                materialize(
                        sourceRevision,
                        levelZeroPixelsToSlide,
                        sourceLevel,
                        sourceEnvelopeAtLevel,
                        artifact,
                        source,
                        Objects.requireNonNull(originalSourcePath, "originalSourcePath"),
                        originalSourcePath,
                        revisionVerifier);
        return copyOriginal(admitted, destination, revisionVerifier);
    }

    private MaterializedSourceArtifact copyOriginal(
            MaterializedSourceArtifact admitted,
            Path destination,
            SourceRevisionVerifier revisionVerifier)
            throws IOException {
        Path target =
                Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(target.getParent(), "destination parent");
        Files.createDirectories(parent);
        Path incomplete =
                Files.createTempFile(parent, "." + target.getFileName() + ".", ".incomplete");
        try {
            Files.copy(admitted.path(), incomplete, StandardCopyOption.REPLACE_EXISTING);
            force(incomplete);
            revisionVerifier.requireCurrent(((OriginalWsi) admitted.origin()).source());
            long size = Files.size(incomplete);
            String digest = sha256(incomplete);
            if (size != admitted.sizeBytes() || !digest.equals(admitted.sha256())) {
                throw new IOException("original source changed while its package copy was written");
            }
            Files.move(
                    incomplete,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return new MaterializedSourceArtifact(
                    target,
                    admitted.container(),
                    size,
                    admitted.origin(),
                    admitted.requiredLevels(),
                    digest);
        } finally {
            Files.deleteIfExists(incomplete);
        }
    }

    private MaterializedSourceArtifact exposeOriginal(
            SourceIdentityCodec.ImageSourceIdentity source,
            Path path,
            OriginalArtifactPlan original,
            SourceRevisionVerifier verifier)
            throws IOException {
        SourceIdentityCodec.LocalFile expectedVersion =
                source.contentVersion()
                        .filter(SourceIdentityCodec.LocalFile.class::isInstance)
                        .map(SourceIdentityCodec.LocalFile.class::cast)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "original local source requires local-file content_version"));
        LocalFileSourceVersion.requireCurrent(path, expectedVersion);
        verifier.requireCurrent(source);
        long size = Files.size(path);
        if (size != original.totalBytes()) {
            throw new IOException("original source length does not match its plan");
        }
        requireWithinSourceLimit(size);
        String digest = sha256(path);
        verifier.requireCurrent(source);
        LocalFileSourceVersion.requireCurrent(path, expectedVersion);

        SourceArtifactOrigin origin =
                new OriginalWsi(
                        source,
                        original.pyramidSeriesIndex(),
                        original.levelZeroPixelsToSlide(),
                        original.entryPath(),
                        original.readerSettings(),
                        List.of());
        return new MaterializedSourceArtifact(
                path, SourceContainer.SLIDE_FILE, size, origin, original.requiredLevels(), digest);
    }

    private MaterializedSourceArtifact packageOriginal(
            SourceIdentityCodec.ImageSourceIdentity source,
            Path entry,
            Path destination,
            OriginalArtifactPlan original,
            SourceRevisionVerifier verifier)
            throws IOException {
        var version = (SourceIdentityCodec.LocalFileSet) source.contentVersion().orElseThrow();
        var files =
                new SlideFileInventory(
                        entry.getParent(),
                        original.entryPath(),
                        version.members(),
                        Optional.empty());
        verifier.requireCurrent(source);
        var members =
                OriginalSlideArchive.write(
                        files, destination, original.totalBytes(), maxSourceArtifactBytes);
        verifier.requireCurrent(source);
        var origin =
                new OriginalWsi(
                        source,
                        original.pyramidSeriesIndex(),
                        original.levelZeroPixelsToSlide(),
                        original.entryPath(),
                        original.readerSettings(),
                        members);
        return new MaterializedSourceArtifact(
                destination,
                SourceContainer.SLIDE_FILE_SET,
                Files.size(destination),
                origin,
                original.requiredLevels(),
                sha256(destination));
    }

    private MaterializedSourceArtifact writeDerived(
            SourceIdentityCodec.ImageSourceIdentity sourceIdentity,
            PinnedSourceLevel sourceLevel,
            SourceIdentityCodec.IntRect sourceEnvelopeAtLevel,
            ImageServer<BufferedImage> source,
            Path destination,
            DerivedArtifactPlan derived,
            SourceRevisionVerifier verifier)
            throws IOException {
        if (derived.conservativeArtifactBytes() > maxDerivedArtifactBytes) {
            throw new IOException("derived source artifact exceeds the derived byte ceiling");
        }
        Path parent = Objects.requireNonNull(destination.getParent(), "destination parent");
        Files.createDirectories(parent);
        Path stagingDirectory = null;
        Path incomplete;
        if (derived.container() == SourceContainer.TIFF_FAMILY) {
            stagingDirectory =
                    Files.createTempDirectory(parent, "." + destination.getFileName() + ".");
            incomplete = stagingDirectory.resolve(destination.getFileName());
        } else {
            incomplete =
                    Files.createTempFile(parent, "." + destination.getFileName() + ".", ".png");
        }
        try {
            SourceArtifactOrigin origin =
                    new DerivedRegion(
                            sourceIdentity,
                            derived.sourceResolutionLevel(),
                            derived.plane(),
                            derived.sourceRectAtLevel(),
                            derived.artifactPixelsToSlide(),
                            writerBuild);
            verifier.requireCurrent(sourceIdentity);
            try (var exactSource =
                    selectedLevelServer(
                            sourceIdentity, sourceLevel, sourceEnvelopeAtLevel, source, derived)) {
                TiffRgbReadback readback = null;
                if (derived.container() == SourceContainer.PNG) {
                    new PngWriter().writeImage(exactSource, incomplete.toString());
                } else {
                    readback = exactSource.recordTiffRgb();
                    writeTiledTiff(exactSource, incomplete, origin);
                }
                force(incomplete);
                if (readback != null) {
                    readback.requireExactCopy(incomplete);
                }
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("failed to materialize derived source artifact", error);
            }
            verifier.requireCurrent(sourceIdentity);
            long size = Files.size(incomplete);
            requireWithinDerivedLimit(size);
            String digest = sha256(incomplete);

            Files.move(
                    incomplete,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            List<SourceLevelRequirement> levels =
                    List.of(
                            new SourceLevelRequirement(
                                    0,
                                    0,
                                    derived.sourceRectAtLevel().width(),
                                    derived.sourceRectAtLevel().height()));
            return new MaterializedSourceArtifact(
                    destination, derived.container(), size, origin, levels, digest);
        } finally {
            Files.deleteIfExists(incomplete);
            if (stagingDirectory != null) {
                Files.deleteIfExists(stagingDirectory);
            }
        }
    }

    private static SelectedLevelRegionServer selectedLevelServer(
            SourceIdentityCodec.ImageSourceIdentity sourceIdentity,
            PinnedSourceLevel sourceLevel,
            SourceIdentityCodec.IntRect sourceEnvelopeAtLevel,
            ImageServer<BufferedImage> source,
            DerivedArtifactPlan derived)
            throws IOException {
        SourceIdentityCodec.PixelCalibration sourceCalibration = sourceIdentity.calibration();
        SourceIdentityCodec.ScaleTranslate2D levelMapping = sourceLevel.pixelsToLevelZero();
        SourceIdentityCodec.PixelCalibration selectedCalibration =
                new SourceIdentityCodec.PixelCalibration(
                        sourceCalibration.mppX() * levelMapping.scaleX(),
                        sourceCalibration.mppY() * levelMapping.scaleY());
        return new SelectedLevelRegionServer(
                source,
                derived.sourceResolutionLevel(),
                sourceLevel.dimensions(),
                derived.plane(),
                sourceEnvelopeAtLevel,
                selectedCalibration);
    }

    private void writeTiledTiff(
            ImageServer<BufferedImage> exactSource, Path incomplete, SourceArtifactOrigin origin)
            throws IOException {
        var series =
                new OMEPyramidWriter.Builder(exactSource)
                        .downsamples(1.0)
                        .tileSize(DERIVED_TILE_SIZE)
                        .compression(OMEPyramidWriter.CompressionType.ZLIB)
                        .channelsInterleaved()
                        .bigTiff()
                        .zSlice(0)
                        .timePoint(0)
                        .parallelize(omeWriterThreads)
                        .build();
        try {
            OMEPyramidWriter.createWriter(series).writeImage(incomplete.toString());
            normalizeOmeIdentity(incomplete, origin);
        } catch (FormatException error) {
            throw new IOException("failed to encode derived OME-BigTIFF", error);
        }
    }

    private static void normalizeOmeIdentity(Path path, SourceArtifactOrigin origin)
            throws IOException, FormatException {
        String comment;
        try (var parser = new TiffParser(path.toString())) {
            comment = parser.getComment();
        }
        if (comment == null || !OME_UUID_URN.matcher(comment).find()) {
            throw new IOException("derived OME-BigTIFF does not contain an OME UUID");
        }
        String uuid =
                "urn:uuid:"
                        + UUID.nameUUIDFromBytes(
                                        CanonicalCodec.canonicalBytes(
                                                SourceArtifactCodec.toJson(origin)))
                                .toString();
        String normalized =
                OME_UUID_URN.matcher(comment).replaceAll(Matcher.quoteReplacement(uuid));
        try (var input = new RandomAccessInputStream(path.toString());
                var saver = new TiffSaver(path.toString())) {
            saver.overwriteComment(input, normalized);
        }
    }

    private PreparedSourceArtifact prepared(
            MaterializedSourceArtifact artifact, String creationActionId, String artifactId) {
        SourceArtifactStartRequest start =
                new SourceArtifactStartRequest(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        creationActionId,
                        artifactId,
                        artifact.container(),
                        artifact.sizeBytes(),
                        chunkSizeBytes,
                        artifact.origin(),
                        artifact.requiredLevels());
        var seal = new SourceArtifactTransferCodec.SourceArtifactSealRequest(artifact.sha256());
        return new PreparedSourceArtifact(artifact.path(), start, seal);
    }

    private void requireWithinDerivedLimit(long size) throws IOException {
        if (size <= 0 || size > maxDerivedArtifactBytes) {
            throw new IOException("derived source artifact exceeds the derived byte ceiling");
        }
    }

    private void requireWithinSourceLimit(long size) throws IOException {
        if (size <= 0 || size > maxSourceArtifactBytes) {
            throw new IOException("source artifact exceeds the backend byte ceiling");
        }
    }

    private static void requireSourceMatchesPlan(
            SourceIdentityCodec.ImageSourceIdentity expected,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            PinnedSourceLevel sourceLevel,
            SourceIdentityCodec.IntRect sourceEnvelopeAtLevel,
            SourceArtifactPlan artifact,
            ImageServer<BufferedImage> source) {
        if (source.getWidth() != expected.width()
                || source.getHeight() != expected.height()
                || source.nZSlices() != expected.zSlices()
                || source.nTimepoints() != expected.timepoints()
                || !source.isRGB()
                || source.getPixelType() != PixelType.UINT8
                || source.nChannels() != 3) {
            throw new IllegalArgumentException(
                    "source server does not match the captured identity");
        }
        var calibration = source.getPixelCalibration();
        if (Double.compare(calibration.getPixelWidthMicrons(), expected.calibration().mppX()) != 0
                || Double.compare(
                                calibration.getPixelHeightMicrons(), expected.calibration().mppY())
                        != 0) {
            throw new IllegalArgumentException(
                    "source server calibration does not match the captured identity");
        }
        int levelIndex = sourceLevel.resolutionLevel();
        if (levelIndex >= source.nResolutions()) {
            throw new IllegalArgumentException("planned source level is unavailable");
        }
        var actualLevel = source.getMetadata().getLevel(levelIndex);
        if (actualLevel.getWidth() != sourceLevel.dimensions().width()
                || actualLevel.getHeight() != sourceLevel.dimensions().height()) {
            throw new IllegalArgumentException(
                    "planned source-level dimensions no longer match the server");
        }
        SourceIdentityCodec.IntRect envelope = sourceEnvelopeAtLevel;
        if ((long) envelope.x() + envelope.width() > actualLevel.getWidth()
                || (long) envelope.y() + envelope.height() > actualLevel.getHeight()) {
            throw new IllegalArgumentException("planned source envelope lies outside its level");
        }

        if (artifact instanceof DerivedArtifactPlan derived) {
            if (derived.sourceResolutionLevel() != levelIndex
                    || derived.plane().z() != 0
                    || derived.plane().t() != 0
                    || !derived.sourceRectAtLevel().equals(envelope)) {
                throw new IllegalArgumentException(
                        "derived artifact does not match its source plan");
            }
            SourceIdentityCodec.ScaleTranslate2D expectedMapping =
                    SourceViewGeometry.deriveOriginalViewTransform(
                            levelZeroPixelsToSlide,
                            sourceLevel.pixelsToLevelZero(),
                            sourceLevel.dimensions(),
                            envelope);
            if (!derived.artifactPixelsToSlide().equals(expectedMapping)) {
                throw new IllegalArgumentException(
                        "derived artifact mapping does not match its source plan");
            }
            return;
        }

        OriginalArtifactPlan original = (OriginalArtifactPlan) artifact;
        for (SourceLevelRequirement requirement : original.requiredLevels()) {
            if (requirement.resolutionLevel() >= source.nResolutions()) {
                throw new IllegalArgumentException("original required source level is unavailable");
            }
            var level = source.getMetadata().getLevel(requirement.resolutionLevel());
            if (level.getWidth() != requirement.width()
                    || level.getHeight() != requirement.height()) {
                throw new IllegalArgumentException(
                        "original required source level does not match the server");
            }
        }
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
