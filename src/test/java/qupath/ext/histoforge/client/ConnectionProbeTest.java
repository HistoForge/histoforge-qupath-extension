package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.platform.ExtensionRelease;

/** End-to-end local HTTP checks for authenticated deployment compatibility. */
class ConnectionProbeTest {

    @Test
    void malformedIdentityCannotSupplyAnAttributedBackendVersion(@TempDir Path dir)
            throws Exception {
        for (String field : List.of("deployment_id", "auth_revision")) {
            JsonObject body = capabilities("deployment-a", 7);
            body.remove(field);
            HttpServer server = gatewayStub(new AtomicInteger(), null, body);
            try {
                var result =
                        ConnectionProbe.probe(storeFor(server, dir, "deployment-a", 7, "token-a"));
                assertEquals(
                        BackendCompatibility.State.UNKNOWN,
                        result.compatibility().orElseThrow().state());
                assertFalse(result.compatibility().orElseThrow().backendVersion().isPresent());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void sameReleaseDifferentBuildDigestIsStillConnected(@TempDir Path dir) throws Exception {
        JsonObject body = capabilities("deployment-a", 7);
        body.getAsJsonObject("gateway_build").addProperty("artifact_digest", "e".repeat(64));
        HttpServer server = gatewayStub(new AtomicInteger(), null, body);
        try {
            var result = ConnectionProbe.probe(storeFor(server, dir, "deployment-a", 7, "token-a"));
            assertEquals(ConnectionProbe.Status.CONNECTED, result.status());
            assertEquals(
                    BackendCompatibility.State.CURRENT,
                    result.compatibility().orElseThrow().state());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noValidDeploymentIsNotConfigured(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deployment.json");
        assertEquals(ConnectionProbe.Status.NOT_CONFIGURED, probeStatus(new DeploymentStore(file)));

        Files.writeString(file, "{}");
        assertEquals(ConnectionProbe.Status.NOT_CONFIGURED, probeStatus(new DeploymentStore(file)));
    }

    @Test
    void exactAuthenticatedDeploymentIsConnected(@TempDir Path dir) throws Exception {
        AtomicInteger authenticatedCalls = new AtomicInteger();
        HttpServer server = gatewayStub(authenticatedCalls, null, null);
        try {
            assertEquals(
                    ConnectionProbe.Status.CONNECTED,
                    probeStatus(storeFor(server, dir, "deployment-a", 7, "token-a")));
            assertEquals(1, authenticatedCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modalColdStartWithinHealthBudgetIsConnected(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/health",
                exchange -> {
                    try {
                        Thread.sleep(6_000);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                        return;
                    }
                    reply(exchange, 200, "{\"api_major\":1,\"status\":\"ok\"}");
                });
        server.createContext(
                "/v1/capabilities",
                exchange -> {
                    assertEquals(
                            "Bearer token-a",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    reply(exchange, 200, capabilities("deployment-a", 7).toString());
                });
        server.start();
        try {
            assertEquals(
                    ConnectionProbe.Status.CONNECTED,
                    probeStatus(storeFor(server, dir, "deployment-a", 7, "token-a")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void revokedCredentialIsNotConnected(@TempDir Path dir) throws Exception {
        HttpServer server = gatewayStub(new AtomicInteger(), 401, null);
        try {
            assertEquals(
                    ConnectionProbe.Status.AUTHENTICATION_REQUIRED,
                    probeStatus(storeFor(server, dir, "deployment-a", 7, "old")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oneSameDeploymentRotationIsReloadedAndRevalidated(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deployment.json");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String url = "http://localhost:" + server.getAddress().getPort();
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/health",
                exchange -> reply(exchange, 200, "{\"api_major\":1,\"status\":\"ok\"}"));
        server.createContext(
                "/v1/capabilities",
                exchange -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        assertEquals(
                                "Bearer old-token",
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        writeDeployment(file, url, "deployment-a", 8, "new-token");
                        reply(
                                exchange,
                                401,
                                "{\"schema_version\":1,"
                                        + "\"code\":\"AUTHENTICATION_REQUIRED\","
                                        + "\"message\":\"credential required\"}");
                    } else {
                        assertEquals(
                                "Bearer new-token",
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        reply(exchange, 200, capabilities("deployment-a", 8).toString());
                    }
                });
        server.start();
        writeDeployment(file, url, "deployment-a", 7, "old-token");
        try {
            assertEquals(ConnectionProbe.Status.CONNECTED, probeStatus(new DeploymentStore(file)));
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void replacementDeploymentAtTheSameGatewayIsReloadedAndRevalidated(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("deployment.json");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String url = "http://localhost:" + server.getAddress().getPort();
        AtomicInteger calls = new AtomicInteger();
        server.createContext(
                "/v1/health",
                exchange -> reply(exchange, 200, "{\"api_major\":1,\"status\":\"ok\"}"));
        server.createContext(
                "/v1/capabilities",
                exchange -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        assertEquals(
                                "Bearer old-token",
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        writeDeployment(file, url, "deployment-b", 1, "replacement-token");
                        reply(
                                exchange,
                                401,
                                "{\"schema_version\":1,"
                                        + "\"code\":\"AUTHENTICATION_REQUIRED\","
                                        + "\"message\":\"credential required\"}");
                    } else {
                        assertEquals(
                                "Bearer replacement-token",
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        reply(exchange, 200, capabilities("deployment-b", 1).toString());
                    }
                });
        server.start();
        writeDeployment(file, url, "deployment-a", 7, "old-token");
        try {
            assertEquals(ConnectionProbe.Status.CONNECTED, probeStatus(new DeploymentStore(file)));
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void credentialForAnotherEndpointRequiresRestartWithoutSendingItsToken(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("deployment.json");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String url = "http://localhost:" + server.getAddress().getPort();
        List<String> headers = new ArrayList<>();
        server.createContext(
                "/v1/health",
                exchange -> reply(exchange, 200, "{\"api_major\":1,\"status\":\"ok\"}"));
        server.createContext(
                "/v1/capabilities",
                exchange -> {
                    headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    if (headers.size() == 1) {
                        writeDeployment(
                                file, "https://other.example", "deployment-b", 1, "other-secret");
                    }
                    reply(
                            exchange,
                            401,
                            "{\"schema_version\":1,"
                                    + "\"code\":\"AUTHENTICATION_REQUIRED\","
                                    + "\"message\":\"credential required\"}");
                });
        server.start();
        writeDeployment(file, url, "deployment-a", 7, "old-token");
        try {
            assertEquals(
                    ConnectionProbe.Status.RESTART_REQUIRED,
                    probeStatus(new DeploymentStore(file)));
            assertEquals(List.of("Bearer old-token"), headers);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void alreadySharedClientFailsClosedAfterEndpointReplacement(@TempDir Path dir)
            throws Exception {
        AtomicInteger authenticatedCalls = new AtomicInteger();
        HttpServer server = gatewayStub(authenticatedCalls, null, null);
        Path file = dir.resolve("deployment.json");
        String oldUrl = "http://localhost:" + server.getAddress().getPort();
        writeDeployment(file, oldUrl, "deployment-a", 7, "old-token");
        DeploymentStore store = new DeploymentStore(file);
        ModuleGatewayClient shared = new ModuleGatewayClient(store);
        writeDeployment(file, "https://other.example", "deployment-b", 1, "other-secret");
        try {
            assertEquals(ConnectionProbe.Status.RESTART_REQUIRED, probeStatus(store, shared));
            assertEquals(0, authenticatedCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authenticatedCapabilityProbeNeverFollowsRedirect(@TempDir Path dir) throws Exception {
        List<String> receivedAuthorization = new ArrayList<>();
        HttpServer sink = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        sink.createContext(
                "/captured",
                exchange -> {
                    receivedAuthorization.add(
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    reply(exchange, 200, capabilities("deployment-a", 7).toString());
                });
        sink.start();

        HttpServer redirect = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        redirect.createContext(
                "/v1/health",
                exchange -> reply(exchange, 200, "{\"api_major\":1,\"status\":\"ok\"}"));
        redirect.createContext(
                "/v1/capabilities",
                exchange -> {
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "http://localhost:"
                                            + sink.getAddress().getPort()
                                            + "/captured");
                    reply(exchange, 302, "");
                });
        redirect.start();
        try {
            assertEquals(
                    ConnectionProbe.Status.INCOMPATIBLE,
                    probeStatus(storeFor(redirect, dir, "deployment-a", 7, "token-a")));
            assertEquals(List.of(), receivedAuthorization);
        } finally {
            redirect.stop(0);
            sink.stop(0);
        }
    }

    @Test
    void mismatchedDeploymentIsRejected(@TempDir Path dir) throws Exception {
        JsonObject wrongDeployment = capabilities("other", 7);
        HttpServer first = gatewayStub(new AtomicInteger(), null, wrongDeployment);
        try {
            assertEquals(
                    ConnectionProbe.Status.IDENTITY_MISMATCH,
                    probeStatus(storeFor(first, dir, "deployment-a", 7, "token-a")));
        } finally {
            first.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "finite_inference_schema_version, 1",
        "finite_inference_schema_version, 3",
        "durable_model_run_schema_version, 3",
        "durable_model_run_schema_version, 5"
    })
    void oldAndFutureInferenceSchemasCannotConnectOrInspect(
            String field, int version, @TempDir Path dir) throws Exception {
        JsonObject wrongSchema = capabilities("deployment-a", 7);
        wrongSchema.addProperty(field, version);
        HttpServer server = gatewayStub(new AtomicInteger(), null, wrongSchema);
        try {
            var result = ConnectionProbe.probe(storeFor(server, dir, "deployment-a", 7, "token-a"));
            assertEquals(ConnectionProbe.Status.INCOMPATIBLE, result.status());
            assertFalse(result.inspectionSupported());
            assertFalse(result.compatibility().orElseThrow().permitsWork());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedHealthIsIncompatibleAndUnavailableGatewayIsUnreachable(@TempDir Path dir)
            throws Exception {
        HttpServer malformed = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        malformed.createContext("/v1/health", exchange -> reply(exchange, 200, "{}"));
        malformed.start();
        try {
            assertEquals(
                    ConnectionProbe.Status.INCOMPATIBLE,
                    probeStatus(storeFor(malformed, dir, "deployment-a", 7, "token-a")));
        } finally {
            malformed.stop(0);
        }

        assertEquals(ConnectionProbe.Status.UNREACHABLE, probeStatus(storeForClosedPort(dir)));
    }

    @Test
    void validOldAndNewReleasesHaveDirectionalAdvice(@TempDir Path dir) throws Exception {
        var policy =
                new BackendCompatibility(
                        new ExtensionRelease("1.0.0", "0.10.0", "0.8.0", "unused.whl"));
        for (String version : List.of("0.7.0", "0.9.0", "0.10.0", "0.11.0")) {
            var body = capabilities("deployment-a", 7);
            body.getAsJsonObject("gateway_build").addProperty("version", version);
            HttpServer server = gatewayStub(new AtomicInteger(), null, body);
            try {
                var store = storeFor(server, dir, "deployment-a", 7, "token-a");
                var result = ConnectionProbe.probe(store, new ModuleGatewayClient(store), policy);
                assertEquals(
                        version.equals("0.7.0")
                                ? ConnectionProbe.Status.INCOMPATIBLE
                                : ConnectionProbe.Status.CONNECTED,
                        result.status());
                assertEquals(
                        version,
                        result.compatibility().orElseThrow().backendVersion().orElseThrow());
                assertTrue(result.inspectionSupported());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void malformedOlderAndNewerResponsesNeverRecommendDeployment(@TempDir Path dir)
            throws Exception {
        for (String version : List.of("0.0.1", "90.0.0", "0.2.0-rc1")) {
            var body = capabilities("deployment-a", 7);
            body.getAsJsonObject("gateway_build").addProperty("version", version);
            body.remove("limits");
            HttpServer server = gatewayStub(new AtomicInteger(), null, body);
            try {
                var result =
                        ConnectionProbe.probe(storeFor(server, dir, "deployment-a", 7, "token-a"));
                var decision = result.compatibility().orElseThrow();
                assertEquals(ConnectionProbe.Status.INCOMPATIBLE, result.status());
                assertEquals(BackendCompatibility.State.UNKNOWN, decision.state());
                assertEquals(version, decision.backendVersion().orElseThrow());
                assertFalse(decision.permitsCloudUpdate());
                assertFalse(decision.permitsWork());
                assertFalse(result.inspectionSupported());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void unknownReleaseWithIntactContractsStillAllowsInspection(@TempDir Path dir)
            throws Exception {
        var body = capabilities("deployment-a", 7);
        body.getAsJsonObject("gateway_build").addProperty("version", "development-build");
        HttpServer server = gatewayStub(new AtomicInteger(), null, body);
        try {
            var result = ConnectionProbe.probe(storeFor(server, dir, "deployment-a", 7, "token-a"));
            assertTrue(result.inspectionSupported());
            assertFalse(result.compatibility().orElseThrow().permitsWork());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unsupportedApiStillAuthenticatesAndContradictoryHealthRemainsUnknown(@TempDir Path dir)
            throws Exception {
        var body = capabilities("deployment-a", 7);
        body.addProperty("api_major", 2);
        body.getAsJsonObject("gateway_build").addProperty("version", "9.0.0");
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = gatewayStub(calls, null, body);
        try {
            var store = storeFor(server, dir, "deployment-a", 7, "token-a");
            assertEquals(
                    BackendCompatibility.State.UNKNOWN,
                    ConnectionProbe.probe(store).compatibility().orElseThrow().state());
            server.removeContext("/v1/health");
            server.createContext(
                    "/v1/health", ex -> reply(ex, 200, "{\"api_major\":2,\"status\":\"ok\"}"));
            var result = ConnectionProbe.probe(store);
            assertEquals(
                    BackendCompatibility.State.EXTENSION_UPDATE_REQUIRED,
                    result.compatibility().orElseThrow().state());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
        HttpServer rejected = gatewayStub(new AtomicInteger(), 401, null);
        try {
            rejected.removeContext("/v1/health");
            rejected.createContext(
                    "/v1/health", ex -> reply(ex, 200, "{\"api_major\":2,\"status\":\"ok\"}"));
            assertEquals(
                    ConnectionProbe.Status.AUTHENTICATION_REQUIRED,
                    ConnectionProbe.probe(storeFor(rejected, dir, "deployment-a", 7, "token-a"))
                            .status());
        } finally {
            rejected.stop(0);
        }
    }

    private static ConnectionProbe.Status probeStatus(DeploymentStore store) {
        return ConnectionProbe.probe(store).status();
    }

    private static ConnectionProbe.Status probeStatus(
            DeploymentStore store, ModuleGatewayClient client) {
        return ConnectionProbe.probe(store, client).status();
    }

    private static HttpServer gatewayStub(
            AtomicInteger authenticatedCalls, Integer capabilityStatus, JsonObject capabilityBody)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/health",
                exchange -> reply(exchange, 200, "{\"api_major\":1,\"status\":\"ok\"}"));
        server.createContext(
                "/v1/capabilities",
                exchange -> {
                    authenticatedCalls.incrementAndGet();
                    if (capabilityStatus != null) {
                        reply(
                                exchange,
                                capabilityStatus,
                                "{\"schema_version\":1,"
                                        + "\"code\":\"AUTHENTICATION_REQUIRED\","
                                        + "\"message\":\"credential required\"}");
                        return;
                    }
                    assertEquals(
                            "Bearer token-a",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    reply(
                            exchange,
                            200,
                            (capabilityBody == null
                                            ? capabilities("deployment-a", 7)
                                            : capabilityBody)
                                    .toString());
                });
        server.start();
        return server;
    }

    private static JsonObject capabilities(String deploymentId, long authRevision)
            throws IOException {
        JsonObject value =
                GatewayCapabilitiesCodec.toJson(
                        ModelGatewayExecutorTestData.capabilities(deploymentId));
        value.addProperty("auth_revision", authRevision);
        return value;
    }

    private static DeploymentStore storeFor(
            HttpServer server, Path dir, String deploymentId, long revision, String token)
            throws IOException {
        return storeForUrl(
                "http://localhost:" + server.getAddress().getPort(),
                dir,
                deploymentId,
                revision,
                token);
    }

    private static DeploymentStore storeForClosedPort(Path dir) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = server.getAddress().getPort();
        server.start();
        server.stop(0);
        return storeForUrl("http://localhost:" + port, dir, "deployment-a", 7, "token-a");
    }

    private static DeploymentStore storeForUrl(
            String url, Path dir, String deploymentId, long revision, String token)
            throws IOException {
        Path file = dir.resolve("deployment.json");
        writeDeployment(file, url, deploymentId, revision, token);
        return new DeploymentStore(file);
    }

    private static void writeDeployment(
            Path file, String url, String deploymentId, long revision, String token)
            throws IOException {
        Deployment deployment =
                new Deployment(
                        1,
                        deploymentId,
                        url,
                        "research-lab",
                        "histoforge-gateway",
                        revision,
                        token,
                        Instant.parse("2026-08-15T12:34:56.789Z"));
        Files.writeString(file, deployment.toJson().toString());
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
