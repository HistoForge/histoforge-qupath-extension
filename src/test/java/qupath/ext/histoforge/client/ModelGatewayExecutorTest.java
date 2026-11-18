package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion.SourceVersionChangedException;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;

/** End-to-end local HTTP tests for source publication and recovery sequencing. */
class ModelGatewayExecutorTest {

    @Test
    void sourceOperationTimeoutsCoverObservedSlowDurablePublication() {
        assertEquals(HttpClient.Version.HTTP_1_1, GatewayTransport.HTTP_VERSION);
        assertTrue(
                ModelGatewayExecutor.CHUNK_HTTP_TIMEOUT.compareTo(GatewayTransport.HTTP_TIMEOUT)
                        > 0);
        assertEquals(Duration.ofSeconds(300), ModelGatewayExecutor.CHUNK_HTTP_TIMEOUT);
        assertTrue(
                ModelGatewayExecutor.CHUNK_HTTP_TIMEOUT.compareTo(
                                ModelGatewayExecutor.SEAL_HTTP_TIMEOUT)
                        < 0);
        assertEquals(Duration.ofSeconds(330), ModelGatewayExecutor.SEAL_HTTP_TIMEOUT);
        assertEquals(Duration.ofSeconds(330), ModelGatewayExecutor.RELEASE_HTTP_TIMEOUT);
        assertTrue(
                ModelGatewayExecutor.RELEASE_HTTP_TIMEOUT.compareTo(GatewayTransport.HTTP_TIMEOUT)
                        > 0);
    }

