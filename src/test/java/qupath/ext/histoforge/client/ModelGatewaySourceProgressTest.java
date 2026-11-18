package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.histoforge.client.SourceTransferProgress.Phase.PREPARING;
import static qupath.ext.histoforge.client.SourceTransferProgress.Phase.READY;
import static qupath.ext.histoforge.client.SourceTransferProgress.Phase.UPLOADING;
import static qupath.ext.histoforge.client.SourceTransferProgress.Phase.VERIFYING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion.SourceVersionChangedException;

/** Byte progress follows validated gateway receipts, not requests or local transfer attempts. */
class ModelGatewaySourceProgressTest {

    @Test
    void validatesPhaseAndByteCounts() {
        assertEquals(
                new SourceTransferProgress(PREPARING, 0, 0), SourceTransferProgress.preparing());
        assertEquals(
                32L << 30, new SourceTransferProgress(READY, 32L << 30, 32L << 30).totalBytes());
        assertThrows(NullPointerException.class, () -> new SourceTransferProgress(null, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SourceTransferProgress(UPLOADING, -1, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SourceTransferProgress(UPLOADING, 11, 10));
        assertThrows(
                IllegalArgumentException.class, () -> new SourceTransferProgress(PREPARING, 1, 10));
        for (var phase : List.of(UPLOADING, VERIFYING, READY)) {
            assertThrows(
                    IllegalArgumentException.class, () -> new SourceTransferProgress(phase, 0, 0));
        }
        for (var phase : List.of(VERIFYING, READY)) {
            assertThrows(
                    IllegalArgumentException.class, () -> new SourceTransferProgress(phase, 9, 10));
        }
    }

    @Test
    void countsShortRetriedChunkOnceAndReportsOutOfOrderAcksOnCaller(@TempDir Path directory)
            throws Exception {
        var prepared =
                ModelGatewayExecutorTestData.prepared(
                        directory.resolve("source.tif"),
                        ModelGatewayExecutorTestData.sourceBytes(10),
                        4);
        Path requestFile = directory.resolve("source-start.json");
        List<SourceTransferProgress> observed = new ArrayList<>();
        CountDownLatch shortChunkAcknowledged = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile)) {
            gateway.preaccept(1);
            gateway.loseNextChunkResponse(2);
            gateway.loseNextSealResponse();
            gateway.afterAcceptingChunk(0, () -> await(shortChunkAcknowledged));
            try (var executor = executor(gateway, directory, 2)) {
                executor.publishSource(
                        prepared,
                        requestFile,
                        progress -> {
                            assertSame(caller, Thread.currentThread());
                            observed.add(progress);
                            if (progress.phase() == UPLOADING
                                    && progress.acknowledgedBytes() == 6) {
                                shortChunkAcknowledged.countDown();
                            }
                        });
            }
            assertEquals(
                    List.of(
                            SourceTransferProgress.preparing(),
                            new SourceTransferProgress(UPLOADING, 4, 10),
                            new SourceTransferProgress(UPLOADING, 6, 10),
                            new SourceTransferProgress(UPLOADING, 10, 10),
                            new SourceTransferProgress(VERIFYING, 10, 10),
                            new SourceTransferProgress(READY, 10, 10)),
                    observed);
            assertEquals(2, gateway.chunkCalls(2));
            assertEquals(0, gateway.chunkCalls(1));
            assertEquals(1, gateway.sealCalls());
            gateway.requireNoFailure();
        } finally {
            shortChunkAcknowledged.countDown();
        }
    }

