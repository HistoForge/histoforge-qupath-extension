package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;

/** Dataset-owned training task and model-field wire contracts. */
public final class TrainingTaskCodec {

    public static final String VARIABLE_FIELD_PLANNER = "variable-field-v2";
    public static final int VARIABLE_FIELD_OUTER_ARTIFICIAL_PADDING_MODEL_PX = 8;

    private static final Set<String> TASK_FIELDS =
            Set.of(
                    "capability_id",
                    "input_kind",
                    "model_output",
                    "supports_negative_rois",
                    "model_microns_per_pixel",
                    "annotation_class_map");
    private static final Set<String> FIXED_FIELD_FIELDS =
            Set.of(
                    "planner",
                    "side_model_px",
                    "alignment_model_px",
                    "small_roi_context",
                    "tile_overlap",
                    "aggregation",
                    "preprocessing");
    private static final Set<String> VARIABLE_FIELD_FIELDS =
            Set.of(
                    "planner",
                    "minSideModelPx",
                    "maxSideModelPx",
                    "preferredTileSideModelPx",
                    "alignmentModelPx",
                    "outerArtificialPaddingModelPx",
                    "smallRoiContext",
                    "tileOverlap",
                    "aggregation",
                    "preprocessing");
    private static final Set<String> INTEGER_RANGE_FIELDS = Set.of("minimum", "maximum");
    private static final Set<String> INPUT_KINDS = Set.of("field", "crop");

    private TrainingTaskCodec() {}

    /** Immutable label and raster interpretation captured into one dataset. */
    public record TrainingTask(
            String capabilityId,
            String inputKind,
            ModelOutput modelOutput,
            boolean supportsNegativeRois,
            double modelMicronsPerPixel,
            Map<String, String> annotationClassMap) {
        public TrainingTask {
            modelOutput = Objects.requireNonNull(modelOutput, "modelOutput");
            annotationClassMap = Map.copyOf(annotationClassMap);
            for (var entry : annotationClassMap.entrySet()) {
                CanonicalCodec.requirePathClassName(entry.getKey(), "annotation_class_map source");
                CanonicalCodec.requirePathClassName(
                        entry.getValue(), "annotation_class_map target");
                if (!modelOutput.targetClasses().contains(entry.getValue())) {
                    throw new IllegalArgumentException(
                            "annotation_class_map target is not a training class");
                }
            }
        }

        public String outputType() {
            return modelOutput.type();
        }

        public List<String> targetClasses() {
            return modelOutput.targetClasses();
        }
    }

    /** Closed fixed or variable model-field planner. */
    public sealed interface TrainingFieldProfile permits FixedFieldProfile, VariableFieldProfile {}

    /** Historical one-side model-field contract. */
    public record FixedFieldProfile(
            int sideModelPx,
            int alignmentModelPx,
            boolean smallRoiContext,
            double tileOverlap,
            String aggregation,
            String preprocessing)
            implements TrainingFieldProfile {}

    /** Module-owned field range and preferred tiling side. */
    public record VariableFieldProfile(
            int minSideModelPx,
            int maxSideModelPx,
            int preferredTileSideModelPx,
            int alignmentModelPx,
            int outerArtificialPaddingModelPx,
            boolean smallRoiContext,
            double tileOverlap,
            String aggregation,
            String preprocessing)
            implements TrainingFieldProfile {}

    /** Inclusive nonnegative integer range advertised by the backend. */
    public record ClosedIntegerRange(long minimum, long maximum) {}

    public static TrainingTask parseTask(JsonElement value) {
        JsonObject object = TrainingJson.exactObject(value, TASK_FIELDS, "training task");
        return new TrainingTask(
                TrainingJson.stableId(object.get("capability_id"), "capability_id"),
                TrainingJson.enumValue(object.get("input_kind"), INPUT_KINDS, "input_kind"),
                ModelOutputCodec.parse(object.get("model_output"), "model_output"),
                TrainingJson.bool(object.get("supports_negative_rois"), "supports_negative_rois"),
                CanonicalCodec.requirePositiveFiniteDouble(
                        object.get("model_microns_per_pixel"), "model_microns_per_pixel"),
                parseClassMap(object.get("annotation_class_map")));
    }

