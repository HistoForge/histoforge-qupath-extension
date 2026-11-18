package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure validated-manifest, sealed-reference, and seal-response contracts. */
public final class SourceArtifactPublicationCodec {

    private static final Set<String> LEVEL_FIELDS =
            Set.of("series_index", "resolution_level", "width", "height");
    private static final Set<String> MANIFEST_FIELDS =
            Set.of(
                    "schema_version",
                    "artifact_id",
                    "creation_action_id",
                    "container",
                    "total_bytes",
                    "bytes_digest",
                    "validated_levels",
                    "manifest_digest");
    private static final Set<String> REFERENCE_FIELDS =
            Set.of(
                    "artifact_id",
                    "creation_action_id",
                    "start_request_digest",
                    "bytes_digest",
                    "manifest_digest",
                    "container");
    private static final Set<String> SEALED_FIELDS =
            Set.of("schema_version", "artifact", "absolute_expires_at");

    private SourceArtifactPublicationCodec() {}

    /** Actual source level verified from the decoded container. */
    public record ValidatedSourceLevel(
            int seriesIndex, int resolutionLevel, int width, int height) {
        public ValidatedSourceLevel {
            seriesIndex = CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index");
            resolutionLevel =
                    CanonicalCodec.requireNonnegativeInt32(resolutionLevel, "resolution_level");
            width = CanonicalCodec.requirePositiveInt32(width, "width");
            height = CanonicalCodec.requirePositiveInt32(height, "height");
        }
    }

    /** Immutable self-digested description of one reader-validated source file. */
    public record SourceArtifactManifest(
            int schemaVersion,
            String artifactId,
            String creationActionId,
            SourceArtifactCodec.SourceContainer container,
            long totalBytes,
            String bytesDigest,
            List<ValidatedSourceLevel> validatedLevels,
            String manifestDigest) {
        public SourceArtifactManifest {
            requireSchemaOne(schemaVersion);
            artifactId = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
            creationActionId =
                    CanonicalCodec.requireCanonicalUuid(creationActionId, "creation_action_id");
            container = Objects.requireNonNull(container, "container");
            totalBytes = CanonicalCodec.requirePositiveSafeInteger(totalBytes, "total_bytes");
            bytesDigest = CanonicalCodec.requireSha256(bytesDigest, "bytes_digest");
            validatedLevels = normalizeValidatedLevels(validatedLevels);
            manifestDigest = CanonicalCodec.requireSha256(manifestDigest, "manifest_digest");
            String expected =
                    computeManifestDigest(
                            schemaVersion,
                            artifactId,
                            creationActionId,
                            container,
                            totalBytes,
                            bytesDigest,
                            validatedLevels);
            if (!manifestDigest.equals(expected)) {
                throw new IllegalArgumentException(
                        "manifest_digest does not match manifest fields");
            }
        }
    }

    /** Compact immutable source identity accepted by finite and durable inference. */
    public record SourceArtifactRef(
            String artifactId,
            String creationActionId,
            String startRequestDigest,
            String bytesDigest,
            String manifestDigest,
            SourceArtifactCodec.SourceContainer container) {
        public SourceArtifactRef {
            artifactId = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
            creationActionId =
                    CanonicalCodec.requireCanonicalUuid(creationActionId, "creation_action_id");
            startRequestDigest =
                    CanonicalCodec.requireSha256(startRequestDigest, "start_request_digest");
            bytesDigest = CanonicalCodec.requireSha256(bytesDigest, "bytes_digest");
            manifestDigest = CanonicalCodec.requireSha256(manifestDigest, "manifest_digest");
            container = Objects.requireNonNull(container, "container");
        }
    }

    /** Seal response exposing the immutable reference and fixed absolute deadline. */
    public record SourceArtifactSealed(
            int schemaVersion, SourceArtifactRef artifact, Instant absoluteExpiresAt) {
        public SourceArtifactSealed {
            requireSchemaOne(schemaVersion);
            artifact = Objects.requireNonNull(artifact, "artifact");
            absoluteExpiresAt = Objects.requireNonNull(absoluteExpiresAt, "absolute_expires_at");
            CanonicalCodec.rfc3339Millis(absoluteExpiresAt, "absolute_expires_at");
        }
    }

