package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.TrainingDatasetCodec;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetManifest;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.ExistingTrainingDataset;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetChunkCommit;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetChunkReceipt;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetPage;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetStartRequest;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetSummary;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetUploadAccepted;
import qupath.ext.histoforge.contract.TrainingLimitsCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PreparedTrainingDataset;
import qupath.ext.histoforge.training.TrainingDatasetUploader;

class TrainingDatasetUploaderTest {

    private static final String DATASET_ID = "33333333-3333-4333-8333-333333333333";
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00.000Z");

    @TempDir Path temporaryDirectory;

    @Test
    void uploadsCanonicalChunksAndSealsTheExactArchive() throws Exception {
        byte[] archive = new byte[TrainingDatasetUploader.CHUNK_SIZE_BYTES + 3];
        for (int index = 0; index < archive.length; index++) {
            archive[index] = (byte) index;
        }
        Fixture fixture = fixture(archive);
        List<byte[]> chunks = new ArrayList<>();
        AtomicInteger sealCalls = new AtomicInteger();
        try (Server server = new Server(fixture, false, chunks, sealCalls)) {
            List<Long> progress = new ArrayList<>();

            TrainingDatasetRecord result =
                    new TrainingDatasetUploader(
                                    client(server.http(), fixture.capabilities()), fixture.limits())
                            .upload(fixture.prepared(), () -> false, progress::add);

            assertEquals(fixture.retained(), result);
            assertEquals(2, chunks.size());
            assertArrayEquals(
                    java.util.Arrays.copyOfRange(
                            archive, 0, TrainingDatasetUploader.CHUNK_SIZE_BYTES),
                    chunks.get(0));
            assertArrayEquals(
                    java.util.Arrays.copyOfRange(
                            archive, TrainingDatasetUploader.CHUNK_SIZE_BYTES, archive.length),
                    chunks.get(1));
            assertEquals(
                    List.of((long) TrainingDatasetUploader.CHUNK_SIZE_BYTES, (long) archive.length),
                    progress);
            assertEquals(1, sealCalls.get());
        }
    }

    @Test
    void lostSealResponseRecoversThroughExactStartReplay() throws Exception {
        Fixture fixture = fixture(new byte[] {1, 2, 3});
        List<byte[]> chunks = new ArrayList<>();
        AtomicInteger sealCalls = new AtomicInteger();
        try (Server server = new Server(fixture, true, chunks, sealCalls)) {
            TrainingDatasetRecord result =
                    new TrainingDatasetUploader(
                                    client(server.http(), fixture.capabilities()), fixture.limits())
                            .upload(fixture.prepared(), () -> false, ignored -> {});

            assertEquals(fixture.retained(), result);
            assertEquals(1, sealCalls.get());
            assertEquals(1, chunks.size());
        }
    }

    @Test
    void cancellationBeforeStartCreatesNoReservation() throws Exception {
        Fixture fixture = fixture(new byte[] {1});
        AtomicInteger requests = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        http.createContext("/v1/training/datasets", exchange -> requests.incrementAndGet());
        http.start();
        try {
            var uploader =
                    new TrainingDatasetUploader(
                            client(http, fixture.capabilities()), fixture.limits());
            assertThrows(
                    CancellationException.class,
                    () -> uploader.upload(fixture.prepared(), () -> true, ignored -> {}));
            assertEquals(0, requests.get());
        } finally {
            http.stop(0);
        }
    }

    @Test
    void qualificationMeasuresRealUploaderProgressAndReadback() throws Exception {
        Fixture fixture = fixture(new byte[TrainingDatasetUploader.CHUNK_SIZE_BYTES + 3]);
        List<byte[]> chunks = new ArrayList<>();
        AtomicInteger sealCalls = new AtomicInteger();
        try (Server server = new Server(fixture, false, chunks, sealCalls)) {
            JsonObject evidence = new JsonObject();
            TrainingDatasetTransferQualification.measure(
                    fixture.prepared(), client(server.http(), fixture.capabilities()), evidence);

            assertEquals(2, chunks.size());
            assertEquals(1, sealCalls.get());
            assertTrue(evidence.get("readback_verified").getAsBoolean());
            var progress = evidence.getAsJsonArray("upload_progress");
            assertEquals(2, progress.size());
            assertEquals(
                    fixture.prepared().archiveBytes(),
                    progress.get(1).getAsJsonObject().get("uploaded_bytes").getAsLong());
            assertTrue(
                    evidence.get("upload_and_seal_seconds").getAsDouble()
                            >= progress.get(1)
                                    .getAsJsonObject()
                                    .get("elapsed_seconds")
                                    .getAsDouble());
            assertTrue(evidence.get("after_last_progress_seconds").getAsDouble() >= 0);
            assertEquals(
                    DATASET_ID,
                    evidence.getAsJsonObject("retained_dataset").get("dataset_id").getAsString());
        }
    }

