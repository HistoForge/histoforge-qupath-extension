package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAugmentationSelection;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingSettings;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingSplitCodec.TrainingSplitPlan;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingFieldProfile;

/** Immutable training-run request wire contract and replay digest. */
public final class TrainingRunRequestCodec {

    private static final Set<String> REQUEST_FIELDS =
            Set.of(
                    "schema_version",
                    "dataset_id",
                    "dataset_digest",
                    "source_module",
                    "selected_model_id",
                    "result_form",
                    "result_display_name",
                    "result_inference_gpu",
                    "split_plan",
                    "field_profile",
                    "augmentation",
                    "trainer_settings",
                    "training_seed",
                    "class_initialization");
    private static final Set<String> RESULT_FORMS =
            Set.of("selected_model_only", "complete_source_workflow");

    private TrainingRunRequestCodec() {}

    /** Exact user choices and immutable source identities accepted for one run. */
    public record TrainingRunRequest(
            String datasetId,
            String datasetDigest,
            ModuleContentIdentity sourceModule,
            String selectedModelId,
            String resultForm,
            String resultDisplayName,
            String resultInferenceGpu,
            TrainingSplitPlan splitPlan,
            TrainingFieldProfile fieldProfile,
            Integer minInstancePixels,
            TrainingAugmentationSelection augmentation,
            TrainingSettings trainerSettings,
            long trainingSeed,
            Initialization classInitialization) {}

    public static TrainingRunRequest parse(JsonElement value, TrainingApiLimits limits) {
        Objects.requireNonNull(limits, "training API limits");
        JsonObject object =
                TrainingJson.objectWithOptional(
                        value,
                        REQUEST_FIELDS,
                        Set.of("min_instance_pixels"),
                        "training run request");
        if (CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version") != 1) {
            throw new IllegalArgumentException("unsupported training run request schema_version");
        }
        return new TrainingRunRequest(
                CanonicalCodec.requireCanonicalUuid(
                        TrainingJson.string(object, "dataset_id"), "dataset_id"),
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "dataset_digest"), "dataset_digest"),
                InferenceIdentityCodec.parseModuleContentIdentity(object.get("source_module")),
                TrainingJson.modelId(object.get("selected_model_id"), "selected_model_id"),
                TrainingJson.enumValue(object.get("result_form"), RESULT_FORMS, "result_form"),
                requireResultDisplayName(
                        TrainingJson.string(object, "result_display_name"), limits),
                TrainingJson.stableId(object.get("result_inference_gpu"), "result_inference_gpu"),
                TrainingSplitCodec.parsePlanSyntax(object.get("split_plan")),
                TrainingTaskCodec.parseFieldProfile(object.get("field_profile")),
                parseInstanceThreshold(object),
                TrainingCapabilityCodec.parseAugmentationSelectionSyntax(
                        object.get("augmentation")),
                TrainingCapabilityCodec.parseSettings(object.get("trainer_settings")),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("training_seed"), "training_seed"),
                Initialization.parse(TrainingJson.string(object, "class_initialization")));
    }

    public static JsonObject toJson(TrainingRunRequest value, TrainingApiLimits limits) {
        return encode(parse(encode(value), limits));
    }

    public static String digest(TrainingRunRequest value, TrainingApiLimits limits) {
        return CanonicalCodec.canonicalDigest(toJson(value, limits));
    }

    /** Validate the editable result name before expensive dataset preparation starts. */
    public static String requireResultDisplayName(String value, TrainingApiLimits limits) {
        return TrainingJson.text(
                new JsonPrimitive(Objects.requireNonNull(value, "result name")),
                "Result name",
                limits.report().maxTextBytes());
    }

    /** Validate one concrete recipe-owned default for the trained result model. */
    public static String requireResultInferenceGpu(String value) {
        return TrainingJson.stableId(
                new JsonPrimitive(Objects.requireNonNull(value, "result inference GPU")),
                "result_inference_gpu");
    }

    /** Absence is dense-only; zero is a real instance threshold, never a sentinel. */
    public static Integer requireInstanceThreshold(Integer value, String outputType) {
        if ("dense_classification".equals(outputType)) {
            if (value != null) {
                throw new IllegalArgumentException(
                        "Dense classification has no minimum object area.");
            }
            return null;
        }
        if (!"instance_segmentation".equals(outputType)
                && !"instance_segmentation_and_classification".equals(outputType)) {
            throw new IllegalArgumentException("Training does not support this model output.");
        }
        if (value == null) {
            throw new IllegalArgumentException("Instance training requires minimum object area.");
        }
        return CanonicalCodec.requireNonnegativeInt32(value, "min_instance_pixels");
    }

    static Integer parseInstanceThreshold(JsonObject object) {
        return object.has("min_instance_pixels")
                ? CanonicalCodec.requireNonnegativeInt32(
                        object.get("min_instance_pixels"), "min_instance_pixels")
                : null;
    }

    private static JsonObject encode(TrainingRunRequest value) {
        Objects.requireNonNull(value, "training run request");
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", 1);
        result.addProperty("dataset_id", value.datasetId());
        result.addProperty("dataset_digest", value.datasetDigest());
        result.add("source_module", InferenceIdentityCodec.toJson(value.sourceModule()));
        result.addProperty("selected_model_id", value.selectedModelId());
        result.addProperty("result_form", value.resultForm());
        result.addProperty("result_display_name", value.resultDisplayName());
        result.addProperty("result_inference_gpu", value.resultInferenceGpu());
        result.add("split_plan", TrainingSplitCodec.toJson(value.splitPlan()));
        result.add("field_profile", TrainingTaskCodec.toJson(value.fieldProfile()));
        if (value.minInstancePixels() != null) {
            result.addProperty("min_instance_pixels", value.minInstancePixels());
        }
        result.add("augmentation", TrainingCapabilityCodec.toJson(value.augmentation()));
        result.add("trainer_settings", TrainingCapabilityCodec.toJson(value.trainerSettings()));
        result.addProperty("training_seed", value.trainingSeed());
        result.addProperty("class_initialization", value.classInitialization().wireValue());
        return result;
    }
}
