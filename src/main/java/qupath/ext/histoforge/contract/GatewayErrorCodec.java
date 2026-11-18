package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Closed schema-1 gateway error envelope, HTTP mapping, and retry policy. */
public final class GatewayErrorCodec {

    private static final Set<String> REQUIRED_FIELDS = Set.of("schema_version", "code", "message");
    private static final Set<String> OPTIONAL_FIELDS = Set.of("resource_id", "retry_not_before");
    private static final Set<String> ALL_FIELDS = allFields();
    private static final Map<Code, Integer> HTTP_STATUS = httpStatuses();
    private static final Set<Code> AUTOMATIC_RETRY_CODES =
            EnumSet.of(Code.RATE_LIMITED, Code.REGISTRY_RATE_LIMITED, Code.SERVICE_UNAVAILABLE);

    private GatewayErrorCodec() {}

    /** Closed machine-actionable failure vocabulary shared by every target gateway route. */
    public enum Code {
        AUTHENTICATION_REQUIRED,
        VALIDATION_ERROR,
        UNSUPPORTED_SCHEMA,
        UNSUPPORTED_CAPABILITY,
        PAYLOAD_TOO_LARGE,
        RATE_LIMITED,
        MODULE_NOT_FOUND,
        MODULE_BUSY,
        MODULE_VERSION_UNAVAILABLE,
        MODULE_IDENTITY_MISMATCH,
        JOB_NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        ARTIFACT_NOT_FOUND,
        ARTIFACT_EXPIRED,
        ARTIFACT_PRECONDITION_FAILED,
        RANGE_NOT_SATISFIABLE,
        CALL_NOT_FOUND,
        CALL_EXPIRED,
        CALL_CANCELLED,
        RUNNER_FAILED,
        INSUFFICIENT_CONTEXT,
        RESULT_LIMIT_EXCEEDED,
        REGISTRY_AUTHENTICATION_FAILED,
        REGISTRY_REPOSITORY_NOT_FOUND,
        REGISTRY_REVISION_NOT_FOUND,
        REGISTRY_PUBLICATION_CONFLICT,
        REGISTRY_RATE_LIMITED,
        MODEL_RUN_NOT_FOUND,
        MODEL_RUN_EXPIRED,
        TRAINING_DATASET_NOT_FOUND,
        TRAINING_UPLOAD_NOT_FOUND,
        TRAINING_RUN_NOT_FOUND,
        TRAINING_STATE_CONFLICT,
        PROVIDER_CREDIT_EXHAUSTED,
        PROVIDER_LIMIT_REACHED,
        SERVICE_UNAVAILABLE,
        INTERNAL_ERROR
    }

    /** Typed error body; messages remain diagnostic and never drive client branching. */
    public record Error(
            int schemaVersion,
            Code code,
            String message,
            Optional<String> resourceId,
            Optional<Instant> retryNotBefore) {
        public Error {
            if (schemaVersion != 1) {
                throw new IllegalArgumentException("unsupported gateway error schema_version");
            }
            if (code == null || resourceId == null || retryNotBefore == null) {
                throw new IllegalArgumentException("gateway error fields must not be null");
            }
            if (message == null) {
                throw new IllegalArgumentException("message must not be null");
            }
            CanonicalCodec.utf8Bytes(message);
            resourceId.ifPresent(CanonicalCodec::utf8Bytes);
            retryNotBefore.ifPresent(
                    value -> CanonicalCodec.rfc3339Millis(value, "retry_not_before"));
            if (retryNotBefore.isPresent() && !isAutomaticallyRetryable(code)) {
                throw new IllegalArgumentException(
                        "retry_not_before is valid only for retryable errors");
            }
        }
    }

