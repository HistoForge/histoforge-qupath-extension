package qupath.ext.histoforge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import qupath.ext.histoforge.contract.ArtifactRefCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ModelExecutionPlanCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ModelRunStatusCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;

/** Stateful local HTTP gateway for the complete durable-run client lifecycle. */
final class DurableModelRunGatewayStub implements AutoCloseable {

    private static final String ACTION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String RUN_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String SECOND_GENERATION_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    private static final String PARENT_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    private static final String REQUEST_ARTIFACT_ID = "20202020-2020-4020-8020-202020202020";
    private static final String MANIFEST_ARTIFACT_ID = "22222222-2222-4222-8222-222222222222";
    private static final String FIRST_FEATURE_ID = "21212121-2121-4121-8121-212121212121";
    private static final String SECOND_FEATURE_ID = "23232323-2323-4232-8232-232323232323";
    private static final Instant DEADLINE = Instant.parse("2026-08-17T12:00:00.000Z");

    private final HttpServer server;
    private final ModelRunRequestCodec.ModelRunRequest request;
    private final RunnerCapabilitiesCodec.RunnerCapabilities runnerCapabilities;
    private final byte[] canonicalRequest;
    private final byte[] completeManifest;
    private final byte[] partialManifest;
    private final Map<String, ArtifactPayload> artifacts = new LinkedHashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger postCalls = new AtomicInteger();
    private final AtomicInteger pollCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private final AtomicInteger artifactCalls = new AtomicInteger();
    private volatile boolean completeImmediately;
    private volatile boolean partial;
    private volatile boolean loseFirstSubmitResponse;
    private volatile ManifestReferenceFault manifestReferenceFault;
    private volatile Runnable beforeSubmitResponse = () -> {};

    DurableModelRunGatewayStub() throws IOException {
        FiniteInferenceCodec.FiniteInferenceRequest finite = finiteRequest();
        var firstOutput =
                new ExecutionScopeCodec.OutputKey(
                        finite.producer(),
                        new ExecutionScopeCodec.FieldScope(finite.rasterInput().frame().region()));
        var secondOutput =
                new ExecutionScopeCodec.OutputKey(
                        finite.producer(),
                        new ExecutionScopeCodec.ParentScope(
                                finite.rasterInput().frame().region(), PARENT_ID));
        request =
                new ModelRunRequestCodec.ModelRunRequest(
                        ModelRunRequestCodec.SCHEMA_VERSION,
                        ACTION_ID,
                        RUN_ID,
                        finite.producer(),
                        finite.expectedBackend(),
                        Optional.empty(),
                        List.of(
                                new ModelRunRequestCodec.ModelRunInput(
                                        0,
                                        finite.outputGenerationId(),
                                        firstOutput,
                                        finite.rasterInput()),
                                new ModelRunRequestCodec.ModelRunInput(
                                        3,
                                        SECOND_GENERATION_ID,
                                        secondOutput,
                                        finite.rasterInput())),
                        finite.resultBudget(),
                        Optional.empty(),
                        java.util.Optional.empty());
        canonicalRequest = CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(request));
        var runLimits =
                qupath.ext.histoforge.contract.ExecutionLimitsCodec.parseModelRunLimits(
                        fixture("model-run-request-v1.json").get("testModelRunLimits"));
        var finiteLimits =
                new RunnerCapabilitiesCodec.FiniteCallLimits(
                        300,
                        1_000_000,
                        runLimits.targetRasterPerInput(),
                        runLimits.maxAggregateInclusionMaskVertices(),
                        runLimits.result());
        runnerCapabilities =
                new RunnerCapabilitiesCodec.RunnerCapabilities(
                        Set.of(
                                RunnerCapabilitiesCodec.InferenceMode.FINITE_CALL,
                                RunnerCapabilitiesCodec.InferenceMode.DURABLE_MODEL_RUN),
                        Set.of(
                                RunnerCapabilitiesCodec.ModelOutputType
                                        .INSTANCE_SEGMENTATION_AND_CLASSIFICATION),
                        Set.of(SourceArtifactCodec.SourceContainer.TIFF_FAMILY),
                        finite.expectedBackend().runnerBuild(),
                        finite.expectedBackend().rasterizer(),
                        finite.expectedBackend().preprocessingContractDigest(),
                        finiteLimits,
                        Optional.of(
                                new RunnerCapabilitiesCodec.DurableRunLimits(runLimits, 86_400)));

