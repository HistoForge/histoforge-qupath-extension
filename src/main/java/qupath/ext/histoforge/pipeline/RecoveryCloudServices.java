package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.ModelRunStatusCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;

/**
 * Opens the real cloud connection only when a recovery attempt needs it. Local replay and saving
 * use the captured module and result files, so neither construction nor close contacts the cloud.
 * One recovery worker owns this lifetime; a paused attempt keeps the same opened services.
 */
final class RecoveryCloudServices implements AutoCloseable {
    private final Supplier<GatewayTransport> connection;
    private final String deploymentId;
    private final ClientBuildIdentity build;
    private final LargeActionHandleStore handles;
    private final int writerThreads;
    private final Consumer<ModelRunStatusCodec.Status> onStatus;
    private final Consumer<ImageSourceProgress> onSourceProgress;
    private ModelGatewayExecutor gateway;
    private DurableModelRunExecutor runs;
    private RecoverableSourceProvisioner sources;
    private boolean closed;

    RecoveryCloudServices(
            Supplier<GatewayTransport> connection,
            String deploymentId,
            ClientBuildIdentity build,
            LargeActionHandleStore handles,
            int writerThreads,
            Consumer<ModelRunStatusCodec.Status> onStatus,
            Consumer<ImageSourceProgress> onSourceProgress) {
        this.connection = Objects.requireNonNull(connection);
        this.deploymentId = Objects.requireNonNull(deploymentId);
        this.build = Objects.requireNonNull(build);
        this.handles = Objects.requireNonNull(handles);
        this.writerThreads = writerThreads;
        this.onStatus = Objects.requireNonNull(onStatus);
        this.onSourceProgress = Objects.requireNonNull(onSourceProgress);
    }

    DurableModelRunExecutor runs() throws IOException, InterruptedException, InferenceException {
        open();
        return runs;
    }

    RecoverableSourceProvisioner sources()
            throws IOException, InterruptedException, InferenceException {
        open();
        return sources;
    }

    private void open() throws IOException, InterruptedException, InferenceException {
        if (closed) throw new IllegalStateException("Recovery cloud services are closed");
        if (gateway != null) return;
        var transport = connection.get();
        var capabilities = new ModuleGatewayClient(transport).capabilities();
        if (!deploymentId.equals(capabilities.deploymentId()))
            throw new GatewayTransport.DeploymentChangedException(
                    "Unfinished model work belongs to another cloud deployment. Reconnect to that deployment to continue.");
        var limits =
                SourcePlanningLimits.forInference(
                        capabilities.sourceArtifactLimits(), build.limits());
        var writer =
                new SourceArtifactWriter(
                        build.extension(),
                        Math.toIntExact(capabilities.sourceArtifactLimits().maxChunkBytes()),
                        writerThreads,
                        limits.maxDerivedSourceArtifactBytes(),
                        limits.maxSourceArtifactBytes());
        var opened = new ModelGatewayExecutor(transport, capabilities);
        try {
            runs = new DurableModelRunExecutor(transport, capabilities, onStatus);
            sources =
                    new RecoverableSourceProvisioner(
                            handles, opened, writer, limits, onSourceProgress);
            gateway = opened;
        } catch (RuntimeException failure) {
            opened.close();
            throw failure;
        }
    }

    @Override
    public void close() {
        closed = true;
        if (gateway != null) gateway.close();
    }
}
