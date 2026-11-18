package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable durable model-run unit, checkpoint, input, and manifest contract. */
public final class ModelRunPublicationCodec {

    public static final int SCHEMA_VERSION = ModelRunRequestCodec.SCHEMA_VERSION;
    public static final String FEATURE_STREAM_MEDIA_TYPE = "application/geo+json-seq";
    public static final String FEATURE_STREAM_CONTENT_ENCODING = "gzip";

    private static final Set<String> UNIT_RESULT_FIELDS =
            Set.of("input_id", "unit_id", "reusable_state", "execution_identity_digest");
    private static final Set<String> CHECKPOINT_FIELDS =
            Set.of("input_id", "unit_id", "sequence", "state", "execution_identity_digest");
    private static final Set<String> UNIT_FAILURE_FIELDS = Set.of("input_id", "unit_id", "failure");
    private static final Set<String> INPUT_RESULT_FIELDS =
            Set.of(
                    "input_id",
                    "input_identity",
                    "execution_plan",
                    "feature_stream_artifact",
                    "feature_count",
                    "vertex_count");
    private static final Set<String> MANIFEST_FIELDS =
            Set.of(
                    "schema_version",
                    "model_run_id",
                    "action_id",
                    "backend",
                    "request_digest",
                    "completed_units",
                    "checkpoints",
                    "failed_units",
                    "completed_inputs",
                    "complete",
                    "manifest_digest");

    private ModelRunPublicationCodec() {}

    /** Reusable backend-internal state for one completed core/halo unit. */
    public record UnitResult(
            int inputId,
            int unitId,
            ArtifactRefCodec.ArtifactRef reusableState,
            String executionIdentityDigest) {
        public UnitResult {
            inputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            unitId = CanonicalCodec.requireNonnegativeInt32(unitId, "unit_id");
            reusableState = Objects.requireNonNull(reusableState, "reusable_state");
            executionIdentityDigest =
                    CanonicalCodec.requireSha256(
                            executionIdentityDigest, "execution_identity_digest");
        }
    }

    /** Self-contained global accumulator state after a deterministic tile count. */
    public record Checkpoint(
            int inputId,
            int unitId,
            long sequence,
            ArtifactRefCodec.ArtifactRef state,
            String executionIdentityDigest) {
        public Checkpoint {
            inputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            unitId = CanonicalCodec.requireNonnegativeInt32(unitId, "unit_id");
            sequence = CanonicalCodec.requirePositiveSafeInteger(sequence, "sequence");
            state = Objects.requireNonNull(state, "state");
            executionIdentityDigest =
                    CanonicalCodec.requireSha256(
                            executionIdentityDigest, "execution_identity_digest");
        }
    }

    /** Closed diagnostic failure for one generated plan unit. */
    public record UnitFailure(int inputId, int unitId, GatewayErrorCodec.Error failure) {
        public UnitFailure {
            inputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            unitId = CanonicalCodec.requireNonnegativeInt32(unitId, "unit_id");
            failure = Objects.requireNonNull(failure, "failure");
        }
    }

    /** Complete final biological feature stream for one logical model input. */
    public record InputResult(
            int inputId,
            FiniteInferenceCodec.BackendInputIdentity inputIdentity,
            ModelExecutionPlanCodec.ModelExecutionPlan executionPlan,
            ArtifactRefCodec.ArtifactRef featureStreamArtifact,
            long featureCount,
            long vertexCount) {
        public InputResult {
            inputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            inputIdentity = Objects.requireNonNull(inputIdentity, "input_identity");
            executionPlan = Objects.requireNonNull(executionPlan, "execution_plan");
            featureStreamArtifact =
                    Objects.requireNonNull(featureStreamArtifact, "feature_stream_artifact");
            featureCount =
                    CanonicalCodec.requireNonnegativeSafeInteger(featureCount, "feature_count");
            vertexCount = CanonicalCodec.requireNonnegativeSafeInteger(vertexCount, "vertex_count");
            if ((featureCount == 0) != (vertexCount == 0)) {
                throw new IllegalArgumentException(
                        "feature_count and vertex_count must be zero together");
            }
        }
    }

