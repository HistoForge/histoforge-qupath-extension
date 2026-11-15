package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.modules.ModuleContract;

/** Pure durable model-run request, plan-family, and acceptance contract. */
public final class ModelRunRequestCodec {

    public static final int SCHEMA_VERSION =
            GatewayCapabilitiesCodec.DURABLE_MODEL_RUN_SCHEMA_VERSION;

    private static final Set<String> INPUT_FIELDS =
            Set.of("input_id", "output_generation_id", "output", "raster_input");
    private static final Set<String> RESUME_FIELDS =
            Set.of("previous_run_id", "previous_manifest_digest");
    private static final Set<String> REQUEST_REQUIRED_FIELDS =
            Set.of(
                    "schema_version",
                    "action_id",
                    "model_run_id",
                    "producer",
                    "expected_backend",
                    "inputs",
                    "result_budget");
    private static final Set<String> REQUEST_OPTIONAL_FIELDS =
            Set.of("expected_profile", "resume_from", "halo_px_override");
    private static final Set<String> ACCEPTED_FIELDS =
            Set.of("schema_version", "action_id", "model_run_id", "request_digest");

    private ModelRunRequestCodec() {}

    /** One exact logical output and its target-raster request. */
    public record ModelRunInput(
            int inputId,
            String outputGenerationId,
            ExecutionScopeCodec.OutputKey output,
            RasterInputCodec.ModelRasterInput rasterInput) {
        public ModelRunInput {
            inputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            outputGenerationId =
                    CanonicalCodec.requireCanonicalUuid(outputGenerationId, "output_generation_id");
            output = Objects.requireNonNull(output, "output");
            rasterInput = Objects.requireNonNull(rasterInput, "raster_input");
            if (!ExecutionScopeCodec.regionOf(output.scope())
                    .equals(rasterInput.frame().region())) {
                throw new IllegalArgumentException(
                        "output scope region does not match raster input region");
            }
        }
    }

    /** Exact prior terminal manifest proposed by an explicit replacement run. */
    public record ModelRunResumeSource(String previousRunId, String previousManifestDigest) {
        public ModelRunResumeSource {
            previousRunId = CanonicalCodec.requireCanonicalUuid(previousRunId, "previous_run_id");
            previousManifestDigest =
                    CanonicalCodec.requireSha256(
                            previousManifestDigest, "previous_manifest_digest");
        }
    }

    /** Complete request for one exact backend model and homogeneous input family. */
    public record ModelRunRequest(
            int schemaVersion,
            String actionId,
            String modelRunId,
            InferenceIdentityCodec.ModelStepIdentity producer,
            InferenceIdentityCodec.BackendModelIdentity expectedBackend,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> expectedProfile,
            List<ModelRunInput> inputs,
            FiniteInferenceCodec.ClientResultBudget resultBudget,
            Optional<ModelRunResumeSource> resumeFrom,
            Optional<Integer> haloPxOverride) {
        public ModelRunRequest {
            requireCurrentSchema(schemaVersion);
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            modelRunId = CanonicalCodec.requireCanonicalUuid(modelRunId, "model_run_id");
            producer = Objects.requireNonNull(producer, "producer");
            expectedBackend = Objects.requireNonNull(expectedBackend, "expected_backend");
            expectedProfile = Objects.requireNonNull(expectedProfile, "expected_profile");
            haloPxOverride = Objects.requireNonNull(haloPxOverride, "halo_px_override");
            haloPxOverride.ifPresent(
                    value -> CanonicalCodec.requireNonnegativeInt32(value, "halo_px_override"));
            if (haloPxOverride.isPresent()
                    && expectedProfile
                            .filter(LargeRegionProfileCodec.CoreHaloProfile.class::isInstance)
                            .isEmpty()) {
                throw new IllegalArgumentException("halo_px_override requires a core-halo profile");
            }
            resultBudget = Objects.requireNonNull(resultBudget, "result_budget");
            resumeFrom = Objects.requireNonNull(resumeFrom, "resume_from");
            if (resumeFrom
                    .map(ModelRunResumeSource::previousRunId)
                    .filter(modelRunId::equals)
                    .isPresent()) {
                throw new IllegalArgumentException("replacement run must use a fresh model_run_id");
            }
            if (!producer.moduleRef().equals(expectedBackend.module().moduleRef())) {
                throw new IllegalArgumentException(
                        "producer module_ref does not match expected backend module");
            }
            if (!producer.stepId().equals(expectedBackend.modelId())) {
                throw new IllegalArgumentException(
                        "producer step_id does not match expected backend model_id");
            }
            inputs = normalizeInputs(inputs, producer, expectedBackend);
            if (expectedProfile
                            .filter(LargeRegionProfileCodec.GlobalDecodeProfile.class::isInstance)
                            .isPresent()
                    && inputs.stream()
                            .anyMatch(
                                    input ->
                                            input.rasterInput().sourceView()
                                                    instanceof SourceCaptureCodec.View)) {
                throw new IllegalArgumentException(
                        "global-decode does not support incremental image delivery");
            }
        }
    }

