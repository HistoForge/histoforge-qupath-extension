package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.ResumableAction;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.ResumeOwnership;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.Suspended;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

/** Composes one recoverable target without holding a QuPath image lease during remote work. */
final class RecoverableImageActionExecutor {

    private static final Logger logger =
            LoggerFactory.getLogger(RecoverableImageActionExecutor.class);

    private final ImageActionRegistry actionRegistry;
    private final ImageLifecycle lifecycle;
    private final SourcePlanningLimits sourcePlanningLimits;
    private final LargeActionHandleStore handles;
    private final RecoverableSourceProvisioner sourceProvisioner;
    private final LargeActionCommitCoordinator commitCoordinator;
    private final DurableDispatch durableDispatch;
    private final CommitPreparation commitPreparation;
    private final RecoverableActionAttemptResolver attemptResolver;
    private final RecoverableActionRecovery recovery;

    RecoverableImageActionExecutor(
            ImageActionRegistry actionRegistry,
            ImageLifecycle lifecycle,
            SourcePlanningLimits sourcePlanningLimits,
            LargeActionHandleStore handles,
            RecoverableSourceProvisioner sourceProvisioner,
            DurableModelRunExecutor runs) {
        this(
                actionRegistry,
                lifecycle,
                sourcePlanningLimits,
                handles,
                sourceProvisioner,
                new LargeActionCommitCoordinator(handles, lifecycle),
                (action, plan, inputs, image, delivery, stop) ->
                        DurableRecipeDispatcher.dispatch(
                                action, plan, inputs, image, handles, runs, delivery, stop),
                (action, plan, inputs, image, delivery, stop) ->
                        DurableRecipeDispatcher.resume(
                                action, plan, inputs, image, handles, runs, delivery, stop),
                (action, stop) ->
                        LargeActionRecipeReconstructor.prepare(action, handles, runs, stop));
    }

    RecoverableImageActionExecutor(
            ImageActionRegistry actionRegistry,
            ImageLifecycle lifecycle,
            SourcePlanningLimits sourcePlanningLimits,
            LargeActionHandleStore handles,
            RecoverableSourceProvisioner sourceProvisioner,
            LargeActionCommitCoordinator commitCoordinator,
            DurableDispatch durableDispatch,
            CommitPreparation commitPreparation) {
        this(
                actionRegistry,
                lifecycle,
                sourcePlanningLimits,
                handles,
                sourceProvisioner,
                commitCoordinator,
                durableDispatch,
                durableDispatch,
                commitPreparation);
    }

    RecoverableImageActionExecutor(
            ImageActionRegistry actionRegistry,
            ImageLifecycle lifecycle,
            SourcePlanningLimits sourcePlanningLimits,
            LargeActionHandleStore handles,
            RecoverableSourceProvisioner sourceProvisioner,
            LargeActionCommitCoordinator commitCoordinator,
            DurableDispatch durableDispatch,
            DurableDispatch durableResume,
            CommitPreparation commitPreparation) {
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.sourcePlanningLimits =
                Objects.requireNonNull(sourcePlanningLimits, "sourcePlanningLimits");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.sourceProvisioner = Objects.requireNonNull(sourceProvisioner, "sourceProvisioner");
        this.attemptResolver = new RecoverableActionAttemptResolver(this.handles);
        this.commitCoordinator = Objects.requireNonNull(commitCoordinator, "commitCoordinator");
        this.durableDispatch = Objects.requireNonNull(durableDispatch, "durableDispatch");
        this.commitPreparation = Objects.requireNonNull(commitPreparation, "commitPreparation");
        this.recovery =
                new RecoverableActionRecovery(
                        this.actionRegistry,
                        this.lifecycle,
                        this.handles,
                        () -> this.sourceProvisioner,
                        this.commitCoordinator,
                        Objects.requireNonNull(durableResume, "durableResume"),
                        this.commitPreparation,
                        this.attemptResolver);
    }

    /** Resume one suspended attempt by transferring its still-held mutation reservation. */
    RecoverableActionAttempt resume(
            Suspended suspended, ProjectImageEntry<BufferedImage> entry, BooleanSupplier shouldStop)
            throws IOException, InferenceException {
        return recovery.resume(suspended, entry, shouldStop);
    }

