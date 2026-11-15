package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.ResumableAction;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.ResumeOwnership;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.Suspended;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion.SourceVersionChangedException;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

/** Explicitly advances one same-build persisted large action without automatic redispatch. */
final class RecoverableActionRecovery {

    private static final Logger logger = LoggerFactory.getLogger(RecoverableActionRecovery.class);

    @FunctionalInterface
    interface SourceAccess {
        RecoverableSourceProvisioner get()
                throws IOException, InterruptedException, InferenceException;
    }

    private final ImageActionRegistry actionRegistry;
    private final ImageLifecycle lifecycle;
    private final LargeActionHandleStore handles;
    private final SourceAccess sourceProvisioner;
    private final LargeActionCommitCoordinator commitCoordinator;
    private final RecoverableImageActionExecutor.DurableDispatch durableResume;
    private final RecoverableImageActionExecutor.CommitPreparation commitPreparation;
    private final RecoverableActionAttemptResolver attemptResolver;

    RecoverableActionRecovery(
            ImageActionRegistry actionRegistry,
            ImageLifecycle lifecycle,
            LargeActionHandleStore handles,
            SourceAccess sourceProvisioner,
            LargeActionCommitCoordinator commitCoordinator,
            RecoverableImageActionExecutor.DurableDispatch durableResume,
            RecoverableImageActionExecutor.CommitPreparation commitPreparation,
            RecoverableActionAttemptResolver attemptResolver) {
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.sourceProvisioner = Objects.requireNonNull(sourceProvisioner, "sourceProvisioner");
        this.commitCoordinator = Objects.requireNonNull(commitCoordinator, "commitCoordinator");
        this.durableResume = Objects.requireNonNull(durableResume, "durableResume");
        this.commitPreparation = Objects.requireNonNull(commitPreparation, "commitPreparation");
        this.attemptResolver = Objects.requireNonNull(attemptResolver, "attemptResolver");
    }

    RecoverableActionAttempt resume(
            Suspended suspended, ProjectImageEntry<BufferedImage> entry, BooleanSupplier shouldStop)
            throws IOException, InferenceException {
        requireEntry(entry, suspended.lastObservedHandle().imageId());
        return resume(suspended.claimForResume(), entry, shouldStop);
    }

    RecoverableActionAttempt resume(
            String actionId,
            String handleGenerationId,
            ProjectImageEntry<BufferedImage> entry,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        var handle = handles.requireResumeCandidate(actionId, handleGenerationId);
        requireEntry(entry, handle.imageId());
        var reservation = actionRegistry.reserve(handle.imageId());
        return resume(
                new ResumeOwnership(
                        handle, Optional.empty(), List.of(), reservation, Optional.empty()),
                entry,
                shouldStop);
    }

    RecoverableActionAttempt resume(
            ResumeOwnership resumeOwnership,
            ProjectImageEntry<BufferedImage> entry,
            BooleanSupplier shouldStop)
            throws IOException, InferenceException {
        return resume(resumeOwnership, entry, false, shouldStop);
    }

