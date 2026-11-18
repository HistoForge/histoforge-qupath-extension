package qupath.ext.histoforge.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec.FiniteInferenceRequest;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;

/** Stateful local HTTP finite gateway used by {@link ModelGatewayExecutorFiniteTest}. */
final class FiniteInferenceGatewayStub implements AutoCloseable {

    static final String CALL_ID = "call /+🧪\u0001";

    private final FiniteInferenceRequest request;
    private final RunnerCapabilitiesCodec.RunnerCapabilities runnerCapabilities;
    private final Set<String> declaredClasses;
    private final byte[] canonicalRequest;
    private final JsonObject accepted;
    private final JsonObject pending;
    private final JsonObject cancellation;
    private final JsonObject result;
    private final HttpServer server;
    private final AtomicInteger postCalls = new AtomicInteger();
    private final AtomicInteger pollCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Runnable> beforeSubmitResponse = new AtomicReference<>();
    private final AtomicBoolean loseSubmitResponse = new AtomicBoolean();
    private final AtomicReference<HttpExchange> pausedPollResponse = new AtomicReference<>();
    private volatile boolean pausePollBody;
    private volatile Integer fixedPollStatus;
    private volatile byte[] fixedPollBody;

    FiniteInferenceGatewayStub() throws IOException {
        this(null);
    }

    FiniteInferenceGatewayStub(FiniteInferenceRequest replacementRequest) throws IOException {
        JsonObject testCase = firstCase();
        request =
                replacementRequest == null
                        ? FiniteInferenceCodec.parseRequest(wire(testCase, "request"))
                        : replacementRequest;
        canonicalRequest = CanonicalCodec.canonicalBytes(FiniteInferenceCodec.toJson(request));
        declaredClasses = strings(testCase, "declaredClasses");
        accepted = wire(testCase, "accepted");
        accepted.addProperty("action_id", request.actionId());
        accepted.addProperty("output_generation_id", request.outputGenerationId());
        accepted.addProperty("backend_call_id", CALL_ID);
        pending = wire(testCase, "pending");
        pending.addProperty("backend_call_id", CALL_ID);
        cancellation = wire(testCase, "cancellation");
        cancellation.addProperty("backend_call_id", CALL_ID);
        result = wire(testCase, "result");
        JsonObject meta = result.getAsJsonObject("meta");
        meta.addProperty("action_id", request.actionId());
        meta.addProperty("output_generation_id", request.outputGenerationId());
        meta.addProperty("input_id", request.inputId());
        meta.addProperty("backend_call_id", CALL_ID);
        meta.add("producer", FiniteInferenceCodec.toJson(request).get("producer"));
        meta.add("backend", FiniteInferenceCodec.toJson(request).get("expected_backend"));
        meta.add(
                "input_identity",
                FiniteInferenceCodec.toJson(
                        FiniteInferenceCodec.buildBackendInputIdentity(request.rasterInput())));
        runnerCapabilities = runnerFor(request);

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ModelGatewayExecutorTestData.serveCapabilities(
                server, ModelGatewayExecutorTestData.capabilities());
        server.createContext("/v1/inference", this::handle);
        server.start();
    }

    HttpServer server() {
        return server;
    }

    FiniteInferenceRequest request() {
        return request;
    }

    RunnerCapabilitiesCodec.RunnerCapabilities runnerCapabilities() {
        return runnerCapabilities;
    }

    Set<String> declaredClasses() {
        return declaredClasses;
    }

