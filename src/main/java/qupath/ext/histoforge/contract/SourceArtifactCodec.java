package qupath.ext.histoforge.contract;

import static qupath.ext.histoforge.contract.StrictJson.requireExactObject;
import static qupath.ext.histoforge.contract.StrictJson.requireFields;
import static qupath.ext.histoforge.contract.StrictJson.requireObject;
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
import java.util.Set;

/** Immutable source-upload reservation values and their canonical byte-range plan. */
public final class SourceArtifactCodec {

    public static final int SCHEMA_VERSION = 2;

    private static final Set<String> ORIGINAL_FIELDS =
            Set.of(
                    "type",
                    "source",
                    "pyramid_series_index",
                    "level_zero_pixels_to_slide",
                    "entry_path",
                    "reader_settings");
    private static final Set<String> DERIVED_FIELDS =
            Set.of(
                    "type",
                    "source",
                    "source_resolution_level",
                    "plane",
                    "source_rect_at_level",
                    "artifact_pixels_to_slide",
                    "writer_build");
    private static final Set<String> LEVEL_FIELDS =
            Set.of("series_index", "resolution_level", "width", "height");
    private static final Set<String> START_FIELDS =
            Set.of(
                    "schema_version",
                    "creation_action_id",
                    "artifact_id",
                    "container",
                    "total_bytes",
                    "chunk_size_bytes",
                    "origin",
                    "required_levels");
    private static final Set<String> ACCEPTED_FIELDS =
            Set.of(
                    "schema_version",
                    "creation_action_id",
                    "artifact_id",
                    "start_request_digest",
                    "absolute_expires_at");

    private SourceArtifactCodec() {}

    /** Closed encoded-image families accepted by the source reader. */
    public enum SourceContainer {
        PNG,
        TIFF_FAMILY,
        SLIDE_FILE,
        SLIDE_FILE_SET
    }

    /** Original bytes or a derived source-level capture with its exact slide mapping. */
    public sealed interface SourceArtifactOrigin permits OriginalWsi, DerivedRegion {}

    /** Original entry and reader interpretation, with byte evidence for any packaged members. */
    public record OriginalWsi(
            SourceIdentityCodec.ImageSourceIdentity source,
            int pyramidSeriesIndex,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            String entryPath,
            OriginalReaderSettingsCodec.Settings readerSettings,
            List<SlideFileSetCodec.Member> members)
            implements SourceArtifactOrigin {
        public OriginalWsi {
            source = Objects.requireNonNull(source, "source");
            if (source.contentVersion().isEmpty()) {
                throw new IllegalArgumentException(
                        "original WSI source must have a stable content_version");
            }
            pyramidSeriesIndex =
                    CanonicalCodec.requireNonnegativeInt32(
                            pyramidSeriesIndex, "pyramid_series_index");
            levelZeroPixelsToSlide =
                    Objects.requireNonNull(levelZeroPixelsToSlide, "level_zero_pixels_to_slide");
            entryPath = SlideFileSetCodec.relativePath(entryPath);
            readerSettings = Objects.requireNonNull(readerSettings, "reader_settings");
            if (!source.sourceAdapterId().equals(readerSettings.adapter()))
                throw new IllegalArgumentException(
                        "reader settings differ from captured source adapter");
            members = members.isEmpty() ? List.of() : SlideFileSetCodec.normalizeMembers(members);
        }
    }

    /** Lossless capture of one source-level rectangle before model-MPP resampling. */
    public record DerivedRegion(
            SourceIdentityCodec.ImageSourceIdentity source,
            int sourceResolutionLevel,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.IntRect sourceRectAtLevel,
            SourceIdentityCodec.ScaleTranslate2D artifactPixelsToSlide,
            BuildIdentityCodec.SoftwareBuildIdentity writerBuild)
            implements SourceArtifactOrigin {
        public DerivedRegion {
            source = Objects.requireNonNull(source, "source");
            sourceResolutionLevel =
                    CanonicalCodec.requireNonnegativeInt32(
                            sourceResolutionLevel, "source_resolution_level");
            plane = Objects.requireNonNull(plane, "plane");
            if (plane.z() != 0 || plane.t() != 0) {
                throw new IllegalArgumentException("source artifacts support only plane z=0, t=0");
            }
            sourceRectAtLevel = Objects.requireNonNull(sourceRectAtLevel, "source_rect_at_level");
            artifactPixelsToSlide =
                    Objects.requireNonNull(artifactPixelsToSlide, "artifact_pixels_to_slide");
            writerBuild = Objects.requireNonNull(writerBuild, "writer_build");
        }
    }

