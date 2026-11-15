package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.spatial.ImageSourceCapture.CapturedSource;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.SourceRevisionVerifier;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.ext.histoforge.util.FxRunner;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Sole HistoForge owner of public QuPath working-copy load, save, viewer swap, and server cleanup.
 *
 * <p>The caller owns an {@link ImageActionRegistry.Lease} for the complete lifetime of a finite
 * action. This class deliberately does not serialize {@code .qpdata}, navigate the viewer, or add
 * another recovery path.
 */
public final class ImageLifecycle {

    private final QuPathGUI qupath;
    private final Supplier<Iterable<QuPathViewer>> viewers;
    private final Consumer<Runnable> fxRunner;
    private final RevisionCapture revisionCapture;
    private final SourceCapture sourceCapture;

    /** Use the real JavaFX and semantic-revision owners. */
    public ImageLifecycle(QuPathGUI qupath) {
        this(
                qupath,
                qupath::getAllViewers,
                FxRunner::runOnFx,
                ImageDataRevisionDigester::capture,
                new ImageSourceCapture()::capture);
    }

    ImageLifecycle(
            QuPathGUI qupath,
            Supplier<Iterable<QuPathViewer>> viewers,
            Consumer<Runnable> fxRunner,
            RevisionCapture revisionCapture,
            SourceCapture sourceCapture) {
        this.qupath = Objects.requireNonNull(qupath, "qupath");
        this.viewers = Objects.requireNonNull(viewers, "viewers");
        this.fxRunner = Objects.requireNonNull(fxRunner, "fxRunner");
        this.revisionCapture = Objects.requireNonNull(revisionCapture, "revisionCapture");
        this.sourceCapture = Objects.requireNonNull(sourceCapture, "sourceCapture");
    }