    @Test
    void replaysExactSealWhenAmbiguousCallRemainsUploading(@TempDir Path directory)
            throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(12);
        Path source = directory.resolve("source.tif");
        var prepared = ModelGatewayExecutorTestData.prepared(source, bytes, 4);
        Path requestFile = directory.resolve("recovery/source-start.json");

        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile)) {
            gateway.loseNextSealBeforePublication();
            GatewayTransport transport =
                    new GatewayTransport(
                            ModelGatewayExecutorTestData.storeFor(gateway.server(), directory));
            try (var executor =
                    new ModelGatewayExecutor(
                            transport, ModelGatewayExecutorTestData.capabilities())) {
                var sealed = executor.publishSource(prepared, requestFile);
                assertEquals(prepared.sealRequest().bytesDigest(), sealed.artifact().bytesDigest());
            }

            assertEquals(2, gateway.sealCalls());
            var requests = gateway.sealRequestBodies();
            assertEquals(2, requests.size());
            assertArrayEquals(requests.get(0), requests.get(1));
            gateway.requireNoFailure();
        }
    }

    @Test
    void publishesRequestBeforeNetworkUploadsOnlyMissingWithFiveWayBoundAndRecoversLostSeal(
            @TempDir Path directory) throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(28);
        Path source = directory.resolve("source.tif");
        var prepared = ModelGatewayExecutorTestData.prepared(source, bytes, 4);
        Path requestFile = directory.resolve("recovery/source-start.json");

        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile)) {
            gateway.preaccept(1);
            gateway.blockFirstChunkRequests(5);
            gateway.loseNextStartResponse();
            gateway.loseNextChunkResponse(0);
            gateway.loseNextSealResponse();
            GatewayTransport transport =
                    new GatewayTransport(
                            ModelGatewayExecutorTestData.storeFor(gateway.server(), directory));
            try (var executor =
                            new ModelGatewayExecutor(
                                    transport, ModelGatewayExecutorTestData.capabilities());
                    var caller = Executors.newSingleThreadExecutor()) {
                var result = caller.submit(() -> executor.publishSource(prepared, requestFile));
                assertTrue(gateway.awaitBlockedChunks(), "five upload requests did not arrive");
                assertEquals(5, gateway.peakChunkRequests());
                assertEquals(0, gateway.chunkCalls(6), "sixth request exceeded the shared bound");
                gateway.unblockChunkRequests();

                var sealed = result.get(10, TimeUnit.SECONDS);
                assertEquals(prepared.sealRequest().bytesDigest(), sealed.artifact().bytesDigest());
                assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6), gateway.acceptedChunks());
                assertEquals(0, gateway.chunkCalls(1), "already accepted chunk was reuploaded");
                assertEquals(2, gateway.startCalls(), "lost start response was not replayed");
                assertEquals(2, gateway.chunkCalls(0), "lost chunk response was not replayed");
                assertEquals(1, gateway.sealCalls());
                assertArrayEquals(
                        CanonicalCodec.canonicalBytes(
                                SourceArtifactCodec.toJson(prepared.startRequest())),
                        Files.readAllBytes(requestFile));

                try (var resumed =
                        new ModelGatewayExecutor(
                                transport, ModelGatewayExecutorTestData.capabilities())) {
                    assertEquals(sealed, resumed.resumeSource(source, requestFile));
                }
                assertEquals(1, gateway.sealCalls(), "SEALED recovery replayed seal");

                executor.releaseSource(prepared.startRequest().artifactId());
                executor.releaseSource(prepared.startRequest().artifactId());
                assertTrue(gateway.released());
            }
            gateway.requireNoFailure();
        }
    }

    @Test
    void resumesAfterPartialTypedFailureWithoutResendingAcceptedChunks(@TempDir Path directory)
            throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(12);
        Path source = directory.resolve("source.tif");
        var prepared = ModelGatewayExecutorTestData.prepared(source, bytes, 4);
        Path requestFile = directory.resolve("recovery/source-start.json");

        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile)) {
            gateway.failChunk(2);
            GatewayTransport transport =
                    new GatewayTransport(
                            ModelGatewayExecutorTestData.storeFor(gateway.server(), directory));
            try (var first =
                    new ModelGatewayExecutor(
                            transport, ModelGatewayExecutorTestData.capabilities(), 1)) {
                InferenceException error =
                        assertThrows(
                                InferenceException.class,
                                () -> first.publishSource(prepared, requestFile));
                assertEquals(
                        GatewayErrorCodec.Code.SERVICE_UNAVAILABLE,
                        error.gatewayError().orElseThrow().code());
            }

            Set<Integer> alreadyAccepted = gateway.acceptedChunks();
            assertTrue(alreadyAccepted.containsAll(Set.of(0, 1)));
            Map<Integer, Integer> callsBeforeResume = new HashMap<>();
            alreadyAccepted.forEach(
                    chunkId -> callsBeforeResume.put(chunkId, gateway.chunkCalls(chunkId)));
            gateway.failChunk(null);

            try (var resumed =
                    new ModelGatewayExecutor(
                            transport, ModelGatewayExecutorTestData.capabilities(), 2)) {
                var sealed = resumed.resumeSource(source, requestFile);
                assertEquals(prepared.sealRequest().bytesDigest(), sealed.artifact().bytesDigest());
            }
            callsBeforeResume.forEach(
                    (chunkId, count) -> assertEquals(count, gateway.chunkCalls(chunkId)));
            gateway.requireNoFailure();
        }
    }

    @Test
    void corruptRecoveryRequestFailsBeforeNetwork(@TempDir Path directory) throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(12);
        Path source = directory.resolve("source.tif");
        var prepared = ModelGatewayExecutorTestData.prepared(source, bytes, 4);
        Path requestFile = directory.resolve("source-start.json");
        byte[] canonical =
                CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(prepared.startRequest()));
        Files.write(
                requestFile,
                (new String(canonical, StandardCharsets.UTF_8) + "\n")
                        .getBytes(StandardCharsets.UTF_8));

        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile);
                var executor =
                        new ModelGatewayExecutor(
                                new GatewayTransport(
                                        ModelGatewayExecutorTestData.storeFor(
                                                gateway.server(), directory)),
                                ModelGatewayExecutorTestData.capabilities())) {
            assertThrows(IOException.class, () -> executor.resumeSource(source, requestFile));
            assertEquals(0, gateway.requestCount());
        }
    }

    @Test
    void absentRecoveryDoesNotRecreateTheOldArtifactId(@TempDir Path directory) throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(12);
        Path source = directory.resolve("source.tif");
        var prepared = ModelGatewayExecutorTestData.prepared(source, bytes, 4);
        Path requestFile = directory.resolve("recovery/source-start.json");
        SourceArtifactLocalFiles.publishCanonicalStart(
                requestFile,
                CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(prepared.startRequest())));

        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile);
                var executor =
                        new ModelGatewayExecutor(
                                new GatewayTransport(
                                        ModelGatewayExecutorTestData.storeFor(
                                                gateway.server(), directory)),
                                ModelGatewayExecutorTestData.capabilities())) {
            InferenceException error =
                    assertThrows(
                            InferenceException.class,
                            () -> executor.resumeSource(source, requestFile));
            assertEquals(
                    GatewayErrorCodec.Code.ARTIFACT_NOT_FOUND,
                    error.gatewayError().orElseThrow().code());
            assertEquals(0, gateway.startCalls());
            assertEquals(1, gateway.requestCount());
        }
    }

    @Test
    void capturedDeploymentChangeStopsBeforeRemoteMutation(@TempDir Path directory)
            throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(12);
        Path source = directory.resolve("source.tif");
        var prepared = ModelGatewayExecutorTestData.prepared(source, bytes, 4);
        Path requestFile = directory.resolve("recovery/source-start.json");

        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile)) {
            Path credential = directory.resolve("deployment.json");
            String url = "http://localhost:" + gateway.server().getAddress().getPort();
            Files.writeString(
                    credential,
                    ModelGatewayExecutorTestData.deployment(
                                    url, ModelGatewayExecutorTestData.DEPLOYMENT_ID)
                            .toJson()
                            .toString());
            GatewayTransport transport = new GatewayTransport(new DeploymentStore(credential));
            Files.writeString(
                    credential,
                    ModelGatewayExecutorTestData.deployment(url, "deployment-b")
                            .toJson()
                            .toString());
            try (var executor =
                    new ModelGatewayExecutor(
                            transport, ModelGatewayExecutorTestData.capabilities())) {
                assertThrows(
                        GatewayTransport.DeploymentChangedException.class,
                        () -> executor.publishSource(prepared, requestFile));
                assertTrue(Files.isRegularFile(requestFile));
                assertEquals(0, gateway.requestCount());
            }
        }
    }

    @Test
    void changedSourceAfterChunkTransferNeverReachesSeal(@TempDir Path directory) throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(12);
        Path source = directory.resolve("source.tif");
        var prepared = ModelGatewayExecutorTestData.prepared(source, bytes, 4);
        Path requestFile = directory.resolve("recovery/source-start.json");

        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile)) {
            gateway.afterAcceptingChunk(
                    2,
                    () -> {
                        try {
                            byte[] changed = bytes.clone();
                            changed[0] ^= 0x7f;
                            Files.write(source, changed);
                        } catch (IOException error) {
                            throw new IllegalStateException(error);
                        }
                    });
            try (var executor =
                    new ModelGatewayExecutor(
                            new GatewayTransport(
                                    ModelGatewayExecutorTestData.storeFor(
                                            gateway.server(), directory)),
                            ModelGatewayExecutorTestData.capabilities())) {
                SourceVersionChangedException error =
                        assertThrows(
                                SourceVersionChangedException.class,
                                () -> executor.publishSource(prepared, requestFile));
                assertTrue(error.getMessage().contains("changed"));
                assertEquals(0, gateway.sealCalls());
                assertFalse(gateway.released());
            }
            gateway.requireNoFailure();
        }
    }

    @Test
    void changedDerivedBytesAreTypedAsCorruptBeforeNetwork(@TempDir Path directory)
            throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(12);
        Path source = directory.resolve("source.tif");
        PreparedSourceArtifact original = ModelGatewayExecutorTestData.prepared(source, bytes, 4);
        SourceIdentityCodec.ImageSourceIdentity identity =
                ((SourceArtifactCodec.OriginalWsi) original.startRequest().origin()).source();
        var start =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        original.startRequest().schemaVersion(),
                        original.startRequest().creationActionId(),
                        original.startRequest().artifactId(),
                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY,
                        original.startRequest().totalBytes(),
                        original.startRequest().chunkSizeBytes(),
                        new SourceArtifactCodec.DerivedRegion(
                                identity,
                                0,
                                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                                new SourceIdentityCodec.IntRect(0, 0, 7, 4),
                                new SourceIdentityCodec.ScaleTranslate2D(1.0, 1.0, 0.0, 0.0),
                                new BuildIdentityCodec.SoftwareBuildIdentity(
                                        "test-writer", "d".repeat(64), "e".repeat(64))),
                        original.startRequest().requiredLevels());
        PreparedSourceArtifact derived =
                new PreparedSourceArtifact(source, start, original.sealRequest());
        Path requestFile = directory.resolve("recovery/source-start.json");

        try (var gateway = new SourceArtifactGatewayStub(derived, requestFile);
                var executor =
                        new ModelGatewayExecutor(
                                new GatewayTransport(
                                        ModelGatewayExecutorTestData.storeFor(
                                                gateway.server(), directory)),
                                ModelGatewayExecutorTestData.capabilities())) {
            Files.write(source, Arrays.copyOf(bytes, bytes.length + 1));
            assertThrows(
                    ModelGatewayExecutor.PreparedSourceCorruptException.class,
                    () -> executor.publishSource(derived, requestFile));
            byte[] changed = bytes.clone();
            changed[0] ^= 0x7f;
            Files.write(source, changed);
            assertThrows(
                    ModelGatewayExecutor.PreparedSourceCorruptException.class,
                    () -> executor.publishSource(derived, requestFile));
            Files.delete(source);
            assertThrows(
                    ModelGatewayExecutor.PreparedSourceCorruptException.class,
                    () -> executor.publishSource(derived, requestFile));
            assertEquals(0, gateway.requestCount());
        }
    }

    @Test
    void originalSourceVersionChangeFailsBeforeNetworkEvenWhenBytesMatch(@TempDir Path directory)
            throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(12);
        Path source = directory.resolve("source.tif");
        var prepared = ModelGatewayExecutorTestData.prepared(source, bytes, 4);
        Path requestFile = directory.resolve("recovery/source-start.json");
        Files.setLastModifiedTime(
                source,
                java.nio.file.attribute.FileTime.fromMillis(
                        Files.getLastModifiedTime(source).toMillis() + 1_000));

        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile);
                var executor =
                        new ModelGatewayExecutor(
                                new GatewayTransport(
                                        ModelGatewayExecutorTestData.storeFor(
                                                gateway.server(), directory)),
                                ModelGatewayExecutorTestData.capabilities())) {
            SourceVersionChangedException error =
                    assertThrows(
                            SourceVersionChangedException.class,
                            () -> executor.publishSource(prepared, requestFile));
            assertTrue(error.getMessage().contains("content version changed"));
            assertEquals(0, gateway.requestCount());
            assertTrue(Files.isRegularFile(requestFile));
        }
    }
}
