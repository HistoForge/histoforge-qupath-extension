package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;

/** The caller must finish retaining COMPLETE output before execution can advance. */
class DurableModelResultRetentionTest {

    @Test
    void completeOutputPassesThroughRetentionBeforeItIsReturned(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.completeImmediately();
            var executor = executor(gateway, directory);
            var handle = submit(executor, gateway);
            var retained = new AtomicInteger();

            var terminal =
                    executor.awaitTerminal(
                            handle,
                            () -> false,
                            (artifact, manifest, downloads) -> {
                                assertTrue(manifest.complete());
                                var bytes =
                                        CanonicalCodec.canonicalBytes(
                                                ModelRunPublicationCodec.toJson(manifest));
                                assertEquals(artifact.sha256(), CanonicalCodec.sha256(bytes));
                                assertEquals(artifact.sizeBytes(), bytes.length);
                                var result =
                                        ModelResultReader.read(
                                                manifest,
                                                handle.request().resultBudget(),
                                                (input, budget) ->
                                                        downloads.featureStream(
                                                                input.featureStreamArtifact(),
                                                                budget,
                                                                handle.declaredClasses(),
                                                                input.featureCount(),
                                                                input.vertexCount(),
                                                                "retain test output"));
                                retained.incrementAndGet();
                                return result;
                            });

            assertTrue(terminal.complete());
            assertEquals(1, retained.get());
            assertEquals(2, terminal.completedInputs().size());
            assertEquals(0, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void diskFailureLeavesCompletedCloudWorkAvailableForAnotherAttempt(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.completeImmediately();
            var executor = executor(gateway, directory);
            var handle = submit(executor, gateway);
            var diskFull = new IOException("Insufficient disk space for retained results");

            assertSame(
                    diskFull,
                    assertThrows(
                            IOException.class,
                            () ->
                                    executor.awaitTerminal(
                                            handle,
                                            () -> false,
                                            (artifact, manifest, downloads) -> {
                                                throw diskFull;
                                            })));

            assertEquals(0, gateway.cancelCalls());
            assertTrue(executor.awaitTerminal(handle, () -> false).complete());
            assertEquals(1, gateway.postCalls());
            assertEquals(2, gateway.pollCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void partialOutputNeverUsesTheCompleteRetentionCallback(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.returnPartial();
            var executor = executor(gateway, directory);

            var terminal =
                    executor.awaitTerminal(
                            submit(executor, gateway),
                            () -> false,
                            (artifact, manifest, downloads) -> {
                                throw new AssertionError(
                                        "Partial output is not a complete local result");
                            });

            assertFalse(terminal.complete());
            assertEquals(1, terminal.completedInputs().size());
            assertEquals(0, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    private static DurableModelRunExecutor executor(
            DurableModelRunGatewayStub gateway, Path directory) throws Exception {
        return new DurableModelRunExecutor(
                new GatewayTransport(
                        ModelGatewayExecutorTestData.storeFor(gateway.server(), directory)),
                ModelGatewayExecutorTestData.capabilities());
    }

    private static ModelRunHandle submit(
            DurableModelRunExecutor executor, DurableModelRunGatewayStub gateway) throws Exception {
        return executor.submit(
                gateway.request(),
                gateway.runnerCapabilities(),
                Optional.empty(),
                gateway.declaredClasses());
    }
}
