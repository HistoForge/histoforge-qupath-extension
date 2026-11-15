package qupath.ext.histoforge.client;

import java.util.Set;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec.FiniteInferenceRequest;

/** Immutable local identity needed to poll, cancel, and validate one accepted finite call. */
public final class FiniteCallHandle {

    private final String deploymentId;
    private final FiniteInferenceRequest request;
    private final String backendCallId;
    private final Set<String> declaredClasses;

    FiniteCallHandle(
            String deploymentId,
            FiniteInferenceRequest request,
            String backendCallId,
            Set<String> declaredClasses) {
        this.deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
        this.request = java.util.Objects.requireNonNull(request, "request");
        this.backendCallId =
                BackendExecutionCodec.requireBackendCallId(backendCallId, "backend_call_id");
        this.declaredClasses = Set.copyOf(declaredClasses);
        if (this.declaredClasses.isEmpty()) {
            throw new IllegalArgumentException("declaredClasses must be nonempty");
        }
    }

    public String deploymentId() {
        return deploymentId;
    }

    public FiniteInferenceRequest request() {
        return request;
    }

    public String backendCallId() {
        return backendCallId;
    }

    public Set<String> declaredClasses() {
        return declaredClasses;
    }

    /** Execution identity retained if this exact result later commits. */
    public BackendExecutionCodec.FiniteCall executionReference() {
        return new BackendExecutionCodec.FiniteCall(backendCallId);
    }
}
