package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Canonical execution-limit contracts and overflow-safe finite-fit predicate. */
public final class ExecutionLimitsCodec {

    private static final Set<String> TARGET_RASTER_LIMIT_FIELDS =
            Set.of("max_width", "max_height", "max_pixels");
    private static final Set<String> RESULT_LIMIT_FIELDS =
            Set.of(
                    "max_transferred_bytes",
                    "max_decoded_bytes",
                    "max_record_bytes",
                    "max_features",
                    "max_vertices");
    private static final Set<String> MODEL_RUN_LIMIT_FIELDS =
            Set.of(
                    "max_execution_seconds",
                    "max_request_canonical_bytes",
                    "max_inputs",
                    "max_units",
                    "target_raster_per_input",
                    "processing_raster",
                    "max_aggregate_target_raster_pixels",
                    "max_aggregate_inclusion_mask_vertices",
                    "max_recovery_state_bytes_per_unit",
                    "max_recovery_state_bytes_per_run",
                    "intermediate_result",
                    "result");
    private static final Set<String> CLIENT_EXECUTION_LIMIT_FIELDS =
            Set.of(
                    "max_hierarchy_objects",
                    "max_hierarchy_geometry_vertices",
                    "max_hierarchy_measurements",
                    "max_hierarchy_text_utf8_bytes",
                    "max_generation_store_utf8_bytes",
                    "max_metric_values_per_region",
                    "max_metric_values_per_image",
                    "max_local_source_artifact_bytes");

    private ExecutionLimitsCodec() {}

    /** Exact per-raster spatial ceilings advertised by one runner. */
    public record TargetRasterLimits(int maxWidth, int maxHeight, long maxPixels) {
        public TargetRasterLimits {
            maxWidth = CanonicalCodec.requirePositiveInt32(maxWidth, "max_width");
            maxHeight = CanonicalCodec.requirePositiveInt32(maxHeight, "max_height");
            maxPixels = CanonicalCodec.requirePositiveSafeInteger(maxPixels, "max_pixels");
        }
    }

