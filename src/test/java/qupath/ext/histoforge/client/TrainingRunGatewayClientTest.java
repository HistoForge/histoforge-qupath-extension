package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingRunCodec;
import qupath.ext.histoforge.contract.TrainingRunPageCodec;
import qupath.ext.histoforge.contract.TrainingRunReportCodec;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunRecord;

class TrainingRunGatewayClientTest {

    private static final Instant CREATED = Instant.parse("2026-09-04T12:00:00.000Z");

    @Test
    void completeRunHttpFlowBindsRequestsRoutesAndReports(@TempDir Path directory)
            throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        Map<String, JsonObject> recordValues = recordValues(fixture);
        TrainingRunRecord queued = TrainingRunCodec.parseRecord(recordValues.get("queued"), limits);
        TrainingRunRecord succeeded =
                TrainingRunCodec.parseRecord(recordValues.get("succeeded"), limits);
        JsonObject cancelled = cancelledFrom(queued, limits);
        JsonObject report = reportResponse(fixture);
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs",
                exchange -> {
                    try {
                        handle(exchange, fixture, queued, succeeded, cancelled, report, limits);
                    } catch (Throwable error) {
                        handlerFailure.compareAndSet(null, error);
                        exchange.close();
                    }
                });
        server.start();
        try {
            TrainingRunGatewayClient client = client(server, directory);
            assertEquals(
                    TrainingRunCodec.parseAccepted(
                            fixture.getAsJsonObject("accepted").get("value")),
                    client.submit(queued.request()));
            assertEquals(
                    TrainingRunPageCodec.parsePage(
                            fixture.getAsJsonObject("page").get("value"), limits),
                    client.page(3, Optional.of("cursor +/1")));
            assertEquals(queued, client.get(queued.runId()));
            assertEquals(
                    TrainingRunCodec.parseRecord(cancelled, limits), client.cancel(queued.runId()));
            assertEquals(
                    TrainingRunReportCodec.validateForRun(succeeded, report, limits),
                    client.report(succeeded));
            client.clear(succeeded.runId());
            assertNull(handlerFailure.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitFollowsOnlyTheSameModalResultUrlWithAuthenticatedGets(@TempDir Path directory)
            throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        TrainingRunRecord queued =
                TrainingRunCodec.parseRecord(recordValues(fixture).get("queued"), limits);
        var calls = new AtomicInteger();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs",
                exchange -> {
                    try {
                        int call = calls.getAndIncrement();
                        assertEquals(
                                "Bearer token",
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        if (call == 0) {
                            assertEquals("POST", exchange.getRequestMethod());
                            assertEquals(
                                    TrainingRunRequestCodec.toJson(queued.request(), limits),
                                    JsonParser.parseString(read(exchange)).getAsJsonObject());
                        } else {
                            assertEquals("GET", exchange.getRequestMethod());
                            assertEquals(
                                    "__modal_attempt_token=attempt-1",
                                    exchange.getRequestURI().getRawQuery());
                            assertEquals("", read(exchange));
                        }
                        if (call < 2) {
                            exchange.getResponseHeaders()
                                    .add(
                                            "Location",
                                            "/v1/training/runs?__modal_attempt_token=attempt-1");
                            exchange.sendResponseHeaders(303, -1);
                            exchange.close();
                            return;
                        }
                        reply(
                                exchange,
                                202,
                                fixture.getAsJsonObject("accepted").get("value").toString());
                    } catch (Throwable error) {
                        handlerFailure.compareAndSet(null, error);
                        exchange.close();
                    }
                });
        server.start();
        try {
            assertEquals(
                    TrainingRunCodec.parseAccepted(
                            fixture.getAsJsonObject("accepted").get("value")),
                    client(server, directory).submit(queued.request()));
            assertEquals(3, calls.get());
            assertNull(handlerFailure.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitDecodesProviderCreditFailureFromTheSharedContract(@TempDir Path directory)
            throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject runs = fixture("/contracts/training-runs-v1.json");
        TrainingRunRecord queued =
                TrainingRunCodec.parseRecord(recordValues(runs).get("queued"), limits);
        JsonObject value = null;
        for (var item : fixture("/contracts/gateway-errors-v1.json").getAsJsonArray("validCases")) {
            JsonObject testCase = item.getAsJsonObject();
            if (testCase.get("name").getAsString().equals("provider-credit-exhausted")) {
                value = testCase.getAsJsonObject("value");
                break;
            }
        }
        if (value == null) throw new AssertionError("missing provider-credit fixture");
        JsonObject creditError = value;
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs", exchange -> reply(exchange, 402, creditError.toString()));
        server.start();
        try {
            InferenceException failure =
                    assertThrows(
                            InferenceException.class,
                            () -> client(server, directory).submit(queued.request()));

            assertEquals(402, failure.statusCode());
            assertEquals(
                    GatewayErrorCodec.Code.PROVIDER_CREDIT_EXHAUSTED,
                    failure.gatewayError().orElseThrow().code());
            assertEquals(
                    creditError.get("message").getAsString(),
                    failure.gatewayError().orElseThrow().message());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submitRejectsLocationsThatAreNotTheExactModalResultUrl(@TempDir Path directory)
            throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        TrainingRunRecord queued =
                TrainingRunCodec.parseRecord(recordValues(fixture).get("queued"), limits);
        List<String> locations =
                List.of(
                        "https://example.com/v1/training/runs?__modal_attempt_token=attempt-1",
                        "/v1/training/other?__modal_attempt_token=attempt-1",
                        "/v1/training/runs?unexpected=attempt-1",
                        "/v1/training/runs?__modal_attempt_token=",
                        "/v1/training/runs?__modal_attempt_token=attempt-1&extra=value",
                        "/v1/training/runs?__modal_attempt_token=attempt-1#fragment");

        for (String location : locations) {
            var calls = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext(
                    "/v1/training/runs",
                    exchange -> {
                        calls.incrementAndGet();
                        exchange.getResponseHeaders().add("Location", location);
                        exchange.sendResponseHeaders(303, -1);
                        exchange.close();
                    });
            server.start();
            try {
                assertThrows(
                        IOException.class,
                        () -> client(server, directory).submit(queued.request()),
                        location);
                assertEquals(1, calls.get(), location);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void submitRequiresExactlyOneModalResultLocationHeader(@TempDir Path directory)
            throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        TrainingRunRecord queued =
                TrainingRunCodec.parseRecord(recordValues(fixture).get("queued"), limits);

        for (int locationCount : List.of(0, 2)) {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext(
                    "/v1/training/runs",
                    exchange -> {
                        for (int index = 0; index < locationCount; index++) {
                            exchange.getResponseHeaders()
                                    .add(
                                            "Location",
                                            "/v1/training/runs?__modal_attempt_token=attempt-1");
                        }
                        exchange.sendResponseHeaders(303, -1);
                        exchange.close();
                    });
            server.start();
            try {
                assertThrows(
                        IOException.class,
                        () -> client(server, directory).submit(queued.request()));
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void modalResultDeadlineIncludesTheTerminalResponseBody(@TempDir Path directory)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs",
                exchange -> {
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(202, body.length);
                    try {
                        Thread.sleep(2_000);
                        exchange.getResponseBody().write(body);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            GatewayTransport transport = transport(server, directory);
            assertThrows(
                    HttpTimeoutException.class,
                    () ->
                            transport.sendModalResultPostForDeployment(
                                    "deployment-2026-08-12-a",
                                    "/training/runs",
                                    "{}".getBytes(StandardCharsets.UTF_8),
                                    Duration.ofMillis(250)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void modalResultDeadlineIsRecomputedForAuthenticationRetry(@TempDir Path directory)
            throws Exception {
        var calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs",
                exchange -> {
                    int call = calls.getAndIncrement();
                    try {
                        Thread.sleep(350);
                        reply(exchange, call == 0 ? 401 : 202, call == 0 ? "" : "{}");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        exchange.close();
                    }
                });
        server.start();
        try {
            GatewayTransport transport = transport(server, directory);
            assertThrows(
                    HttpTimeoutException.class,
                    () ->
                            transport.sendModalResultPostForDeployment(
                                    "deployment-2026-08-12-a",
                                    "/training/runs",
                                    "{}".getBytes(StandardCharsets.UTF_8),
                                    Duration.ofMillis(500)));
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void getRejectsAValidRecordForAnotherRun(@TempDir Path directory) throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        Map<String, JsonObject> records = recordValues(fixture);
        TrainingRunRecord queued = TrainingRunCodec.parseRecord(records.get("queued"), limits);
        JsonObject other = records.get("failed");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs/" + queued.runId(),
                exchange -> reply(exchange, 200, other.toString()));
        server.start();
        try {
            assertThrows(
                    InferenceException.class, () -> client(server, directory).get(queued.runId()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pageRejectsMoreRowsThanTheRequestedLimit(@TempDir Path directory) throws Exception {
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs",
                exchange ->
                        reply(
                                exchange,
                                200,
                                fixture.getAsJsonObject("page").get("value").toString()));
        server.start();
        try {
            TrainingRunGatewayClient client = client(server, directory);
            assertThrows(InferenceException.class, () -> client.page(1, Optional.empty()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listFollowsEveryPageAndStartsFreshOnEachRefresh(@TempDir Path directory) throws Exception {
        JsonArray items =
                fixture("/contracts/training-runs-v1.json")
                        .getAsJsonObject("page")
                        .getAsJsonObject("value")
                        .getAsJsonArray("items");
        var queries = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs",
                exchange -> {
                    String query = exchange.getRequestURI().getRawQuery();
                    queries.add(query);
                    boolean first = !query.contains("cursor=");
                    var page = new JsonObject();
                    var rows = new JsonArray();
                    rows.add(items.get(first ? 0 : 2));
                    if (first) {
                        rows.add(items.get(1));
                        page.addProperty("next_cursor", "next page +/1");
                    }
                    page.add("items", rows);
                    reply(exchange, 200, page.toString());
                });
        server.start();
        try {
            var client = client(server, directory);
            assertEquals(3, client.listRuns().size());
            assertEquals(3, client.listRuns().size());
            String initial = "limit=" + client.limits().maxPageSize();
            String next = initial + "&cursor=next%20page%20%2B%2F1";
            assertEquals(List.of(initial, next, initial, next), queries);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listRejectsRepeatedIdentitiesOrContinuationTokens(@TempDir Path directory)
            throws Exception {
        JsonArray items =
                fixture("/contracts/training-runs-v1.json")
                        .getAsJsonObject("page")
                        .getAsJsonObject("value")
                        .getAsJsonArray("items");
        for (boolean repeatIdentity : List.of(true, false)) {
            var calls = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext(
                    "/v1/training/runs",
                    exchange -> {
                        int call = calls.getAndIncrement();
                        var page = new JsonObject();
                        var rows = new JsonArray();
                        rows.add(items.get(repeatIdentity ? 0 : Math.min(call, 2)));
                        page.add("items", rows);
                        page.addProperty("next_cursor", repeatIdentity ? "next-" + call : "same");
                        reply(exchange, 200, page.toString());
                    });
            server.start();
            try {
                var client = client(server, directory);
                assertThrows(InferenceException.class, client::listRuns);
                assertEquals(2, calls.get());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void cancelRejectsANonCancelledSuccessRecord(@TempDir Path directory) throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        TrainingRunRecord queued =
                TrainingRunCodec.parseRecord(recordValues(fixture).get("queued"), limits);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs/" + queued.runId() + "/cancel",
                exchange ->
                        reply(exchange, 200, TrainingRunCodec.toJson(queued, limits).toString()));
        server.start();
        try {
            TrainingRunGatewayClient client = client(server, directory);
            assertThrows(InferenceException.class, () -> client.cancel(queued.runId()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pageAndReportEnforceTheirNegotiatedSuccessByteLimits(@TempDir Path directory)
            throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        TrainingRunRecord succeeded =
                TrainingRunCodec.parseRecord(recordValues(fixture).get("succeeded"), limits);
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs",
                exchange -> {
                    boolean report = exchange.getRequestURI().getPath().endsWith("/report");
                    long ceiling =
                            report
                                    ? Math.addExact(limits.report().maxReportBytes(), 1_024)
                                    : limits.maxPageEncodedBytes();
                    reply(exchange, 200, "x".repeat(Math.toIntExact(ceiling + 1)));
                });
        server.start();
        try {
            TrainingRunGatewayClient client = client(server, directory);
            assertThrows(IOException.class, () -> client.page(1, Optional.empty()));
            assertThrows(IOException.class, () -> client.report(succeeded));
        } finally {
            server.stop(0);
        }
    }

    private static void handle(
            HttpExchange exchange,
            JsonObject fixture,
            TrainingRunRecord queued,
            TrainingRunRecord succeeded,
            JsonObject cancelled,
            JsonObject report,
            TrainingApiLimits limits)
            throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (method.equals("POST") && path.equals("/v1/training/runs")) {
            assertEquals(
                    TrainingRunRequestCodec.toJson(queued.request(), limits),
                    JsonParser.parseString(read(exchange)).getAsJsonObject());
            reply(exchange, 202, fixture.getAsJsonObject("accepted").get("value").toString());
            return;
        }
        if (method.equals("GET") && path.equals("/v1/training/runs")) {
            assertEquals("limit=3&cursor=cursor%20%2B%2F1", exchange.getRequestURI().getRawQuery());
            reply(exchange, 200, fixture.getAsJsonObject("page").get("value").toString());
            return;
        }
        if (method.equals("GET") && path.equals("/v1/training/runs/" + queued.runId())) {
            reply(exchange, 200, TrainingRunCodec.toJson(queued, limits).toString());
            return;
        }
        if (method.equals("POST")
                && path.equals("/v1/training/runs/" + queued.runId() + "/cancel")) {
            assertEquals("", read(exchange));
            reply(exchange, 200, cancelled.toString());
            return;
        }
        if (method.equals("GET")
                && path.equals("/v1/training/runs/" + succeeded.runId() + "/report")) {
            reply(exchange, 200, report.toString());
            return;
        }
        if (method.equals("DELETE") && path.equals("/v1/training/runs/" + succeeded.runId())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        reply(exchange, 404, "{}");
    }

    private static JsonObject cancelledFrom(TrainingRunRecord queued, TrainingApiLimits limits) {
        JsonObject value = TrainingRunCodec.toJson(queued, limits);
        value.addProperty("state", "CANCELLED");
        value.addProperty("report_revision", queued.reportRevision() + 1);
        value.addProperty("updated_at", "2026-09-04T12:01:00.000Z");
        JsonObject result = new JsonObject();
        result.addProperty("type", "cancelled");
        result.addProperty("report_digest", "f".repeat(64));
        value.add("result", result);
        return value;
    }

    private static JsonObject reportResponse(JsonObject runFixture) throws Exception {
        JsonObject metadata = runFixture.getAsJsonObject("report_response");
        JsonObject moduleReport =
                fixture("/contracts/module-training-report-v1.json")
                        .getAsJsonObject("response")
                        .getAsJsonObject("report")
                        .deepCopy();
        JsonObject response = new JsonObject();
        response.addProperty("report_revision", metadata.get("report_revision").getAsInt());
        response.addProperty("report_digest", metadata.get("report_digest").getAsString());
        response.add("report", moduleReport);
        return response;
    }

    private static Map<String, JsonObject> recordValues(JsonObject fixture) {
        Map<String, JsonObject> records = new LinkedHashMap<>();
        fixture.getAsJsonArray("records")
                .forEach(
                        element -> {
                            JsonObject item = element.getAsJsonObject();
                            records.put(
                                    item.get("name").getAsString(), item.getAsJsonObject("value"));
                        });
        return records;
    }

    private static TrainingRunGatewayClient client(HttpServer server, Path directory)
            throws IOException {
        ModelGatewayExecutorTestData.serveCapabilities(server, capabilities());
        return new TrainingRunGatewayClient(transport(server, directory), capabilities());
    }

    private static GatewayTransport transport(HttpServer server, Path directory)
            throws IOException {
        Path file = directory.resolve("deployment.json");
        String url = "http://localhost:" + server.getAddress().getPort();
        Deployment deployment =
                new Deployment(
                        1,
                        "deployment-2026-08-12-a",
                        url,
                        "research-lab",
                        "histoforge-gateway",
                        capabilities().authRevision(),
                        "token",
                        CREATED);
        Files.writeString(file, deployment.toJson().toString());
        return new GatewayTransport(new DeploymentStore(file));
    }

    private static TrainingApiLimits limits() throws Exception {
        return TrainingLimitsCodec.parse(
                fixture("/contracts/training-datasets-v1.json").get("trainingApiLimits"));
    }

    private static GatewayCapabilities capabilities() throws IOException {
        return ModelGatewayExecutorTestData.capabilities("deployment-2026-08-12-a");
    }

    private static JsonObject fixture(String resource) throws IOException {
        try (var stream = TrainingRunGatewayClientTest.class.getResourceAsStream(resource);
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, encoded.length);
        try (var output = exchange.getResponseBody()) {
            output.write(encoded);
        }
    }
}
