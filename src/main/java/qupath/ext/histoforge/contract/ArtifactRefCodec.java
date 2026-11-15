package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure wire codec for one immutable downloadable byte representation. */
public final class ArtifactRefCodec {

    private static final Set<String> REQUIRED_FIELDS =
            Set.of("artifact_id", "media_type", "size_bytes", "sha256");
    private static final Set<String> FIELDS_WITH_ENCODING =
            Set.of("artifact_id", "media_type", "content_encoding", "size_bytes", "sha256");
    private static final Pattern HTTP_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9a-z-]+");
    private static final Pattern MEDIA_TYPE =
            Pattern.compile("[!#$%&'*+.^_`|~0-9a-z-]+/[!#$%&'*+.^_`|~0-9a-z-]+");

    private ArtifactRefCodec() {}

    /** Opaque locator plus complete identity of the transferred representation. */
    public record ArtifactRef(
            /** Backend-generated UUID resolved only by the authenticated artifact endpoint. */
            String artifactId,
            /** Lowercase parameter-free media type of the transferred bytes. */
            String mediaType,
            /** Lowercase content coding, absent when the representation is unencoded. */
            Optional<String> contentEncoding,
            /** Exact nonnegative transferred-byte length. */
            long sizeBytes,
            /** SHA-256 of the transferred bytes and source of the strong ETag. */
            String sha256) {
        public ArtifactRef {
            artifactId = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
            mediaType = requireMediaType(mediaType);
            contentEncoding =
                    Objects.requireNonNull(contentEncoding, "content_encoding")
                            .map(ArtifactRefCodec::requireContentEncoding);
            sizeBytes = CanonicalCodec.requireNonnegativeSafeInteger(sizeBytes, "size_bytes");
            sha256 = CanonicalCodec.requireSha256(sha256, "sha256");
        }
    }

    /** Parse one exact artifact reference, with absent optionals omitted rather than null. */
    public static ArtifactRef parse(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("artifact ref must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        Set<String> fields = object.keySet();
        if (!fields.equals(REQUIRED_FIELDS) && !fields.equals(FIELDS_WITH_ENCODING)) {
            throw new IllegalArgumentException("artifact ref fields mismatch");
        }
        return new ArtifactRef(
                requiredString(object, "artifact_id"),
                requiredString(object, "media_type"),
                object.has("content_encoding")
                        ? Optional.of(requiredString(object, "content_encoding"))
                        : Optional.empty(),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("size_bytes"), "size_bytes"),
                requiredString(object, "sha256"));
    }

    /** Encode one reference using exact lower-snake wire fields. */
    public static JsonObject toJson(ArtifactRef reference) {
        JsonObject value = new JsonObject();
        value.addProperty("artifact_id", reference.artifactId());
        value.addProperty("media_type", reference.mediaType());
        reference
                .contentEncoding()
                .ifPresent(encoding -> value.addProperty("content_encoding", encoding));
        value.addProperty("size_bytes", reference.sizeBytes());
        value.addProperty("sha256", reference.sha256());
        return value;
    }

    private static String requireMediaType(String value) {
        String mediaType = CanonicalCodec.requireNonblank(value, "media_type");
        if (!MEDIA_TYPE.matcher(mediaType).matches()) {
            throw new IllegalArgumentException(
                    "media_type must be a lowercase parameter-free HTTP media type");
        }
        return mediaType;
    }

    private static String requireContentEncoding(String value) {
        String encoding = CanonicalCodec.requireNonblank(value, "content_encoding");
        if (!HTTP_TOKEN.matcher(encoding).matches()) {
            throw new IllegalArgumentException("content_encoding must be one lowercase HTTP token");
        }
        return encoding;
    }

    private static String requiredString(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }
}
