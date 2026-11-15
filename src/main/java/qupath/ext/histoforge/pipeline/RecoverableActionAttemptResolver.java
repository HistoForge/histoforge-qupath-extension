package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.RecoveryReferenceConflictException;
import qupath.ext.histoforge.large.LargeActionHandleStore.UnrecoverableRecoveryStateException;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.Completed;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.Suspended;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion.SourceVersionChangedException;

/** Reduces recoverable attempt completion and failure to persisted-state truth. */
final class RecoverableActionAttemptResolver {

    private final LargeActionHandleStore handles;

    RecoverableActionAttemptResolver(LargeActionHandleStore handles) {
        this.handles = Objects.requireNonNull(handles, "handles");
    }

    Completed completed(
            String imageId,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            LargeActionCommitResult completion) {
        // Witness-only recovery proves the stored save, not that a live viewer adopted it.
        // Do not reopen/flush that viewer for later work. Fresh no-change commits are safe.
        boolean witnessedSave =
                completion.handle().phase() == LargeActionPhase.COMMITTED
                        && completion.imageCommit().isEmpty()
                        && !completion
                                .handle()
                                .baseline()
                                .hierarchy()
                                .equals(
                                        completion
                                                .handle()
                                                .expectedCommittedHierarchy()
                                                .orElseThrow());
        boolean automaticContinuationAllowed =
                completion.handle().phase() != LargeActionPhase.SUPERSEDED && !witnessedSave;
        return new Completed(
                terminalResult(imageId, plan, observedWork, completion),
                automaticContinuationAllowed);
    }

