package qupath.ext.histoforge.contract;

import static qupath.ext.histoforge.contract.StrictJson.requiredString;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure bounded status and cross-record binding for one source-artifact lifecycle. */
public final class SourceArtifactStatusCodec {

    private static final Set<String> UPLOADING_FIELDS =
            Set.of(
                    "schema_version",
                    "artifact_id",
                    "start_request_digest",
                    "phase",
                    "missing_chunks",
                    "upload_idle_expires_at",
                    "absolute_expires_at");
    private static final Set<String> SEALED_FIELDS =
            Set.of(
                    "schema_version",
                    "artifact_id",
                    "start_request_digest",
                    "phase",
                    "missing_chunks",
                    "sealed_artifact",
                    "absolute_expires_at");

    private SourceArtifactStatusCodec() {}

    /** Closed upload lifecycle represented by the bounded status. */
    public enum Phase {
        UPLOADING,
        SEALED
    }

    /** Bounded upload progress plus request digest and optional sealed-source reference. */
    public record SourceArtifactStatus(
            /** Source-artifact status-schema version. */
            int schemaVersion,
            /** Client-generated source reservation being projected. */
            String artifactId,
            /** Digest of the exact canonical start request retained by both sides. */
            String startRequestDigest,
            /** Current closed upload lifecycle phase. */
            Phase phase,
            /** Canonically ordered range IDs not yet durably accepted. */
            List<Integer> missingChunks,
            /** Complete source identity, present exactly after successful seal. */
            Optional<SourceArtifactPublicationCodec.SourceArtifactRef> sealedArtifact,
            /** Incomplete-upload inactivity deadline, present exactly while uploading. */
            Optional<Instant> uploadIdleExpiresAt,
            /** Fixed absolute deadline shared with reservation and seal records. */
            Instant absoluteExpiresAt) {
        public SourceArtifactStatus {
            requireSchemaOne(schemaVersion);
            artifactId = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
            startRequestDigest =
                    CanonicalCodec.requireSha256(startRequestDigest, "start_request_digest");
            phase = Objects.requireNonNull(phase, "phase");
            missingChunks = normalizeMissingChunks(missingChunks);
            sealedArtifact = Objects.requireNonNull(sealedArtifact, "sealed_artifact");
            uploadIdleExpiresAt =
                    Objects.requireNonNull(uploadIdleExpiresAt, "upload_idle_expires_at");
            absoluteExpiresAt = Objects.requireNonNull(absoluteExpiresAt, "absolute_expires_at");
            CanonicalCodec.rfc3339Millis(absoluteExpiresAt, "absolute_expires_at");
            uploadIdleExpiresAt.ifPresent(
                    value -> CanonicalCodec.rfc3339Millis(value, "upload_idle_expires_at"));
            if (phase == Phase.UPLOADING
                    && (sealedArtifact.isPresent() || uploadIdleExpiresAt.isEmpty())) {
                throw new IllegalArgumentException(
                        "UPLOADING status requires upload_idle_expires_at and no sealed_artifact");
            }
            if (phase == Phase.SEALED
                    && (sealedArtifact.isEmpty()
                            || uploadIdleExpiresAt.isPresent()
                            || !missingChunks.isEmpty())) {
                throw new IllegalArgumentException(
                        "SEALED status requires sealed_artifact and no missing/upload-idle state");
            }
        }
    }

