package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec.CoreHaloProfile;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.SourceInputPlan;

/** Deterministically assigns and shards one durable recipe model step. */
final class DurableRequestSharder {

    private static final String RESUME_SIZING_RUN_ID_A = "ffffffff-ffff-4fff-bfff-ffffffffffff";
    private static final String RESUME_SIZING_RUN_ID_B = "eeeeeeee-eeee-4eee-beee-eeeeeeeeeeee";
    private static final String RESUME_SIZING_MANIFEST_DIGEST = "0".repeat(64);

    private DurableRequestSharder() {}

    /** One fresh logical recipe input bound to its durable action-local assignments. */
    record AssignedInput(
            SourceInputPlan.PlannedModelInput plannedInput,
            ModelRunRequestCodec.ModelRunInput requestInput) {
        AssignedInput {
            plannedInput = Objects.requireNonNull(plannedInput, "plannedInput");
            requestInput = Objects.requireNonNull(requestInput, "requestInput");
            if (!plannedInput.output().equals(requestInput.output())) {
                throw new IllegalArgumentException(
                        "planned and durable request inputs have different logical outputs");
            }
        }
    }

    /** One deterministic request boundary and its exact assigned logical inputs. */
    record PlannedShard(
            int shardIndex,
            ModelRunRequestCodec.ModelRunRequest request,
            List<AssignedInput> inputs) {
        PlannedShard {
            shardIndex = CanonicalCodec.requireNonnegativeInt32(shardIndex, "shard_index");
            request = Objects.requireNonNull(request, "request");
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
            if (inputs.isEmpty()
                    || !inputs.stream()
                            .map(AssignedInput::requestInput)
                            .toList()
                            .equals(request.inputs())) {
                throw new IllegalArgumentException(
                        "planned shard inputs must equal its canonical request inputs");
            }
        }
    }

    /** Complete canonical assignments and request boundaries for one selected model step. */
    record StepPlan(List<AssignedInput> inputs, List<PlannedShard> shards) {
        StepPlan {
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
            shards = List.copyOf(Objects.requireNonNull(shards, "shards"));
            Set<ModelRunRequestCodec.ModelRunInput> assigned = new LinkedHashSet<>();
            inputs.forEach(value -> assigned.add(value.requestInput()));
            if (assigned.size() != inputs.size()) {
                throw new IllegalArgumentException(
                        "durable step repeats an assigned request input");
            }
            Set<ModelRunRequestCodec.ModelRunInput> requested = new LinkedHashSet<>();
            Set<String> modelRunIds = new LinkedHashSet<>();
            int requestedCount = 0;
            for (int index = 0; index < shards.size(); index++) {
                PlannedShard shard = Objects.requireNonNull(shards.get(index), "shard");
                if (shard.shardIndex() != index) {
                    throw new IllegalArgumentException(
                            "durable request shards must be contiguous from zero");
                }
                if (!modelRunIds.add(shard.request().modelRunId())) {
                    throw new IllegalArgumentException(
                            "durable request shards repeat a model-run ID");
                }
                requested.addAll(shard.request().inputs());
                requestedCount += shard.request().inputs().size();
            }
            if (requested.size() != requestedCount) {
                throw new IllegalArgumentException("durable request shards repeat a request input");
            }
            if (!assigned.equals(requested)) {
                throw new IllegalArgumentException(
                        "durable request shards do not cover the assigned step inputs");
            }
        }

        List<RecipeStepPlanDigester.RequestShard> digestShards() {
            return shards.stream()
                    .map(
                            value ->
                                    new RecipeStepPlanDigester.RequestShard(
                                            value.shardIndex(), value.request()))
                    .toList();
        }

        String inputPlanDigest() {
            return RecipeStepPlanDigester.inputPlanDigest(digestShards());
        }
    }