    @Test
    void qualificationDoesNotTreatDatasetReuseAsTransferEvidence() throws Exception {
        Fixture fixture = fixture(new byte[] {1, 2, 3});
        List<byte[]> chunks = new ArrayList<>();
        AtomicInteger seals = new AtomicInteger();
        try (Server server = new Server(fixture, false, chunks, seals)) {
            server.sealed = true;
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            TrainingDatasetTransferQualification.measure(
                                    fixture.prepared(),
                                    client(server.http(), fixture.capabilities()),
                                    new JsonObject()));
            assertTrue(chunks.isEmpty());
            assertEquals(0, seals.get());
        }
    }

    @Test
    void qualificationRejectsChangedArchiveWithoutOpeningGateway() throws Exception {
        Path archive = temporaryDirectory.resolve("private-source.zip");
        Files.write(archive, new byte[] {1});
        Path credential = temporaryDirectory.resolve("not-read-private-credential.json");
        Path output = temporaryDirectory.resolve("evidence.json");
        assertThrows(
                IllegalStateException.class,
                () ->
                        TrainingDatasetTransferQualification.main(
                                new String[] {
                                    credential.toString(),
                                    archive.toString(),
                                    "0".repeat(64),
                                    output.toString()
                                }));
        String text = Files.readString(output);
        JsonObject evidence =
                CanonicalCodec.parseJson(Files.readAllBytes(output)).getAsJsonObject();
        String digest = evidence.remove("evidence_digest").getAsString();
        assertEquals(CanonicalCodec.canonicalDigest(evidence), digest);
        assertFalse(evidence.get("success").getAsBoolean());
        assertFalse(text.contains("private-source"));
        assertFalse(text.contains("private-credential"));
        assertFalse(Files.exists(credential));
    }

    private Fixture fixture(byte[] archive) throws Exception {
        ContractFixture contract = contractFixture();
        Path path = temporaryDirectory.resolve("dataset-" + archive.length + ".zip");
        Files.write(path, archive);
        PreparedTrainingDataset prepared =
                new PreparedTrainingDataset(
                        path, archive.length, CanonicalCodec.sha256(archive), contract.manifest());
        TrainingDatasetStartRequest start =
                new TrainingDatasetStartRequest(
                        contract.manifest().datasetDigest(),
                        prepared.archiveBytesDigest(),
                        archive.length,
                        TrainingDatasetUploader.CHUNK_SIZE_BYTES);
        TrainingDatasetRecord retained =
                new TrainingDatasetRecord(
                        DATASET_ID,
                        contract.manifest().datasetDigest(),
                        archive.length,
                        "Test training task",
                        contract.manifest().images().size(),
                        contract.manifest().rois().size(),
                        NOW);
        return new Fixture(contract.limits(), contract.capabilities(), prepared, start, retained);
    }

    private TrainingDatasetGatewayClient client(HttpServer server, GatewayCapabilities capabilities)
            throws IOException {
        Path deploymentFile =
                temporaryDirectory.resolve("deployment-" + server.getAddress().getPort() + ".json");
        String url = "http://localhost:" + server.getAddress().getPort();
        Deployment deployment =
                new Deployment(
                        1,
                        capabilities.deploymentId(),
                        url,
                        "research-lab",
                        "histoforge-gateway",
                        capabilities.authRevision(),
                        "token",
                        NOW);
        Files.writeString(deploymentFile, deployment.toJson().toString());
        return new TrainingDatasetGatewayClient(
                new GatewayTransport(new DeploymentStore(deploymentFile)),
                capabilities,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5));
    }

    private static ContractFixture contractFixture() throws Exception {
        JsonObject training;
        try (var input =
                TrainingDatasetUploaderTest.class.getResourceAsStream(
                        "/contracts/training-datasets-v1.json")) {
            if (input == null) {
                throw new IllegalStateException("missing training fixture");
            }
            training =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
        }
        GatewayCapabilities capabilities = ModelGatewayExecutorTestData.capabilities();
        TrainingApiLimits limits = TrainingLimitsCodec.parse(training.get("trainingApiLimits"));
        return new ContractFixture(
                limits,
                capabilities,
                TrainingDatasetCodec.parseManifest(
                        training.getAsJsonObject("manifest").get("value"), limits));
    }

    private record ContractFixture(
            TrainingApiLimits limits,
            GatewayCapabilities capabilities,
            TrainingDatasetManifest manifest) {}

    private record Fixture(
            TrainingApiLimits limits,
            GatewayCapabilities capabilities,
            PreparedTrainingDataset prepared,
            TrainingDatasetStartRequest start,
            TrainingDatasetRecord retained) {}

    private static final class Server implements AutoCloseable {
        private final Fixture fixture;
        private final boolean loseFirstSealResponse;
        private final List<byte[]> chunks;
        private final AtomicInteger sealCalls;
        private final HttpServer http;
        private boolean sealed;

        private Server(
                Fixture fixture,
                boolean loseFirstSealResponse,
                List<byte[]> chunks,
                AtomicInteger sealCalls)
                throws IOException {
            this.fixture = fixture;
            this.loseFirstSealResponse = loseFirstSealResponse;
            this.chunks = chunks;
            this.sealCalls = sealCalls;
            http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ModelGatewayExecutorTestData.serveCapabilities(http, fixture.capabilities());
            http.createContext("/v1/training/datasets", this::handle);
            http.start();
        }

        private HttpServer http() {
            return http;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && path.equals("/v1/training/datasets")) {
                List<TrainingDatasetSummary> items =
                        sealed
                                ? List.of(new TrainingDatasetSummary(fixture.retained(), false))
                                : List.of();
                reply(
                        exchange,
                        TrainingDatasetTransferCodec.toJson(
                                new TrainingDatasetPage(items, Optional.empty()),
                                fixture.limits()));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && path.equals("/v1/training/datasets/" + DATASET_ID)
                    && sealed) {
                reply(
                        exchange,
                        TrainingDatasetTransferCodec.toJson(
                                new TrainingDatasetSummary(fixture.retained(), false),
                                fixture.limits()));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod())
                    && "/v1/training/datasets".equals(path)) {
                TrainingDatasetStartRequest actual =
                        TrainingDatasetTransferCodec.parseStartRequest(
                                JsonParser.parseString(readString(exchange)), fixture.limits());
                assertEquals(fixture.start(), actual);
                if (sealed) {
                    reply(
                            exchange,
                            TrainingDatasetTransferCodec.toJson(
                                    new ExistingTrainingDataset(fixture.retained()),
                                    fixture.limits()));
                    return;
                }
                String digest =
                        TrainingDatasetTransferCodec.startRequestDigest(actual, fixture.limits());
                reply(
                        exchange,
                        TrainingDatasetTransferCodec.toJson(
                                new TrainingDatasetUploadAccepted(
                                        DATASET_ID, digest, NOW.plusSeconds(300)),
                                fixture.limits()));
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod()) && path.contains("/chunks/")) {
                int index = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
                byte[] body = exchange.getRequestBody().readAllBytes();
                while (chunks.size() <= index) {
                    chunks.add(null);
                }
                chunks.set(index, body);
                String startDigest =
                        TrainingDatasetTransferCodec.startRequestDigest(
                                fixture.start(), fixture.limits());
                reply(
                        exchange,
                        TrainingDatasetTransferCodec.toJson(
                                new TrainingDatasetChunkReceipt(
                                        DATASET_ID,
                                        new TrainingDatasetChunkCommit(
                                                startDigest,
                                                index,
                                                body.length,
                                                CanonicalCodec.sha256(body),
                                                NOW),
                                        NOW.plusSeconds(300))));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/seal")) {
                sealCalls.incrementAndGet();
                readString(exchange);
                sealed = true;
                if (loseFirstSealResponse) {
                    exchange.close();
                } else {
                    reply(
                            exchange,
                            TrainingDatasetTransferCodec.toJson(
                                    fixture.retained(), fixture.limits()));
                }
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        @Override
        public void close() {
            http.stop(0);
        }

        private static String readString(HttpExchange exchange) throws IOException {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }

        private static void reply(HttpExchange exchange, JsonObject body) throws IOException {
            byte[] encoded = CanonicalCodec.canonicalBytes(body);
            exchange.sendResponseHeaders(200, encoded.length);
            try (var output = exchange.getResponseBody()) {
                output.write(encoded);
            }
        }
    }
}
