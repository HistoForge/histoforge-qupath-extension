package qupath.ext.histoforge.client;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Optional;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactAccepted;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactStartRequest;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactSealed;
import qupath.ext.histoforge.contract.SourceArtifactStatusCodec;
import qupath.ext.histoforge.contract.SourceArtifactStatusCodec.SourceArtifactStatus;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec.SourceArtifactSealRequest;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec.SourceByteChunkAccepted;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec.SourceByteChunkCommit;

/** Exact HTTP adaptation for the closed source-artifact lifecycle routes. */
final class SourceArtifactGatewayClient {

    /** Status recovered without the possibly lost start response. */
    record RecoveredStatus(SourceArtifactAccepted accepted, SourceArtifactStatus status) {}

    private final GatewayTransport transport;
    private final String deploymentId;
    private final Duration chunkTimeout;
    private final Duration sealTimeout;
    private final Duration releaseTimeout;

    SourceArtifactGatewayClient(
            GatewayTransport transport,
            String deploymentId,
            Duration chunkTimeout,
            Duration sealTimeout,
            Duration releaseTimeout) {
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
        this.chunkTimeout = java.util.Objects.requireNonNull(chunkTimeout, "chunkTimeout");
        this.sealTimeout = java.util.Objects.requireNonNull(sealTimeout, "sealTimeout");
        this.releaseTimeout = java.util.Objects.requireNonNull(releaseTimeout, "releaseTimeout");
    }

    SourceArtifactAccepted reserve(SourceArtifactStartRequest start, byte[] canonicalStart)
            throws IOException, InterruptedException, InferenceException {
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/source-artifacts", token)
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofByteArray(
                                                        canonicalStart))
                                        .build());
        transport.requireTargetStatus(response, 200, "start source " + start.artifactId());
        try {
            return SourceArtifactCodec.validateAcceptance(
                    start,
                    SourceArtifactCodec.parseAccepted(CanonicalCodec.parseJson(response.body())));
        } catch (IllegalArgumentException error) {
            throw invalidResponse("start source " + start.artifactId(), error);
        }
    }

    SourceArtifactStatus status(
            SourceArtifactStartRequest start,
            SourceArtifactAccepted accepted,
            SourceArtifactSealRequest seal)
            throws IOException, InterruptedException, InferenceException {
        SourceArtifactStatus status = readStatus(start);
        try {
            return SourceArtifactStatusCodec.validateProjection(
                    start, accepted, status, Optional.of(seal), Optional.empty());
        } catch (IllegalArgumentException error) {
            throw invalidResponse("read source " + start.artifactId(), error);
        }
    }

    RecoveredStatus recoverStatus(SourceArtifactStartRequest start, SourceArtifactSealRequest seal)
            throws IOException, InterruptedException, InferenceException {
        SourceArtifactStatus status = readStatus(start);
        SourceArtifactAccepted accepted =
                new SourceArtifactAccepted(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        start.creationActionId(),
                        start.artifactId(),
                        SourceArtifactCodec.startRequestDigest(start),
                        status.absoluteExpiresAt());
        try {
            SourceArtifactStatus validated =
                    SourceArtifactStatusCodec.validateProjection(
                            start, accepted, status, Optional.of(seal), Optional.empty());
            return new RecoveredStatus(accepted, validated);
        } catch (IllegalArgumentException error) {
            throw invalidResponse("recover source " + start.artifactId(), error);
        }
    }

    private SourceArtifactStatus readStatus(SourceArtifactStartRequest start)
            throws IOException, InterruptedException, InferenceException {
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/source-artifacts/" + start.artifactId(), token)
                                        .GET()
                                        .build());
        transport.requireTargetStatus(response, 200, "read source " + start.artifactId());
        try {
            return SourceArtifactStatusCodec.parse(CanonicalCodec.parseJson(response.body()));
        } catch (IllegalArgumentException error) {
            throw invalidResponse("read source " + start.artifactId(), error);
        }
    }

    void putChunk(SourceArtifactStartRequest start, SourceByteChunkCommit expected, byte[] body)
            throws IOException, InterruptedException, InferenceException {
        String path = "/source-artifacts/" + start.artifactId() + "/chunks/" + expected.chunkId();
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed(path, token)
                                        .timeout(chunkTimeout)
                                        .header("Content-Type", "application/octet-stream")
                                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                                        .build());
        transport.requireTargetStatus(response, 200, "upload source chunk " + expected.chunkId());
        try {
            SourceByteChunkAccepted accepted =
                    SourceArtifactTransferCodec.parseChunkAccepted(
                            CanonicalCodec.parseJson(response.body()));
            SourceArtifactTransferCodec.validateChunkAcceptance(start, expected, accepted);
        } catch (IllegalArgumentException error) {
            throw invalidResponse("upload source chunk " + expected.chunkId(), error);
        }
    }

    SourceArtifactSealed seal(
            SourceArtifactStartRequest start,
            SourceArtifactAccepted accepted,
            SourceArtifactSealRequest seal)
            throws IOException, InterruptedException, InferenceException {
        byte[] body = CanonicalCodec.canonicalBytes(SourceArtifactTransferCodec.toJson(seal));
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed(
                                                "/source-artifacts/" + start.artifactId() + "/seal",
                                                token)
                                        .timeout(sealTimeout)
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                                        .build());
        transport.requireTargetStatus(response, 200, "seal source " + start.artifactId());
        try {
            SourceArtifactSealed sealed =
                    SourceArtifactPublicationCodec.parseSealed(
                            CanonicalCodec.parseJson(response.body()));
            return SourceArtifactPublicationCodec.validateSealedIdentity(
                    start, accepted, seal, sealed);
        } catch (IllegalArgumentException error) {
            throw invalidResponse("seal source " + start.artifactId(), error);
        }
    }

    SourceArtifactSealed sealedFromStatus(
            SourceArtifactStartRequest start,
            SourceArtifactAccepted accepted,
            SourceArtifactSealRequest seal,
            SourceArtifactStatus status) {
        SourceArtifactSealed sealed =
                new SourceArtifactSealed(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        status.sealedArtifact().orElseThrow(),
                        status.absoluteExpiresAt());
        return SourceArtifactPublicationCodec.validateSealedIdentity(start, accepted, seal, sealed);
    }

    void release(String artifactId) throws IOException, InterruptedException, InferenceException {
        String canonicalId = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
        GatewayTransport.BoundedResponse response =
                transport.sendForDeployment(
                        deploymentId,
                        token ->
                                transport
                                        .authed("/source-artifacts/" + canonicalId, token)
                                        .timeout(releaseTimeout)
                                        .DELETE()
                                        .build());
        transport.requireTargetStatus(response, 204, "release source " + canonicalId);
    }

    private static InferenceException invalidResponse(
            String operation, IllegalArgumentException error) {
        return new InferenceException(operation + ": invalid gateway response", error);
    }
}
