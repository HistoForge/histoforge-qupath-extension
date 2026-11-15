package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Canonical finite-call or durable-run identity retained by accepted results. */
public final class BackendExecutionCodec {

    public static final int MAX_BACKEND_CALL_ID_UTF8_BYTES = 128;
    public static final String BACKEND_CALL_QUERY_PARAMETER = "backend_call_id";

    private static final char[] UPPER_HEX = "0123456789ABCDEF".toCharArray();
    private static final String MAXIMUM_CANONICAL_SIZE_BACKEND_CALL_ID =
            "\u0001".repeat(MAX_BACKEND_CALL_ID_UTF8_BYTES);
    private static final Set<String> FINITE_CALL_FIELDS = Set.of("type", "backend_call_id");
    private static final Set<String> DURABLE_RUN_FIELDS =
            Set.of("type", "model_run_id", "result_manifest_digest");

    private BackendExecutionCodec() {}

    /** Closed backend execution identity retained by candidates and committed provenance. */
    public sealed interface BackendExecutionRef permits FiniteCall, DurableRun {}

    /** One replaceable finite backend call. */
    public record FiniteCall(
            // Server-assigned call ID used for polling and commit fencing.
            String backendCallId) implements BackendExecutionRef {
        public FiniteCall {
            backendCallId = requireBackendCallId(backendCallId, "backend_call_id");
        }
    }

    /** One complete immutable durable-run result manifest. */
    public record DurableRun(
            // Canonical UUID of the durable model run.
            String modelRunId,
            // Digest of the immutable terminal result manifest.
            String resultManifestDigest)
            implements BackendExecutionRef {
        public DurableRun {
            modelRunId = CanonicalCodec.requireCanonicalUuid(modelRunId, "model_run_id");
            resultManifestDigest =
                    CanonicalCodec.requireSha256(resultManifestDigest, "result_manifest_digest");
        }
    }

    /** Parse one exact finite-call or durable-run reference. */
    public static BackendExecutionRef parse(JsonElement value) {
        JsonObject object = requiredObject(value, "backend execution reference");
        String type = requiredString(object, "type");
        return switch (type) {
            case "finite-call" -> {
                requireFields(object, FINITE_CALL_FIELDS, "finite-call execution reference");
                yield new FiniteCall(requiredString(object, "backend_call_id"));
            }
            case "durable-run" -> {
                requireFields(object, DURABLE_RUN_FIELDS, "durable-run execution reference");
                yield new DurableRun(
                        requiredString(object, "model_run_id"),
                        requiredString(object, "result_manifest_digest"));
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported backend execution reference type: " + type);
        };
    }

    /** Encode one reference with its closed lower-kebab variant tag. */
    public static JsonObject toJson(BackendExecutionRef execution) {
        JsonObject value = new JsonObject();
        if (execution instanceof FiniteCall finite) {
            value.addProperty("type", "finite-call");
            value.addProperty("backend_call_id", finite.backendCallId());
        } else if (execution instanceof DurableRun durable) {
            value.addProperty("type", "durable-run");
            value.addProperty("model_run_id", durable.modelRunId());
            value.addProperty("result_manifest_digest", durable.resultManifestDigest());
        } else {
            throw new IllegalArgumentException("unsupported backend execution reference");
        }
        return value;
    }

    /** Validate one opaque finite-call ID against its protocol byte ceiling. */
    public static String requireBackendCallId(String value, String path) {
        String callId = CanonicalCodec.requireNonblank(value, path);
        if (CanonicalCodec.utf8Bytes(callId).length > MAX_BACKEND_CALL_ID_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    path + " must be at most " + MAX_BACKEND_CALL_ID_UTF8_BYTES + " UTF-8 bytes");
        }
        return callId;
    }

    /** Encode one validated opaque call ID as its canonical RFC 3986 query. */
    public static String encodeBackendCallQuery(String value) {
        byte[] bytes = CanonicalCodec.utf8Bytes(requireBackendCallId(value, "backend_call_id"));
        StringBuilder query =
                new StringBuilder(BACKEND_CALL_QUERY_PARAMETER.length() + 1 + bytes.length * 3);
        query.append(BACKEND_CALL_QUERY_PARAMETER).append('=');
        for (byte valueByte : bytes) {
            int octet = valueByte & 0xff;
            if (isUnreserved(octet)) {
                query.append((char) octet);
            } else {
                query.append('%');
                query.append(UPPER_HEX[octet >>> 4]);
                query.append(UPPER_HEX[octet & 0x0f]);
            }
        }
        return query.toString();
    }

    /** Return a valid call ID whose JCS string encoding is maximal under the byte ceiling. */
    static String maximumCanonicalSizeBackendCallId() {
        return MAXIMUM_CANONICAL_SIZE_BACKEND_CALL_ID;
    }

    private static boolean isUnreserved(int octet) {
        return (octet >= 'a' && octet <= 'z')
                || (octet >= 'A' && octet <= 'Z')
                || (octet >= '0' && octet <= '9')
                || octet == '-'
                || octet == '.'
                || octet == '_'
                || octet == '~';
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void requireFields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    private static String requiredString(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return item.getAsString();
    }
}
