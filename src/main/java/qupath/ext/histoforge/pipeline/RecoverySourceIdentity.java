package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec.ImageSourceIdentity;
import qupath.ext.histoforge.contract.SourceIdentityCodec.LocalFile;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;

/**
 * One explicit recovery attempt's comparison with its historical pixel source.
 *
 * <p>Only a sealed original file can prove a changed filesystem key harmless. The proof stays in
 * this attempt, never replaces the current reader identity, and never rewrites retained requests.
 */
final class RecoverySourceIdentity {

    private final Optional<LargeActionHandle> recovery;
    private final BooleanSupplier shouldStop;
    private VerifiedOriginal verified;

    private RecoverySourceIdentity(
            Optional<LargeActionHandle> recovery, BooleanSupplier shouldStop) {
        this.recovery = recovery;
        this.shouldStop = shouldStop;
    }

    static RecoverySourceIdentity exact() {
        return new RecoverySourceIdentity(Optional.empty(), () -> false);
    }

    static RecoverySourceIdentity forResume(LargeActionHandle handle, BooleanSupplier shouldStop) {
        return new RecoverySourceIdentity(
                Optional.of(Objects.requireNonNull(handle, "handle")),
                Objects.requireNonNull(shouldStop, "shouldStop"));
    }

    boolean reconnected() {
        return verified != null;
    }

    /** Current reader geometry paired with historical identity for request reproduction only. */
    CapturedSourcePyramid planningPyramid(
            LargeActionHandle handle, ImageLifecycle.WorkingImage working) throws IOException {
        var current = working.sourcePyramid();
        requireMatch(
                handle, current.sourceRevision().pixelSource(), working.unchangedOriginalPath());
        return new CapturedSourcePyramid(
                historicalRevision(handle, current.sourceRevision()),
                current.seriesIndex(),
                current.levelZeroPixelsToSlide(),
                current.levels());
    }

    RasterInputCodec.ActionSourceRevision historicalRevision(
            LargeActionHandle handle, RasterInputCodec.ActionSourceRevision current) {
        var historical = handle.baseline().pixelSource();
        if (historical.equals(current.pixelSource())) return current;
        var revision = current.imageDataRevision();
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        revision.imageId(),
                        SourceIdentityCodec.imageSourceIdentityDigest(historical),
                        revision.imageDataContentDigest()),
                historical);
    }

    /** Validate again against the current file whenever a reopened reader consumes this proof. */
    void requireMatch(
            LargeActionHandle handle, ImageSourceIdentity current, Optional<Path> originalPath)
            throws IOException {
        requireMatch(handle, current, originalPath, true);
    }

    void requireMatch(
            LargeActionHandle handle,
            ImageSourceIdentity current,
            Optional<Path> originalPath,
            boolean cancellable)
            throws IOException {
        ImageSourceIdentity historical = handle.baseline().pixelSource();
        if (verified != null) {
            if (recovery.isEmpty()
                    || !sameAction(recovery.orElseThrow(), handle)
                    || !verified.source().equals(current)
                    || originalPath.isEmpty()
                    || !verified.path().equals(originalPath.orElseThrow().toRealPath())) {
                throw new IOException(
                        "the source changed after reconnection verification; retry recovery");
            }
            requireObservedFile(
                    verified.path(), (LocalFile) current.contentVersion().orElseThrow());
            return;
        }
        if (historical.equals(current)) return;
        if (recovery.isEmpty()
                || !sameAction(recovery.orElseThrow(), handle)
                || !onlyFileKeyDiffers(historical, current)) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh pixel source differs from the captured recovery source");
        }
        var source =
                handle.source().orElseThrow(() -> new IOException("no sealed original source"));
        var sealed =
                source.sealedArtifact()
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "reconnecting an unsealed source is not supported"));
        if (!(handle.sourcePlan() instanceof OriginalArtifactPlan plan)
                || source.ownedUploadFile().isPresent()
                || sealed.container() != SourceContainer.SLIDE_FILE
                || !sealed.artifactId().equals(source.artifactId())
                || !sealed.startRequestDigest().equals(source.startRequestFile().sha256())
                || plan.totalBytes()
                        != ((LocalFile) historical.contentVersion().orElseThrow()).sizeBytes()) {
            throw new LargeActionTransactionValidator.Conflict(
                    "reconnection requires the captured sealed original, not derived pixels");
        }
        Path path =
                originalPath
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "reopen the supported local original before recovery"))
                        .toRealPath();
        LocalFile observed = (LocalFile) current.contentVersion().orElseThrow();
        requireObservedFile(path, observed);
        String digest = hash(path, plan.totalBytes(), cancellable);
        requireObservedFile(path, observed);
        if (!digest.equals(sealed.bytesDigest())) {
            throw new LargeActionTransactionValidator.Conflict(
                    "original slide bytes differ from the completed upload");
        }
        verified = new VerifiedOriginal(path, current);
    }

    private static boolean sameAction(LargeActionHandle original, LargeActionHandle current) {
        return original.actionId().equals(current.actionId())
                && original.handleGenerationId().equals(current.handleGenerationId())
                && original.clientBuild().equals(current.clientBuild())
                && original.baseline().pixelSource().equals(current.baseline().pixelSource())
                && original.sourcePlan().equals(current.sourcePlan())
                && original.source().equals(current.source());
    }

    static boolean onlyFileKeyDiffers(ImageSourceIdentity historical, ImageSourceIdentity current) {
        if (!(historical.contentVersion().orElse(null) instanceof LocalFile before)
                || !(current.contentVersion().orElse(null) instanceof LocalFile after)
                || before.sizeBytes() != after.sizeBytes()
                || before.lastModifiedEpochMillis() != after.lastModifiedEpochMillis()
                || before.fileKey().isEmpty()
                || after.fileKey().isEmpty()
                || before.fileKey().equals(after.fileKey())) return false;
        return historical.equals(
                new ImageSourceIdentity(
                        current.sourceAdapterId(),
                        current.credentialFreeDescriptorDigest(),
                        Optional.of(before),
                        current.width(),
                        current.height(),
                        current.zSlices(),
                        current.timepoints(),
                        current.calibration()));
    }

    private static void requireObservedFile(Path path, LocalFile expected) throws IOException {
        // An incomplete observation is retryable I/O, not proof of different historical bytes.
        if (!LocalFileSourceVersion.capture(path).equals(expected)) {
            throw new IOException(
                    "source file changed during reconnection verification; retry recovery");
        }
    }

    private String hash(Path path, long expectedBytes, boolean cancellable) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        byte[] buffer = new byte[8 * 1024 * 1024];
        long count = 0;
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (cancellable && shouldStop.getAsBoolean())
                    throw new CancellationException("source verification cancelled");
                count = Math.addExact(count, read);
                if (count > expectedBytes) throw new IOException("source grew during verification");
                digest.update(buffer, 0, read);
            }
        }
        if (count != expectedBytes) throw new IOException("source ended during verification");
        if (cancellable && shouldStop.getAsBoolean())
            throw new CancellationException("source verification cancelled");
        return HexFormat.of().formatHex(digest.digest());
    }

    private record VerifiedOriginal(Path path, ImageSourceIdentity source) {}
}