    @Test
    void resumesAtExactAcknowledgedBytesAndReusesSealedSource(@TempDir Path directory)
            throws Exception {
        var prepared =
                ModelGatewayExecutorTestData.prepared(
                        directory.resolve("source.tif"),
                        ModelGatewayExecutorTestData.sourceBytes(10),
                        4);
        Path requestFile = directory.resolve("source-start.json");
        List<SourceTransferProgress> failed = new ArrayList<>();
        List<SourceTransferProgress> resumed = new ArrayList<>();
        List<SourceTransferProgress> sealedReuse = new ArrayList<>();
        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile);
                var executor = executor(gateway, directory, 1)) {
            gateway.failChunk(2);
            assertThrows(
                    InferenceException.class,
                    () -> executor.publishSource(prepared, requestFile, failed::add));
            assertEquals(
                    List.of(
                            SourceTransferProgress.preparing(),
                            new SourceTransferProgress(UPLOADING, 0, 10),
                            new SourceTransferProgress(UPLOADING, 4, 10),
                            new SourceTransferProgress(UPLOADING, 8, 10)),
                    failed);
            gateway.failChunk(null);
            var sealed = executor.resumeSource(prepared.path(), requestFile, resumed::add);
            assertEquals(
                    List.of(
                            SourceTransferProgress.preparing(),
                            new SourceTransferProgress(UPLOADING, 8, 10),
                            new SourceTransferProgress(UPLOADING, 10, 10),
                            new SourceTransferProgress(VERIFYING, 10, 10),
                            new SourceTransferProgress(READY, 10, 10)),
                    resumed);
            assertEquals(
                    sealed, executor.resumeSource(prepared.path(), requestFile, sealedReuse::add));
            assertEquals(
                    List.of(
                            SourceTransferProgress.preparing(),
                            new SourceTransferProgress(VERIFYING, 10, 10),
                            new SourceTransferProgress(READY, 10, 10)),
                    sealedReuse);
            assertEquals(1, gateway.sealCalls());
            assertEquals(1, gateway.chunkCalls(0));
            assertEquals(1, gateway.chunkCalls(1));
            assertEquals(2, gateway.chunkCalls(2));
            assertEquals(4, failed.size(), "failed publication received later callbacks");
            gateway.requireNoFailure();
        }
    }

    @Test
    void invalidAcknowledgementNeverAdvancesProgress(@TempDir Path directory) throws Exception {
        var prepared =
                ModelGatewayExecutorTestData.prepared(
                        directory.resolve("source.tif"),
                        ModelGatewayExecutorTestData.sourceBytes(4),
                        4);
        Path requestFile = directory.resolve("source-start.json");
        List<SourceTransferProgress> observed = new ArrayList<>();
        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile);
                var executor = executor(gateway, directory, 1)) {
            gateway.invalidateChunkAcknowledgement(0);
            assertThrows(
                    InferenceException.class,
                    () -> executor.publishSource(prepared, requestFile, observed::add));
            assertEquals(
                    List.of(
                            SourceTransferProgress.preparing(),
                            new SourceTransferProgress(UPLOADING, 0, 4)),
                    observed);
            assertEquals(0, gateway.sealCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void finalDigestFailureReportsVerificationButNeverReady(@TempDir Path directory)
            throws Exception {
        byte[] bytes = ModelGatewayExecutorTestData.sourceBytes(4);
        var prepared =
                ModelGatewayExecutorTestData.prepared(directory.resolve("source.tif"), bytes, 4);
        Path requestFile = directory.resolve("source-start.json");
        List<SourceTransferProgress> observed = new ArrayList<>();
        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile);
                var executor = executor(gateway, directory, 1)) {
            assertThrows(
                    SourceVersionChangedException.class,
                    () ->
                            executor.publishSource(
                                    prepared,
                                    requestFile,
                                    progress -> {
                                        observed.add(progress);
                                        if (progress.phase() == VERIFYING) {
                                            try {
                                                Files.write(prepared.path(), new byte[5]);
                                            } catch (IOException error) {
                                                throw new AssertionError(error);
                                            }
                                        }
                                    }));
            assertEquals(VERIFYING, observed.getLast().phase());
            assertFalse(observed.stream().anyMatch(progress -> progress.phase() == READY));
            assertEquals(0, gateway.sealCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void sealedReuseStillChecksDeploymentBeforeReady(@TempDir Path directory) throws Exception {
        var prepared =
                ModelGatewayExecutorTestData.prepared(
                        directory.resolve("source.tif"),
                        ModelGatewayExecutorTestData.sourceBytes(4),
                        4);
        Path requestFile = directory.resolve("source-start.json");
        List<SourceTransferProgress> observed = new ArrayList<>();
        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile);
                var executor = executor(gateway, directory, 1)) {
            executor.publishSource(prepared, requestFile);
            assertThrows(
                    GatewayTransport.DeploymentChangedException.class,
                    () ->
                            executor.resumeSource(
                                    prepared.path(),
                                    requestFile,
                                    progress -> {
                                        observed.add(progress);
                                        if (progress.phase() == VERIFYING) {
                                            try {
                                                ModelGatewayExecutorTestData.storeFor(
                                                        gateway.server(),
                                                        directory,
                                                        "deployment-b");
                                            } catch (IOException error) {
                                                throw new AssertionError(error);
                                            }
                                        }
                                    }));
            assertEquals(
                    List.of(
                            SourceTransferProgress.preparing(),
                            new SourceTransferProgress(VERIFYING, 4, 4)),
                    observed);
            assertEquals(1, gateway.sealCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void interruptedPublicationCannotEmitFromLateChunkWorkers(@TempDir Path directory)
            throws Exception {
        var prepared =
                ModelGatewayExecutorTestData.prepared(
                        directory.resolve("source.tif"),
                        ModelGatewayExecutorTestData.sourceBytes(8),
                        4);
        Path requestFile = directory.resolve("source-start.json");
        List<SourceTransferProgress> observed = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch remoteAccepted = new CountDownLatch(1);
        try (var gateway = new SourceArtifactGatewayStub(prepared, requestFile);
                var executor = executor(gateway, directory, 1)) {
            gateway.blockFirstChunkRequests(1);
            gateway.afterAcceptingChunk(0, remoteAccepted::countDown);
            Thread caller =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            executor.publishSource(
                                                    prepared, requestFile, observed::add);
                                        } catch (Throwable error) {
                                            failure.set(error);
                                        } finally {
                                            finished.countDown();
                                        }
                                    });
            try {
                assertTrue(gateway.awaitBlockedChunks());
                caller.interrupt();
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                assertInstanceOf(InterruptedException.class, failure.get());
                List<SourceTransferProgress> atFailure = List.copyOf(observed);
                gateway.unblockChunkRequests();
                assertTrue(remoteAccepted.await(5, TimeUnit.SECONDS));
                List<SourceTransferProgress> resumed = new ArrayList<>();
                executor.resumeSource(prepared.path(), requestFile, resumed::add);
                assertEquals(atFailure, observed, "old upload worker emitted into a later source");
                assertEquals(READY, resumed.getLast().phase());
            } finally {
                caller.interrupt();
                gateway.unblockChunkRequests();
                caller.join(5000);
            }
        }
    }

    private static ModelGatewayExecutor executor(
            SourceArtifactGatewayStub gateway, Path directory, int concurrency) throws IOException {
        return new ModelGatewayExecutor(
                new GatewayTransport(
                        ModelGatewayExecutorTestData.storeFor(gateway.server(), directory)),
                ModelGatewayExecutorTestData.capabilities(),
                concurrency);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "expected acknowledgement did not arrive");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
