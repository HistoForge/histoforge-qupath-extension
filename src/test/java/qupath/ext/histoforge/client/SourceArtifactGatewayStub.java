package qupath.ext.histoforge.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactAccepted;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactSealed;
import qupath.ext.histoforge.contract.SourceArtifactStatusCodec;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;

/** Stateful local HTTP source gateway used by {@link ModelGatewayExecutorTest}. */
final class SourceArtifactGatewayStub implements AutoCloseable {

    private final PreparedSourceArtifact prepared;
    private final byte[] expectedBytes;
    private final Path requiredStartFile;
    private final SourceArtifactAccepted accepted;
    private final SourceArtifactSealed sealed;
    private final Set<Integer> acceptedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Integer, AtomicInteger> chunkCalls = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger activeChunks = new AtomicInteger();
    private final AtomicInteger peakChunks = new AtomicInteger();
    private final AtomicInteger startCalls = new AtomicInteger();
    private final AtomicInteger sealCalls = new AtomicInteger();
    private final List<byte[]> sealRequestBodies = new CopyOnWriteArrayList<>();
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean loseNextSealBeforePublication = new AtomicBoolean();
    private final AtomicBoolean loseNextSealResponse = new AtomicBoolean();
    private final AtomicBoolean loseNextStartResponse = new AtomicBoolean();
    private final Set<Integer> loseNextChunkResponses = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean acceptedHookRan = new AtomicBoolean();
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool();
    private final HttpServer server;
    private volatile Integer failingChunk;
    private volatile Integer invalidAcknowledgementChunk;
    private volatile Integer acceptedHookChunk;
    private volatile Runnable acceptedHook = () -> {};
    private volatile boolean reservedState;
    private volatile boolean sealedState;
    private volatile CountDownLatch blockedEntered = new CountDownLatch(0);
    private volatile CountDownLatch unblockChunks = new CountDownLatch(0);

    SourceArtifactGatewayStub(PreparedSourceArtifact prepared, Path requiredStartFile)
            throws IOException {
        this.prepared = prepared;
        this.expectedBytes = Files.readAllBytes(prepared.path());
        this.requiredStartFile = requiredStartFile.toAbsolutePath().normalize();
        this.accepted =
                new SourceArtifactAccepted(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        prepared.startRequest().creationActionId(),
                        prepared.startRequest().artifactId(),
                        SourceArtifactCodec.startRequestDigest(prepared.startRequest()),
                        ModelGatewayExecutorTestData.ABSOLUTE_DEADLINE);
        var levels =
                prepared.startRequest().requiredLevels().stream()
                        .map(
                                level ->
                                        new SourceArtifactPublicationCodec.ValidatedSourceLevel(
                                                level.seriesIndex(),
                                                level.resolutionLevel(),
                                                level.width(),
                                                level.height()))
                        .toList();
        var manifest =
                SourceArtifactPublicationCodec.buildManifest(
                        prepared.startRequest(), prepared.sealRequest(), levels);
        this.sealed =
                new SourceArtifactSealed(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        SourceArtifactPublicationCodec.buildArtifactRef(
                                prepared.startRequest(), manifest),
                        accepted.absoluteExpiresAt());
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ModelGatewayExecutorTestData.serveCapabilities(
                server, ModelGatewayExecutorTestData.capabilities());
        server.createContext("/v1/source-artifacts", this::handle);
        server.setExecutor(requestExecutor);
        server.start();
    }

    HttpServer server() {
        return server;
    }

    void preaccept(int... chunkIds) {
        Arrays.stream(chunkIds).forEach(acceptedChunks::add);
    }

    void blockFirstChunkRequests(int count) {
        blockedEntered = new CountDownLatch(count);
        unblockChunks = new CountDownLatch(1);
    }

    boolean awaitBlockedChunks() throws InterruptedException {
        return blockedEntered.await(5, TimeUnit.SECONDS);
    }

    void unblockChunkRequests() {
        unblockChunks.countDown();
    }

    void loseNextSealResponse() {
        loseNextSealResponse.set(true);
    }

    void loseNextSealBeforePublication() {
        loseNextSealBeforePublication.set(true);
    }

    void loseNextStartResponse() {
        loseNextStartResponse.set(true);
    }

    void loseNextChunkResponse(int chunkId) {
        loseNextChunkResponses.add(chunkId);
    }

    void failChunk(Integer chunkId) {
        failingChunk = chunkId;
    }