    private static Map<String, String> parseClassMap(JsonElement value) {
        var object = TrainingJson.object(value, "annotation_class_map");
        var mapping = new LinkedHashMap<String, String>();
        for (String source : object.keySet()) {
            mapping.put(source, TrainingJson.string(object, source));
        }
        return mapping;
    }

    public static TrainingFieldProfile parseFieldProfile(JsonElement value) {
        JsonObject object = TrainingJson.object(value, "training field profile");
        String planner = TrainingJson.string(object, "planner");
        return switch (planner) {
            case "fixed-field-v1" -> parseFixedField(object);
            case VARIABLE_FIELD_PLANNER -> parseVariableField(object);
            default ->
                    throw new IllegalArgumentException(
                            "unsupported training field planner: " + planner);
        };
    }

    public static ClosedIntegerRange parseClosedIntegerRange(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, INTEGER_RANGE_FIELDS, "closed integer range");
        long minimum =
                CanonicalCodec.requireNonnegativeSafeInteger(object.get("minimum"), "minimum");
        long maximum =
                CanonicalCodec.requireNonnegativeSafeInteger(object.get("maximum"), "maximum");
        if (maximum < minimum) {
            throw new IllegalArgumentException("closed integer range maximum is below minimum");
        }
        return new ClosedIntegerRange(minimum, maximum);
    }

    public static JsonObject toJson(TrainingTask value) {
        return encodeTask(parseTask(encodeTask(value)));
    }

    public static JsonObject toJson(TrainingFieldProfile value) {
        return encodeFieldProfile(parseFieldProfile(encodeFieldProfile(value)));
    }

    public static JsonObject toJson(ClosedIntegerRange value) {
        return encodeClosedIntegerRange(parseClosedIntegerRange(encodeClosedIntegerRange(value)));
    }

    private static FixedFieldProfile parseFixedField(JsonObject object) {
        TrainingJson.requireFields(object, FIXED_FIELD_FIELDS, "fixed field profile");
        int side =
                CanonicalCodec.requirePositiveInt32(object.get("side_model_px"), "side_model_px");
        int alignment =
                CanonicalCodec.requirePositiveInt32(
                        object.get("alignment_model_px"), "alignment_model_px");
        if (side % alignment != 0) {
            throw new IllegalArgumentException(
                    "side_model_px must be divisible by alignment_model_px");
        }
        return new FixedFieldProfile(
                side,
                alignment,
                TrainingJson.bool(object.get("small_roi_context"), "small_roi_context"),
                overlap(object.get("tile_overlap"), "tile_overlap"),
                TrainingJson.stableId(object.get("aggregation"), "aggregation"),
                TrainingJson.stableId(object.get("preprocessing"), "preprocessing"));
    }

    private static VariableFieldProfile parseVariableField(JsonObject object) {
        TrainingJson.requireFields(object, VARIABLE_FIELD_FIELDS, "variable field profile");
        int minimum =
                CanonicalCodec.requirePositiveInt32(object.get("minSideModelPx"), "minSideModelPx");
        int maximum =
                CanonicalCodec.requirePositiveInt32(object.get("maxSideModelPx"), "maxSideModelPx");
        int preferred =
                CanonicalCodec.requirePositiveInt32(
                        object.get("preferredTileSideModelPx"), "preferredTileSideModelPx");
        int alignment =
                CanonicalCodec.requirePositiveInt32(
                        object.get("alignmentModelPx"), "alignmentModelPx");
        int outerPadding =
                CanonicalCodec.requirePositiveInt32(
                        object.get("outerArtificialPaddingModelPx"),
                        "outerArtificialPaddingModelPx");
        if (outerPadding != VARIABLE_FIELD_OUTER_ARTIFICIAL_PADDING_MODEL_PX) {
            throw new IllegalArgumentException(
                    "outerArtificialPaddingModelPx must equal "
                            + VARIABLE_FIELD_OUTER_ARTIFICIAL_PADDING_MODEL_PX);
        }
        if (minimum % alignment != 0 || maximum % alignment != 0 || preferred % alignment != 0) {
            throw new IllegalArgumentException(
                    "variable field sides must be divisible by alignmentModelPx");
        }
        if (!(minimum <= preferred && preferred <= maximum)) {
            throw new IllegalArgumentException(
                    "variable field sides must satisfy minimum <= preferred <= maximum");
        }
        return new VariableFieldProfile(
                minimum,
                maximum,
                preferred,
                alignment,
                outerPadding,
                TrainingJson.bool(object.get("smallRoiContext"), "smallRoiContext"),
                overlap(object.get("tileOverlap"), "tileOverlap"),
                TrainingJson.stableId(object.get("aggregation"), "aggregation"),
                TrainingJson.stableId(object.get("preprocessing"), "preprocessing"));
    }

    private static double overlap(JsonElement value, String path) {
        double result = CanonicalCodec.requireFiniteDouble(value, path);
        if (result < 0.0 || result >= 1.0) {
            throw new IllegalArgumentException(path + " must be in [0, 1)");
        }
        return result;
    }

    private static JsonObject encodeTask(TrainingTask value) {
        Objects.requireNonNull(value, "training task");
        JsonObject result = new JsonObject();
        result.addProperty("capability_id", value.capabilityId());
        result.addProperty("input_kind", value.inputKind());
        result.add("model_output", ModelOutputCodec.toJson(value.modelOutput()));
        result.addProperty("supports_negative_rois", value.supportsNegativeRois());
        result.addProperty("model_microns_per_pixel", value.modelMicronsPerPixel());
        var mapping = new JsonObject();
        value.annotationClassMap().forEach(mapping::addProperty);
        result.add("annotation_class_map", mapping);
        return result;
    }

    private static JsonObject encodeFieldProfile(TrainingFieldProfile value) {
        Objects.requireNonNull(value, "training field profile");
        JsonObject result = new JsonObject();
        if (value instanceof FixedFieldProfile fixed) {
            result.addProperty("planner", "fixed-field-v1");
            result.addProperty("side_model_px", fixed.sideModelPx());
            result.addProperty("alignment_model_px", fixed.alignmentModelPx());
            result.addProperty("small_roi_context", fixed.smallRoiContext());
            result.addProperty("tile_overlap", fixed.tileOverlap());
            result.addProperty("aggregation", fixed.aggregation());
            result.addProperty("preprocessing", fixed.preprocessing());
        } else if (value instanceof VariableFieldProfile variable) {
            result.addProperty("planner", VARIABLE_FIELD_PLANNER);
            result.addProperty("minSideModelPx", variable.minSideModelPx());
            result.addProperty("maxSideModelPx", variable.maxSideModelPx());
            result.addProperty("preferredTileSideModelPx", variable.preferredTileSideModelPx());
            result.addProperty("alignmentModelPx", variable.alignmentModelPx());
            result.addProperty(
                    "outerArtificialPaddingModelPx", variable.outerArtificialPaddingModelPx());
            result.addProperty("smallRoiContext", variable.smallRoiContext());
            result.addProperty("tileOverlap", variable.tileOverlap());
            result.addProperty("aggregation", variable.aggregation());
            result.addProperty("preprocessing", variable.preprocessing());
        } else {
            throw new IllegalArgumentException("unsupported training field profile");
        }
        return result;
    }

    private static JsonObject encodeClosedIntegerRange(ClosedIntegerRange value) {
        Objects.requireNonNull(value, "closed integer range");
        JsonObject result = new JsonObject();
        result.addProperty("minimum", value.minimum());
        result.addProperty("maximum", value.maximum());
        return result;
    }
}
