package qupath.ext.histoforge.client;

import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;

/** Immutable local identity and captured limits needed to poll or cancel one durable model run. */
public final class ModelRunHandle {

    private final String deploymentId;
    private final ModelRunRequestCodec.ModelRunRequest request;
    private final ModelRunRequestCodec.ModelRunAccepted accepted;
    private final RunnerCapabilitiesCodec.DurableRunLimits durableLimits;
    private final Set<String> declaredClasses;

    ModelRunHandle(
            String deploymentId,
            ModelRunRequestCodec.ModelRunRequest request,
            ModelRunRequestCodec.ModelRunAccepted accepted,
            RunnerCapabilitiesCodec.DurableRunLimits durableLimits,
            Set<String> declaredClasses) {
        this.deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
        this.request = Objects.requireNonNull(request, "request");
        this.accepted = Objects.requireNonNull(accepted, "accepted");
        this.durableLimits = Objects.requireNonNull(durableLimits, "durableLimits");
        this.declaredClasses = Set.copyOf(declaredClasses);
        if (!accepted.actionId().equals(request.actionId())
                || !accepted.modelRunId().equals(request.modelRunId())
                || !accepted.requestDigest().equals(ModelRunRequestCodec.requestDigest(request))) {
            throw new IllegalArgumentException("accepted durable run does not match its request");
        }
        if (this.declaredClasses.isEmpty()) {
            throw new IllegalArgumentException("declaredClasses must be nonempty");
        }
    }

    public String deploymentId() {
        return deploymentId;
    }

    public ModelRunRequestCodec.ModelRunRequest request() {
        return request;
    }

    public ModelRunRequestCodec.ModelRunAccepted accepted() {
        return accepted;
    }

    public RunnerCapabilitiesCodec.DurableRunLimits durableLimits() {
        return durableLimits;
    }

    public Set<String> declaredClasses() {
        return declaredClasses;
    }
}