    RecoverableActionAttempt resume(
            ResumeOwnership resumeOwnership,
            ProjectImageEntry<BufferedImage> entry,
            boolean retainReservation,
            BooleanSupplier shouldStop)
            throws IOException, InferenceException {
        ResumeOwnership resume = Objects.requireNonNull(resumeOwnership, "resumeOwnership");
        LargeActionHandle candidate = resume.lastObservedHandle();
        ResumableAction retained = null;
        ImageActionRegistry.Reservation ownership = resume.reservation();
        ProjectImageEntry<BufferedImage> imageEntry = Objects.requireNonNull(entry, "entry");
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        String imageId = CanonicalCodec.requireNonblank(candidate.imageId(), "image_id");
        requireEntry(imageEntry, imageId);
        if (!ownership.imageId().equals(imageId)) {
            throw new IllegalArgumentException(
                    "resume reservation belongs to another project image");
        }

        boolean reservationTransferred = false;
        ImageExecutionPlan targetPlan = resume.lastObservedPlan().orElse(null);
        List<RunResults.ScopeWorkResult> observedWork = resume.observedWork();
        LargeActionCommitCoordinator.TransactionPreparation preparation = null;
        try {
            candidate =
                    handles.requireResumeCandidate(
                            candidate.actionId(), candidate.handleGenerationId());
            RecoverySourceIdentity sourceIdentity =
                    RecoverySourceIdentity.forResume(candidate, stop);

            if (candidate.phase() == LargeActionPhase.RESULT_READY) {
                Optional<LargeActionCommitResult> resolution =
                        commitCoordinator.resolveResultReady(
                                candidate.actionId(),
                                candidate.handleGenerationId(),
                                ownership,
                                imageEntry,
                                sourceIdentity);
                if (resolution.isPresent()) {
                    return attemptResolver.completed(
                            imageId, targetPlan, observedWork, resolution.orElseThrow());
                }
                requireRunning(stop);
            }

            retained =
                    handles.requireResumable(candidate.actionId(), candidate.handleGenerationId());

            if (retained.handle().phase() != LargeActionPhase.RESULT_READY) {
                RecoveryCapture capture =
                        capture(retained, ownership, imageEntry, stop, false, sourceIdentity);
                retained = capture.action();
                boolean captureClosed = false;
                targetPlan = capture.plan();
                try {
                    RecoverableSourceProvisioner.ProvisionedSource source;
                    try {
                        source = provisionSource(capture, stop);
                    } catch (
                            RecoverableSourceProvisioner.SourceArtifactUnavailableException
                                    unavailable) {
                        if (capture.action().handle().hasRetainedModelRunRequest()) {
                            throw unavailable;
                        }
                        if (sourceIdentity.reconnected()) {
                            throw new IOException(
                                    "the reconnected original's upload is unavailable; start a new action to upload it again",
                                    unavailable);
                        }
                        closeWorking(capture.working());
                        captureClosed = true;
                        capture =
                                capture(
                                        capture.action(),
                                        ownership,
                                        imageEntry,
                                        stop,
                                        true,
                                        sourceIdentity);
                        captureClosed = false;
                        retained = capture.action();
                        targetPlan = capture.plan();
                        source = provisionSource(capture, stop);
                    }
                    retained = source.action();
                    RecipeExecutor.ExecutionInputs inputs =
                            new RecipeExecutor.ExecutionInputs(
                                    capture.sourcePlan(),
                                    Map.of(retained.handle().target().key(), source.source()),
                                    capture.sourcePlan()
                                            .targets()
                                            .getFirst()
                                            .sourcePlan()
                                            .orElseThrow()
                                            .sourceRevision());
                    var working = capture.working();
                    DurableRecipeDispatcher.CompletedDispatch dispatched =
                            durableResume.dispatch(
                                    retained,
                                    targetPlan,
                                    inputs,
                                    capture.working().imageData(),
                                    (journal, shardIndex, runs, run, deliveryStop) ->
                                            sourceProvisioner
                                                    .get()
                                                    .imageDelivery(working, null)
                                                    .deliver(
                                                            journal,
                                                            shardIndex,
                                                            runs,
                                                            run,
                                                            deliveryStop),
                                    stop);
                    retained = dispatched.action();
                    observedWork = dispatched.outcomes();
                    preparation = dispatched.preparedResult();
                } finally {
                    capture.preparedUpload()
                            .ifPresent(RecoverableSourceProvisioner.PreparedUpload::close);
                    if (!captureClosed) {
                        closeWorking(capture.working());
                    }
                }
            }

            requireRunning(stop);
            if (preparation == null) {
                preparation = commitPreparation.prepare(retained, stop);
            }
            requireRunning(stop);
            LargeActionCommitResult completion =
                    commitCoordinator.complete(
                            retained.handle().actionId(),
                            retained.handle().handleGenerationId(),
                            ownership,
                            imageEntry,
                            preparation,
                            stop,
                            sourceIdentity);
            return attemptResolver.completed(imageId, targetPlan, observedWork, completion);
        } catch (TargetRecipeTransaction.TargetTransactionFailure failure) {
            RecoverableActionAttempt terminal =
                    attemptResolver.resolveFailure(
                            imageId,
                            currentHandle(retained, candidate),
                            targetPlan,
                            failure.outcomes(),
                            failure.failure(),
                            failure.originalCause(),
                            ownership);
            reservationTransferred = terminal instanceof Suspended;
            return terminal;
        } catch (LargeActionTransactionValidator.Conflict conflict) {
            RecoverableActionAttempt terminal =
                    attemptResolver.conflict(
                            imageId,
                            currentHandle(retained, candidate),
                            targetPlan,
                            observedWork,
                            conflict,
                            ownership);
            reservationTransferred = terminal instanceof Suspended;
            return terminal;
        } catch (TargetRecipeTransaction.TargetTransactionCancelled cancelled) {
            RecoverableActionAttempt terminal =
                    attemptResolver.cancel(
                            imageId,
                            currentHandle(retained, candidate),
                            targetPlan,
                            cancelled.outcomes(),
                            cancelled,
                            ownership,
                            false);
            reservationTransferred = terminal instanceof Suspended;
            return terminal;
        } catch (CancellationException cancelled) {
            RecoverableActionAttempt terminal =
                    attemptResolver.cancel(
                            imageId,
                            currentHandle(retained, candidate),
                            targetPlan,
                            observedWork,
                            cancelled,
                            ownership,
                            false);
            reservationTransferred = terminal instanceof Suspended;
            return terminal;
        } catch (InterruptedException interrupted) {
            RecoverableActionAttempt terminal =
                    attemptResolver.cancel(
                            imageId,
                            currentHandle(retained, candidate),
                            targetPlan,
                            observedWork,
                            interrupted,
                            ownership,
                            true);
            reservationTransferred = terminal instanceof Suspended;
            return terminal;
        } catch (InferenceException failure) {
            RecoverableActionAttempt terminal =
                    attemptResolver.resolveFailure(
                            imageId,
                            currentHandle(retained, candidate),
                            targetPlan,
                            observedWork,
                            RecipeFailures.detail(failure),
                            failure,
                            ownership);
            reservationTransferred = terminal instanceof Suspended;
            return terminal;
        } catch (IOException failure) {
            RecoverableActionAttempt terminal =
                    attemptResolver.resolveFailure(
                            imageId,
                            currentHandle(retained, candidate),
                            targetPlan,
                            observedWork,
                            RecipeFailures.detail(failure),
                            failure,
                            ownership);
            reservationTransferred = terminal instanceof Suspended;
            return terminal;
        } catch (LocalCapacityExceededException failure) {
            RecoverableActionAttempt terminal =
                    attemptResolver.resolveFailure(
                            imageId,
                            currentHandle(retained, candidate),
                            targetPlan,
                            observedWork,
                            ImageResultReducer.failure(
                                    RunResults.FailureType.LOCAL_CAPACITY, failure),
                            failure,
                            ownership);
            reservationTransferred = terminal instanceof Suspended;
            return terminal;
        } catch (RuntimeException failure) {
            Suspended suspended =
                    attemptResolver.suspendUnexpected(
                            currentHandle(retained, candidate),
                            targetPlan,
                            observedWork,
                            failure,
                            ownership);
            reservationTransferred = true;
            return suspended;
        } finally {
            if (!reservationTransferred && !retainReservation) {
                ownership.close();
            }
        }
    }