    /**
     * Persist an explicit user save of the exact image currently owned by a QuPath viewer.
     *
     * <p>This is the non-action save path used by Project-tab refresh. It performs no working-copy
     * swap, but it retains this class's single ownership of HistoForge-initiated QuPath saves and
     * rejects a stale entry/image pair before writing it.
     */
    public void saveCurrentViewerImage(
            ProjectImageEntry<BufferedImage> entry, ImageData<BufferedImage> imageData)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(imageData, "imageData");
        try {
            onFx(
                    () -> {
                        LiveTarget live = findLiveTarget(entry);
                        if (live == null || live.imageData() != imageData) {
                            throw new IllegalStateException(
                                    "entry and ImageData are no longer the current viewer target");
                        }
                        saveUnchecked(entry, imageData);
                        return null;
                    });
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    /**
     * Replace the active viewer image and transfer ownership only after QuPath adopted it.
     *
     * <p>If replacement fails, the displaced image is restored when possible and every unowned
     * image is closed before the original failure is rethrown. After successful adoption, the
     * displaced image is closed only when no viewer still owns it. A displaced-image cleanup
     * failure is returned because it must not turn a successful viewer replacement into a false
     * open failure.
     */
    public Optional<Throwable> replaceCurrentViewerImage(ImageData<BufferedImage> candidate)
            throws IOException {
        Objects.requireNonNull(candidate, "candidate");
        try {
            return onFx(
                    () -> {
                        QuPathViewer viewer = Objects.requireNonNull(qupath.getViewer(), "viewer");
                        ImageData<BufferedImage> previous = viewer.getImageData();
                        try {
                            viewer.setImageData(candidate);
                        } catch (Throwable failure) {
                            restoreAfterReplacementFailure(viewer, previous, candidate, failure);
                            throwUnchecked(failure);
                        }

                        if (viewer.getImageData() != candidate) {
                            IOException failure =
                                    new IOException("QuPath did not adopt the requested ImageData");
                            restoreAfterReplacementFailure(viewer, previous, candidate, failure);
                            throwUnchecked(failure);
                        }

                        Throwable cleanupFailure = null;
                        if (previous != null && previous != candidate && !viewerOwns(previous)) {
                            try {
                                previous.close();
                            } catch (Exception failure) {
                                cleanupFailure = failure;
                            }
                        }
                        return Optional.ofNullable(cleanupFailure);
                    });
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /** Says which authoritative QuPath materialization supplied a read-only capture. */
    public enum ImageDataMaterialization {
        STORED_QPDATA,
        LIVE_VIEWER
    }

    /** Detached value plus the exact semantic and pixel-source revision it represents. */
    public record ReadOnlyImageCapture<T>(
            ImageDataMaterialization materialization,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            T value) {
        public ReadOnlyImageCapture {
            materialization = Objects.requireNonNull(materialization, "materialization");
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            value = Objects.requireNonNull(value, "value");
        }
    }

    /** Owner-confined operation that must return a value detached from the supplied ImageData. */
    @FunctionalInterface
    public interface ReadOnlyCaptureOperation<T> {
        T capture(
                ImageData<BufferedImage> imageData,
                RasterInputCodec.ActionSourceRevision sourceRevision,
                CapturedSource capturedSource);
    }

    /**
     * Capture one authoritative image without saving, replacing a viewer, or retaining ImageData.
     *
     * <p>An open project entry is captured synchronously on the FX thread. Otherwise a fresh stored
     * {@code .qpdata} image is opened, captured on the caller thread, and closed before this method
     * returns. The callback must copy everything it needs into immutable detached values.
     */
    public <T> ReadOnlyImageCapture<T> captureReadOnly(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            ReadOnlyCaptureOperation<T> operation)
            throws IOException {
        return captureReadOnly(entry, imageId, limits, () -> {}, operation);
    }

    /** Recheck read-only cancellation on the owner thread before expensive revision capture. */
    public <T> ReadOnlyImageCapture<T> captureReadOnly(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            Runnable checkRunning,
            ReadOnlyCaptureOperation<T> operation)
            throws IOException {
        return captureReadOnlyIf(entry, imageId, limits, image -> true, checkRunning, operation)
                .orElseThrow();
    }

    /**
     * Skip an irrelevant image before source preparation or semantic revision capture.
     *
     * <p>The cheap condition runs on the hierarchy's owner thread, including a second check after
     * live-source preparation. It is not a validation substitute: accepted images use the same
     * complete capture as {@link #captureReadOnly}. A skipped stored image is still closed.
     */
    public <T> Optional<ReadOnlyImageCapture<T>> captureReadOnlyIf(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            Predicate<ImageData<BufferedImage>> shouldCapture,
            Runnable checkRunning,
            ReadOnlyCaptureOperation<T> operation)
            throws IOException {
        return captureImage(
                entry,
                imageId,
                limits,
                false,
                image -> true,
                shouldCapture,
                checkRunning,
                operation);
    }

    /**
     * Save an open image before capturing detached calculation inputs.
     *
     * <p>The caller identifies the ROI representations it can read exactly as QuPath would reload
     * them. Other representations use the saved image. Neither path retains an editable image; a
     * later correction must load a working image matching this capture.
     */
    public <T> ReadOnlyImageCapture<T> captureSavedImage(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            Predicate<ImageData<BufferedImage>> canCaptureLive,
            ReadOnlyCaptureOperation<T> operation)
            throws IOException {
        return captureImage(
                        entry,
                        imageId,
                        limits,
                        true,
                        canCaptureLive,
                        image -> true,
                        () -> {},
                        operation)
                .orElseThrow();
    }

    private <T> Optional<ReadOnlyImageCapture<T>> captureImage(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            boolean saveLive,
            Predicate<ImageData<BufferedImage>> canCaptureLive,
            Predicate<ImageData<BufferedImage>> shouldCapture,
            Runnable checkRunning,
            ReadOnlyCaptureOperation<T> operation)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        requireMatchingImageId(entry, imageId);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(canCaptureLive, "canCaptureLive");
        Objects.requireNonNull(shouldCapture, "shouldCapture");
        Objects.requireNonNull(checkRunning, "checkRunning");

        try {
            Optional<LiveSourceCapture> live =
                    onFx(
                            () -> {
                                checkRunning.run();
                                LiveTarget target = findLiveTarget(entry);
                                if (target == null) return Optional.empty();
                                if (!canCaptureLive.test(target.imageData())) {
                                    if (saveLive) saveUnchecked(entry, target.imageData());
                                    return Optional.empty();
                                }
                                if (!shouldCapture.test(target.imageData())) {
                                    return Optional.of(
                                            new LiveSourceCapture(target, Optional.empty()));
                                }
                                try {
                                    return Optional.of(
                                            new LiveSourceCapture(
                                                    target,
                                                    Optional.of(
                                                            sourceCapture.capture(
                                                                    target.imageData()
                                                                            .getServer()))));
                                } catch (IOException error) {
                                    throw new UncheckedIOException(error);
                                }
                            });
            if (live.isPresent()) {
                var initial = live.orElseThrow();
                if (initial.source().isEmpty()) return Optional.empty();
                CapturedSource prepared = initial.source().orElseThrow().prepare();
                Optional<ReadOnlyImageCapture<T>> result =
                        onFx(
                                () -> {
                                    checkRunning.run();
                                    LiveTarget current = findLiveTarget(entry);
                                    if (current == null
                                            || current.imageData() != initial.target().imageData()
                                            || !canCaptureLive.test(current.imageData()))
                                        throw new UncheckedIOException(
                                                new IOException(
                                                        "viewer image changed during source preparation"));
                                    if (!shouldCapture.test(current.imageData()))
                                        return Optional.empty();
                                    try {
                                        prepared.requireSameView(
                                                sourceCapture.capture(
                                                        current.imageData().getServer()));
                                        if (saveLive) saveUnchecked(entry, current.imageData());
                                        return Optional.of(
                                                capturePreparedReadOnly(
                                                        imageId,
                                                        limits,
                                                        current.imageData(),
                                                        ImageDataMaterialization.LIVE_VIEWER,
                                                        operation,
                                                        prepared));
                                    } catch (IOException error) {
                                        throw new UncheckedIOException(error);
                                    }
                                });
                // The detached callback is complete. Source validation can perform object-store
                // I/O, so it runs on the caller's worker rather than blocking JavaFX.
                if (result.isPresent()) prepared.requireCurrent();
                return result;
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }

        checkRunning.run();
        return captureStoredReadOnly(
                entry, imageId, limits, shouldCapture, checkRunning, operation);
    }

    /**
     * Capture the authoritative stored {@code .qpdata} without consulting or saving a viewer.
     *
     * <p>Large-action crash recovery uses this path because its commit witness describes the last
     * completed project save. Reading a live unsaved viewer would answer a different question and
     * could not resolve an ambiguous save.
     */
    public <T> ReadOnlyImageCapture<T> captureStoredReadOnly(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            ReadOnlyCaptureOperation<T> operation)
            throws IOException {
        return captureStoredReadOnly(entry, imageId, limits, image -> true, () -> {}, operation)
                .orElseThrow();
    }

    private <T> Optional<ReadOnlyImageCapture<T>> captureStoredReadOnly(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            Predicate<ImageData<BufferedImage>> shouldCapture,
            Runnable checkRunning,
            ReadOnlyCaptureOperation<T> operation)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        requireMatchingImageId(entry, imageId);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(operation, "operation");

        ImageData<BufferedImage> stored =
                Objects.requireNonNull(entry.readImageData(), "entry returned no stored ImageData");
        Optional<ReadOnlyImageCapture<T>> result;
        try {
            checkRunning.run();
            result =
                    shouldCapture.test(stored)
                            ? Optional.of(
                                    captureReadOnly(
                                            imageId,
                                            limits,
                                            stored,
                                            ImageDataMaterialization.STORED_QPDATA,
                                            operation))
                            : Optional.empty();
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(stored, failure);
            throw failure;
        }
        try {
            stored.close();
        } catch (Exception closeFailure) {
            throw new IOException("failed to close read-only stored ImageData", closeFailure);
        }
        return result;
    }

    /**
     * Open the stored pixel source that corresponds to an earlier detached training capture.
     *
     * <p>This does not save or inspect a live viewer. Hierarchy edits after the detached capture
     * are intentionally irrelevant, but the exact pixel-source identity must still match. The
     * caller owns the returned session and must close it.
     */
    public ReadOnlySourceSession openStoredSourceSession(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            RasterInputCodec.ActionSourceRevision expectedSource)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        requireMatchingImageId(entry, imageId);
        RasterInputCodec.ActionSourceRevision expected =
                Objects.requireNonNull(expectedSource, "expectedSource");
        if (!imageId.equals(expected.imageDataRevision().imageId())) {
            throw new IllegalArgumentException(
                    "expected source revision belongs to another project image");
        }

        ImageData<BufferedImage> stored =
                Objects.requireNonNull(entry.readImageData(), "entry returned no stored ImageData");
        try {
            CapturedSource source = sourceCapture.capture(stored.getServer()).prepare();
            if (!expected.pixelSource().equals(source.identity())) {
                throw new IOException("stored pixel source differs from the training capture");
            }
            source.requireCurrent();
            return new ReadOnlySourceSession(stored, expected, source);
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(stored, failure);
            throw failure;
        }
    }

    private <T> ReadOnlyImageCapture<T> captureReadOnly(
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            ImageData<BufferedImage> imageData,
            ImageDataMaterialization materialization,
            ReadOnlyCaptureOperation<T> operation)
            throws IOException {
        CapturedSource source = sourceCapture.capture(imageData.getServer()).prepare();
        ReadOnlyImageCapture<T> result =
                capturePreparedReadOnly(
                        imageId, limits, imageData, materialization, operation, source);
        source.requireCurrent();
        return result;
    }

    private <T> ReadOnlyImageCapture<T> capturePreparedReadOnly(
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            ImageData<BufferedImage> imageData,
            ImageDataMaterialization materialization,
            ReadOnlyCaptureOperation<T> operation,
            CapturedSource source)
            throws IOException {
        String sourceDigest = SourceIdentityCodec.imageSourceIdentityDigest(source.identity());
        RasterInputCodec.ImageRevisionToken revision =
                revisionCapture.capture(imageId, sourceDigest, imageData, limits);
        RasterInputCodec.ActionSourceRevision sourceRevision =
                source.bind(revision).sourceRevision();
        T value = operation.capture(imageData, sourceRevision, source);
        return new ReadOnlyImageCapture<>(materialization, sourceRevision, value);
    }

    /**
     * Synchronize a live target through QuPath, then load and revision-bind a separate working
     * image.
     *
     * <p>Stored reads remain on the calling worker thread. Viewer inspection and any live save are
     * FX-confined. A failure closes a working image that was already opened.
     */
    public WorkingImage openWorkingImage(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        requireMatchingImageId(entry, imageId);
        Objects.requireNonNull(limits, "limits");

        try {
            onFx(
                    () -> {
                        LiveTarget live = findLiveTarget(entry);
                        if (live != null) {
                            saveUnchecked(entry, live.imageData());
                        }
                        return null;
                    });
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }

        return readStoredWorkingImage(entry, imageId, limits);
    }

    /**
     * Load an editable image only if it still matches earlier calculation inputs. This deliberately
     * does not save the viewer: edits made during calculation must remain edits, not become a new
     * baseline for old parent assignments.
     */
    public WorkingImage openMatchingWorkingImage(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            RasterInputCodec.ActionSourceRevision expected)
            throws IOException, RevisionConflictException {
        Objects.requireNonNull(expected, "expected");
        requireMatchingImageId(entry, imageId);
        if (!imageId.equals(expected.imageDataRevision().imageId())) {
            throw new IllegalArgumentException("captured revision belongs to another image");
        }
        WorkingImage working = readStoredWorkingImage(entry, imageId, limits);
        if (!expected.equals(working.sourceRevision())) {
            var conflict =
                    new RevisionConflictException(
                            expected.imageDataRevision(), working.baseRevision());
            closeAfterFailure(working, conflict);
            throw conflict;
        }
        return working;
    }

    private WorkingImage readStoredWorkingImage(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits)
            throws IOException {
        ImageData<BufferedImage> working =
                Objects.requireNonNull(
                        entry.readImageData(), "entry returned no working ImageData");
        try {
            CapturedSource capturedSource = sourceCapture.capture(working.getServer()).prepare();
            String imageSourceDigest =
                    SourceIdentityCodec.imageSourceIdentityDigest(capturedSource.identity());
            RasterInputCodec.ImageRevisionToken baseRevision =
                    revisionCapture.capture(imageId, imageSourceDigest, working, limits);
            return new WorkingImage(
                    entry,
                    imageId,
                    limits,
                    working,
                    capturedSource.bind(baseRevision).sourceRevision(),
                    capturedSource);
        } catch (IOException failure) {
            closeAfterFailure(working, failure);
            throw failure;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(working, failure);
            throw failure;
        }
    }

    /**
     * Check that detached calculations still describe the authoritative image, without saving it.
     * Stored reads happen off FX; if the image becomes visible during that read, its viewer wins.
     */
    public RasterInputCodec.ImageRevisionToken verifyUnchanged(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            ExecutionLimitsCodec.ClientExecutionLimits limits,
            RasterInputCodec.ActionSourceRevision expected)
            throws IOException, RevisionConflictException {
        Objects.requireNonNull(expected, "expected");
        requireMatchingImageId(entry, imageId);
        if (!imageId.equals(expected.imageDataRevision().imageId())) {
            throw new IllegalArgumentException("captured revision belongs to another image");
        }
        var captured = captureReadOnly(entry, imageId, limits, (image, revision, source) -> source);
        RasterInputCodec.ActionSourceRevision current = captured.sourceRevision();
        if (captured.materialization() == ImageDataMaterialization.STORED_QPDATA) {
            try {
                var stored = current;
                current =
                        onFx(
                                () -> {
                                    LiveTarget live = findLiveTarget(entry);
                                    if (live == null) return stored;
                                    try {
                                        var source = captured.value();
                                        source.requireSameView(
                                                sourceCapture.capture(
                                                        live.imageData().getServer()));
                                        return capturePreparedReadOnly(
                                                        imageId,
                                                        limits,
                                                        live.imageData(),
                                                        ImageDataMaterialization.LIVE_VIEWER,
                                                        (image, revision, prepared) -> revision,
                                                        source)
                                                .sourceRevision();
                                    } catch (IOException error) {
                                        throw new UncheckedIOException(error);
                                    }
                                });
            } catch (UncheckedIOException failure) {
                throw failure.getCause();
            }
            captured.value().requireCurrent();
        }
        if (!expected.equals(current)) {
            throw new RevisionConflictException(
                    expected.imageDataRevision(), current.imageDataRevision());
        }
        return current.imageDataRevision();
    }

    /**
     * Verify the authoritative base, save once through QuPath, then refresh only a still-open
     * target viewer.
     *
     * <p>A revision conflict or thrown save closes the working image and never calls {@code
     * setImageData}. A viewer-refresh failure is returned because the successful project save
     * remains authoritative. After replacement returns or throws, the current viewer is read again
     * before server ownership is decided.
     */
    public CommitResult commit(WorkingImage working) throws IOException, RevisionConflictException {
        Objects.requireNonNull(working, "working");
        working.beginFinalization();
        try {
            working.requireSourceCurrent();
            Optional<CommitResult> liveResult =
                    onFx(
                            () -> {
                                LiveTarget live = findLiveTarget(working.entry());
                                if (live == null) {
                                    return Optional.empty();
                                }
                                return Optional.of(commitOnFx(working, live));
                            });
            if (liveResult.isPresent()) {
                return finishCommit(working, liveResult.orElseThrow());
            }

            RasterInputCodec.ImageRevisionToken storedRevision = captureStoredRevision(working);

            CommitResult result =
                    onFx(
                            () -> {
                                LiveTarget live = findLiveTarget(working.entry());
                                RasterInputCodec.ImageRevisionToken authoritative =
                                        live == null
                                                ? storedRevision
                                                : working.captureRevision(
                                                        live.imageData(),
                                                        revisionCapture,
                                                        sourceCapture);
                                return commitOnFx(working, authoritative, live);
                            });
            return finishCommit(working, result);
        } catch (RevisionConflictSignal signal) {
            closeAfterFailure(working, signal.conflict);
            throw signal.conflict;
        } catch (UncheckedIOException error) {
            IOException failure = error.getCause();
            closeAfterFailure(working, failure);
            throw failure;
        } catch (IOException failure) {
            closeAfterFailure(working, failure);
            throw failure;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(working, failure);
            throw failure;
        }
    }

    /**
     * Prove that both the disposable working image and authoritative project image still equal the
     * captured base, then close the working copy without saving or touching a viewer.
     *
     * <p>This is the terminal path for a successful Fill/Redo/Metrics action whose combined delta
     * is empty. It prevents a claimed no-op from publishing after an unnoticed working-image or
     * user edit while avoiding an unnecessary QuPath save.
     */
    public RasterInputCodec.ImageRevisionToken finishUnchanged(WorkingImage working)
            throws IOException, RevisionConflictException {
        Objects.requireNonNull(working, "working");
        working.beginFinalization();
        try {
            working.requireSourceCurrent();
            RasterInputCodec.ImageRevisionToken workingRevision =
                    working.captureRevision(working.imageData(), revisionCapture, sourceCapture);
            if (!working.baseRevision().equals(workingRevision)) {
                throw new IllegalStateException(
                        "working ImageData changed during a no-change action");
            }

            Optional<RasterInputCodec.ImageRevisionToken> liveRevision =
                    onFx(
                            () -> {
                                LiveTarget live = findLiveTarget(working.entry());
                                return live == null
                                        ? Optional.empty()
                                        : Optional.of(
                                                working.captureRevision(
                                                        live.imageData(),
                                                        revisionCapture,
                                                        sourceCapture));
                            });
            RasterInputCodec.ImageRevisionToken authoritative;
            if (liveRevision.isPresent()) {
                authoritative = liveRevision.orElseThrow();
            } else {
                RasterInputCodec.ImageRevisionToken storedRevision = captureStoredRevision(working);
                authoritative =
                        onFx(
                                () -> {
                                    LiveTarget live = findLiveTarget(working.entry());
                                    return live == null
                                            ? storedRevision
                                            : working.captureRevision(
                                                    live.imageData(),
                                                    revisionCapture,
                                                    sourceCapture);
                                });
            }
            if (!working.baseRevision().equals(authoritative)) {
                throw new RevisionConflictException(working.baseRevision(), authoritative);
            }
            working.close();
            return authoritative;
        } catch (UncheckedIOException error) {
            IOException failure = error.getCause();
            closeAfterFailure(working, failure);
            throw failure;
        } catch (IOException | RevisionConflictException failure) {
            closeAfterFailure(working, failure);
            throw failure;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(working, failure);
            throw failure;
        }
    }

    private CommitResult commitOnFx(WorkingImage working, LiveTarget authoritativeTarget) {
        return commitOnFx(
                working,
                working.captureRevision(
                        authoritativeTarget.imageData(), revisionCapture, sourceCapture),
                authoritativeTarget);
    }

    private CommitResult commitOnFx(
            WorkingImage working,
            RasterInputCodec.ImageRevisionToken authoritativeRevision,
            LiveTarget liveTarget) {
        if (!working.baseRevision().equals(authoritativeRevision)) {
            throw new RevisionConflictSignal(
                    new RevisionConflictException(working.baseRevision(), authoritativeRevision));
        }

        working.captureRevision(working.imageData(), revisionCapture, sourceCapture);
        saveUnchecked(working.entry(), working.imageData());
        RasterInputCodec.ImageRevisionToken savedRevision =
                working.captureRevision(working.imageData(), revisionCapture, sourceCapture);

        boolean viewerWasTarget = liveTarget != null;
        Throwable refreshFailure = null;
        if (viewerWasTarget && liveTarget.viewer().getImageData() != working.imageData()) {
            try {
                liveTarget.viewer().setImageData(working.imageData());
            } catch (Throwable error) {
                refreshFailure = error;
            }
        }

        boolean viewerAdoptedWorking =
                viewerWasTarget && liveTarget.viewer().getImageData() == working.imageData();
        if (viewerAdoptedWorking) {
            working.transferToViewer();
        }
        if (liveTarget != null && !viewerOwns(liveTarget.imageData())) {
            try {
                liveTarget.imageData().close();
            } catch (Exception closeFailure) {
                if (refreshFailure == null) {
                    refreshFailure = closeFailure;
                } else {
                    refreshFailure.addSuppressed(closeFailure);
                }
            }
        }
        if (refreshFailure instanceof Error fatal
                && !(refreshFailure instanceof UnsatisfiedLinkError)) {
            throw fatal;
        }
        return new CommitResult(
                savedRevision,
                viewerWasTarget,
                viewerAdoptedWorking,
                Optional.ofNullable(refreshFailure));
    }

    private static CommitResult finishCommit(WorkingImage working, CommitResult result) {
        if (result.viewerAdoptedWorkingImage()) {
            return result;
        }
        try {
            working.close();
            return result;
        } catch (RuntimeException cleanupFailure) {
            Throwable priorFailure = result.viewerRefreshFailure().orElse(null);
            if (priorFailure != null) {
                priorFailure.addSuppressed(cleanupFailure);
                return result;
            }
            return new CommitResult(
                    result.savedRevision(),
                    result.viewerWasTarget(),
                    false,
                    Optional.of(cleanupFailure));
        }
    }

    private RasterInputCodec.ImageRevisionToken captureStoredRevision(WorkingImage working)
            throws IOException {
        ImageData<BufferedImage> stored =
                Objects.requireNonNull(
                        working.entry().readImageData(),
                        "entry returned no authoritative stored ImageData");
        RasterInputCodec.ImageRevisionToken revision;
        try {
            CapturedSource source = sourceCapture.capture(stored.getServer()).prepare();
            source.requireCurrent();
            revision =
                    revisionCapture.capture(
                            working.imageId,
                            SourceIdentityCodec.imageSourceIdentityDigest(source.identity()),
                            stored,
                            working.limits);
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(stored, failure);
            throw failure;
        }
        try {
            stored.close();
        } catch (Exception error) {
            throw new IOException("failed to close authoritative stored ImageData", error);
        }
        return revision;
    }

    private LiveTarget findLiveTarget(ProjectImageEntry<BufferedImage> expectedEntry) {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            return null;
        }
        LiveTarget result = null;
        for (QuPathViewer viewer : viewers.get()) {
            ImageData<BufferedImage> imageData = viewer.getImageData();
            if (imageData == null) {
                continue;
            }
            ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
            if (!sameProjectEntry(entry, expectedEntry)) {
                continue;
            }
            if (result != null) {
                throw new IllegalStateException(
                        "project entry is open in more than one QuPath viewer");
            }
            result = new LiveTarget(viewer, imageData);
        }
        return result;
    }

