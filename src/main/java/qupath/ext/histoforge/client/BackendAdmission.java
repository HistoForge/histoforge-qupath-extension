package qupath.ext.histoforge.client;

import java.io.IOException;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;

/** One current handshake before a captured action starts new cloud work. */
final class BackendAdmission {
    private final GatewayTransport transport;
    private final GatewayCapabilities captured;
    private boolean verified;

    BackendAdmission(GatewayTransport transport, GatewayCapabilities captured) {
        this.transport = transport;
        this.captured = captured;
    }

    // A runtime can publish several inputs concurrently. All reuse this single admission,
    // not a capability request per tile. Existing exact-build fences govern subsequent work.
    synchronized void require() throws IOException, InterruptedException, InferenceException {
        if (verified) return;
        if (!transport.matchesCurrentDeployment(captured.deploymentId())) {
            throw new GatewayTransport.DeploymentChangedException(
                    "deployment changed; restart this action");
        }
        var current = new ModuleGatewayClient(transport).admissionCapabilities();
        if (!captured.deploymentId().equals(current.deploymentId())) {
            throw new InferenceException(
                    "Connection identity changed. Reconnect before submitting work.");
        }
        if (!captured.gatewayBuild().equals(current.gatewayBuild())) {
            throw new InferenceException(
                    "Cloud backend changed. Recheck the connection and restart this action.");
        }
        verified = true;
    }
}