    private RecoveryCapture capture(
            ResumableAction action,
            ImageActionRegistry.Reservation reservation,
            ProjectImageEntry<BufferedImage> entry,
            BooleanSupplier shouldStop,
            boolean replaceUnavailableSource,
            RecoverySourceIdentity sourceIdentity)
            throws IOException, InterruptedException, InferenceException {
        try (ImageActionRegistry.Lease ignored = reservation.acquireLease()) {
            requireRunning(shouldStop);
            LargeActionHandle handle = action.handle();
            ImageLifecycle.WorkingImage working =
                    lifecycle.openWorkingImage(
                            entry, handle.imageId(), handle.clientBuild().limits());
            try {
                requireRunning(shouldStop);
                ImageExecutionPlan plan = replan(handle, working.imageData());
                RecoverableSourceProvisioner.requireCurrentBaseline(handle, working);
                SourceInputPlan sourcePlan = sourcePlan(action, plan, working, sourceIdentity);
                SourceInputPlan.TargetPlanningResult target =
                        requireOneRecoverableTarget(sourcePlan);
                if (replaceUnavailableSource) {
                    RecoverableSourceProvisioner.PreparedUpload upload =
                            sourceProvisioner
                                    .get()
                                    .replaceUnavailable(action, plan, target, working, shouldStop);
                    return RecoveryCapture.prepared(
                            upload.action(), plan, sourcePlan, working, upload);
                }
                if (handle.phase() == LargeActionPhase.PREPARING) {
                    if (handle.sourcePlan() instanceof SourceInputPlan.IncrementalSourcePlan) {
                        var captured =
                                RecoverableSourceProvisioner.capture(
                                        action, plan, target, working, handles);
                        return RecoveryCapture.remote(
                                captured.action(), plan, sourcePlan, working, Optional.empty());
                    }
                    RecoverableSourceProvisioner.PreparedUpload upload =
                            sourceProvisioner
                                    .get()
                                    .prepare(action, plan, target, working, shouldStop);
                    return RecoveryCapture.prepared(
                            upload.action(), plan, sourcePlan, working, upload);
                }
                if (handle.phase() != LargeActionPhase.UPLOADING
                        && handle.phase() != LargeActionPhase.EXECUTING) {
                    throw new IllegalStateException(
                            "unsupported resumable large-action phase " + handle.phase());
                }
                Optional<Path> unchangedOriginalSource = Optional.empty();
                if (handle.phase() == LargeActionPhase.UPLOADING
                        && handle.source().orElseThrow().ownedUploadFile().isEmpty()) {
                    unchangedOriginalSource = working.unchangedOriginalPath();
                    if (unchangedOriginalSource.isEmpty()) {
                        throw new SourceVersionChangedException(
                                "original source is no longer available for recovery");
                    }
                }
                return RecoveryCapture.remote(
                        action, plan, sourcePlan, working, unchangedOriginalSource);
            } catch (IOException
                    | InterruptedException
                    | InferenceException
                    | RuntimeException
                    | Error failure) {
                closeAfterFailure(working, failure);
                throw failure;
            }
        }
    }