    private static boolean sameProjectEntry(
            ProjectImageEntry<BufferedImage> actual, ProjectImageEntry<BufferedImage> expected) {
        if (actual == expected) {
            return true;
        }
        if (actual == null || !actual.getID().equals(expected.getID())) {
            return false;
        }
        // IDs are project-local. Reopening the same project creates new entry objects,
        // so also accept the same public storage location, never just the same source slide.
        var actualPath = actual.getEntryPath();
        var expectedPath = expected.getEntryPath();
        return actualPath != null
                && expectedPath != null
                && actualPath.toAbsolutePath().equals(expectedPath.toAbsolutePath());
    }

    private boolean viewerOwns(ImageData<BufferedImage> imageData) {
        for (QuPathViewer viewer : viewers.get()) {
            if (viewer.getImageData() == imageData) {
                return true;
            }
        }
        return false;
    }

    private <T> T onFx(Supplier<T> operation) {
        AtomicReference<T> result = new AtomicReference<>();
        fxRunner.accept(() -> result.set(operation.get()));
        return result.get();
    }

    private static void requireMatchingImageId(
            ProjectImageEntry<BufferedImage> entry, String imageId) {
        String requested = Objects.requireNonNull(imageId, "imageId");
        if (requested.isBlank() || !requested.equals(entry.getID())) {
            throw new IllegalArgumentException("imageId must equal the project entry ID");
        }
    }

