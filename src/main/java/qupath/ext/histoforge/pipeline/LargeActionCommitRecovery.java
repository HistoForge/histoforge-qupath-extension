package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.large.HierarchyTransactionDigester;
import qupath.ext.histoforge.large.LargeActionTypes.HierarchyTransactionIdentity;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

/** Resolves stored hierarchy state after an ambiguous or interrupted large-action save. */
final class LargeActionCommitRecovery {

    private LargeActionCommitRecovery() {}

    static Inspection inspect(
            ImageLifecycle lifecycle,
            LargeActionHandle handle,
            ProjectImageEntry<BufferedImage> entry)
            throws IOException {
        return inspect(lifecycle, handle, entry, RecoverySourceIdentity.exact());
    }

    static Inspection inspect(
            ImageLifecycle lifecycle,
            LargeActionHandle handle,
            ProjectImageEntry<BufferedImage> entry,
            RecoverySourceIdentity sourceIdentity)
            throws IOException {
        try {
            return lifecycle
                    .captureStoredReadOnly(
                            entry,
                            handle.imageId(),
                            handle.clientBuild().limits(),
                            (imageData, sourceRevision, source) -> {
                                try {
                                    return inspectStoredImage(
                                            handle,
                                            sourceRevision,
                                            imageData,
                                            () -> {
                                                sourceIdentity.requireMatch(
                                                        handle,
                                                        sourceRevision.pixelSource(),
                                                        source.unchangedOriginalPath(),
                                                        false);
                                                return sourceIdentity.historicalRevision(
                                                        handle, sourceRevision);
                                            });
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            })
                    .value();
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        } catch (LocalCapacityExceededException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            return Inspection.of(Resolution.CONFLICT);
        }
    }

    static void requireCurrentTargetAndSource(
            LargeActionHandle handle,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            ImageData<?> imageData) {
        if (!sourceRevision.pixelSource().equals(handle.baseline().pixelSource())) {
            throw new IllegalArgumentException("large-action pixel source changed after capture");
        }
        List<ExecutionScopeCodec.TargetRegion> targets =
                ImageActionPlanner.captureRegions(
                        regionSelection(handle.action()),
                        imageData.getHierarchy(),
                        sourceRevision.pixelSource().width(),
                        sourceRevision.pixelSource().height());
        if (!targets.equals(List.of(handle.target()))) {
            throw new IllegalArgumentException(
                    "large-action target geometry or plane changed after capture");
        }
    }

    private static Inspection inspectStoredImage(
            LargeActionHandle handle,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            ImageData<BufferedImage> imageData,
            CheckedSourceRevision matchedSource)
            throws IOException {
        Optional<ImageDataStateCodec.ActionCommitWitness> witness = readWitness(imageData);
        boolean matchingWitness =
                witness.filter(
                                value ->
                                        value.actionId().equals(handle.actionId())
                                                && value.handleGenerationId()
                                                        .equals(handle.handleGenerationId()))
                        .isPresent();

        HierarchyTransactionIdentity expected = handle.expectedCommittedHierarchy().orElseThrow();
        if (matchingWitness) {
            try {
                requireCurrentTargetAndSource(handle, matchedSource.get(), imageData);
                HierarchyTransactionIdentity current =
                        HierarchyTransactionDigester.captureExpected(
                                handle.target(),
                                handle.baseline().hierarchy(),
                                includesMetrics(handle),
                                imageData.getHierarchy(),
                                handle.selectionRegions());
                return current.equals(expected)
                        ? Inspection.committed(sourceRevision.imageDataRevision())
                        : Inspection.of(Resolution.SUPERSEDED);
            } catch (IllegalArgumentException laterState) {
                return Inspection.of(Resolution.SUPERSEDED);
            }
        }

        requireCurrentTargetAndSource(handle, matchedSource.get(), imageData);
        if (expected.equals(handle.baseline().hierarchy())) {
            HierarchyTransactionIdentity current =
                    HierarchyTransactionDigester.captureForRebase(
                            handle.target(),
                            handle.baseline().hierarchy(),
                            includesMetrics(handle),
                            imageData.getHierarchy(),
                            handle.selectionRegions());
            return current.equals(expected)
                    ? Inspection.committed(sourceRevision.imageDataRevision())
                    : Inspection.of(Resolution.CONFLICT);
        }

        try {
            HierarchyTransactionIdentity currentExpected =
                    HierarchyTransactionDigester.captureExpected(
                            handle.target(),
                            handle.baseline().hierarchy(),
                            includesMetrics(handle),
                            imageData.getHierarchy(),
                            handle.selectionRegions());
            if (currentExpected.equals(expected)) {
                return Inspection.of(Resolution.SUPERSEDED);
            }
        } catch (IllegalArgumentException notExpected) {
            // The baseline projection below remains independently meaningful.
        }
        HierarchyTransactionIdentity currentBaseline =
                HierarchyTransactionDigester.captureForRebase(
                        handle.target(),
                        handle.baseline().hierarchy(),
                        includesMetrics(handle),
                        imageData.getHierarchy(),
                        handle.selectionRegions());
        return Inspection.of(
                currentBaseline.equals(handle.baseline().hierarchy())
                        ? Resolution.RETRY_COMMIT
                        : Resolution.CONFLICT);
    }

    private static Optional<ImageDataStateCodec.ActionCommitWitness> readWitness(
            ImageData<?> imageData) {
        if (!imageData
                .getProperties()
                .containsKey(HistoforgePersistenceKeys.ACTION_COMMIT_WITNESS)) {
            return Optional.empty();
        }
        return Optional.of(
                ImageDataStateCodec.decodeActionCommitWitnessProperty(
                        imageData.getProperty(HistoforgePersistenceKeys.ACTION_COMMIT_WITNESS)));
    }

    private static ActionSelectionCodec.RegionSelection regionSelection(
            ActionSelectionCodec.ImageAction action) {
        ActionSelectionCodec.ModelWorkSelection work =
                action instanceof ActionSelectionCodec.Fill fill
                        ? fill.work()
                        : ((ActionSelectionCodec.Redo) action).work();
        return work instanceof ActionSelectionCodec.AllModels all
                ? all.regions()
                : ((ActionSelectionCodec.SelectedModels) work).regions();
    }

    private static boolean includesMetrics(LargeActionHandle handle) {
        return handle.module().metrics().isPresent();
    }

    @FunctionalInterface
    private interface CheckedSourceRevision {
        RasterInputCodec.ActionSourceRevision get() throws IOException;
    }

    record Inspection(
            Resolution resolution, Optional<RasterInputCodec.ImageRevisionToken> finalRevision) {
        Inspection {
            resolution = Objects.requireNonNull(resolution, "resolution");
            finalRevision = Objects.requireNonNull(finalRevision, "finalRevision");
            if ((resolution == Resolution.COMMITTED) != finalRevision.isPresent()) {
                throw new IllegalArgumentException(
                        "stored final revision is present exactly for COMMITTED");
            }
        }

        static Inspection of(Resolution resolution) {
            return new Inspection(resolution, Optional.empty());
        }

        static Inspection committed(RasterInputCodec.ImageRevisionToken finalRevision) {
            return new Inspection(Resolution.COMMITTED, Optional.of(finalRevision));
        }
    }

    enum Resolution {
        COMMITTED,
        SUPERSEDED,
        RETRY_COMMIT,
        CONFLICT
    }
}
