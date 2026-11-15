package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetChunkCommit;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetChunkReceipt;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetPage;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetStartRequest;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetSummary;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetUploadAccepted;
import qupath.ext.histoforge.contract.TrainingLimitsCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;

class TrainingDatasetGatewayClientTest {

    private static final String DATASET_ID = "33333333-3333-4333-8333-333333333333";
    private static final Instant CREATED = Instant.parse("2026-09-02T12:00:00.000Z");

    @Test
    void defaultSealDeadlineCoversGatewayExecutionAndResultHandoff() {
        assertEquals(Duration.ofSeconds(1830), TrainingDatasetGatewayClient.SEAL_TIMEOUT);
    }

    @Test
    void completeDatasetHttpFlowBindsEveryIdentity(@TempDir Path directory) throws Exception {
        byte[] archive = new byte[] {1, 2, 3, 4};
        TrainingApiLimits limits = limits();
        TrainingDatasetStartRequest start =
                new TrainingDatasetStartRequest(
                        "4b95dbf8fa19e752959de3485147d238987f8a83e29be5725e5b6153a486995d",
                        CanonicalCodec.sha256(archive),
                        archive.length,
                        archive.length);
        String startDigest = TrainingDatasetTransferCodec.startRequestDigest(start, limits);
        TrainingDatasetUploadAccepted accepted =
                new TrainingDatasetUploadAccepted(DATASET_ID, startDigest, CREATED.plusSeconds(60));
        TrainingDatasetChunkCommit commit =
                new TrainingDatasetChunkCommit(
                        startDigest, 0, archive.length, CanonicalCodec.sha256(archive), CREATED);
        TrainingDatasetChunkReceipt receipt =
                new TrainingDatasetChunkReceipt(DATASET_ID, commit, CREATED.plusSeconds(60));
        TrainingDatasetRecord retained = retained(start.datasetDigest());
        TrainingDatasetSummary summary = new TrainingDatasetSummary(retained, false);
        TrainingDatasetPage page = new TrainingDatasetPage(List.of(summary), Optional.empty());
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/datasets",
                exchange -> {
                    try {
                        handle(
                                exchange, limits, start, accepted, receipt, retained, summary, page,
                                archive);
                    } catch (Throwable error) {
                        handlerFailure.compareAndSet(null, error);
                        exchange.close();
                    }
                });
        server.start();
        try {
            TrainingDatasetGatewayClient client = client(server, directory);
            TrainingDatasetUploadAccepted actual =
                    assertInstanceOf(TrainingDatasetUploadAccepted.class, client.start(start));
            assertEquals(accepted, actual);
            assertEquals(
                    receipt,
                    client.putChunk(
                            start,
                            actual,
                            TrainingDatasetTransferCodec.chunkRange(start, 0, limits),
                            archive));
            assertEquals(retained, client.seal(start, actual));
            assertEquals(page, client.page(10, Optional.of("opaque +/cursor")));
            assertEquals(summary, client.get(DATASET_ID));
            client.delete(DATASET_ID);
            assertNull(handlerFailure.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void startRejectsAValidResponseForDifferentContent(@TempDir Path directory) throws Exception {
        TrainingApiLimits limits = limits();
        byte[] archive = {1};
        TrainingDatasetStartRequest start =
                new TrainingDatasetStartRequest(
                        "1".repeat(64), CanonicalCodec.sha256(archive), 1, 1);
        TrainingDatasetRecord wrong = retained("2".repeat(64));
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/datasets",
                exchange ->
                        reply(
                                exchange,
                                200,
                                TrainingDatasetTransferCodec.toJson(
                                                new TrainingDatasetTransferCodec
                                                        .ExistingTrainingDataset(wrong),
                                                limits)
                                        .toString()));
        server.start();
        try {
            TrainingDatasetGatewayClient client = client(server, directory);
            assertThrows(InferenceException.class, () -> client.start(start));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"same", "different_id", "different_digest"})
    void sealFollowsTheExactResultUrlAndValidatesTheRetainedIdentity(
            String responseIdentity, @TempDir Path directory) throws Exception {
        TrainingApiLimits limits = limits();
        TrainingDatasetStartRequest start =
                new TrainingDatasetStartRequest("1".repeat(64), "2".repeat(64), 1, 1);
        TrainingDatasetUploadAccepted accepted =
                new TrainingDatasetUploadAccepted(
                        DATASET_ID,
                        TrainingDatasetTransferCodec.startRequestDigest(start, limits),
                        CREATED.plusSeconds(60));
        TrainingDatasetRecord result =
                new TrainingDatasetRecord(
                        responseIdentity.equals("different_id")
                                ? "44444444-4444-4444-8444-444444444444"
                                : DATASET_ID,
                        responseIdentity.equals("different_digest")
                                ? "3".repeat(64)
                                : start.datasetDigest(),
                        1234,
                        "Leydig cell segmentation",
                        2,
                        2,
                        CREATED);
        String path = "/v1/training/datasets/" + DATASET_ID + "/seal";
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                path,
                exchange -> {
                    try {
                        int call = calls.getAndIncrement();
                        assertEquals(path, exchange.getRequestURI().getPath());
                        assertEquals(
                                "Bearer token",
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        if (call == 0) {
                            assertEquals("POST", exchange.getRequestMethod());
                            assertNull(exchange.getRequestURI().getRawQuery());
                            JsonObject body = new JsonObject();
                            body.addProperty("schema_version", 1);
                            body.addProperty("start_request_digest", accepted.startRequestDigest());
                            assertEquals(body, CanonicalCodec.parseJson(readBytes(exchange)));
                        } else {
                            assertEquals("GET", exchange.getRequestMethod());
                            assertEquals("", read(exchange));
                            assertEquals(
                                    "__modal_attempt_token=seal-1",
                                    exchange.getRequestURI().getRawQuery());
                        }
                        if (call < 2) {
                            exchange.getResponseHeaders()
                                    .add("Location", path + "?__modal_attempt_token=seal-1");
                            exchange.sendResponseHeaders(303, -1);
                            exchange.close();
                        } else {
                            reply(
                                    exchange,
                                    200,
                                    TrainingDatasetTransferCodec.toJson(result, limits).toString());
                        }
                    } catch (Throwable error) {
                        handlerFailure.compareAndSet(null, error);
                        exchange.close();
                    }
                });
        server.start();
        try {
            TrainingDatasetGatewayClient client = client(server, directory);
            if (responseIdentity.equals("same")) {
                assertEquals(result, client.seal(start, accepted));
            } else {
                assertThrows(InferenceException.class, () -> client.seal(start, accepted));
            }
            assertEquals(3, calls.get());
        } finally {
            server.stop(0);
            assertNull(handlerFailure.get());
        }
    }

    @Test
    void pageRejectsMoreRowsThanTheRequestedLimit(@TempDir Path directory) throws Exception {
        TrainingApiLimits limits = limits();
        TrainingDatasetSummary first = new TrainingDatasetSummary(retained("1".repeat(64)), false);
        TrainingDatasetRecord secondRecord =
                new TrainingDatasetRecord(
                        "44444444-4444-4444-8444-444444444444",
                        "2".repeat(64),
                        1234,
                        "Leydig cell segmentation",
                        2,
                        2,
                        CREATED);
        TrainingDatasetPage page =
                new TrainingDatasetPage(
                        List.of(first, new TrainingDatasetSummary(secondRecord, false)),
                        Optional.empty());
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/datasets",
                exchange ->
                        reply(
                                exchange,
                                200,
                                TrainingDatasetTransferCodec.toJson(page, limits).toString()));
        server.start();
        try {
            TrainingDatasetGatewayClient client = client(server, directory);
            assertThrows(InferenceException.class, () -> client.page(1, Optional.empty()));
        } finally {
            server.stop(0);
        }
    }

