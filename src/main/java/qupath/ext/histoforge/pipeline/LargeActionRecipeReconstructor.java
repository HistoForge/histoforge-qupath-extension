package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelRunObservation;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.ResumableAction;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.state.OutputIndex;

/** Prepares completed durable results, then replays them through the single recipe transaction. */
public final class LargeActionRecipeReconstructor {

    private LargeActionRecipeReconstructor() {}

    /** Validate retained results before taking an image lease; repair only missing result bytes. */
    public static Prepared prepare(
            ResumableAction action,
            LargeActionHandleStore handles,
            DurableModelRunExecutor runs,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        return prepare(action, handles, () -> runs, shouldStop);
    }

    static Prepared prepare(
            ResumableAction action,
            LargeActionHandleStore handles,
            DurableRecipeDispatcher.RunAccess runs,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        ResumableAction resumable = Objects.requireNonNull(action, "action");
        var journal = new DurableStepJournal(resumable, handles, Clock.systemUTC());
        List<DurableRecipeModelInputAdapter.RecoveredRun> recovered = new ArrayList<>();
        for (var authorized : resumable.authorizedRuns()) {
            requireRunning(shouldStop);
            if (authorized.execution().isEmpty()) {
                throw new IllegalStateException(
                        "Commit requires a recorded manifest for every model run");
            }
            var local =
                    CompleteModelResults.readRetained(
                            journal.currentAction(), authorized, handles.resultFiles(), shouldStop);
            if (local.isPresent()) {
                CompleteModelResults.recordLocalCompletion(journal, local.orElseThrow());
                recovered.add(local.orElseThrow());
                continue;
            }
            var executor = runs.get();
            if (!resumable.handle().module().deploymentId().equals(executor.deploymentId())) {
                throw new GatewayTransport.DeploymentChangedException(
                        "large action belongs to a different gateway deployment");
            }
            var model = resumable.handle().module().activatedModels().get(authorized.stepId());
            var run =
                    executor.adopt(
                            authorized.request(),
                            model.capabilities(),
                            model.largeRegionProfile(),
                            model.output().emittedClasses());
            var observation =
                    executor.poll(
                            run,
                            CompleteModelResults.retaining(
                                    journal,
                                    authorized.stepId(),
                                    authorized.shardIndex(),
                                    authorized.request(),
                                    model,
                                    shouldStop));
            requireRunning(shouldStop);
            if (!(observation instanceof ModelRunObservation.Terminal terminal)
                    || !terminal.complete()
                    || !terminal.executionReference()
                            .equals(authorized.execution().orElseThrow())) {
                throw new IllegalStateException(
                        "Commit requires every retained model run COMPLETE");
            }
            recovered.add(
                    new DurableRecipeModelInputAdapter.RecoveredRun(
                            authorized,
                            terminal.manifest().orElseThrow(),
                            CompleteModelResults.retainedInputs(
                                    journal.currentAction().handle(),
                                            terminal.manifest().orElseThrow(),
                                    terminal.completedInputs(), handles.resultFiles())));
        }
        return new Prepared(journal.currentAction(), recovered, Clock.systemUTC(), shouldStop);
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("large-action result preparation cancelled");
        }
    }

    /** Network-free reconstruction that converts validated local files under the image lease. */
    public static final class Prepared
            implements LargeActionCommitCoordinator.TransactionPreparation {

        private final ResumableAction action;
        private final List<DurableRecipeModelInputAdapter.RecoveredRun> recoveredRuns;
        private final Clock clock;
        private final BooleanSupplier shouldStop;

        private Prepared(
                ResumableAction action,
                List<DurableRecipeModelInputAdapter.RecoveredRun> recoveredRuns,
                Clock clock,
                BooleanSupplier shouldStop) {
            this.action = Objects.requireNonNull(action, "action");
            this.recoveredRuns = List.copyOf(recoveredRuns);
            this.clock = Objects.requireNonNull(clock, "clock");
            this.shouldStop = Objects.requireNonNull(shouldStop, "shouldStop");
        }

        @Override
        public void requireAction(ResumableAction current) {
            if (!action.equals(Objects.requireNonNull(current, "current"))) {
                throw new IllegalStateException(
                        "prepared durable results belong to another retained action state");
            }
        }

        @Override
        public HierarchyDelta stageModelDelta(
                ImageLifecycle.WorkingImage working, ModelOutputCommitter committer) {
            return stageModelDelta(working, committer, working.sourcePyramid());
        }

        @Override
        public HierarchyDelta stageModelDelta(
                ImageLifecycle.WorkingImage working,
                ModelOutputCommitter committer,
                CapturedSourcePyramid planningSource) {
            Objects.requireNonNull(working, "working");
            Objects.requireNonNull(committer, "committer");
            var handle = action.handle();
            var captured = LargeActionRecipePlan.capture(action, working, planningSource);
            ImageExecutionPlan plan = captured.execution();
            var sourcePlan = captured.source();

            DurableRecipeModelInputAdapter adapter =
                    new DurableRecipeModelInputAdapter(plan, action, recoveredRuns);
            RecipeExecutor.ExecutionInputs inputs =
                    new RecipeExecutor.ExecutionInputs(
                            sourcePlan,
                            Map.of(
                                    handle.target().key(),
                                    RecoverableSourceProvisioner.retainedSource(handle)),
                            sourcePlan
                                    .targets()
                                    .getFirst()
                                    .sourcePlan()
                                    .orElseThrow()
                                    .sourceRevision());
            TargetRecipeTransaction transaction =
                    new TargetRecipeTransaction(plan, inputs, working.imageData(), adapter);
            RecipeStaging.Target staged;
            try {
                staged =
                        transaction.stage(
                                handle.target(),
                                sourcePlan.targets().getFirst(),
                                OutputIndex.fromHierarchy(working.imageData().getHierarchy()),
                                handle.baseline().hierarchy().replacementClosure(),
                                new RecipeStaging.Projection(),
                                shouldStop);
            } catch (TargetRecipeTransaction.TargetTransactionFailure failure) {
                throw new IllegalStateException(
                        "durable recipe reconstruction failed: " + failure.failure().message(),
                        failure.originalCause());
            }
            return RecipeExecutor.stageDelta(plan, committer, staged, clock.instant());
        }
    }
}
