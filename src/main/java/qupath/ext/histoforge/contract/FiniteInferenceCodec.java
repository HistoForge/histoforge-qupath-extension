package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.modules.ModuleContract;

/** Pure finite-inference request and response contracts. */
public final class FiniteInferenceCodec {

    public static final int SCHEMA_VERSION =
            GatewayCapabilitiesCodec.FINITE_INFERENCE_SCHEMA_VERSION;

    private static final Set<String> RESULT_BUDGET_FIELDS =
            Set.of(
                    "max_transferred_bytes",
                    "max_decoded_bytes",
                    "max_record_bytes",
                    "max_features",
                    "max_vertices");
    private static final Set<String> BACKEND_INPUT_FIELDS =
            Set.of(
                    "source_artifact_id",
                    "source_manifest_digest",
                    "source_view_digest",
                    "model_raster_input_digest");
    private static final Set<String> REQUEST_FIELDS =
            Set.of(
                    "schema_version",
                    "action_id",
                    "output_generation_id",
                    "input_id",
                    "producer",
                    "expected_backend",
                    "raster_input",
                    "result_budget");
    private static final Set<String> ACCEPTED_FIELDS =
            Set.of("schema_version", "action_id", "output_generation_id", "backend_call_id");
    private static final Set<String> CALL_STATUS_FIELDS =
            Set.of("schema_version", "backend_call_id");
    private static final Set<String> RESULT_META_FIELDS =
            Set.of(
                    "schema_version",
                    "action_id",
                    "output_generation_id",
                    "backend_call_id",
                    "input_id",
                    "producer",
                    "backend",
                    "input_identity",
                    "coordinate_space");
    private static final Set<String> RESULT_FIELDS = Set.of("meta", "geojson");

    private FiniteInferenceCodec() {}