    void invalidateChunkAcknowledgement(int chunkId) {
        invalidAcknowledgementChunk = chunkId;
    }

    void afterAcceptingChunk(int chunkId, Runnable hook) {
        acceptedHookChunk = chunkId;
        acceptedHook = hook;
    }

    int peakChunkRequests() {
        return peakChunks.get();
    }

    int startCalls() {
        return startCalls.get();
    }

    int chunkCalls(int chunkId) {
        AtomicInteger count = chunkCalls.get(chunkId);
        return count == null ? 0 : count.get();
    }

    Set<Integer> acceptedChunks() {
        return Set.copyOf(acceptedChunks);
    }

    int requestCount() {
        return requests.get();
    }

    int sealCalls() {
        return sealCalls.get();
    }

    List<byte[]> sealRequestBodies() {
        return sealRequestBodies.stream().map(byte[]::clone).toList();
    }

    boolean released() {
        return released.get();
    }

    void requireNoFailure() {
        Throwable problem = failure.get();
        if (problem != null) {
            throw new AssertionError("source gateway stub failed", problem);
        }
    }

    @Override
    public void close() {
        unblockChunkRequests();
        server.stop(0);
        requestExecutor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        requests.incrementAndGet();
        try {
            require(
                    List.of("Bearer test-token")
                            .equals(exchange.getRequestHeaders().get("Authorization")),
                    "missing exact authorization header");
            String path = exchange.getRequestURI().getRawPath();
            String base = "/v1/source-artifacts";
            if (path.equals(base) && exchange.getRequestMethod().equals("POST")) {
                handleStart(exchange);
            } else if (path.matches(
                            base + "/" + prepared.startRequest().artifactId() + "/chunks/[0-9]+")
                    && exchange.getRequestMethod().equals("PUT")) {
                handleChunk(exchange, path);
            } else if (path.equals(base + "/" + prepared.startRequest().artifactId() + "/seal")
                    && exchange.getRequestMethod().equals("POST")) {
                handleSeal(exchange);
            } else if (path.equals(base + "/" + prepared.startRequest().artifactId())
                    && exchange.getRequestMethod().equals("GET")) {
                handleStatus(exchange);
            } else if (path.equals(base + "/" + prepared.startRequest().artifactId())
                    && exchange.getRequestMethod().equals("DELETE")) {
                reservedState = false;
                sealedState = false;
                released.set(true);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            } else {
                reply(exchange, 404, "not found");
            }
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
            try {
                reply(exchange, 500, "stub failure");
            } catch (IOException ignored) {
                exchange.close();
            }
        }
    }

