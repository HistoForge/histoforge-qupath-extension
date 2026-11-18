package qupath.ext.histoforge.client;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.ExistingTrainingDataset;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetChunkRange;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetChunkReceipt;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetPage;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetSealRequest;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetStartRequest;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetStartResult;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetSummary;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetUploadAccepted;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;

/** Exact HTTP adaptation for immutable training-dataset routes. */
public final class TrainingDatasetGatewayClient {

    private static final Duration CHUNK_TIMEOUT = Duration.ofSeconds(300);
    // Dataset seal uses the gateway's 1,800-second execution budget, plus result handoff margin.
    static final Duration SEAL_TIMEOUT = Duration.ofSeconds(1830);
    private static final Duration DELETE_TIMEOUT = Duration.ofSeconds(330);

    private final GatewayTransport transport;
    private final String deploymentId;
    private final TrainingApiLimits limits;
    private final Duration chunkTimeout;
    private final Duration sealTimeout;
    private final Duration deleteTimeout;
    private final GatewayCapabilities capabilities;

    /** Use the fixed client behavior policy for normal Train-tab operations. */
    public TrainingDatasetGatewayClient(
            GatewayTransport transport, GatewayCapabilities capabilities) {
        this(transport, capabilities, CHUNK_TIMEOUT, SEAL_TIMEOUT, DELETE_TIMEOUT);
    }

    public TrainingDatasetGatewayClient(
            GatewayTransport transport,
            GatewayCapabilities capabilities,
            Duration chunkTimeout,
            Duration sealTimeout,
            Duration deleteTimeout) {
        this.transport = Objects.requireNonNull(transport, "transport");
        GatewayCapabilities negotiated = Objects.requireNonNull(capabilities, "capabilities");
        if (!GatewayCapabilitiesCodec.supports(negotiated)) {
            throw new IllegalArgumentException("unsupported gateway capabilities");
        }
        this.deploymentId = negotiated.deploymentId();
        this.capabilities = negotiated;
        this.limits = negotiated.trainingApiLimits();
        this.chunkTimeout = requirePositive(chunkTimeout, "chunkTimeout");
        this.sealTimeout = requirePositive(sealTimeout, "sealTimeout");
        this.deleteTimeout = requirePositive(deleteTimeout, "deleteTimeout");
    }

    public TrainingApiLimits limits() {
        return limits;
    }

