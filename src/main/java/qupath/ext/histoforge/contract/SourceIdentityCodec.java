package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Credential-free image-source identity and shared spatial primitives. */
public final class SourceIdentityCodec {

    private static final Set<String> LOCAL_FILE_FIELDS =
            Set.of("type", "size_bytes", "last_modified_epoch_millis");
    private static final Set<String> LOCAL_FILE_FIELDS_WITH_KEY =
            Set.of("type", "size_bytes", "last_modified_epoch_millis", "file_key");
    private static final Set<String> OPAQUE_VERSION_FIELDS = Set.of("type", "value");
    private static final Set<String> S3_ETAG_FIELDS = Set.of("type", "quoted_value");
    private static final Set<String> CALIBRATION_FIELDS = Set.of("mpp_x", "mpp_y");
    private static final Set<String> SOURCE_FIELDS =
            Set.of(
                    "source_adapter_id",
                    "credential_free_descriptor_digest",
                    "width",
                    "height",
                    "z_slices",
                    "timepoints",
                    "calibration");
    private static final Set<String> SOURCE_FIELDS_WITH_VERSION =
            Set.of(
                    "source_adapter_id",
                    "credential_free_descriptor_digest",
                    "content_version",
                    "width",
                    "height",
                    "z_slices",
                    "timepoints",
                    "calibration");
    private static final Set<String> TRANSFORM_FIELDS =
            Set.of("scale_x", "scale_y", "translate_x", "translate_y");
    private static final Set<String> PLANE_FIELDS = Set.of("z", "t");
    private static final Set<String> RECT_FIELDS = Set.of("x", "y", "width", "height");

    private SourceIdentityCodec() {}

    /** Stable source-content version evidence checked around source reads. */
    public sealed interface SourceContentVersion
            permits LocalFile, LocalFileSet, S3VersionId, S3ETag {}

    /** Local-file length, modification time, and optional provider file key. */
    public record LocalFile(long sizeBytes, long lastModifiedEpochMillis, Optional<String> fileKey)
            implements SourceContentVersion {
        public LocalFile {
            sizeBytes = CanonicalCodec.requirePositiveSafeInteger(sizeBytes, "size_bytes");
            lastModifiedEpochMillis =
                    CanonicalCodec.requireSafeInteger(
                            lastModifiedEpochMillis, "last_modified_epoch_millis");
            fileKey = Objects.requireNonNull(fileKey, "file_key");
            fileKey = fileKey.map(value -> CanonicalCodec.requireNonblank(value, "file_key"));
        }
    }

    /** One member's cheap filesystem version; zero-byte metadata members are permitted. */
    public record LocalFileMember(
            String path, long sizeBytes, long lastModifiedEpochMillis, Optional<String> fileKey) {
        public LocalFileMember {
            path = SlideFileSetCodec.relativePath(path);
            sizeBytes = CanonicalCodec.requireNonnegativeSafeInteger(sizeBytes, "size_bytes");
            lastModifiedEpochMillis =
                    CanonicalCodec.requireSafeInteger(
                            lastModifiedEpochMillis, "last_modified_epoch_millis");
            fileKey =
                    Objects.requireNonNull(fileKey, "file_key")
                            .map(value -> CanonicalCodec.requireNonblank(value, "file_key"));
        }
    }

    /** Complete source membership, not merely the entry file's version. */
    public record LocalFileSet(List<LocalFileMember> members) implements SourceContentVersion {
        public LocalFileSet {
            members = List.copyOf(members);
            SlideFileSetCodec.requireMemberPaths(
                    members.stream().map(LocalFileMember::path).toList());
            members =
                    members.stream()
                            .sorted(
                                    Comparator.comparing(
                                            LocalFileMember::path, SlideFileSetCodec.PATH_ORDER))
                            .toList();
        }
    }

    /** Strong S3 object-version identity. */
    public record S3VersionId(String value) implements SourceContentVersion {
        public S3VersionId {
            value = CanonicalCodec.requireNonblank(value, "value");
        }
    }