    /** Immutable result, with an optional actual failure only when reusable work is partial. */
    public record Manifest(
            int schemaVersion,
            String modelRunId,
            String actionId,
            InferenceIdentityCodec.BackendModelIdentity backend,
            String requestDigest,
            List<UnitResult> completedUnits,
            List<Checkpoint> checkpoints,
            List<UnitFailure> failedUnits,
            List<InputResult> completedInputs,
            boolean complete,
            Optional<GatewayErrorCodec.Error> failure,
            String manifestDigest,
            Optional<List<ModelRunSourceCodec.Binding>> sourceBindings) {
        public Manifest(
                int schemaVersion,
                String modelRunId,
                String actionId,
                InferenceIdentityCodec.BackendModelIdentity backend,
                String requestDigest,
                List<UnitResult> completedUnits,
                List<Checkpoint> checkpoints,
                List<UnitFailure> failedUnits,
                List<InputResult> completedInputs,
                boolean complete,
                Optional<GatewayErrorCodec.Error> failure,
                String manifestDigest) {
            this(
                    schemaVersion,
                    modelRunId,
                    actionId,
                    backend,
                    requestDigest,
                    completedUnits,
                    checkpoints,
                    failedUnits,
                    completedInputs,
                    complete,
                    failure,
                    manifestDigest,
                    Optional.empty());
        }

        public Manifest {
            requireCurrentSchema(schemaVersion);
            modelRunId = CanonicalCodec.requireCanonicalUuid(modelRunId, "model_run_id");
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            backend = Objects.requireNonNull(backend, "backend");
            requestDigest = CanonicalCodec.requireSha256(requestDigest, "request_digest");
            completedUnits = immutableList(completedUnits, "completed_units");
            checkpoints = immutableList(checkpoints, "checkpoints");
            failedUnits = immutableList(failedUnits, "failed_units");
            completedInputs = immutableList(completedInputs, "completed_inputs");
            failure = Objects.requireNonNull(failure, "failure");
            if (complete && failure.isPresent()) {
                throw new IllegalArgumentException("complete manifest cannot carry failure");
            }
            manifestDigest = CanonicalCodec.requireSha256(manifestDigest, "manifest_digest");
            sourceBindings =
                    Objects.requireNonNull(sourceBindings, "source_bindings")
                            .map(ModelRunSourceCodec::requireOrdered);
        }
    }