    /** Resume a target while its process-local image coordinator retains action-wide ownership. */
    CoordinatedAttempt resume(
            ResumeOwnership ownership,
            ProjectImageEntry<BufferedImage> entry,
            BooleanSupplier shouldStop)
            throws IOException, InferenceException {
        ResumeOwnership value = Objects.requireNonNull(ownership, "ownership");
        ProjectImageEntry<BufferedImage> imageEntry = Objects.requireNonNull(entry, "entry");
        requireEntry(imageEntry, value.lastObservedHandle().imageId());
        return new CoordinatedAttempt(
                recovery.resume(
                        value, imageEntry, true, Objects.requireNonNull(shouldStop, "shouldStop")));
    }

    /** Resume one persisted same-build handle after process restart. */
    RecoverableActionAttempt resume(
            String actionId,
            String handleGenerationId,
            ProjectImageEntry<BufferedImage> entry,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        return recovery.resume(actionId, handleGenerationId, entry, shouldStop);
    }

    RecoverableActionRecovery recovery() {
        return recovery;
    }

    /**
     * Run one recoverable target to a terminal result or explicit suspended recovery ownership.
     *
     * <p>A suspended attempt retains the image's mutation reservation with its nonterminal handle.
     * Explicit cancellation durably abandons an already-created handle before releasing that
     * reservation.
     */
    RecoverableActionAttempt execute(
            ImageActionExecutor.Request request, BooleanSupplier shouldStop)
            throws IOException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        ImageActionExecutor.Request value = Objects.requireNonNull(request, "request");
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        String imageId = CanonicalCodec.requireNonblank(value.entry().getID(), "image_id");
        ImageActionRegistry.Reservation reservation = null;
        boolean reservationTransferred = false;
        try {
            reservation = actionRegistry.reserve(imageId);
            CoordinatedAttempt result = execute(value, reservation, Optional.empty(), stop);
            reservationTransferred = result.attempt() instanceof Suspended;
            return result.attempt();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new RecoverableActionAttempt.Completed(
                    ImageResultReducer.imageFailure(
                            imageId,
                            RunResults.ImageOutcomeType.CANCELLED,
                            List.of(),
                            ImageResultReducer.failure(
                                    RunResults.FailureType.CANCELLED, interrupted)));
        } finally {
            if (reservation != null && !reservationTransferred) {
                reservation.close();
            }
        }
    }

    /**
     * Execute one target while a process-local image coordinator retains mutation ownership.
     *
     * <p>A suspended result temporarily owns the supplied reservation; the coordinator must claim
     * it exactly once before continuing any other target.
     */
    CoordinatedAttempt execute(
            ImageActionExecutor.Request request,
            ImageActionRegistry.Reservation reservation,
            Optional<SealedOriginalSource> sharedOriginal,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        return execute(request, reservation, Optional.empty(), sharedOriginal, shouldStop);
    }

    /** Execute the exact action-wide captured target without replanning its source work. */
    CoordinatedAttempt execute(
            ImageActionExecutor.Request request,
            ImageActionRegistry.Reservation reservation,
            ImageActionSubsetPlanner.Subset captured,
            Optional<SealedOriginalSource> sharedOriginal,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        return execute(
                request,
                reservation,
                Optional.of(Objects.requireNonNull(captured, "captured")),
                sharedOriginal,
                shouldStop);
    }

    private CoordinatedAttempt execute(
            ImageActionExecutor.Request request,
            ImageActionRegistry.Reservation reservation,
            Optional<ImageActionSubsetPlanner.Subset> captured,
            Optional<SealedOriginalSource> sharedOriginal,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        ImageActionExecutor.Request value = Objects.requireNonNull(request, "request");
        ImageActionRegistry.Reservation owner = Objects.requireNonNull(reservation, "reservation");
        Optional<ImageActionSubsetPlanner.Subset> subset =
                Objects.requireNonNull(captured, "captured");
        Optional<SealedOriginalSource> original =
                Objects.requireNonNull(sharedOriginal, "sharedOriginal");
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        String imageId = CanonicalCodec.requireNonblank(value.entry().getID(), "image_id");
        if (!owner.imageId().equals(imageId)) {
            throw new IllegalArgumentException(
                    "image reservation belongs to another recoverable action");
        }
        ResumableAction retained = null;
        PreparedImageBudget.Reservation preparation = null;
        ImageExecutionPlan targetPlan = null;
        List<RunResults.ScopeWorkResult> observedWork = List.of();
        try {
            PreparedAction prepared;
            try (ImageActionRegistry.Lease ignored = owner.acquireLease()) {
                requireRunning(stop);
                ImageLifecycle.WorkingImage working;
                ImageExecutionPlan imagePlan = null;
                if (subset.isEmpty() && value.annotationClass().isPresent()) {
                    var selection = AnnotationRoiPreparation.open(lifecycle, value, stop);
                    working = selection.working();
                    imagePlan = selection.plan();
                } else {
                    working =
                            lifecycle.openWorkingImage(
                                    value.entry(), imageId, value.clientBuild().limits());
                }
                try {
                    requireRunning(stop);
                    ImageData<BufferedImage> image = working.imageData();
                    if (subset.isPresent()) {
                        ImageActionSubsetPlanner.Subset expected = subset.orElseThrow();
                        requireCapturedRequest(value, expected.execution());
                        imagePlan = expected.execution();
                        targetPlan = imagePlan;
                        ImageActionSubsetPlanner.requireCurrent(
                                expected,
                                image.getHierarchy(),
                                image.getServer().getWidth(),
                                image.getServer().getHeight());
                    } else if (imagePlan == null) {
                        imagePlan =
                                ImageActionPlanner.plan(
                                        value.actionId(),
                                        value.clientBuild(),
                                        imageId,
                                        value.module(),
                                        value.action(),
                                        image.getHierarchy(),
                                        image.getServer().getWidth(),
                                        image.getServer().getHeight());
                    }
                    if (value.action() instanceof ActionSelectionCodec.Metrics) {
                        throw new IllegalArgumentException(
                                "Metrics does not create a recoverable target action");
                    }
                    SourceInputPlan sourcePlan =
                            subset.isPresent()
                                    ? ImageActionSubsetPlanner.sourcesForNewTarget(
                                            subset.orElseThrow(), working.sourceRevision())
                                    : original.isPresent()
                                            ? RecipeSourcePlanner.planAgainstPublishedSource(
                                                    imagePlan,
                                                    working.sourcePyramid(),
                                                    image.getHierarchy(),
                                                    original.orElseThrow().startRequest())
                                            : RecipeSourcePlanner.plan(
                                                    imagePlan,
                                                    working.sourcePyramid(),
                                                    image.getHierarchy(),
                                                    working.eligibleOriginalSource(),
                                                    sourcePlanningLimits);
                    SourceInputPlan.TargetPlanningResult target =
                            requireOneRecoverableTarget(sourcePlan);
                    preparation = sourceProvisioner.reserveNewImage(target, stop);
                    RecoverableActionPreparation.Prepared action =
                            RecoverableActionPreparation.prepare(
                                    imagePlan, target, working.sourceRevision(), image, handles);
                    retained = action.action();
                    targetPlan = action.plan();
                    if (retained.handle().sourcePlan()
                            instanceof SourceInputPlan.IncrementalSourcePlan) {
                        var source =
                                RecoverableSourceProvisioner.capture(
                                        retained, targetPlan, target, working, handles);
                        retained = source.action();
                        prepared = PreparedAction.attached(working, sourcePlan, source);
                    } else if (original.isPresent()) {
                        RecoverableSourceProvisioner.ProvisionedSource source =
                                sourceProvisioner.attachOriginal(
                                        retained,
                                        original.orElseThrow(),
                                        targetPlan,
                                        target,
                                        working);
                        retained = source.action();
                        prepared = PreparedAction.attached(working, sourcePlan, source);
                    } else {
                        RecoverableSourceProvisioner.PreparedUpload upload =
                                sourceProvisioner.prepare(
                                        retained, targetPlan, target, working, stop, preparation);
                        retained = upload.action();
                        prepared = PreparedAction.prepared(working, sourcePlan, upload);
                    }
                } catch (IOException | InterruptedException | RuntimeException | Error failure) {
                    closeAfterFailure(working, failure);
                    throw failure;
                }
            }

            DurableRecipeDispatcher.CompletedDispatch dispatched;
            try {
                RecoverableSourceProvisioner.ProvisionedSource source =
                        prepared.provisionedSource().isPresent()
                                ? prepared.provisionedSource().orElseThrow()
                                : sourceProvisioner.publish(
                                        prepared.preparedUpload().orElseThrow(), stop);
                retained = source.action();
                RecipeExecutor.ExecutionInputs inputs =
                        new RecipeExecutor.ExecutionInputs(
                                prepared.sourcePlan(),
                                Map.of(retained.handle().target().key(), source.source()),
                                prepared.working().sourceRevision());
                dispatched =
                        durableDispatch.dispatch(
                                retained,
                                targetPlan,
                                inputs,
                                prepared.working().imageData(),
                                sourceProvisioner.imageDelivery(prepared.working(), preparation),
                                stop);
                retained = dispatched.action();
                observedWork = dispatched.outcomes();
            } finally {
                closeWorking(prepared.working());
            }

            requireRunning(stop);
            LargeActionCommitResult completion =
                    commitCoordinator.complete(
                            retained.handle().actionId(),
                            retained.handle().handleGenerationId(),
                            owner,
                            value.entry(),
                            dispatched.preparedResult(),
                            stop);
            return coordinated(
                    attemptResolver.completed(imageId, targetPlan, observedWork, completion));
        } catch (ImageLifecycle.RevisionConflictException conflict) {
            return coordinated(
                    new RecoverableActionAttempt.Completed(
                            ImageResultReducer.imageFailure(
                                    imageId,
                                    RunResults.ImageOutcomeType.CONFLICT,
                                    observedWork,
                                    ImageResultReducer.failure(
                                            RunResults.FailureType.REVISION_CONFLICT, conflict))));
        } catch (TargetRecipeTransaction.TargetTransactionFailure failure) {
            if (retained == null || targetPlan == null) {
                throw failure;
            }
            RecoverableActionAttempt terminal =
                    attemptResolver.resolveFailure(
                            imageId,
                            handleOf(retained),
                            targetPlan,
                            failure.outcomes(),
                            failure.failure(),
                            failure.originalCause(),
                            owner);
            return coordinated(terminal);
        } catch (LargeActionTransactionValidator.Conflict conflict) {
            if (retained == null || targetPlan == null) {
                throw conflict;
            }
            RecoverableActionAttempt terminal =
                    attemptResolver.conflict(
                            imageId, handleOf(retained), targetPlan, observedWork, conflict, owner);
            return coordinated(terminal);
        } catch (ImageActionSubsetPlanner.CapturedPlanConflict conflict) {
            if (targetPlan == null) {
                throw conflict;
            }
            return coordinated(
                    new RecoverableActionAttempt.Completed(
                            ImageResultReducer.work(
                                    imageId,
                                    RunResults.ImageOutcomeType.CONFLICT,
                                    RecipeOutcomes.failedTransaction(
                                            targetPlan,
                                            targetPlan.targetRegions(),
                                            List.of(),
                                            ImageResultReducer.failure(
                                                    RunResults.FailureType.REVISION_CONFLICT,
                                                    conflict)),
                                    List.of(),
                                    false)));
        } catch (TargetRecipeTransaction.TargetTransactionCancelled cancelled) {
            RecoverableActionAttempt terminal =
                    attemptResolver.cancel(
                            imageId,
                            handleOf(retained),
                            targetPlan,
                            cancelled.outcomes(),
                            cancelled,
                            owner,
                            false);
            return coordinated(terminal);
        } catch (CancellationException cancelled) {
            RecoverableActionAttempt terminal =
                    attemptResolver.cancel(
                            imageId,
                            handleOf(retained),
                            targetPlan,
                            observedWork,
                            cancelled,
                            owner,
                            false);
            return coordinated(terminal);
        } catch (InterruptedException interrupted) {
            RecoverableActionAttempt terminal =
                    attemptResolver.cancel(
                            imageId,
                            handleOf(retained),
                            targetPlan,
                            observedWork,
                            interrupted,
                            owner,
                            true);
            return coordinated(terminal);
        } catch (InferenceException failure) {
            if (retained == null || targetPlan == null) {
                throw failure;
            }
            RecoverableActionAttempt terminal =
                    attemptResolver.resolveFailure(
                            imageId,
                            handleOf(retained),
                            targetPlan,
                            observedWork,
                            RecipeFailures.detail(failure),
                            failure,
                            owner);
            return coordinated(terminal);
        } catch (IOException failure) {
            if (retained == null || targetPlan == null) {
                throw failure;
            }
            RecoverableActionAttempt terminal =
                    attemptResolver.resolveFailure(
                            imageId,
                            handleOf(retained),
                            targetPlan,
                            observedWork,
                            RecipeFailures.detail(failure),
                            failure,
                            owner);
            return coordinated(terminal);
        } catch (LocalCapacityExceededException failure) {
            if (retained == null || targetPlan == null) {
                throw failure;
            }
            RecoverableActionAttempt terminal =
                    attemptResolver.resolveFailure(
                            imageId,
                            handleOf(retained),
                            targetPlan,
                            observedWork,
                            ImageResultReducer.failure(
                                    RunResults.FailureType.LOCAL_CAPACITY, failure),
                            failure,
                            owner);
            return coordinated(terminal);
        } catch (RuntimeException failure) {
            if (retained == null || targetPlan == null) {
                throw failure;
            }
            Suspended suspended =
                    attemptResolver.suspendUnexpected(
                            handleOf(retained), targetPlan, observedWork, failure, owner);
            return coordinated(suspended);
        } finally {
            if (preparation != null) preparation.close();
        }
    }

    private static SourceInputPlan.TargetPlanningResult requireOneRecoverableTarget(
            SourceInputPlan sourcePlan) {
        SourceInputPlan plan = Objects.requireNonNull(sourcePlan, "sourcePlan");
        if (plan.targets().size() != 1) {
            throw new IllegalArgumentException(
                    "this recoverable execution slice requires exactly one target");
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
            logger.warn("Failed to close a recoverable disposable working image", failure);
        }
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("recoverable image action cancelled");
        }
    }

    private static void requireEntry(ProjectImageEntry<?> entry, String imageId) {
        if (!imageId.equals(CanonicalCodec.requireNonblank(entry.getID(), "entry.image_id"))) {
            throw new IllegalArgumentException(
                    "project image entry belongs to another large action");
        }
    }

    private static void requireCapturedRequest(
            ImageActionExecutor.Request request, ImageExecutionPlan plan) {
        if (!request.actionId().equals(plan.actionId())
                || !request.clientBuild().equals(plan.clientBuild())
                || !request.entry().getID().equals(plan.imageId())
                || !request.module().equals(plan.module())
                || !request.action().equals(plan.action())) {
            throw new IllegalArgumentException(
                    "captured recoverable subset belongs to another image action");
        }
    }

    private static LargeActionHandle handleOf(ResumableAction action) {
        return action == null ? null : action.handle();
    }

    private static CoordinatedAttempt coordinated(RecoverableActionAttempt attempt) {
        return new CoordinatedAttempt(attempt);
    }

    record CoordinatedAttempt(RecoverableActionAttempt attempt) {
        CoordinatedAttempt {
            attempt = Objects.requireNonNull(attempt, "attempt");
        }
    }

    private record PreparedAction(
            ImageLifecycle.WorkingImage working,
            SourceInputPlan sourcePlan,
            Optional<RecoverableSourceProvisioner.PreparedUpload> preparedUpload,
            Optional<RecoverableSourceProvisioner.ProvisionedSource> provisionedSource) {
        private PreparedAction {
            working = Objects.requireNonNull(working, "working");
            sourcePlan = Objects.requireNonNull(sourcePlan, "sourcePlan");
            preparedUpload = Objects.requireNonNull(preparedUpload, "preparedUpload");
            provisionedSource = Objects.requireNonNull(provisionedSource, "provisionedSource");
            if (preparedUpload.isPresent() == provisionedSource.isPresent()) {
                throw new IllegalArgumentException(
                        "prepared action requires exactly one source publication state");
            }
        }

        private static PreparedAction prepared(
                ImageLifecycle.WorkingImage working,
                SourceInputPlan sourcePlan,
                RecoverableSourceProvisioner.PreparedUpload upload) {
            return new PreparedAction(working, sourcePlan, Optional.of(upload), Optional.empty());
        }

        private static PreparedAction attached(
                ImageLifecycle.WorkingImage working,
                SourceInputPlan sourcePlan,
                RecoverableSourceProvisioner.ProvisionedSource source) {
            return new PreparedAction(working, sourcePlan, Optional.empty(), Optional.of(source));
        }
    }

    @FunctionalInterface
    interface DurableDispatch {
        DurableRecipeDispatcher.CompletedDispatch dispatch(
                ResumableAction action,
                ImageExecutionPlan plan,
                RecipeExecutor.ExecutionInputs inputs,
                ImageData<?> image,
                DurableRecipeDispatcher.ImageDelivery imageDelivery,
                BooleanSupplier shouldStop)
                throws IOException,
                        InterruptedException,
                        InferenceException,
                        TargetRecipeTransaction.TargetTransactionFailure;
    }

    @FunctionalInterface
    interface CommitPreparation {
        LargeActionRecipeReconstructor.Prepared prepare(
                ResumableAction action, BooleanSupplier shouldStop)
                throws IOException, InterruptedException, InferenceException;
    }
}