    private static void handle(
            HttpExchange exchange,
            TrainingApiLimits limits,
            TrainingDatasetStartRequest start,
            TrainingDatasetUploadAccepted accepted,
            TrainingDatasetChunkReceipt receipt,
            TrainingDatasetRecord retained,
            TrainingDatasetSummary summary,
            TrainingDatasetPage page,
            byte[] archive)
            throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (method.equals("POST") && path.equals("/v1/training/datasets")) {
            assertEquals(
                    TrainingDatasetTransferCodec.toJson(start, limits),
                    JsonParser.parseString(read(exchange)).getAsJsonObject());
            reply(exchange, 200, TrainingDatasetTransferCodec.toJson(accepted, limits).toString());
            return;
        }
        if (method.equals("PUT") && path.endsWith("/chunks/0")) {
            assertEquals(
                    "application/octet-stream",
                    exchange.getRequestHeaders().getFirst("Content-Type"));
            assertEquals(
                    CanonicalCodec.sha256(archive),
                    exchange.getRequestHeaders().getFirst("X-HistoForge-Chunk-Sha256"));
            assertEquals(List.of(1, 2, 3, 4), bytes(readBytes(exchange)));
            reply(exchange, 200, TrainingDatasetTransferCodec.toJson(receipt).toString());
            return;
        }
        if (method.equals("POST") && path.endsWith("/seal")) {
            JsonObject seal = JsonParser.parseString(read(exchange)).getAsJsonObject();
            assertEquals(
                    accepted.startRequestDigest(), seal.get("start_request_digest").getAsString());
            reply(exchange, 200, TrainingDatasetTransferCodec.toJson(retained, limits).toString());
            return;
        }
        if (method.equals("GET") && path.equals("/v1/training/datasets")) {
            assertEquals(
                    "limit=10&cursor=opaque%20%2B%2Fcursor",
                    exchange.getRequestURI().getRawQuery());
            reply(exchange, 200, TrainingDatasetTransferCodec.toJson(page, limits).toString());
            return;
        }
        if (method.equals("GET") && path.endsWith("/" + DATASET_ID)) {
            reply(exchange, 200, TrainingDatasetTransferCodec.toJson(summary, limits).toString());
            return;
        }
        if (method.equals("DELETE") && path.endsWith("/" + DATASET_ID)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        reply(exchange, 404, "{}");
    }