    /** Assign fresh IDs in canonical OutputKey order and shard the initial model step. */
    static StepPlan initial(
            ImageExecutionPlan plan,
            ModuleRecipe.Step step,
            List<SourceInputPlan.PlannedModelInput> plannedInputs,
            RecipeExecutor.AvailableSource source,
            long gatewayRequestBytes,
            Supplier<String> generationIds,
            Supplier<String> modelRunIds) {
        List<SourceInputPlan.PlannedModelInput> ordered = orderedInputs(plannedInputs);
        Supplier<String> generations = Objects.requireNonNull(generationIds, "generationIds");
        Set<String> issuedGenerations = new HashSet<>();
        List<AssignedInput> assigned = new ArrayList<>(ordered.size());
        for (int inputId = 0; inputId < ordered.size(); inputId++) {
            String generationId =
                    CanonicalCodec.requireCanonicalUuid(generations.get(), "output_generation_id");
            if (!issuedGenerations.add(generationId)) {
                throw new IllegalArgumentException("durable step generation IDs must be unique");
            }
            assigned.add(
                    assignedInput(
                            requireModel(plan, step),
                            ordered.get(inputId),
                            source,
                            inputId,
                            generationId));
        }
        return shard(plan, step, assigned, gatewayRequestBytes, modelRunIds);
    }

    /**
     * Rebind fresh recipe geometry to the retained assignments and request boundaries.
     *
     * <p>Run IDs and optional resume sources are replaceable lifecycle fields. All remaining
     * request bytes must reproduce exactly. Current gateway limits apply to new submissions, not to
     * reconstruction of results from previously accepted requests.
     */
    static StepPlan reproduce(
            ImageExecutionPlan plan,
            ModuleRecipe.Step step,
            List<SourceInputPlan.PlannedModelInput> plannedInputs,
            RecipeExecutor.AvailableSource source,
            List<RecipeStepPlanDigester.RequestShard> retainedShards) {
        List<RecipeStepPlanDigester.RequestShard> retained =
                List.copyOf(Objects.requireNonNull(retainedShards, "retainedShards"));
        Map<ExecutionScopeCodec.OutputKey, ModelRunRequestCodec.ModelRunInput> retainedByOutput =
                new LinkedHashMap<>();
        for (RecipeStepPlanDigester.RequestShard shard : retained) {
            for (ModelRunRequestCodec.ModelRunInput input : shard.request().inputs()) {
                if (retainedByOutput.putIfAbsent(input.output(), input) != null) {
                    throw new IllegalArgumentException(
                            "retained durable requests repeat one logical output");
                }
            }
        }

        List<SourceInputPlan.PlannedModelInput> ordered = orderedInputs(plannedInputs);
        if (ordered.size() != retainedByOutput.size()) {
            throw new IllegalArgumentException(
                    "fresh recipe inputs differ from retained durable assignments");
        }
        ModuleSnapshot.ModelSnapshot model = requireModel(plan, step);
        List<AssignedInput> assigned = new ArrayList<>(ordered.size());
        for (int inputId = 0; inputId < ordered.size(); inputId++) {
            SourceInputPlan.PlannedModelInput planned = ordered.get(inputId);
            ModelRunRequestCodec.ModelRunInput prior = retainedByOutput.get(planned.output());
            if (prior == null || prior.inputId() != inputId) {
                throw new IllegalArgumentException(
                        "retained durable input IDs do not match canonical OutputKey order");
            }
            assigned.add(
                    assignedInput(
                            model, planned, source, prior.inputId(), prior.outputGenerationId()));
        }

        Map<ExecutionScopeCodec.OutputKey, AssignedInput> freshByOutput = new LinkedHashMap<>();
        for (AssignedInput input : assigned) {
            freshByOutput.put(input.requestInput().output(), input);
        }
        Map<ExecutionScopeCodec.OutputKey, AssignedInput> reboundByOutput = new LinkedHashMap<>();
        List<PlannedShard> retainedPlan = new ArrayList<>();
        for (RecipeStepPlanDigester.RequestShard shard : retained) {
            List<AssignedInput> shardInputs =
                    shard.request().inputs().stream()
                            .map(
                                    input -> {
                                        AssignedInput fresh = freshByOutput.get(input.output());
                                        if (fresh == null
                                                || !Arrays.equals(
                                                        CanonicalCodec.canonicalBytes(
                                                                ModelRunRequestCodec.toJson(
                                                                        fresh.requestInput())),
                                                        CanonicalCodec.canonicalBytes(
                                                                ModelRunRequestCodec.toJson(
                                                                        input)))) {
                                            throw new IllegalArgumentException(
                                                    "retained durable shard differs from its fresh"
                                                            + " assigned input");
                                        }
                                        AssignedInput rebound =
                                                new AssignedInput(fresh.plannedInput(), input);
                                        if (reboundByOutput.putIfAbsent(input.output(), rebound)
                                                != null) {
                                            throw new IllegalArgumentException(
                                                    "retained durable shards repeat one assigned"
                                                            + " input");
                                        }
                                        return rebound;
                                    })
                            .toList();
            var actual = shard.request();
            var expected =
                    request(
                            plan,
                            step,
                            model,
                            actual.modelRunId(),
                            actual.expectedProfile().isPresent()
                                    ? model.largeRegionProfile()
                                    : Optional.empty(),
                            shardInputs);
            if (!Arrays.equals(
                    ModelRunRequestCodec.replacementPlanBytes(expected),
                    ModelRunRequestCodec.replacementPlanBytes(actual))) {
                throw new IllegalArgumentException(
                        "fresh durable request plan differs from its retained shard");
            }
            ModelRunRequestCodec.validatePlanFamily(
                    actual,
                    model.capabilities().finite().targetRaster(),
                    model.largeRegionProfile());
            ModelRunRequestCodec.validateResourceLimits(
                    actual, model.capabilities().durableRun().orElseThrow().run());
            retainedPlan.add(new PlannedShard(shard.shardIndex(), actual, shardInputs));
        }
        List<AssignedInput> retainedAssigned =
                assigned.stream()
                        .map(
                                value ->
                                        Objects.requireNonNull(
                                                reboundByOutput.get(value.requestInput().output())))
                        .toList();
        return new StepPlan(retainedAssigned, retainedPlan);
    }