    /** Parse one closed snake-case schema-1 error value. */
    public static Error parse(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("gateway error must be an object");
        }
        return parseObject(value.getAsJsonObject());
    }

    private static Error parseObject(JsonObject value) {
        if (value == null
                || !value.keySet().containsAll(REQUIRED_FIELDS)
                || !ALL_FIELDS.containsAll(value.keySet())) {
            throw new IllegalArgumentException("gateway error has missing or unknown fields");
        }
        int schemaVersion =
                CanonicalCodec.requireInt32(value.get("schema_version"), "schema_version");
        String codeText = requiredString(value.get("code"), "code");
        final Code code;
        try {
            code = Code.valueOf(codeText);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown GatewayErrorCode: " + codeText, e);
        }
        String message = requiredString(value.get("message"), "message");
        Optional<String> resourceId = optionalString(value, "resource_id");
        Optional<Instant> retryNotBefore =
                optionalString(value, "retry_not_before")
                        .map(
                                timestamp ->
                                        CanonicalCodec.requireRfc3339Millis(
                                                timestamp, "retry_not_before"));
        return new Error(schemaVersion, code, message, resourceId, retryNotBefore);
    }

    /** Encode one typed error as the exact snake-case schema-1 JSON object. */
    public static JsonObject toJson(Error error) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", error.schemaVersion());
        value.addProperty("code", error.code().name());
        value.addProperty("message", error.message());
        error.resourceId().ifPresent(item -> value.addProperty("resource_id", item));
        error.retryNotBefore()
                .ifPresent(
                        item ->
                                value.addProperty(
                                        "retry_not_before",
                                        CanonicalCodec.rfc3339Millis(item, "retry_not_before")));
        return value;
    }

    /** Return the one settled HTTP status for this error code. */
    public static int httpStatus(Code code) {
        Integer status = HTTP_STATUS.get(code);
        if (status == null) {
            throw new IllegalArgumentException("unknown GatewayErrorCode");
        }
        return status;
    }

    /** Return whether clients may automatically retry this coded failure. */
    public static boolean isAutomaticallyRetryable(Code code) {
        return AUTOMATIC_RETRY_CODES.contains(code);
    }

    private static Optional<String> optionalString(JsonObject value, String field) {
        if (!value.has(field)) {
            return Optional.empty();
        }
        return Optional.of(requiredString(value.get(field), field));
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }

    private static Set<String> allFields() {
        Set<String> fields = new HashSet<>(REQUIRED_FIELDS);
        fields.addAll(OPTIONAL_FIELDS);
        return Set.copyOf(fields);
    }

    private static Map<Code, Integer> httpStatuses() {
        EnumMap<Code, Integer> statuses = new EnumMap<>(Code.class);
        put(statuses, 401, Code.AUTHENTICATION_REQUIRED);
        put(statuses, 402, Code.PROVIDER_CREDIT_EXHAUSTED);
        put(
                statuses,
                404,
                Code.MODULE_NOT_FOUND,
                Code.JOB_NOT_FOUND,
                Code.ARTIFACT_NOT_FOUND,
                Code.CALL_NOT_FOUND,
                Code.MODEL_RUN_NOT_FOUND,
                Code.TRAINING_DATASET_NOT_FOUND,
                Code.TRAINING_UPLOAD_NOT_FOUND,
                Code.TRAINING_RUN_NOT_FOUND);
        put(
                statuses,
                409,
                Code.MODULE_BUSY,
                Code.MODULE_VERSION_UNAVAILABLE,
                Code.MODULE_IDENTITY_MISMATCH,
                Code.IDEMPOTENCY_CONFLICT,
                Code.CALL_CANCELLED,
                Code.TRAINING_STATE_CONFLICT);
        put(statuses, 410, Code.ARTIFACT_EXPIRED, Code.CALL_EXPIRED, Code.MODEL_RUN_EXPIRED);
        put(statuses, 412, Code.ARTIFACT_PRECONDITION_FAILED);
        put(statuses, 413, Code.PAYLOAD_TOO_LARGE, Code.RESULT_LIMIT_EXCEEDED);
        put(statuses, 416, Code.RANGE_NOT_SATISFIABLE);
        put(
                statuses,
                422,
                Code.VALIDATION_ERROR,
                Code.UNSUPPORTED_SCHEMA,
                Code.UNSUPPORTED_CAPABILITY,
                Code.INSUFFICIENT_CONTEXT);
        put(statuses, 429, Code.RATE_LIMITED, Code.PROVIDER_LIMIT_REACHED);
        put(
                statuses,
                502,
                Code.RUNNER_FAILED,
                Code.REGISTRY_AUTHENTICATION_FAILED,
                Code.REGISTRY_REPOSITORY_NOT_FOUND,
                Code.REGISTRY_REVISION_NOT_FOUND,
                Code.REGISTRY_PUBLICATION_CONFLICT,
                Code.REGISTRY_RATE_LIMITED);
        put(statuses, 503, Code.SERVICE_UNAVAILABLE);
        put(statuses, 500, Code.INTERNAL_ERROR);
        if (!statuses.keySet().equals(EnumSet.allOf(Code.class))) {
            throw new IllegalStateException("every GatewayErrorCode must have one HTTP status");
        }
        return Map.copyOf(statuses);
    }

    private static void put(Map<Code, Integer> statuses, int status, Code... codes) {
        for (Code code : codes) {
            if (statuses.put(code, status) != null) {
                throw new IllegalStateException("duplicate HTTP mapping for " + code);
            }
        }
    }
}
