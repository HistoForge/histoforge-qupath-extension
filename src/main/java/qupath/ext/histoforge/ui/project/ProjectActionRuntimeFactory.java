package qupath.ext.histoforge.ui.project;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.ModelRunStatusCodec;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.pipeline.ImageActionCoordinator;
import qupath.ext.histoforge.pipeline.ImageActionRegistry;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.pipeline.ImageSourceProgress;
import qupath.ext.histoforge.pipeline.PersistedActionRecovery;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;

/** Composes one deployment-bound runtime for a normal batch or explicit recovery session. */
final class ProjectActionRuntimeFactory {

    private static final int INFERENCE_OME_WRITER_THREADS = 4;

    private final Supplier<GatewayTransport> transport;
    private final ImageActionRegistry actionRegistry;
    private final ImageLifecycle imageLifecycle;
    private final MetricsEngine metricsEngine;

    ProjectActionRuntimeFactory(
            Supplier<GatewayTransport> transport,
            ImageActionRegistry actionRegistry,
            ImageLifecycle imageLifecycle,
            MetricsEngine metricsEngine) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.imageLifecycle = Objects.requireNonNull(imageLifecycle, "imageLifecycle");
        this.metricsEngine = Objects.requireNonNull(metricsEngine, "metricsEngine");
    }

    ProjectActionRuntime open(
            GatewayCapabilities gatewayCapabilities,
            ClientBuildIdentity clientBuild,
            LargeActionHandleStore handles) {
        return open(gatewayCapabilities, clientBuild, handles, ignored -> {}, ignored -> {});
    }

    ProjectActionRuntime open(
            GatewayCapabilities gatewayCapabilities,
            ClientBuildIdentity clientBuild,
            LargeActionHandleStore handles,
            Consumer<ModelRunStatusCodec.Status> onStatus,
            Consumer<ImageSourceProgress> onSourceProgress) {
        GatewayCapabilities capabilities =
                Objects.requireNonNull(gatewayCapabilities, "gatewayCapabilities");
        ClientBuildIdentity build = Objects.requireNonNull(clientBuild, "clientBuild");
        SourcePlanningLimits sourcePlanningLimits =
                SourcePlanningLimits.forInference(
                        capabilities.sourceArtifactLimits(), build.limits());
        SourceArtifactWriter writer =
                new SourceArtifactWriter(
                        build.extension(),
                        Math.toIntExact(capabilities.sourceArtifactLimits().maxChunkBytes()),
                        INFERENCE_OME_WRITER_THREADS,
                        sourcePlanningLimits.maxDerivedSourceArtifactBytes(),
                        sourcePlanningLimits.maxSourceArtifactBytes());
        var connection = transport.get();
        ModelGatewayExecutor gateway = new ModelGatewayExecutor(connection, capabilities);
        try {
            ImageActionCoordinator coordinator =
                    new ImageActionCoordinator(
                            actionRegistry,
                            imageLifecycle,
                            gateway,
                            writer,
                            sourcePlanningLimits,
                            Objects.requireNonNull(handles, "handles"),
                            new DurableModelRunExecutor(connection, capabilities, onStatus),
                            metricsEngine,
                            onSourceProgress);
            return new ProjectActionRuntime(gateway, coordinator, handles);
        } catch (RuntimeException failure) {
            gateway.close();
            throw failure;
        }
    }

    PersistedActionRecovery openRecovery(
            ClientBuildIdentity build,
            LargeActionHandleStore handles,
            String deploymentId,
            Consumer<ModelRunStatusCodec.Status> onStatus,
            Consumer<ImageSourceProgress> onSourceProgress) {
        return new PersistedActionRecovery(
                handles,
                actionRegistry,
                imageLifecycle,
                build,
                transport,
                deploymentId,
                INFERENCE_OME_WRITER_THREADS,
                metricsEngine,
                onStatus,
                onSourceProgress);
    }
}