    /** Positive client acceptance ceilings for one complete finite result. */
    public record ClientResultBudget(
            long maxTransferredBytes,
            long maxDecodedBytes,
            long maxRecordBytes,
            long maxFeatures,
            long maxVertices) {
        public ClientResultBudget {
            maxTransferredBytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxTransferredBytes, "max_transferred_bytes");
            maxDecodedBytes =
                    CanonicalCodec.requirePositiveSafeInteger(maxDecodedBytes, "max_decoded_bytes");
            maxRecordBytes =
                    CanonicalCodec.requirePositiveSafeInteger(maxRecordBytes, "max_record_bytes");
            maxFeatures = CanonicalCodec.requirePositiveSafeInteger(maxFeatures, "max_features");
            maxVertices = CanonicalCodec.requirePositiveSafeInteger(maxVertices, "max_vertices");
        }
    }

    /** Exact input evidence returned by either inference protocol. */
    public sealed interface BackendInputIdentity
            permits SealedInputIdentity, CapturedInputIdentity {
        String sourceViewDigest();

        String modelRasterInputDigest();

        default List<SourceCaptureCodec.UnitBinding> sourceBindings() {
            return List.of();
        }
    }

    /** A source view wholly supplied by one already-sealed artifact. */
    public record SealedInputIdentity(
            String sourceArtifactId,
            String sourceManifestDigest,
            String sourceViewDigest,
            String modelRasterInputDigest)
            implements BackendInputIdentity {
        public SealedInputIdentity {
            sourceArtifactId =
                    CanonicalCodec.requireCanonicalUuid(sourceArtifactId, "source_artifact_id");
            sourceManifestDigest =
                    CanonicalCodec.requireSha256(sourceManifestDigest, "source_manifest_digest");
            sourceViewDigest = CanonicalCodec.requireSha256(sourceViewDigest, "source_view_digest");
            modelRasterInputDigest =
                    CanonicalCodec.requireSha256(
                            modelRasterInputDigest, "model_raster_input_digest");
        }
    }

    /** Complete ordered image contributions, retained once per logical model input. */
    public record CapturedInputIdentity(
            String sourceViewDigest,
            String modelRasterInputDigest,
            List<SourceCaptureCodec.UnitBinding> sourceBindings)
            implements BackendInputIdentity {
        public CapturedInputIdentity {
            sourceViewDigest = CanonicalCodec.requireSha256(sourceViewDigest, "source_view_digest");
            modelRasterInputDigest =
                    CanonicalCodec.requireSha256(
                            modelRasterInputDigest, "model_raster_input_digest");
            sourceBindings = SourceCaptureCodec.requireCompleteBindings(sourceBindings);
        }
    }

    /** Complete finite request referring to already sealed immutable image bytes. */
    public record FiniteInferenceRequest(
            int schemaVersion,
            String actionId,
            String outputGenerationId,
            int inputId,
            InferenceIdentityCodec.ModelStepIdentity producer,
            InferenceIdentityCodec.BackendModelIdentity expectedBackend,
            RasterInputCodec.ModelRasterInput rasterInput,
            ClientResultBudget resultBudget,
            Optional<SourceCaptureCodec.Binding> sourceBinding) {
        public FiniteInferenceRequest(
                int schemaVersion,
                String actionId,
                String outputGenerationId,
                int inputId,
                InferenceIdentityCodec.ModelStepIdentity producer,
                InferenceIdentityCodec.BackendModelIdentity expectedBackend,
                RasterInputCodec.ModelRasterInput rasterInput,
                ClientResultBudget resultBudget) {
            this(
                    schemaVersion,
                    actionId,
                    outputGenerationId,
                    inputId,
                    producer,
                    expectedBackend,
                    rasterInput,
                    resultBudget,
                    Optional.empty());
        }

        public FiniteInferenceRequest {
            requireCurrentSchema(schemaVersion);
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            outputGenerationId =
                    CanonicalCodec.requireCanonicalUuid(outputGenerationId, "output_generation_id");
            inputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            producer = Objects.requireNonNull(producer, "producer");
            expectedBackend = Objects.requireNonNull(expectedBackend, "expected_backend");
            rasterInput = Objects.requireNonNull(rasterInput, "raster_input");
            resultBudget = Objects.requireNonNull(resultBudget, "result_budget");
            sourceBinding = Objects.requireNonNull(sourceBinding, "source_binding");
            boolean captured = rasterInput.sourceView() instanceof SourceCaptureCodec.View;
            if (captured != sourceBinding.isPresent()) {
                throw new IllegalArgumentException(
                        "captured finite inputs require exactly one sealed source_binding");
            }
            if (rasterInput.sourceView() instanceof SourceCaptureCodec.View view) {
                sourceBinding.orElseThrow().requireCapture(view.capture());
            }
            if (!producer.moduleRef().equals(expectedBackend.module().moduleRef())) {
                throw new IllegalArgumentException(
                        "producer module_ref does not match expected backend module");
            }
            if (!producer.stepId().equals(expectedBackend.modelId())) {
                throw new IllegalArgumentException(
                        "producer step_id does not match expected backend model_id");
            }
            if (!expectedBackend.rasterizer().equals(rasterInput.rasterizer())) {
                throw new IllegalArgumentException(
                        "expected backend rasterizer does not match raster input");
            }
            if (!(rasterInput.frame().region() instanceof ExecutionScopeCodec.Roi)) {
                throw new IllegalArgumentException("finite request region must be roi");
            }
        }
    }

    /** Accepted action/output echo plus server-assigned finite call ID. */
    public record InferenceAccepted(
            int schemaVersion, String actionId, String outputGenerationId, String backendCallId) {
        public InferenceAccepted {
            requireCurrentSchema(schemaVersion);
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            outputGenerationId =
                    CanonicalCodec.requireCanonicalUuid(outputGenerationId, "output_generation_id");
            backendCallId =
                    BackendExecutionCodec.requireBackendCallId(backendCallId, "backend_call_id");
        }
    }

    /** Nonterminal poll response for one server-assigned call. */
    public record InferencePending(int schemaVersion, String backendCallId) {
        public InferencePending {
            requireCurrentSchema(schemaVersion);
            backendCallId =
                    BackendExecutionCodec.requireBackendCallId(backendCallId, "backend_call_id");
        }
    }

    /** Idempotent best-effort cancellation acknowledgement. */
    public record InferenceCancellationAccepted(int schemaVersion, String backendCallId) {
        public InferenceCancellationAccepted {
            requireCurrentSchema(schemaVersion);
            backendCallId =
                    BackendExecutionCodec.requireBackendCallId(backendCallId, "backend_call_id");
        }
    }

    /** Exact request and call identity echoed before any feature may be accepted. */
    public record BackendResultMeta(
            int schemaVersion,
            String actionId,
            String outputGenerationId,
            String backendCallId,
            int inputId,
            InferenceIdentityCodec.ModelStepIdentity producer,
            InferenceIdentityCodec.BackendModelIdentity backend,
            BackendInputIdentity inputIdentity,
            CoordinateSpace coordinateSpace) {
        public BackendResultMeta {
            requireCurrentSchema(schemaVersion);
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            outputGenerationId =
                    CanonicalCodec.requireCanonicalUuid(outputGenerationId, "output_generation_id");
            backendCallId =
                    BackendExecutionCodec.requireBackendCallId(backendCallId, "backend_call_id");
            inputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            producer = Objects.requireNonNull(producer, "producer");
            backend = Objects.requireNonNull(backend, "backend");
            inputIdentity = Objects.requireNonNull(inputIdentity, "input_identity");
            coordinateSpace = Objects.requireNonNull(coordinateSpace, "coordinate_space");
        }
    }

    /** Closed coordinate system for all finite result geometries. */
    public enum CoordinateSpace {
        RASTER_LOCAL
    }

    /** Complete validated metadata and deterministic feature collection. */
    public record InferenceResult(
            BackendResultMeta meta, InferenceFeatureCodec.FeatureCollection geojson) {
        public InferenceResult {
            meta = Objects.requireNonNull(meta, "meta");
            geojson = Objects.requireNonNull(geojson, "geojson");
        }
    }

    /** Parse one complete finite request and validate its internal identities. */
    public static FiniteInferenceRequest parseRequest(JsonElement value) {
        JsonObject object = requiredObject(value, "finite request");
        var fields = new HashSet<>(REQUEST_FIELDS);
        if (object.has("source_binding")) fields.add("source_binding");
        requireExactObject(object, fields, "finite request");
        return new FiniteInferenceRequest(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "action_id"),
                requiredString(object, "output_generation_id"),
                CanonicalCodec.requireNonnegativeInt32(object.get("input_id"), "input_id"),
                InferenceIdentityCodec.parseModelStepIdentity(object.get("producer")),
                InferenceIdentityCodec.parseBackendModelIdentity(object.get("expected_backend")),
                RasterInputCodec.parseModelRasterInput(object.get("raster_input")),
                parseResultBudget(object.get("result_budget")),
                object.has("source_binding")
                        ? Optional.of(SourceCaptureCodec.parseBinding(object.get("source_binding")))
                        : Optional.empty());
    }

    /** Bind a request to the exact canonical module/model route that received it. */
    public static FiniteInferenceRequest validateRouteRequest(
            JsonElement value, ModuleRef routeModuleRef, String routeModelId) {
        FiniteInferenceRequest request = parseRequest(value);
        ModuleRef moduleRef = Objects.requireNonNull(routeModuleRef, "route_module_ref");
        String modelId = ModuleContract.requireModelId(routeModelId, "route_model_id");
        if (!request.producer().moduleRef().equals(moduleRef)) {
            throw new IllegalArgumentException("request producer does not match route module_ref");
        }
        if (!request.producer().stepId().equals(modelId)) {
            throw new IllegalArgumentException("request producer does not match route model_id");
        }
        return request;
    }

    /** Parse one client result budget. */
    public static ClientResultBudget parseResultBudget(JsonElement value) {
        JsonObject object = requireExactObject(value, RESULT_BUDGET_FIELDS, "result budget");
        return new ClientResultBudget(
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_transferred_bytes"), "max_transferred_bytes"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_decoded_bytes"), "max_decoded_bytes"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_record_bytes"), "max_record_bytes"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_features"), "max_features"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_vertices"), "max_vertices"));
    }

    /** Parse one compact source/raster identity echo. */
    public static BackendInputIdentity parseBackendInputIdentity(JsonElement value) {
        JsonObject fields = requiredObject(value, "backend input identity");
        if (fields.has("source_bindings")) {
            requireExactObject(
                    fields,
                    Set.of("source_view_digest", "model_raster_input_digest", "source_bindings"),
                    "backend input identity");
            return new CapturedInputIdentity(
                    requiredString(fields, "source_view_digest"),
                    requiredString(fields, "model_raster_input_digest"),
                    SourceCaptureCodec.parseCompleteBindings(fields.get("source_bindings")));
        }
        JsonObject object =
                requireExactObject(value, BACKEND_INPUT_FIELDS, "backend input identity");
        return new SealedInputIdentity(
                requiredString(object, "source_artifact_id"),
                requiredString(object, "source_manifest_digest"),
                requiredString(object, "source_view_digest"),
                requiredString(object, "model_raster_input_digest"));
    }

    /** Derive the only compact backend echo for one exact model-raster input. */
    public static BackendInputIdentity buildBackendInputIdentity(
            RasterInputCodec.ModelRasterInput input) {
        return buildBackendInputIdentity(input, List.of());
    }

    public static BackendInputIdentity buildBackendInputIdentity(
            RasterInputCodec.ModelRasterInput input,
            List<SourceCaptureCodec.UnitBinding> bindings) {
        if (input.sourceView() instanceof SourceCaptureCodec.View captured) {
            for (var binding : bindings) binding.source().requireCapture(captured.capture());
            return new CapturedInputIdentity(captured.viewDigest(), input.inputDigest(), bindings);
        }
        if (!bindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "sealed-file input identity cannot carry source bindings");
        }
        var view = (RasterInputCodec.SourceView) input.sourceView();
        return new SealedInputIdentity(
                view.artifact().artifactId(),
                view.artifact().manifestDigest(),
                input.sourceView().viewDigest(),
                input.inputDigest());
    }

    public static BackendInputIdentity inputIdentity(FiniteInferenceRequest request) {
        return buildBackendInputIdentity(
                request.rasterInput(),
                request.sourceBinding()
                        .map(binding -> List.of(new SourceCaptureCodec.UnitBinding(0, binding)))
                        .orElseGet(List::of));
    }

    public static SourceArtifactPublicationCodec.SourceArtifactRef sourceArtifact(
            FiniteInferenceRequest request) {
        return request.sourceBinding()
                .map(SourceCaptureCodec.Binding::artifact)
                .orElseGet(
                        () ->
                                ((RasterInputCodec.SourceView) request.rasterInput().sourceView())
                                        .artifact());
    }

    /** Parse one finite submission acknowledgement. */
    public static InferenceAccepted parseAccepted(JsonElement value) {
        JsonObject object = requireExactObject(value, ACCEPTED_FIELDS, "inference accepted");
        return new InferenceAccepted(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "action_id"),
                requiredString(object, "output_generation_id"),
                requiredString(object, "backend_call_id"));
    }

    /** Require an acceptance to echo the submitted action and output generation. */
    public static InferenceAccepted validateAcceptance(
            FiniteInferenceRequest request, JsonElement value) {
        InferenceAccepted accepted = parseAccepted(value);
        if (!accepted.actionId().equals(request.actionId())) {
            throw new IllegalArgumentException("accepted action_id does not match finite request");
        }
        if (!accepted.outputGenerationId().equals(request.outputGenerationId())) {
            throw new IllegalArgumentException(
                    "accepted output_generation_id does not match finite request");
        }
        return accepted;
    }

    /** Parse one nonterminal poll response. */
    public static InferencePending parsePending(JsonElement value) {
        JsonObject object = requireExactObject(value, CALL_STATUS_FIELDS, "inference pending");
        return new InferencePending(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "backend_call_id"));
    }

    /** Bind a pending response to the call ID addressed by the poll. */
    public static InferencePending validatePending(
            JsonElement value, String addressedBackendCallId) {
        InferencePending pending = parsePending(value);
        requireAddressedCall(pending.backendCallId(), addressedBackendCallId);
        return pending;
    }

    /** Parse one best-effort cancellation acknowledgement. */
    public static InferenceCancellationAccepted parseCancellationAccepted(JsonElement value) {
        JsonObject object = requireExactObject(value, CALL_STATUS_FIELDS, "inference cancellation");
        return new InferenceCancellationAccepted(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "backend_call_id"));
    }

    /** Bind a cancellation acknowledgement to the call ID addressed by DELETE. */
    public static InferenceCancellationAccepted validateCancellationAcceptance(
            JsonElement value, String addressedBackendCallId) {
        InferenceCancellationAccepted accepted = parseCancellationAccepted(value);
        requireAddressedCall(accepted.backendCallId(), addressedBackendCallId);
        return accepted;
    }

    /** Parse result metadata without a retained request or addressed call. */
    public static BackendResultMeta parseResultMeta(JsonElement value) {
        JsonObject object = requireExactObject(value, RESULT_META_FIELDS, "backend result meta");
        return new BackendResultMeta(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "action_id"),
                requiredString(object, "output_generation_id"),
                requiredString(object, "backend_call_id"),
                CanonicalCodec.requireNonnegativeInt32(object.get("input_id"), "input_id"),
                InferenceIdentityCodec.parseModelStepIdentity(object.get("producer")),
                InferenceIdentityCodec.parseBackendModelIdentity(object.get("backend")),
                parseBackendInputIdentity(object.get("input_identity")),
                exactEnum(
                        object.get("coordinate_space"), CoordinateSpace.class, "coordinate_space"));
    }

    /**
     * Bind a parsed deterministic result to its request and addressed call.
     *
     * <p>The transport owns transferred/decoded byte counting while reading. This boundary rechecks
     * record, feature, and vertex ceilings before candidates can be used.
     */
    public static InferenceResult validateResult(
            JsonElement value,
            FiniteInferenceRequest request,
            String addressedCallId,
            Set<String> declaredClasses) {
        return validateResult(
                value,
                request,
                addressedCallId,
                declaredClasses,
                InferenceFeatureCodec.GeometryKind.POLYGONAL);
    }

    public static InferenceResult validateResult(
            JsonElement value,
            FiniteInferenceRequest request,
            String addressedCallId,
            Set<String> declaredClasses,
            InferenceFeatureCodec.GeometryKind geometryKind) {
        JsonObject object = requireExactObject(value, RESULT_FIELDS, "inference result");
        String callId =
                BackendExecutionCodec.requireBackendCallId(addressedCallId, "backend_call_id");
        BackendResultMeta meta = parseResultMeta(object.get("meta"));
        BackendInputIdentity inputIdentity = inputIdentity(request);
        if (!meta.actionId().equals(request.actionId())
                || !meta.outputGenerationId().equals(request.outputGenerationId())
                || !meta.backendCallId().equals(callId)
                || meta.inputId() != request.inputId()
                || !meta.producer().equals(request.producer())
                || !meta.backend().equals(request.expectedBackend())
                || !meta.inputIdentity().equals(inputIdentity)) {
            throw new IllegalArgumentException(
                    "result metadata does not match submitted request and addressed call");
        }
        InferenceFeatureCodec.FeatureCollection features =
                InferenceFeatureCodec.validate(
                        requiredObject(object.get("geojson"), "geojson"),
                        declaredClasses,
                        geometryKind);
        if (features.features().size() > request.resultBudget().maxFeatures()) {
            throw new IllegalArgumentException("inference result exceeds max_features");
        }
        long vertexCount = 0;
        RasterInputCodec.RasterDescriptor raster =
                request.rasterInput().frame().transform().raster();
        for (InferenceFeatureCodec.Feature feature : features.features()) {
            RasterInputCodec.requireGeometryInsideRaster(
                    feature.geometry(), raster, "inference feature geometry");
            if (InferenceFeatureCodec.canonicalRecordBytes(feature)
                    > request.resultBudget().maxRecordBytes()) {
                throw new IllegalArgumentException("inference feature exceeds max_record_bytes");
            }
            vertexCount = Math.addExact(vertexCount, InferenceFeatureCodec.vertexCount(feature));
            if (vertexCount > request.resultBudget().maxVertices()) {
                throw new IllegalArgumentException("inference result exceeds max_vertices");
            }
        }
        return new InferenceResult(meta, features);
    }

    /** Encode one request using exact lower-snake fields. */
    public static JsonObject toJson(FiniteInferenceRequest request) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", request.schemaVersion());
        value.addProperty("action_id", request.actionId());
        value.addProperty("output_generation_id", request.outputGenerationId());
        value.addProperty("input_id", request.inputId());
        value.add("producer", InferenceIdentityCodec.toJson(request.producer()));
        value.add("expected_backend", InferenceIdentityCodec.toJson(request.expectedBackend()));
        value.add("raster_input", RasterInputCodec.toJson(request.rasterInput()));
        value.add("result_budget", toJson(request.resultBudget()));
        request.sourceBinding()
                .ifPresent(
                        binding -> value.add("source_binding", SourceCaptureCodec.toJson(binding)));
        return value;
    }

    /** Encode one accepted response. */
    public static JsonObject toJson(InferenceAccepted accepted) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", accepted.schemaVersion());
        value.addProperty("action_id", accepted.actionId());
        value.addProperty("output_generation_id", accepted.outputGenerationId());
        value.addProperty("backend_call_id", accepted.backendCallId());
        return value;
    }

    /** Encode one pending response. */
    public static JsonObject toJson(InferencePending pending) {
        return callStatusJson(pending.schemaVersion(), pending.backendCallId());
    }

    /** Encode one cancellation acknowledgement. */
    public static JsonObject toJson(InferenceCancellationAccepted accepted) {
        return callStatusJson(accepted.schemaVersion(), accepted.backendCallId());
    }

    /** Encode one complete validated inference result. */
    public static JsonObject toJson(InferenceResult result) {
        JsonObject value = new JsonObject();
        value.add("meta", toJson(result.meta()));
        value.add("geojson", InferenceFeatureCodec.toJson(result.geojson()));
        return value;
    }

    /** Encode one shared finite or aggregate durable-run result budget. */
    public static JsonObject toJson(ClientResultBudget budget) {
        JsonObject value = new JsonObject();
        value.addProperty("max_transferred_bytes", budget.maxTransferredBytes());
        value.addProperty("max_decoded_bytes", budget.maxDecodedBytes());
        value.addProperty("max_record_bytes", budget.maxRecordBytes());
        value.addProperty("max_features", budget.maxFeatures());
        value.addProperty("max_vertices", budget.maxVertices());
        return value;
    }

    /** Encode one shared finite/durable backend input identity. */
    public static JsonObject toJson(BackendInputIdentity identity) {
        JsonObject value = new JsonObject();
        if (identity instanceof SealedInputIdentity sealed) {
            value.addProperty("source_artifact_id", sealed.sourceArtifactId());
            value.addProperty("source_manifest_digest", sealed.sourceManifestDigest());
        } else if (identity instanceof CapturedInputIdentity captured) {
            value.add(
                    "source_bindings", SourceCaptureCodec.bindingsJson(captured.sourceBindings()));
        }
        value.addProperty("source_view_digest", identity.sourceViewDigest());
        value.addProperty("model_raster_input_digest", identity.modelRasterInputDigest());
        return value;
    }

    private static JsonObject toJson(BackendResultMeta meta) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", meta.schemaVersion());
        value.addProperty("action_id", meta.actionId());
        value.addProperty("output_generation_id", meta.outputGenerationId());
        value.addProperty("backend_call_id", meta.backendCallId());
        value.addProperty("input_id", meta.inputId());
        value.add("producer", InferenceIdentityCodec.toJson(meta.producer()));
        value.add("backend", InferenceIdentityCodec.toJson(meta.backend()));
        value.add("input_identity", toJson(meta.inputIdentity()));
        value.addProperty("coordinate_space", meta.coordinateSpace().name());
        return value;
    }

    private static JsonObject callStatusJson(int schemaVersion, String backendCallId) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", schemaVersion);
        value.addProperty("backend_call_id", backendCallId);
        return value;
    }

    private static void requireAddressedCall(String responseCallId, String addressedBackendCallId) {
        String addressed =
                BackendExecutionCodec.requireBackendCallId(
                        addressedBackendCallId, "addressed_backend_call_id");
        if (!responseCallId.equals(addressed)) {
            throw new IllegalArgumentException(
                    "response backend_call_id does not match addressed call");
        }
    }

    private static void requireCurrentSchema(int value) {
        if (value != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported finite inference schema_version");
        }
    }

    private static <T extends Enum<T>> T exactEnum(JsonElement value, Class<T> type, String path) {
        String text = requiredString(value, path);
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + " has an unsupported value", e);
        }
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject value, String field) {
        return requiredString(value.get(field), field);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }
}