        byte[] firstFeatures = featureBytes(0);
        byte[] secondFeatures = featureBytes(1);
        ArtifactRefCodec.ArtifactRef firstFeature =
                reference(
                        FIRST_FEATURE_ID,
                        ModelRunPublicationCodec.FEATURE_STREAM_MEDIA_TYPE,
                        Optional.of(ModelRunPublicationCodec.FEATURE_STREAM_CONTENT_ENCODING),
                        firstFeatures);
        ArtifactRefCodec.ArtifactRef secondFeature =
                reference(
                        SECOND_FEATURE_ID,
                        ModelRunPublicationCodec.FEATURE_STREAM_MEDIA_TYPE,
                        Optional.of(ModelRunPublicationCodec.FEATURE_STREAM_CONTENT_ENCODING),
                        secondFeatures);
        completeManifest = manifestBytes(runLimits, List.of(firstFeature, secondFeature), true);
        partialManifest = manifestBytes(runLimits, List.of(firstFeature), false);

        registerArtifact(
                reference(
                        REQUEST_ARTIFACT_ID,
                        "application/json",
                        Optional.empty(),
                        canonicalRequest),
                canonicalRequest);
        registerArtifact(firstFeature, firstFeatures);
        registerArtifact(secondFeature, secondFeatures);
        registerManifest(completeManifest);

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ModelGatewayExecutorTestData.serveCapabilities(
                server, ModelGatewayExecutorTestData.capabilities());
        server.createContext("/v1/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    HttpServer server() {
        return server;
    }

    ModelRunRequestCodec.ModelRunRequest request() {
        return request;
    }

    RunnerCapabilitiesCodec.RunnerCapabilities runnerCapabilities() {
        return runnerCapabilities;
    }

    Set<String> declaredClasses() {
        return Set.of("Tubule");
    }

    void completeImmediately() {
        completeImmediately = true;
    }

    void returnPartial() {
        partial = true;
        completeImmediately = true;
        registerManifest(partialManifest);
    }

    void loseFirstSubmitResponse() {
        loseFirstSubmitResponse = true;
    }

    void beforeSubmitResponse(Runnable callback) {
        beforeSubmitResponse = Objects.requireNonNull(callback, "callback");
    }

    void alterFirstFeatureGzipHeader() {
        replaceFirstFeatureByte(4);
    }

    void malformFirstFeatureGzipTrailer() {
        replaceFirstFeatureByte(artifacts.get(FIRST_FEATURE_ID).bytes().length - 1);
    }

    void useWrongManifestMediaType() {
        manifestReferenceFault = ManifestReferenceFault.MEDIA_TYPE;
    }

    void useWrongManifestEncoding() {
        manifestReferenceFault = ManifestReferenceFault.CONTENT_ENCODING;
    }

    void useOversizedManifestReference() {
        manifestReferenceFault = ManifestReferenceFault.SIZE;
    }

    private void replaceFirstFeatureByte(int index) {
        ArtifactPayload current = artifacts.get(FIRST_FEATURE_ID);
        byte[] corrupt = current.bytes().clone();
        corrupt[index] ^= 1;
        artifacts.put(FIRST_FEATURE_ID, new ArtifactPayload(current.reference(), corrupt));
    }

    int postCalls() {
        return postCalls.get();
    }

    int pollCalls() {
        return pollCalls.get();
    }

    int cancelCalls() {
        return cancelCalls.get();
    }

    int artifactCalls() {
        return artifactCalls.get();
    }

    void requireNoFailure() {
        Throwable value = failure.get();
        if (value != null) {
            throw new AssertionError("gateway stub failed", value);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            requireAuthorization(exchange);
            String path = exchange.getRequestURI().getRawPath();
            String startPath =
                    "/v1/inference/"
                            + request.producer().moduleRef().owner()
                            + "/"
                            + request.producer().moduleRef().name()
                            + "/"
                            + request.producer().stepId()
                            + "/runs";
            String statusPath = "/v1/inference/runs/" + request.modelRunId();
            if (path.equals(startPath) && exchange.getRequestMethod().equals("POST")) {
                handleSubmit(exchange);
            } else if (path.equals(statusPath) && exchange.getRequestMethod().equals("GET")) {
                handleStatus(exchange);
            } else if (path.equals(statusPath) && exchange.getRequestMethod().equals("DELETE")) {
                cancelCalls.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            } else if (path.startsWith("/v1/artifacts/")
                    && exchange.getRequestMethod().equals("GET")) {
                handleArtifact(exchange, path.substring("/v1/artifacts/".length()));
            } else {
                throw new AssertionError(
                        "unexpected request: " + exchange.getRequestMethod() + " " + path);
            }
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
            exchange.close();
        }
    }

