package qupath.ext.histoforge.client;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec.FiniteInferenceRequest;
import qupath.ext.histoforge.contract.FiniteInferenceCodec.InferenceAccepted;
import qupath.ext.histoforge.contract.FiniteInferenceCodec.InferenceResult;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec.RunnerCapabilities;

/** Validates and executes one bounded finite-call lifecycle without recipe orchestration. */
final class FiniteCallExecutor {

    private static final String CALLS_PATH = "/inference/calls";

    private final GatewayTransport transport;
    private final GatewayCapabilities gatewayCapabilities;
    private final BackendAdmission admission;

    FiniteCallExecutor(
            GatewayTransport transport,
            GatewayCapabilities gatewayCapabilities,
            BackendAdmission admission) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.gatewayCapabilities =
                Objects.requireNonNull(gatewayCapabilities, "gatewayCapabilities");
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    FiniteCallHandle submit(
            FiniteInferenceRequest request,
            RunnerCapabilities runnerCapabilities,
            Collection<String> declaredClasses)
            throws IOException, InterruptedException, InferenceException {
        FiniteInferenceRequest supplied = Objects.requireNonNull(request, "request");
        byte[] canonicalRequest =
                CanonicalCodec.canonicalBytes(FiniteInferenceCodec.toJson(supplied));
        FiniteInferenceRequest validated =
                FiniteInferenceCodec.validateRouteRequest(
                        CanonicalCodec.parseJson(canonicalRequest),
                        supplied.producer().moduleRef(),
                        supplied.producer().stepId());
        RunnerCapabilities runner =
                Objects.requireNonNull(runnerCapabilities, "runnerCapabilities");
        Set<String> classes = RunnerAdmission.normalizeDeclaredClasses(declaredClasses);
        validateAdmission(validated, runner, canonicalRequest.length);
        admission.require();
        requireDeployment();
        var accepted = submitGateway(validated, canonicalRequest);
        return new FiniteCallHandle(
                gatewayCapabilities.deploymentId(), validated, accepted.backendCallId(), classes);
    }

    Optional<InferenceResult> poll(FiniteCallHandle handle)
            throws IOException, InterruptedException, InferenceException {
        FiniteCallHandle call = Objects.requireNonNull(handle, "handle");
        requireHandleDeployment(call);
        Optional<InferenceResult> result = pollGateway(call);
        requireDeployment();
        return result;
    }

    void cancel(FiniteCallHandle handle)
            throws IOException, InterruptedException, InferenceException {
        FiniteCallHandle call = Objects.requireNonNull(handle, "handle");
        requireHandleDeployment(call);
        cancelGateway(call.backendCallId());
        requireDeployment();
    }

    private InferenceAccepted submitGateway(FiniteInferenceRequest request, byte[] canonicalRequest)
            throws IOException, InterruptedException, InferenceException {
        var module = request.producer().moduleRef();
        String path =
                "/inference/"
                        + module.owner()
                        + "/"
                        + module.name()
                        + "/"
                        + request.producer().stepId();
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        gatewayCapabilities.deploymentId(),
                        token ->
                                transport
                                        .authed(path, token)
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofByteArray(
                                                        canonicalRequest))
                                        .build());
        transport.requireTargetStatus(response, 202, "submit finite inference");
        try {
            return FiniteInferenceCodec.validateAcceptance(
                    request, CanonicalCodec.parseJson(response.body()));
        } catch (IllegalArgumentException error) {
            throw invalidResponse("submit finite inference", error);
        }
    }

    private Optional<InferenceResult> pollGateway(FiniteCallHandle call)
            throws IOException, InterruptedException, InferenceException {
        String callId = call.backendCallId();
        String path = CALLS_PATH + "?" + BackendExecutionCodec.encodeBackendCallQuery(callId);
        long resultBodyLimit =
                Math.min(
                        call.request().resultBudget().maxTransferredBytes(),
                        call.request().resultBudget().maxDecodedBytes());
        GatewayTransport.BoundedResponse response =
                transport.sendBoundedForDeployment(
                        gatewayCapabilities.deploymentId(),
                        token -> transport.authed(path, token).GET().build(),
                        status ->
                                status == 200
                                        ? resultBodyLimit
                                        : GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES);
        try {
            if (response.statusCode() == 202) {
                FiniteInferenceCodec.validatePending(
                        CanonicalCodec.parseJson(response.body()), callId);
                return Optional.empty();
            }
            if (response.statusCode() == 200) {
                return Optional.of(
                        FiniteInferenceCodec.validateResult(
                                CanonicalCodec.parseJson(response.body()),
                                call.request(),
                                callId,
                                call.declaredClasses()));
            }
            transport.requireTargetStatus(response, 200, "poll finite inference");
            throw new IllegalStateException("unreachable finite poll status");
        } catch (IllegalArgumentException error) {
            throw invalidResponse("poll finite inference", error);
        }
    }

    private void cancelGateway(String backendCallId)
            throws IOException, InterruptedException, InferenceException {
        String callId =
                BackendExecutionCodec.requireBackendCallId(backendCallId, "backend_call_id");
        String path = CALLS_PATH + "?" + BackendExecutionCodec.encodeBackendCallQuery(callId);
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        gatewayCapabilities.deploymentId(),
                        token -> transport.authed(path, token).DELETE().build());
        transport.requireTargetStatus(response, 202, "cancel finite inference");
        try {
            FiniteInferenceCodec.validateCancellationAcceptance(
                    CanonicalCodec.parseJson(response.body()), callId);
        } catch (IllegalArgumentException error) {
            throw invalidResponse("cancel finite inference", error);
        }
    }

    private void validateAdmission(
            FiniteInferenceRequest request, RunnerCapabilities runner, int canonicalBytes) {
        var finite = runner.finite();
        if (canonicalBytes > gatewayCapabilities.limits().maxJsonRequestBytes()
                || canonicalBytes > finite.maxRequestCanonicalBytes()) {
            throw new IllegalArgumentException(
                    "finite request exceeds captured canonical byte limits");
        }
        var backend = request.expectedBackend();
        RunnerAdmission.requireBackend(backend, runner);
        var raster = request.rasterInput().frame().transform().raster();
        if (!ExecutionLimitsCodec.fitsTargetRaster(raster, finite.targetRaster())) {
            throw new IllegalArgumentException("target raster exceeds captured finite-call limits");
        }
        if (RasterInputCodec.geometryVertexCount(
                        request.rasterInput().frame().inclusionMaskRaster())
                > finite.maxInclusionMaskVertices()) {
            throw new IllegalArgumentException(
                    "inclusion mask exceeds captured finite-call limits");
        }
        RunnerAdmission.requireSourceContainer(
                FiniteInferenceCodec.sourceArtifact(request).container().name(), runner);
        RunnerAdmission.requireResultBudgetWithin(request.resultBudget(), finite.result());
    }

    private void requireDeployment() throws IOException {
        if (!transport.matchesCurrentDeployment(gatewayCapabilities.deploymentId())) {
            throw new GatewayTransport.DeploymentChangedException(
                    "deployment changed during finite call; restart action");
        }
    }

    private void requireHandleDeployment(FiniteCallHandle handle) {
        if (!gatewayCapabilities.deploymentId().equals(handle.deploymentId())) {
            throw new IllegalArgumentException(
                    "finite call handle belongs to a different gateway deployment");
        }
    }

    private static InferenceException invalidResponse(
            String operation, IllegalArgumentException error) {
        return new InferenceException(operation + ": invalid gateway response", error);
    }
}
