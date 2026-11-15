package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ModelRunSourceCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;

/** Real HTTP checks for authenticated image assignment and truthful delivery failures. */
class ModelRunImageDeliveryTest {
    @Test
    void readBindReplayAndFailureUseTheAcceptedRunWithoutRedispatch(@TempDir Path directory)
            throws Exception {
        try (var gateway = new FixtureGateway(directory)) {
            var delivery = gateway.executor.readSources(gateway.handle);
            var binding = delivery.sourceBindings().getFirst();
            gateway.executor.bindSource(gateway.handle, binding);
            gateway.executor.bindSource(gateway.handle, binding);
            var failure =
                    new GatewayErrorCodec.Error(
                            1,
                            GatewayErrorCodec.Code.RUNNER_FAILED,
                            "Could not read the selected image",
                            Optional.empty(),
                            Optional.empty());
            gateway.executor.reportSourceFailure(gateway.handle, failure);

            assertEquals(
                    List.of(
                            "GET /sources",
                            "POST /sources",
                            "POST /sources",
                            "POST /source-failure"),
                    gateway.calls);
            byte[] bindingBytes =
                    CanonicalCodec.canonicalBytes(ModelRunSourceCodec.toJson(binding));
            assertArrayEquals(bindingBytes, gateway.bodies.get(1));
            assertArrayEquals(bindingBytes, gateway.bodies.get(2));
            assertArrayEquals(
                    CanonicalCodec.canonicalBytes(GatewayErrorCodec.toJson(failure)),
                    gateway.bodies.get(3));
            gateway.requireNoFailure();
        }
    }

    @Test
    void wrongAcceptanceAndNoncanonicalReadbackAreRejected(@TempDir Path directory)
            throws Exception {
        try (var gateway = new FixtureGateway(directory)) {
            var binding = gateway.executor.readSources(gateway.handle).sourceBindings().getFirst();
            var changed = ModelRunSourceCodec.toJson(binding);
            changed.getAsJsonObject("source")
                    .getAsJsonObject("artifact")
                    .addProperty("bytes_digest", "f".repeat(64));
            gateway.bindingResponse = CanonicalCodec.canonicalBytes(changed);
            assertThrows(
                    InferenceException.class,
                    () -> gateway.executor.bindSource(gateway.handle, binding));

            gateway.deliveryBytes =
                    (new String(gateway.deliveryBytes, StandardCharsets.UTF_8) + " ")
                            .getBytes(StandardCharsets.UTF_8);
            assertThrows(
                    InferenceException.class, () -> gateway.executor.readSources(gateway.handle));
            gateway.requireNoFailure();
        }
    }

    @Test
    void wrongInputAndChangedDeploymentNeverSendAnImage(@TempDir Path directory) throws Exception {
        try (var gateway = new FixtureGateway(directory)) {
            var binding = gateway.executor.readSources(gateway.handle).sourceBindings().getFirst();
            var wrong =
                    new ModelRunSourceCodec.Binding(
                            binding.inputId() + 100, binding.unitId(), binding.source());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> gateway.executor.bindSource(gateway.handle, wrong));
            String url = "http://localhost:" + gateway.server.getAddress().getPort();
            java.nio.file.Files.writeString(
                    directory.resolve("deployment.json"),
                    ModelGatewayExecutorTestData.deployment(url, "replacement-deployment")
                            .toJson()
                            .toString());
            assertThrows(
                    GatewayTransport.DeploymentChangedException.class,
                    () -> gateway.executor.bindSource(gateway.handle, binding));
            assertEquals(List.of("GET /sources"), gateway.calls);
            gateway.requireNoFailure();
        }
    }

    private static final class FixtureGateway implements AutoCloseable {
        private final HttpServer server;
        private final DurableModelRunExecutor executor;
        private final ModelRunHandle handle;
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile byte[] deliveryBytes;
        private volatile byte[] bindingResponse;

        private FixtureGateway(Path directory) throws Exception {
            JsonObject fixture;
            try (var stream =
                    ModelRunImageDeliveryTest.class.getResourceAsStream(
                            "/contracts/model-source-delivery-v1.json")) {
                if (stream == null) throw new IOException("missing source delivery fixture");
                fixture =
                        JsonParser.parseString(
                                        new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                                .getAsJsonObject();
            }
            var item = fixture.getAsJsonArray("cases").get(1).getAsJsonObject();
            var request =
                    ModelRunRequestCodec.parseRequest(
                            JsonParser.parseString(item.get("requestCanonical").getAsString()));
            deliveryBytes =
                    item.get("deliveryCanonical").getAsString().getBytes(StandardCharsets.UTF_8);
            var capabilities = ModelGatewayExecutorTestData.capabilities();
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ModelGatewayExecutorTestData.serveCapabilities(server, capabilities);
            String prefix = "/v1/inference/runs/" + request.modelRunId();
            server.createContext(prefix, exchange -> respond(exchange, prefix));
            server.start();
            executor =
                    new DurableModelRunExecutor(
                            new GatewayTransport(
                                    ModelGatewayExecutorTestData.storeFor(server, directory)),
                            capabilities);
            handle =
                    new ModelRunHandle(
                            capabilities.deploymentId(),
                            request,
                            new ModelRunRequestCodec.ModelRunAccepted(
                                    ModelRunRequestCodec.SCHEMA_VERSION,
                                    request.actionId(),
                                    request.modelRunId(),
                                    ModelRunRequestCodec.requestDigest(request)),
                            new RunnerCapabilitiesCodec.DurableRunLimits(
                                    ExecutionLimitsCodec.parseModelRunLimits(
                                            fixture.get("modelRunLimits")),
                                    3600),
                            Set.of("Cell"));
        }

        private void respond(HttpExchange exchange, String prefix) throws IOException {
            try {
                assertEquals(
                        "Bearer test-token",
                        exchange.getRequestHeaders().getFirst("Authorization"));
                String route =
                        exchange.getRequestMethod()
                                + " "
                                + exchange.getRequestURI().getPath().substring(prefix.length());
                calls.add(route);
                byte[] body = exchange.getRequestBody().readAllBytes();
                bodies.add(body);
                byte[] response;
                switch (route) {
                    case "GET /sources" -> response = deliveryBytes;
                    case "POST /sources" ->
                            response = bindingResponse == null ? body : bindingResponse;
                    case "POST /source-failure" -> {
                        GatewayErrorCodec.parse(CanonicalCodec.parseJson(body));
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    default -> throw new AssertionError("unexpected run operation: " + route);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable error) {
                failure.set(error);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        }

        private void requireNoFailure() {
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
