package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Set;

/** Pure chunk-acknowledgement and whole-file seal-request wire contracts. */
public final class SourceArtifactTransferCodec {

    private static final Set<String> CHUNK_COMMIT_FIELDS = Set.of("chunk_id", "sha256");
    private static final Set<String> CHUNK_ACCEPTED_FIELDS =
            Set.of("schema_version", "artifact_id", "chunk");
    private static final Set<String> SEAL_REQUEST_FIELDS = Set.of("bytes_digest");

    private SourceArtifactTransferCodec() {}

    /** Immutable identity of one accepted raw file range. */
    public record SourceByteChunkCommit(int chunkId, String sha256) {
        public SourceByteChunkCommit {
            chunkId = CanonicalCodec.requireNonnegativeInt32(chunkId, "chunk_id");
            sha256 = CanonicalCodec.requireSha256(sha256, "sha256");
        }
    }

    /** Echo returned after one source range is durably accepted. */
    public record SourceByteChunkAccepted(
            int schemaVersion, String artifactId, SourceByteChunkCommit chunk) {
        public SourceByteChunkAccepted {
            requireSchemaOne(schemaVersion);
            artifactId = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
            chunk = Objects.requireNonNull(chunk, "chunk");
        }
    }

    /** Whole-file identity requested when sealing the canonical chunk cover. */
    public record SourceArtifactSealRequest(String bytesDigest) {
        public SourceArtifactSealRequest {
            bytesDigest = CanonicalCodec.requireSha256(bytesDigest, "bytes_digest");
        }
    }

    /** Parse one exact immutable chunk identity. */
    public static SourceByteChunkCommit parseChunkCommit(JsonElement value) {
        JsonObject object = requireExactObject(value, CHUNK_COMMIT_FIELDS, "chunk commit");
        return new SourceByteChunkCommit(
                CanonicalCodec.requireNonnegativeInt32(object.get("chunk_id"), "chunk_id"),
                requiredString(object, "sha256"));
    }

    /** Parse one exact durable chunk acknowledgement. */
    public static SourceByteChunkAccepted parseChunkAccepted(JsonElement value) {
        JsonObject object = requireExactObject(value, CHUNK_ACCEPTED_FIELDS, "chunk acceptance");
        return new SourceByteChunkAccepted(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "artifact_id"),
                parseChunkCommit(object.get("chunk")));
    }

    /** Parse one exact whole-file seal request. */
    public static SourceArtifactSealRequest parseSealRequest(JsonElement value) {
        JsonObject object = requireExactObject(value, SEAL_REQUEST_FIELDS, "seal request");
        return new SourceArtifactSealRequest(requiredString(object, "bytes_digest"));
    }

    /** Encode one immutable chunk identity as exact snake-case JSON. */
    public static JsonObject toJson(SourceByteChunkCommit commit) {
        JsonObject value = new JsonObject();
        value.addProperty("chunk_id", commit.chunkId());
        value.addProperty("sha256", commit.sha256());
        return value;
    }

    /** Encode one durable chunk acknowledgement as exact snake-case JSON. */
    public static JsonObject toJson(SourceByteChunkAccepted accepted) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", accepted.schemaVersion());
        value.addProperty("artifact_id", accepted.artifactId());
        value.add("chunk", toJson(accepted.chunk()));
        return value;
    }

    /** Encode one whole-file seal request as exact snake-case JSON. */
    public static JsonObject toJson(SourceArtifactSealRequest request) {
        JsonObject value = new JsonObject();
        value.addProperty("bytes_digest", request.bytesDigest());
        return value;
    }

    /** Require an acknowledgement to echo the expected chunk of the exact reservation. */
    public static SourceByteChunkAccepted validateChunkAcceptance(
            SourceArtifactCodec.SourceArtifactStartRequest start,
            SourceByteChunkCommit expected,
            SourceByteChunkAccepted accepted) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(accepted, "accepted");
        int count = SourceArtifactCodec.chunkCount(start.totalBytes(), start.chunkSizeBytes());
        if (expected.chunkId() >= count) {
            throw new IllegalArgumentException("chunk_id lies outside the canonical chunk plan");
        }
        if (!accepted.artifactId().equals(start.artifactId())) {
            throw new IllegalArgumentException(
                    "chunk acceptance artifact_id does not match request");
        }
        if (!accepted.chunk().equals(expected)) {
            throw new IllegalArgumentException("chunk acceptance does not match expected commit");
        }
        return accepted;
    }

    private static void requireSchemaOne(int value) {
        if (value != SourceArtifactCodec.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported source artifact schema_version");
        }
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static String requiredString(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return item.getAsString();
    }
}
