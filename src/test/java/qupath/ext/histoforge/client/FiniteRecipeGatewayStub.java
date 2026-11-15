package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec.FiniteInferenceRequest;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;

/** Local finite HTTP boundary for a recipe; inference and source service are not implemented. */
public final class FiniteRecipeGatewayStub implements AutoCloseable {
    private final HttpServer server;
    private final GatewayCapabilities capabilities;
    private final DeploymentStore deployment;
    private final BiFunction<FiniteInferenceRequest, String, JsonObject> prediction;
    private final List<FiniteInferenceRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, JsonObject> resultsByQuery = new HashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    public FiniteRecipeGatewayStub(
            Path directory,
            String deploymentId,
            BiFunction<FiniteInferenceRequest, String, JsonObject> prediction)
            throws IOException {
        this.prediction = prediction;
        capabilities = ModelGatewayExecutorTestData.capabilities(deploymentId);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        deployment = ModelGatewayExecutorTestData.storeFor(server, directory, deploymentId);
        ModelGatewayExecutorTestData.serveCapabilities(server, capabilities);
        server.createContext("/v1/inference", this::handle);
        server.start();
    }

    public ModelGatewayExecutor executor() {
        return new ModelGatewayExecutor(new GatewayTransport(deployment), capabilities);
    }

    public List<FiniteInferenceRequest> requests() {
        return List.copyOf(requests);
    }

    public void requireNoFailure() {
        if (failure.get() != null) {
            throw new AssertionError("finite recipe gateway failed", failure.get());
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) {
        try {
            assertEquals(
                    List.of("Bearer test-token"),
                    exchange.getRequestHeaders().get("Authorization"));
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestMethod().equals("POST")) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                var request = FiniteInferenceCodec.parseRequest(CanonicalCodec.parseJson(body));
                assertArrayEquals(
                        CanonicalCodec.canonicalBytes(FiniteInferenceCodec.toJson(request)), body);
                assertEquals(
                        "/v1/inference/"
                                + request.producer().moduleRef().owner()
                                + "/"
                                + request.producer().moduleRef().name()
                                + "/"
                                + request.producer().stepId(),
                        path);
                assertNull(exchange.getRequestURI().getRawQuery());
                assertEquals(
                        List.of("application/json"),
                        exchange.getRequestHeaders().get("Content-Type"));
                requests.add(request);
                String callId = "recipe-call-" + requests.size();
                resultsByQuery.put(
                        BackendExecutionCodec.encodeBackendCallQuery(callId),
                        prediction.apply(request, callId));
                reply(
                        exchange,
                        202,
                        CanonicalCodec.canonicalBytes(
                                FiniteInferenceCodec.toJson(
                                        new FiniteInferenceCodec.InferenceAccepted(
                                                FiniteInferenceCodec.SCHEMA_VERSION,
                                                request.actionId(),
                                                request.outputGenerationId(),
                                                callId))));
            } else {
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("/v1/inference/calls", path);
                JsonObject result = resultsByQuery.get(exchange.getRequestURI().getRawQuery());
                assertNotNull(result, "poll must address a previously accepted call");
                reply(exchange, 200, CanonicalCodec.canonicalBytes(result));
            }
        } catch (Throwable problem) {
            failure.compareAndSet(null, problem);
            try {
                reply(exchange, 500, "stub failure".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                exchange.close();
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