    /** Bind preprocessing behavior to one exact immutable raster input. */
    public static String preprocessingContextDigest(
            ModelRunRequestCodec.ModelRunInput input,
            InferenceIdentityCodec.BackendModelIdentity backend) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(backend, "backend");
        JsonObject preimage = new JsonObject();
        var rasterInput = input.rasterInput();
        if (rasterInput.sourceView() instanceof SourceCaptureCodec.View) {
            // The processing plan exists before any files arrive; its identity cannot depend on
            // them.
            var identity = new JsonObject();
            identity.addProperty("source_view_digest", rasterInput.sourceView().viewDigest());
            identity.addProperty(
                    "model_raster_input_digest",
                    RasterInputCodec.modelRasterInputDigest(rasterInput));
            preimage.add("input_identity", identity);
        } else {
            preimage.add(
                    "input_identity",
                    FiniteInferenceCodec.toJson(
                            FiniteInferenceCodec.buildBackendInputIdentity(rasterInput)));
        }
        preimage.addProperty(
                "preprocessing_contract_digest", backend.preprocessingContractDigest());
        return CanonicalCodec.canonicalDigest(preimage);
    }

    /** Derive every bounded plan from the accepted request and runner limits. */
    public static Map<Integer, ModelExecutionPlanCodec.ModelExecutionPlan> buildExpectedPlans(
            ModelRunRequestCodec.ModelRunRequest request,
            ExecutionLimitsCodec.ModelRunLimits limits) {
        ModelRunRequestCodec.validateResourceLimits(request, limits);
        Map<Integer, ModelExecutionPlanCodec.ModelExecutionPlan> plans = new HashMap<>();
        for (var input : request.inputs()) {
            var raster = input.rasterInput().frame().transform().raster();
            plans.put(
                    input.inputId(),
                    ModelExecutionPlanCodec.buildExpected(
                            request.expectedProfile(),
                            raster,
                            preprocessingContextDigest(input, request.expectedBackend()),
                            limits.maxUnits(),
                            request.haloPxOverride()));
        }
        return Map.copyOf(plans);
    }

    /** Return the exact-compatible reusable-state identity for one plan unit. */
    public static String executionIdentityDigest(
            ModelRunRequestCodec.ModelRunRequest request,
            int inputId,
            int unitId,
            ExecutionLimitsCodec.ModelRunLimits limits) {
        return executionIdentityDigest(request, inputId, unitId, limits, List.of());
    }

    public static String executionIdentityDigest(
            ModelRunRequestCodec.ModelRunRequest request,
            int inputId,
            int unitId,
            ExecutionLimitsCodec.ModelRunLimits limits,
            List<ModelRunSourceCodec.Binding> bindings) {
        int normalizedInputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
        int normalizedUnitId = CanonicalCodec.requireNonnegativeInt32(unitId, "unit_id");
        ModelRunRequestCodec.ModelRunInput input = inputMap(request).get(normalizedInputId);
        if (input == null) {
            throw new IllegalArgumentException(
                    "execution identity input_id is absent from model run request");
        }
        var plans = buildExpectedPlans(request, limits);
        ModelRunSourceCodec.validateBindings(bindings, request, plans);
        var plan = plans.get(normalizedInputId);
        requirePlanUnit(plan, normalizedUnitId);
        return executionIdentityDigest(request, input, plan, normalizedUnitId, bindings);
    }

    /** Parse and self-digest validate one context-free terminal manifest. */
    public static Manifest parseManifest(JsonElement value) {
        JsonObject object =
                requireExactObject(
                        value,
                        MANIFEST_FIELDS,
                        Set.of("failure", "source_bindings"),
                        "model result manifest");
        Manifest manifest =
                new Manifest(
                        CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                        requiredString(object, "model_run_id"),
                        requiredString(object, "action_id"),
                        InferenceIdentityCodec.parseBackendModelIdentity(object.get("backend")),
                        requiredString(object, "request_digest"),
                        parseArray(object, "completed_units", ModelRunPublicationCodec::parseUnit),
                        parseArray(
                                object, "checkpoints", ModelRunPublicationCodec::parseCheckpoint),
                        parseArray(
                                object, "failed_units", ModelRunPublicationCodec::parseUnitFailure),
                        parseArray(
                                object,
                                "completed_inputs",
                                ModelRunPublicationCodec::parseInputResult),
                        requiredBoolean(object.get("complete"), "complete"),
                        object.has("failure")
                                ? Optional.of(GatewayErrorCodec.parse(object.get("failure")))
                                : Optional.empty(),
                        requiredString(object, "manifest_digest"),
                        object.has("source_bindings")
                                ? Optional.of(
                                        ModelRunSourceCodec.parseBindings(
                                                object.get("source_bindings")))
                                : Optional.empty());
        String expected = CanonicalCodec.canonicalDigest(manifestPreimageJson(manifest));
        if (!manifest.manifestDigest().equals(expected)) {
            throw new IllegalArgumentException(
                    "manifest_digest does not match model result manifest fields");
        }
        return manifest;
    }

    /** Validate one terminal manifest against its exact request, plans, and caps. */
    public static Manifest validateManifest(
            JsonElement value,
            ModelRunRequestCodec.ModelRunRequest request,
            ExecutionLimitsCodec.ModelRunLimits limits) {
        ModelRunRequestCodec.validateResourceLimits(request, limits);
        Manifest manifest = parseManifest(value);
        if (!manifest.modelRunId().equals(request.modelRunId())) {
            throw new IllegalArgumentException(
                    "manifest model_run_id does not match model run request");
        }
        if (!manifest.actionId().equals(request.actionId())) {
            throw new IllegalArgumentException(
                    "manifest action_id does not match model run request");
        }
        if (!manifest.backend().equals(request.expectedBackend())) {
            throw new IllegalArgumentException("manifest backend does not match model run request");
        }
        if (!manifest.requestDigest().equals(ModelRunRequestCodec.requestDigest(request))) {
            throw new IllegalArgumentException(
                    "manifest request_digest does not match model run request");
        }

        Map<Integer, ModelRunRequestCodec.ModelRunInput> inputs = inputMap(request);
        Map<Integer, ModelExecutionPlanCodec.ModelExecutionPlan> plans =
                buildExpectedPlans(request, limits);
        boolean captured =
                request.inputs().stream()
                        .anyMatch(
                                input ->
                                        input.rasterInput().sourceView()
                                                instanceof SourceCaptureCodec.View);
        if (captured != manifest.sourceBindings().isPresent()) {
            throw new IllegalArgumentException(
                    "manifest source_bindings must be present exactly for captured inputs");
        }
        var bindings = manifest.sourceBindings().orElse(List.of());
        ModelRunSourceCodec.validateBindings(bindings, request, plans);
        validateCanonicalOrder(manifest.completedUnits(), "completed_units");
        validateCanonicalOrder(manifest.checkpoints(), "checkpoints");
        validateCanonicalOrder(manifest.failedUnits(), "failed_units");
        validateInputOrder(manifest.completedInputs());

        for (UnitResult unit : manifest.completedUnits()) {
            var plan = requirePlanUnit(plans.get(unit.inputId()), unit.unitId());
            if (!(plan instanceof ModelExecutionPlanCodec.CoreHaloPlan)) {
                throw new IllegalArgumentException(
                        "completed unit state is legal only for core-halo plans");
            }
            requireNonemptyArtifact(unit.reusableState(), "reusable_state");
            requireExecutionDigest(
                    unit.executionIdentityDigest(),
                    request,
                    inputs,
                    plan,
                    unit.inputId(),
                    unit.unitId(),
                    bindings);
        }
        for (Checkpoint checkpoint : manifest.checkpoints()) {
            var plan = requirePlanUnit(plans.get(checkpoint.inputId()), checkpoint.unitId());
            if (!(plan instanceof ModelExecutionPlanCodec.GlobalDecodePlan)
                    || checkpoint.unitId() != 0) {
                throw new IllegalArgumentException(
                        "checkpoint is legal only for global-decode unit zero");
            }
            requireNonemptyArtifact(checkpoint.state(), "state");
            requireExecutionDigest(
                    checkpoint.executionIdentityDigest(),
                    request,
                    inputs,
                    plan,
                    checkpoint.inputId(),
                    checkpoint.unitId(),
                    bindings);
        }
        for (UnitFailure failure : manifest.failedUnits()) {
            requirePlanUnit(plans.get(failure.inputId()), failure.unitId());
        }
        for (InputResult result : manifest.completedInputs()) {
            var input = inputs.get(result.inputId());
            if (input == null) {
                throw new IllegalArgumentException(
                        "model input result input_id is absent from request");
            }
            var sources = ModelRunSourceCodec.inputBindings(bindings, input.inputId());
            if (input.rasterInput().sourceView() instanceof SourceCaptureCodec.View
                    && sources.size()
                            != ModelExecutionPlanCodec.unitCount(plans.get(input.inputId()))) {
                throw new IllegalArgumentException(
                        "complete input must identify every planned source unit");
            }
            var expectedIdentity =
                    FiniteInferenceCodec.buildBackendInputIdentity(input.rasterInput(), sources);
            if (!result.inputIdentity().equals(expectedIdentity)) {
                throw new IllegalArgumentException(
                        "model input result input_identity does not match request");
            }
            if (!result.executionPlan().equals(plans.get(result.inputId()))) {
                throw new IllegalArgumentException(
                        "model input result execution_plan does not match request");
            }
            requireFeatureStreamArtifact(result.featureStreamArtifact());
        }
        requireCoverage(manifest, inputs.keySet());
        requireBudgets(manifest, request.resultBudget(), limits);
        return manifest;
    }

    /** Project accepted plan-unit progress from one validated manifest. */
    public static int completedUnitCount(Manifest manifest) {
        int count = manifest.completedUnits().size();
        for (InputResult input : manifest.completedInputs()) {
            count = Math.addExact(count, ModelExecutionPlanCodec.unitCount(input.executionPlan()));
        }
        return count;
    }

    /** Encode one complete manifest using exact lower-snake fields. */
    public static JsonObject toJson(Manifest manifest) {
        JsonObject value = manifestPreimageJson(manifest);
        value.addProperty("manifest_digest", manifest.manifestDigest());
        return value;
    }

    private static UnitResult parseUnit(JsonElement value) {
        JsonObject object = requireExactObject(value, UNIT_RESULT_FIELDS, "model run unit result");
        return new UnitResult(
                nonnegativeInt(object, "input_id"),
                nonnegativeInt(object, "unit_id"),
                ArtifactRefCodec.parse(object.get("reusable_state")),
                requiredString(object, "execution_identity_digest"));
    }

    private static Checkpoint parseCheckpoint(JsonElement value) {
        JsonObject object = requireExactObject(value, CHECKPOINT_FIELDS, "model run checkpoint");
        return new Checkpoint(
                nonnegativeInt(object, "input_id"),
                nonnegativeInt(object, "unit_id"),
                CanonicalCodec.requirePositiveSafeInteger(object.get("sequence"), "sequence"),
                ArtifactRefCodec.parse(object.get("state")),
                requiredString(object, "execution_identity_digest"));
    }

    private static UnitFailure parseUnitFailure(JsonElement value) {
        JsonObject object =
                requireExactObject(value, UNIT_FAILURE_FIELDS, "model run unit failure");
        return new UnitFailure(
                nonnegativeInt(object, "input_id"),
                nonnegativeInt(object, "unit_id"),
                GatewayErrorCodec.parse(object.get("failure")));
    }

    private static InputResult parseInputResult(JsonElement value) {
        JsonObject object = requireExactObject(value, INPUT_RESULT_FIELDS, "model input result");
        return new InputResult(
                nonnegativeInt(object, "input_id"),
                FiniteInferenceCodec.parseBackendInputIdentity(object.get("input_identity")),
                ModelExecutionPlanCodec.parse(object.get("execution_plan")),
                ArtifactRefCodec.parse(object.get("feature_stream_artifact")),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("feature_count"), "feature_count"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("vertex_count"), "vertex_count"));
    }

    private static JsonObject manifestPreimageJson(Manifest manifest) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", manifest.schemaVersion());
        value.addProperty("model_run_id", manifest.modelRunId());
        value.addProperty("action_id", manifest.actionId());
        value.add("backend", InferenceIdentityCodec.toJson(manifest.backend()));
        value.addProperty("request_digest", manifest.requestDigest());
        value.add(
                "completed_units",
                toJsonArray(manifest.completedUnits(), ModelRunPublicationCodec::toJson));
        value.add(
                "checkpoints",
                toJsonArray(manifest.checkpoints(), ModelRunPublicationCodec::toJson));
        value.add(
                "failed_units",
                toJsonArray(manifest.failedUnits(), ModelRunPublicationCodec::toJson));
        value.add(
                "completed_inputs",
                toJsonArray(manifest.completedInputs(), ModelRunPublicationCodec::toJson));
        value.addProperty("complete", manifest.complete());
        manifest.failure()
                .ifPresent(failure -> value.add("failure", GatewayErrorCodec.toJson(failure)));
        manifest.sourceBindings()
                .ifPresent(
                        bindings ->
                                value.add("source_bindings", ModelRunSourceCodec.toJson(bindings)));
        return value;
    }

    private static JsonObject toJson(UnitResult unit) {
        JsonObject value = new JsonObject();
        value.addProperty("input_id", unit.inputId());
        value.addProperty("unit_id", unit.unitId());
        value.add("reusable_state", ArtifactRefCodec.toJson(unit.reusableState()));
        value.addProperty("execution_identity_digest", unit.executionIdentityDigest());
        return value;
    }

    private static JsonObject toJson(Checkpoint checkpoint) {
        JsonObject value = new JsonObject();
        value.addProperty("input_id", checkpoint.inputId());
        value.addProperty("unit_id", checkpoint.unitId());
        value.addProperty("sequence", checkpoint.sequence());
        value.add("state", ArtifactRefCodec.toJson(checkpoint.state()));
        value.addProperty("execution_identity_digest", checkpoint.executionIdentityDigest());
        return value;
    }

    private static JsonObject toJson(UnitFailure failure) {
        JsonObject value = new JsonObject();
        value.addProperty("input_id", failure.inputId());
        value.addProperty("unit_id", failure.unitId());
        value.add("failure", GatewayErrorCodec.toJson(failure.failure()));
        return value;
    }

    private static JsonObject toJson(InputResult result) {
        JsonObject value = new JsonObject();
        value.addProperty("input_id", result.inputId());
        value.add("input_identity", FiniteInferenceCodec.toJson(result.inputIdentity()));
        value.add("execution_plan", ModelExecutionPlanCodec.toJson(result.executionPlan()));
        value.add(
                "feature_stream_artifact", ArtifactRefCodec.toJson(result.featureStreamArtifact()));
        value.addProperty("feature_count", result.featureCount());
        value.addProperty("vertex_count", result.vertexCount());
        return value;
    }

    private static String executionIdentityDigest(
            ModelRunRequestCodec.ModelRunRequest request,
            ModelRunRequestCodec.ModelRunInput input,
            ModelExecutionPlanCodec.ModelExecutionPlan plan,
            int unitId,
            List<ModelRunSourceCodec.Binding> bindings) {
        JsonObject preimage = new JsonObject();
        preimage.addProperty("action_id", request.actionId());
        preimage.add("producer", InferenceIdentityCodec.toJson(request.producer()));
        preimage.add("expected_backend", InferenceIdentityCodec.toJson(request.expectedBackend()));
        request.expectedProfile()
                .ifPresent(
                        profile ->
                                preimage.add(
                                        "expected_profile",
                                        LargeRegionProfileCodec.toJson(profile)));
        preimage.add("input", ModelRunRequestCodec.toJson(input));
        preimage.add("execution_plan", ModelExecutionPlanCodec.toJson(plan));
        preimage.addProperty("unit_id", unitId);
        if (input.rasterInput().sourceView() instanceof SourceCaptureCodec.View) {
            String architecture = request.expectedBackend().architecture();
            if (plan instanceof ModelExecutionPlanCodec.CoreHaloPlan
                    && (architecture.equals("dinov3-adapter-cellpose")
                            || architecture.equals("dinov3-adapter-hovernet")
                            || architecture.equals("dinov3-adapter-semantic"))) {
                var sources = ModelRunSourceCodec.inputBindings(bindings, input.inputId());
                if (sources.size() != ModelExecutionPlanCodec.unitCount(plan)) {
                    throw new IllegalArgumentException(
                            "target normalization requires every input source binding");
                }
                preimage.add("source_bindings", SourceCaptureCodec.bindingsJson(sources));
                return CanonicalCodec.canonicalDigest(preimage);
            }
            var source =
                    ModelRunSourceCodec.unitBinding(bindings, input.inputId(), unitId)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "published unit has no accepted source binding"));
            preimage.add("source_binding", SourceCaptureCodec.toJson(source));
        }
        return CanonicalCodec.canonicalDigest(preimage);
    }

    private static void requireExecutionDigest(
            String actual,
            ModelRunRequestCodec.ModelRunRequest request,
            Map<Integer, ModelRunRequestCodec.ModelRunInput> inputs,
            ModelExecutionPlanCodec.ModelExecutionPlan plan,
            int inputId,
            int unitId,
            List<ModelRunSourceCodec.Binding> bindings) {
        String expected =
                executionIdentityDigest(request, inputs.get(inputId), plan, unitId, bindings);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "publication execution_identity_digest does not match its plan");
        }
    }

    private static ModelExecutionPlanCodec.ModelExecutionPlan requirePlanUnit(
            ModelExecutionPlanCodec.ModelExecutionPlan plan, int unitId) {
        if (plan == null) {
            throw new IllegalArgumentException(
                    "publication input_id is absent from model run request");
        }
        if (unitId < 0 || unitId >= ModelExecutionPlanCodec.unitCount(plan)) {
            throw new IllegalArgumentException("publication unit_id is absent from generated plan");
        }
        return plan;
    }

    private static void requireCoverage(Manifest manifest, Set<Integer> requestedInputIds) {
        Set<Integer> completedInputIds = new HashSet<>();
        for (InputResult result : manifest.completedInputs()) {
            completedInputIds.add(result.inputId());
        }
        for (UnitKey key : unitKeys(manifest.completedUnits())) {
            if (completedInputIds.contains(key.inputId())) {
                throw new IllegalArgumentException("completed_units cannot name a completed input");
            }
        }
        for (UnitKey key : unitKeys(manifest.checkpoints())) {
            if (completedInputIds.contains(key.inputId())) {
                throw new IllegalArgumentException("checkpoints cannot name a completed input");
            }
        }
        for (UnitKey key : unitKeys(manifest.failedUnits())) {
            if (completedInputIds.contains(key.inputId())) {
                throw new IllegalArgumentException("failed_units cannot name a completed input");
            }
        }
        Set<UnitKey> completed = new HashSet<>(unitKeys(manifest.completedUnits()));
        Set<UnitKey> failed = new HashSet<>(unitKeys(manifest.failedUnits()));
        completed.retainAll(failed);
        if (!completed.isEmpty()) {
            throw new IllegalArgumentException(
                    "one core/halo unit cannot be both completed and failed");
        }
        if (manifest.complete()) {
            if (!completedInputIds.equals(requestedInputIds)) {
                throw new IllegalArgumentException(
                        "complete manifest must cover every requested input");
            }
            if (!manifest.completedUnits().isEmpty()
                    || !manifest.checkpoints().isEmpty()
                    || !manifest.failedUnits().isEmpty()) {
                throw new IllegalArgumentException(
                        "complete manifest cannot retain unit state or failures");
            }
        } else {
            if (completedInputIds.equals(requestedInputIds)) {
                throw new IllegalArgumentException(
                        "manifest covering every requested input must be complete");
            }
            if (manifest.completedInputs().isEmpty()
                    && manifest.completedUnits().isEmpty()
                    && manifest.checkpoints().isEmpty()) {
                throw new IllegalArgumentException("partial manifest must preserve reusable state");
            }
        }
    }

    private static void requireBudgets(
            Manifest manifest,
            FiniteInferenceCodec.ClientResultBudget resultBudget,
            ExecutionLimitsCodec.ModelRunLimits limits) {
        long recoveryBytes = 0;
        for (UnitResult unit : manifest.completedUnits()) {
            long size = unit.reusableState().sizeBytes();
            if (size > limits.maxRecoveryStateBytesPerUnit()) {
                throw new IllegalArgumentException(
                        "manifest recovery state exceeds per-unit byte limit");
            }
            recoveryBytes = Math.addExact(recoveryBytes, size);
        }
        for (Checkpoint checkpoint : manifest.checkpoints()) {
            long size = checkpoint.state().sizeBytes();
            if (size > limits.maxRecoveryStateBytesPerUnit()) {
                throw new IllegalArgumentException(
                        "manifest recovery state exceeds per-unit byte limit");
            }
            recoveryBytes = Math.addExact(recoveryBytes, size);
        }
        if (recoveryBytes > limits.maxRecoveryStateBytesPerRun()) {
            throw new IllegalArgumentException("manifest recovery state exceeds run byte limit");
        }

        long transferred = 0;
        long features = 0;
        long vertices = 0;
        for (InputResult input : manifest.completedInputs()) {
            transferred = Math.addExact(transferred, input.featureStreamArtifact().sizeBytes());
            features = Math.addExact(features, input.featureCount());
            vertices = Math.addExact(vertices, input.vertexCount());
        }
        if (transferred > resultBudget.maxTransferredBytes()) {
            throw new IllegalArgumentException(
                    "manifest feature streams exceed max_transferred_bytes");
        }
        if (features > resultBudget.maxFeatures()) {
            throw new IllegalArgumentException("manifest completed inputs exceed max_features");
        }
        if (vertices > resultBudget.maxVertices()) {
            throw new IllegalArgumentException("manifest completed inputs exceed max_vertices");
        }
    }

    private static void requireFeatureStreamArtifact(ArtifactRefCodec.ArtifactRef artifact) {
        requireNonemptyArtifact(artifact, "feature_stream_artifact");
        if (!artifact.mediaType().equals(FEATURE_STREAM_MEDIA_TYPE)) {
            throw new IllegalArgumentException("feature stream artifact has the wrong media_type");
        }
        if (!artifact.contentEncoding()
                .equals(java.util.Optional.of(FEATURE_STREAM_CONTENT_ENCODING))) {
            throw new IllegalArgumentException(
                    "feature stream artifact must use gzip content encoding");
        }
    }

    private static void requireNonemptyArtifact(
            ArtifactRefCodec.ArtifactRef artifact, String path) {
        if (artifact.sizeBytes() == 0) {
            throw new IllegalArgumentException(path + " must name nonempty immutable bytes");
        }
    }

    private static void validateCanonicalOrder(List<?> values, String path) {
        List<UnitKey> keys = unitKeys(values);
        if (new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalArgumentException(path + " contains duplicate input/unit keys");
        }
        for (int index = 1; index < keys.size(); index++) {
            if (keys.get(index - 1).compareTo(keys.get(index)) >= 0) {
                throw new IllegalArgumentException(path + " must use canonical input/unit order");
            }
        }
    }

    private static void validateInputOrder(List<InputResult> values) {
        Integer previous = null;
        Set<Integer> seen = new HashSet<>();
        for (InputResult value : values) {
            if (!seen.add(value.inputId())) {
                throw new IllegalArgumentException("completed_inputs contains duplicate input_id");
            }
            if (previous != null && previous >= value.inputId()) {
                throw new IllegalArgumentException(
                        "completed_inputs must use canonical input order");
            }
            previous = value.inputId();
        }
    }

    private static List<UnitKey> unitKeys(List<?> values) {
        List<UnitKey> keys = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof UnitResult item) {
                keys.add(new UnitKey(item.inputId(), item.unitId()));
            } else if (value instanceof Checkpoint item) {
                keys.add(new UnitKey(item.inputId(), item.unitId()));
            } else if (value instanceof UnitFailure item) {
                keys.add(new UnitKey(item.inputId(), item.unitId()));
            } else {
                throw new IllegalArgumentException("unsupported unit publication");
            }
        }
        return keys;
    }

    private record UnitKey(int inputId, int unitId) implements Comparable<UnitKey> {
        @Override
        public int compareTo(UnitKey other) {
            int inputComparison = Integer.compare(inputId, other.inputId);
            return inputComparison != 0 ? inputComparison : Integer.compare(unitId, other.unitId);
        }
    }

    private static Map<Integer, ModelRunRequestCodec.ModelRunInput> inputMap(
            ModelRunRequestCodec.ModelRunRequest request) {
        Map<Integer, ModelRunRequestCodec.ModelRunInput> inputs = new HashMap<>();
        for (var input : request.inputs()) {
            inputs.put(input.inputId(), input);
        }
        return Map.copyOf(inputs);
    }

    private static <T> List<T> immutableList(List<T> values, String path) {
        if (values == null) {
            throw new NullPointerException(path);
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, path + " item"));
        }
        return List.copyOf(copy);
    }

    private static <T> List<T> parseArray(JsonObject object, String field, Parser<T> parser) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        List<T> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            result.add(parser.parse(item));
        }
        return result;
    }

    private static <T> JsonArray toJsonArray(List<T> values, Encoder<T> encoder) {
        JsonArray array = new JsonArray();
        values.forEach(value -> array.add(encoder.encode(value)));
        return array;
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        return requireExactObject(value, fields, Set.of(), path);
    }

    private static JsonObject requireExactObject(
            JsonElement value,
            Set<String> requiredFields,
            Set<String> optionalFields,
            String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        Set<String> allowed = new HashSet<>(requiredFields);
        allowed.addAll(optionalFields);
        if (!object.keySet().containsAll(requiredFields) || !allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static String requiredString(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }

    private static boolean requiredBoolean(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(path + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static int nonnegativeInt(JsonObject value, String field) {
        return CanonicalCodec.requireNonnegativeInt32(value.get(field), field);
    }

    private static void requireCurrentSchema(int value) {
        if (value != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported model run publication schema_version");
        }
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(JsonElement value);
    }

    @FunctionalInterface
    private interface Encoder<T> {
        JsonElement encode(T value);
    }
}
