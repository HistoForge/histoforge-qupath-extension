package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import qupath.ext.histoforge.client.dto.InstallRequest;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;

class BackendAdmissionTest {
    @ParameterizedTest
    @CsvSource({
        "finite_inference_schema_version, 1",
        "finite_inference_schema_version, 3",
        "durable_model_run_schema_version, 3",
        "durable_model_run_schema_version, 5"
    })
    void oldAndFutureInferenceSchemasRejectWorkBeforeMutation(
            String field, int version, @TempDir Path directory) throws Exception {
        var captured = ModelGatewayExecutorTestData.capabilities();
        var changed = GatewayCapabilitiesCodec.toJson(captured);
        changed.addProperty(field, version);
        var unsupported = GatewayCapabilitiesCodec.parseGatewayCapabilities(changed);
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ModelGatewayExecutorTestData.serveCapabilities(server, unsupported);
        AtomicInteger mutations = new AtomicInteger();
        server.createContext(
                "/v1",
                exchange -> {
                    mutations.incrementAndGet();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
        try {
            var transport =
                    new GatewayTransport(ModelGatewayExecutorTestData.storeFor(server, directory));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ModelGatewayExecutor(transport, unsupported));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DurableModelRunExecutor(transport, unsupported));
            assertThrows(
                    InferenceException.class,
                    () -> new BackendAdmission(transport, captured).require());
            assertThrows(
                    InferenceException.class,
                    () ->
                            new ModuleGatewayClient(transport)
                                    .submitInstall(new InstallRequest("owner/model", null)));
            assertEquals(0, mutations.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void staleSupportedCatalogCannotAuthorizeAChangedOrUnknownBackend(@TempDir Path directory)
            throws Exception {
        var captured = ModelGatewayExecutorTestData.capabilities();
        for (String version : List.of("0.0.0", "unknown", "0.10.0")) {
            var current = GatewayCapabilitiesCodec.toJson(captured);
            current.getAsJsonObject("gateway_build").addProperty("version", version);
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ModelGatewayExecutorTestData.serveHealth(server, captured.apiMajor());
            AtomicInteger mutations = new AtomicInteger();
            byte[] body = CanonicalCodec.canonicalBytes(current);
            server.createContext(
                    "/v1/capabilities",
                    exchange -> {
                        exchange.sendResponseHeaders(200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                    });
            server.createContext(
                    "/v1/install",
                    exchange -> {
                        mutations.incrementAndGet();
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                    });
            server.start();
            try {
                var transport =
                        new GatewayTransport(
                                ModelGatewayExecutorTestData.storeFor(server, directory));
                assertThrows(
                        InferenceException.class,
                        () -> new BackendAdmission(transport, captured).require());
                if (!version.equals("0.10.0")) {
                    assertThrows(
                            InferenceException.class,
                            () ->
                                    new ModuleGatewayClient(transport)
                                            .submitInstall(
                                                    new InstallRequest("owner/model", null)));
                    assertEquals(0, mutations.get());
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void contradictoryHealthCannotAdmitWorkAfterTheProbeReportsUnknown(@TempDir Path directory)
            throws Exception {
        var captured = ModelGatewayExecutorTestData.capabilities();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ModelGatewayExecutorTestData.serveCapabilities(server, captured);
        server.removeContext("/v1/health");
        ModelGatewayExecutorTestData.serveHealth(server, captured.apiMajor() + 1);
        AtomicInteger mutations = new AtomicInteger();
        server.createContext(
                "/v1/install",
                exchange -> {
                    mutations.incrementAndGet();
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
        try {
            var store = ModelGatewayExecutorTestData.storeFor(server, directory);
            var probe = ConnectionProbe.probe(store);
            assertEquals(
                    BackendCompatibility.State.UNKNOWN,
                    probe.compatibility().orElseThrow().state());
            var transport = new GatewayTransport(store);
            assertThrows(
                    InferenceException.class,
                    () -> new BackendAdmission(transport, captured).require());
            assertThrows(
                    InferenceException.class,
                    () ->
                            new ModuleGatewayClient(transport)
                                    .submitInstall(new InstallRequest("owner/model", null)));
            assertEquals(0, mutations.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void admissionIsOncePerCapturedActionNotPerInput(@TempDir Path directory) throws Exception {
        var captured = ModelGatewayExecutorTestData.capabilities();
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ModelGatewayExecutorTestData.serveHealth(server, captured.apiMajor());
        AtomicInteger calls = new AtomicInteger();
        byte[] body = CanonicalCodec.canonicalBytes(GatewayCapabilitiesCodec.toJson(captured));
        server.createContext(
                "/v1/capabilities",
                exchange -> {
                    calls.incrementAndGet();
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var transport =
                    new GatewayTransport(ModelGatewayExecutorTestData.storeFor(server, directory));
            var admission = new BackendAdmission(transport, captured);
            admission.require();
            admission.require();
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oldReleaseCanStillBeInspectedAndCancelledWithoutAReleaseAdmission(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            var old = GatewayCapabilitiesCodec.toJson(ModelGatewayExecutorTestData.capabilities());
            old.getAsJsonObject("gateway_build").addProperty("version", "0.0.0");
            var transport =
                    new GatewayTransport(
                            ModelGatewayExecutorTestData.storeFor(gateway.server(), directory));
            var executor =
                    new DurableModelRunExecutor(
                            transport, GatewayCapabilitiesCodec.parseGatewayCapabilities(old));
            var handle =
                    executor.adopt(
                            gateway.request(),
                            gateway.runnerCapabilities(),
                            java.util.Optional.empty(),
                            gateway.declaredClasses());
            executor.poll(handle);
            executor.cancel(handle);
            assertEquals(1, gateway.pollCalls());
            assertEquals(1, gateway.cancelCalls());
            assertEquals(0, gateway.postCalls());
            gateway.requireNoFailure();
        }
    }
}
