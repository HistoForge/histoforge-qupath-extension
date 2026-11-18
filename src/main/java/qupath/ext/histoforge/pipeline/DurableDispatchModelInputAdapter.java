package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.CandidatePipeline;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourceInputPlanner;
import qupath.lib.objects.PathObject;

/** Adapts one initial or explicitly resumed durable action to the single recipe walker. */
final class DurableDispatchModelInputAdapter implements RecipeModelInputAdapter {

    /**
     * One logical input whose request is durable but whose result remains behind its step barrier.
     */
    private record PendingInput(String stepId, DurableModelInputExecutor.PreparedInput prepared)
            implements PreparedInput {
        private PendingInput {
            stepId = qupath.ext.histoforge.modules.ModuleContract.requireModelId(stepId, "step_id");
            prepared = Objects.requireNonNull(prepared, "prepared");
        }

        @Override
        public SourceInputPlan.PlannedModelInput plannedInput() {
            return prepared.plannedInput();
        }

        @Override
        public qupath.ext.histoforge.contract.ImageDataStateCodec.GenerationStoreReservation
                reservation() {
            return prepared.reservation();
        }
    }

    private record CompletedInput(
            DurableModelInputExecutor.PreparedInput prepared,
            ModelRunPublicationCodec.InputResult manifestInput,
            CompleteModelResults.RetainedFeatures features,
            BackendExecutionCodec.DurableRun execution) {
        private CompletedInput {
            prepared = Objects.requireNonNull(prepared, "prepared");
            manifestInput = Objects.requireNonNull(manifestInput, "manifestInput");
            features = Objects.requireNonNull(features, "features");
            execution = Objects.requireNonNull(execution, "execution");
        }
    }

    private static final class StepState {
        private final DurableRequestSharder.StepPlan plan;
        private final Map<ExecutionScopeCodec.OutputKey, PendingInput> pending;
        private final boolean retained;
        private Map<ExecutionScopeCodec.OutputKey, CompletedInput> completed = Map.of();
        private boolean resultsValidated;

        private StepState(
                DurableRequestSharder.StepPlan plan,
                Map<ExecutionScopeCodec.OutputKey, PendingInput> pending,
                boolean retained) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.pending = new LinkedHashMap<>(Objects.requireNonNull(pending, "pending"));
            this.retained = retained;
            resultsValidated = pending.isEmpty();
        }