    /** Durable reservation acknowledgement echoing the canonical request digest. */
    public record ModelRunAccepted(
            int schemaVersion, String actionId, String modelRunId, String requestDigest) {
        public ModelRunAccepted {
            requireCurrentSchema(schemaVersion);
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            modelRunId = CanonicalCodec.requireCanonicalUuid(modelRunId, "model_run_id");
            requestDigest = CanonicalCodec.requireSha256(requestDigest, "request_digest");
        }
    }

    /** Parse one exact logical model-run input. */
    public static ModelRunInput parseInput(JsonElement value) {
        JsonObject object = requireExactObject(value, INPUT_FIELDS, "model run input");
        return new ModelRunInput(
                CanonicalCodec.requireNonnegativeInt32(object.get("input_id"), "input_id"),
                requiredString(object, "output_generation_id"),
                ExecutionScopeCodec.parseOutputKey(object.get("output")),
                RasterInputCodec.parseModelRasterInput(object.get("raster_input")));
    }

    /** Parse one exact prior manifest reuse proposal. */
    public static ModelRunResumeSource parseResumeSource(JsonElement value) {
        JsonObject object = requireExactObject(value, RESUME_FIELDS, "model run resume source");
        return new ModelRunResumeSource(
                requiredString(object, "previous_run_id"),
                requiredString(object, "previous_manifest_digest"));
    }

