package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelRunHandle;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.images.ImageData;

/** Runs or explicitly resumes one persisted recipe without mutating a live QuPath hierarchy. */
final class DurableRecipeDispatcher {

    private DurableRecipeDispatcher() {}

    /** Open real cloud services only when a recipe needs remote work or a missing download. */
    @FunctionalInterface
    interface RunAccess {
        DurableModelRunExecutor get() throws IOException, InterruptedException, InferenceException;
    }

    /** Attempt-scoped pixel delivery; called only for accepted requests with captured sources. */
    @FunctionalInterface
    interface ImageDelivery {
        void deliver(
                DurableStepJournal journal,
                int shardIndex,
                DurableModelRunExecutor runs,
                ModelRunHandle run,
                BooleanSupplier stop)
                throws IOException, InterruptedException, InferenceException;
    }

    /** Commit-ready action and its completed recipe; no original image or reader is retained. */
    record CompletedDispatch(
            LargeActionHandleStore.ResumableAction action, PreparedRecipeResult preparedResult) {
        CompletedDispatch {
            action = Objects.requireNonNull(action, "action");
            preparedResult = Objects.requireNonNull(preparedResult, "preparedResult");
            preparedResult.requireAction(action);
        }

        List<RunResults.ScopeWorkResult> outcomes() {
            return preparedResult.outcomes();
        }
    }

    static CompletedDispatch dispatch(
            LargeActionHandleStore.ResumableAction action,
            ImageExecutionPlan plan,
            RecipeExecutor.ExecutionInputs inputs,
            ImageData<?> isolatedImage,
            LargeActionHandleStore handles,
            DurableModelRunExecutor runs,
            ImageDelivery imageDelivery,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        LargeActionHandleStore.ResumableAction retained = Objects.requireNonNull(action, "action");
        DurableModelRunExecutor executor = Objects.requireNonNull(runs, "runs");
        if (!retained.handle().module().deploymentId().equals(executor.deploymentId())) {
            throw new GatewayTransport.DeploymentChangedException(
                    "large action belongs to a different gateway deployment");
        }
        return dispatch(
                retained,
                plan,
                inputs,
                isolatedImage,
                handles,
                (request, model, stop, completeResults, journal, shardIndex) -> {
                    var run =
                            executor.submit(
                                    request,
                                    model.capabilities(),
                                    model.largeRegionProfile(),
                                    model.output().emittedClasses());
                    deliverImages(imageDelivery, journal, shardIndex, executor, run, stop);
                    return executor.awaitTerminal(run, stop, completeResults);
                },
                executor.maxRequestCanonicalBytes(),
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                () -> UUID.randomUUID().toString(),
                shouldStop);
    }

    /** Explicitly resume one persisted durable recipe without automatic replacement loops. */
    static CompletedDispatch resume(
            LargeActionHandleStore.ResumableAction action,
            ImageExecutionPlan plan,
            RecipeExecutor.ExecutionInputs inputs,
            ImageData<?> isolatedImage,
            LargeActionHandleStore handles,
            DurableModelRunExecutor runs,
            ImageDelivery imageDelivery,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        return resume(
                action,
                plan,
                inputs,
                isolatedImage,
                handles,
                () -> runs,
                imageDelivery,
                shouldStop);
    }

