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
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;

/** Training-dataset upload, retention, cleanup, and list DTO contracts. */
public final class TrainingDatasetTransferCodec {

    private static final Set<String> START_FIELDS =
            Set.of(
                    "schema_version",
                    "dataset_digest",
                    "archive_bytes_digest",
                    "total_bytes",
                    "chunk_size_bytes");
    private static final Set<String> UPLOAD_ACCEPTED_FIELDS =
            Set.of("type", "dataset_id", "start_request_digest", "expires_at");
    private static final Set<String> EXISTING_FIELDS = Set.of("type", "dataset");
    private static final Set<String> SEAL_FIELDS = Set.of("schema_version", "start_request_digest");
    private static final Set<String> CHUNK_FIELDS =
            Set.of("start_request_digest", "chunk_index", "size_bytes", "sha256", "accepted_at");
    private static final Set<String> RECEIPT_FIELDS = Set.of("dataset_id", "chunk", "expires_at");
    private static final Set<String> RETAINED_FIELDS =
            Set.of(
                    "type",
                    "dataset_id",
                    "dataset_digest",
                    "retained_bytes",
                    "task_display_name",
                    "image_count",
                    "roi_count",
                    "created_at");
    private static final Set<String> CLEANUP_FIELDS =
            Set.of(
                    "type",
                    "dataset_id",
                    "task_display_name",
                    "image_count",
                    "roi_count",
                    "last_known_remaining_bytes",
                    "created_at");
    private static final Set<String> CLEANUP_WITH_ERROR_FIELDS =
            Set.of(
                    "type",
                    "dataset_id",
                    "task_display_name",
                    "image_count",
                    "roi_count",
                    "last_known_remaining_bytes",
                    "created_at",
                    "last_error");
    private static final Set<String> SUMMARY_FIELDS = Set.of("record", "in_use");
    private static final Set<String> PAGE_FIELDS = Set.of("items");
    private static final Set<String> PAGE_WITH_CURSOR_FIELDS = Set.of("items", "next_cursor");

    private TrainingDatasetTransferCodec() {}

    public record TrainingDatasetStartRequest(
            String datasetDigest,
            String archiveBytesDigest,
            long totalBytes,
            long chunkSizeBytes) {}

    public record TrainingDatasetChunkRange(int chunkIndex, long offset, long sizeBytes) {}

    public sealed interface TrainingDatasetStartResult
            permits ExistingTrainingDataset, TrainingDatasetUploadAccepted {}

    public record ExistingTrainingDataset(TrainingDatasetRecord dataset)
            implements TrainingDatasetStartResult {}

    public record TrainingDatasetUploadAccepted(
            String datasetId, String startRequestDigest, Instant expiresAt)
            implements TrainingDatasetStartResult {}

    public record TrainingDatasetSealRequest(String startRequestDigest) {}

    public record TrainingDatasetChunkCommit(
            String startRequestDigest,
            int chunkIndex,
            long sizeBytes,
            String sha256,
            Instant acceptedAt) {}

    public record TrainingDatasetChunkReceipt(
            String datasetId, TrainingDatasetChunkCommit chunk, Instant expiresAt) {}

    public sealed interface TrainingDatasetListRecord
            permits TrainingDatasetRecord, TrainingDatasetCleanupRecord {
        String datasetId();

        String taskDisplayName();

        long imageCount();

        long roiCount();

        Instant createdAt();
    }

    public record TrainingDatasetRecord(
            String datasetId,
            String datasetDigest,
            long retainedBytes,
            String taskDisplayName,
            long imageCount,
            long roiCount,
            Instant createdAt)
            implements TrainingDatasetListRecord {}

    public record TrainingDatasetCleanupRecord(
            String datasetId,
            String taskDisplayName,
            long imageCount,
            long roiCount,
            long lastKnownRemainingBytes,
            Instant createdAt,
            Optional<GatewayErrorCodec.Error> lastError)
            implements TrainingDatasetListRecord {
        public TrainingDatasetCleanupRecord {
            lastError = Objects.requireNonNull(lastError, "last_error");
        }
    }

    public record TrainingDatasetSummary(TrainingDatasetListRecord record, boolean inUse) {}