    private RecoverableSourceProvisioner.ProvisionedSource provisionSource(
            RecoveryCapture capture, BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        if (capture.preparedUpload().isPresent()) {
            return sourceProvisioner
                    .get()
                    .publish(capture.preparedUpload().orElseThrow(), shouldStop);
        }
        if (capture.action().handle().sourcePlan() instanceof SourceInputPlan.IncrementalSourcePlan
                || canReplayBeforeSourceCheck(capture.action().handle())) {
            // Completed predictions keep their historical source identity, not a dependency on
            // its old upload. Any new run must pass backend sealed-source/lifetime admission.
            return new RecoverableSourceProvisioner.ProvisionedSource(
                    capture.action(),
                    RecoverableSourceProvisioner.retainedSource(capture.action().handle()));
        }
        LargeActionPhase phase = capture.action().handle().phase();
        if (phase == LargeActionPhase.UPLOADING) {
            return sourceProvisioner
                    .get()
                    .resume(capture.action(), capture.unchangedOriginalSource(), shouldStop);
        }
        if (phase != LargeActionPhase.EXECUTING) {
            throw new IllegalStateException(
                    "recoverable dispatch requires UPLOADING or EXECUTING source state");
        }
        return sourceProvisioner.get().verifySealed(capture.action(), shouldStop);
    }

    private static boolean canReplayBeforeSourceCheck(LargeActionHandle handle) {
        // A recorded zero-input model step also replays without its historical source upload.
        return handle.phase() == LargeActionPhase.EXECUTING && !handle.steps().isEmpty();
    }