    /** Conditional quoted S3 ETag identity. */
    public record S3ETag(String quotedValue) implements SourceContentVersion {
        public S3ETag {
            quotedValue = CanonicalCodec.requireNonblank(quotedValue, "quoted_value");
            if (quotedValue.length() < 3
                    || quotedValue.charAt(0) != '"'
                    || quotedValue.charAt(quotedValue.length() - 1) != '"') {
                throw new IllegalArgumentException("quoted_value must be a quoted S3 ETag");
            }
        }
    }

    /** Independent physical pixel calibration along both source axes. */
    public record PixelCalibration(double mppX, double mppY) {
        public PixelCalibration {
            mppX = CanonicalCodec.requirePositiveFiniteDouble(mppX, "mpp_x");
            mppY = CanonicalCodec.requirePositiveFiniteDouble(mppY, "mpp_y");
        }
    }

    /** Credential-free identity, shape, optional stable version, and calibration of a source. */
    public record ImageSourceIdentity(
            String sourceAdapterId,
            String credentialFreeDescriptorDigest,
            Optional<SourceContentVersion> contentVersion,
            int width,
            int height,
            int zSlices,
            int timepoints,
            PixelCalibration calibration) {
        public ImageSourceIdentity {
            sourceAdapterId = CanonicalCodec.requireNonblank(sourceAdapterId, "source_adapter_id");
            credentialFreeDescriptorDigest =
                    CanonicalCodec.requireSha256(
                            credentialFreeDescriptorDigest, "credential_free_descriptor_digest");
            contentVersion = Objects.requireNonNull(contentVersion, "content_version");
            width = CanonicalCodec.requirePositiveInt32(width, "width");
            height = CanonicalCodec.requirePositiveInt32(height, "height");
            zSlices = CanonicalCodec.requirePositiveInt32(zSlices, "z_slices");
            timepoints = CanonicalCodec.requirePositiveInt32(timepoints, "timepoints");
            calibration = Objects.requireNonNull(calibration, "calibration");
        }
    }

    /** Axis-aligned mapping between source and destination pixel-edge coordinates. */
    public record ScaleTranslate2D(
            double scaleX, double scaleY, double translateX, double translateY) {
        public ScaleTranslate2D {
            scaleX = CanonicalCodec.requirePositiveFiniteDouble(scaleX, "scale_x");
            scaleY = CanonicalCodec.requirePositiveFiniteDouble(scaleY, "scale_y");
            translateX = CanonicalCodec.requireFiniteDouble(translateX, "translate_x");
            translateY = CanonicalCodec.requireFiniteDouble(translateY, "translate_y");
        }
    }

    /** Explicit nonnegative z/t plane. */
    public record ImagePlaneIndex(int z, int t) {
        public ImagePlaneIndex {
            z = CanonicalCodec.requireNonnegativeInt32(z, "z");
            t = CanonicalCodec.requireNonnegativeInt32(t, "t");
        }
    }

    /** Nonnegative half-open pixel-edge rectangle with positive extents. */
    public record IntRect(int x, int y, int width, int height) {
        public IntRect {
            x = CanonicalCodec.requireNonnegativeInt32(x, "x");
            y = CanonicalCodec.requireNonnegativeInt32(y, "y");
            width = CanonicalCodec.requirePositiveInt32(width, "width");
            height = CanonicalCodec.requirePositiveInt32(height, "height");
        }
    }

