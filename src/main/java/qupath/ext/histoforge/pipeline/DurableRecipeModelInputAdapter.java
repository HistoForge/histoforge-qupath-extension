package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.CandidatePipeline;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourceInputPlanner;
import qupath.lib.objects.PathObject;

/** Rebinds complete immutable durable results to a freshly reconstructed recipe plan. */
final class DurableRecipeModelInputAdapter implements RecipeModelInputAdapter {

    /** One complete run's validated local files, prepared before the image lease is acquired. */
    record RecoveredRun(
            LargeActionHandleStore.AuthorizedModelRun authorized,
            ModelRunPublicationCodec.Manifest manifest,
            Map<Integer, CompleteModelResults.RetainedFeatures> retainedInputs) {
        RecoveredRun {
            authorized = Objects.requireNonNull(authorized, "authorized");
            manifest = Objects.requireNonNull(manifest, "manifest");
            retainedInputs = Map.copyOf(Objects.requireNonNull(retainedInputs, "retainedInputs"));
            ModelRunRequestCodec.ModelRunRequest request = authorized.request();
            BackendExecutionCodec.DurableRun execution =
                    authorized
                            .execution()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "recovered run requires a retained terminal"
                                                            + " execution"));
            if (!manifest.complete()
                    || !manifest.modelRunId().equals(request.modelRunId())
                    || !manifest.actionId().equals(request.actionId())
                    || !manifest.backend().equals(request.expectedBackend())
                    || !manifest.requestDigest().equals(ModelRunRequestCodec.requestDigest(request))
                    || !manifest.manifestDigest().equals(execution.resultManifestDigest())) {
                throw new IllegalArgumentException(
                        "recovered manifest does not match its canonical request and execution");
            }
            Map<Integer, ModelRunPublicationCodec.InputResult> results = new LinkedHashMap<>();
            for (ModelRunPublicationCodec.InputResult result : manifest.completedInputs()) {
                if (results.putIfAbsent(result.inputId(), result) != null) {
                    throw new IllegalArgumentException(
                            "recovered manifest contains a duplicate logical input");
                }
            }
            if (!results.keySet().equals(retainedInputs.keySet())
                    || results.size() != request.inputs().size()) {
                throw new IllegalArgumentException(
                        "recovered run does not contain every canonical logical input");
            }
            for (ModelRunRequestCodec.ModelRunInput input : request.inputs()) {
                ModelRunPublicationCodec.InputResult result = results.get(input.inputId());
                var features = retainedInputs.get(input.inputId());
                if (result == null
                        || features == null
                        || !result.inputIdentity()
                                .equals(
                                        FiniteInferenceCodec.buildBackendInputIdentity(
                                                input.rasterInput(),
                                                result.inputIdentity().sourceBindings()))
                        || (!result.inputIdentity().sourceBindings().isEmpty()
                                && result.inputIdentity().sourceBindings().size()
                                        != qupath.ext.histoforge.contract.ModelExecutionPlanCodec
                                                .unitCount(result.executionPlan()))
                        || result.featureStreamArtifact().sizeBytes() != features.file().sizeBytes()
                        || !result.featureStreamArtifact().sha256().equals(features.file().sha256())
                        || result.featureCount() != features.validation().featureCount()
                        || result.vertexCount() != features.validation().vertexCount()) {
                    throw new IllegalArgumentException(
                            "recovered input does not match its request and manifest");
                }
                features.validation()
                        .requireInsideRaster(input.rasterInput().frame().transform().raster());
            }
        }
    }

    /** One process-local input after its fresh scope evidence has been rebound. */
    record PreparedDurableInput(
            DurableModelInputExecutor.PreparedInput prepared,
            ModelRunPublicationCodec.InputResult manifestInput,
            CompleteModelResults.RetainedFeatures features,
            BackendExecutionCodec.DurableRun execution)
            implements PreparedInput {
        PreparedDurableInput {
            prepared = Objects.requireNonNull(prepared, "prepared");
            manifestInput = Objects.requireNonNull(manifestInput, "manifestInput");
            features = Objects.requireNonNull(features, "features");
            execution = Objects.requireNonNull(execution, "execution");
        }

        @Override
        public SourceInputPlan.PlannedModelInput plannedInput() {
            return prepared.plannedInput();
        }

        ModelRunRequestCodec.ModelRunInput requestInput() {
            return prepared.requestInput();
        }

        @Override
        public qupath.ext.histoforge.contract.ImageDataStateCodec.GenerationStoreReservation
                reservation() {
            return prepared.reservation();
        }
    }

    private record CompletedInput(
            ModelRunRequestCodec.ModelRunRequest request,
            ModelRunRequestCodec.ModelRunInput input,
            ModelRunPublicationCodec.InputResult manifestInput,
            CompleteModelResults.RetainedFeatures features,
            BackendExecutionCodec.DurableRun execution) {}

    private record StepState(
            LargeActionTypes.StepRunRef retained,
            Map<ExecutionScopeCodec.OutputKey, CompletedInput> inputs,
            List<RecipeStepPlanDigester.RequestShard> shards) {}

    private final ImageExecutionPlan plan;
    private final Map<String, StepState> steps;
    private final int retainedInputCount;
    private final Set<String> consumedSteps = new LinkedHashSet<>();
    private final Set<String> consumedGenerationIds = new LinkedHashSet<>();

    DurableRecipeModelInputAdapter(
            ImageExecutionPlan plan,
            LargeActionHandleStore.ResumableAction action,
            List<RecoveredRun> recoveredRuns) {
        this.plan = Objects.requireNonNull(plan, "plan");
        LargeActionHandleStore.ResumableAction resumable = Objects.requireNonNull(action, "action");
        if (!plan.actionId().equals(resumable.handle().actionId())
                || !plan.clientBuild().equals(resumable.handle().clientBuild())
                || !plan.imageId().equals(resumable.handle().imageId())
                || !plan.module().equals(resumable.handle().module())
                || !plan.action().equals(resumable.handle().action())
                || !plan.targetRegions().equals(List.of(resumable.handle().target()))) {
            throw new LargeActionTransactionValidator.Conflict(
                    "reconstructed image plan does not match the retained large action");
        }

        Map<String, RecoveredRun> recoveredById = new HashMap<>();
        for (RecoveredRun recovered : Objects.requireNonNull(recoveredRuns, "recoveredRuns")) {
            String runId = recovered.authorized().request().modelRunId();
            if (recoveredById.putIfAbsent(runId, recovered) != null) {
                throw new IllegalArgumentException("recovery contains a duplicate model run");
            }
        }

        Map<String, StepState> stepValues = new LinkedHashMap<>();
        Map<ExecutionScopeCodec.OutputKey, CompletedInput> outputValues = new LinkedHashMap<>();
        int authorizedPosition = 0;
        for (LargeActionTypes.StepRunRef retained : resumable.handle().steps()) {
            Map<ExecutionScopeCodec.OutputKey, CompletedInput> completed = new LinkedHashMap<>();
            List<RecipeStepPlanDigester.RequestShard> shards = new ArrayList<>();
            for (LargeActionTypes.ModelRunRef runRef : retained.runs()) {
                if (authorizedPosition >= resumable.authorizedRuns().size()) {
                    throw new IllegalArgumentException(
                            "recovery lacks an authorized canonical request shard");
                }
                LargeActionHandleStore.AuthorizedModelRun authorized =
                        resumable.authorizedRuns().get(authorizedPosition++);
                if (!authorized.stepId().equals(retained.stepId())
                        || authorized.shardIndex() != runRef.shardIndex()
                        || !authorized.request().modelRunId().equals(runRef.modelRunId())) {
                    throw new IllegalArgumentException(
                            "authorized request order differs from the retained step plan");
                }
                RecoveredRun recovered = recoveredById.remove(runRef.modelRunId());
                if (recovered == null || !recovered.authorized().equals(authorized)) {
                    throw new IllegalArgumentException(
                            "recovery lacks the exact completed retained model run");
                }
                shards.add(
                        new RecipeStepPlanDigester.RequestShard(
                                runRef.shardIndex(), authorized.request()));
                Map<Integer, ModelRunPublicationCodec.InputResult> results = new HashMap<>();
                recovered
                        .manifest()
                        .completedInputs()
                        .forEach(result -> results.put(result.inputId(), result));
                for (ModelRunRequestCodec.ModelRunInput input : authorized.request().inputs()) {
                    CompletedInput value =
                            new CompletedInput(
                                    authorized.request(),
                                    input,
                                    results.get(input.inputId()),
                                    recovered.retainedInputs().get(input.inputId()),
                                    authorized.execution().orElseThrow());
                    if (outputValues.putIfAbsent(input.output(), value) != null) {
                        throw new IllegalArgumentException(
                                "retained model runs repeat one logical output");
                    }
                    if (completed.putIfAbsent(input.output(), value) != null) {
                        throw new IllegalArgumentException(
                                "retained model step repeats one logical output");
                    }
                }
            }
            final String canonicalInputPlanDigest;
            try {
                canonicalInputPlanDigest = RecipeStepPlanDigester.inputPlanDigest(shards);
            } catch (IllegalArgumentException mismatch) {
                throw new LargeActionTransactionValidator.Conflict(
                        "retained canonical requests do not form the captured input plan",
                        mismatch);
            }
            if (!retained.inputPlanDigest().equals(canonicalInputPlanDigest)) {
                throw new LargeActionTransactionValidator.Conflict(
                        "retained input-plan digest does not match its canonical requests");
            }
            stepValues.put(
                    retained.stepId(),
                    new StepState(retained, Map.copyOf(completed), List.copyOf(shards)));
        }
        if (authorizedPosition != resumable.authorizedRuns().size() || !recoveredById.isEmpty()) {
            throw new IllegalArgumentException(
                    "recovery contains a request or result outside the retained handle");
        }
        steps = Map.copyOf(stepValues);
        retainedInputCount = outputValues.size();
    }

    @Override
    public SourceInputPlan.PlannedModelInput planFieldInput(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            ExecutionScopeCodec.OutputKey output) {
        if (!(output.scope() instanceof ExecutionScopeCodec.FieldScope)) {
            throw new IllegalArgumentException("field input requires a field output scope");
        }
        return requiredSource(source, target).fieldInputs().stream()
                .filter(input -> input.output().equals(output))
                .findFirst()
                .orElseThrow(
                        () ->
                                new LargeActionTransactionValidator.Conflict(
                                        "fresh source plan lacks the selected field input"));
    }

    @Override
    public SourceInputPlan.PlannedModelInput planParentInput(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            qupath.ext.histoforge.contract.InferenceIdentityCodec.ModelStepIdentity producer,
            String parentId,
            Geometry parentGeometry) {
        try {
            return SourceInputPlanner.planParentInput(
                    requiredSource(source, target), producer, parentId, parentGeometry);
        } catch (LargeActionTransactionValidator.Conflict mismatch) {
            throw mismatch;
        } catch (IllegalArgumentException mismatch) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh parent input differs from the retained model step", mismatch);
        }
    }

    @Override
    public List<PreparedDurableInput> prepareStep(
            ModuleRecipe.Step step,
            List<SourceInputPlan.PlannedModelInput> plannedInputs,
            List<PathObject> sourceParents,
            RecipeExecutor.AvailableSource source,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existingObjects) {
        StepState state = requireUnusedStep(step.model());
        requireParentPlan(step, state, sourceParents);
        final DurableRequestSharder.StepPlan reproduced;
        try {
            reproduced =
                    DurableRequestSharder.reproduce(
                            plan, step, plannedInputs, source, state.shards());
        } catch (IllegalArgumentException mismatch) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh input plan differs from the retained model step", mismatch);
        }
        String freshInputPlan = reproduced.inputPlanDigest();
        if (!state.retained().inputPlanDigest().equals(freshInputPlan)) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh input plan differs from the retained model step");
        }
        if (state.inputs().size() != plannedInputs.size()) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh recipe input count differs from the retained model step");
        }
        List<CandidatePipeline.ExistingObjectSnapshot> preservation =
                List.copyOf(Objects.requireNonNull(existingObjects, "existingObjects"));
        List<PreparedDurableInput> prepared = new ArrayList<>();
        for (DurableRequestSharder.AssignedInput assigned : reproduced.inputs()) {
            SourceInputPlan.PlannedModelInput planned = assigned.plannedInput();
            CompletedInput completed = state.inputs().get(planned.output());
            if (completed == null) {
                throw new LargeActionTransactionValidator.Conflict(
                        "fresh recipe input differs from its retained canonical request");
            }
            ModelRunRequestCodec.ModelRunInput input = completed.input();
            if (!input.equals(assigned.requestInput())) {
                throw new LargeActionTransactionValidator.Conflict(
                        "fresh recipe input differs from its retained canonical request");
            }
            if (!consumedGenerationIds.add(input.outputGenerationId())) {
                throw new IllegalArgumentException(
                        "retained requests repeat one output-generation ID");
            }
            prepared.add(
                    new PreparedDurableInput(
                            DurableModelInputExecutor.prepare(
                                    plan, completed.request(), input, planned, preservation),
                            completed.manifestInput(),
                            completed.features(),
                            completed.execution()));
        }
        consumedSteps.add(step.model());
        return List.copyOf(prepared);
    }

    @Override
    public void verifyEmptyStep(ModuleRecipe.Step step, List<PathObject> sourceParents) {
        StepState state = requireUnusedStep(step.model());
        requireParentPlan(step, state, sourceParents);
        if (!state.inputs().isEmpty()) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh valid-empty step differs from retained model inputs");
        }
        consumedSteps.add(step.model());
    }

    @Override
    public CandidateGeneration execute(PreparedInput input, BooleanSupplier shouldStop)
            throws IOException {
        if (!(input instanceof PreparedDurableInput durable)) {
            throw new IllegalArgumentException("durable adapter received a finite input");
        }
        if (shouldStop.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException(
                    "durable recipe reconstruction cancelled");
        }
        return DurableModelInputExecutor.complete(
                durable.prepared(),
                durable.manifestInput(),
                durable.features(),
                durable.execution(),
                shouldStop);
    }

    @Override
    public void requireComplete() {
        if (!consumedSteps.equals(steps.keySet())
                || consumedGenerationIds.size() != retainedInputCount) {
            throw new LargeActionTransactionValidator.Conflict(
                    "reconstructed recipe did not consume every retained model result");
        }
    }

    private StepState requireUnusedStep(String stepId) {
        StepState state = steps.get(stepId);
        if (state == null || consumedSteps.contains(stepId)) {
            throw new LargeActionTransactionValidator.Conflict(
                    "reconstructed recipe step is absent or repeated in retained state");
        }
        return state;
    }

    private void requireParentPlan(
            ModuleRecipe.Step step, StepState state, List<PathObject> sourceParents) {
        if (!step.isRegionGated()) {
            if (!sourceParents.isEmpty() || state.retained().parentPlanDigest().isPresent()) {
                throw new LargeActionTransactionValidator.Conflict(
                        "field step must not carry a source-parent plan");
            }
            return;
        }
        final String actual;
        try {
            actual =
                    RecipeStepPlanDigester.parentPlanDigest(
                            plan.module().activatedModels().get(step.model()).input(),
                            RecipeStepPlanDigester.captureParents(
                                    plan.targetRegions().getFirst(), sourceParents));
        } catch (IllegalArgumentException mismatch) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh source-parent plan is invalid", mismatch);
        }
        if (!state.retained().parentPlanDigest().filter(actual::equals).isPresent()) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh source-parent plan differs from the retained model step");
        }
    }

    private static SourceInputPlan.TargetSourcePlan requiredSource(
            SourceInputPlan.TargetPlanningResult source, ExecutionScopeCodec.TargetRegion target) {
        if (source == null || !source.target().equals(target) || source.sourcePlan().isEmpty()) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh source plan lacks the selected durable model input");
        }
        SourceInputPlan.TargetSourcePlan plan = source.sourcePlan().orElseThrow();
        if (plan.protocol() != SourceInputPlan.ExecutionProtocol.RECOVERABLE) {
            throw new LargeActionTransactionValidator.Conflict(
                    "retained large action no longer resolves to recoverable model execution");
        }
        return plan;
    }
}
