package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactRef;

/** Recovery checks an immutable cloud upload without depending on its former local bytes. */
class SealedSourceVerificationTest {

    @TempDir Path directory;

    @Test
    void verifiesFromSavedIdentityAfterLocalImageDeletionWithOnlyOneStatusRead() throws Exception {
        var prepared =
                ModelGatewayExecutorTestData.prepared(
                        directory.resolve("source.tif"),
                        ModelGatewayExecutorTestData.sourceBytes(12),
                        4);
        Path startFile = directory.resolve("source-start.json");
        try (var gateway = new SourceArtifactGatewayStub(prepared, startFile);
                var executor =
                        new ModelGatewayExecutor(
                                new GatewayTransport(
                                        ModelGatewayExecutorTestData.storeFor(
                                                gateway.server(), directory)),
                                ModelGatewayExecutorTestData.capabilities())) {
            var sealed = executor.publishSource(prepared, startFile);
            Files.delete(prepared.path());
            int requests = gateway.requestCount();
            List<SourceTransferProgress> progress = new ArrayList<>();

            executor.verifySealedSource(prepared.startRequest(), sealed.artifact(), progress::add);

            assertEquals(requests + 1, gateway.requestCount());
            assertEquals(1, gateway.startCalls());
            assertEquals(1, gateway.sealCalls());
            assertEquals(
                    List.of(
                            SourceTransferProgress.Phase.VERIFYING,
                            SourceTransferProgress.Phase.READY),
                    progress.stream().map(SourceTransferProgress::phase).toList());
            assertTrue(Files.isRegularFile(startFile));
            gateway.requireNoFailure();
        }
    }

    @Test
    void rejectsDifferentManifestAndNeverRecreatesMissingCloudSource() throws Exception {
        var prepared =
                ModelGatewayExecutorTestData.prepared(
                        directory.resolve("source.tif"),
                        ModelGatewayExecutorTestData.sourceBytes(12),
                        4);
        Path startFile = directory.resolve("source-start.json");
        try (var gateway = new SourceArtifactGatewayStub(prepared, startFile);
                var executor =
                        new ModelGatewayExecutor(
                                new GatewayTransport(
                                        ModelGatewayExecutorTestData.storeFor(
                                                gateway.server(), directory)),
                                ModelGatewayExecutorTestData.capabilities())) {
            var expected = executor.publishSource(prepared, startFile).artifact();
            Files.delete(prepared.path());
            var differentManifest =
                    new SourceArtifactRef(
                            expected.artifactId(),
                            expected.creationActionId(),
                            expected.startRequestDigest(),
                            expected.bytesDigest(),
                            "f".repeat(64),
                            expected.container());
            assertThrows(
                    InferenceException.class,
                    () ->
                            executor.verifySealedSource(
                                    prepared.startRequest(), differentManifest, ignored -> {}));

            executor.releaseSource(expected.artifactId());
            var absent =
                    assertThrows(
                            InferenceException.class,
                            () ->
                                    executor.verifySealedSource(
                                            prepared.startRequest(), expected, ignored -> {}));
            assertEquals(
                    GatewayErrorCodec.Code.ARTIFACT_NOT_FOUND,
                    absent.gatewayError().orElseThrow().code());
            assertEquals(1, gateway.startCalls());
            assertEquals(1, gateway.sealCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void doesNotFinishAnUploadWhenSavedStateClaimsItWasAlreadySealed() throws Exception {
        var prepared =
                ModelGatewayExecutorTestData.prepared(
                        directory.resolve("source.tif"),
                        ModelGatewayExecutorTestData.sourceBytes(12),
                        4);
        Path startFile = directory.resolve("source-start.json");
        try (var gateway = new SourceArtifactGatewayStub(prepared, startFile);
                var executor =
                        new ModelGatewayExecutor(
                                new GatewayTransport(
                                        ModelGatewayExecutorTestData.storeFor(
                                                gateway.server(), directory)),
                                ModelGatewayExecutorTestData.capabilities(),
                                1)) {
            gateway.failChunk(2);
            assertThrows(
                    InferenceException.class, () -> executor.publishSource(prepared, startFile));
            var expected =
                    new SourceArtifactRef(
                            prepared.startRequest().artifactId(),
                            prepared.startRequest().creationActionId(),
                            qupath.ext.histoforge.contract.SourceArtifactCodec.startRequestDigest(
                                    prepared.startRequest()),
                            prepared.sealRequest().bytesDigest(),
                            "f".repeat(64),
                            prepared.startRequest().container());
            int requests = gateway.requestCount();

            assertThrows(
                    InferenceException.class,
                    () ->
                            executor.verifySealedSource(
                                    prepared.startRequest(), expected, ignored -> {}));

            assertEquals(requests + 1, gateway.requestCount());
            assertEquals(0, gateway.sealCalls());
            assertEquals(1, gateway.chunkCalls(2));
            gateway.requireNoFailure();
        }
    }

    @Test
    void deploymentChangePreventsReadingAnotherDeploymentsSource() throws Exception {
        var prepared =
                ModelGatewayExecutorTestData.prepared(
                        directory.resolve("source.tif"),
                        ModelGatewayExecutorTestData.sourceBytes(12),
                        4);
        Path startFile = directory.resolve("source-start.json");
        try (var gateway = new SourceArtifactGatewayStub(prepared, startFile)) {
            var store = ModelGatewayExecutorTestData.storeFor(gateway.server(), directory);
            try (var executor =
                    new ModelGatewayExecutor(
                            new GatewayTransport(store),
                            ModelGatewayExecutorTestData.capabilities())) {
                var expected = executor.publishSource(prepared, startFile).artifact();
                int requests = gateway.requestCount();
                ModelGatewayExecutorTestData.storeFor(gateway.server(), directory, "deployment-b");

                assertThrows(
                        GatewayTransport.DeploymentChangedException.class,
                        () ->
                                executor.verifySealedSource(
                                        prepared.startRequest(), expected, ignored -> {}));

                assertEquals(requests, gateway.requestCount());
                gateway.requireNoFailure();
            }
        }
    }
}