    /** Transferred, decoded, record, feature, and geometry ceilings. */
    public record ResultLimits(
            long maxTransferredBytes,
            long maxDecodedBytes,
            long maxRecordBytes,
            long maxFeatures,
            long maxVertices) {
        public ResultLimits {
            maxTransferredBytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxTransferredBytes, "max_transferred_bytes");
            maxDecodedBytes =
                    CanonicalCodec.requirePositiveSafeInteger(maxDecodedBytes, "max_decoded_bytes");
            maxRecordBytes =
                    CanonicalCodec.requirePositiveSafeInteger(maxRecordBytes, "max_record_bytes");
            maxFeatures = CanonicalCodec.requirePositiveSafeInteger(maxFeatures, "max_features");
            maxVertices = CanonicalCodec.requirePositiveSafeInteger(maxVertices, "max_vertices");
        }
    }

    /**
     * Durable-run limits for the complete input, each processing image (including halo), recovery
     * storage, intermediate geometry before assembly, and the final assembled output.
     */
    public record ModelRunLimits(
            int maxExecutionSeconds,
            long maxRequestCanonicalBytes,
            int maxInputs,
            int maxUnits,
            TargetRasterLimits targetRasterPerInput,
            TargetRasterLimits processingRaster,
            long maxAggregateTargetRasterPixels,
            long maxAggregateInclusionMaskVertices,
            long maxRecoveryStateBytesPerUnit,
            long maxRecoveryStateBytesPerRun,
            ResultLimits intermediateResult,
            ResultLimits result) {
        public ModelRunLimits {
            maxExecutionSeconds =
                    CanonicalCodec.requirePositiveInt32(
                            maxExecutionSeconds, "max_execution_seconds");
            maxRequestCanonicalBytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxRequestCanonicalBytes, "max_request_canonical_bytes");
            maxInputs = CanonicalCodec.requirePositiveInt32(maxInputs, "max_inputs");
            maxUnits = CanonicalCodec.requirePositiveInt32(maxUnits, "max_units");
            if (targetRasterPerInput == null
                    || processingRaster == null
                    || intermediateResult == null
                    || result == null) {
                throw new NullPointerException("model run nested limits are required");
            }
            maxAggregateTargetRasterPixels =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxAggregateTargetRasterPixels, "max_aggregate_target_raster_pixels");
            maxAggregateInclusionMaskVertices =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxAggregateInclusionMaskVertices,
                            "max_aggregate_inclusion_mask_vertices");
            maxRecoveryStateBytesPerUnit =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxRecoveryStateBytesPerUnit, "max_recovery_state_bytes_per_unit");
            maxRecoveryStateBytesPerRun =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxRecoveryStateBytesPerRun, "max_recovery_state_bytes_per_run");
        }
    }

    /** Client resource ceilings captured by every action and persisted in its build identity. */
    public record ClientExecutionLimits(
            // Maximum semantic PathObjects in one captured or working image.
            long maxHierarchyObjects,
            // Maximum aggregate geometry vertices across those image objects.
            long maxHierarchyGeometryVertices,
            // Maximum aggregate measurement entries across those image objects.
            long maxHierarchyMeasurements,
            // Maximum aggregate UTF-8 bytes in object names, metadata, and string properties.
            long maxHierarchyTextUtf8Bytes,
            // Maximum UTF-8 bytes in the persisted generation store.
            long maxGenerationStoreUtf8Bytes,
            // Maximum metric values materialized for one logical region.
            long maxMetricValuesPerRegion,
            // Maximum metric values materialized for one image.
            long maxMetricValuesPerImage,
            // Maximum bytes in one client-created derived source artifact.
            long maxLocalSourceArtifactBytes) {
        public ClientExecutionLimits {
            maxHierarchyObjects =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxHierarchyObjects, "max_hierarchy_objects");
            maxHierarchyGeometryVertices =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxHierarchyGeometryVertices, "max_hierarchy_geometry_vertices");
            maxHierarchyMeasurements =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxHierarchyMeasurements, "max_hierarchy_measurements");
            maxHierarchyTextUtf8Bytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxHierarchyTextUtf8Bytes, "max_hierarchy_text_utf8_bytes");
            maxGenerationStoreUtf8Bytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxGenerationStoreUtf8Bytes, "max_generation_store_utf8_bytes");
            maxMetricValuesPerRegion =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxMetricValuesPerRegion, "max_metric_values_per_region");
            maxMetricValuesPerImage =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxMetricValuesPerImage, "max_metric_values_per_image");
            maxLocalSourceArtifactBytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxLocalSourceArtifactBytes, "max_local_source_artifact_bytes");
        }
    }

    /** Parse one closed target-raster limit object. */
    public static TargetRasterLimits parseTargetRasterLimits(JsonElement value) {
        JsonObject object = requiredObject(value, "target raster limits");
        if (!object.keySet().equals(TARGET_RASTER_LIMIT_FIELDS)) {
            throw new IllegalArgumentException(
                    "target raster limits have missing or unknown fields");
        }
        return new TargetRasterLimits(
                CanonicalCodec.requirePositiveInt32(object.get("max_width"), "max_width"),
                CanonicalCodec.requirePositiveInt32(object.get("max_height"), "max_height"),
                CanonicalCodec.requirePositiveSafeInteger(object.get("max_pixels"), "max_pixels"));
    }

    /** Parse one closed result-limit object. */
    public static ResultLimits parseResultLimits(JsonElement value) {
        JsonObject object = requireExactObject(value, RESULT_LIMIT_FIELDS, "result limits");
        return new ResultLimits(
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_transferred_bytes"), "max_transferred_bytes"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_decoded_bytes"), "max_decoded_bytes"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_record_bytes"), "max_record_bytes"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_features"), "max_features"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_vertices"), "max_vertices"));
    }

    /** Parse the complete durable model-run limit projection. */
    public static ModelRunLimits parseModelRunLimits(JsonElement value) {
        JsonObject object = requireExactObject(value, MODEL_RUN_LIMIT_FIELDS, "model run limits");
        return new ModelRunLimits(
                CanonicalCodec.requirePositiveInt32(
                        object.get("max_execution_seconds"), "max_execution_seconds"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_request_canonical_bytes"), "max_request_canonical_bytes"),
                CanonicalCodec.requirePositiveInt32(object.get("max_inputs"), "max_inputs"),
                CanonicalCodec.requirePositiveInt32(object.get("max_units"), "max_units"),
                parseTargetRasterLimits(object.get("target_raster_per_input")),
                parseTargetRasterLimits(object.get("processing_raster")),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_aggregate_target_raster_pixels"),
                        "max_aggregate_target_raster_pixels"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_aggregate_inclusion_mask_vertices"),
                        "max_aggregate_inclusion_mask_vertices"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_recovery_state_bytes_per_unit"),
                        "max_recovery_state_bytes_per_unit"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_recovery_state_bytes_per_run"),
                        "max_recovery_state_bytes_per_run"),
                parseResultLimits(object.get("intermediate_result")),
                parseResultLimits(object.get("result")));
    }

    /** Parse the complete client-side hierarchy, metric, persistence, and artifact ceilings. */
    public static ClientExecutionLimits parseClientExecutionLimits(JsonElement value) {
        JsonObject object =
                requireExactObject(value, CLIENT_EXECUTION_LIMIT_FIELDS, "client execution limits");
        return new ClientExecutionLimits(
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_hierarchy_objects"), "max_hierarchy_objects"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_hierarchy_geometry_vertices"),
                        "max_hierarchy_geometry_vertices"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_hierarchy_measurements"), "max_hierarchy_measurements"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_hierarchy_text_utf8_bytes"),
                        "max_hierarchy_text_utf8_bytes"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_generation_store_utf8_bytes"),
                        "max_generation_store_utf8_bytes"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_metric_values_per_region"), "max_metric_values_per_region"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_metric_values_per_image"), "max_metric_values_per_image"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_local_source_artifact_bytes"),
                        "max_local_source_artifact_bytes"));
    }

    /** Return the frozen finite-plan selection predicate for one target raster. */
    public static boolean fitsTargetRaster(
            RasterInputCodec.RasterDescriptor raster, TargetRasterLimits limits) {
        if (raster == null || limits == null) {
            throw new NullPointerException("raster and limits are required");
        }
        return fitsTargetRaster(raster.width(), raster.height(), limits);
    }

    /** Return the same frozen fit predicate before a complete raster descriptor is constructed. */
    public static boolean fitsTargetRaster(int width, int height, TargetRasterLimits limits) {
        CanonicalCodec.requirePositiveInt32(width, "raster.width");
        CanonicalCodec.requirePositiveInt32(height, "raster.height");
        if (limits == null) {
            throw new NullPointerException("limits");
        }
        long pixels = Math.multiplyExact((long) width, height);
        return width <= limits.maxWidth()
                && height <= limits.maxHeight()
                && pixels <= limits.maxPixels();
    }

    /** Encode one target-raster limit object using exact lower-snake fields. */
    public static JsonObject toJson(TargetRasterLimits limits) {
        JsonObject value = new JsonObject();
        value.addProperty("max_width", limits.maxWidth());
        value.addProperty("max_height", limits.maxHeight());
        value.addProperty("max_pixels", limits.maxPixels());
        return value;
    }

    /** Encode one result-limit object using exact lower-snake fields. */
    public static JsonObject toJson(ResultLimits limits) {
        JsonObject value = new JsonObject();
        value.addProperty("max_transferred_bytes", limits.maxTransferredBytes());
        value.addProperty("max_decoded_bytes", limits.maxDecodedBytes());
        value.addProperty("max_record_bytes", limits.maxRecordBytes());
        value.addProperty("max_features", limits.maxFeatures());
        value.addProperty("max_vertices", limits.maxVertices());
        return value;
    }

    /** Encode one complete durable model-run limit object. */
    public static JsonObject toJson(ModelRunLimits limits) {
        JsonObject value = new JsonObject();
        value.addProperty("max_execution_seconds", limits.maxExecutionSeconds());
        value.addProperty("max_request_canonical_bytes", limits.maxRequestCanonicalBytes());
        value.addProperty("max_inputs", limits.maxInputs());
        value.addProperty("max_units", limits.maxUnits());
        value.add("target_raster_per_input", toJson(limits.targetRasterPerInput()));
        value.add("processing_raster", toJson(limits.processingRaster()));
        value.addProperty(
                "max_aggregate_target_raster_pixels", limits.maxAggregateTargetRasterPixels());
        value.addProperty(
                "max_aggregate_inclusion_mask_vertices",
                limits.maxAggregateInclusionMaskVertices());
        value.addProperty(
                "max_recovery_state_bytes_per_unit", limits.maxRecoveryStateBytesPerUnit());
        value.addProperty("max_recovery_state_bytes_per_run", limits.maxRecoveryStateBytesPerRun());
        value.add("intermediate_result", toJson(limits.intermediateResult()));
        value.add("result", toJson(limits.result()));
        return value;
    }

    /** Encode one complete client execution-limit identity. */
    public static JsonObject toJson(ClientExecutionLimits limits) {
        JsonObject value = new JsonObject();
        value.addProperty("max_hierarchy_objects", limits.maxHierarchyObjects());
        value.addProperty("max_hierarchy_geometry_vertices", limits.maxHierarchyGeometryVertices());
        value.addProperty("max_hierarchy_measurements", limits.maxHierarchyMeasurements());
        value.addProperty("max_hierarchy_text_utf8_bytes", limits.maxHierarchyTextUtf8Bytes());
        value.addProperty("max_generation_store_utf8_bytes", limits.maxGenerationStoreUtf8Bytes());
        value.addProperty("max_metric_values_per_region", limits.maxMetricValuesPerRegion());
        value.addProperty("max_metric_values_per_image", limits.maxMetricValuesPerImage());
        value.addProperty("max_local_source_artifact_bytes", limits.maxLocalSourceArtifactBytes());
        return value;
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }
}
