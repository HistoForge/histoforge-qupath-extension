package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Exact client-visible training package, transfer, report, and pagination ceilings. */
public final class TrainingLimitsCodec {

    private static final Set<String> DATASET_FIELDS =
            Set.of(
                    "max_rois",
                    "max_objects_per_roi",
                    "max_total_objects",
                    "max_vertices_per_geometry",
                    "max_total_vertices",
                    "max_json_member_bytes",
                    "max_archive_bytes",
                    "max_extracted_bytes");
    private static final Set<String> TRANSFER_FIELDS =
            Set.of("max_chunk_bytes", "max_chunks_per_upload");
    private static final Set<String> REPORT_FIELDS =
            Set.of(
                    "max_metric_series",
                    "max_epochs",
                    "max_messages",
                    "max_text_bytes",
                    "max_component_parameters_bytes",
                    "max_component_parameter_depth",
                    "max_component_collection_entries",
                    "max_report_bytes");
    private static final Set<String> API_FIELDS =
            Set.of("dataset", "transfer", "report", "max_page_size", "max_page_encoded_bytes");

    private TrainingLimitsCodec() {}

    public static TrainingApiLimits parse(JsonElement value) {
        JsonObject object = TrainingJson.exactObject(value, API_FIELDS, "training API limits");
        TrainingDatasetLimits dataset = parseDataset(object.get("dataset"));
        TrainingDatasetTransferLimits transfer = parseTransfer(object.get("transfer"));
        long archiveChunkCount = 1 + (dataset.maxArchiveBytes() - 1) / transfer.maxChunkBytes();
        if (archiveChunkCount > transfer.maxChunksPerUpload()) {
            throw new IllegalArgumentException(
                    "max_archive_bytes exceeds the canonical chunk plan");
        }
        return new TrainingApiLimits(
                dataset,
                transfer,
                parseReport(object.get("report")),
                CanonicalCodec.requirePositiveInt32(object.get("max_page_size"), "max_page_size"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_page_encoded_bytes"), "max_page_encoded_bytes"));
    }

    public static JsonObject toJson(TrainingApiLimits value) {
        JsonObject result = new JsonObject();
        result.add("dataset", toJson(value.dataset()));
        result.add("transfer", toJson(value.transfer()));
        result.add("report", toJson(value.report()));
        result.addProperty("max_page_size", value.maxPageSize());
        result.addProperty("max_page_encoded_bytes", value.maxPageEncodedBytes());
        return result;
    }

    private static TrainingDatasetLimits parseDataset(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, DATASET_FIELDS, "training dataset limits");
        TrainingDatasetLimits result =
                new TrainingDatasetLimits(
                        positive(object, "max_rois"),
                        positive(object, "max_objects_per_roi"),
                        positive(object, "max_total_objects"),
                        positive(object, "max_vertices_per_geometry"),
                        positive(object, "max_total_vertices"),
                        positive(object, "max_json_member_bytes"),
                        positive(object, "max_archive_bytes"),
                        positive(object, "max_extracted_bytes"));
        if (result.maxJsonMemberBytes() > result.maxExtractedBytes()) {
            throw new IllegalArgumentException("max_json_member_bytes exceeds max_extracted_bytes");
        }
        return result;
    }

    private static TrainingDatasetTransferLimits parseTransfer(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, TRANSFER_FIELDS, "training transfer limits");
        return new TrainingDatasetTransferLimits(
                positive(object, "max_chunk_bytes"),
                CanonicalCodec.requirePositiveInt32(
                        object.get("max_chunks_per_upload"), "max_chunks_per_upload"));
    }

    private static TrainingReportLimits parseReport(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, REPORT_FIELDS, "training report limits");
        return new TrainingReportLimits(
                positive(object, "max_metric_series"),
                positive(object, "max_epochs"),
                positive(object, "max_messages"),
                positive(object, "max_text_bytes"),
                positive(object, "max_component_parameters_bytes"),
                positive(object, "max_component_parameter_depth"),
                positive(object, "max_component_collection_entries"),
                positive(object, "max_report_bytes"));
    }

    private static JsonObject toJson(TrainingDatasetLimits value) {
        JsonObject result = new JsonObject();
        result.addProperty("max_rois", value.maxRois());
        result.addProperty("max_objects_per_roi", value.maxObjectsPerRoi());
        result.addProperty("max_total_objects", value.maxTotalObjects());
        result.addProperty("max_vertices_per_geometry", value.maxVerticesPerGeometry());
        result.addProperty("max_total_vertices", value.maxTotalVertices());
        result.addProperty("max_json_member_bytes", value.maxJsonMemberBytes());
        result.addProperty("max_archive_bytes", value.maxArchiveBytes());
        result.addProperty("max_extracted_bytes", value.maxExtractedBytes());
        return result;
    }

    private static JsonObject toJson(TrainingDatasetTransferLimits value) {
        JsonObject result = new JsonObject();
        result.addProperty("max_chunk_bytes", value.maxChunkBytes());
        result.addProperty("max_chunks_per_upload", value.maxChunksPerUpload());
        return result;
    }

    private static JsonObject toJson(TrainingReportLimits value) {
        JsonObject result = new JsonObject();
        result.addProperty("max_metric_series", value.maxMetricSeries());
        result.addProperty("max_epochs", value.maxEpochs());
        result.addProperty("max_messages", value.maxMessages());
        result.addProperty("max_text_bytes", value.maxTextBytes());
        result.addProperty("max_component_parameters_bytes", value.maxComponentParametersBytes());
        result.addProperty("max_component_parameter_depth", value.maxComponentParameterDepth());
        result.addProperty(
                "max_component_collection_entries", value.maxComponentCollectionEntries());
        result.addProperty("max_report_bytes", value.maxReportBytes());
        return result;
    }

    private static long positive(JsonObject value, String field) {
        return CanonicalCodec.requirePositiveSafeInteger(value.get(field), field);
    }

    public record TrainingDatasetLimits(
            long maxRois,
            long maxObjectsPerRoi,
            long maxTotalObjects,
            long maxVerticesPerGeometry,
            long maxTotalVertices,
            long maxJsonMemberBytes,
            long maxArchiveBytes,
            long maxExtractedBytes) {}

    public record TrainingDatasetTransferLimits(long maxChunkBytes, int maxChunksPerUpload) {}

    public record TrainingReportLimits(
            long maxMetricSeries,
            long maxEpochs,
            long maxMessages,
            long maxTextBytes,
            long maxComponentParametersBytes,
            long maxComponentParameterDepth,
            long maxComponentCollectionEntries,
            long maxReportBytes) {}

    public record TrainingApiLimits(
            TrainingDatasetLimits dataset,
            TrainingDatasetTransferLimits transfer,
            TrainingReportLimits report,
            int maxPageSize,
            long maxPageEncodedBytes) {}
}