    private void handleSubmit(HttpExchange exchange) throws IOException {
        int call = postCalls.incrementAndGet();
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (!Arrays.equals(body, canonicalRequest)) {
            throw new AssertionError("durable submit body is not the canonical request");
        }
        if (loseFirstSubmitResponse && call == 1) {
            exchange.close();
            return;
        }
        var accepted =
                new ModelRunRequestCodec.ModelRunAccepted(
                        ModelRunRequestCodec.SCHEMA_VERSION,
                        request.actionId(),
                        request.modelRunId(),
                        ModelRunRequestCodec.requestDigest(request));
        beforeSubmitResponse.run();
        respondJson(exchange, 202, ModelRunRequestCodec.toJson(accepted));
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        int call = pollCalls.incrementAndGet();
        if (!completeImmediately && call == 1) {
            respondJson(exchange, 200, status(ModelRunStatusCodec.Phase.QUEUED, null));
            return;
        }
        byte[] manifest = partial ? partialManifest : completeManifest;
        respondJson(
                exchange,
                200,
                status(
                        partial
                                ? ModelRunStatusCodec.Phase.PARTIAL
                                : ModelRunStatusCodec.Phase.COMPLETE,
                        manifest));
    }

    private void handleArtifact(HttpExchange exchange, String artifactId) throws IOException {
        artifactCalls.incrementAndGet();
        ArtifactPayload payload = artifacts.get(artifactId);
        if (payload == null) {
            throw new AssertionError("unknown artifact: " + artifactId);
        }
        if (exchange.getRequestHeaders().containsKey("Range")
                || exchange.getRequestHeaders().containsKey("If-Match")) {
            throw new AssertionError("bounded complete download unexpectedly used a range");
        }
        if (!List.of("identity").equals(exchange.getRequestHeaders().get("Accept-Encoding"))) {
            throw new AssertionError("immutable download did not require identity encoding");
        }
        ArtifactRefCodec.ArtifactRef reference = payload.reference();
        exchange.getResponseHeaders().set("Content-Type", reference.mediaType());
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("ETag", '"' + reference.sha256() + '"');
        reference
                .contentEncoding()
                .ifPresent(value -> exchange.getResponseHeaders().set("Content-Encoding", value));
        exchange.sendResponseHeaders(200, payload.bytes().length);
        exchange.getResponseBody().write(payload.bytes());
    }