    /** Parse one exact reader-validated level. */
    public static ValidatedSourceLevel parseValidatedLevel(JsonElement value) {
        JsonObject object = requireExactObject(value, LEVEL_FIELDS, "validated source level");
        return new ValidatedSourceLevel(
                CanonicalCodec.requireNonnegativeInt32(object.get("series_index"), "series_index"),
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("resolution_level"), "resolution_level"),
                CanonicalCodec.requirePositiveInt32(object.get("width"), "width"),
                CanonicalCodec.requirePositiveInt32(object.get("height"), "height"));
    }

    /** Parse and verify one self-digested source manifest. */
    public static SourceArtifactManifest parseManifest(JsonElement value) {
        JsonObject object = requireExactObject(value, MANIFEST_FIELDS, "source manifest");
        JsonElement levelsValue = object.get("validated_levels");
        if (levelsValue == null || !levelsValue.isJsonArray()) {
            throw new IllegalArgumentException("validated_levels must be a nonempty array");
        }
        List<ValidatedSourceLevel> levels = new ArrayList<>();
        for (JsonElement level : levelsValue.getAsJsonArray()) {
            levels.add(parseValidatedLevel(level));
        }
        return new SourceArtifactManifest(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "artifact_id"),
                requiredString(object, "creation_action_id"),
                SourceArtifactCodec.parseContainer(requiredString(object, "container")),
                CanonicalCodec.requirePositiveSafeInteger(object.get("total_bytes"), "total_bytes"),
                requiredString(object, "bytes_digest"),
                levels,
                requiredString(object, "manifest_digest"));
    }

    /** Parse one exact compact sealed source reference. */
    public static SourceArtifactRef parseArtifactRef(JsonElement value) {
        JsonObject object = requireExactObject(value, REFERENCE_FIELDS, "source artifact ref");
        return new SourceArtifactRef(
                requiredString(object, "artifact_id"),
                requiredString(object, "creation_action_id"),
                requiredString(object, "start_request_digest"),
                requiredString(object, "bytes_digest"),
                requiredString(object, "manifest_digest"),
                SourceArtifactCodec.parseContainer(requiredString(object, "container")));
    }

    /** Parse one exact seal response. */
    public static SourceArtifactSealed parseSealed(JsonElement value) {
        JsonObject object = requireExactObject(value, SEALED_FIELDS, "source artifact sealed");
        return new SourceArtifactSealed(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                parseArtifactRef(object.get("artifact")),
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "absolute_expires_at"), "absolute_expires_at"));
    }

    /** Encode one reader-validated level as exact snake-case JSON. */
    public static JsonObject toJson(ValidatedSourceLevel level) {
        JsonObject value = new JsonObject();
        value.addProperty("series_index", level.seriesIndex());
        value.addProperty("resolution_level", level.resolutionLevel());
        value.addProperty("width", level.width());
        value.addProperty("height", level.height());
        return value;
    }

    /** Encode one self-digested source manifest as exact snake-case JSON. */
    public static JsonObject toJson(SourceArtifactManifest manifest) {
        JsonObject value =
                manifestPreimage(
                        manifest.schemaVersion(),
                        manifest.artifactId(),
                        manifest.creationActionId(),
                        manifest.container(),
                        manifest.totalBytes(),
                        manifest.bytesDigest(),
                        manifest.validatedLevels());
        value.addProperty("manifest_digest", manifest.manifestDigest());
        return value;
    }

    /** Encode one compact sealed source reference as exact snake-case JSON. */
    public static JsonObject toJson(SourceArtifactRef reference) {
        JsonObject value = new JsonObject();
        value.addProperty("artifact_id", reference.artifactId());
        value.addProperty("creation_action_id", reference.creationActionId());
        value.addProperty("start_request_digest", reference.startRequestDigest());
        value.addProperty("bytes_digest", reference.bytesDigest());
        value.addProperty("manifest_digest", reference.manifestDigest());
        value.addProperty("container", reference.container().name());
        return value;
    }

    /** Encode one seal response as exact snake-case JSON. */
    public static JsonObject toJson(SourceArtifactSealed sealed) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", sealed.schemaVersion());
        value.add("artifact", toJson(sealed.artifact()));
        value.addProperty(
                "absolute_expires_at",
                CanonicalCodec.rfc3339Millis(sealed.absoluteExpiresAt(), "absolute_expires_at"));
        return value;
    }

    /** Build the unique manifest for one start, seal request, and reader result. */
    public static SourceArtifactManifest buildManifest(
            SourceArtifactCodec.SourceArtifactStartRequest start,
            SourceArtifactTransferCodec.SourceArtifactSealRequest seal,
            List<ValidatedSourceLevel> validatedLevels) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(seal, "seal");
        List<ValidatedSourceLevel> levels = normalizeValidatedLevels(validatedLevels);
        validateLevelCoverage(start, levels);
        String digest =
                computeManifestDigest(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        start.artifactId(),
                        start.creationActionId(),
                        start.container(),
                        start.totalBytes(),
                        seal.bytesDigest(),
                        levels);
        return new SourceArtifactManifest(
                SourceArtifactCodec.SCHEMA_VERSION,
                start.artifactId(),
                start.creationActionId(),
                start.container(),
                start.totalBytes(),
                seal.bytesDigest(),
                levels,
                digest);
    }

    /** Require one manifest to bind the exact start and seal requests. */
    public static SourceArtifactManifest validatePublication(
            SourceArtifactCodec.SourceArtifactStartRequest start,
            SourceArtifactTransferCodec.SourceArtifactSealRequest seal,
            SourceArtifactManifest manifest) {
        SourceArtifactManifest expected = buildManifest(start, seal, manifest.validatedLevels());
        if (!manifest.equals(expected)) {
            throw new IllegalArgumentException(
                    "source manifest does not match start and seal requests");
        }
        return manifest;
    }

    /** Derive the only compact reference for one start request and manifest. */
    public static SourceArtifactRef buildArtifactRef(
            SourceArtifactCodec.SourceArtifactStartRequest start, SourceArtifactManifest manifest) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(manifest, "manifest");
        requireManifestStartMatch(start, manifest);
        return new SourceArtifactRef(
                start.artifactId(),
                start.creationActionId(),
                SourceArtifactCodec.startRequestDigest(start),
                manifest.bytesDigest(),
                manifest.manifestDigest(),
                manifest.container());
    }

    /** Require a supplied compact reference to equal the uniquely derived value. */
    public static SourceArtifactRef validateReference(
            SourceArtifactCodec.SourceArtifactStartRequest start,
            SourceArtifactManifest manifest,
            SourceArtifactRef reference) {
        if (!reference.equals(buildArtifactRef(start, manifest))) {
            throw new IllegalArgumentException(
                    "source artifact ref does not match start request and manifest");
        }
        return reference;
    }

    /** Bind a seal response to its reservation, publication, and fixed deadline. */
    public static SourceArtifactSealed validateSealResponse(
            SourceArtifactCodec.SourceArtifactStartRequest start,
            SourceArtifactCodec.SourceArtifactAccepted accepted,
            SourceArtifactTransferCodec.SourceArtifactSealRequest seal,
            SourceArtifactManifest manifest,
            SourceArtifactSealed sealed) {
        validateSealedIdentity(start, accepted, seal, sealed);
        SourceArtifactManifest publication = validatePublication(start, seal, manifest);
        if (!sealed.artifact().equals(buildArtifactRef(start, publication))) {
            throw new IllegalArgumentException("sealed artifact ref does not match publication");
        }
        return sealed;
    }

    /** Bind the compact seal response fields available to a client without the private manifest. */
    public static SourceArtifactSealed validateSealedIdentity(
            SourceArtifactCodec.SourceArtifactStartRequest start,
            SourceArtifactCodec.SourceArtifactAccepted accepted,
            SourceArtifactTransferCodec.SourceArtifactSealRequest seal,
            SourceArtifactSealed sealed) {
        SourceArtifactCodec.validateAcceptance(start, accepted);
        Objects.requireNonNull(seal, "seal");
        Objects.requireNonNull(sealed, "sealed");
        SourceArtifactRef reference = sealed.artifact();
        if (!reference.artifactId().equals(start.artifactId())
                || !reference.creationActionId().equals(start.creationActionId())
                || !reference
                        .startRequestDigest()
                        .equals(SourceArtifactCodec.startRequestDigest(start))
                || reference.container() != start.container()
                || !reference.bytesDigest().equals(seal.bytesDigest())) {
            throw new IllegalArgumentException(
                    "sealed artifact identity does not match start and seal requests");
        }
        if (!sealed.absoluteExpiresAt().equals(accepted.absoluteExpiresAt())) {
            throw new IllegalArgumentException("sealed deadline does not match accepted deadline");
        }
        return sealed;
    }

    private static String computeManifestDigest(
            int schemaVersion,
            String artifactId,
            String creationActionId,
            SourceArtifactCodec.SourceContainer container,
            long totalBytes,
            String bytesDigest,
            List<ValidatedSourceLevel> levels) {
        return CanonicalCodec.canonicalDigest(
                manifestPreimage(
                        schemaVersion,
                        artifactId,
                        creationActionId,
                        container,
                        totalBytes,
                        bytesDigest,
                        levels));
    }

    private static JsonObject manifestPreimage(
            int schemaVersion,
            String artifactId,
            String creationActionId,
            SourceArtifactCodec.SourceContainer container,
            long totalBytes,
            String bytesDigest,
            List<ValidatedSourceLevel> levels) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", schemaVersion);
        value.addProperty("artifact_id", artifactId);
        value.addProperty("creation_action_id", creationActionId);
        value.addProperty("container", container.name());
        value.addProperty("total_bytes", totalBytes);
        value.addProperty("bytes_digest", bytesDigest);
        JsonArray encodedLevels = new JsonArray();
        levels.forEach(level -> encodedLevels.add(toJson(level)));
        value.add("validated_levels", encodedLevels);
        return value;
    }

    private static List<ValidatedSourceLevel> normalizeValidatedLevels(
            List<ValidatedSourceLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException("validated_levels must be a nonempty array");
        }
        List<ValidatedSourceLevel> result = new ArrayList<>(levels.size());
        for (ValidatedSourceLevel level : levels) {
            result.add(Objects.requireNonNull(level, "validated_levels item"));
        }
        result.sort(
                Comparator.comparingInt(ValidatedSourceLevel::seriesIndex)
                        .thenComparingInt(ValidatedSourceLevel::resolutionLevel));
        Set<LevelKey> keys = new HashSet<>();
        for (ValidatedSourceLevel level : result) {
            if (!keys.add(new LevelKey(level.seriesIndex(), level.resolutionLevel()))) {
                throw new IllegalArgumentException(
                        "validated_levels contains a duplicate series/level key");
            }
        }
        return List.copyOf(result);
    }

    private static void validateLevelCoverage(
            SourceArtifactCodec.SourceArtifactStartRequest start,
            List<ValidatedSourceLevel> levels) {
        for (SourceArtifactCodec.SourceLevelRequirement required : start.requiredLevels()) {
            boolean match =
                    levels.stream()
                            .anyMatch(
                                    level ->
                                            level.seriesIndex() == required.seriesIndex()
                                                    && level.resolutionLevel()
                                                            == required.resolutionLevel()
                                                    && level.width() == required.width()
                                                    && level.height() == required.height());
            if (!match) {
                throw new IllegalArgumentException(
                        "validated_levels do not satisfy required level dimensions");
            }
        }

        if (start.origin() instanceof SourceArtifactCodec.OriginalWsi original) {
            if (levels.stream()
                    .anyMatch(level -> level.seriesIndex() != original.pyramidSeriesIndex())) {
                throw new IllegalArgumentException(
                        "original WSI validated levels must use pyramid_series_index");
            }
            return;
        }

        SourceArtifactCodec.SourceLevelRequirement required = start.requiredLevels().getFirst();
        ValidatedSourceLevel expected =
                new ValidatedSourceLevel(
                        required.seriesIndex(),
                        required.resolutionLevel(),
                        required.width(),
                        required.height());
        if (levels.size() != 1 || !levels.getFirst().equals(expected)) {
            throw new IllegalArgumentException(
                    "derived region must have exactly its required validated level");
        }
    }

    private static void requireManifestStartMatch(
            SourceArtifactCodec.SourceArtifactStartRequest start, SourceArtifactManifest manifest) {
        if (!manifest.artifactId().equals(start.artifactId())) {
            throw new IllegalArgumentException(
                    "source manifest artifact_id does not match start request");
        }
        if (!manifest.creationActionId().equals(start.creationActionId())) {
            throw new IllegalArgumentException(
                    "source manifest creation_action_id does not match start request");
        }
        if (manifest.container() != start.container()) {
            throw new IllegalArgumentException(
                    "source manifest container does not match start request");
        }
        if (manifest.totalBytes() != start.totalBytes()) {
            throw new IllegalArgumentException(
                    "source manifest total_bytes does not match start request");
        }
        validateLevelCoverage(start, manifest.validatedLevels());
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

    private record LevelKey(int seriesIndex, int resolutionLevel) {}
}
