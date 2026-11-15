package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;

/** End-to-end local HTTP tests for bounded finite call execution. */
class ModelGatewayExecutorFiniteTest {

    @Test
    void completesTheOwnedSubmitPollLifecycle(@TempDir Path directory) throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            var completed =
                    executor.executeFinite(
                            gateway.request(),
                            gateway.runnerCapabilities(),
                            gateway.declaredClasses(),
                            () -> false);

            assertEquals(FiniteInferenceGatewayStub.CALL_ID, completed.execution().backendCallId());
            assertEquals(2, completed.result().geojson().features().size());
            assertEquals(1, gateway.postCalls());
            assertEquals(2, gateway.pollCalls());
            assertEquals(0, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void cancelsAnAcceptedCallWithoutAcceptingALateResult(@TempDir Path directory)
            throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            AtomicInteger checks = new AtomicInteger();

            ModelGatewayExecutor.AcceptedFiniteCallCancellation cancelled =
                    assertThrows(
                            ModelGatewayExecutor.AcceptedFiniteCallCancellation.class,
                            () ->
                                    executor.executeFinite(
                                            gateway.request(),
                                            gateway.runnerCapabilities(),
                                            gateway.declaredClasses(),
                                            () -> checks.getAndIncrement() > 0));
            assertEquals(FiniteInferenceGatewayStub.CALL_ID, cancelled.execution().backendCallId());
            assertEquals(1, gateway.postCalls());
            assertEquals(0, gateway.pollCalls());
            assertEquals(1, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void discardsACompletionObservedAfterCancellation(@TempDir Path directory) throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            ModelGatewayExecutor.AcceptedFiniteCallCancellation cancelled =
                    assertThrows(
                            ModelGatewayExecutor.AcceptedFiniteCallCancellation.class,
                            () ->
                                    executor.executeFinite(
                                            gateway.request(),
                                            gateway.runnerCapabilities(),
                                            gateway.declaredClasses(),
                                            () -> gateway.pollCalls() >= 2));
            assertEquals(FiniteInferenceGatewayStub.CALL_ID, cancelled.execution().backendCallId());

            assertEquals(1, gateway.postCalls());
            assertEquals(2, gateway.pollCalls());
            assertEquals(1, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void submitsCanonicalRequestPollsExactOpaqueCallAndCancels(@TempDir Path directory)
            throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            var handle =
                    executor.submitFinite(
                            gateway.request(),
                            gateway.runnerCapabilities(),
                            gateway.declaredClasses());

            assertEquals(FiniteInferenceGatewayStub.CALL_ID, handle.backendCallId());
            assertEquals(ModelGatewayExecutorTestData.DEPLOYMENT_ID, handle.deploymentId());
            assertEquals(
                    FiniteInferenceGatewayStub.CALL_ID,
                    handle.executionReference().backendCallId());
            assertTrue(executor.pollFinite(handle).isEmpty());
            var result = executor.pollFinite(handle).orElseThrow();
            assertEquals(handle.request().actionId(), result.meta().actionId());
            assertEquals(handle.request().outputGenerationId(), result.meta().outputGenerationId());
            assertEquals(2, result.geojson().features().size());

            executor.cancelFinite(handle);
            assertEquals(1, gateway.postCalls());
            assertEquals(2, gateway.pollCalls());
            assertEquals(1, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void rejectsHandleFromAnotherDeploymentBeforePollOrCancellation(@TempDir Path directory)
            throws Exception {
        String deploymentB = "deployment-b";
        Path directoryA = Files.createDirectory(directory.resolve("a"));
        Path directoryB = Files.createDirectory(directory.resolve("b"));
        try (var gatewayA = new FiniteInferenceGatewayStub();
                var gatewayB = new FiniteInferenceGatewayStub();
                var executorA =
                        executor(gatewayA, directoryA, ModelGatewayExecutorTestData.DEPLOYMENT_ID);
                var executorB = executor(gatewayB, directoryB, deploymentB)) {
            var handle =
                    executorA.submitFinite(
                            gatewayA.request(),
                            gatewayA.runnerCapabilities(),
                            gatewayA.declaredClasses());

            assertThrows(IllegalArgumentException.class, () -> executorB.pollFinite(handle));
            assertThrows(IllegalArgumentException.class, () -> executorB.cancelFinite(handle));
            assertEquals(1, gatewayA.postCalls());
            assertEquals(0, gatewayA.pollCalls());
            assertEquals(0, gatewayA.cancelCalls());
            assertEquals(0, gatewayB.requestCount());
            gatewayA.requireNoFailure();
            gatewayB.requireNoFailure();
        }
    }

    @Test
    void ambiguousSubmitIsNotRetried(@TempDir Path directory) throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            gateway.loseSubmitResponse();

            assertThrows(
                    IOException.class,
                    () ->
                            executor.submitFinite(
                                    gateway.request(),
                                    gateway.runnerCapabilities(),
                                    gateway.declaredClasses()));
            assertEquals(1, gateway.postCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void mismatchedAcceptanceFailsWithoutReturningAHandle(@TempDir Path directory)
            throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            gateway.setAcceptedActionId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

            assertThrows(
                    InferenceException.class,
                    () ->
                            executor.submitFinite(
                                    gateway.request(),
                                    gateway.runnerCapabilities(),
                                    gateway.declaredClasses()));
            assertEquals(1, gateway.postCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void admissionLimitsAndRunnerIdentityFailBeforeNetwork(@TempDir Path directory)
            throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            var runner = gateway.runnerCapabilities();
            var finite = runner.finite();
            var tooSmall =
                    new RunnerCapabilitiesCodec.RunnerCapabilities(
                            runner.modes(),
                            runner.outputTypes(),
                            runner.sourceContainers(),
                            runner.runnerBuild(),
                            runner.rasterizer(),
                            runner.preprocessingContractDigest(),
                            new RunnerCapabilitiesCodec.FiniteCallLimits(
                                    finite.maxAttemptExecutionSeconds(),
                                    1,
                                    finite.targetRaster(),
                                    finite.maxInclusionMaskVertices(),
                                    finite.result()),
                            Optional.empty());
            var wrongIdentity =
                    new RunnerCapabilitiesCodec.RunnerCapabilities(
                            runner.modes(),
                            runner.outputTypes(),
                            runner.sourceContainers(),
                            runner.runnerBuild(),
                            runner.rasterizer(),
                            "a".repeat(64),
                            finite,
                            Optional.empty());
            var raster = gateway.request().rasterInput().frame().transform().raster();
            var targetTooSmall =
                    copyRunner(
                            runner,
                            runner.sourceContainers(),
                            new RunnerCapabilitiesCodec.FiniteCallLimits(
                                    finite.maxAttemptExecutionSeconds(),
                                    finite.maxRequestCanonicalBytes(),
                                    new ExecutionLimitsCodec.TargetRasterLimits(
                                            raster.width() - 1,
                                            finite.targetRaster().maxHeight(),
                                            finite.targetRaster().maxPixels()),
                                    finite.maxInclusionMaskVertices(),
                                    finite.result()));
            long maskVertices =
                    RasterInputCodec.geometryVertexCount(
                            gateway.request().rasterInput().frame().inclusionMaskRaster());
            var maskTooSmall =
                    copyRunner(
                            runner,
                            runner.sourceContainers(),
                            new RunnerCapabilitiesCodec.FiniteCallLimits(
                                    finite.maxAttemptExecutionSeconds(),
                                    finite.maxRequestCanonicalBytes(),
                                    finite.targetRaster(),
                                    maskVertices - 1,
                                    finite.result()));
            var resultTooSmall =
                    copyRunner(
                            runner,
                            runner.sourceContainers(),
                            new RunnerCapabilitiesCodec.FiniteCallLimits(
                                    finite.maxAttemptExecutionSeconds(),
                                    finite.maxRequestCanonicalBytes(),
                                    finite.targetRaster(),
                                    finite.maxInclusionMaskVertices(),
                                    new ExecutionLimitsCodec.ResultLimits(
                                            gateway.request().resultBudget().maxTransferredBytes()
                                                    - 1,
                                            finite.result().maxDecodedBytes(),
                                            finite.result().maxRecordBytes(),
                                            finite.result().maxFeatures(),
                                            finite.result().maxVertices())));
            var unsupportedSource =
                    copyRunner(runner, Set.of(SourceArtifactCodec.SourceContainer.PNG), finite);

            for (var invalid :
                    List.of(
                            tooSmall,
                            wrongIdentity,
                            targetTooSmall,
                            maskTooSmall,
                            resultTooSmall,
                            unsupportedSource)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                executor.submitFinite(
                                        gateway.request(), invalid, gateway.declaredClasses()));
            }
            assertEquals(0, gateway.requestCount());
            gateway.requireNoFailure();
        }
    }

    @Test
    void resultBodyUsesNegotiatedCapButTypedErrorsUseControlCap(@TempDir Path directory)
            throws Exception {
        FiniteInferenceCodec.FiniteInferenceRequest base;
        try (var fixture = new FiniteInferenceGatewayStub()) {
            base = fixture.request();
        }
        var budgetOne =
                new FiniteInferenceCodec.FiniteInferenceRequest(
                        base.schemaVersion(),
                        base.actionId(),
                        base.outputGenerationId(),
                        base.inputId(),
                        base.producer(),
                        base.expectedBackend(),
                        base.rasterInput(),
                        new FiniteInferenceCodec.ClientResultBudget(1, 1, 1, 1, 1));

        try (var gateway = new FiniteInferenceGatewayStub(budgetOne);
                var executor = executor(gateway, directory)) {
            var handle =
                    executor.submitFinite(
                            gateway.request(),
                            gateway.runnerCapabilities(),
                            gateway.declaredClasses());
            byte[] typedError =
                    CanonicalCodec.canonicalBytes(
                            CanonicalCodec.parseJson(
                                    "{\"schema_version\":1,\"code\":\"SERVICE_UNAVAILABLE\","
                                            + "\"message\":\"retry later\"}"));
            gateway.replyToPoll(503, typedError);
            InferenceException error =
                    assertThrows(InferenceException.class, () -> executor.pollFinite(handle));
            assertEquals(
                    GatewayErrorCodec.Code.SERVICE_UNAVAILABLE,
                    error.gatewayError().orElseThrow().code());

            gateway.replyToPoll(200, "{}".getBytes(StandardCharsets.UTF_8));
            IOException oversized =
                    assertThrows(IOException.class, () -> executor.pollFinite(handle));
            assertTrue(oversized.getMessage().contains("negotiated byte limit"));
            gateway.requireNoFailure();
        }
    }

    @Test
    void failureAfterAcceptanceRetainsTheExactFiniteCall(@TempDir Path directory) throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            byte[] typedError =
                    CanonicalCodec.canonicalBytes(
                            CanonicalCodec.parseJson(
                                    "{\"schema_version\":1,\"code\":\"CALL_EXPIRED\","
                                            + "\"message\":\"result retention expired\"}"));
            gateway.replyToPoll(410, typedError);

            ModelGatewayExecutor.AcceptedFiniteCallFailure failure =
                    assertThrows(
                            ModelGatewayExecutor.AcceptedFiniteCallFailure.class,
                            () ->
                                    executor.executeFinite(
                                            gateway.request(),
                                            gateway.runnerCapabilities(),
                                            gateway.declaredClasses(),
                                            () -> false));

            assertEquals(FiniteInferenceGatewayStub.CALL_ID, failure.execution().backendCallId());
            InferenceException original =
                    assertInstanceOf(InferenceException.class, failure.originalFailure());
            assertEquals(
                    GatewayErrorCodec.Code.CALL_EXPIRED,
                    original.gatewayError().orElseThrow().code());
            assertEquals(1, gateway.postCalls());
            assertEquals(1, gateway.pollCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void deploymentReplacementAfterAcceptanceRetainsTheExactFiniteCall(@TempDir Path directory)
            throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            String url = "http://localhost:" + gateway.server().getAddress().getPort();
            gateway.beforeSubmitResponse(
                    () -> {
                        try {
                            Files.writeString(
                                    directory.resolve("deployment.json"),
                                    ModelGatewayExecutorTestData.deployment(url, "deployment-b")
                                            .toJson()
                                            .toString());
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    });

            ModelGatewayExecutor.AcceptedFiniteCallFailure failure =
                    assertThrows(
                            ModelGatewayExecutor.AcceptedFiniteCallFailure.class,
                            () ->
                                    executor.executeFinite(
                                            gateway.request(),
                                            gateway.runnerCapabilities(),
                                            gateway.declaredClasses(),
                                            () -> false));

            assertEquals(FiniteInferenceGatewayStub.CALL_ID, failure.execution().backendCallId());
            GatewayTransport.DeploymentChangedException original =
                    assertInstanceOf(
                            GatewayTransport.DeploymentChangedException.class,
                            failure.originalFailure());
            assertTrue(original.getMessage().contains("deployment changed"));
            assertEquals(1, gateway.postCalls());
            assertEquals(0, gateway.pollCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void interruptionDuringResponseBodyRetainsTheExactFiniteCall(@TempDir Path directory)
            throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            gateway.pausePollBody();
            AtomicReference<Throwable> observed = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread worker =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            executor.executeFinite(
                                                    gateway.request(),
                                                    gateway.runnerCapabilities(),
                                                    gateway.declaredClasses(),
                                                    () -> false);
                                        } catch (Throwable failure) {
                                            observed.set(failure);
                                            interruptRestored.set(
                                                    Thread.currentThread().isInterrupted());
                                        }
                                    });
            try {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
                boolean readingBody = false;
                while (worker.isAlive() && System.nanoTime() < deadline) {
                    readingBody =
                            gateway.pollCalls() == 1
                                    && Arrays.stream(worker.getStackTrace())
                                            .anyMatch(
                                                    frame ->
                                                            frame.getClassName()
                                                                            .equals(
                                                                                    GatewayTransport
                                                                                            .class
                                                                                            .getName())
                                                                    && frame.getMethodName()
                                                                            .equals("readBounded"));
                    if (readingBody) {
                        break;
                    }
                    Thread.sleep(10);
                }
                assertTrue(readingBody, "finite worker did not enter the response-body read");
            } finally {
                worker.interrupt();
                worker.join(java.time.Duration.ofSeconds(5));
                gateway.releasePollBody();
            }

            assertTrue(!worker.isAlive(), "interrupted finite worker did not terminate");
            ModelGatewayExecutor.AcceptedFiniteCallCancellation cancelled =
                    assertInstanceOf(
                            ModelGatewayExecutor.AcceptedFiniteCallCancellation.class,
                            observed.get(),
                            () -> "Observed finite interruption: " + observed.get());
            assertInstanceOf(IOException.class, cancelled.getCause().getCause());
            assertEquals(FiniteInferenceGatewayStub.CALL_ID, cancelled.execution().backendCallId());
            assertTrue(interruptRestored.get());
            assertEquals(1, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void interruptionBeforePollingRetainsTheExactFiniteCall(@TempDir Path directory)
            throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub();
                var executor = executor(gateway, directory)) {
            AtomicInteger checks = new AtomicInteger();
            try {
                var cancelled =
                        assertThrows(
                                ModelGatewayExecutor.AcceptedFiniteCallCancellation.class,
                                () ->
                                        executor.executeFinite(
                                                gateway.request(),
                                                gateway.runnerCapabilities(),
                                                gateway.declaredClasses(),
                                                () -> {
                                                    if (checks.getAndIncrement() == 1) {
                                                        // Interrupt after acceptance, before the
                                                        // next credential or HTTP body read.
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    return false;
                                                }));
                assertEquals(
                        FiniteInferenceGatewayStub.CALL_ID, cancelled.execution().backendCallId());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(1, gateway.postCalls());
            assertEquals(0, gateway.pollCalls());
            assertEquals(1, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void closedExecutorRejectsFiniteCallsBeforeNetwork(@TempDir Path directory) throws Exception {
        try (var gateway = new FiniteInferenceGatewayStub()) {
            var executor = executor(gateway, directory);
            executor.close();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            executor.submitFinite(
                                    gateway.request(),
                                    gateway.runnerCapabilities(),
                                    gateway.declaredClasses()));
            assertEquals(0, gateway.requestCount());
            gateway.requireNoFailure();
        }
    }

    private static RunnerCapabilitiesCodec.RunnerCapabilities copyRunner(
            RunnerCapabilitiesCodec.RunnerCapabilities source,
            Set<SourceArtifactCodec.SourceContainer> containers,
            RunnerCapabilitiesCodec.FiniteCallLimits finite) {
        return new RunnerCapabilitiesCodec.RunnerCapabilities(
                source.modes(),
                source.outputTypes(),
                containers,
                source.runnerBuild(),
                source.rasterizer(),
                source.preprocessingContractDigest(),
                finite,
                source.durableRun());
    }

    private static ModelGatewayExecutor executor(FiniteInferenceGatewayStub gateway, Path directory)
            throws IOException {
        return executor(gateway, directory, ModelGatewayExecutorTestData.DEPLOYMENT_ID);
    }

    private static ModelGatewayExecutor executor(
            FiniteInferenceGatewayStub gateway, Path directory, String deploymentId)
            throws IOException {
        return new ModelGatewayExecutor(
                new GatewayTransport(
                        ModelGatewayExecutorTestData.storeFor(
                                gateway.server(), directory, deploymentId)),
                ModelGatewayExecutorTestData.capabilities(deploymentId));
    }
}