    /** Required decoded shape that seal independently verifies. */
    public record SourceLevelRequirement(
            int seriesIndex, int resolutionLevel, int width, int height) {
        public SourceLevelRequirement {
            seriesIndex = CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index");
            resolutionLevel =
                    CanonicalCodec.requireNonnegativeInt32(resolutionLevel, "resolution_level");
            width = CanonicalCodec.requirePositiveInt32(width, "width");
            height = CanonicalCodec.requirePositiveInt32(height, "height");
        }
    }

    /** Replayable reservation for one immutable encoded image file. */
    public record SourceArtifactStartRequest(
            int schemaVersion,
            String creationActionId,
            String artifactId,
            SourceContainer container,
            long totalBytes,
            int chunkSizeBytes,
            SourceArtifactOrigin origin,
            List<SourceLevelRequirement> requiredLevels) {
        public SourceArtifactStartRequest {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported source artifact schema_version");
            }
            creationActionId =
                    CanonicalCodec.requireCanonicalUuid(creationActionId, "creation_action_id");
            artifactId = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
            container = Objects.requireNonNull(container, "container");
            totalBytes = CanonicalCodec.requirePositiveSafeInteger(totalBytes, "total_bytes");
            chunkSizeBytes =
                    CanonicalCodec.requirePositiveInt32(chunkSizeBytes, "chunk_size_bytes");
            origin = Objects.requireNonNull(origin, "origin");
            requiredLevels = normalizeRequiredLevels(requiredLevels);
            validateOriginRequirements(container, totalBytes, origin, requiredLevels);
            chunkCount(totalBytes, chunkSizeBytes);
        }
    }

    /** Durable reservation acknowledgment returned for first start and idempotent replay. */
    public record SourceArtifactAccepted(
            int schemaVersion,
            String creationActionId,
            String artifactId,
            String startRequestDigest,
            Instant absoluteExpiresAt) {
        public SourceArtifactAccepted {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported source artifact schema_version");
            }
            creationActionId =
                    CanonicalCodec.requireCanonicalUuid(creationActionId, "creation_action_id");
            artifactId = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
            startRequestDigest =
                    CanonicalCodec.requireSha256(startRequestDigest, "start_request_digest");
            absoluteExpiresAt = Objects.requireNonNull(absoluteExpiresAt, "absolute_expires_at");
            CanonicalCodec.rfc3339Millis(absoluteExpiresAt, "absolute_expires_at");
        }
    }

    /** One range in the unique contiguous byte cover; this is not a wire record. */
    public record ChunkRange(int chunkId, long offset, int size) {
        public ChunkRange {
            chunkId = CanonicalCodec.requireNonnegativeInt32(chunkId, "chunk_id");
            offset = CanonicalCodec.requireNonnegativeSafeInteger(offset, "offset");
            size = CanonicalCodec.requirePositiveInt32(size, "size");
        }
    }

    /** Parse one exact original or derived source origin. */
    public static SourceArtifactOrigin parseOrigin(JsonElement value) {
        JsonObject object = requireObject(value, "source artifact origin");
        String type = requiredString(object, "type");
        return switch (type) {
            case "original-wsi" -> {
                var fields = new HashSet<>(ORIGINAL_FIELDS);
                if (object.has("members")) fields.add("members");
                requireFields(object, fields, "original WSI origin");
                var source = SourceIdentityCodec.parseImageSourceIdentity(object.get("source"));
                yield new OriginalWsi(
                        source,
                        CanonicalCodec.requireNonnegativeInt32(
                                object.get("pyramid_series_index"), "pyramid_series_index"),
                        SourceIdentityCodec.parseScaleTranslate2D(
                                object.get("level_zero_pixels_to_slide")),
                        requiredString(object, "entry_path"),
                        OriginalReaderSettingsCodec.parse(
                                object.get("reader_settings"), source.sourceAdapterId()),
                        object.has("members")
                                ? SlideFileSetCodec.parseMembers(object.get("members"))
                                : List.of());
            }
            case "derived-region" -> {
                requireFields(object, DERIVED_FIELDS, "derived region origin");
                yield new DerivedRegion(
                        SourceIdentityCodec.parseImageSourceIdentity(object.get("source")),
                        CanonicalCodec.requireNonnegativeInt32(
                                object.get("source_resolution_level"), "source_resolution_level"),
                        SourceIdentityCodec.parseImagePlaneIndex(object.get("plane")),
                        SourceIdentityCodec.parseIntRect(object.get("source_rect_at_level")),
                        SourceIdentityCodec.parseScaleTranslate2D(
                                object.get("artifact_pixels_to_slide")),
                        BuildIdentityCodec.parseSoftwareBuildIdentity(object.get("writer_build")));
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported source artifact origin type: " + type);
        };
    }

    /** Parse one exact decoded-level requirement. */
    public static SourceLevelRequirement parseLevelRequirement(JsonElement value) {
        JsonObject object = requireExactObject(value, LEVEL_FIELDS, "source level requirement");
        return new SourceLevelRequirement(
                CanonicalCodec.requireNonnegativeInt32(object.get("series_index"), "series_index"),
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("resolution_level"), "resolution_level"),
                CanonicalCodec.requirePositiveInt32(object.get("width"), "width"),
                CanonicalCodec.requirePositiveInt32(object.get("height"), "height"));
    }

    /** Parse and normalize one closed source-artifact start request. */
    public static SourceArtifactStartRequest parseStartRequest(JsonElement value) {
        JsonObject object = requireExactObject(value, START_FIELDS, "source artifact start");
        JsonElement requiredLevels = object.get("required_levels");
        if (requiredLevels == null || !requiredLevels.isJsonArray()) {
            throw new IllegalArgumentException("required_levels must be a nonempty array");
        }
        List<SourceLevelRequirement> levels = new ArrayList<>();
        for (JsonElement level : requiredLevels.getAsJsonArray()) {
            levels.add(parseLevelRequirement(level));
        }
        return new SourceArtifactStartRequest(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "creation_action_id"),
                requiredString(object, "artifact_id"),
                parseContainer(requiredString(object, "container")),
                CanonicalCodec.requirePositiveSafeInteger(object.get("total_bytes"), "total_bytes"),
                CanonicalCodec.requirePositiveInt32(
                        object.get("chunk_size_bytes"), "chunk_size_bytes"),
                parseOrigin(object.get("origin")),
                levels);
    }

    /** Parse one closed source-artifact reservation acknowledgment. */
    public static SourceArtifactAccepted parseAccepted(JsonElement value) {
        JsonObject object = requireExactObject(value, ACCEPTED_FIELDS, "source acceptance");
        return new SourceArtifactAccepted(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "creation_action_id"),
                requiredString(object, "artifact_id"),
                requiredString(object, "start_request_digest"),
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "absolute_expires_at"), "absolute_expires_at"));
    }

    /** Encode one source origin as exact snake-case JSON. */
    public static JsonObject toJson(SourceArtifactOrigin origin) {
        JsonObject value = new JsonObject();
        if (origin instanceof OriginalWsi original) {
            value.addProperty("type", "original-wsi");
            value.add("source", SourceIdentityCodec.toJson(original.source()));
            value.addProperty("pyramid_series_index", original.pyramidSeriesIndex());
            value.add(
                    "level_zero_pixels_to_slide",
                    SourceIdentityCodec.toJson(original.levelZeroPixelsToSlide()));
            value.addProperty("entry_path", original.entryPath());
            value.add(
                    "reader_settings",
                    OriginalReaderSettingsCodec.toJson(original.readerSettings()));
            if (!original.members().isEmpty())
                value.add("members", SlideFileSetCodec.toJson(original.members()));
        } else if (origin instanceof DerivedRegion derived) {
            value.addProperty("type", "derived-region");
            value.add("source", SourceIdentityCodec.toJson(derived.source()));
            value.addProperty("source_resolution_level", derived.sourceResolutionLevel());
            value.add("plane", SourceIdentityCodec.toJson(derived.plane()));
            value.add(
                    "source_rect_at_level",
                    SourceIdentityCodec.toJson(derived.sourceRectAtLevel()));
            value.add(
                    "artifact_pixels_to_slide",
                    SourceIdentityCodec.toJson(derived.artifactPixelsToSlide()));
            value.add("writer_build", BuildIdentityCodec.toJson(derived.writerBuild()));
        } else {
            throw new IllegalArgumentException("unsupported source artifact origin");
        }
        return value;
    }

    /** Encode one decoded-level requirement as exact snake-case JSON. */
    public static JsonObject toJson(SourceLevelRequirement level) {
        JsonObject value = new JsonObject();
        value.addProperty("series_index", level.seriesIndex());
        value.addProperty("resolution_level", level.resolutionLevel());
        value.addProperty("width", level.width());
        value.addProperty("height", level.height());
        return value;
    }

    /** Encode a normalized start request as exact snake-case JSON. */
    public static JsonObject toJson(SourceArtifactStartRequest request) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", request.schemaVersion());
        value.addProperty("creation_action_id", request.creationActionId());
        value.addProperty("artifact_id", request.artifactId());
        value.addProperty("container", request.container().name());
        value.addProperty("total_bytes", request.totalBytes());
        value.addProperty("chunk_size_bytes", request.chunkSizeBytes());
        value.add("origin", toJson(request.origin()));
        JsonArray levels = new JsonArray();
        request.requiredLevels().forEach(level -> levels.add(toJson(level)));
        value.add("required_levels", levels);
        return value;
    }

    /** Encode a reservation acknowledgment as exact snake-case JSON. */
    public static JsonObject toJson(SourceArtifactAccepted accepted) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", accepted.schemaVersion());
        value.addProperty("creation_action_id", accepted.creationActionId());
        value.addProperty("artifact_id", accepted.artifactId());
        value.addProperty("start_request_digest", accepted.startRequestDigest());
        value.addProperty(
                "absolute_expires_at",
                CanonicalCodec.rfc3339Millis(accepted.absoluteExpiresAt(), "absolute_expires_at"));
        return value;
    }

    /** Return the JCS digest that fences idempotent start under one artifact ID. */
    public static String startRequestDigest(SourceArtifactStartRequest request) {
        return CanonicalCodec.canonicalDigest(toJson(request));
    }

    /** Require an acknowledgment to bind the exact start request. */
    public static SourceArtifactAccepted validateAcceptance(
            SourceArtifactStartRequest request, SourceArtifactAccepted accepted) {
        if (!accepted.creationActionId().equals(request.creationActionId())) {
            throw new IllegalArgumentException(
                    "acceptance creation_action_id does not match request");
        }
        if (!accepted.artifactId().equals(request.artifactId())) {
            throw new IllegalArgumentException("acceptance artifact_id does not match request");
        }
        if (!accepted.startRequestDigest().equals(startRequestDigest(request))) {
            throw new IllegalArgumentException(
                    "acceptance start_request_digest does not match request");
        }
        return accepted;
    }

    /** Return the number of ranges in the unique contiguous byte cover. */
    public static int chunkCount(long totalBytes, int chunkSizeBytes) {
        long total = CanonicalCodec.requirePositiveSafeInteger(totalBytes, "total_bytes");
        int chunkSize = CanonicalCodec.requirePositiveInt32(chunkSizeBytes, "chunk_size_bytes");
        long count = (total + chunkSize - 1L) / chunkSize;
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "canonical chunk count exceeds SourceByteChunkId range");
        }
        return (int) count;
    }

    /** Return one canonical range without materializing the complete plan. */
    public static ChunkRange chunkRange(long totalBytes, int chunkSizeBytes, int chunkId) {
        long total = CanonicalCodec.requirePositiveSafeInteger(totalBytes, "total_bytes");
        int chunkSize = CanonicalCodec.requirePositiveInt32(chunkSizeBytes, "chunk_size_bytes");
        int count = chunkCount(total, chunkSize);
        int index = CanonicalCodec.requireNonnegativeInt32(chunkId, "chunk_id");
        if (index >= count) {
            throw new IllegalArgumentException("chunk_id lies outside canonical chunk plan");
        }
        long offset = (long) index * chunkSize;
        int size = (int) Math.min(chunkSize, total - offset);
        return new ChunkRange(index, offset, size);
    }

    /** Parse one closed encoded-image family. */
    public static SourceContainer parseContainer(String value) {
        try {
            return SourceContainer.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unsupported source container: " + value, error);
        }
    }

    private static List<SourceLevelRequirement> normalizeRequiredLevels(
            List<SourceLevelRequirement> levels) {
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException("required_levels must be a nonempty array");
        }
        List<SourceLevelRequirement> result = new ArrayList<>(levels.size());
        for (SourceLevelRequirement level : levels) {
            result.add(Objects.requireNonNull(level, "required_levels item"));
        }
        result.sort(
                Comparator.comparingInt(SourceLevelRequirement::seriesIndex)
                        .thenComparingInt(SourceLevelRequirement::resolutionLevel));
        Set<LevelKey> keys = new HashSet<>();
        for (SourceLevelRequirement level : result) {
            if (!keys.add(new LevelKey(level.seriesIndex(), level.resolutionLevel()))) {
                throw new IllegalArgumentException(
                        "required_levels contains a duplicate series/level key");
            }
        }
        return List.copyOf(result);
    }

    private static void validateOriginRequirements(
            SourceContainer container,
            long totalBytes,
            SourceArtifactOrigin origin,
            List<SourceLevelRequirement> levels) {
        if (origin instanceof OriginalWsi original) {
            if (container != SourceContainer.SLIDE_FILE
                    && container != SourceContainer.SLIDE_FILE_SET) {
                throw new IllegalArgumentException(
                        "original WSI must use SLIDE_FILE or SLIDE_FILE_SET");
            }
            if (levels.stream()
                    .anyMatch(level -> level.seriesIndex() != original.pyramidSeriesIndex())) {
                throw new IllegalArgumentException(
                        "original WSI requirements must use pyramid_series_index");
            }
            SourceIdentityCodec.SourceContentVersion version =
                    original.source().contentVersion().orElseThrow();
            if (container == SourceContainer.SLIDE_FILE_SET) {
                if (!(version instanceof SourceIdentityCodec.LocalFileSet files)
                        || original.members().isEmpty())
                    throw new IllegalArgumentException(
                            "SLIDE_FILE_SET requires member bytes and versions");
                var sizes =
                        original.members().stream()
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                SlideFileSetCodec.Member::path,
                                                SlideFileSetCodec.Member::sizeBytes));
                var versions =
                        files.members().stream()
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                SourceIdentityCodec.LocalFileMember::path,
                                                SourceIdentityCodec.LocalFileMember::sizeBytes));
                if (!sizes.equals(versions) || sizes.getOrDefault(original.entryPath(), 0L) <= 0)
                    throw new IllegalArgumentException(
                            "fileset entry/membership/sizes differ from source version");
            } else if (!(version instanceof SourceIdentityCodec.LocalFile)
                    || !original.members().isEmpty()
                    || original.entryPath().contains("/")) {
                throw new IllegalArgumentException(
                        "SLIDE_FILE requires one unchanged local filename/version");
            }
            if (version instanceof SourceIdentityCodec.LocalFile local
                    && local.sizeBytes() != totalBytes) {
                throw new IllegalArgumentException("local-file size_bytes must equal total_bytes");
            }
            boolean hasLevelZero =
                    levels.stream()
                            .anyMatch(
                                    level ->
                                            level.resolutionLevel() == 0
                                                    && level.width() == original.source().width()
                                                    && level.height()
                                                            == original.source().height());
            if (!hasLevelZero) {
                throw new IllegalArgumentException(
                        "original WSI requirements must contain exact level zero");
            }
            if (levels.stream()
                    .anyMatch(
                            level ->
                                    level.resolutionLevel()
                                            >= original.readerSettings().levels().size()))
                throw new IllegalArgumentException(
                        "required source level has no captured reader mapping");
            return;
        }

        if (container != SourceContainer.PNG && container != SourceContainer.TIFF_FAMILY)
            throw new IllegalArgumentException("derived regions must use PNG or TIFF_FAMILY");
        DerivedRegion derived = (DerivedRegion) origin;
        if (levels.size() != 1) {
            throw new IllegalArgumentException(
                    "derived region must have exactly one required level");
        }
        SourceLevelRequirement level = levels.getFirst();
        if (level.seriesIndex() != 0
                || level.resolutionLevel() != 0
                || level.width() != derived.sourceRectAtLevel().width()
                || level.height() != derived.sourceRectAtLevel().height()) {
            throw new IllegalArgumentException(
                    "derived region requires exact series-zero level-zero shape");
        }
    }

    private record LevelKey(int seriesIndex, int resolutionLevel) {}
}
