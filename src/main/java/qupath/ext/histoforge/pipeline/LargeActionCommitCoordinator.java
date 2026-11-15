package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.large.HierarchyTransactionDigester;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.ResumableAction;
import qupath.ext.histoforge.large.LargeActionTypes.HierarchyTransactionIdentity;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.pipeline.LargeActionCommitRecovery.Inspection;
import qupath.ext.histoforge.pipeline.LargeActionCommitRecovery.Resolution;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.lib.projects.ProjectImageEntry;

/** Sequences the one-save final commit and crash resolution of a recoverable large action. */
public final class LargeActionCommitCoordinator {

    private final LargeActionHandleStore handles;
    private final ImageLifecycle lifecycle;
    private final Clock clock;

    public LargeActionCommitCoordinator(LargeActionHandleStore handles, ImageLifecycle lifecycle) {
        this(handles, lifecycle, Clock.systemUTC());
    }

    LargeActionCommitCoordinator(
            LargeActionHandleStore handles, ImageLifecycle lifecycle, Clock clock) {
        this.handles = Objects.requireNonNull(handles, "handles");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Stage completed model and ordinary-script work without running metrics. */
    @FunctionalInterface
    public interface TransactionPreparation {
        /** Fail before the image lease if preparation belongs to another retained action state. */
        default void requireAction(ResumableAction action) {}

        HierarchyDelta stageModelDelta(
                ImageLifecycle.WorkingImage working, ModelOutputCommitter committer);

        /** Use verified historical source identity only for reproducing recorded model inputs. */
        default HierarchyDelta stageModelDelta(
                ImageLifecycle.WorkingImage working,
                ModelOutputCommitter committer,
                CapturedSourcePyramid planningSource) {
            return stageModelDelta(working, committer);
        }
    }

    /**
     * Finish an EXECUTING action or resolve/retry an already RESULT_READY action.
     *
     * <p>The caller retains the image's long-lived mutation reservation. This method acquires its
     * short image lease, so read-only metric/export capture may continue while backend work runs
     * but cannot overlap final rebase or save.
     */
    public LargeActionCommitResult complete(
            String actionId,
            String handleGenerationId,
            ImageActionRegistry.Reservation reservation,
            ProjectImageEntry<BufferedImage> entry,
            TransactionPreparation preparation)
            throws IOException, InterruptedException {
        return complete(actionId, handleGenerationId, reservation, entry, preparation, () -> false);
    }

    /** Complete one retained action, discarding prepared work if cancellation wins first. */
    public LargeActionCommitResult complete(
            String actionId,
            String handleGenerationId,
            ImageActionRegistry.Reservation reservation,
            ProjectImageEntry<BufferedImage> entry,
            TransactionPreparation preparation,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException {
        return complete(
                actionId,
                handleGenerationId,
                reservation,
                entry,
                preparation,
                shouldStop,
                RecoverySourceIdentity.exact());
    }

    LargeActionCommitResult complete(
            String actionId,
            String handleGenerationId,
            ImageActionRegistry.Reservation reservation,
            ProjectImageEntry<BufferedImage> entry,
            TransactionPreparation preparation,
            BooleanSupplier shouldStop,
            RecoverySourceIdentity sourceIdentity)
            throws IOException, InterruptedException {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(preparation, "preparation");
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        LargeActionHandle handle = handles.requireResumeCandidate(actionId, handleGenerationId);
        if (!reservation.imageId().equals(handle.imageId())) {
            throw new IllegalArgumentException(
                    "image reservation belongs to another large-action image");
        }
        if (handle.phase() == LargeActionPhase.RESULT_READY) {
            Optional<LargeActionCommitResult> resolution =
                    resolveResultReady(
                            actionId, handleGenerationId, reservation, entry, sourceIdentity);
            if (resolution.isPresent()) {
                return resolution.orElseThrow();
            }
        }

        ResumableAction resumable = handles.requireCommitReady(actionId, handleGenerationId);
        preparation.requireAction(resumable);
        try (ImageActionRegistry.Lease ignored = reservation.acquireLease()) {
            if (resumable.handle().phase() == LargeActionPhase.RESULT_READY) {
                Inspection inspection =
                        LargeActionCommitRecovery.inspect(
                                lifecycle, resumable.handle(), entry, sourceIdentity);
                if (inspection.resolution() != Resolution.RETRY_COMMIT) {
                    return terminalize(resumable.handle(), inspection);
                }
            }
            requireRunning(stop);
            return prepareAndCommit(resumable, entry, preparation, stop, sourceIdentity);
        }
    }

    /**
     * Resolve an already RESULT_READY action before any reconstruction files are consumed.
     *
     * <p>An empty result means the stored image is still at the captured baseline and commit must
     * be reconstructed. A terminal result is authoritative even if now-unneeded local source or
     * model-request files were lost after the ambiguous save.
     */
    Optional<LargeActionCommitResult> resolveResultReady(
            String actionId,
            String handleGenerationId,
            ImageActionRegistry.Reservation reservation,
            ProjectImageEntry<BufferedImage> entry)
            throws IOException, InterruptedException {
        return resolveResultReady(
                actionId, handleGenerationId, reservation, entry, RecoverySourceIdentity.exact());
    }

    Optional<LargeActionCommitResult> resolveResultReady(
            String actionId,
            String handleGenerationId,
            ImageActionRegistry.Reservation reservation,
            ProjectImageEntry<BufferedImage> entry,
            RecoverySourceIdentity sourceIdentity)
            throws IOException, InterruptedException {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(entry, "entry");
        LargeActionHandle handle = handles.requireResumeCandidate(actionId, handleGenerationId);
        if (handle.phase() != LargeActionPhase.RESULT_READY) {
            throw new IllegalArgumentException("commit resolution requires RESULT_READY");
        }
        if (!reservation.imageId().equals(handle.imageId())) {
            throw new IllegalArgumentException(
                    "image reservation belongs to another large-action image");
        }
        try (ImageActionRegistry.Lease ignored = reservation.acquireLease()) {
            Inspection inspection =
                    LargeActionCommitRecovery.inspect(lifecycle, handle, entry, sourceIdentity);
            return inspection.resolution() == Resolution.RETRY_COMMIT
                    ? Optional.empty()
                    : Optional.of(terminalize(handle, inspection));
        }
    }

    private LargeActionCommitResult prepareAndCommit(
            ResumableAction resumable,
            ProjectImageEntry<BufferedImage> entry,
            TransactionPreparation preparation,
            BooleanSupplier shouldStop,
            RecoverySourceIdentity sourceIdentity)
            throws IOException {
        LargeActionHandle handle = resumable.handle();
        try (ImageLifecycle.WorkingImage working =
                lifecycle.openWorkingImage(
                        entry, handle.imageId(), handle.clientBuild().limits())) {
            final HierarchyTransactionDigester.RebaseCapture current;
            final CapturedSourcePyramid planningSource;
            try {
                planningSource = sourceIdentity.planningPyramid(handle, working);
                LargeActionCommitRecovery.requireCurrentTargetAndSource(
                        handle, planningSource.sourceRevision(), working.imageData());
                current =
                        HierarchyTransactionDigester.captureForCommit(
                                handle.target(),
                                handle.baseline().hierarchy(),
                                includesMetrics(handle),
                                working.imageData().getHierarchy(),
                                handle.selectionRegions());
                if (!current.identity().equals(handle.baseline().hierarchy())) {
                    return conflict(handle, "target hierarchy changed after large-action capture");
                }
            } catch (IllegalArgumentException failure) {
                return conflict(handle, failure.getMessage());
            }

            ModelOutputCommitter committer =
                    new ModelOutputCommitter(working.imageData(), handle.clientBuild().limits());
            final HierarchyDelta modelDelta;
            try {
                modelDelta =
                        Objects.requireNonNull(
                                preparation.stageModelDelta(working, committer, planningSource),
                                "prepared model delta");
                LargeActionTransactionValidator.requireModelDelta(
                        handle,
                        modelDelta,
                        current,
                        resumable.authorizedInputs(),
                        resumable.authorizedRuns());
            } catch (LargeActionTransactionValidator.Conflict mismatch) {
                return conflict(handle, mismatch.getMessage());
            }
            requireRunning(shouldStop);
            ModelOutputCommitter.ApplyResult modelApply = committer.apply(modelDelta);

            final HierarchyTransactionIdentity expected;
            try {
                expected =
                        HierarchyTransactionDigester.captureExpected(
                                handle.target(),
                                handle.baseline().hierarchy(),
                                includesMetrics(handle),
                                working.imageData().getHierarchy(),
                                handle.selectionRegions());
            } catch (IllegalArgumentException mismatch) {
                return conflict(handle, mismatch.getMessage());
            }

            // This is the final cancellable boundary. RESULT_READY publication and the following
            // QuPath save/no-save decision are one noninterruptible commit cut.
            requireRunning(shouldStop);
            LargeActionHandle resultReady = handle;
            if (handle.phase() == LargeActionPhase.EXECUTING) {
                resultReady = transition(handle, LargeActionPhase.RESULT_READY, expected, null);
            } else if (!handle.expectedCommittedHierarchy().orElseThrow().equals(expected)) {
                return conflict(
                        handle,
                        "reconstructed hierarchy differs from persisted RESULT_READY state");
            }

            try {
                if (resultReady.baseline().hierarchy().equals(expected)) {
                    RasterInputCodec.ImageRevisionToken finalRevision =
                            lifecycle.finishUnchanged(working);
                    LargeActionHandle committed =
                            transition(resultReady, LargeActionPhase.COMMITTED, expected, null);
                    return completed(
                            committed,
                            Optional.empty(),
                            finalRevision,
                            ImageResultReducer.warnings(modelApply.missingGenerationReferences()));
                }

                ImageDataStateCodec.ActionCommitWitness witness =
                        new ImageDataStateCodec.ActionCommitWitness(
                                ImageDataStateCodec.ACTION_COMMIT_WITNESS_SCHEMA_VERSION,
                                resultReady.actionId(),
                                resultReady.handleGenerationId());
                working.imageData()
                        .setProperty(
                                HistoforgePersistenceKeys.ACTION_COMMIT_WITNESS,
                                ImageDataStateCodec.encodeActionCommitWitness(witness));
                ImageLifecycle.CommitResult committedImage = lifecycle.commit(working);
                LargeActionHandle committed =
                        transition(resultReady, LargeActionPhase.COMMITTED, expected, null);
                return completed(
                        committed,
                        Optional.of(committedImage),
                        committedImage.savedRevision(),
                        ImageResultReducer.warnings(modelApply.missingGenerationReferences()));
            } catch (ImageLifecycle.RevisionConflictException conflict) {
                return conflict(resultReady, conflict.getMessage());
            }
        }
    }

    private LargeActionCommitResult terminalize(LargeActionHandle handle, Inspection inspection)
            throws IOException {
        return switch (inspection.resolution()) {
            case COMMITTED ->
                    new LargeActionCommitResult(
                            transition(
                                    handle,
                                    LargeActionPhase.COMMITTED,
                                    handle.expectedCommittedHierarchy().orElseThrow(),
                                    null),
                            Optional.empty(),
                            inspection.finalRevision(),
                            List.of());
            case SUPERSEDED ->
                    new LargeActionCommitResult(
                            transition(
                                    handle,
                                    LargeActionPhase.SUPERSEDED,
                                    handle.expectedCommittedHierarchy().orElseThrow(),
                                    null),
                            Optional.empty(),
                            Optional.empty(),
                            List.of());
            case CONFLICT -> conflict(handle, "stored large-action commit state conflicts");
            case RETRY_COMMIT ->
                    throw new IllegalStateException(
                            "retry resolution must reconstruct before terminalization");
        };
    }

    private LargeActionCommitResult conflict(LargeActionHandle handle, String message)
            throws IOException {
        String detail =
                message == null || message.isBlank()
                        ? "large-action target transaction conflicts"
                        : message;
        LargeActionHandle conflicted =
                transition(
                        handle,
                        LargeActionPhase.CONFLICT,
                        handle.expectedCommittedHierarchy().orElse(null),
                        new RunResults.FailureDetail(
                                RunResults.FailureType.REVISION_CONFLICT, detail));
        return new LargeActionCommitResult(
                conflicted, Optional.empty(), Optional.empty(), List.of());
    }

    private static LargeActionCommitResult completed(
            LargeActionHandle handle,
            Optional<ImageLifecycle.CommitResult> commitResult,
            RasterInputCodec.ImageRevisionToken finalRevision,
            List<RunResults.MissingGenerationWarning> warnings) {
        return new LargeActionCommitResult(
                handle, commitResult, Optional.of(finalRevision), warnings);
    }

    private LargeActionHandle transition(
            LargeActionHandle current,
            LargeActionPhase phase,
            HierarchyTransactionIdentity expected,
            RunResults.FailureDetail failure)
            throws IOException {
        Instant updated = Instant.ofEpochMilli(clock.instant().toEpochMilli());
        if (updated.isBefore(current.updatedAt())) {
            updated = current.updatedAt();
        }
        LargeActionHandle next =
                new LargeActionHandle(
                        current.schemaVersion(),
                        current.actionId(),
                        current.handleGenerationId(),
                        current.clientBuild(),
                        current.imageId(),
                        current.module(),
                        current.action(),
                        current.target(),
                        current.baseline(),
                        current.sourcePlan(),
                        current.source(),
                        current.steps(),
                        Optional.ofNullable(expected),
                        phase,
                        Optional.ofNullable(failure),
                        current.createdAt(),
                        updated,
                        current.selectionRegions());
        return handles.save(next);
    }

    private static boolean includesMetrics(LargeActionHandle handle) {
        return handle.module().metrics().isPresent();
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("large-action commit cancelled before persistence");
        }
    }
}