    private static TrainingDatasetGatewayClient client(HttpServer server, Path directory)
            throws IOException {
        ModelGatewayExecutorTestData.serveCapabilities(server, capabilities());
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
        return new TrainingDatasetGatewayClient(
                new GatewayTransport(new DeploymentStore(file)),
                capabilities(),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5));
    }

    private static TrainingApiLimits limits() throws IOException {
        try (var input =
                TrainingDatasetGatewayClientTest.class.getResourceAsStream(
                        "/contracts/training-datasets-v1.json")) {
            if (input == null) {
                throw new IOException("missing training contract fixture");
            }
            JsonObject fixture =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
            return TrainingLimitsCodec.parse(fixture.get("trainingApiLimits"));
        }
    }

    private static GatewayCapabilities capabilities() throws IOException {
        return ModelGatewayExecutorTestData.capabilities("deployment-2026-08-12-a");
    }

    private static TrainingDatasetRecord retained(String datasetDigest) {
        return new TrainingDatasetRecord(
                DATASET_ID, datasetDigest, 1234, "Leydig cell segmentation", 2, 2, CREATED);
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(readBytes(exchange), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(HttpExchange exchange) throws IOException {
        return exchange.getRequestBody().readAllBytes();
    }

    private static List<Integer> bytes(byte[] values) {
        java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
        for (byte value : values) {
            result.add((int) value);
        }
        return result;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, encoded.length);
        try (var output = exchange.getResponseBody()) {
            output.write(encoded);
        }
    }
}