    private static void saveUnchecked(
            ProjectImageEntry<BufferedImage> entry, ImageData<BufferedImage> imageData) {
        try {
            entry.saveImageData(imageData);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void closeAfterFailure(ImageData<BufferedImage> imageData, Throwable failure) {
        try {
            imageData.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeAfterFailure(WorkingImage working, Throwable failure) {
        if (working.viewerOwnsImage()) {
            return;
        }
        try {
            working.close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void restoreAfterReplacementFailure(
            QuPathViewer viewer,
            ImageData<BufferedImage> previous,
            ImageData<BufferedImage> candidate,
            Throwable failure) {
        if (viewer.getImageData() != previous) {
            try {
                viewer.setImageData(previous);
            } catch (Throwable restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }
        if (!viewerOwns(candidate)) {
            closeAfterFailure(candidate, failure);
        }
        if (previous != null && !viewerOwns(previous)) {
            closeAfterFailure(previous, failure);
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof IOException ioFailure) {
            throw new UncheckedIOException(ioFailure);
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        throw new IllegalStateException(failure);
    }

    @FunctionalInterface
    interface RevisionCapture {
        RasterInputCodec.ImageRevisionToken capture(
                String imageId,
                String imageSourceDigest,
                ImageData<?> imageData,
                ExecutionLimitsCodec.ClientExecutionLimits limits);
    }

    @FunctionalInterface
    interface SourceCapture {
        CapturedSource capture(ImageServer<BufferedImage> source) throws IOException;
    }

    private record LiveTarget(QuPathViewer viewer, ImageData<BufferedImage> imageData) {
        private LiveTarget {
            Objects.requireNonNull(viewer, "viewer");
            Objects.requireNonNull(imageData, "imageData");
        }
    }

    /** Borrowed viewer identity plus detached reader facts; used only across preparation. */
    // No live target means stored fallback; a live target without source means deliberately
    // skipped.
    private record LiveSourceCapture(LiveTarget target, Optional<CapturedSource> source) {}

    /** Caller-owned stored source kept open only while one image's package files are written. */
    public static final class ReadOnlySourceSession implements AutoCloseable {

        private final ImageData<BufferedImage> imageData;
        private final RasterInputCodec.ActionSourceRevision sourceRevision;
        private final CapturedSource capturedSource;
        private boolean closed;

        private ReadOnlySourceSession(
                ImageData<BufferedImage> imageData,
                RasterInputCodec.ActionSourceRevision sourceRevision,
                CapturedSource capturedSource) {
            this.imageData = Objects.requireNonNull(imageData, "imageData");
            this.sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            this.capturedSource = Objects.requireNonNull(capturedSource, "capturedSource");
        }

        public synchronized ImageServer<BufferedImage> server() {
            requireOpen();
            return imageData.getServer();
        }

        public RasterInputCodec.ActionSourceRevision sourceRevision() {
            return sourceRevision;
        }

        public CapturedSourcePyramid sourcePyramid() {
            return capturedSource.bind(sourceRevision.imageDataRevision());
        }

        public Optional<EligibleOriginalSource> eligibleOriginalSource() {
            return capturedSource.eligibleOriginal();
        }

        public Optional<java.nio.file.Path> unchangedOriginalPath() {
            return capturedSource.unchangedOriginalPath();
        }

        public SourceRevisionVerifier sourceRevisionVerifier() {
            return capturedSource.revisionVerifier();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                imageData.close();
            } catch (Exception error) {
                throw new IOException("failed to close read-only source ImageData", error);
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("read-only source session is closed");
            }
        }
    }

    /** Separate mutable image plus the exact authoritative revision on which it is based. */
    public static final class WorkingImage implements AutoCloseable {

        private enum State {
            OPEN,
            FINALIZING,
            VIEWER_OWNED,
            CLOSED
        }

        private final ProjectImageEntry<BufferedImage> entry;
        private final String imageId;
        private final ExecutionLimitsCodec.ClientExecutionLimits limits;
        private final ImageData<BufferedImage> imageData;
        private final RasterInputCodec.ActionSourceRevision sourceRevision;
        private final CapturedSource capturedSource;
        private State state = State.OPEN;

        private WorkingImage(
                ProjectImageEntry<BufferedImage> entry,
                String imageId,
                ExecutionLimitsCodec.ClientExecutionLimits limits,
                ImageData<BufferedImage> imageData,
                RasterInputCodec.ActionSourceRevision sourceRevision,
                CapturedSource capturedSource) {
            this.entry = entry;
            this.imageId = imageId;
            this.limits = limits;
            this.imageData = imageData;
            this.sourceRevision = sourceRevision;
            this.capturedSource = capturedSource;
        }

        public synchronized ImageData<BufferedImage> imageData() {
            if (state == State.CLOSED || state == State.VIEWER_OWNED) {
                throw new IllegalStateException("working image is no longer caller-owned");
            }
            return imageData;
        }

        public RasterInputCodec.ImageRevisionToken baseRevision() {
            return sourceRevision.imageDataRevision();
        }

        /** Exact semantic and pixel-source revision captured from the separate working image. */
        public RasterInputCodec.ActionSourceRevision sourceRevision() {
            return sourceRevision;
        }

        /** Reader-derived source pyramid accepted by the pure source-input planner. */
        public CapturedSourcePyramid sourcePyramid() {
            return capturedSource.bind(sourceRevision.imageDataRevision());
        }

        /** OpenSlide original eligibility captured from the same immutable working source. */
        public Optional<EligibleOriginalSource> eligibleOriginalSource() {
            return capturedSource.eligibleOriginal();
        }

        /** Stable unchanged source file; present exactly when original eligibility is present. */
        public Optional<java.nio.file.Path> unchangedOriginalPath() {
            return capturedSource.unchangedOriginalPath();
        }

        /** Exact source-version check reused by source materialization. */
        public SourceRevisionVerifier sourceRevisionVerifier() {
            return capturedSource.revisionVerifier();
        }

        private ProjectImageEntry<BufferedImage> entry() {
            return entry;
        }

        private RasterInputCodec.ImageRevisionToken captureRevision(
                ImageData<BufferedImage> source,
                RevisionCapture capture,
                SourceCapture sourceCapture) {
            try {
                var current = sourceCapture.capture(source.getServer());
                // Non-local captures are already complete; preserve the ordinary revision
                // comparison for a substituted object/version. Local discovery stays off FX.
                if (capturedSource.localSource().isEmpty()
                        && !capturedSource.identity().equals(current.identity()))
                    return capture.capture(
                            imageId,
                            SourceIdentityCodec.imageSourceIdentityDigest(current.identity()),
                            source,
                            limits);
                capturedSource.requireSameView(current);
                String digest =
                        SourceIdentityCodec.imageSourceIdentityDigest(capturedSource.identity());
                return capture.capture(imageId, digest, source, limits);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private void requireSourceCurrent() throws IOException {
            capturedSource.requireCurrent();
        }

        private synchronized void beginFinalization() {
            if (state != State.OPEN) {
                throw new IllegalStateException("working image can be finalized only once");
            }
            state = State.FINALIZING;
        }

        private synchronized void transferToViewer() {
            if (state != State.FINALIZING) {
                throw new IllegalStateException("only a finalizing image can transfer ownership");
            }
            state = State.VIEWER_OWNED;
        }

        private synchronized boolean viewerOwnsImage() {
            return state == State.VIEWER_OWNED;
        }

        @Override
        public synchronized void close() {
            if (state == State.CLOSED || state == State.VIEWER_OWNED) {
                return;
            }
            state = State.CLOSED;
            try {
                imageData.close();
            } catch (Exception error) {
                throw new IllegalStateException("failed to close working ImageData", error);
            }
        }
    }

    /** Successful QuPath save plus the independently fallible viewer and ownership phase. */
    public record CommitResult(
            RasterInputCodec.ImageRevisionToken savedRevision,
            boolean viewerWasTarget,
            boolean viewerAdoptedWorkingImage,
            Optional<Throwable> viewerRefreshFailure) {
        public CommitResult {
            savedRevision = Objects.requireNonNull(savedRevision, "savedRevision");
            viewerRefreshFailure =
                    Objects.requireNonNull(viewerRefreshFailure, "viewerRefreshFailure");
        }
    }

    /** Exact authoritative semantic state changed after the separate working copy was captured. */
    public static final class RevisionConflictException extends Exception {

        private final RasterInputCodec.ImageRevisionToken expected;
        private final RasterInputCodec.ImageRevisionToken actual;

        private RevisionConflictException(
                RasterInputCodec.ImageRevisionToken expected,
                RasterInputCodec.ImageRevisionToken actual) {
            super("authoritative ImageData changed before save");
            this.expected = expected;
            this.actual = actual;
        }

        public RasterInputCodec.ImageRevisionToken expected() {
            return expected;
        }

        public RasterInputCodec.ImageRevisionToken actual() {
            return actual;
        }
    }

    private static final class RevisionConflictSignal extends RuntimeException {

        private final RevisionConflictException conflict;

        private RevisionConflictSignal(RevisionConflictException conflict) {
            super(conflict);
            this.conflict = conflict;
        }
    }
}