    private static StepPlan shard(
            ImageExecutionPlan plan,
            ModuleRecipe.Step step,
            List<AssignedInput> assignedInputs,
            long gatewayRequestBytes,
            Supplier<String> modelRunIds) {
        ImageExecutionPlan action = Objects.requireNonNull(plan, "plan");
        ModuleRecipe.Step recipeStep = Objects.requireNonNull(step, "step");
        ModuleSnapshot.ModelSnapshot model = requireModel(action, recipeStep);
        long gatewayLimit =
                CanonicalCodec.requirePositiveSafeInteger(
                        gatewayRequestBytes, "gateway max_json_request_bytes");
        Supplier<String> runIds = Objects.requireNonNull(modelRunIds, "modelRunIds");
        List<AssignedInput> assigned =
                List.copyOf(Objects.requireNonNull(assignedInputs, "assignedInputs"));
        for (int index = 0; index < assigned.size(); index++) {
            if (assigned.get(index).requestInput().inputId() != index) {
                throw new IllegalArgumentException(
                        "durable step input IDs must be the canonical zero-based positions");
            }
        }
        if (assigned.isEmpty()) {
            return new StepPlan(List.of(), List.of());
        }

        List<AssignedInput> finite = new ArrayList<>();
        List<AssignedInput> profiled = new ArrayList<>();
        for (AssignedInput input : assigned) {
            boolean fits =
                    ExecutionLimitsCodec.fitsTargetRaster(
                            input.requestInput().rasterInput().frame().transform().raster(),
                            model.capabilities().finite().targetRaster());
            (fits ? finite : profiled).add(input);
        }

        List<PlannedShard> shards = new ArrayList<>();
        appendFamily(
                action, recipeStep, model, finite, Optional.empty(), gatewayLimit, runIds, shards);
        if (!profiled.isEmpty() && model.largeRegionProfile().isEmpty()) {
            throw new IllegalArgumentException(
                    "oversized durable input requires an advertised large-region profile");
        }
        appendFamily(
                action,
                recipeStep,
                model,
                profiled,
                model.largeRegionProfile(),
                gatewayLimit,
                runIds,
                shards);
        return new StepPlan(assigned, shards);
    }