    /** Parse one exact stable source-content version. */
    public static SourceContentVersion parseSourceContentVersion(JsonElement value) {
        JsonObject object = requiredObject(value, "source content version");
        String type = requiredString(object, "type");
        return switch (type) {
            case "local-file-set" -> {
                requireFields(object, Set.of("type", "members"), "local-file-set source version");
                if (!object.get("members").isJsonArray())
                    throw new IllegalArgumentException("members must be an array");
                var members = new ArrayList<LocalFileMember>();
                for (var item : object.getAsJsonArray("members")) {
                    var member = requiredObject(item, "local member version");
                    requireFields(
                            member,
                            member.has("file_key")
                                    ? Set.of(
                                            "path",
                                            "size_bytes",
                                            "last_modified_epoch_millis",
                                            "file_key")
                                    : Set.of("path", "size_bytes", "last_modified_epoch_millis"),
                            "local member version");
                    members.add(
                            new LocalFileMember(
                                    requiredString(member, "path"),
                                    CanonicalCodec.requireNonnegativeSafeInteger(
                                            member.get("size_bytes"), "size_bytes"),
                                    CanonicalCodec.requireSafeInteger(
                                            member.get("last_modified_epoch_millis"),
                                            "last_modified_epoch_millis"),
                                    member.has("file_key")
                                            ? Optional.of(requiredString(member, "file_key"))
                                            : Optional.empty()));
                }
                yield new LocalFileSet(members);
            }
            case "local-file" -> {
                requireFields(
                        object,
                        object.has("file_key") ? LOCAL_FILE_FIELDS_WITH_KEY : LOCAL_FILE_FIELDS,
                        "local-file source content version");
                yield new LocalFile(
                        CanonicalCodec.requirePositiveSafeInteger(
                                object.get("size_bytes"), "size_bytes"),
                        CanonicalCodec.requireSafeInteger(
                                object.get("last_modified_epoch_millis"),
                                "last_modified_epoch_millis"),
                        object.has("file_key")
                                ? Optional.of(requiredString(object, "file_key"))
                                : Optional.empty());
            }
            case "s3-version-id" -> {
                requireFields(object, OPAQUE_VERSION_FIELDS, "S3 version ID");
                yield new S3VersionId(requiredString(object, "value"));
            }
            case "s3-etag" -> {
                requireFields(object, S3_ETAG_FIELDS, "S3 ETag");
                yield new S3ETag(requiredString(object, "quoted_value"));
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported source content version type: " + type);
        };
    }

    /** Parse one exact source-pixel calibration. */
    public static PixelCalibration parsePixelCalibration(JsonElement value) {
        JsonObject object = requireExactObject(value, CALIBRATION_FIELDS, "pixel calibration");
        return new PixelCalibration(
                CanonicalCodec.requirePositiveFiniteDouble(object.get("mpp_x"), "mpp_x"),
                CanonicalCodec.requirePositiveFiniteDouble(object.get("mpp_y"), "mpp_y"));
    }

    /** Parse one exact credential-free image-source identity. */
    public static ImageSourceIdentity parseImageSourceIdentity(JsonElement value) {
        JsonObject object = requiredObject(value, "image source identity");
        requireFields(
                object,
                object.has("content_version") ? SOURCE_FIELDS_WITH_VERSION : SOURCE_FIELDS,
                "image source identity");
        return new ImageSourceIdentity(
                requiredString(object, "source_adapter_id"),
                requiredString(object, "credential_free_descriptor_digest"),
                object.has("content_version")
                        ? Optional.of(parseSourceContentVersion(object.get("content_version")))
                        : Optional.empty(),
                CanonicalCodec.requirePositiveInt32(object.get("width"), "width"),
                CanonicalCodec.requirePositiveInt32(object.get("height"), "height"),
                CanonicalCodec.requirePositiveInt32(object.get("z_slices"), "z_slices"),
                CanonicalCodec.requirePositiveInt32(object.get("timepoints"), "timepoints"),
                parsePixelCalibration(object.get("calibration")));
    }

    /** Return the canonical digest bound into an action's semantic image revision. */
    public static String imageSourceIdentityDigest(ImageSourceIdentity source) {
        return CanonicalCodec.canonicalDigest(toJson(Objects.requireNonNull(source, "source")));
    }

    /** Parse one exact positive-scale affine mapping. */
    public static ScaleTranslate2D parseScaleTranslate2D(JsonElement value) {
        JsonObject object = requireExactObject(value, TRANSFORM_FIELDS, "scale transform");
        return new ScaleTranslate2D(
                CanonicalCodec.requirePositiveFiniteDouble(object.get("scale_x"), "scale_x"),
                CanonicalCodec.requirePositiveFiniteDouble(object.get("scale_y"), "scale_y"),
                CanonicalCodec.requireFiniteDouble(object.get("translate_x"), "translate_x"),
                CanonicalCodec.requireFiniteDouble(object.get("translate_y"), "translate_y"));
    }

    /** Parse one exact source plane index. */
    public static ImagePlaneIndex parseImagePlaneIndex(JsonElement value) {
        JsonObject object = requireExactObject(value, PLANE_FIELDS, "image plane index");
        return new ImagePlaneIndex(
                CanonicalCodec.requireNonnegativeInt32(object.get("z"), "z"),
                CanonicalCodec.requireNonnegativeInt32(object.get("t"), "t"));
    }

    /** Parse one exact half-open integer rectangle. */
    public static IntRect parseIntRect(JsonElement value) {
        JsonObject object = requireExactObject(value, RECT_FIELDS, "integer rectangle");
        return new IntRect(
                CanonicalCodec.requireNonnegativeInt32(object.get("x"), "x"),
                CanonicalCodec.requireNonnegativeInt32(object.get("y"), "y"),
                CanonicalCodec.requirePositiveInt32(object.get("width"), "width"),
                CanonicalCodec.requirePositiveInt32(object.get("height"), "height"));
    }

    /** Encode one stable source-content version as exact snake-case JSON. */
    public static JsonObject toJson(SourceContentVersion version) {
        JsonObject value = new JsonObject();
        if (version instanceof LocalFile local) {
            value.addProperty("type", "local-file");
            value.addProperty("size_bytes", local.sizeBytes());
            value.addProperty("last_modified_epoch_millis", local.lastModifiedEpochMillis());
            local.fileKey().ifPresent(fileKey -> value.addProperty("file_key", fileKey));
        } else if (version instanceof LocalFileSet files) {
            value.addProperty("type", "local-file-set");
            var members = new JsonArray();
            for (var member : files.members()) {
                var item = new JsonObject();
                item.addProperty("path", member.path());
                item.addProperty("size_bytes", member.sizeBytes());
                item.addProperty("last_modified_epoch_millis", member.lastModifiedEpochMillis());
                member.fileKey().ifPresent(key -> item.addProperty("file_key", key));
                members.add(item);
            }
            value.add("members", members);
        } else if (version instanceof S3VersionId s3Version) {
            value.addProperty("type", "s3-version-id");
            value.addProperty("value", s3Version.value());
        } else if (version instanceof S3ETag etag) {
            value.addProperty("type", "s3-etag");
            value.addProperty("quoted_value", etag.quotedValue());
        } else {
            throw new IllegalArgumentException("unsupported source content version");
        }
        return value;
    }

    /** Encode source-pixel calibration as exact snake-case JSON. */
    public static JsonObject toJson(PixelCalibration calibration) {
        JsonObject value = new JsonObject();
        value.addProperty("mpp_x", calibration.mppX());
        value.addProperty("mpp_y", calibration.mppY());
        return value;
    }

    /** Encode a credential-free image-source identity as exact snake-case JSON. */
    public static JsonObject toJson(ImageSourceIdentity source) {
        JsonObject value = new JsonObject();
        value.addProperty("source_adapter_id", source.sourceAdapterId());
        value.addProperty(
                "credential_free_descriptor_digest", source.credentialFreeDescriptorDigest());
        source.contentVersion().ifPresent(version -> value.add("content_version", toJson(version)));
        value.addProperty("width", source.width());
        value.addProperty("height", source.height());
        value.addProperty("z_slices", source.zSlices());
        value.addProperty("timepoints", source.timepoints());
        value.add("calibration", toJson(source.calibration()));
        return value;
    }

    /** Encode an axis-aligned mapping as exact snake-case JSON. */
    public static JsonObject toJson(ScaleTranslate2D transform) {
        JsonObject value = new JsonObject();
        value.addProperty("scale_x", transform.scaleX());
        value.addProperty("scale_y", transform.scaleY());
        value.addProperty("translate_x", transform.translateX());
        value.addProperty("translate_y", transform.translateY());
        return value;
    }

    /** Encode a source plane as exact snake-case JSON. */
    public static JsonObject toJson(ImagePlaneIndex plane) {
        JsonObject value = new JsonObject();
        value.addProperty("z", plane.z());
        value.addProperty("t", plane.t());
        return value;
    }

    /** Encode a half-open integer rectangle as exact snake-case JSON. */
    public static JsonObject toJson(IntRect rectangle) {
        JsonObject value = new JsonObject();
        value.addProperty("x", rectangle.x());
        value.addProperty("y", rectangle.y());
        value.addProperty("width", rectangle.width());
        value.addProperty("height", rectangle.height());
        return value;
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        requireFields(object, fields, path);
        return object;
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