    int requestCount() {
        return postCalls.get() + pollCalls.get() + cancelCalls.get();
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

    void loseSubmitResponse() {
        loseSubmitResponse.set(true);
    }

    void beforeSubmitResponse(Runnable action) {
        beforeSubmitResponse.set(action);
    }

    void setAcceptedActionId(String actionId) {
        accepted.addProperty("action_id", actionId);
    }

    void replyToPoll(int status, byte[] body) {
        fixedPollStatus = status;
        fixedPollBody = body.clone();
    }

    void pausePollBody() {
        pausePollBody = true;
    }

    void releasePollBody() {
        HttpExchange paused = pausedPollResponse.getAndSet(null);
        if (paused != null) {
            paused.close();
        }
    }

    void requireNoFailure() {
        Throwable problem = failure.get();
        if (problem != null) {
            throw new AssertionError("finite gateway stub failed", problem);
        }
    }

    @Override
    public void close() {
        releasePollBody();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) {
        try {
            require(
                    List.of("Bearer test-token")
                            .equals(exchange.getRequestHeaders().get("Authorization")),
                    "missing exact authorization header");
            String rawPath = exchange.getRequestURI().getRawPath();
            if ("POST".equals(exchange.getRequestMethod())) {
                handleSubmit(exchange, rawPath);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                handlePoll(exchange, rawPath);
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                handleCancel(exchange, rawPath);
            } else {
                reply(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
            try {
                reply(exchange, 500, "stub failure".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                exchange.close();
            }
        }
    }

    private void handleSubmit(HttpExchange exchange, String rawPath) throws IOException {
        postCalls.incrementAndGet();
        var module = request.producer().moduleRef();
        String expectedPath =
                "/v1/inference/"
                        + module.owner()
                        + "/"
                        + module.name()
                        + "/"
                        + request.producer().stepId();
        require(expectedPath.equals(rawPath), "finite submit route differs");
        require(exchange.getRequestURI().getRawQuery() == null, "finite submit has a query");
        require(
                List.of("application/json")
                        .equals(exchange.getRequestHeaders().get("Content-Type")),
                "finite submit content type differs");
        require(
                java.util.Arrays.equals(canonicalRequest, exchange.getRequestBody().readAllBytes()),
                "finite request is not the exact canonical body");
        if (loseSubmitResponse.compareAndSet(true, false)) {
            exchange.close();
            return;
        }
        Runnable action = beforeSubmitResponse.getAndSet(null);
        if (action != null) {
            action.run();
        }
        reply(exchange, 202, CanonicalCodec.canonicalBytes(accepted));
    }

    private void handlePoll(HttpExchange exchange, String rawPath) throws IOException {
        pollCalls.incrementAndGet();
        requireCallsAddress(exchange, rawPath);
        if (pausePollBody) {
            exchange.sendResponseHeaders(202, 0);
            var body = exchange.getResponseBody();
            body.write('{');
            body.flush();
            // Keep the body open without blocking the server from accepting cancellation.
            pausedPollResponse.set(exchange);
            return;
        }
        Integer status = fixedPollStatus;
        if (status != null) {
            reply(exchange, status, fixedPollBody);
            return;
        }
        if (pollCalls.get() == 1) {
            reply(exchange, 202, CanonicalCodec.canonicalBytes(pending));
        } else {
            reply(exchange, 200, CanonicalCodec.canonicalBytes(result));
        }
    }

    private void handleCancel(HttpExchange exchange, String rawPath) throws IOException {
        cancelCalls.incrementAndGet();
        requireCallsAddress(exchange, rawPath);
        reply(exchange, 202, CanonicalCodec.canonicalBytes(cancellation));
    }

    private static void requireCallsAddress(HttpExchange exchange, String rawPath) {
        require("/v1/inference/calls".equals(rawPath), "finite call route differs");
        require(
                BackendExecutionCodec.encodeBackendCallQuery(CALL_ID)
                        .equals(exchange.getRequestURI().getRawQuery()),
                "finite call query differs");
    }

    private static RunnerCapabilitiesCodec.RunnerCapabilities runnerFor(
            FiniteInferenceRequest request) {
        InferenceIdentityCodec.BackendModelIdentity backend = request.expectedBackend();
        return new RunnerCapabilitiesCodec.RunnerCapabilities(
                Set.of(RunnerCapabilitiesCodec.InferenceMode.FINITE_CALL),
                Set.of(RunnerCapabilitiesCodec.ModelOutputType.INSTANCE_SEGMENTATION),
                Set.of(
                        SourceArtifactCodec.SourceContainer.PNG,
                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY),
                backend.runnerBuild(),
                backend.rasterizer(),
                backend.preprocessingContractDigest(),
                new RunnerCapabilitiesCodec.FiniteCallLimits(
                        600,
                        1_000_000,
                        new ExecutionLimitsCodec.TargetRasterLimits(2048, 2048, 4_194_304),
                        100_000,
                        new ExecutionLimitsCodec.ResultLimits(
                                3_145_728, 3_145_728, 1_000_000, 100_000, 1_000_000)),
                java.util.Optional.empty());
    }

    private static JsonObject firstCase() throws IOException {
        try (InputStream stream =
                FiniteInferenceGatewayStub.class.getResourceAsStream(
                        "/contracts/finite-inference-v3.json")) {
            if (stream == null) {
                throw new IOException("missing finite-inference fixture");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes())
                    .getAsJsonObject()
                    .getAsJsonArray("cases")
                    .get(0)
                    .getAsJsonObject();
        }
    }

    private static JsonObject wire(JsonObject testCase, String name) {
        return CanonicalCodec.parseJson(testCase.get(name + "Canonical").getAsString())
                .getAsJsonObject();
    }

    private static Set<String> strings(JsonObject value, String field) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement item : value.getAsJsonArray(field)) {
            result.add(item.getAsString());
        }
        return Set.copyOf(result);
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