        private boolean complete() {
            return resultsValidated && pending.isEmpty() && completed.isEmpty();
        }
    }

    private final ImageExecutionPlan plan;
    private final DurableStepJournal journal;
    private final DurableRunResolver runResolver;

    @FunctionalInterface
    interface RequestByteLimit {
        long read() throws IOException, InterruptedException, InferenceException;
    }

    private final RequestByteLimit gatewayRequestBytes;
    private final Supplier<String> generationIds;
    private final Set<String> issuedGenerationIds = new LinkedHashSet<>();
    private final Map<String, StepState> steps = new LinkedHashMap<>();
    private final int retainedStepCount;
    private int stepPosition;

    DurableDispatchModelInputAdapter(
            ImageExecutionPlan plan,
            LargeActionHandleStore.ResumableAction action,
            LargeActionHandleStore handles,
            DurableRunResolver.RunSubmission runs,
            RequestByteLimit gatewayRequestBytes,
            Clock clock,
            Supplier<String> generationIds,
            Supplier<String> modelRunIds) {
        this(
                plan,
                action,
                handles,
                runs,
                null,
                gatewayRequestBytes,
                clock,
                generationIds,
                modelRunIds);
    }

    DurableDispatchModelInputAdapter(
            ImageExecutionPlan plan,
            LargeActionHandleStore.ResumableAction action,
            LargeActionHandleStore handles,
            DurableRunResolver.RunSubmission runs,
            DurableRunResolver.RetainedRunObservation retainedRuns,
            RequestByteLimit gatewayRequestBytes,
            Clock clock,
            Supplier<String> generationIds,
            Supplier<String> modelRunIds) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.journal = new DurableStepJournal(action, handles, clock);
        this.runResolver = new DurableRunResolver(action, journal, runs, retainedRuns, modelRunIds);
        this.gatewayRequestBytes =
                Objects.requireNonNull(gatewayRequestBytes, "gatewayRequestBytes");
        this.generationIds = Objects.requireNonNull(generationIds, "generationIds");
        retainedStepCount = action.handle().steps().size();
        issuedGenerationIds.addAll(action.authorizedInputs().keySet());
        requireAction();
    }

    @Override
    public SourceInputPlan.PlannedModelInput planFieldInput(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            ExecutionScopeCodec.OutputKey output) {
        return requiredSource(source, target).fieldInputs().stream()
                .filter(input -> input.output().equals(output))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "source plan lacks the selected durable field input"));
    }

    @Override
    public SourceInputPlan.PlannedModelInput planParentInput(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            qupath.ext.histoforge.contract.InferenceIdentityCodec.ModelStepIdentity producer,
            String parentId,
            Geometry parentGeometry) {
        return SourceInputPlanner.planParentInput(
                requiredSource(source, target), producer, parentId, parentGeometry);
    }

    @Override
    public List<PendingInput> prepareStep(
            ModuleRecipe.Step step,
            List<SourceInputPlan.PlannedModelInput> plannedInputs,
            List<PathObject> sourceParents,
            RecipeExecutor.AvailableSource source,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existingObjects)
            throws IOException, InterruptedException, InferenceException {
        requireNextStep(step);
        if (!RecoverableSourceProvisioner.retainedSource(journal.currentAction().handle())
                .equals(source)) {
            throw new IllegalArgumentException(
                    "durable recipe input differs from its captured source");
        }
        Optional<String> parentPlan = parentPlan(step, sourceParents);
        boolean retained = stepPosition < retainedStepCount;
        DurableRequestSharder.StepPlan stepPlan;
        if (retained) {
            LargeActionTypes.StepRunRef retainedStep = retainedStep(step.model(), parentPlan);
            try {
                stepPlan =
                        DurableRequestSharder.reproduce(
                                plan, step, plannedInputs, source, retainedShards(retainedStep));
            } catch (IllegalArgumentException mismatch) {
                throw new LargeActionTransactionValidator.Conflict(
                        "fresh durable input plan differs from the retained model step", mismatch);
            }
            if (!retainedStep.inputPlanDigest().equals(stepPlan.inputPlanDigest())) {
                throw new LargeActionTransactionValidator.Conflict(
                        "fresh durable input plan differs from the retained model step");
            }
        } else {
            stepPlan =
                    DurableRequestSharder.initial(
                            plan,
                            step,
                            plannedInputs,
                            source,
                            gatewayRequestBytes.read(),
                            this::nextGenerationId,
                            runResolver::nextModelRunId);
        }
        Map<Integer, DurableRequestSharder.PlannedShard> shardByInput = new LinkedHashMap<>();
        for (DurableRequestSharder.PlannedShard shard : stepPlan.shards()) {
            for (DurableRequestSharder.AssignedInput input : shard.inputs()) {
                shardByInput.put(input.requestInput().inputId(), shard);
            }
        }
        Map<ExecutionScopeCodec.OutputKey, PendingInput> pending = new LinkedHashMap<>();
        List<PendingInput> prepared = new ArrayList<>();
        for (DurableRequestSharder.AssignedInput assigned : stepPlan.inputs()) {
            DurableRequestSharder.PlannedShard shard =
                    Objects.requireNonNull(
                            shardByInput.get(assigned.requestInput().inputId()), "input shard");
            PendingInput input =
                    new PendingInput(
                            step.model(),
                            DurableModelInputExecutor.prepare(
                                    plan,
                                    shard.request(),
                                    assigned.requestInput(),
                                    assigned.plannedInput(),
                                    existingObjects));
            pending.put(assigned.requestInput().output(), input);
            prepared.add(input);
        }
        if (!retained) {
            journal.appendStep(step.model(), parentPlan, stepPlan);
        }
        StepState state = new StepState(stepPlan, pending, retained);
        steps.put(step.model(), state);
        stepPosition++;
        return List.copyOf(prepared);
    }

    @Override
    public void verifyEmptyStep(ModuleRecipe.Step step, List<PathObject> sourceParents)
            throws IOException {
        requireNextStep(step);
        DurableRequestSharder.StepPlan empty =
                new DurableRequestSharder.StepPlan(List.of(), List.of());
        Optional<String> parentPlan = parentPlan(step, sourceParents);
        boolean retained = stepPosition < retainedStepCount;
        if (retained) {
            LargeActionTypes.StepRunRef retainedStep = retainedStep(step.model(), parentPlan);
            if (!retainedStep.runs().isEmpty()
                    || !retainedStep.inputPlanDigest().equals(empty.inputPlanDigest())) {
                throw new LargeActionTransactionValidator.Conflict(
                        "fresh valid-empty step differs from the retained model step");
            }
        } else {
            journal.appendStep(step.model(), parentPlan, empty);
        }
        steps.put(step.model(), new StepState(empty, Map.of(), retained));
        stepPosition++;
    }

    @Override
    public CandidateGeneration execute(PreparedInput input, BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    RecipeFailures.Categorized {
        if (!(input instanceof PendingInput pending)) {
            throw new IllegalArgumentException("durable dispatch received another adapter input");
        }
        StepState state = steps.get(pending.stepId());
        if (state == null || state.pending.get(pending.plannedInput().output()) != pending) {
            throw new IllegalArgumentException("durable dispatch input is not owned by this step");
        }
        completeStep(pending.stepId(), state, shouldStop);
        CompletedInput completed = state.completed.get(pending.plannedInput().output());
        if (completed == null) {
            throw new IllegalStateException("completed durable step lacks its logical input");
        }
        CandidateGeneration generation =
                DurableModelInputExecutor.complete(
                        completed.prepared(),
                        completed.manifestInput(),
                        completed.features(),
                        completed.execution(),
                        shouldStop);
        // The generation now owns the accepted output; later steps need no preparation snapshots.
        state.pending.remove(pending.plannedInput().output());
        state.completed.remove(pending.plannedInput().output());
        return generation;
    }

    @Override
    public void requireComplete() {
        List<String> expected =
                plan.module().recipe().steps().stream()
                        .filter(ModuleRecipe.Step::isModelStep)
                        .map(ModuleRecipe.Step::model)
                        .filter(plan.module().activatedModels()::containsKey)
                        .toList();
        if (!new ArrayList<>(steps.keySet()).equals(expected)
                || stepPosition != expected.size()
                || journal.currentAction().handle().steps().size() != expected.size()
                || steps.values().stream().anyMatch(state -> !state.complete())) {
            throw new IllegalStateException(
                    "durable dispatch did not complete every activated recipe model step");
        }
    }

    LargeActionHandleStore.ResumableAction currentAction() {
        return journal.currentAction();
    }

    private void completeStep(String stepId, StepState state, BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    RecipeFailures.Categorized {
        if (state.resultsValidated) {
            return;
        }
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        Map<ExecutionScopeCodec.OutputKey, CompletedInput> completed = new LinkedHashMap<>();
        ModuleSnapshot.ModelSnapshot model = plan.module().activatedModels().get(stepId);
        for (DurableRequestSharder.PlannedShard shard : state.plan.shards()) {
            if (stop.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException(
                        "durable recipe dispatch cancelled before run submission");
            }
            DurableRecipeModelInputAdapter.RecoveredRun recovered =
                    state.retained
                            ? runResolver.resumeRetained(stepId, shard, model, stop)
                            : runResolver.submitInitial(stepId, shard, model, stop);
            var request = recovered.authorized().request();
            var execution = recovered.authorized().execution().orElseThrow();
            Map<Integer, ModelRunPublicationCodec.InputResult> manifestInputs =
                    new LinkedHashMap<>();
            recovered
                    .manifest()
                    .completedInputs()
                    .forEach(value -> manifestInputs.put(value.inputId(), value));
            for (ModelRunRequestCodec.ModelRunInput requestInput : request.inputs()) {
                PendingInput pending = state.pending.get(requestInput.output());
                if (pending == null) {
                    throw new IllegalStateException(
                            "completed durable run has no freshly planned logical input");
                }
                DurableModelInputExecutor.PreparedInput rebound =
                        DurableModelInputExecutor.prepare(
                                plan,
                                request,
                                requestInput,
                                pending.prepared().plannedInput(),
                                pending.prepared().existingObjects());
                completed.put(
                        requestInput.output(),
                        new CompletedInput(
                                rebound,
                                manifestInputs.get(requestInput.inputId()),
                                recovered.retainedInputs().get(requestInput.inputId()),
                                execution));
            }
        }
        state.completed = completed;
        state.resultsValidated = true;
    }

    private LargeActionTypes.StepRunRef retainedStep(String stepId, Optional<String> parentPlan) {
        LargeActionTypes.StepRunRef retained =
                journal.currentAction().handle().steps().get(stepPosition);
        if (!retained.stepId().equals(stepId) || !retained.parentPlanDigest().equals(parentPlan)) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh recipe step differs from the retained durable prefix");
        }
        return retained;
    }

    private List<RecipeStepPlanDigester.RequestShard> retainedShards(
            LargeActionTypes.StepRunRef step) {
        Map<Integer, LargeActionHandleStore.AuthorizedModelRun> authorized = new LinkedHashMap<>();
        for (LargeActionHandleStore.AuthorizedModelRun run :
                journal.currentAction().authorizedRuns()) {
            if (run.stepId().equals(step.stepId())) {
                authorized.put(run.shardIndex(), run);
            }
        }
        List<RecipeStepPlanDigester.RequestShard> result = new ArrayList<>();
        for (LargeActionTypes.ModelRunRef run : step.runs()) {
            LargeActionHandleStore.AuthorizedModelRun value = authorized.get(run.shardIndex());
            if (value == null || !value.request().modelRunId().equals(run.modelRunId())) {
                throw new IllegalStateException(
                        "retained durable step lacks its canonical request shard");
            }
            result.add(new RecipeStepPlanDigester.RequestShard(run.shardIndex(), value.request()));
        }
        return List.copyOf(result);
    }

    private Optional<String> parentPlan(ModuleRecipe.Step step, List<PathObject> sourceParents) {
        ModuleSnapshot.ModelSnapshot model = plan.module().activatedModels().get(step.model());
        if (model.input().type() != ModuleSnapshot.ModelInputType.CROP) {
            return Optional.empty();
        }
        return Optional.of(
                RecipeStepPlanDigester.parentPlanDigest(
                        model.input(),
                        RecipeStepPlanDigester.captureParents(
                                journal.currentAction().handle().target(), sourceParents)));
    }

    private void requireNextStep(ModuleRecipe.Step step) {
        Objects.requireNonNull(step, "step");
        List<String> expected =
                plan.module().recipe().steps().stream()
                        .filter(ModuleRecipe.Step::isModelStep)
                        .map(ModuleRecipe.Step::model)
                        .filter(plan.module().activatedModels()::containsKey)
                        .toList();
        if (stepPosition >= expected.size()
                || !expected.get(stepPosition).equals(step.model())
                || steps.containsKey(step.model())) {
            throw new IllegalStateException(
                    "durable model steps must persist once in activated recipe order");
        }
    }

    private void requireAction() {
        var handle = journal.currentAction().handle();
        if (handle.phase() != LargeActionTypes.LargeActionPhase.EXECUTING
                || (!runResolver.recoveryEnabled() && !handle.steps().isEmpty())
                || !plan.actionId().equals(handle.actionId())
                || !plan.clientBuild().equals(handle.clientBuild())
                || !plan.imageId().equals(handle.imageId())
                || !plan.module().equals(handle.module())
                || !plan.action().equals(handle.action())
                || !plan.targetRegions().equals(List.of(handle.target()))) {
            throw new IllegalArgumentException(
                    "durable dispatch requires its exact EXECUTING action");
        }
    }

    private SourceInputPlan.TargetSourcePlan requiredSource(
            SourceInputPlan.TargetPlanningResult source, ExecutionScopeCodec.TargetRegion target) {
        if (source == null
                || !source.target().equals(target)
                || source.sourcePlan().isEmpty()
                || source.sourcePlan().orElseThrow().protocol()
                        != SourceInputPlan.ExecutionProtocol.RECOVERABLE) {
            throw new IllegalArgumentException(
                    "large-action model input requires one recoverable source plan");
        }
        return source.sourcePlan().orElseThrow();
    }

    private String nextGenerationId() {
        String value =
                CanonicalCodec.requireCanonicalUuid(generationIds.get(), "output_generation_id");
        if (!issuedGenerationIds.add(value)) {
            throw new IllegalArgumentException(
                    "durable action generation IDs must be globally unique");
        }
        return value;
    }
}