    static CompletedDispatch resume(
            LargeActionHandleStore.ResumableAction action,
            ImageExecutionPlan plan,
            RecipeExecutor.ExecutionInputs inputs,
            ImageData<?> isolatedImage,
            LargeActionHandleStore handles,
            RunAccess runs,
            ImageDelivery imageDelivery,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        var retained = Objects.requireNonNull(action, "action");
        return run(
                retained,
                plan,
                inputs,
                isolatedImage,
                handles,
                (request, model, stop, completeResults, journal, shardIndex) -> {
                    var executor = requireDeployment(retained, runs.get());
                    var run =
                            executor.submit(
                                    request,
                                    model.capabilities(),
                                    model.largeRegionProfile(),
                                    model.output().emittedClasses());
                    deliverImages(imageDelivery, journal, shardIndex, executor, run, stop);
                    return executor.awaitTerminal(run, stop, completeResults);
                },
                (request, model, stop, completeResults, journal, shardIndex) -> {
                    var executor = requireDeployment(retained, runs.get());
                    var run =
                            executor.adopt(
                                    request,
                                    model.capabilities(),
                                    model.largeRegionProfile(),
                                    model.output().emittedClasses());
                    deliverImages(imageDelivery, journal, shardIndex, executor, run, stop);
                    return executor.awaitTerminal(run, stop, completeResults);
                },
                () -> requireDeployment(retained, runs.get()).maxRequestCanonicalBytes(),
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                () -> UUID.randomUUID().toString(),
                shouldStop);
    }

    private static void deliverImages(
            ImageDelivery delivery,
            DurableStepJournal journal,
            int shardIndex,
            DurableModelRunExecutor runs,
            ModelRunHandle run,
            BooleanSupplier stop)
            throws IOException, InterruptedException, InferenceException {
        if (journal.run(run.request().producer().stepId(), shardIndex).imageSources().isPresent())
            delivery.deliver(journal, shardIndex, runs, run, stop);
    }

    private static DurableModelRunExecutor requireDeployment(
            LargeActionHandleStore.ResumableAction action, DurableModelRunExecutor executor)
            throws GatewayTransport.DeploymentChangedException {
        if (!action.handle().module().deploymentId().equals(executor.deploymentId()))
            throw new GatewayTransport.DeploymentChangedException(
                    "large action belongs to a different gateway deployment");
        return executor;
    }

    static CompletedDispatch dispatch(
            LargeActionHandleStore.ResumableAction action,
            ImageExecutionPlan plan,
            RecipeExecutor.ExecutionInputs inputs,
            ImageData<?> isolatedImage,
            LargeActionHandleStore handles,
            DurableRunResolver.RunSubmission runs,
            long gatewayRequestBytes,
            Clock clock,
            Supplier<String> generationIds,
            Supplier<String> modelRunIds,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        return run(
                action,
                plan,
                inputs,
                isolatedImage,
                handles,
                runs,
                null,
                () -> gatewayRequestBytes,
                clock,
                generationIds,
                modelRunIds,
                shouldStop);
    }

    static CompletedDispatch resume(
            LargeActionHandleStore.ResumableAction action,
            ImageExecutionPlan plan,
            RecipeExecutor.ExecutionInputs inputs,
            ImageData<?> isolatedImage,
            LargeActionHandleStore handles,
            DurableRunResolver.RunSubmission runs,
            DurableRunResolver.RetainedRunObservation retainedRuns,
            long gatewayRequestBytes,
            Clock clock,
            Supplier<String> generationIds,
            Supplier<String> modelRunIds,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        return run(
                action,
                plan,
                inputs,
                isolatedImage,
                handles,
                runs,
                Objects.requireNonNull(retainedRuns, "retainedRuns"),
                () -> gatewayRequestBytes,
                clock,
                generationIds,
                modelRunIds,
                shouldStop);
    }