    /** Parse one exact status and enforce phase-dependent field presence. */
    public static SourceArtifactStatus parse(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("source artifact status must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        Phase phase = parsePhase(requiredString(object, "phase"));
        Set<String> expected = phase == Phase.UPLOADING ? UPLOADING_FIELDS : SEALED_FIELDS;
        if (!object.keySet().equals(expected)) {
            throw new IllegalArgumentException("source artifact status fields mismatch");
        }
        JsonElement chunksValue = object.get("missing_chunks");
        if (chunksValue == null || !chunksValue.isJsonArray()) {
            throw new IllegalArgumentException("missing_chunks must be an array");
        }
        List<Integer> missingChunks = new ArrayList<>();
        for (JsonElement chunk : chunksValue.getAsJsonArray()) {
            missingChunks.add(CanonicalCodec.requireNonnegativeInt32(chunk, "missing_chunks item"));
        }
        return new SourceArtifactStatus(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "artifact_id"),
                requiredString(object, "start_request_digest"),
                phase,
                missingChunks,
                phase == Phase.SEALED
                        ? Optional.of(
                                SourceArtifactPublicationCodec.parseArtifactRef(
                                        object.get("sealed_artifact")))
                        : Optional.empty(),
                phase == Phase.UPLOADING
                        ? Optional.of(
                                CanonicalCodec.requireRfc3339Millis(
                                        requiredString(object, "upload_idle_expires_at"),
                                        "upload_idle_expires_at"))
                        : Optional.empty(),
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "absolute_expires_at"), "absolute_expires_at"));
    }

    /** Encode one status with absent optionals omitted. */
    public static JsonObject toJson(SourceArtifactStatus status) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", status.schemaVersion());
        value.addProperty("artifact_id", status.artifactId());
        value.addProperty("start_request_digest", status.startRequestDigest());
        value.addProperty("phase", status.phase().name());
        JsonArray chunks = new JsonArray();
        status.missingChunks().forEach(chunks::add);
        value.add("missing_chunks", chunks);
        status.sealedArtifact()
                .ifPresent(
                        artifact ->
                                value.add(
                                        "sealed_artifact",
                                        SourceArtifactPublicationCodec.toJson(artifact)));
        status.uploadIdleExpiresAt()
                .ifPresent(
                        deadline ->
                                value.addProperty(
                                        "upload_idle_expires_at",
                                        CanonicalCodec.rfc3339Millis(
                                                deadline, "upload_idle_expires_at")));
        value.addProperty(
                "absolute_expires_at",
                CanonicalCodec.rfc3339Millis(status.absoluteExpiresAt(), "absolute_expires_at"));
        return value;
    }

    /** Bind one status to its accepted start, requested digest, and seal publication. */
    public static SourceArtifactStatus validateProjection(
            SourceArtifactCodec.SourceArtifactStartRequest start,
            SourceArtifactCodec.SourceArtifactAccepted accepted,
            SourceArtifactStatus status,
            Optional<SourceArtifactTransferCodec.SourceArtifactSealRequest> expectedSealRequest,
            Optional<SourceArtifactPublicationCodec.SourceArtifactSealed> sealed) {
        SourceArtifactCodec.validateAcceptance(start, accepted);
        if (!status.artifactId().equals(start.artifactId())) {
            throw new IllegalArgumentException("status artifact_id does not match request");
        }
        if (!status.absoluteExpiresAt().equals(accepted.absoluteExpiresAt())) {
            throw new IllegalArgumentException(
                    "status absolute deadline does not match acceptance");
        }
        String startRequestDigest = SourceArtifactCodec.startRequestDigest(start);
        if (!status.startRequestDigest().equals(startRequestDigest)) {
            throw new IllegalArgumentException(
                    "status start_request_digest does not match request");
        }
        int chunkCount = SourceArtifactCodec.chunkCount(start.totalBytes(), start.chunkSizeBytes());
        if (status.missingChunks().stream().anyMatch(chunk -> chunk >= chunkCount)) {
            throw new IllegalArgumentException(
                    "status missing_chunks lies outside the canonical chunk plan");
        }
        if (status.phase() == Phase.UPLOADING) {
            if (sealed.isPresent()) {
                throw new IllegalArgumentException(
                        "UPLOADING status cannot bind a seal publication");
            }
            if (status.uploadIdleExpiresAt().orElseThrow().isAfter(status.absoluteExpiresAt())) {
                throw new IllegalArgumentException(
                        "upload idle deadline cannot exceed absolute deadline");
            }
            return status;
        }
        var reference = status.sealedArtifact().orElseThrow();
        var expectedSeal =
                expectedSealRequest.orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "SEALED status requires the expected seal request"));
        if (!reference.artifactId().equals(start.artifactId())) {
            throw new IllegalArgumentException(
                    "status sealed_artifact artifact_id does not match request");
        }
        if (!reference.creationActionId().equals(start.creationActionId())) {
            throw new IllegalArgumentException(
                    "status sealed_artifact creation_action_id does not match request");
        }
        if (!reference.startRequestDigest().equals(startRequestDigest)) {
            throw new IllegalArgumentException(
                    "status sealed_artifact start_request_digest does not match request");
        }
        if (reference.container() != start.container()) {
            throw new IllegalArgumentException(
                    "status sealed_artifact container does not match request");
        }
        if (!reference.bytesDigest().equals(expectedSeal.bytesDigest())) {
            throw new IllegalArgumentException(
                    "status sealed_artifact bytes_digest does not match seal request");
        }
        sealed.ifPresent(
                publication -> {
                    if (!reference.equals(publication.artifact())) {
                        throw new IllegalArgumentException(
                                "status sealed_artifact does not match seal publication");
                    }
                    if (!status.absoluteExpiresAt().equals(publication.absoluteExpiresAt())) {
                        throw new IllegalArgumentException(
                                "status absolute deadline does not match seal publication");
                    }
                });
        return status;
    }

    private static List<Integer> normalizeMissingChunks(List<Integer> chunks) {
        if (chunks == null) {
            throw new IllegalArgumentException("missing_chunks must be an array");
        }
        List<Integer> result = new ArrayList<>(chunks.size());
        for (Integer chunk : chunks) {
            if (chunk == null) {
                throw new IllegalArgumentException("missing_chunks item must be an integer");
            }
            result.add(CanonicalCodec.requireNonnegativeInt32(chunk, "missing_chunks item"));
        }
        result.sort(Comparator.naturalOrder());
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("missing_chunks contains a duplicate chunk ID");
        }
        return List.copyOf(result);
    }

    private static Phase parsePhase(String value) {
        try {
            return Phase.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported source artifact phase: " + value, e);
        }
    }

    private static void requireSchemaOne(int value) {
        if (value != SourceArtifactCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported source artifact schema_version");
        }
    }
}
