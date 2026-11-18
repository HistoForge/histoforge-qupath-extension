package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingDatasetLimits;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;

/** Immutable training-dataset manifest identity and package graph. */
public final class TrainingDatasetCodec {

    public static final int SCHEMA_VERSION = 2;

    private static final Set<String> MANIFEST_FIELDS =
            Set.of(
                    "schema_version",
                    "dataset_digest",
                    "task",
                    "artifacts",
                    "project_groups",
                    "images",
                    "rois",
                    "files");
    private static final Set<String> PREIMAGE_FIELDS =
            Set.of(
                    "schema_version",
                    "task",
                    "artifacts",
                    "project_groups",
                    "images",
                    "rois",
                    "files");
    private static final Set<String> IMAGE_FIELDS = Set.of("project_entry_id_digest");
    private static final Set<String> IMAGE_WITH_GROUP_FIELDS =
            Set.of("project_entry_id_digest", "project_group_index");
    private static final Set<String> ROI_FIELDS =
            Set.of("source_roi_uuid", "image_index", "source_envelope");
    private static final Set<String> ARTIFACT_FIELDS = Set.of("image_index", "representation");
    private static final Set<String> ENVELOPE_FIELDS =
            Set.of("artifact_index", "series_index", "resolution_level", "pixel_rect");
    private static final Set<String> FILE_FIELDS = Set.of("path", "size_bytes", "sha256");
    private static final Set<String> REPRESENTATIONS = Set.of("png", "ome_bigtiff", "original");

    private TrainingDatasetCodec() {}

    /** One QuPath project image included in the dataset. */
    public record TrainingImage(String projectEntryIdDigest, OptionalInt projectGroupIndex) {
        public TrainingImage {
            projectGroupIndex = Objects.requireNonNull(projectGroupIndex, "project_group_index");
        }
    }

    /** Exact stored pixels available to one training ROI. */
    public record TrainingSourceEnvelope(
            int artifactIndex,
            int seriesIndex,
            int resolutionLevel,
            SourceIdentityCodec.IntRect pixelRect) {}

    /** One persistent HistoForge ROI and its image/source references. */
    public record TrainingRoi(
            String sourceRoiUuid, int imageIndex, TrainingSourceEnvelope sourceEnvelope) {}

    /** One retained source image representation. */
    public record TrainingArtifact(int imageIndex, String representation) {}

    /** One required non-manifest package member. */
    public record TrainingPackageFile(String path, long sizeBytes, String sha256) {}

    /** Digest-free manifest content used to derive dataset identity. */
    public record TrainingDatasetPreimage(
            int schemaVersion,
            TrainingTask task,
            List<TrainingArtifact> artifacts,
            List<String> projectGroups,
            List<TrainingImage> images,
            List<TrainingRoi> rois,
            List<TrainingPackageFile> files) {
        public TrainingDatasetPreimage {
            artifacts = List.copyOf(artifacts);
            projectGroups = List.copyOf(projectGroups);
            images = List.copyOf(images);
            rois = List.copyOf(rois);
            files = List.copyOf(files);
        }
    }

    /** Complete self-digested immutable dataset manifest. */
    public record TrainingDatasetManifest(
            int schemaVersion,
            String datasetDigest,
            TrainingTask task,
            List<TrainingArtifact> artifacts,
            List<String> projectGroups,
            List<TrainingImage> images,
            List<TrainingRoi> rois,
            List<TrainingPackageFile> files) {
        public TrainingDatasetManifest {
            artifacts = List.copyOf(artifacts);
            projectGroups = List.copyOf(projectGroups);
            images = List.copyOf(images);
            rois = List.copyOf(rois);
            files = List.copyOf(files);
        }

        public TrainingDatasetPreimage preimage() {
            return new TrainingDatasetPreimage(
                    schemaVersion, task, artifacts, projectGroups, images, rois, files);
        }
    }

    /** Validate a digest-free manifest and derive its scientific identity. */
    public static TrainingDatasetManifest build(
            TrainingDatasetPreimage value, TrainingApiLimits limits) {
        Objects.requireNonNull(value, "training dataset preimage");
        JsonObject normalized = normalizePreimage(encodePreimage(value), limits);
        normalized.addProperty("dataset_digest", CanonicalCodec.canonicalDigest(normalized));
        return parseManifest(normalized, limits);
    }