    private static CompletedDispatch run(
            LargeActionHandleStore.ResumableAction action,
            ImageExecutionPlan plan,
            RecipeExecutor.ExecutionInputs inputs,
            ImageData<?> isolatedImage,
            LargeActionHandleStore handles,
            DurableRunResolver.RunSubmission runs,
            DurableRunResolver.RetainedRunObservation retainedRuns,
            DurableDispatchModelInputAdapter.RequestByteLimit gatewayRequestBytes,
            Clock clock,
            Supplier<String> generationIds,
            Supplier<String> modelRunIds,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    TargetRecipeTransaction.TargetTransactionFailure {
        LargeActionHandleStore store = Objects.requireNonNull(handles, "handles");
        LargeActionHandleStore.ResumableAction retained = Objects.requireNonNull(action, "action");
        LargeActionHandleStore.ResumableAction current =
                store.requireResumable(
                        retained.handle().actionId(), retained.handle().handleGenerationId());
        if (!current.equals(retained)) {
            throw new IllegalStateException(
                    "durable dispatch requires the latest persisted action state");
        }
        ImageExecutionPlan executionPlan = Objects.requireNonNull(plan, "plan");
        RecipeExecutor.ExecutionInputs executionInputs = Objects.requireNonNull(inputs, "inputs");
        if (executionPlan.targetRegions().size() != 1
                || executionInputs.sourcePlan().targets().size() != 1) {
            throw new IllegalArgumentException(
                    "durable recipe dispatch owns exactly one recoverable target");
        }
        SourceInputPlan.TargetPlanningResult targetSource =
                requireDurableSource(current, executionPlan, executionInputs);
        DurableDispatchModelInputAdapter adapter =
                retainedRuns == null
                        ? new DurableDispatchModelInputAdapter(
                                executionPlan,
                                current,
                                store,
                                runs,
                                gatewayRequestBytes,
                                clock,
                                generationIds,
                                modelRunIds)
                        : new DurableDispatchModelInputAdapter(
                                executionPlan,
                                current,
                                store,
                                runs,
                                retainedRuns,
                                gatewayRequestBytes,
                                clock,
                                generationIds,
                                modelRunIds);
        TargetRecipeTransaction transaction =
                new TargetRecipeTransaction(
                        executionPlan,
                        executionInputs,
                        Objects.requireNonNull(isolatedImage, "isolatedImage"),
                        adapter);
        RecipeStaging.Target staged =
                transaction.stage(
                        current.handle().target(),
                        targetSource,
                        OutputIndex.fromHierarchy(isolatedImage.getHierarchy()),
                        current.handle().baseline().hierarchy().replacementClosure(),
                        new RecipeStaging.Projection(),
                        Objects.requireNonNull(shouldStop, "shouldStop"));
        LargeActionHandleStore.ResumableAction commitReady =
                store.requireCommitReady(
                        current.handle().actionId(), current.handle().handleGenerationId());
        if (!commitReady.equals(adapter.currentAction())) {
            throw new IllegalStateException(
                    "durable recipe completed against a stale persisted action state");
        }
        return new CompletedDispatch(
                commitReady,
                new PreparedRecipeResult(
                        commitReady, executionPlan, executionInputs.sourcePlan(), staged, clock));
    }

    private static SourceInputPlan.TargetPlanningResult requireDurableSource(
            LargeActionHandleStore.ResumableAction action,
            ImageExecutionPlan plan,
            RecipeExecutor.ExecutionInputs inputs) {
        var handle = action.handle();
        SourceInputPlan.TargetPlanningResult target = inputs.sourcePlan().targets().getFirst();
        if (!plan.targetRegions().equals(List.of(handle.target()))
                || !target.target().equals(handle.target())
                || target.sourcePlan().isEmpty()) {
            throw new IllegalArgumentException(
                    "durable source plan belongs to another action target");
        }
        SourceInputPlan.TargetSourcePlan source = target.sourcePlan().orElseThrow();
        if (source.protocol() != SourceInputPlan.ExecutionProtocol.RECOVERABLE
                || !source.sourceRevision().equals(inputs.sourceRevision())
                || !inputs.sourceRevision().imageDataRevision().imageId().equals(handle.imageId())
                || !inputs.sourceRevision().pixelSource().equals(handle.baseline().pixelSource())) {
            throw new IllegalArgumentException(
                    "durable source plan does not match the retained image revision");
        }
        if (!source.artifact().equals(handle.sourcePlan())
                || inputs.sourceProvisions().size() != 1
                || !RecoverableSourceProvisioner.retainedSource(handle)
                        .equals(inputs.sourceProvisions().get(handle.target().key()))) {
            throw new IllegalArgumentException(
                    "durable source provision does not match the captured source plan");
        }
        return target;
    }
}