    private SourceInputPlan sourcePlan(
            ResumableAction action,
            ImageExecutionPlan plan,
            ImageLifecycle.WorkingImage working,
            RecoverySourceIdentity sourceIdentity)
            throws IOException {
        try {
            var sourcePyramid =
                    LargeActionRecipePlan.planningSourcePyramid(
                            sourceIdentity.planningPyramid(action.handle(), working),
                            action.authorizedInputs());
            if (action.handle().phase() == LargeActionPhase.PREPARING
                    || action.handle().sourcePlan()
                            instanceof SourceInputPlan.IncrementalSourcePlan) {
                return RecipeSourcePlanner.planAgainstCapturedSource(
                        plan,
                        sourcePyramid,
                        working.imageData().getHierarchy(),
                        action.handle().sourcePlan());
            }
            return RecipeSourcePlanner.planAgainstPublishedSource(
                    plan,
                    sourcePyramid,
                    working.imageData().getHierarchy(),
                    action.sourceStartRequest().orElseThrow());
        } catch (IllegalArgumentException mismatch) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh source plan differs from the retained large action", mismatch);
        }
    }

    private static ImageExecutionPlan replan(
            LargeActionHandle handle, ImageData<BufferedImage> image) {
        try {
            ImageExecutionPlan plan =
                    ImageActionPlanner.plan(
                                    handle.actionId(),
                                    handle.clientBuild(),
                                    handle.imageId(),
                                    handle.module(),
                                    handle.action(),
                                    image.getHierarchy(),
                                    image.getServer().getWidth(),
                                    image.getServer().getHeight())
                            .withSelectionRegions(handle.selectionRegions());
            if (!plan.targetRegions().equals(List.of(handle.target()))) {
                throw new LargeActionTransactionValidator.Conflict(
                        "fresh image plan differs from the retained large-action target");
            }
            return plan;
        } catch (LargeActionTransactionValidator.Conflict conflict) {
            throw conflict;
        } catch (IllegalArgumentException mismatch) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh image plan differs from the retained large action", mismatch);
        }
    }

    private static SourceInputPlan.TargetPlanningResult requireOneRecoverableTarget(
            SourceInputPlan sourcePlan) {
        SourceInputPlan plan = Objects.requireNonNull(sourcePlan, "sourcePlan");
        if (plan.targets().size() != 1) {
            throw new IllegalArgumentException("recoverable execution requires exactly one target");
        }
        SourceInputPlan.TargetPlanningResult target = plan.targets().getFirst();
        if (target.sourcePlan().isEmpty()
                || target.sourcePlan().orElseThrow().protocol()
                        != SourceInputPlan.ExecutionProtocol.RECOVERABLE) {
            throw new IllegalArgumentException(
                    "recoverable execution requires one nonempty recoverable target");
        }
        return target;
    }

    private static void requireEntry(ProjectImageEntry<?> entry, String imageId) {
        if (!imageId.equals(CanonicalCodec.requireNonblank(entry.getID(), "entry.image_id"))) {
            throw new IllegalArgumentException(
                    "project image entry belongs to another large action");
        }
    }

    private static LargeActionHandle currentHandle(
            ResumableAction action, LargeActionHandle candidate) {
        return action == null ? candidate : action.handle();
    }

    private static void closeAfterFailure(ImageLifecycle.WorkingImage working, Throwable original) {
        try {
            working.close();
        } catch (RuntimeException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private static void closeWorking(ImageLifecycle.WorkingImage working) {
        try {
            working.close();
        } catch (RuntimeException failure) {
            logger.warn("Failed to close a recovered disposable working image", failure);
        }
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("recoverable image action cancelled");
        }
    }

    private record RecoveryCapture(
            ResumableAction action,
            ImageExecutionPlan plan,
            SourceInputPlan sourcePlan,
            ImageLifecycle.WorkingImage working,
            Optional<RecoverableSourceProvisioner.PreparedUpload> preparedUpload,
            Optional<Path> unchangedOriginalSource) {
        private RecoveryCapture {
            action = Objects.requireNonNull(action, "action");
            plan = Objects.requireNonNull(plan, "plan");
            sourcePlan = Objects.requireNonNull(sourcePlan, "sourcePlan");
            working = Objects.requireNonNull(working, "working");
            preparedUpload = Objects.requireNonNull(preparedUpload, "preparedUpload");
            unchangedOriginalSource =
                    Objects.requireNonNull(unchangedOriginalSource, "unchangedOriginalSource");
            if (preparedUpload.isPresent()
                    && !preparedUpload.orElseThrow().action().equals(action)) {
                throw new IllegalArgumentException(
                        "prepared recovery upload does not match its action");
            }
            if (unchangedOriginalSource.isPresent()
                    && action.handle().phase() != LargeActionPhase.UPLOADING) {
                throw new IllegalArgumentException(
                        "unchanged original path belongs only to source recovery");
            }
        }

        private static RecoveryCapture prepared(
                ResumableAction action,
                ImageExecutionPlan plan,
                SourceInputPlan sourcePlan,
                ImageLifecycle.WorkingImage working,
                RecoverableSourceProvisioner.PreparedUpload upload) {
            return new RecoveryCapture(
                    action, plan, sourcePlan, working, Optional.of(upload), Optional.empty());
        }

        private static RecoveryCapture remote(
                ResumableAction action,
                ImageExecutionPlan plan,
                SourceInputPlan sourcePlan,
                ImageLifecycle.WorkingImage working,
                Optional<Path> unchangedOriginalSource) {
            return new RecoveryCapture(
                    action, plan, sourcePlan, working, Optional.empty(), unchangedOriginalSource);
        }
    }
}
