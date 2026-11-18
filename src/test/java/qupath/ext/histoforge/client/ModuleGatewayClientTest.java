package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.client.dto.HuggingFaceVisibility;
import qupath.ext.histoforge.client.dto.InstallRequest;
import qupath.ext.histoforge.client.dto.PublishRequest;
import qupath.ext.histoforge.contract.AdminJobCodec.AdminJobResult;
import qupath.ext.histoforge.contract.AdminJobCodec.AdminJobStatus;
import qupath.ext.histoforge.contract.AdminJobCodec.JobPhase;
import qupath.ext.histoforge.contract.AdminJobCodec.JobType;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.ModuleCatalogCodec;
import qupath.ext.histoforge.modules.InstalledModule;

/** Tests the jobs + modules API on {@link ModuleGatewayClient} against a local stub gateway. */
class ModuleGatewayClientTest {

    @Test
    void missingPrecisionContractPreservesUpdateGuidance(@TempDir Path dir) throws Exception {
        var page = fixture("/contracts/module-catalog-v1.json").getAsJsonObject("page");
        page.getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("models")
                .getAsJsonObject("cells")
                .remove("precision_overrides");
        HttpServer server = stub("/v1/modules", 200, page.toString());
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            var error = assertThrows(InferenceException.class, client::captureModuleCatalog);
            assertTrue(error.getMessage().contains("Update cloud backend in Settings"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void changedEndpointStopsCapturedTransportBeforeSending(@TempDir Path dir) throws Exception {
        AtomicInteger originalRequests = new AtomicInteger();
        AtomicInteger changedRequests = new AtomicInteger();
        HttpServer originalServer =
                stubHandler(
                        "/v1/jobs",
                        exchange -> {
                            originalRequests.incrementAndGet();
                            reply(exchange, 200, "{\"jobs\":[]}");
                        });
        HttpServer changedServer =
                stubHandler(
                        "/v1/jobs",
                        exchange -> {
                            changedRequests.incrementAndGet();
                            reply(exchange, 202, "{\"call_id\":\"unexpected\"}");
                        });
        Path file = dir.resolve("deployment.json");
        String original = "http://localhost:" + originalServer.getAddress().getPort();
        Files.writeString(file, deployment(original, "deployment-a", 1, "old").toJson().toString());
        GatewayTransport transport = new GatewayTransport(new DeploymentStore(file));
        ModuleGatewayClient moduleClient = new ModuleGatewayClient(transport);
        String changed = "http://localhost:" + changedServer.getAddress().getPort();
        Files.writeString(
                file, deployment(changed, "deployment-b", 1, "other").toJson().toString());
        try {
            GatewayTransport.DeploymentChangedException moduleError =
                    assertThrows(
                            GatewayTransport.DeploymentChangedException.class,
                            moduleClient::listJobs);
            assertTrue(moduleError.getMessage().contains("restart QuPath"));
            assertEquals(0, originalRequests.get());
            assertEquals(0, changedRequests.get());
        } finally {
            originalServer.stop(0);
            changedServer.stop(0);
        }
    }

    @Test
    void submitPublishReturnsJobId(@TempDir Path dir) throws Exception {
        String id = "11111111-1111-4111-8111-111111111111";
        HttpServer server = stub("/v1/publish", 202, "{\"job_id\":\"" + id + "\"}");
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            assertEquals(id, client.submitPublish(smokeReq()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitPublishRequiresExactAcceptedStatus(@TempDir Path dir) throws Exception {
        String id = "11111111-1111-4111-8111-111111111111";
        HttpServer server = stub("/v1/publish", 200, "{\"job_id\":\"" + id + "\"}");
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            assertThrows(InferenceException.class, () -> client.submitPublish(smokeReq()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitPublishSerializesSnakeCaseKeys(@TempDir Path dir) throws Exception {
        // Capture the body the client sends so we can verify the DTO -> JSON mapping.
        final StringBuilder captured = new StringBuilder();
        HttpServer server =
                stubHandler(
                        "/v1/publish",
                        ex -> {
                            captured.append(
                                    new String(
                                            ex.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            reply(
                                    ex,
                                    202,
                                    "{\"job_id\":" + "\"11111111-1111-4111-8111-111111111111\"}");
                        });
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            client.submitPublish(smokeReq());
            var body = JsonParser.parseString(captured.toString()).getAsJsonObject();
            assertEquals(
                    Set.of(
                            "source_module",
                            "target_repository",
                            "visibility",
                            "card_markdown",
                            "sync_locally"),
                    body.keySet());
            assertEquals("PRIVATE", body.get("visibility").getAsString());
            assertFalse(captured.toString().contains("token"), captured.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitInstallOmitsAbsentRevision(@TempDir Path dir) throws Exception {
        StringBuilder captured = new StringBuilder();
        HttpServer server =
                stubHandler(
                        "/v1/install",
                        ex -> {
                            captured.append(
                                    new String(
                                            ex.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            reply(
                                    ex,
                                    202,
                                    "{\"job_id\":" + "\"22222222-2222-4222-8222-222222222222\"}");
                        });
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            assertEquals(
                    "22222222-2222-4222-8222-222222222222",
                    client.submitInstall(new InstallRequest("someone/x", null)));
            var body = JsonParser.parseString(captured.toString()).getAsJsonObject();
            assertEquals(Set.of("module_ref"), body.keySet());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitInstallReturnsJobId(@TempDir Path dir) throws Exception {
        StringBuilder captured = new StringBuilder();
        HttpServer server =
                stubHandler(
                        "/v1/install",
                        ex -> {
                            captured.append(
                                    new String(
                                            ex.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            reply(
                                    ex,
                                    202,
                                    "{\"job_id\":" + "\"33333333-3333-4333-8333-333333333333\"}");
                        });
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            String jobId = client.submitInstall(new InstallRequest("someone/x", "main"));
            assertEquals("33333333-3333-4333-8333-333333333333", jobId);
            var body = JsonParser.parseString(captured.toString()).getAsJsonObject();
            assertEquals(Set.of("module_ref", "hugging_face_revision"), body.keySet());
            assertFalse(captured.toString().contains("token"), captured.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pollJobStateParsesTerminal(@TempDir Path dir) throws Exception {
        HttpServer server =
                stub(
                        "/v1/jobs/33333333-3333-4333-8333-333333333333",
                        200,
                        "{\"job_id\":\"33333333-3333-4333-8333-333333333333\","
                                + "\"type\":\"PUBLISH\","
                                + "\"requested_module_ref\":\"alice/leydig\","
                                + "\"phase\":\"SUCCEEDED\","
                                + "\"updated_at\":\"2026-07-20T00:01:00.000Z\","
                                + "\"result\":{\"type\":\"published\","
                                + "\"target_repository\":\"alice/leydig\","
                                + "\"commit_sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                                + "\"local_sync\":{\"type\":\"not-requested\"}}}");
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            AdminJobStatus info = client.pollJobState("33333333-3333-4333-8333-333333333333");
            assertEquals("33333333-3333-4333-8333-333333333333", info.jobId());
            assertEquals(JobType.PUBLISH, info.type());
            assertEquals("alice/leydig", info.requestedModuleRef().toString());
            assertTrue(info.isTerminal());
            assertEquals(JobPhase.SUCCEEDED, info.phase());
            AdminJobResult.Published result =
                    (AdminJobResult.Published) info.result().orElseThrow();
            assertEquals("a".repeat(40), result.commitSha());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listJobsReturnsList(@TempDir Path dir) throws Exception {
        HttpServer server =
                stub(
                        "/v1/jobs",
                        200,
                        "{\"jobs\":[{\"job_id\":"
                                + "\"11111111-1111-4111-8111-111111111111\","
                                + "\"type\":\"INSTALL\",\"requested_module_ref\":\"x/y\","
                                + "\"phase\":\"QUEUED\","
                                + "\"updated_at\":\"2026-07-20T00:02:00.000Z\"}]}");
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            List<AdminJobStatus> jobs = client.listJobs();
            assertEquals(1, jobs.size());
            assertEquals("11111111-1111-4111-8111-111111111111", jobs.get(0).jobId());
            assertEquals(JobPhase.QUEUED, jobs.get(0).phase());
            assertFalse(jobs.get(0).isTerminal());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deleteJobRemovesEntry(@TempDir Path dir) throws Exception {
        String id = "44444444-4444-4444-8444-444444444444";
        HttpServer server =
                stubHandler(
                        "/v1/jobs/" + id,
                        ex -> {
                            ex.sendResponseHeaders(204, -1);
                            ex.close();
                        });
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            // 204 No Content — must not throw.
            client.deleteJob(id);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pathIdentifiersAreValidatedBeforeNetwork(@TempDir Path dir) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server =
                stubHandler(
                        "/v1/",
                        exchange -> {
                            requests.incrementAndGet();
                            reply(exchange, 500, "unexpected");
                        });
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            assertThrows(IllegalArgumentException.class, () -> client.deleteModule("x/.."));
            assertThrows(IllegalArgumentException.class, () -> client.pollJobState("not-a-uuid"));
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listInstalledReturnsList(@TempDir Path dir) throws Exception {
        var capabilities =
                GatewayCapabilitiesCodec.toJson(ModelGatewayExecutorTestData.capabilities());
        var firstPage =
                fixture("/contracts/module-catalog-v1.json").getAsJsonObject("page").deepCopy();
        List<String> queries = new ArrayList<>();
        AtomicInteger pageNumber = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/capabilities", exchange -> reply(exchange, 200, capabilities.toString()));
        server.createContext(
                "/v1/modules",
                exchange -> {
                    queries.add(exchange.getRequestURI().getRawQuery());
                    reply(
                            exchange,
                            200,
                            pageNumber.getAndIncrement() == 0
                                    ? firstPage.toString()
                                    : "{\"modules\":[]}");
                });
        server.start();
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            List<InstalledModule> modules = client.listInstalled();
            assertEquals(1, modules.size());
            assertEquals("owner/catalog", modules.getFirst().ref());
            assertEquals(879L, modules.getFirst().sizeBytes());
            assertEquals("Catalog Fixture", modules.getFirst().recipe().name());
            assertTrue(modules.getFirst().scripts().containsKey("metrics.groovy"));
            assertEquals(List.of("limit=100", "limit=100&cursor=b3duZXIvY2F0YWxvZw"), queries);

            pageNumber.set(0);
            ModuleGatewayClient.CatalogCapture capture = client.captureModuleCatalog();
            assertEquals("deployment-a", capture.deploymentId());
            assertEquals(1, capture.modules().size());
            assertEquals(
                    "owner/catalog",
                    capture.modules().getFirst().identity().moduleRef().toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void catalogReadEnforcesNegotiatedEncodedByteLimit(@TempDir Path dir) throws Exception {
        var capabilities =
                GatewayCapabilitiesCodec.toJson(ModelGatewayExecutorTestData.capabilities());
        capabilities.getAsJsonObject("limits").addProperty("max_catalog_encoded_bytes", 32);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/capabilities", exchange -> reply(exchange, 200, capabilities.toString()));
        server.createContext("/v1/modules", exchange -> reply(exchange, 200, "x".repeat(33)));
        server.start();
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            IOException error = assertThrows(IOException.class, client::listModuleDescriptors);
            assertTrue(error.getMessage().contains("negotiated byte limit"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void moduleTrainingReportUsesCapturedIdentityAndNegotiatedLimit(@TempDir Path dir)
            throws Exception {
        InstalledModule module = fixtureModule(null);
        var fixture = fixture("/contracts/module-training-report-v1.json");
        var response = fixture.getAsJsonObject("response").deepCopy();
        response.add(
                "module_identity",
                qupath.ext.histoforge.contract.InferenceIdentityCodec.toJson(
                        module.descriptor().identity()));
        List<String> queries = new ArrayList<>();
        HttpServer server =
                stubHandler(
                        "/v1/modules/owner/catalog/training-report",
                        exchange -> {
                            queries.add(exchange.getRequestURI().getRawQuery());
                            reply(exchange, 200, response.toString());
                        });
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            var report = client.moduleTrainingReport(module);

            assertEquals("Fine-tuned Leydig workflow", report.report().resultDisplayName());
            assertEquals(module.descriptor().identity(), report.moduleIdentity());
            assertEquals(
                    List.of(
                            "expected_content_digest="
                                    + module.descriptor().identity().contentDigest()),
                    queries);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void moduleTrainingReportRejectsAChangedIdentity(@TempDir Path dir) throws Exception {
        InstalledModule module = fixtureModule(null);
        String response =
                fixture("/contracts/module-training-report-v1.json")
                        .getAsJsonObject("response")
                        .toString();
        HttpServer server = stub("/v1/modules/owner/catalog/training-report", 200, response);
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            InferenceException error =
                    assertThrows(
                            InferenceException.class, () -> client.moduleTrainingReport(module));
            assertTrue(error.getMessage().contains("invalid response"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void moduleTrainingReportEnforcesNegotiatedResponseBytes(@TempDir Path dir) throws Exception {
        InstalledModule module = fixtureModule(128);
        HttpServer server =
                stub("/v1/modules/owner/catalog/training-report", 200, "x".repeat(128 + 1_024 + 1));
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            IOException error =
                    assertThrows(IOException.class, () -> client.moduleTrainingReport(module));
            assertTrue(error.getMessage().contains("negotiated byte limit"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitPublishThrowsOnNon2xx(@TempDir Path dir) throws Exception {
        HttpServer server =
                stub(
                        "/v1/publish",
                        422,
                        "{\"schema_version\":1,\"code\":\"VALIDATION_ERROR\","
                                + "\"message\":\"bad request\"}");
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            InferenceException ex =
                    assertThrows(InferenceException.class, () -> client.submitPublish(smokeReq()));
            assertNotNull(ex.getMessage());
            assertEquals(
                    GatewayErrorCodec.Code.VALIDATION_ERROR,
                    ex.gatewayError().orElseThrow().code());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void adminFailuresRejectMalformedErrorBodiesWithoutRawFallback(@TempDir Path dir)
            throws Exception {
        HttpServer server = stub("/v1/publish", 500, "credential=secret");
        try {
            ModuleGatewayClient client = new ModuleGatewayClient(storeFor(server, dir));
            InferenceException error =
                    assertThrows(InferenceException.class, () -> client.submitPublish(smokeReq()));
            assertEquals(500, error.statusCode());
            assertTrue(error.gatewayError().isEmpty());
            assertFalse(error.getMessage().contains("secret"));
            assertTrue(error.getMessage().contains("invalid typed gateway error response"));
        } finally {
            server.stop(0);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static PublishRequest smokeReq() {
        return new PublishRequest(
                "histoforge/leydig_wholecell",
                "alice/leydig_wholecell",
                HuggingFaceVisibility.PRIVATE,
                "---\nlicense: apache-2.0\n---\n# card\n",
                false);
    }

    private static HttpServer stub(String path, int code, String body) throws IOException {
        return stubHandler(path, ex -> reply(ex, code, body));
    }

    private static HttpServer stubHandler(String path, HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ModelGatewayExecutorTestData.serveCapabilities(
                server, ModelGatewayExecutorTestData.capabilities());
        server.createContext(path, handler);
        server.start();
        return server;
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static com.google.gson.JsonObject fixture(String path) throws IOException {
        try (var input = ModuleGatewayClientTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing fixture " + path);
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static InstalledModule fixtureModule(Integer maxReportBytes) throws IOException {
        var capabilities =
                GatewayCapabilitiesCodec.toJson(ModelGatewayExecutorTestData.capabilities());
        if (maxReportBytes != null) {
            capabilities
                    .getAsJsonObject("training_api_limits")
                    .getAsJsonObject("report")
                    .addProperty("max_report_bytes", maxReportBytes);
        }
        var gateway = GatewayCapabilitiesCodec.parseGatewayCapabilities(capabilities);
        var page = fixture("/contracts/module-catalog-v1.json").getAsJsonObject("page");
        var descriptor =
                ModuleCatalogCodec.parse(
                                CanonicalCodec.canonicalTree(page),
                                gateway.trainingApiLimits().report().maxTextBytes())
                        .modules()
                        .getFirst();
        var recipe = descriptor.recipe();
        recipe.addProperty(
                "trainingReportDigest",
                fixture("/contracts/module-training-report-v1.json")
                        .get("report_digest")
                        .getAsString());
        var identity =
                new qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity(
                        descriptor.identity().moduleRef(),
                        descriptor.identity().resolvedCommitSha(),
                        CanonicalCodec.canonicalDigest(recipe),
                        "9".repeat(64));
        var trainedDescriptor =
                new qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor(
                        identity,
                        descriptor.installedAt(),
                        descriptor.installedModuleFileBytes(),
                        recipe,
                        descriptor.scripts(),
                        descriptor.scriptSetDigest(),
                        descriptor.models(),
                        descriptor.readmeMarkdown());
        return new InstalledModule(gateway, trainedDescriptor);
    }

    private static DeploymentStore storeFor(HttpServer server, Path dir) throws IOException {
        Path file = dir.resolve("deployment.json");
        String url = "http://localhost:" + server.getAddress().getPort();
        Files.writeString(file, deployment(url, "deployment-a", 1, "t").toJson().toString());
        return new DeploymentStore(file);
    }

    private static Deployment deployment(
            String url, String deploymentId, long revision, String token) {
        return new Deployment(
                1,
                deploymentId,
                url,
                "research-lab",
                "histoforge-gateway",
                revision,
                token,
                Instant.parse("2026-08-15T12:34:56.789Z"));
    }
}