    private static void appendFamily(
            ImageExecutionPlan plan,
            ModuleRecipe.Step step,
            ModuleSnapshot.ModelSnapshot model,
            List<AssignedInput> family,
            Optional<qupath.ext.histoforge.contract.LargeRegionProfileCodec.LargeRegionProfile>
                    profile,
            long gatewayLimit,
            Supplier<String> modelRunIds,
            List<PlannedShard> result) {
        if (family.isEmpty()) {
            return;
        }
        List<AssignedInput> current = new ArrayList<>();
        String currentRunId = nextRunId(modelRunIds);
        for (AssignedInput input : family) {
            List<AssignedInput> candidate = new ArrayList<>(current);
            candidate.add(input);
            ModelRunRequestCodec.ModelRunRequest candidateRequest =
                    request(plan, step, model, currentRunId, profile, candidate);
            if (fits(candidateRequest, model, gatewayLimit)) {
                current = candidate;
                continue;
            }
            if (current.isEmpty()) {
                validate(candidateRequest, model, gatewayLimit);
                throw new IllegalStateException("unreachable invalid durable request");
            }
            addShard(plan, step, model, currentRunId, profile, current, gatewayLimit, result);
            currentRunId = nextRunId(modelRunIds);
            current = new ArrayList<>(List.of(input));
            validate(
                    request(plan, step, model, currentRunId, profile, current),
                    model,
                    gatewayLimit);
        }
        addShard(plan, step, model, currentRunId, profile, current, gatewayLimit, result);
    }

    private static void addShard(
            ImageExecutionPlan plan,
            ModuleRecipe.Step step,
            ModuleSnapshot.ModelSnapshot model,
            String modelRunId,
            Optional<qupath.ext.histoforge.contract.LargeRegionProfileCodec.LargeRegionProfile>
                    profile,
            List<AssignedInput> inputs,
            long gatewayLimit,
            List<PlannedShard> result) {
        ModelRunRequestCodec.ModelRunRequest request =
                request(plan, step, model, modelRunId, profile, inputs);
        validate(request, model, gatewayLimit);
        result.add(new PlannedShard(result.size(), request, inputs));
    }

    private static ModelRunRequestCodec.ModelRunRequest request(
            ImageExecutionPlan plan,
            ModuleRecipe.Step step,
            ModuleSnapshot.ModelSnapshot model,
            String modelRunId,
            Optional<qupath.ext.histoforge.contract.LargeRegionProfileCodec.LargeRegionProfile>
                    profile,
            List<AssignedInput> inputs) {
        return new ModelRunRequestCodec.ModelRunRequest(
                ModelRunRequestCodec.SCHEMA_VERSION,
                plan.actionId(),
                CanonicalCodec.requireCanonicalUuid(modelRunId, "model_run_id"),
                inputs.getFirst().requestInput().output().producer(),
                plan.module().backendIdentity(step.model()),
                profile,
                inputs.stream().map(AssignedInput::requestInput).toList(),
                resultBudget(model),
                Optional.empty(),
                profile.filter(CoreHaloProfile.class::isInstance)
                        .flatMap(ignored -> model.haloPxOverride()));
    }

