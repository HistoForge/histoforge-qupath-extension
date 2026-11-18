package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;

/** End-to-end local HTTP tests for durable run control and immutable result download. */
class DurableModelRunExecutorTest {

    @Test
    void inspectionValidatesTerminalMetadataWithoutDownloadingPredictions(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.completeImmediately();
            gateway.alterFirstFeatureGzipHeader();
            DurableModelRunExecutor executor = executor(gateway, directory);

            var observed =
                    executor.inspect(
                            gateway.request(),
                            gateway.runnerCapabilities().durableRun().orElseThrow().run());

            assertEquals(
                    qupath.ext.histoforge.contract.ModelRunStatusCodec.Phase.COMPLETE,
                    observed.status().phase());
            assertTrue(observed.manifest().orElseThrow().complete());
            assertEquals(0, gateway.postCalls());
            assertEquals(1, gateway.pollCalls());
            assertEquals(1, gateway.artifactCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void inspectionOfActiveWorkDoesNotDownloadAnyArtifacts(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            var observed =
                    executor(gateway, directory)
                            .inspect(
                                    gateway.request(),
                                    gateway.runnerCapabilities().durableRun().orElseThrow().run());
            assertEquals(
                    qupath.ext.histoforge.contract.ModelRunStatusCodec.Phase.QUEUED,
                    observed.status().phase());
            assertTrue(observed.manifest().isEmpty());
            assertEquals(0, gateway.artifactCalls());
            assertEquals(0, gateway.postCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void recordedRunCanBeCancelledWithoutModelAdmission(@TempDir Path directory) throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            executor(gateway, directory).cancel(gateway.request().modelRunId());
            assertEquals(1, gateway.cancelCalls());
            assertEquals(0, gateway.postCalls());
            assertEquals(0, gateway.pollCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void liveStatusListenerCannotChangeExecutionOutcome(@TempDir Path directory) throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.completeImmediately();
            var observed =
                    new java.util.ArrayList<
                            qupath.ext.histoforge.contract.ModelRunStatusCodec.Status>();
            var executor =
                    new DurableModelRunExecutor(
                            transport(gateway, directory),
                            ModelGatewayExecutorTestData.capabilities(),
                            status -> {
                                observed.add(status);
                                throw new IllegalStateException("closed display");
                            });
            var result = executor.awaitTerminal(submit(executor, gateway), () -> false);
            assertTrue(result.complete());
            assertEquals(List.of(result.status()), observed);
            assertEquals(1, gateway.pollCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void awaitTerminalReturnsTheValidatedCompleteObservation(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.completeImmediately();
            DurableModelRunExecutor executor = executor(gateway, directory);

            ModelRunObservation.Terminal terminal =
                    executor.awaitTerminal(submit(executor, gateway), () -> false);

            assertTrue(terminal.complete());
            assertEquals(1, gateway.pollCalls());
            assertEquals(0, gateway.cancelCalls());
            assertEquals(
                    ModelGatewayExecutorTestData.capabilities().limits().maxJsonRequestBytes(),
                    executor.maxRequestCanonicalBytes());
            gateway.requireNoFailure();
        }
    }

    @Test
    void awaitTerminalCancelsBeforePollingWhenTheActionStops(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            DurableModelRunExecutor executor = executor(gateway, directory);
            ModelRunHandle handle = submit(executor, gateway);

            assertThrows(
                    CancellationException.class, () -> executor.awaitTerminal(handle, () -> true));

            assertEquals(0, gateway.pollCalls());
            assertEquals(1, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void completesOnlyAfterRequestManifestAndEveryFeatureStreamValidate(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            DurableModelRunExecutor executor = executor(gateway, directory);
            ModelRunHandle handle = submit(executor, gateway);

            var active = assertInstanceOf(ModelRunObservation.Active.class, executor.poll(handle));
            assertEquals(
                    qupath.ext.histoforge.contract.ModelRunStatusCodec.Phase.QUEUED,
                    active.status().phase());
            assertEquals(0, gateway.artifactCalls());

            var terminal =
                    assertInstanceOf(ModelRunObservation.Terminal.class, executor.poll(handle));
            assertTrue(terminal.complete());
            assertEquals(Set.of(0, 3), terminal.completedInputs().keySet());
            assertEquals(2, terminal.completedInputs().get(0).featureCount());
            assertEquals(6, terminal.completedInputs().get(0).vertexCount());
            assertEquals(0, terminal.completedInputs().get(3).featureCount());
            assertEquals(handle.request().modelRunId(), terminal.executionReference().modelRunId());
            assertEquals(
                    terminal.manifest().orElseThrow().manifestDigest(),
                    terminal.executionReference().resultManifestDigest());

            assertEquals(1, gateway.postCalls());
            assertEquals(2, gateway.pollCalls());
            assertEquals(4, gateway.artifactCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void partialRetainsValidatedCompletedInputsButIsNotCommittable(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.returnPartial();
            DurableModelRunExecutor executor = executor(gateway, directory);

            var terminal =
                    assertInstanceOf(
                            ModelRunObservation.Terminal.class,
                            executor.poll(submit(executor, gateway)));

            assertFalse(terminal.complete());
            assertEquals(Set.of(0), terminal.completedInputs().keySet());
            assertFalse(terminal.manifest().orElseThrow().complete());
            assertThrows(IllegalStateException.class, terminal::executionReference);
            assertEquals(3, gateway.artifactCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void exactSubmitReplayRecoversALostAcceptance(@TempDir Path directory) throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.loseFirstSubmitResponse();
            DurableModelRunExecutor executor = executor(gateway, directory);

            ModelRunHandle handle = submit(executor, gateway);

            assertEquals(gateway.request().modelRunId(), handle.accepted().modelRunId());
            assertEquals(2, gateway.postCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void adoptedRetainedRequestPollsWithoutAnotherStart(@TempDir Path directory) throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.completeImmediately();
            DurableModelRunExecutor executor = executor(gateway, directory);

            ModelRunHandle handle =
                    executor.adopt(
                            gateway.request(),
                            gateway.runnerCapabilities(),
                            Optional.empty(),
                            gateway.declaredClasses());
            var terminal =
                    assertInstanceOf(ModelRunObservation.Terminal.class, executor.poll(handle));

            assertTrue(terminal.complete());
            assertEquals(gateway.request(), handle.request());
            assertEquals(0, gateway.postCalls());
            assertEquals(1, gateway.pollCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void adoptedRetainedRequestCanExceedTheLoweredGatewayJsonCeiling(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.completeImmediately();
            var executor = executorWithLoweredJsonCeiling(gateway, directory);

            var handle =
                    executor.adopt(
                            gateway.request(),
                            gateway.runnerCapabilities(),
                            Optional.empty(),
                            gateway.declaredClasses());
            var terminal =
                    assertInstanceOf(ModelRunObservation.Terminal.class, executor.poll(handle));

            assertTrue(terminal.complete());
            assertEquals(gateway.request(), handle.request());
            assertTrue(
                    CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(handle.request()))
                                    .length
                            > executor.maxRequestCanonicalBytes());
            assertEquals(
                    gateway.request().modelRunId(), terminal.executionReference().modelRunId());
            assertEquals(Set.of(0, 3), terminal.completedInputs().keySet());
            assertEquals(0, gateway.postCalls());
            assertEquals(1, gateway.pollCalls());
            assertEquals(4, gateway.artifactCalls());
            gateway.requireNoFailure();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshAndReplacementSubmissionsStillEnforceTheLoweredGatewayJsonCeiling(
            boolean replacement, @TempDir Path directory) throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            var executor = executorWithLoweredJsonCeiling(gateway, directory);
            var original = gateway.request();
            var request =
                    new ModelRunRequestCodec.ModelRunRequest(
                            original.schemaVersion(),
                            original.actionId(),
                            "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                            original.producer(),
                            original.expectedBackend(),
                            original.expectedProfile(),
                            original.inputs(),
                            original.resultBudget(),
                            replacement
                                    ? Optional.of(
                                            new ModelRunRequestCodec.ModelRunResumeSource(
                                                    original.modelRunId(), "a".repeat(64)))
                                    : Optional.empty(),
                            original.haloPxOverride());
            assertTrue(
                    CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(request)).length
                            > executor.maxRequestCanonicalBytes());

            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    executor.submit(
                                            request,
                                            gateway.runnerCapabilities(),
                                            Optional.empty(),
                                            gateway.declaredClasses()));

            assertTrue(failure.getMessage().contains("gateway JSON limit"));
            assertEquals(0, gateway.postCalls());
            assertEquals(0, gateway.pollCalls());
            assertEquals(0, gateway.artifactCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void validatedAcceptanceIsReturnedBeforeLaterDeploymentDrift(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            Path credential = directory.resolve("deployment.json");
            GatewayTransport transport =
                    new GatewayTransport(
                            ModelGatewayExecutorTestData.storeFor(gateway.server(), directory));
            DurableModelRunExecutor executor =
                    new DurableModelRunExecutor(
                            transport, ModelGatewayExecutorTestData.capabilities());
            gateway.beforeSubmitResponse(
                    () -> replaceDeployment(gateway, credential, "deployment-b"));

            ModelRunHandle handle = submit(executor, gateway);

            assertEquals(ModelGatewayExecutorTestData.DEPLOYMENT_ID, handle.deploymentId());
            int polls = gateway.pollCalls();
            GatewayTransport.DeploymentChangedException failure =
                    assertThrows(
                            GatewayTransport.DeploymentChangedException.class,
                            () -> executor.poll(handle));
            assertTrue(failure.getMessage().contains("deployment changed"));
            assertEquals(polls, gateway.pollCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void cancellationAndPollRejectAnotherDeploymentBeforeNetwork(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            DurableModelRunExecutor executor = executor(gateway, directory);
            ModelRunHandle handle = submit(executor, gateway);

            executor.cancel(handle);
            assertEquals(1, gateway.cancelCalls());

            var other =
                    new DurableModelRunExecutor(
                            transport(gateway, directory),
                            ModelGatewayExecutorTestData.capabilities("deployment-b"));
            int polls = gateway.pollCalls();
            int cancellations = gateway.cancelCalls();
            assertThrows(IllegalArgumentException.class, () -> other.poll(handle));
            assertThrows(IllegalArgumentException.class, () -> other.cancel(handle));
            assertEquals(polls, gateway.pollCalls());
            assertEquals(cancellations, gateway.cancelCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void durableAdmissionFailsBeforeNetworkWhenTheRunnerDoesNotAdvertiseIt(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            var runner = gateway.runnerCapabilities();
            var finiteOnly =
                    new RunnerCapabilitiesCodec.RunnerCapabilities(
                            Set.of(RunnerCapabilitiesCodec.InferenceMode.FINITE_CALL),
                            runner.outputTypes(),
                            runner.sourceContainers(),
                            runner.runnerBuild(),
                            runner.rasterizer(),
                            runner.preprocessingContractDigest(),
                            runner.finite(),
                            Optional.empty());
            DurableModelRunExecutor executor = executor(gateway, directory);

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            executor.submit(
                                    gateway.request(),
                                    finiteOnly,
                                    Optional.empty(),
                                    gateway.declaredClasses()));
            assertEquals(0, gateway.postCalls());
            gateway.requireNoFailure();
        }
    }

    @Test
    void validGzipWithTheWrongArtifactDigestFailsBeforeTerminalExposure(@TempDir Path directory)
            throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.completeImmediately();
            gateway.alterFirstFeatureGzipHeader();
            DurableModelRunExecutor executor = executor(gateway, directory);
            ModelRunHandle handle = submit(executor, gateway);

            InferenceException failure =
                    assertThrows(InferenceException.class, () -> executor.poll(handle));
            assertTrue(failure.getMessage().contains("invalid immutable artifact"));
            assertTrue(failure.gatewayError().isEmpty());
            gateway.requireNoFailure();
        }
    }

    @Test
    void malformedGzipUsesTheInvalidArtifactBoundary(@TempDir Path directory) throws Exception {
        try (var gateway = new DurableModelRunGatewayStub()) {
            gateway.completeImmediately();
            gateway.malformFirstFeatureGzipTrailer();
            DurableModelRunExecutor executor = executor(gateway, directory);
            ModelRunHandle handle = submit(executor, gateway);

            InferenceException failure =
                    assertThrows(InferenceException.class, () -> executor.poll(handle));
            assertTrue(failure.getMessage().contains("invalid immutable artifact"));
            assertTrue(failure.gatewayError().isEmpty());
            gateway.requireNoFailure();
        }
    }

    @Test
    void malformedManifestReferencesUseTheInvalidArtifactBoundary(@TempDir Path directory)
            throws Exception {
        List<Consumer<DurableModelRunGatewayStub>> faults =
                List.of(
                        DurableModelRunGatewayStub::useWrongManifestMediaType,
                        DurableModelRunGatewayStub::useWrongManifestEncoding,
                        DurableModelRunGatewayStub::useOversizedManifestReference);
        for (Consumer<DurableModelRunGatewayStub> fault : faults) {
            try (var gateway = new DurableModelRunGatewayStub()) {
                gateway.completeImmediately();
                fault.accept(gateway);
                DurableModelRunExecutor executor = executor(gateway, directory);
                ModelRunHandle handle = submit(executor, gateway);

                InferenceException failure =
                        assertThrows(InferenceException.class, () -> executor.poll(handle));
                assertTrue(failure.getMessage().contains("invalid immutable artifact"));
                assertTrue(failure.gatewayError().isEmpty());
                assertEquals(0, gateway.artifactCalls());
                gateway.requireNoFailure();
            }
        }
    }

    private static ModelRunHandle submit(
            DurableModelRunExecutor executor, DurableModelRunGatewayStub gateway) throws Exception {
        return executor.submit(
                gateway.request(),
                gateway.runnerCapabilities(),
                Optional.empty(),
                gateway.declaredClasses());
    }

    private static DurableModelRunExecutor executor(
            DurableModelRunGatewayStub gateway, Path directory) throws Exception {
        return new DurableModelRunExecutor(
                transport(gateway, directory), ModelGatewayExecutorTestData.capabilities());
    }

    private static DurableModelRunExecutor executorWithLoweredJsonCeiling(
            DurableModelRunGatewayStub gateway, Path directory) throws Exception {
        var capabilities =
                GatewayCapabilitiesCodec.toJson(ModelGatewayExecutorTestData.capabilities());
        int requestBytes =
                CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(gateway.request()))
                        .length;
        capabilities
                .getAsJsonObject("limits")
                .addProperty("max_json_request_bytes", requestBytes - 1);
        return new DurableModelRunExecutor(
                transport(gateway, directory),
                GatewayCapabilitiesCodec.parseGatewayCapabilities(capabilities));
    }

    private static GatewayTransport transport(DurableModelRunGatewayStub gateway, Path directory)
            throws Exception {
        return new GatewayTransport(
                ModelGatewayExecutorTestData.storeFor(gateway.server(), directory));
    }

    private static void replaceDeployment(
            DurableModelRunGatewayStub gateway, Path credential, String deploymentId) {
        String url = "http://localhost:" + gateway.server().getAddress().getPort();
        try {
            Files.writeString(
                    credential,
                    ModelGatewayExecutorTestData.deployment(url, deploymentId).toJson().toString());
        } catch (IOException error) {
            throw new AssertionError("failed to replace the deployment fixture", error);
        }
    }
}