    public record TrainingDatasetPage(
            List<TrainingDatasetSummary> items, Optional<String> nextCursor) {
        public TrainingDatasetPage {
            items = List.copyOf(items);
            nextCursor = Objects.requireNonNull(nextCursor, "next_cursor");
        }
    }

    public static TrainingDatasetStartRequest parseStartRequest(
            JsonElement value, TrainingApiLimits limits) {
        JsonObject object = TrainingJson.exactObject(value, START_FIELDS, "training dataset start");
        requireSchemaVersion(object, "training dataset start");
        long total =
                CanonicalCodec.requirePositiveSafeInteger(object.get("total_bytes"), "total_bytes");
        long chunkSize =
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("chunk_size_bytes"), "chunk_size_bytes");
        if (total > limits.dataset().maxArchiveBytes()) {
            throw new IllegalArgumentException(
                    "training dataset archive exceeds max_archive_bytes");
        }
        if (chunkSize > limits.transfer().maxChunkBytes()) {
            throw new IllegalArgumentException("chunk_size_bytes exceeds max_chunk_bytes");
        }
        if (chunkCount(total, chunkSize) > limits.transfer().maxChunksPerUpload()) {
            throw new IllegalArgumentException(
                    "training dataset archive exceeds max_chunks_per_upload");
        }
        return new TrainingDatasetStartRequest(
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "dataset_digest"), "dataset_digest"),
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "archive_bytes_digest"),
                        "archive_bytes_digest"),
                total,
                chunkSize);
    }

    public static String startRequestDigest(
            TrainingDatasetStartRequest value, TrainingApiLimits limits) {
        return CanonicalCodec.canonicalDigest(toJson(value, limits));
    }

    public static TrainingDatasetChunkRange chunkRange(
            TrainingDatasetStartRequest request, int chunkIndex, TrainingApiLimits limits) {
        TrainingDatasetStartRequest checked =
                parseStartRequest(encodeStartRequest(request), limits);
        int count = chunkCount(checked.totalBytes(), checked.chunkSizeBytes());
        int index = CanonicalCodec.requireNonnegativeInt32(chunkIndex, "chunk_index");
        if (index >= count) {
            throw new IllegalArgumentException("chunk_index lies outside canonical chunk plan");
        }
        long offset = Math.multiplyExact((long) index, checked.chunkSizeBytes());
        return new TrainingDatasetChunkRange(
                index, offset, Math.min(checked.chunkSizeBytes(), checked.totalBytes() - offset));
    }

    public static TrainingDatasetStartResult parseStartResult(
            JsonElement value, TrainingApiLimits limits) {
        JsonObject object = TrainingJson.object(value, "training dataset start result");
        String type = TrainingJson.string(object, "type");
        return switch (type) {
            case "existing" -> {
                TrainingJson.requireFields(object, EXISTING_FIELDS, "existing training dataset");
                yield new ExistingTrainingDataset(
                        parseDatasetRecord(object.get("dataset"), limits));
            }
            case "upload" -> {
                TrainingJson.requireFields(
                        object, UPLOAD_ACCEPTED_FIELDS, "training dataset upload accepted");
                yield new TrainingDatasetUploadAccepted(
                        canonicalUuid(object, "dataset_id"),
                        sha256(object, "start_request_digest"),
                        timestamp(object, "expires_at"));
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported training dataset start result: " + type);
        };
    }

    public static TrainingDatasetSealRequest parseSealRequest(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, SEAL_FIELDS, "training dataset seal request");
        requireSchemaVersion(object, "training dataset seal");
        return new TrainingDatasetSealRequest(sha256(object, "start_request_digest"));
    }

    public static TrainingDatasetChunkCommit parseChunkCommit(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, CHUNK_FIELDS, "training dataset chunk commit");
        return new TrainingDatasetChunkCommit(
                sha256(object, "start_request_digest"),
                CanonicalCodec.requireNonnegativeInt32(object.get("chunk_index"), "chunk_index"),
                CanonicalCodec.requirePositiveSafeInteger(object.get("size_bytes"), "size_bytes"),
                sha256(object, "sha256"),
                timestamp(object, "accepted_at"));
    }

    public static TrainingDatasetChunkReceipt parseChunkReceipt(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, RECEIPT_FIELDS, "training dataset chunk receipt");
        return new TrainingDatasetChunkReceipt(
                canonicalUuid(object, "dataset_id"),
                parseChunkCommit(object.get("chunk")),
                timestamp(object, "expires_at"));
    }

    public static TrainingDatasetRecord parseDatasetRecord(
            JsonElement value, TrainingApiLimits limits) {
        JsonObject object =
                TrainingJson.exactObject(value, RETAINED_FIELDS, "training dataset record");
        if (!"retained".equals(TrainingJson.string(object, "type"))) {
            throw new IllegalArgumentException("training dataset record type must be retained");
        }
        return new TrainingDatasetRecord(
                canonicalUuid(object, "dataset_id"),
                sha256(object, "dataset_digest"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("retained_bytes"), "retained_bytes"),
                TrainingJson.textSyntax(object.get("task_display_name"), "task_display_name"),
                positiveCount(object, "image_count"),
                positiveCount(object, "roi_count"),
                timestamp(object, "created_at"));
    }

    public static TrainingDatasetCleanupRecord parseCleanupRecord(
            JsonElement value, TrainingApiLimits limits) {
        JsonObject object = TrainingJson.object(value, "training dataset cleanup record");
        TrainingJson.requireFields(
                object,
                object.has("last_error") ? CLEANUP_WITH_ERROR_FIELDS : CLEANUP_FIELDS,
                "training dataset cleanup record");
        if (!"cleanup-required".equals(TrainingJson.string(object, "type"))) {
            throw new IllegalArgumentException(
                    "training dataset cleanup record type must be cleanup-required");
        }
        return new TrainingDatasetCleanupRecord(
                canonicalUuid(object, "dataset_id"),
                TrainingJson.textSyntax(object.get("task_display_name"), "task_display_name"),
                positiveCount(object, "image_count"),
                positiveCount(object, "roi_count"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("last_known_remaining_bytes"), "last_known_remaining_bytes"),
                timestamp(object, "created_at"),
                object.has("last_error")
                        ? Optional.of(GatewayErrorCodec.parse(object.get("last_error")))
                        : Optional.empty());
    }

    public static TrainingDatasetSummary parseSummary(JsonElement value, TrainingApiLimits limits) {
        JsonObject object =
                TrainingJson.exactObject(value, SUMMARY_FIELDS, "training dataset summary");
        JsonObject recordValue =
                TrainingJson.object(object.get("record"), "training dataset summary record");
        TrainingDatasetListRecord record =
                switch (TrainingJson.string(recordValue, "type")) {
                    case "retained" -> parseDatasetRecord(recordValue, limits);
                    case "cleanup-required" -> parseCleanupRecord(recordValue, limits);
                    default ->
                            throw new IllegalArgumentException(
                                    "training dataset summary has an unsupported record");
                };
        boolean inUse = TrainingJson.bool(object.get("in_use"), "in_use");
        if (record instanceof TrainingDatasetCleanupRecord && inUse) {
            throw new IllegalArgumentException("cleanup-required dataset cannot be in use");
        }
        return new TrainingDatasetSummary(record, inUse);
    }

    public static TrainingDatasetPage parsePage(JsonElement value, TrainingApiLimits limits) {
        JsonObject object = TrainingJson.object(value, "training dataset page");
        TrainingJson.requireFields(
                object,
                object.has("next_cursor") ? PAGE_WITH_CURSOR_FIELDS : PAGE_FIELDS,
                "training dataset page");
        JsonArray rawItems = TrainingJson.array(object.get("items"), "items");
        if (rawItems.size() > limits.maxPageSize()) {
            throw new IllegalArgumentException("training dataset page exceeds max_page_size");
        }
        List<TrainingDatasetSummary> items = new ArrayList<>();
        rawItems.forEach(item -> items.add(parseSummary(item, limits)));
        List<TrainingDatasetSummary> sorted = new ArrayList<>(items);
        sorted.sort(summaryComparator());
        Set<String> identities = new HashSet<>();
        items.forEach(
                item ->
                        identities.add(
                                item.record().createdAt() + "\u0000" + item.record().datasetId()));
        if (!items.equals(sorted) || identities.size() != items.size()) {
            throw new IllegalArgumentException("training dataset page is not uniquely ordered");
        }
        Optional<String> cursor = Optional.empty();
        if (object.has("next_cursor")) {
            String valueCursor =
                    CanonicalCodec.requireNonblank(
                            TrainingJson.string(object, "next_cursor"), "next_cursor");
            if (items.isEmpty()) {
                throw new IllegalArgumentException("empty page cannot have next_cursor");
            }
            cursor = Optional.of(valueCursor);
        }
        TrainingDatasetPage result = new TrainingDatasetPage(items, cursor);
        if (CanonicalCodec.canonicalBytes(encodePage(result)).length
                > limits.maxPageEncodedBytes()) {
            throw new IllegalArgumentException(
                    "training dataset page exceeds max_page_encoded_bytes");
        }
        return result;
    }

    public static JsonObject toJson(TrainingDatasetStartRequest value, TrainingApiLimits limits) {
        return encodeStartRequest(parseStartRequest(encodeStartRequest(value), limits));
    }

    public static JsonObject toJson(TrainingDatasetStartResult value, TrainingApiLimits limits) {
        return encodeStartResult(parseStartResult(encodeStartResult(value), limits));
    }

    public static JsonObject toJson(TrainingDatasetSealRequest value) {
        return encodeSealRequest(parseSealRequest(encodeSealRequest(value)));
    }

    public static JsonObject toJson(TrainingDatasetChunkCommit value) {
        return encodeChunkCommit(parseChunkCommit(encodeChunkCommit(value)));
    }

    public static JsonObject toJson(TrainingDatasetChunkReceipt value) {
        return encodeChunkReceipt(parseChunkReceipt(encodeChunkReceipt(value)));
    }

    public static JsonObject toJson(TrainingDatasetListRecord value, TrainingApiLimits limits) {
        JsonObject encoded = encodeListRecord(value);
        return value instanceof TrainingDatasetRecord
                ? encodeListRecord(parseDatasetRecord(encoded, limits))
                : encodeListRecord(parseCleanupRecord(encoded, limits));
    }

    public static JsonObject toJson(TrainingDatasetSummary value, TrainingApiLimits limits) {
        return encodeSummary(parseSummary(encodeSummary(value), limits));
    }

    public static JsonObject toJson(TrainingDatasetPage value, TrainingApiLimits limits) {
        return encodePage(parsePage(encodePage(value), limits));
    }

    /** Number of ranges in the canonical contiguous chunk plan. */
    public static int chunkCount(long totalBytes, long chunkSizeBytes) {
        long count = 1 + (totalBytes - 1) / chunkSizeBytes;
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("canonical chunk count exceeds signed 32-bit range");
        }
        return (int) count;
    }

    private static Comparator<TrainingDatasetSummary> summaryComparator() {
        return Comparator.<TrainingDatasetSummary, Instant>comparing(
                        item -> item.record().createdAt())
                .reversed()
                .thenComparing(item -> item.record().datasetId());
    }

    private static long positiveCount(JsonObject value, String field) {
        return CanonicalCodec.requirePositiveSafeInteger(value.get(field), field);
    }

    private static void requireSchemaVersion(JsonObject value, String path) {
        if (CanonicalCodec.requireInt32(value.get("schema_version"), "schema_version") != 1) {
            throw new IllegalArgumentException("unsupported " + path + " schema_version");
        }
    }

    private static String canonicalUuid(JsonObject value, String field) {
        return CanonicalCodec.requireCanonicalUuid(TrainingJson.string(value, field), field);
    }

    private static String sha256(JsonObject value, String field) {
        return CanonicalCodec.requireSha256(TrainingJson.string(value, field), field);
    }

    private static Instant timestamp(JsonObject value, String field) {
        return CanonicalCodec.requireRfc3339Millis(TrainingJson.string(value, field), field);
    }

    private static JsonObject encodeStartRequest(TrainingDatasetStartRequest value) {
        Objects.requireNonNull(value, "training dataset start request");
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", 1);
        result.addProperty("dataset_digest", value.datasetDigest());
        result.addProperty("archive_bytes_digest", value.archiveBytesDigest());
        result.addProperty("total_bytes", value.totalBytes());
        result.addProperty("chunk_size_bytes", value.chunkSizeBytes());
        return result;
    }

    private static JsonObject encodeStartResult(TrainingDatasetStartResult value) {
        Objects.requireNonNull(value, "training dataset start result");
        JsonObject result = new JsonObject();
        if (value instanceof ExistingTrainingDataset existing) {
            result.addProperty("type", "existing");
            result.add("dataset", encodeListRecord(existing.dataset()));
        } else if (value instanceof TrainingDatasetUploadAccepted upload) {
            result.addProperty("type", "upload");
            result.addProperty("dataset_id", upload.datasetId());
            result.addProperty("start_request_digest", upload.startRequestDigest());
            result.addProperty(
                    "expires_at", CanonicalCodec.rfc3339Millis(upload.expiresAt(), "expires_at"));
        } else {
            throw new IllegalArgumentException("unsupported training dataset start result");
        }
        return result;
    }

    private static JsonObject encodeSealRequest(TrainingDatasetSealRequest value) {
        Objects.requireNonNull(value, "training dataset seal request");
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", 1);
        result.addProperty("start_request_digest", value.startRequestDigest());
        return result;
    }

    private static JsonObject encodeChunkCommit(TrainingDatasetChunkCommit value) {
        Objects.requireNonNull(value, "training dataset chunk commit");
        JsonObject result = new JsonObject();
        result.addProperty("start_request_digest", value.startRequestDigest());
        result.addProperty("chunk_index", value.chunkIndex());
        result.addProperty("size_bytes", value.sizeBytes());
        result.addProperty("sha256", value.sha256());
        result.addProperty(
                "accepted_at", CanonicalCodec.rfc3339Millis(value.acceptedAt(), "accepted_at"));
        return result;
    }

    private static JsonObject encodeChunkReceipt(TrainingDatasetChunkReceipt value) {
        Objects.requireNonNull(value, "training dataset chunk receipt");
        JsonObject result = new JsonObject();
        result.addProperty("dataset_id", value.datasetId());
        result.add("chunk", encodeChunkCommit(value.chunk()));
        result.addProperty(
                "expires_at", CanonicalCodec.rfc3339Millis(value.expiresAt(), "expires_at"));
        return result;
    }

    private static JsonObject encodeListRecord(TrainingDatasetListRecord value) {
        Objects.requireNonNull(value, "training dataset list record");
        JsonObject result = new JsonObject();
        if (value instanceof TrainingDatasetRecord retained) {
            result.addProperty("type", "retained");
            result.addProperty("dataset_digest", retained.datasetDigest());
            result.addProperty("retained_bytes", retained.retainedBytes());
        } else if (value instanceof TrainingDatasetCleanupRecord cleanup) {
            result.addProperty("type", "cleanup-required");
            result.addProperty("last_known_remaining_bytes", cleanup.lastKnownRemainingBytes());
            cleanup.lastError()
                    .ifPresent(error -> result.add("last_error", GatewayErrorCodec.toJson(error)));
        } else {
            throw new IllegalArgumentException("unsupported training dataset list record");
        }
        result.addProperty("dataset_id", value.datasetId());
        result.addProperty("task_display_name", value.taskDisplayName());
        result.addProperty("image_count", value.imageCount());
        result.addProperty("roi_count", value.roiCount());
        result.addProperty(
                "created_at", CanonicalCodec.rfc3339Millis(value.createdAt(), "created_at"));
        return result;
    }

    private static JsonObject encodeSummary(TrainingDatasetSummary value) {
        Objects.requireNonNull(value, "training dataset summary");
        JsonObject result = new JsonObject();
        result.add("record", encodeListRecord(value.record()));
        result.addProperty("in_use", value.inUse());
        return result;
    }

    private static JsonObject encodePage(TrainingDatasetPage value) {
        Objects.requireNonNull(value, "training dataset page");
        JsonObject result = new JsonObject();
        JsonArray items = new JsonArray();
        value.items().forEach(item -> items.add(encodeSummary(item)));
        result.add("items", items);
        value.nextCursor().ifPresent(cursor -> result.addProperty("next_cursor", cursor));
        return result;
    }
}