    /** Parse and context-free validate one complete model-run request. */
    public static ModelRunRequest parseRequest(JsonElement value) {
        JsonObject object = requiredObject(value, "model run request");
        requireFields(
                object, REQUEST_REQUIRED_FIELDS, REQUEST_OPTIONAL_FIELDS, "model run request");
        JsonElement inputsValue = object.get("inputs");
        if (inputsValue == null || !inputsValue.isJsonArray()) {
            throw new IllegalArgumentException("inputs must be a nonempty array");
        }
        List<ModelRunInput> inputs = new ArrayList<>();
        for (JsonElement item : inputsValue.getAsJsonArray()) {
            inputs.add(parseInput(item));
        }
        return new ModelRunRequest(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "action_id"),
                requiredString(object, "model_run_id"),
                InferenceIdentityCodec.parseModelStepIdentity(object.get("producer")),
                InferenceIdentityCodec.parseBackendModelIdentity(object.get("expected_backend")),
                object.has("expected_profile")
                        ? Optional.of(LargeRegionProfileCodec.parse(object.get("expected_profile")))
                        : Optional.empty(),
                inputs,
                FiniteInferenceCodec.parseResultBudget(object.get("result_budget")),
                object.has("resume_from")
                        ? Optional.of(parseResumeSource(object.get("resume_from")))
                        : Optional.empty(),
                object.has("halo_px_override")
                        ? Optional.of(
                                CanonicalCodec.requireNonnegativeInt32(
                                        object.get("halo_px_override"), "halo_px_override"))
                        : Optional.empty());
    }

    /** Bind a request to the exact canonical module/model route receiving it. */
    public static ModelRunRequest validateRouteRequest(
            JsonElement value, String routeModuleRef, String routeModelId) {
        ModelRunRequest request = parseRequest(value);
        ModuleRef moduleRef =
                ModuleRef.parse(routeModuleRef)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "route module_ref must be canonical owner/name"));
        String modelId = ModuleContract.requireModelId(routeModelId, "route_model_id");
        if (!request.producer().moduleRef().equals(moduleRef)) {
            throw new IllegalArgumentException("request producer does not match route module_ref");
        }
        if (!request.producer().stepId().equals(modelId)) {
            throw new IllegalArgumentException("request producer does not match route model_id");
        }
        return request;
    }

    /** Validate every codec-owned request fence before durable admission. */
    public static ModelRunRequest validateAdmission(
            JsonElement value,
            String routeModuleRef,
            String routeModelId,
            InferenceIdentityCodec.BackendModelIdentity actualBackend,
            ExecutionLimitsCodec.TargetRasterLimits finiteLimits,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> advertisedProfile,
            ExecutionLimitsCodec.ModelRunLimits modelRunLimits) {
        ModelRunRequest request = validateRouteRequest(value, routeModuleRef, routeModelId);
        if (!request.expectedBackend()
                .equals(Objects.requireNonNull(actualBackend, "actual_backend"))) {
            throw new IllegalArgumentException(
                    "expected_backend does not match the deployed backend identity");
        }
        validatePlanFamily(request, finiteLimits, advertisedProfile);
        return validateResourceLimits(request, modelRunLimits);
    }

    /** Bind optional profile presence to the exact finite-raster selection predicate. */
    public static ModelRunRequest validatePlanFamily(
            ModelRunRequest request,
            ExecutionLimitsCodec.TargetRasterLimits finiteLimits,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> advertisedProfile) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(finiteLimits, "finite_limits");
        Objects.requireNonNull(advertisedProfile, "advertised_profile");
        boolean anyFits = false;
        boolean allFit = true;
        for (ModelRunInput input : request.inputs()) {
            boolean fits =
                    ExecutionLimitsCodec.fitsTargetRaster(
                            input.rasterInput().frame().transform().raster(), finiteLimits);
            anyFits |= fits;
            allFit &= fits;
        }
        if (anyFits && !allFit) {
            throw new IllegalArgumentException(
                    "model run inputs must use one homogeneous plan family");
        }
        if (allFit) {
            if (request.expectedProfile().isPresent()) {
                throw new IllegalArgumentException(
                        "finite-plan request must omit expected_profile");
            }
            return request;
        }
        if (request.expectedProfile().isEmpty()) {
            throw new IllegalArgumentException("oversized request must carry expected_profile");
        }
        if (advertisedProfile.isEmpty()) {
            throw new IllegalArgumentException("runner does not advertise a large-region profile");
        }
        if (!request.expectedProfile().get().equals(advertisedProfile.get())) {
            throw new IllegalArgumentException(
                    "expected_profile does not match the advertised profile");
        }
        return request;
    }

    /** Enforce durable-run caps that admit but never select the plan family. */
    public static ModelRunRequest validateResourceLimits(
            ModelRunRequest request, ExecutionLimitsCodec.ModelRunLimits limits) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(limits, "model_run_limits");
        if (CanonicalCodec.canonicalBytes(toJson(request)).length
                > limits.maxRequestCanonicalBytes()) {
            throw new IllegalArgumentException(
                    "model run request exceeds max_request_canonical_bytes");
        }
        if (request.inputs().size() > limits.maxInputs()) {
            throw new IllegalArgumentException("model run request exceeds max_inputs");
        }

        long aggregatePixels = 0;
        long aggregateMaskVertices = 0;
        long aggregateUnits = 0;
        for (ModelRunInput input : request.inputs()) {
            var raster = input.rasterInput().frame().transform().raster();
            if (!ExecutionLimitsCodec.fitsTargetRaster(raster, limits.targetRasterPerInput())) {
                throw new IllegalArgumentException(
                        "model run input exceeds target_raster_per_input");
            }
            aggregatePixels =
                    Math.addExact(
                            aggregatePixels,
                            Math.multiplyExact((long) raster.width(), raster.height()));
            aggregateMaskVertices =
                    Math.addExact(
                            aggregateMaskVertices,
                            RasterInputCodec.geometryVertexCount(
                                    input.rasterInput().frame().inclusionMaskRaster()));
            aggregateUnits =
                    Math.addExact(
                            aggregateUnits,
                            ModelExecutionPlanCodec.unitCount(request.expectedProfile(), raster));
        }
        if (aggregatePixels > limits.maxAggregateTargetRasterPixels()) {
            throw new IllegalArgumentException(
                    "model run request exceeds aggregate target-raster pixels");
        }
        if (aggregateMaskVertices > limits.maxAggregateInclusionMaskVertices()) {
            throw new IllegalArgumentException(
                    "model run request exceeds aggregate inclusion-mask vertices");
        }
        if (aggregateUnits > limits.maxUnits()) {
            throw new IllegalArgumentException("model run request exceeds max_units");
        }
        var requested = request.resultBudget();
        var result = limits.result();
        if (requested.maxTransferredBytes() > result.maxTransferredBytes()
                || requested.maxDecodedBytes() > result.maxDecodedBytes()
                || requested.maxRecordBytes() > result.maxRecordBytes()
                || requested.maxFeatures() > result.maxFeatures()
                || requested.maxVertices() > result.maxVertices()) {
            throw new IllegalArgumentException(
                    "model run result_budget exceeds runner result limits");
        }
        // The admitted unit-count limit bounds this traversal before request submission.
        for (ModelRunInput input : request.inputs()) {
            ModelExecutionPlanCodec.validateProcessingRasterLimits(
                    request.expectedProfile(),
                    input.rasterInput().frame().transform().raster(),
                    request.haloPxOverride(),
                    limits.processingRaster());
        }
        return request;
    }

    /** Parse one durable reservation acknowledgement. */
    public static ModelRunAccepted parseAccepted(JsonElement value) {
        JsonObject object = requireExactObject(value, ACCEPTED_FIELDS, "model run accepted");
        return new ModelRunAccepted(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "action_id"),
                requiredString(object, "model_run_id"),
                requiredString(object, "request_digest"));
    }

    /** Require one acknowledgement to echo and digest the submitted request. */
    public static ModelRunAccepted validateAcceptance(ModelRunRequest request, JsonElement value) {
        ModelRunAccepted accepted = parseAccepted(value);
        String digest = requestDigest(request);
        if (!accepted.actionId().equals(request.actionId())
                || !accepted.modelRunId().equals(request.modelRunId())
                || !accepted.requestDigest().equals(digest)) {
            throw new IllegalArgumentException(
                    "accepted identity does not match model run request");
        }
        return accepted;
    }

    /** Return the idempotency digest of one exact accepted request. */
    public static String requestDigest(ModelRunRequest request) {
        return CanonicalCodec.canonicalDigest(toJson(request));
    }

    /**
     * Return the exact scientific request plan retained across an explicit replacement run.
     *
     * <p>The fresh model-run identity and optional prior-manifest reuse proposal are operational
     * lifecycle fields. Every other request field, including input assignments and budgets, is part
     * of the immutable replacement plan.
     */
    public static byte[] replacementPlanBytes(ModelRunRequest request) {
        JsonObject projection = toJson(Objects.requireNonNull(request, "request"));
        projection.remove("model_run_id");
        projection.remove("resume_from");
        return CanonicalCodec.canonicalBytes(projection);
    }

    /** Encode one complete request using exact lower-snake fields. */
    public static JsonObject toJson(ModelRunRequest request) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", request.schemaVersion());
        value.addProperty("action_id", request.actionId());
        value.addProperty("model_run_id", request.modelRunId());
        value.add("producer", InferenceIdentityCodec.toJson(request.producer()));
        value.add("expected_backend", InferenceIdentityCodec.toJson(request.expectedBackend()));
        request.expectedProfile()
                .ifPresent(
                        profile ->
                                value.add(
                                        "expected_profile",
                                        LargeRegionProfileCodec.toJson(profile)));
        JsonArray inputs = new JsonArray();
        request.inputs().forEach(input -> inputs.add(toJson(input)));
        value.add("inputs", inputs);
        value.add("result_budget", FiniteInferenceCodec.toJson(request.resultBudget()));
        request.resumeFrom().ifPresent(source -> value.add("resume_from", toJson(source)));
        request.haloPxOverride().ifPresent(halo -> value.addProperty("halo_px_override", halo));
        return value;
    }

    /** Encode one durable reservation acknowledgement. */
    public static JsonObject toJson(ModelRunAccepted accepted) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", accepted.schemaVersion());
        value.addProperty("action_id", accepted.actionId());
        value.addProperty("model_run_id", accepted.modelRunId());
        value.addProperty("request_digest", accepted.requestDigest());
        return value;
    }

    /** Encode one shared durable logical input using exact lower-snake fields. */
    public static JsonObject toJson(ModelRunInput input) {
        JsonObject value = new JsonObject();
        value.addProperty("input_id", input.inputId());
        value.addProperty("output_generation_id", input.outputGenerationId());
        value.add("output", ExecutionScopeCodec.toJson(input.output()));
        value.add("raster_input", RasterInputCodec.toJson(input.rasterInput()));
        return value;
    }

    private static JsonObject toJson(ModelRunResumeSource source) {
        JsonObject value = new JsonObject();
        value.addProperty("previous_run_id", source.previousRunId());
        value.addProperty("previous_manifest_digest", source.previousManifestDigest());
        return value;
    }

    private static List<ModelRunInput> normalizeInputs(
            List<ModelRunInput> values,
            InferenceIdentityCodec.ModelStepIdentity producer,
            InferenceIdentityCodec.BackendModelIdentity backend) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("inputs must be a nonempty array");
        }
        List<ModelRunInput> inputs = List.copyOf(values);
        Set<Integer> inputIds = new HashSet<>();
        Set<String> generations = new HashSet<>();
        Set<ExecutionScopeCodec.OutputKey> outputs = new HashSet<>();
        Integer previousInputId = null;
        for (ModelRunInput inputValue : inputs) {
            ModelRunInput input = Objects.requireNonNull(inputValue, "input");
            if (!inputIds.add(input.inputId())) {
                throw new IllegalArgumentException("inputs contain duplicate input_id");
            }
            if (previousInputId != null && input.inputId() < previousInputId) {
                throw new IllegalArgumentException(
                        "inputs must use strictly increasing input_id order");
            }
            previousInputId = input.inputId();
            if (!input.output().producer().equals(producer)) {
                throw new IllegalArgumentException(
                        "input output producer does not match request producer");
            }
            if (!input.rasterInput().rasterizer().equals(backend.rasterizer())) {
                throw new IllegalArgumentException(
                        "input rasterizer does not match expected backend rasterizer");
            }
            if (!generations.add(input.outputGenerationId())) {
                throw new IllegalArgumentException("inputs contain duplicate output_generation_id");
            }
            if (!outputs.add(input.output())) {
                throw new IllegalArgumentException("inputs contain duplicate output");
            }
        }
        return inputs;
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static void requireFields(
            JsonObject value, Set<String> required, Set<String> optional, String path) {
        if (!value.keySet().containsAll(required)
                || !union(required, optional).containsAll(value.keySet())) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return result;
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
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

    private static void requireCurrentSchema(int value) {
        if (value != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported model run schema_version");
        }
    }
}