    private void handleStart(HttpExchange exchange) throws IOException {
        startCalls.incrementAndGet();
        require(
                List.of("application/json")
                        .equals(exchange.getRequestHeaders().get("Content-Type")),
                "start content type differs");
        byte[] expected =
                CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(prepared.startRequest()));
        require(Files.isRegularFile(requiredStartFile), "network preceded local request publish");
        require(
                Arrays.equals(expected, Files.readAllBytes(requiredStartFile)),
                "start file differs");
        require(
                Arrays.equals(expected, exchange.getRequestBody().readAllBytes()),
                "wire start request differs");
        reservedState = true;
        if (loseNextStartResponse.compareAndSet(true, false)) {
            exchange.close();
            return;
        }
        reply(exchange, 200, SourceArtifactCodec.toJson(accepted).toString());
    }

    private void handleChunk(HttpExchange exchange, String path) throws Exception {
        require(reservedState, "chunk arrived before source reservation");
        int active = activeChunks.incrementAndGet();
        peakChunks.accumulateAndGet(active, Math::max);
        try {
            require(
                    List.of("application/octet-stream")
                            .equals(exchange.getRequestHeaders().get("Content-Type")),
                    "chunk content type differs");
            int chunkId = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            chunkCalls.computeIfAbsent(chunkId, ignored -> new AtomicInteger()).incrementAndGet();
            blockedEntered.countDown();
            require(unblockChunks.await(5, TimeUnit.SECONDS), "chunk unblock timed out");
            byte[] body = exchange.getRequestBody().readAllBytes();
            var range =
                    SourceArtifactCodec.chunkRange(
                            prepared.startRequest().totalBytes(),
                            prepared.startRequest().chunkSizeBytes(),
                            chunkId);
            byte[] expected =
                    Arrays.copyOfRange(
                            expectedBytes,
                            Math.toIntExact(range.offset()),
                            Math.toIntExact(range.offset()) + range.size());
            require(Arrays.equals(expected, body), "chunk body differs from canonical range");
            if (Integer.valueOf(chunkId).equals(failingChunk)) {
                reply(
                        exchange,
                        503,
                        "{\"schema_version\":1,\"code\":\"SERVICE_UNAVAILABLE\","
                                + "\"message\":\"injected chunk failure\"}");
                return;
            }
            acceptedChunks.add(chunkId);
            if (Integer.valueOf(chunkId).equals(acceptedHookChunk)
                    && acceptedHookRan.compareAndSet(false, true)) {
                acceptedHook.run();
            }
            var commit =
                    new SourceArtifactTransferCodec.SourceByteChunkCommit(
                            chunkId,
                            Integer.valueOf(chunkId).equals(invalidAcknowledgementChunk)
                                    ? "0".repeat(64)
                                    : CanonicalCodec.sha256(body));
            var acceptedChunk =
                    new SourceArtifactTransferCodec.SourceByteChunkAccepted(
                            SourceArtifactCodec.SCHEMA_VERSION,
                            prepared.startRequest().artifactId(),
                            commit);
            if (loseNextChunkResponses.remove(chunkId)) {
                exchange.close();
                return;
            }
            reply(exchange, 200, SourceArtifactTransferCodec.toJson(acceptedChunk).toString());
        } finally {
            activeChunks.decrementAndGet();
        }
    }

    private void handleSeal(HttpExchange exchange) throws IOException {
        require(reservedState, "seal arrived before source reservation");
        sealCalls.incrementAndGet();
        require(
                List.of("application/json")
                        .equals(exchange.getRequestHeaders().get("Content-Type")),
                "seal content type differs");
        byte[] body = exchange.getRequestBody().readAllBytes();
        sealRequestBodies.add(body.clone());
        var request = SourceArtifactTransferCodec.parseSealRequest(CanonicalCodec.parseJson(body));
        require(request.equals(prepared.sealRequest()), "seal request differs");
        require(missingChunks().isEmpty(), "seal arrived before every chunk");
        if (loseNextSealBeforePublication.compareAndSet(true, false)) {
            exchange.close();
            return;
        }
        sealedState = true;
        if (loseNextSealResponse.compareAndSet(true, false)) {
            exchange.close();
            return;
        }
        reply(exchange, 200, SourceArtifactPublicationCodec.toJson(sealed).toString());
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!reservedState) {
            reply(
                    exchange,
                    404,
                    "{\"schema_version\":1,\"code\":\"ARTIFACT_NOT_FOUND\","
                            + "\"message\":\"source artifact is absent\"}");
            return;
        }
        SourceArtifactStatusCodec.SourceArtifactStatus status;
        if (sealedState) {
            status =
                    new SourceArtifactStatusCodec.SourceArtifactStatus(
                            SourceArtifactCodec.SCHEMA_VERSION,
                            prepared.startRequest().artifactId(),
                            SourceArtifactCodec.startRequestDigest(prepared.startRequest()),
                            SourceArtifactStatusCodec.Phase.SEALED,
                            List.of(),
                            Optional.of(sealed.artifact()),
                            Optional.empty(),
                            accepted.absoluteExpiresAt());
        } else {
            status =
                    new SourceArtifactStatusCodec.SourceArtifactStatus(
                            SourceArtifactCodec.SCHEMA_VERSION,
                            prepared.startRequest().artifactId(),
                            SourceArtifactCodec.startRequestDigest(prepared.startRequest()),
                            SourceArtifactStatusCodec.Phase.UPLOADING,
                            missingChunks(),
                            Optional.empty(),
                            Optional.of(ModelGatewayExecutorTestData.IDLE_DEADLINE),
                            accepted.absoluteExpiresAt());
        }
        reply(exchange, 200, SourceArtifactStatusCodec.toJson(status).toString());
    }

    private List<Integer> missingChunks() {
        int count =
                SourceArtifactCodec.chunkCount(
                        prepared.startRequest().totalBytes(),
                        prepared.startRequest().chunkSizeBytes());
        List<Integer> missing = new ArrayList<>();
        for (int chunkId = 0; chunkId < count; chunkId++) {
            if (!acceptedChunks.contains(chunkId)) {
                missing.add(chunkId);
            }
        }
        return List.copyOf(new TreeSet<>(missing));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
