package qupath.ext.histoforge.client;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingRunCodec;
import qupath.ext.histoforge.contract.TrainingRunPageCodec;
import qupath.ext.histoforge.contract.TrainingRunReportCodec;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec.TrainingRunRequest;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingReportResponse;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunAccepted;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunPage;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunRecord;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunSummary;

/** Exact HTTP adaptation for durable managed-training run routes. */
public final class TrainingRunGatewayClient {

    private static final Duration SUBMIT_TIMEOUT = Duration.ofSeconds(1830);
    private static final Duration CLEAR_TIMEOUT = Duration.ofSeconds(330);
    private static final long REPORT_ENVELOPE_BYTES = 1_024;

    private final GatewayTransport transport;
    private final String deploymentId;
    private final TrainingApiLimits limits;
    private final GatewayCapabilities capabilities;

    public TrainingRunGatewayClient(GatewayTransport transport, GatewayCapabilities capabilities) {
        this.transport = Objects.requireNonNull(transport, "transport");
        GatewayCapabilities negotiated = Objects.requireNonNull(capabilities, "capabilities");
        if (!GatewayCapabilitiesCodec.supports(negotiated)) {
            throw new IllegalArgumentException("unsupported gateway capabilities");
        }
        this.deploymentId = negotiated.deploymentId();
        this.capabilities = negotiated;
        this.limits = negotiated.trainingApiLimits();
    }

    public TrainingApiLimits limits() {
        return limits;
    }

    /** Submit one exact request and bind the acceptance echo to its digest. */
    public TrainingRunAccepted submit(TrainingRunRequest request)
            throws IOException, InterruptedException, InferenceException {
        byte[] body =
                CanonicalCodec.canonicalBytes(TrainingRunRequestCodec.toJson(request, limits));
        new BackendAdmission(transport, capabilities).require();
        GatewayTransport.BoundedResponse response =
                transport.sendModalResultPostForDeployment(
                        deploymentId, "/training/runs", body, SUBMIT_TIMEOUT);
        transport.requireTargetStatus(response, 202, "submit training run");
        try {
            return TrainingRunCodec.validateAcceptance(
                    request, CanonicalCodec.parseJson(response.body()), limits);
        } catch (IllegalArgumentException error) {
            throw invalidResponse("submit training run", error);
        }
    }

    /** Read one stable, byte-bounded page of training Jobs rows. */
    public TrainingRunPage page(int limit, Optional<String> cursor)
            throws IOException, InterruptedException, InferenceException {
        if (limit <= 0 || limit > limits.maxPageSize()) {
            throw new IllegalArgumentException(
                    "training run page limit is outside negotiated limits");
        }
        Objects.requireNonNull(cursor, "cursor");
        String path = "/training/runs?limit=" + limit;
        if (cursor.isPresent()) {
            String value =
                    CanonicalCodec.requireNonblank(cursor.orElseThrow(), "training run cursor");
            path += "&cursor=" + GatewayTransport.encodeQueryValue(value);
        }
        String requestPath = path;
        GatewayTransport.BoundedResponse response =
                transport.sendBoundedForDeployment(
                        deploymentId,
                        token -> transport.authed(requestPath, token).GET().build(),
                        status ->
                                status == 200
                                        ? limits.maxPageEncodedBytes()
                                        : GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES);
        transport.requireTargetStatus(response, 200, "list training runs");
        try {
            TrainingRunPage page =
                    TrainingRunPageCodec.parsePage(
                            CanonicalCodec.parseJson(response.body()), limits);
            if (page.items().size() > limit) {
                throw new IllegalArgumentException("training run page exceeds requested limit");
            }
            return page;
        } catch (IllegalArgumentException error) {
            throw invalidResponse("list training runs", error);
        }
    }