    public TrainingDatasetStartResult start(TrainingDatasetStartRequest request)
            throws IOException, InterruptedException, InferenceException {
        byte[] body =
                CanonicalCodec.canonicalBytes(TrainingDatasetTransferCodec.toJson(request, limits));
        new BackendAdmission(transport, capabilities).require();
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/training/datasets", token)
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                        .build());
        transport.requireTargetStatus(response, 200, "start training dataset");
        try {
            TrainingDatasetStartResult result =
                    TrainingDatasetTransferCodec.parseStartResult(
                            CanonicalCodec.parseJson(response.body()), limits);
            requireStartIdentity(request, result);
            return result;
        } catch (IllegalArgumentException error) {
            throw invalidResponse("start training dataset", error);
        }
    }

    public TrainingDatasetChunkReceipt putChunk(
            TrainingDatasetStartRequest request,
            TrainingDatasetUploadAccepted accepted,
            TrainingDatasetChunkRange range,
            byte[] body)
            throws IOException, InterruptedException, InferenceException {
        Objects.requireNonNull(body, "body");
        TrainingDatasetChunkRange expected =
                TrainingDatasetTransferCodec.chunkRange(request, range.chunkIndex(), limits);
        if (!expected.equals(range) || body.length != range.sizeBytes()) {
            throw new IllegalArgumentException("chunk body does not match its canonical range");
        }
        requireUploadIdentity(request, accepted);
        String digest = CanonicalCodec.sha256(body);
        String path =
                "/training/datasets/" + accepted.datasetId() + "/chunks/" + range.chunkIndex();
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed(path, token)
                                        .timeout(chunkTimeout)
                                        .header("Content-Type", "application/octet-stream")
                                        .header("X-HistoForge-Chunk-Sha256", digest)
                                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                                        .build());
        transport.requireTargetStatus(
                response, 200, "upload training dataset chunk " + range.chunkIndex());
        try {
            TrainingDatasetChunkReceipt receipt =
                    TrainingDatasetTransferCodec.parseChunkReceipt(
                            CanonicalCodec.parseJson(response.body()));
            if (!receipt.datasetId().equals(accepted.datasetId())
                    || !receipt.chunk().startRequestDigest().equals(accepted.startRequestDigest())
                    || receipt.chunk().chunkIndex() != range.chunkIndex()
                    || receipt.chunk().sizeBytes() != range.sizeBytes()
                    || !receipt.chunk().sha256().equals(digest)) {
                throw new IllegalArgumentException("chunk receipt identity differs");
            }
            return receipt;
        } catch (IllegalArgumentException error) {
            throw invalidResponse("upload training dataset chunk", error);
        }
    }

    /** Seal the exact upload, allowing Modal's same-request result handoff. */
    public TrainingDatasetRecord seal(
            TrainingDatasetStartRequest request, TrainingDatasetUploadAccepted accepted)
            throws IOException, InterruptedException, InferenceException {
        requireUploadIdentity(request, accepted);
        TrainingDatasetSealRequest seal =
                new TrainingDatasetSealRequest(accepted.startRequestDigest());
        byte[] body = CanonicalCodec.canonicalBytes(TrainingDatasetTransferCodec.toJson(seal));
        GatewayTransport.BoundedResponse response =
                transport.sendModalResultPostForDeployment(
                        deploymentId,
                        "/training/datasets/" + accepted.datasetId() + "/seal",
                        body,
                        sealTimeout);
        transport.requireTargetStatus(response, 200, "seal training dataset");
        try {
            TrainingDatasetRecord record =
                    TrainingDatasetTransferCodec.parseDatasetRecord(
                            CanonicalCodec.parseJson(response.body()), limits);
            if (!record.datasetId().equals(accepted.datasetId())
                    || !record.datasetDigest().equals(request.datasetDigest())) {
                throw new IllegalArgumentException("sealed dataset identity differs");
            }
            return record;
        } catch (IllegalArgumentException error) {
            throw invalidResponse("seal training dataset", error);
        }
    }

    public TrainingDatasetPage page(int limit, Optional<String> cursor)
            throws IOException, InterruptedException, InferenceException {
        if (limit <= 0 || limit > limits.maxPageSize()) {
            throw new IllegalArgumentException("dataset page limit is outside negotiated limits");
        }
        Objects.requireNonNull(cursor, "cursor");
        String path = "/training/datasets?limit=" + limit;
        if (cursor.isPresent()) {
            String value = CanonicalCodec.requireNonblank(cursor.orElseThrow(), "dataset cursor");
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
        transport.requireTargetStatus(response, 200, "list training datasets");
        try {
            TrainingDatasetPage page =
                    TrainingDatasetTransferCodec.parsePage(
                            CanonicalCodec.parseJson(response.body()), limits);
            if (page.items().size() > limit) {
                throw new IllegalArgumentException("dataset page exceeds requested limit");
            }
            return page;
        } catch (IllegalArgumentException error) {
            throw invalidResponse("list training datasets", error);
        }
    }

    public TrainingDatasetSummary get(String datasetId)
            throws IOException, InterruptedException, InferenceException {
        String canonicalId = CanonicalCodec.requireCanonicalUuid(datasetId, "dataset_id");
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/training/datasets/" + canonicalId, token)
                                        .GET()
                                        .build());
        transport.requireTargetStatus(response, 200, "read training dataset " + canonicalId);
        try {
            TrainingDatasetSummary summary =
                    TrainingDatasetTransferCodec.parseSummary(
                            CanonicalCodec.parseJson(response.body()), limits);
            if (!summary.record().datasetId().equals(canonicalId)) {
                throw new IllegalArgumentException("dataset response identity differs");
            }
            return summary;
        } catch (IllegalArgumentException error) {
            throw invalidResponse("read training dataset " + canonicalId, error);
        }
    }

    public void delete(String datasetId)
            throws IOException, InterruptedException, InferenceException {
        String canonicalId = CanonicalCodec.requireCanonicalUuid(datasetId, "dataset_id");
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/training/datasets/" + canonicalId, token)
                                        .timeout(deleteTimeout)
                                        .DELETE()
                                        .build());
        transport.requireTargetStatus(response, 204, "delete training dataset " + canonicalId);
        if (response.body().length != 0) {
            throw new InferenceException("delete training dataset: invalid nonempty response");
        }
    }

    private void requireStartIdentity(
            TrainingDatasetStartRequest request, TrainingDatasetStartResult result) {
        if (result instanceof ExistingTrainingDataset existing) {
            if (!existing.dataset().datasetDigest().equals(request.datasetDigest())) {
                throw new IllegalArgumentException("existing dataset identity differs");
            }
            return;
        }
        requireUploadIdentity(request, (TrainingDatasetUploadAccepted) result);
    }

    private void requireUploadIdentity(
            TrainingDatasetStartRequest request, TrainingDatasetUploadAccepted accepted) {
        String expected = TrainingDatasetTransferCodec.startRequestDigest(request, limits);
        if (!accepted.startRequestDigest().equals(expected)) {
            throw new IllegalArgumentException("upload reservation identity differs");
        }
    }

    private static Duration requirePositive(Duration value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(path + " must be positive");
        }
        return value;
    }

    private static InferenceException invalidResponse(
            String operation, IllegalArgumentException error) {
        return new InferenceException(operation + ": invalid gateway response", error);
    }
}