    private JsonObject status(ModelRunStatusCodec.Phase phase, byte[] manifest) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", ModelRunStatusCodec.SCHEMA_VERSION);
        value.addProperty("model_run_id", request.modelRunId());
        value.addProperty("phase", phase.name());
        value.add(
                "request_artifact",
                qupath.ext.histoforge.contract.ArtifactRefCodec.toJson(
                        artifacts.get(REQUEST_ARTIFACT_ID).reference()));
        value.addProperty(
                "completed_unit_count",
                phase == ModelRunStatusCodec.Phase.QUEUED ? 0 : partial ? 1 : 2);
        value.addProperty("total_unit_count", 2);
        if (manifest != null) {
            value.add("terminal_manifest", ArtifactRefCodec.toJson(manifestReference()));
        }
        value.addProperty("phase_deadline", CanonicalCodec.rfc3339Millis(DEADLINE, "deadline"));
        return value;
    }

    private ArtifactRefCodec.ArtifactRef manifestReference() {
        ArtifactRefCodec.ArtifactRef reference = artifacts.get(MANIFEST_ARTIFACT_ID).reference();
        if (manifestReferenceFault == null) {
            return reference;
        }
        return switch (manifestReferenceFault) {
            case MEDIA_TYPE ->
                    new ArtifactRefCodec.ArtifactRef(
                            reference.artifactId(),
                            "text/plain",
                            reference.contentEncoding(),
                            reference.sizeBytes(),
                            reference.sha256());
            case CONTENT_ENCODING ->
                    new ArtifactRefCodec.ArtifactRef(
                            reference.artifactId(),
                            reference.mediaType(),
                            Optional.of("gzip"),
                            reference.sizeBytes(),
                            reference.sha256());
            case SIZE ->
                    new ArtifactRefCodec.ArtifactRef(
                            reference.artifactId(),
                            reference.mediaType(),
                            reference.contentEncoding(),
                            GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES + 1,
                            reference.sha256());
        };
    }

    private byte[] manifestBytes(
            qupath.ext.histoforge.contract.ExecutionLimitsCodec.ModelRunLimits limits,
            List<ArtifactRefCodec.ArtifactRef> featureArtifacts,
            boolean complete) {
        Map<Integer, ModelExecutionPlanCodec.ModelExecutionPlan> plans =
                ModelRunPublicationCodec.buildExpectedPlans(request, limits);
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", ModelRunPublicationCodec.SCHEMA_VERSION);
        value.addProperty("model_run_id", request.modelRunId());
        value.addProperty("action_id", request.actionId());
        value.add("backend", InferenceIdentityCodec.toJson(request.expectedBackend()));
        value.addProperty("request_digest", ModelRunRequestCodec.requestDigest(request));
        value.add("completed_units", new JsonArray());
        value.add("checkpoints", new JsonArray());
        value.add("failed_units", new JsonArray());
        JsonArray completed = new JsonArray();
        for (int index = 0; index < featureArtifacts.size(); index++) {
            var input = request.inputs().get(index);
            JsonObject result = new JsonObject();
            result.addProperty("input_id", input.inputId());
            result.add(
                    "input_identity",
                    FiniteInferenceCodec.toJson(
                            FiniteInferenceCodec.buildBackendInputIdentity(input.rasterInput())));
            result.add(
                    "execution_plan", ModelExecutionPlanCodec.toJson(plans.get(input.inputId())));
            result.add(
                    "feature_stream_artifact",
                    ArtifactRefCodec.toJson(featureArtifacts.get(index)));
            result.addProperty("feature_count", index == 0 ? 2 : 0);
            result.addProperty("vertex_count", index == 0 ? 6 : 0);
            completed.add(result);
        }
        value.add("completed_inputs", completed);
        value.addProperty("complete", complete);
        value.addProperty("manifest_digest", CanonicalCodec.canonicalDigest(value));
        ModelRunPublicationCodec.validateManifest(value, request, limits);
        return CanonicalCodec.canonicalBytes(value);
    }

    private void registerManifest(byte[] bytes) {
        registerArtifact(
                reference(MANIFEST_ARTIFACT_ID, "application/json", Optional.empty(), bytes),
                bytes);
    }

    private void registerArtifact(ArtifactRefCodec.ArtifactRef reference, byte[] bytes) {
        artifacts.put(reference.artifactId(), new ArtifactPayload(reference, bytes.clone()));
    }

    private static ArtifactRefCodec.ArtifactRef reference(
            String artifactId, String mediaType, Optional<String> contentEncoding, byte[] bytes) {
        return new ArtifactRefCodec.ArtifactRef(
                artifactId, mediaType, contentEncoding, bytes.length, CanonicalCodec.sha256(bytes));
    }

    private static FiniteInferenceCodec.FiniteInferenceRequest finiteRequest() throws IOException {
        JsonObject root = fixture("finite-inference-v1.json");
        String canonical =
                root.getAsJsonArray("cases")
                        .get(0)
                        .getAsJsonObject()
                        .get("requestCanonical")
                        .getAsString();
        return FiniteInferenceCodec.parseRequest(JsonParser.parseString(canonical));
    }

    private static byte[] featureBytes(int index) throws IOException {
        String encoded =
                fixture("feature-stream-v1.json")
                        .getAsJsonArray("cases")
                        .get(index)
                        .getAsJsonObject()
                        .get("gzipBase64")
                        .getAsString();
        return java.util.Base64.getDecoder().decode(encoded);
    }

    private static JsonObject fixture(String name) throws IOException {
        try (InputStream input =
                DurableModelRunGatewayStub.class
                        .getClassLoader()
                        .getResourceAsStream("contracts/" + name)) {
            if (input == null) {
                throw new IOException("missing fixture: " + name);
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static void requireAuthorization(HttpExchange exchange) {
        if (!List.of("Bearer test-token")
                .equals(exchange.getRequestHeaders().get("Authorization"))) {
            throw new AssertionError("missing gateway bearer token");
        }
    }

    private static void respondJson(HttpExchange exchange, int status, JsonElement value)
            throws IOException {
        byte[] body = CanonicalCodec.canonicalBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private record ArtifactPayload(ArtifactRefCodec.ArtifactRef reference, byte[] bytes) {
        private ArtifactPayload {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private enum ManifestReferenceFault {
        MEDIA_TYPE,
        CONTENT_ENCODING,
        SIZE
    }
}