    private static boolean fits(
            ModelRunRequestCodec.ModelRunRequest request,
            ModuleSnapshot.ModelSnapshot model,
            long gatewayLimit) {
        try {
            validate(request, model, gatewayLimit);
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    private static void validate(
            ModelRunRequestCodec.ModelRunRequest request,
            ModuleSnapshot.ModelSnapshot model,
            long gatewayLimit) {
        var durable = model.capabilities().durableRun().orElseThrow();
        ModelRunRequestCodec.validatePlanFamily(
                request, model.capabilities().finite().targetRaster(), model.largeRegionProfile());
        ModelRunRequestCodec.ModelRunRequest sizingRequest = withResumeSizingOverhead(request);
        ModelRunRequestCodec.validateResourceLimits(sizingRequest, durable.run());
        if (CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(sizingRequest)).length
                > gatewayLimit) {
            throw new IllegalArgumentException(
                    "model run request exceeds captured gateway JSON limit");
        }
    }

    private static ModelRunRequestCodec.ModelRunRequest withResumeSizingOverhead(
            ModelRunRequestCodec.ModelRunRequest request) {
        if (request.resumeFrom().isPresent()) {
            return request;
        }
        String previousRunId =
                request.modelRunId().equals(RESUME_SIZING_RUN_ID_A)
                        ? RESUME_SIZING_RUN_ID_B
                        : RESUME_SIZING_RUN_ID_A;
        return new ModelRunRequestCodec.ModelRunRequest(
                request.schemaVersion(),
                request.actionId(),
                request.modelRunId(),
                request.producer(),
                request.expectedBackend(),
                request.expectedProfile(),
                request.inputs(),
                request.resultBudget(),
                Optional.of(
                        new ModelRunRequestCodec.ModelRunResumeSource(
                                previousRunId, RESUME_SIZING_MANIFEST_DIGEST)),
                request.haloPxOverride());
    }

    private static AssignedInput assignedInput(
            ModuleSnapshot.ModelSnapshot model,
            SourceInputPlan.PlannedModelInput planned,
            RecipeExecutor.AvailableSource source,
            int inputId,
            String outputGenerationId) {
        var sourceView = Objects.requireNonNull(source, "source").viewFor(planned.sourceView());
        RasterInputCodec.ModelRasterInput rasterInput =
                RasterInputCodec.buildModelRasterInput(
                        sourceView, planned.frame(), model.capabilities().rasterizer());
        return new AssignedInput(
                planned,
                new ModelRunRequestCodec.ModelRunInput(
                        inputId, outputGenerationId, planned.output(), rasterInput));
    }

    private static List<SourceInputPlan.PlannedModelInput> orderedInputs(
            List<SourceInputPlan.PlannedModelInput> values) {
        List<SourceInputPlan.PlannedModelInput> result =
                new ArrayList<>(List.copyOf(Objects.requireNonNull(values, "plannedInputs")));
        result.sort(
                (left, right) ->
                        Arrays.compareUnsigned(
                                ExecutionScopeCodec.canonicalBytes(left.output()),
                                ExecutionScopeCodec.canonicalBytes(right.output())));
        Set<ExecutionScopeCodec.OutputKey> outputs = new HashSet<>();
        for (SourceInputPlan.PlannedModelInput input : result) {
            if (!outputs.add(Objects.requireNonNull(input, "planned input").output())) {
                throw new IllegalArgumentException(
                        "durable step contains a duplicate logical output");
            }
        }
        return List.copyOf(result);
    }

    private static ModuleSnapshot.ModelSnapshot requireModel(
            ImageExecutionPlan plan, ModuleRecipe.Step step) {
        ImageExecutionPlan action = Objects.requireNonNull(plan, "plan");
        ModuleRecipe.Step recipeStep = Objects.requireNonNull(step, "step");
        if (!recipeStep.isModelStep()) {
            throw new IllegalArgumentException("durable sharding requires a model step");
        }
        ModuleSnapshot.ModelSnapshot model =
                action.module().activatedModels().get(recipeStep.model());
        if (model == null || model.capabilities().durableRun().isEmpty()) {
            throw new IllegalArgumentException(
                    "durable sharding requires one activated durable model");
        }
        return model;
    }

    private static FiniteInferenceCodec.ClientResultBudget resultBudget(
            ModuleSnapshot.ModelSnapshot model) {
        ExecutionLimitsCodec.ResultLimits result =
                model.capabilities().durableRun().orElseThrow().run().result();
        return new FiniteInferenceCodec.ClientResultBudget(
                result.maxTransferredBytes(),
                result.maxDecodedBytes(),
                result.maxRecordBytes(),
                result.maxFeatures(),
                result.maxVertices());
    }

    private static String nextRunId(Supplier<String> modelRunIds) {
        return CanonicalCodec.requireCanonicalUuid(modelRunIds.get(), "model_run_id");
    }
}