    /** Parse one complete manifest and verify its digest and package graph. */
    public static TrainingDatasetManifest parseManifest(
            JsonElement value, TrainingApiLimits limits) {
        JsonObject object =
                TrainingJson.exactObject(value, MANIFEST_FIELDS, "training dataset manifest");
        String supplied =
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "dataset_digest"), "dataset_digest");
        JsonObject rawPreimage = new JsonObject();
        PREIMAGE_FIELDS.forEach(field -> rawPreimage.add(field, object.get(field)));
        JsonObject normalized = normalizePreimage(rawPreimage, limits);
        if (!supplied.equals(CanonicalCodec.canonicalDigest(normalized))) {
            throw new IllegalArgumentException(
                    "dataset_digest does not match the manifest preimage");
        }
        TrainingDatasetManifest result = decodeNormalized(normalized, supplied);
        requireExtractedSize(result, limits);
        return result;
    }

    /** Encode and revalidate one complete manifest. */
    public static JsonObject toJson(TrainingDatasetManifest value, TrainingApiLimits limits) {
        TrainingDatasetManifest checked = parseManifest(encodeManifest(value), limits);
        return encodeManifest(checked);
    }

    /** Return fixed package member paths in canonical order. */
    public static List<String> requiredPackagePaths(
            TrainingDatasetManifest value, TrainingApiLimits limits) {
        return parseManifest(encodeManifest(value), limits).files().stream()
                .map(TrainingPackageFile::path)
                .toList();
    }

    public static String artifactOriginPath(int artifactIndex) {
        return "artifacts/" + nonnegativeIndex(artifactIndex, "artifact_index") + "/origin.json";
    }

    public static String artifactSourcePath(int artifactIndex, String representation) {
        int index = nonnegativeIndex(artifactIndex, "artifact_index");
        String basename =
                switch (representation) {
                    case "png" -> "source.png";
                    case "ome_bigtiff" -> "source.ome.tif";
                    case "original" -> "source.tif";
                    default ->
                            throw new IllegalArgumentException(
                                    "unsupported training artifact representation");
                };
        return "artifacts/" + index + "/" + basename;
    }

    public static String roiGeometryPath(int roiIndex) {
        return "rois/" + nonnegativeIndex(roiIndex, "roi_index") + "/roi.geojson";
    }

    public static String roiObjectsPath(int roiIndex) {
        return "rois/" + nonnegativeIndex(roiIndex, "roi_index") + "/objects.geojson";
    }

    /** JCS string-byte order required by the project_groups array. */
    public static Comparator<String> canonicalProjectGroupOrder() {
        return TrainingDatasetCodec::compareCanonicalStrings;
    }

    private static JsonObject normalizePreimage(JsonElement value, TrainingApiLimits limits) {
        Objects.requireNonNull(limits, "training API limits");
        JsonObject object =
                TrainingJson.exactObject(value, PREIMAGE_FIELDS, "training dataset preimage");
        int schemaVersion =
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version");
        if (schemaVersion != 1 && schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported training dataset schema_version");
        }
        List<String> groups = parseProjectGroups(object.get("project_groups"), limits);
        List<TrainingImage> images = parseImages(object.get("images"), groups.size());
        List<TrainingRoi> rois = parseRois(object.get("rois"), images.size());
        List<TrainingArtifact> artifacts = parseArtifacts(object.get("artifacts"), images.size());
        if (schemaVersion == 1
                && artifacts.stream()
                        .anyMatch(artifact -> "original".equals(artifact.representation()))) {
            throw new IllegalArgumentException(
                    "schema-1 original training artifacts must be recaptured with reader settings");
        }
        List<TrainingPackageFile> files = parseFiles(object.get("files"), limits.dataset());
        requireCount("rois", rois.size(), limits.dataset().maxRois());
        requireGraph(groups, images, rois, artifacts, files);
        return encodePreimage(
                new TrainingDatasetPreimage(
                        schemaVersion,
                        TrainingTaskCodec.parseTask(object.get("task")),
                        artifacts,
                        groups,
                        images,
                        rois,
                        files));
    }

    private static List<String> parseProjectGroups(JsonElement value, TrainingApiLimits limits) {
        List<String> groups = new ArrayList<>();
        for (JsonElement item : TrainingJson.array(value, "project_groups")) {
            groups.add(
                    TrainingJson.text(item, "project_groups item", limits.report().maxTextBytes()));
        }
        if (groups.size() != new HashSet<>(groups).size()) {
            throw new IllegalArgumentException("project_groups contains duplicates");
        }
        List<String> sorted = new ArrayList<>(groups);
        sorted.sort(TrainingDatasetCodec::compareCanonicalStrings);
        if (!groups.equals(sorted)) {
            throw new IllegalArgumentException(
                    "project_groups is not in canonical JCS-string order");
        }
        return List.copyOf(groups);
    }

    private static List<TrainingImage> parseImages(JsonElement value, int groupCount) {
        List<TrainingImage> result = new ArrayList<>();
        for (JsonElement itemValue : TrainingJson.nonemptyArray(value, "images")) {
            JsonObject object = TrainingJson.object(itemValue, "training image");
            boolean hasGroup = object.has("project_group_index");
            TrainingJson.requireFields(
                    object, hasGroup ? IMAGE_WITH_GROUP_FIELDS : IMAGE_FIELDS, "training image");
            OptionalInt group = OptionalInt.empty();
            if (hasGroup) {
                int index =
                        CanonicalCodec.requireNonnegativeInt32(
                                object.get("project_group_index"), "project_group_index");
                if (index >= groupCount) {
                    throw new IllegalArgumentException(
                            "project_group_index is outside project_groups");
                }
                group = OptionalInt.of(index);
            }
            result.add(
                    new TrainingImage(
                            CanonicalCodec.requireSha256(
                                    TrainingJson.string(object, "project_entry_id_digest"),
                                    "project_entry_id_digest"),
                            group));
        }
        List<String> digests = result.stream().map(TrainingImage::projectEntryIdDigest).toList();
        List<String> sorted = digests.stream().sorted().toList();
        if (!digests.equals(sorted) || digests.size() != new HashSet<>(digests).size()) {
            throw new IllegalArgumentException(
                    "images must be unique and sorted by project entry digest");
        }
        return List.copyOf(result);
    }

    private static List<TrainingRoi> parseRois(JsonElement value, int imageCount) {
        List<TrainingRoi> result = new ArrayList<>();
        for (JsonElement itemValue : TrainingJson.nonemptyArray(value, "rois")) {
            JsonObject object = TrainingJson.exactObject(itemValue, ROI_FIELDS, "training ROI");
            int imageIndex =
                    CanonicalCodec.requireNonnegativeInt32(
                            object.get("image_index"), "image_index");
            if (imageIndex >= imageCount) {
                throw new IllegalArgumentException("ROI image_index is outside images");
            }
            String uuid = TrainingJson.string(object, "source_roi_uuid");
            CanonicalCodec.requireCanonicalUuid(uuid, "source_roi_uuid");
            result.add(
                    new TrainingRoi(
                            uuid, imageIndex, parseSourceEnvelope(object.get("source_envelope"))));
        }
        for (int index = 1; index < result.size(); index++) {
            TrainingRoi previous = result.get(index - 1);
            TrainingRoi current = result.get(index);
            int comparison = Integer.compare(previous.imageIndex(), current.imageIndex());
            if (comparison == 0) {
                comparison = previous.sourceRoiUuid().compareTo(current.sourceRoiUuid());
            }
            if (comparison >= 0) {
                throw new IllegalArgumentException(
                        "rois must be unique and sorted by image and UUID");
            }
        }
        return List.copyOf(result);
    }

    private static List<TrainingArtifact> parseArtifacts(JsonElement value, int imageCount) {
        List<TrainingArtifact> result = new ArrayList<>();
        for (JsonElement itemValue : TrainingJson.nonemptyArray(value, "artifacts")) {
            JsonObject object =
                    TrainingJson.exactObject(itemValue, ARTIFACT_FIELDS, "training artifact");
            int imageIndex =
                    CanonicalCodec.requireNonnegativeInt32(
                            object.get("image_index"), "image_index");
            if (imageIndex >= imageCount) {
                throw new IllegalArgumentException("artifact image_index is outside images");
            }
            result.add(
                    new TrainingArtifact(
                            imageIndex,
                            TrainingJson.enumValue(
                                    object.get("representation"),
                                    REPRESENTATIONS,
                                    "representation")));
        }
        for (int index = 1; index < result.size(); index++) {
            if (result.get(index - 1).imageIndex() > result.get(index).imageIndex()) {
                throw new IllegalArgumentException("artifacts must be grouped by image_index");
            }
        }
        return List.copyOf(result);
    }

    private static TrainingSourceEnvelope parseSourceEnvelope(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, ENVELOPE_FIELDS, "training source envelope");
        return new TrainingSourceEnvelope(
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("artifact_index"), "artifact_index"),
                CanonicalCodec.requireNonnegativeInt32(object.get("series_index"), "series_index"),
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("resolution_level"), "resolution_level"),
                SourceIdentityCodec.parseIntRect(object.get("pixel_rect")));
    }

    private static List<TrainingPackageFile> parseFiles(
            JsonElement value, TrainingDatasetLimits limits) {
        List<TrainingPackageFile> result = new ArrayList<>();
        long total = 0;
        for (JsonElement itemValue : TrainingJson.nonemptyArray(value, "files")) {
            JsonObject object = TrainingJson.exactObject(itemValue, FILE_FIELDS, "package file");
            String path = packagePath(TrainingJson.string(object, "path"));
            long size =
                    CanonicalCodec.requirePositiveSafeInteger(
                            object.get("size_bytes"), "size_bytes");
            if (isJsonMember(path) && size > limits.maxJsonMemberBytes()) {
                throw new IllegalArgumentException(
                        "JSON package member exceeds max_json_member_bytes");
            }
            total = checkedAdd(total, size, "package files exceed max_extracted_bytes");
            result.add(
                    new TrainingPackageFile(
                            path,
                            size,
                            CanonicalCodec.requireSha256(
                                    TrainingJson.string(object, "sha256"), "sha256")));
        }
        List<String> paths = result.stream().map(TrainingPackageFile::path).toList();
        if (!paths.equals(paths.stream().sorted().toList())
                || paths.size() != new HashSet<>(paths).size()) {
            throw new IllegalArgumentException("files must be unique and sorted by ASCII path");
        }
        if (total > limits.maxExtractedBytes()) {
            throw new IllegalArgumentException("package files exceed max_extracted_bytes");
        }
        return List.copyOf(result);
    }

    private static void requireGraph(
            List<String> projectGroups,
            List<TrainingImage> images,
            List<TrainingRoi> rois,
            List<TrainingArtifact> artifacts,
            List<TrainingPackageFile> files) {
        Map<Integer, List<Integer>> roisByImage = groupedRoiIndices(rois);
        Map<Integer, List<Integer>> artifactsByImage = groupedArtifactIndices(artifacts);
        for (int imageIndex = 0; imageIndex < images.size(); imageIndex++) {
            List<Integer> imageRois = roisByImage.getOrDefault(imageIndex, List.of());
            List<Integer> imageArtifacts = artifactsByImage.getOrDefault(imageIndex, List.of());
            if (imageRois.isEmpty() || imageArtifacts.isEmpty()) {
                throw new IllegalArgumentException(
                        "every image must own at least one ROI and artifact");
            }
            Set<String> representations = new HashSet<>();
            imageArtifacts.forEach(
                    index -> representations.add(artifacts.get(index).representation()));
            if (representations.contains("original")) {
                requireOriginalMode(imageRois, imageArtifacts, rois, representations);
            } else {
                requireDerivedMode(imageRois, imageArtifacts, rois);
            }
        }
        for (TrainingRoi roi : rois) {
            int artifactIndex = roi.sourceEnvelope().artifactIndex();
            if (artifactIndex >= artifacts.size()) {
                throw new IllegalArgumentException(
                        "source envelope artifact_index is outside artifacts");
            }
            if (artifacts.get(artifactIndex).imageIndex() != roi.imageIndex()) {
                throw new IllegalArgumentException(
                        "source envelope references an artifact from another image");
            }
        }
        Set<Integer> usedGroups = new HashSet<>();
        images.stream()
                .filter(image -> image.projectGroupIndex().isPresent())
                .forEach(image -> usedGroups.add(image.projectGroupIndex().getAsInt()));
        Set<Integer> expectedGroups = new HashSet<>();
        for (int index = 0; index < projectGroups.size(); index++) {
            expectedGroups.add(index);
        }
        if (!usedGroups.equals(expectedGroups)) {
            throw new IllegalArgumentException(
                    "every project group must be referenced by an image");
        }
        Set<String> expectedPaths = new HashSet<>();
        for (int index = 0; index < artifacts.size(); index++) {
            expectedPaths.add(artifactOriginPath(index));
            expectedPaths.add(artifactSourcePath(index, artifacts.get(index).representation()));
        }
        for (int index = 0; index < rois.size(); index++) {
            expectedPaths.add(roiGeometryPath(index));
            expectedPaths.add(roiObjectsPath(index));
        }
        Set<String> actualPaths = new HashSet<>();
        files.forEach(file -> actualPaths.add(file.path()));
        if (!actualPaths.equals(expectedPaths)) {
            throw new IllegalArgumentException(
                    "files do not equal the required package member set");
        }
    }

    private static void requireOriginalMode(
            List<Integer> imageRois,
            List<Integer> imageArtifacts,
            List<TrainingRoi> rois,
            Set<String> representations) {
        if (!representations.equals(Set.of("original")) || imageArtifacts.size() != 1) {
            throw new IllegalArgumentException(
                    "original mode requires one original artifact per image");
        }
        int expected = imageArtifacts.get(0);
        if (imageRois.stream()
                .anyMatch(index -> rois.get(index).sourceEnvelope().artifactIndex() != expected)) {
            throw new IllegalArgumentException("original-mode ROIs must share the image artifact");
        }
    }

    private static void requireDerivedMode(
            List<Integer> imageRois, List<Integer> imageArtifacts, List<TrainingRoi> rois) {
        if (imageRois.size() != imageArtifacts.size()) {
            throw new IllegalArgumentException("derived mode requires one artifact per ROI");
        }
        for (int index = 0; index < imageRois.size(); index++) {
            if (rois.get(imageRois.get(index)).sourceEnvelope().artifactIndex()
                    != imageArtifacts.get(index)) {
                throw new IllegalArgumentException(
                        "derived ROI does not reference its paired artifact");
            }
        }
    }

    private static Map<Integer, List<Integer>> groupedRoiIndices(List<TrainingRoi> values) {
        Map<Integer, List<Integer>> result = new HashMap<>();
        for (int index = 0; index < values.size(); index++) {
            result.computeIfAbsent(values.get(index).imageIndex(), ignored -> new ArrayList<>())
                    .add(index);
        }
        return result;
    }

    private static Map<Integer, List<Integer>> groupedArtifactIndices(
            List<TrainingArtifact> values) {
        Map<Integer, List<Integer>> result = new HashMap<>();
        for (int index = 0; index < values.size(); index++) {
            result.computeIfAbsent(values.get(index).imageIndex(), ignored -> new ArrayList<>())
                    .add(index);
        }
        return result;
    }

    private static TrainingDatasetManifest decodeNormalized(JsonObject value, String digest) {
        List<String> groups =
                TrainingJson.stringArray(value.get("project_groups"), "project_groups");
        return new TrainingDatasetManifest(
                value.get("schema_version").getAsInt(),
                digest,
                TrainingTaskCodec.parseTask(value.get("task")),
                parseArtifacts(
                        value.get("artifacts"),
                        TrainingJson.array(value.get("images"), "images").size()),
                groups,
                parseImages(value.get("images"), groups.size()),
                parseRois(
                        value.get("rois"),
                        TrainingJson.array(value.get("images"), "images").size()),
                parseFilesUnchecked(value.get("files")));
    }

    private static List<TrainingPackageFile> parseFilesUnchecked(JsonElement value) {
        List<TrainingPackageFile> result = new ArrayList<>();
        for (JsonElement itemValue : TrainingJson.array(value, "files")) {
            JsonObject item = itemValue.getAsJsonObject();
            result.add(
                    new TrainingPackageFile(
                            item.get("path").getAsString(),
                            item.get("size_bytes").getAsLong(),
                            item.get("sha256").getAsString()));
        }
        return List.copyOf(result);
    }

    private static void requireExtractedSize(
            TrainingDatasetManifest manifest, TrainingApiLimits limits) {
        long manifestBytes = CanonicalCodec.canonicalBytes(encodeManifest(manifest)).length;
        if (manifestBytes > limits.dataset().maxJsonMemberBytes()) {
            throw new IllegalArgumentException("dataset.json exceeds max_json_member_bytes");
        }
        long total = manifestBytes;
        for (TrainingPackageFile file : manifest.files()) {
            total = checkedAdd(total, file.sizeBytes(), "dataset exceeds max_extracted_bytes");
        }
        if (total > limits.dataset().maxExtractedBytes()) {
            throw new IllegalArgumentException("dataset exceeds max_extracted_bytes");
        }
    }

    private static boolean isJsonMember(String path) {
        return path.endsWith(".json") || path.endsWith(".geojson");
    }

    private static JsonObject encodeManifest(TrainingDatasetManifest value) {
        Objects.requireNonNull(value, "training dataset manifest");
        JsonObject result = encodePreimage(value.preimage());
        result.addProperty("dataset_digest", value.datasetDigest());
        return result;
    }

    private static JsonObject encodePreimage(TrainingDatasetPreimage value) {
        Objects.requireNonNull(value, "training dataset preimage");
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", value.schemaVersion());
        result.add("task", TrainingTaskCodec.toJson(value.task()));
        JsonArray artifacts = new JsonArray();
        value.artifacts().forEach(item -> artifacts.add(encodeArtifact(item)));
        result.add("artifacts", artifacts);
        result.add("project_groups", TrainingJson.strings(value.projectGroups()));
        JsonArray images = new JsonArray();
        value.images().forEach(item -> images.add(encodeImage(item)));
        result.add("images", images);
        JsonArray rois = new JsonArray();
        value.rois().forEach(item -> rois.add(encodeRoi(item)));
        result.add("rois", rois);
        JsonArray files = new JsonArray();
        value.files().forEach(item -> files.add(encodeFile(item)));
        result.add("files", files);
        return result;
    }

    private static JsonObject encodeImage(TrainingImage value) {
        JsonObject result = new JsonObject();
        result.addProperty("project_entry_id_digest", value.projectEntryIdDigest());
        if (value.projectGroupIndex().isPresent()) {
            result.addProperty("project_group_index", value.projectGroupIndex().getAsInt());
        }
        return result;
    }

    private static JsonObject encodeRoi(TrainingRoi value) {
        JsonObject result = new JsonObject();
        result.addProperty("source_roi_uuid", value.sourceRoiUuid());
        result.addProperty("image_index", value.imageIndex());
        result.add("source_envelope", encodeSourceEnvelope(value.sourceEnvelope()));
        return result;
    }

    private static JsonObject encodeSourceEnvelope(TrainingSourceEnvelope value) {
        JsonObject result = new JsonObject();
        result.addProperty("artifact_index", value.artifactIndex());
        result.addProperty("series_index", value.seriesIndex());
        result.addProperty("resolution_level", value.resolutionLevel());
        result.add("pixel_rect", SourceIdentityCodec.toJson(value.pixelRect()));
        return result;
    }

    private static JsonObject encodeArtifact(TrainingArtifact value) {
        JsonObject result = new JsonObject();
        result.addProperty("image_index", value.imageIndex());
        result.addProperty("representation", value.representation());
        return result;
    }

    private static JsonObject encodeFile(TrainingPackageFile value) {
        JsonObject result = new JsonObject();
        result.addProperty("path", value.path());
        result.addProperty("size_bytes", value.sizeBytes());
        result.addProperty("sha256", value.sha256());
        return result;
    }

    private static int compareCanonicalStrings(String left, String right) {
        return Arrays.compareUnsigned(
                CanonicalCodec.canonicalBytes(new JsonPrimitive(left)),
                CanonicalCodec.canonicalBytes(new JsonPrimitive(right)));
    }

    private static String packagePath(String value) {
        if (value.isEmpty()
                || !value.chars().allMatch(character -> character < 128)
                || value.startsWith("/")
                || value.contains("\\")
                || value.contains("//")) {
            throw new IllegalArgumentException("package path is not normalized relative ASCII");
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("package path is not normalized relative ASCII");
            }
        }
        return value;
    }

    private static int nonnegativeIndex(int value, String path) {
        return CanonicalCodec.requireNonnegativeInt32(value, path);
    }

    private static void requireCount(String path, long count, long maximum) {
        if (count > maximum) {
            throw new IllegalArgumentException(path + " exceeds its configured maximum");
        }
    }

    private static long checkedAdd(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(message, error);
        }
    }
}