    /** Refresh all retained Jobs rows, starting from the first page on every call. */
    public List<TrainingRunSummary> listRuns()
            throws IOException, InterruptedException, InferenceException {
        var result = new ArrayList<TrainingRunSummary>();
        var seenIds = new HashSet<String>();
        var seenCursors = new HashSet<String>();
        Optional<String> cursor = Optional.empty();
        do {
            TrainingRunPage page = page(limits.maxPageSize(), cursor);
            for (TrainingRunSummary run : page.items()) {
                if (!seenIds.add(run.runId())) {
                    throw new InferenceException("training run pages repeat a run ID");
                }
                result.add(run);
            }
            cursor = page.nextCursor();
            if (cursor.isPresent() && !seenCursors.add(cursor.orElseThrow())) {
                throw new InferenceException("training run pages repeat a continuation cursor");
            }
        } while (cursor.isPresent());
        return List.copyOf(result);
    }

    /** Read the current complete state for one accepted training run. */
    public TrainingRunRecord get(String runId)
            throws IOException, InterruptedException, InferenceException {
        String canonicalId = CanonicalCodec.requireCanonicalUuid(runId, "run_id");
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/training/runs/" + canonicalId, token)
                                        .GET()
                                        .build());
        transport.requireTargetStatus(response, 200, "read training run " + canonicalId);
        return parseRecordForRoute(response, canonicalId, "read training run " + canonicalId);
    }

    /** Commit cancellation and return the resulting current run state. */
    public TrainingRunRecord cancel(String runId)
            throws IOException, InterruptedException, InferenceException {
        String canonicalId = CanonicalCodec.requireCanonicalUuid(runId, "run_id");
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/training/runs/" + canonicalId + "/cancel", token)
                                        .POST(HttpRequest.BodyPublishers.noBody())
                                        .build());
        transport.requireTargetStatus(response, 200, "cancel training run " + canonicalId);
        String operation = "cancel training run " + canonicalId;
        TrainingRunRecord run = parseRecordForRoute(response, canonicalId, operation);
        if (!"CANCELLED".equals(run.state())) {
            throw invalidResponse(
                    operation,
                    new IllegalArgumentException(
                            "successful cancellation did not return CANCELLED"));
        }
        return run;
    }

    /** Fetch the immutable report currently advertised by an observed run. */
    public TrainingReportResponse report(TrainingRunRecord observed)
            throws IOException, InterruptedException, InferenceException {
        Objects.requireNonNull(observed, "observed");
        String runId = CanonicalCodec.requireCanonicalUuid(observed.runId(), "run_id");
        long successLimit = Math.addExact(limits.report().maxReportBytes(), REPORT_ENVELOPE_BYTES);
        GatewayTransport.BoundedResponse response =
                transport.sendBoundedForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/training/runs/" + runId + "/report", token)
                                        .GET()
                                        .build(),
                        status ->
                                status == 200
                                        ? successLimit
                                        : GatewayTransport.MAX_BUFFERED_CONTROL_RESPONSE_BYTES);
        transport.requireTargetStatus(response, 200, "read training report " + runId);
        try {
            return TrainingRunReportCodec.validateForRun(
                    observed, CanonicalCodec.parseJson(response.body()), limits);
        } catch (IllegalArgumentException error) {
            throw invalidResponse("read training report " + runId, error);
        }
    }

    /** Clear one terminal Jobs row and its run-owned report history. */
    public void clear(String runId) throws IOException, InterruptedException, InferenceException {
        String canonicalId = CanonicalCodec.requireCanonicalUuid(runId, "run_id");
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/training/runs/" + canonicalId, token)
                                        .timeout(CLEAR_TIMEOUT)
                                        .DELETE()
                                        .build());
        transport.requireTargetStatus(response, 204, "clear training run " + canonicalId);
        if (response.body().length != 0) {
            throw new InferenceException("clear training run: invalid nonempty response");
        }
    }

    private TrainingRunRecord parseRecordForRoute(
            GatewayTransport.BoundedResponse response, String runId, String operation)
            throws InferenceException {
        try {
            TrainingRunRecord run =
                    TrainingRunCodec.parseRecord(CanonicalCodec.parseJson(response.body()), limits);
            if (!run.runId().equals(runId)) {
                throw new IllegalArgumentException("training run response identity differs");
            }
            return run;
        } catch (IllegalArgumentException error) {
            throw invalidResponse(operation, error);
        }
    }

    private static InferenceException invalidResponse(
            String operation, IllegalArgumentException error) {
        return new InferenceException(operation + ": invalid gateway response", error);
    }
}