    RecoverableActionAttempt cancel(
            String imageId,
            LargeActionHandle retained,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            Throwable cancellation,
            ImageActionRegistry.Reservation reservation,
            boolean restoreInterrupted)
            throws UnrecoverableRecoveryStateException {
        boolean wasInterrupted = Thread.interrupted();
        try {
            try {
                abandon(retained);
            } catch (UnrecoverableRecoveryStateException abandonmentFailure) {
                abandonmentFailure.addSuppressed(cancellation);
                throw abandonmentFailure;
            } catch (IOException abandonmentFailure) {
                abandonmentFailure.addSuppressed(cancellation);
                return suspend(retained, plan, observedWork, abandonmentFailure, reservation);
            }
            return new Completed(cancelledResult(imageId, plan, observedWork, cancellation));
        } finally {
            if (restoreInterrupted || wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    RecoverableActionAttempt resolveFailure(
            String imageId,
            LargeActionHandle retained,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            RunResults.FailureDetail terminalDetail,
            Exception failure,
            ImageActionRegistry.Reservation reservation)
            throws IOException, InferenceException {
        if (failure instanceof RecoveryReferenceConflictException
                || failure instanceof SourceVersionChangedException) {
            return conflict(imageId, retained, plan, observedWork, failure, reservation);
        }
        if (failure instanceof UnrecoverableRecoveryStateException
                || failure instanceof ModelGatewayExecutor.PreparedSourceCorruptException) {
            return fail(
                    imageId,
                    retained,
                    plan,
                    observedWork,
                    ImageResultReducer.failure(RunResults.FailureType.CORRUPT_IMAGE_STATE, failure),
                    failure,
                    reservation);
        }
        if (failure instanceof GatewayTransport.DeploymentChangedException) {
            return fail(
                    imageId,
                    retained,
                    plan,
                    observedWork,
                    ImageResultReducer.failure(RunResults.FailureType.BACKEND, failure),
                    failure,
                    reservation);
        }
        if (failure instanceof InferenceException inference
                && retained != null
                && retained.phase() == LargeActionPhase.RESULT_READY
                && isMissingImmutableResult(inference)) {
            return fail(
                    imageId, retained, plan, observedWork, terminalDetail, failure, reservation);
        }
        if (failure instanceof DurableRunResolver.RecoverableTerminalException) {
            if (!hasUnresolvedHandle(retained, failure)) {
                throw new IllegalStateException(
                        "replaceable durable terminal belongs to an already-terminal action",
                        failure);
            }
            return suspend(retained, plan, observedWork, failure, reservation);
        }
        if (failure instanceof RecoverableSourceProvisioner.SourceArtifactUnavailableException) {
            if (retained == null) {
                throw (InferenceException) failure;
            }
            if (retained.hasRetainedModelRunRequest()) {
                return fail(
                        imageId,
                        retained,
                        plan,
                        observedWork,
                        terminalDetail,
                        failure,
                        reservation);
            }
            if (!hasUnresolvedHandle(retained, failure)) {
                throw (InferenceException) failure;
            }
            return suspend(retained, plan, observedWork, failure, reservation);
        }
        if (failure instanceof InferenceException inference && isSourceArtifactFailure(inference)) {
            if (retained == null) {
                throw inference;
            }
            boolean sourceAlreadyReferenced =
                    retained.hasRetainedModelRunRequest()
                            || observedWork.stream()
                                    .anyMatch(outcome -> outcome.execution().isPresent());
            if (sourceAlreadyReferenced) {
                return fail(
                        imageId,
                        retained,
                        plan,
                        observedWork,
                        terminalDetail,
                        failure,
                        reservation);
            }
            if (!hasUnresolvedHandle(retained, failure)) {
                throw inference;
            }
            return suspend(retained, plan, observedWork, failure, reservation);
        }

        boolean repairable =
                failure instanceof IOException
                        || (failure instanceof InferenceException inference
                                && isRepairableModelRunFailure(inference));
        if (!repairable) {
            return fail(
                    imageId, retained, plan, observedWork, terminalDetail, failure, reservation);
        }
        if (!hasUnresolvedHandle(retained, failure)) {
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw (InferenceException) failure;
        }
        return suspend(retained, plan, observedWork, failure, reservation);
    }

    Suspended suspendUnexpected(
            LargeActionHandle retained,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            RuntimeException failure,
            ImageActionRegistry.Reservation reservation)
            throws UnrecoverableRecoveryStateException {
        if (!hasUnresolvedHandle(retained, failure)) {
            throw failure;
        }
        return suspend(retained, plan, observedWork, failure, reservation);
    }

    RecoverableActionAttempt conflict(
            String imageId,
            LargeActionHandle retained,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            Exception conflict,
            ImageActionRegistry.Reservation reservation)
            throws UnrecoverableRecoveryStateException {
        RunResults.FailureDetail detail =
                ImageResultReducer.failure(RunResults.FailureType.REVISION_CONFLICT, conflict);
        try {
            handles.conflict(retained.actionId(), retained.handleGenerationId(), detail);
        } catch (UnrecoverableRecoveryStateException terminalizationFailure) {
            terminalizationFailure.addSuppressed(conflict);
            throw terminalizationFailure;
        } catch (IOException terminalizationFailure) {
            terminalizationFailure.addSuppressed(conflict);
            return suspend(retained, plan, observedWork, terminalizationFailure, reservation);
        }
        List<RunResults.ScopeWorkResult> failedWork =
                plan == null
                        ? observedWork
                        : RecipeOutcomes.failedTransaction(
                                plan, plan.targetRegions(), observedWork, detail);
        return new Completed(
                ImageResultReducer.work(
                        imageId,
                        RunResults.ImageOutcomeType.CONFLICT,
                        failedWork,
                        plan == null ? List.of(detail) : List.of(),
                        false));
    }

    private RecoverableActionAttempt fail(
            String imageId,
            LargeActionHandle retained,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            RunResults.FailureDetail detail,
            Exception failure,
            ImageActionRegistry.Reservation reservation)
            throws UnrecoverableRecoveryStateException {
        try {
            handles.fail(retained.actionId(), retained.handleGenerationId(), detail);
        } catch (UnrecoverableRecoveryStateException terminalizationFailure) {
            terminalizationFailure.addSuppressed(failure);
            throw terminalizationFailure;
        } catch (IOException terminalizationFailure) {
            terminalizationFailure.addSuppressed(failure);
            return suspend(retained, plan, observedWork, terminalizationFailure, reservation);
        }
        List<RunResults.ScopeWorkResult> failedWork =
                plan == null
                        ? observedWork
                        : RecipeOutcomes.failedTransaction(
                                plan, plan.targetRegions(), observedWork, detail);
        return new Completed(
                ImageResultReducer.work(
                        imageId,
                        RunResults.ImageOutcomeType.FAILED,
                        failedWork,
                        plan == null ? List.of(detail) : List.of(),
                        false));
    }

    private RunResults.ImageRunResult terminalResult(
            String imageId,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            LargeActionCommitResult completion) {
        LargeActionPhase phase = completion.handle().phase();
        if (phase == LargeActionPhase.CONFLICT) {
            RunResults.FailureDetail failure = completion.handle().failure().orElseThrow();
            List<RunResults.ScopeWorkResult> failedWork =
                    plan == null
                            ? observedWork
                            : RecipeOutcomes.failedTransaction(
                                    plan, plan.targetRegions(), observedWork, failure);
            return ImageResultReducer.work(
                    imageId,
                    RunResults.ImageOutcomeType.CONFLICT,
                    failedWork,
                    plan == null ? List.of(failure) : List.of(),
                    false);
        }
        if (phase != LargeActionPhase.COMMITTED && phase != LargeActionPhase.SUPERSEDED) {
            throw new IllegalStateException(
                    "large-action completion returned a non-completion terminal phase");
        }

        boolean hierarchySaved =
                !completion
                        .handle()
                        .baseline()
                        .hierarchy()
                        .equals(completion.handle().expectedCommittedHierarchy().orElseThrow());
        Optional<RunResults.FailureDetail> viewerFailure =
                completion
                        .imageCommit()
                        .flatMap(ImageLifecycle.CommitResult::viewerRefreshFailure)
                        .map(
                                failure ->
                                        ImageResultReducer.failure(
                                                RunResults.FailureType.VIEWER_REFRESH, failure));
        return ImageResultReducer.completed(
                imageId,
                observedWork,
                completion.warnings(),
                hierarchySaved,
                viewerFailure,
                Optional.empty(),
                List.of());
    }

    private void abandon(LargeActionHandle retained) throws IOException {
        if (retained != null) {
            handles.abandon(retained.actionId(), retained.handleGenerationId());
        }
    }

    private boolean hasUnresolvedHandle(LargeActionHandle retained, Exception failure)
            throws UnrecoverableRecoveryStateException {
        if (retained == null) {
            return false;
        }
        try {
            return !handles.load(retained.actionId(), retained.handleGenerationId())
                    .phase()
                    .terminal();
        } catch (UnrecoverableRecoveryStateException stateFailure) {
            stateFailure.addSuppressed(failure);
            throw stateFailure;
        } catch (IOException | RuntimeException stateFailure) {
            failure.addSuppressed(stateFailure);
            return true;
        }
    }

    private Suspended suspend(
            LargeActionHandle retained,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            Exception failure,
            ImageActionRegistry.Reservation reservation) {
        return new Suspended(retained, plan, observedWork, failure, handles, reservation);
    }

    private static boolean isRepairableModelRunFailure(InferenceException failure) {
        if (failure.statusCode() == 0) {
            return false;
        }
        return failure.gatewayError()
                .map(GatewayErrorCodec.Error::code)
                .map(
                        code ->
                                switch (code) {
                                    case RATE_LIMITED,
                                            REGISTRY_RATE_LIMITED,
                                            SERVICE_UNAVAILABLE,
                                            AUTHENTICATION_REQUIRED,
                                            MODULE_BUSY,
                                            MODEL_RUN_NOT_FOUND,
                                            MODEL_RUN_EXPIRED ->
                                            true;
                                    default -> false;
                                })
                .orElse(false);
    }

    private static boolean isMissingImmutableResult(InferenceException failure) {
        return failure.gatewayError()
                .map(GatewayErrorCodec.Error::code)
                .map(
                        code ->
                                code == GatewayErrorCodec.Code.MODEL_RUN_NOT_FOUND
                                        || code == GatewayErrorCodec.Code.MODEL_RUN_EXPIRED)
                .orElse(false);
    }

    private static boolean isSourceArtifactFailure(InferenceException failure) {
        return failure.gatewayError()
                .map(GatewayErrorCodec.Error::code)
                .map(
                        code ->
                                code == GatewayErrorCodec.Code.ARTIFACT_NOT_FOUND
                                        || code == GatewayErrorCodec.Code.ARTIFACT_EXPIRED
                                        || code
                                                == GatewayErrorCodec.Code
                                                        .ARTIFACT_PRECONDITION_FAILED)
                .orElse(false);
    }

    private static RunResults.ImageRunResult cancelledResult(
            String imageId,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            Throwable cancellation) {
        RunResults.FailureDetail detail =
                ImageResultReducer.failure(RunResults.FailureType.CANCELLED, cancellation);
        List<RunResults.ScopeWorkResult> work =
                plan != null
                        ? RecipeOutcomes.cancelledTransaction(
                                plan, plan.targetRegions(), observedWork, detail)
                        : observedWork;
        return ImageResultReducer.work(
                imageId,
                RunResults.ImageOutcomeType.CANCELLED,
                work,
                plan == null ? List.of(detail) : List.of(),
                false);
    }
}
