package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingTaskCodec.ClosedIntegerRange;
import qupath.ext.histoforge.contract.TrainingTaskCodec.FixedFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.modules.ModuleContract;

/** Curated model-training capability and availability wire contracts. */
public final class TrainingCapabilityCodec {

    private static final Set<String> GPU_FIELDS = Set.of("training_gpu_id", "display_name");
    private static final Set<String> SETTINGS_FIELDS =
            Set.of(
                    "training_gpu_id",
                    "training_batch_size",
                    "data_loading_workers",
                    "gradient_accumulation_steps",
                    "total_epochs",
                    "warmup_mode",
                    "warmup_epochs");
    private static final Set<String> AUTOMATIC_WARMUP_FIELDS = Set.of("divisor", "maximum_epochs");
    private static final Set<String> OPERATOR_FIELDS =
            Set.of("operator_id", "display_name", "description", "default_enabled");
    private static final Set<String> PROFILE_FIELDS =
            Set.of("profile_id", "display_name", "description", "operators");
    private static final Set<String> SELECTION_FIELDS =
            Set.of("profile_id", "enabled_operator_ids");
    private static final Set<String> CAPABILITY_FIELDS =
            Set.of(
                    "capability_id",
                    "task_display_name",
                    "input_kind",
                    "output_type",
                    "supports_negative_rois",
                    "crop_padding_model_px",
                    "default_field_profile",
                    "allowed_field_side_model_px",
                    "small_roi_context_editable",
                    "augmentation_profiles",
                    "default_augmentation_profile_id",
                    "training_gpus",
                    "total_epochs_range",
                    "automatic_warmup",
                    "training_batch_size_range",
                    "data_loading_workers_range",
                    "gradient_accumulation_steps_range",
                    "default_training_settings");
    private static final Set<String> AVAILABLE_FIELDS = Set.of("type", "options");
    private static final Set<String> OPTION_FIELDS = Set.of("capability", "class_initializations");
    private static final Set<String> UNAVAILABLE_FIELDS = Set.of("type", "code", "message");
    private static final Set<String> INPUT_KINDS = Set.of("field", "crop");
    private static final Set<String> WARMUP_MODES = Set.of("auto", "custom");
    public static final String PIXEL_FLOW_WEIGHTING = "pixel";
    public static final String EQUAL_INSTANCE_FLOW_WEIGHTING = "equal_instance";
    private static final Set<String> FLOW_LOSS_WEIGHTINGS =
            Set.of(PIXEL_FLOW_WEIGHTING, EQUAL_INSTANCE_FLOW_WEIGHTING);
    private static final Set<String> UNAVAILABLE_CODES =
            Set.of("trainer_not_deployed", "unsupported_model_configuration");

    private TrainingCapabilityCodec() {}

    /** One backend-controlled training GPU choice. */
    public record TrainingGpuChoice(String trainingGpuId, String displayName) {}

    /** User-visible and run-owned training settings. */
    public record TrainingSettings(
            String trainingGpuId,
            int trainingBatchSize,
            long dataLoadingWorkers,
            int gradientAccumulationSteps,
            int totalEpochs,
            String warmupMode,
            int warmupEpochs,
            String flowLossWeighting,
            Boolean oversample) {}

    /** Exact backend-owned rule used when warmup mode is Auto. */
    public record AutomaticWarmupPolicy(int divisor, int maximumEpochs) {}

    /** One independently selectable augmentation operator. */
    public record TrainingAugmentationOperatorChoice(
            String operatorId, String displayName, String description, boolean defaultEnabled) {}

    /** One stain or modality augmentation profile. */
    public record TrainingAugmentationProfileChoice(
            String profileId,
            String displayName,
            String description,
            List<TrainingAugmentationOperatorChoice> operators) {
        public TrainingAugmentationProfileChoice {
            operators = List.copyOf(operators);
        }
    }

    /** Exact profile and ordered enabled operators selected for one run. */
    public record TrainingAugmentationSelection(String profileId, List<String> enabledOperatorIds) {
        public TrainingAugmentationSelection {
            enabledOperatorIds = List.copyOf(enabledOperatorIds);
        }
    }

    /** Complete client-visible capability for one installed model descriptor. */
    public record ModelTrainingCapability(
            String capabilityId,
            String taskDisplayName,
            String inputKind,
            String outputType,
            boolean supportsNegativeRois,
            int cropPaddingModelPx,
            TrainingFieldProfile defaultFieldProfile,
            ClosedIntegerRange allowedFieldSideModelPx,
            boolean smallRoiContextEditable,
            List<TrainingAugmentationProfileChoice> augmentationProfiles,
            String defaultAugmentationProfileId,
            List<TrainingGpuChoice> trainingGpus,
            ClosedIntegerRange totalEpochsRange,
            AutomaticWarmupPolicy automaticWarmup,
            ClosedIntegerRange trainingBatchSizeRange,
            ClosedIntegerRange dataLoadingWorkersRange,
            ClosedIntegerRange gradientAccumulationStepsRange,
            List<String> flowLossWeightingOptions,
            TrainingSettings defaultTrainingSettings) {
        public ModelTrainingCapability {
            augmentationProfiles = List.copyOf(augmentationProfiles);
            trainingGpus = List.copyOf(trainingGpus);
            flowLossWeightingOptions = List.copyOf(flowLossWeightingOptions);
        }
    }

    /** Available or explicitly unavailable training support for one model. */
    public sealed interface TrainingAvailability permits TrainingAvailable, TrainingUnavailable {}

    public record TrainingOption(
            ModelTrainingCapability capability, List<Initialization> classInitializations) {
        public TrainingOption {
            capability = Objects.requireNonNull(capability, "capability");
            classInitializations = List.copyOf(classInitializations);
        }
    }

    public record TrainingAvailable(List<TrainingOption> options) implements TrainingAvailability {
        public TrainingAvailable {
            options = List.copyOf(options);
        }
    }

    public record TrainingUnavailable(String code, String message)
            implements TrainingAvailability {}

    public static ModelTrainingCapability parseCapability(JsonElement value, long maxTextBytes) {
        JsonObject object =
                TrainingJson.objectWithOptional(
                        value,
                        CAPABILITY_FIELDS,
                        Set.of("flow_loss_weighting_options"),
                        "model training capability");
        String outputType =
                ModuleContract.requireOutputType(
                        TrainingJson.string(object, "output_type"), "output_type");
        String inputKind =
                TrainingJson.enumValue(object.get("input_kind"), INPUT_KINDS, "input_kind");
        int cropPaddingModelPx =
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("crop_padding_model_px"), "crop_padding_model_px");
        if ("field".equals(inputKind) && cropPaddingModelPx != 0) {
            throw new IllegalArgumentException("field input must use zero crop_padding_model_px");
        }
        TrainingFieldProfile field =
                TrainingTaskCodec.parseFieldProfile(object.get("default_field_profile"));
        ClosedIntegerRange allowed =
                TrainingTaskCodec.parseClosedIntegerRange(
                        object.get("allowed_field_side_model_px"));
        boolean contextEditable =
                TrainingJson.bool(
                        object.get("small_roi_context_editable"), "small_roi_context_editable");
        validateFieldCapability(inputKind, field, allowed, contextEditable);

        List<TrainingAugmentationProfileChoice> augmentationProfiles =
                parseProfiles(object.get("augmentation_profiles"), maxTextBytes);
        if (augmentationProfiles.size() != 1) {
            throw new IllegalArgumentException(
                    "augmentation_profiles must contain exactly one profile");
        }
        String defaultProfile =
                TrainingJson.stableId(
                        object.get("default_augmentation_profile_id"),
                        "default_augmentation_profile_id");
        if (augmentationProfiles.stream()
                .noneMatch(profile -> profile.profileId().equals(defaultProfile))) {
            throw new IllegalArgumentException("default_augmentation_profile_id is not advertised");
        }

        List<TrainingGpuChoice> gpus = parseGpus(object.get("training_gpus"), maxTextBytes);
        ClosedIntegerRange epochRange =
                TrainingTaskCodec.parseClosedIntegerRange(object.get("total_epochs_range"));
        if (epochRange.minimum() < 1) {
            throw new IllegalArgumentException("total epochs must start at one or greater");
        }
        AutomaticWarmupPolicy automaticWarmup =
                parseAutomaticWarmup(object.get("automatic_warmup"));
        ClosedIntegerRange batchRange =
                TrainingTaskCodec.parseClosedIntegerRange(object.get("training_batch_size_range"));
        ClosedIntegerRange workerRange =
                TrainingTaskCodec.parseClosedIntegerRange(object.get("data_loading_workers_range"));
        ClosedIntegerRange accumulationRange =
                TrainingTaskCodec.parseClosedIntegerRange(
                        object.get("gradient_accumulation_steps_range"));
        if (batchRange.minimum() < 1 || accumulationRange.minimum() < 1) {
            throw new IllegalArgumentException(
                    "batch size and gradient accumulation must start at one or greater");
        }
        List<String> flowOptions = new ArrayList<>();
        if (object.has("flow_loss_weighting_options")) {
            for (JsonElement option :
                    TrainingJson.nonemptyArray(
                            object.get("flow_loss_weighting_options"),
                            "flow_loss_weighting_options")) {
                flowOptions.add(
                        TrainingJson.enumValue(
                                option, FLOW_LOSS_WEIGHTINGS, "flow_loss_weighting"));
            }
            if (flowOptions.size() != new HashSet<>(flowOptions).size()) {
                throw new IllegalArgumentException("flow_loss_weighting_options must be unique");
            }
            if ("dense_classification".equals(outputType)) {
                throw new IllegalArgumentException(
                        "dense classification cannot advertise flow weighting");
            }
        }
        TrainingSettings settings = parseSettings(object.get("default_training_settings"));
        requireSettingsApplicable(settings, outputType);
        validateSettingsRanges(
                settings,
                gpus,
                epochRange,
                automaticWarmup,
                batchRange,
                workerRange,
                accumulationRange,
                flowOptions);

        return new ModelTrainingCapability(
                TrainingJson.stableId(object.get("capability_id"), "capability_id"),
                TrainingJson.text(
                        object.get("task_display_name"), "task_display_name", maxTextBytes),
                inputKind,
                outputType,
                TrainingJson.bool(object.get("supports_negative_rois"), "supports_negative_rois"),
                cropPaddingModelPx,
                field,
                allowed,
                contextEditable,
                augmentationProfiles,
                defaultProfile,
                gpus,
                epochRange,
                automaticWarmup,
                batchRange,
                workerRange,
                accumulationRange,
                flowOptions,
                settings);
    }

    public static TrainingAvailability parseAvailability(JsonElement value, long maxTextBytes) {
        JsonObject object = TrainingJson.object(value, "training availability");
        String type = TrainingJson.string(object, "type");
        return switch (type) {
            case "available" -> {
                TrainingJson.requireFields(object, AVAILABLE_FIELDS, "training availability");
                var options = new ArrayList<TrainingOption>();
                var ids = new HashSet<String>();
                var outputs = new HashSet<String>();
                for (var valueOption :
                        TrainingJson.nonemptyArray(object.get("options"), "training options")) {
                    var option =
                            TrainingJson.exactObject(valueOption, OPTION_FIELDS, "training option");
                    var capability = parseCapability(option.get("capability"), maxTextBytes);
                    var modes =
                            TrainingJson.stringArray(
                                            option.get("class_initializations"),
                                            "class_initializations")
                                    .stream()
                                    .map(Initialization::parse)
                                    .toList();
                    if (modes.isEmpty() || new HashSet<>(modes).size() != modes.size()) {
                        throw new IllegalArgumentException(
                                "class_initializations must be nonempty and unique");
                    }
                    if (!ids.add(capability.capabilityId())
                            || !outputs.add(capability.outputType())) {
                        throw new IllegalArgumentException(
                                "training options must have unique IDs and target tasks");
                    }
                    options.add(new TrainingOption(capability, modes));
                }
                yield new TrainingAvailable(options);
            }
            case "unavailable" -> {
                TrainingJson.requireFields(object, UNAVAILABLE_FIELDS, "training availability");
                yield new TrainingUnavailable(
                        TrainingJson.enumValue(object.get("code"), UNAVAILABLE_CODES, "code"),
                        TrainingJson.text(object.get("message"), "message", maxTextBytes));
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported training availability type: " + type);
        };
    }

    public static TrainingSettings parseSettings(JsonElement value) {
        JsonObject object =
                TrainingJson.objectWithOptional(
                        value,
                        SETTINGS_FIELDS,
                        Set.of("flow_loss_weighting", "oversample"),
                        "training settings");
        int totalEpochs =
                CanonicalCodec.requirePositiveInt32(object.get("total_epochs"), "total_epochs");
        int warmupEpochs =
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("warmup_epochs"), "warmup_epochs");
        if (warmupEpochs >= totalEpochs) {
            throw new IllegalArgumentException("warmup_epochs must be less than total_epochs");
        }
        return new TrainingSettings(
                TrainingJson.stableId(object.get("training_gpu_id"), "training_gpu_id"),
                CanonicalCodec.requirePositiveInt32(
                        object.get("training_batch_size"), "training_batch_size"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("data_loading_workers"), "data_loading_workers"),
                CanonicalCodec.requirePositiveInt32(
                        object.get("gradient_accumulation_steps"), "gradient_accumulation_steps"),
                totalEpochs,
                TrainingJson.enumValue(object.get("warmup_mode"), WARMUP_MODES, "warmup_mode"),
                warmupEpochs,
                object.has("flow_loss_weighting")
                        ? TrainingJson.enumValue(
                                object.get("flow_loss_weighting"),
                                FLOW_LOSS_WEIGHTINGS,
                                "flow_loss_weighting")
                        : null,
                object.has("oversample")
                        ? TrainingJson.bool(object.get("oversample"), "oversample")
                        : null);
    }

    public static AutomaticWarmupPolicy parseAutomaticWarmup(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, AUTOMATIC_WARMUP_FIELDS, "automatic warmup");
        return new AutomaticWarmupPolicy(
                CanonicalCodec.requirePositiveInt32(object.get("divisor"), "divisor"),
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("maximum_epochs"), "maximum_epochs"));
    }

    public static int automaticWarmupEpochs(int totalEpochs, AutomaticWarmupPolicy policy) {
        if (totalEpochs < 1) {
            throw new IllegalArgumentException("total_epochs must be positive");
        }
        AutomaticWarmupPolicy resolved = parseAutomaticWarmup(encodeAutomaticWarmup(policy));
        return Math.min(resolved.maximumEpochs(), totalEpochs / resolved.divisor());
    }

    /** Admit user resource choices against the selected model's advertised ranges. */
    public static void requireSettingsAllowed(
            TrainingSettings value, ModelTrainingCapability capability) {
        TrainingSettings settings = parseSettings(encodeSettings(value));
        if ((settings.oversample() != null)
                != (capability.defaultTrainingSettings().oversample() != null)) {
            throw new IllegalArgumentException(
                    "oversample presence must match the advertised setting");
        }
        validateSettingsRanges(
                settings,
                capability.trainingGpus(),
                capability.totalEpochsRange(),
                capability.automaticWarmup(),
                capability.trainingBatchSizeRange(),
                capability.dataLoadingWorkersRange(),
                capability.gradientAccumulationStepsRange(),
                capability.flowLossWeightingOptions());
    }

    /** Validate task applicability without consulting a later capability catalog. */
    public static void requireSettingsApplicable(TrainingSettings settings, String outputType) {
        if (settings.oversample() != null
                && !"instance_segmentation_and_classification".equals(outputType)) {
            throw new IllegalArgumentException("oversample requires instance classification");
        }
    }

    public static TrainingAugmentationSelection parseAugmentationSelection(
            JsonElement value, ModelTrainingCapability capability, long maxTextBytes) {
        TrainingAugmentationSelection selection = parseAugmentationSelectionSyntax(value);
        ModelTrainingCapability resolved =
                parseCapability(encodeCapability(capability), maxTextBytes);
        String profileId = selection.profileId();
        TrainingAugmentationProfileChoice profile =
                resolved.augmentationProfiles().stream()
                        .filter(item -> item.profileId().equals(profileId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "augmentation profile is not advertised"));
        List<String> enabled = selection.enabledOperatorIds();
        List<String> operatorOrder =
                profile.operators().stream()
                        .map(TrainingAugmentationOperatorChoice::operatorId)
                        .toList();
        Set<String> enabledSet = Set.copyOf(enabled);
        List<String> expected = operatorOrder.stream().filter(enabledSet::contains).toList();
        if (!enabled.equals(expected)) {
            throw new IllegalArgumentException(
                    "enabled operators must be unique and follow profile order");
        }
        if (!new HashSet<>(operatorOrder).containsAll(enabled)) {
            throw new IllegalArgumentException("enabled operator is not advertised by the profile");
        }
        return new TrainingAugmentationSelection(profileId, enabled);
    }

    /** Parse a frozen selection without consulting a later capability build. */
    public static TrainingAugmentationSelection parseAugmentationSelectionSyntax(
            JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(
                        value, SELECTION_FIELDS, "training augmentation selection");
        List<String> enabled =
                TrainingJson.stringArray(object.get("enabled_operator_ids"), "enabled_operator_ids")
                        .stream()
                        .map(
                                item ->
                                        TrainingJson.stableId(
                                                new com.google.gson.JsonPrimitive(item),
                                                "enabled_operator_ids item"))
                        .toList();
        if (enabled.size() != new HashSet<>(enabled).size()) {
            throw new IllegalArgumentException("enabled_operator_ids must be unique");
        }
        return new TrainingAugmentationSelection(
                TrainingJson.stableId(object.get("profile_id"), "profile_id"), enabled);
    }

    public static TrainingTaskCodec.TrainingTask taskFromCapability(
            ModelTrainingCapability capability,
            ModelOutput modelOutput,
            double modelMicronsPerPixel,
            long maxTextBytes) {
        return taskFromCapability(
                capability,
                modelOutput,
                modelMicronsPerPixel,
                modelOutput.targetClasses().stream()
                        .collect(java.util.stream.Collectors.toMap(name -> name, name -> name)),
                maxTextBytes);
    }

    public static TrainingTaskCodec.TrainingTask taskFromCapability(
            ModelTrainingCapability capability,
            ModelOutput modelOutput,
            double modelMicronsPerPixel,
            Map<String, String> annotationClassMap,
            long maxTextBytes) {
        ModelTrainingCapability resolved =
                parseCapability(encodeCapability(capability), maxTextBytes);
        ModelOutput output = ModelOutputCodec.parse(ModelOutputCodec.toJson(modelOutput));
        if (!output.type().equals(resolved.outputType())) {
            throw new IllegalArgumentException(
                    "model output type differs from the training capability");
        }
        return TrainingTaskCodec.parseTask(
                encodeTaskFromCapability(
                        resolved, output, modelMicronsPerPixel, annotationClassMap));
    }

    /** Require one captured task to match every compatibility field owned by a capability. */
    public static void requireTaskMatchesCapability(
            TrainingTaskCodec.TrainingTask task, ModelTrainingCapability capability) {
        TrainingTaskCodec.TrainingTask selected =
                TrainingTaskCodec.parseTask(
                        TrainingTaskCodec.toJson(Objects.requireNonNull(task, "task")));
        ModelTrainingCapability advertised =
                Objects.requireNonNull(capability, "model training capability");
        if (!selected.capabilityId().equals(advertised.capabilityId())
                || !selected.inputKind().equals(advertised.inputKind())
                || !selected.modelOutput().type().equals(advertised.outputType())
                || selected.supportsNegativeRois() != advertised.supportsNegativeRois()) {
            throw new IllegalArgumentException(
                    "training task does not match the selected model capability");
        }
    }

    public static JsonObject toJson(ModelTrainingCapability value, long maxTextBytes) {
        return encodeCapability(parseCapability(encodeCapability(value), maxTextBytes));
    }

    public static JsonObject toJson(TrainingAvailability value, long maxTextBytes) {
        return encodeAvailability(parseAvailability(encodeAvailability(value), maxTextBytes));
    }

    public static JsonObject toJson(TrainingSettings value) {
        return encodeSettings(parseSettings(encodeSettings(value)));
    }

    public static JsonObject toJson(
            TrainingAugmentationSelection value,
            ModelTrainingCapability capability,
            long maxTextBytes) {
        return encodeSelection(
                parseAugmentationSelection(encodeSelection(value), capability, maxTextBytes));
    }

    public static JsonObject toJson(TrainingAugmentationSelection value) {
        return encodeSelection(parseAugmentationSelectionSyntax(encodeSelection(value)));
    }

    private static List<TrainingAugmentationProfileChoice> parseProfiles(
            JsonElement value, long maxTextBytes) {
        JsonArray array = TrainingJson.nonemptyArray(value, "augmentation_profiles");
        List<TrainingAugmentationProfileChoice> result = new ArrayList<>();
        Set<String> profileIds = new HashSet<>();
        for (JsonElement profileValue : array) {
            JsonObject object =
                    TrainingJson.exactObject(profileValue, PROFILE_FIELDS, "augmentation profile");
            String profileId = TrainingJson.stableId(object.get("profile_id"), "profile_id");
            if (!profileIds.add(profileId)) {
                throw new IllegalArgumentException(
                        "augmentation_profiles contains duplicate profile IDs");
            }
            List<TrainingAugmentationOperatorChoice> operators =
                    parseOperators(object.get("operators"), maxTextBytes);
            result.add(
                    new TrainingAugmentationProfileChoice(
                            profileId,
                            TrainingJson.text(
                                    object.get("display_name"), "display_name", maxTextBytes),
                            TrainingJson.text(
                                    object.get("description"), "description", maxTextBytes),
                            operators));
        }
        return List.copyOf(result);
    }

    private static List<TrainingAugmentationOperatorChoice> parseOperators(
            JsonElement value, long maxTextBytes) {
        JsonArray array = TrainingJson.array(value, "operators");
        List<TrainingAugmentationOperatorChoice> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement operatorValue : array) {
            JsonObject object =
                    TrainingJson.exactObject(
                            operatorValue, OPERATOR_FIELDS, "augmentation operator");
            String id = TrainingJson.stableId(object.get("operator_id"), "operator_id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException(
                        "augmentation profile contains duplicate operator IDs");
            }
            result.add(
                    new TrainingAugmentationOperatorChoice(
                            id,
                            TrainingJson.text(
                                    object.get("display_name"), "display_name", maxTextBytes),
                            TrainingJson.text(
                                    object.get("description"), "description", maxTextBytes),
                            TrainingJson.bool(object.get("default_enabled"), "default_enabled")));
        }
        return List.copyOf(result);
    }

    private static List<TrainingGpuChoice> parseGpus(JsonElement value, long maxTextBytes) {
        JsonArray array = TrainingJson.nonemptyArray(value, "training_gpus");
        List<TrainingGpuChoice> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement gpuValue : array) {
            JsonObject object = TrainingJson.exactObject(gpuValue, GPU_FIELDS, "training GPU");
            String id = TrainingJson.stableId(object.get("training_gpu_id"), "training_gpu_id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("training_gpus contains duplicate IDs");
            }
            result.add(
                    new TrainingGpuChoice(
                            id,
                            TrainingJson.text(
                                    object.get("display_name"), "display_name", maxTextBytes)));
        }
        return List.copyOf(result);
    }

    private static void validateFieldCapability(
            String inputKind,
            TrainingFieldProfile profile,
            ClosedIntegerRange allowed,
            boolean editable) {
        boolean context =
                profile instanceof FixedFieldProfile fixed
                        ? fixed.smallRoiContext()
                        : ((VariableFieldProfile) profile).smallRoiContext();
        if ("crop".equals(inputKind) && (context || editable)) {
            throw new IllegalArgumentException(
                    "crop input cannot enable editable small-ROI context");
        }
        if (profile instanceof FixedFieldProfile fixed) {
            if (allowed.minimum() != fixed.sideModelPx()
                    || allowed.maximum() != fixed.sideModelPx()
                    || editable) {
                throw new IllegalArgumentException(
                        "fixed field capability must expose one immutable side");
            }
            return;
        }
        VariableFieldProfile variable = (VariableFieldProfile) profile;
        for (long side :
                List.of(
                        (long) variable.minSideModelPx(),
                        (long) variable.preferredTileSideModelPx(),
                        (long) variable.maxSideModelPx())) {
            if (side < allowed.minimum() || side > allowed.maximum()) {
                throw new IllegalArgumentException(
                        "default variable field sides are outside the allowed range");
            }
        }
    }

    private static void validateSettingsRanges(
            TrainingSettings settings,
            List<TrainingGpuChoice> gpus,
            ClosedIntegerRange epochRange,
            AutomaticWarmupPolicy automaticWarmup,
            ClosedIntegerRange batchRange,
            ClosedIntegerRange workerRange,
            ClosedIntegerRange accumulationRange,
            List<String> flowOptions) {
        if (!flowOptions.isEmpty()) {
            if (settings.flowLossWeighting() == null
                    || !flowOptions.contains(settings.flowLossWeighting())) {
                throw new IllegalArgumentException(
                        "flow_loss_weighting must name an advertised option");
            }
        } else if (settings.flowLossWeighting() != null) {
            throw new IllegalArgumentException("flow_loss_weighting is not advertised");
        }
        if (gpus.stream().noneMatch(gpu -> gpu.trainingGpuId().equals(settings.trainingGpuId()))) {
            throw new IllegalArgumentException("Training GPU is not advertised");
        }
        requireInRange(settings.totalEpochs(), epochRange, "total_epochs");
        if ("auto".equals(settings.warmupMode())
                && settings.warmupEpochs()
                        != automaticWarmupEpochs(settings.totalEpochs(), automaticWarmup)) {
            throw new IllegalArgumentException(
                    "automatic warmup does not match the advertised policy");
        }
        requireInRange(settings.trainingBatchSize(), batchRange, "training_batch_size");
        requireInRange(settings.dataLoadingWorkers(), workerRange, "data_loading_workers");
        requireInRange(
                settings.gradientAccumulationSteps(),
                accumulationRange,
                "gradient_accumulation_steps");
    }

    private static void requireInRange(long value, ClosedIntegerRange range, String path) {
        if (value < range.minimum() || value > range.maximum()) {
            throw new IllegalArgumentException(
                    path + " must be between " + range.minimum() + " and " + range.maximum());
        }
    }

    private static JsonObject encodeCapability(ModelTrainingCapability value) {
        Objects.requireNonNull(value, "model training capability");
        JsonObject result = new JsonObject();
        result.addProperty("capability_id", value.capabilityId());
        result.addProperty("task_display_name", value.taskDisplayName());
        result.addProperty("input_kind", value.inputKind());
        result.addProperty("output_type", value.outputType());
        result.addProperty("supports_negative_rois", value.supportsNegativeRois());
        result.addProperty("crop_padding_model_px", value.cropPaddingModelPx());
        result.add("default_field_profile", TrainingTaskCodec.toJson(value.defaultFieldProfile()));
        result.add(
                "allowed_field_side_model_px",
                TrainingTaskCodec.toJson(value.allowedFieldSideModelPx()));
        result.addProperty("small_roi_context_editable", value.smallRoiContextEditable());
        JsonArray profiles = new JsonArray();
        value.augmentationProfiles().forEach(item -> profiles.add(encodeProfile(item)));
        result.add("augmentation_profiles", profiles);
        result.addProperty("default_augmentation_profile_id", value.defaultAugmentationProfileId());
        JsonArray gpus = new JsonArray();
        value.trainingGpus().forEach(item -> gpus.add(encodeGpu(item)));
        result.add("training_gpus", gpus);
        result.add("total_epochs_range", TrainingTaskCodec.toJson(value.totalEpochsRange()));
        result.add("automatic_warmup", encodeAutomaticWarmup(value.automaticWarmup()));
        result.add(
                "training_batch_size_range",
                TrainingTaskCodec.toJson(value.trainingBatchSizeRange()));
        result.add(
                "data_loading_workers_range",
                TrainingTaskCodec.toJson(value.dataLoadingWorkersRange()));
        result.add(
                "gradient_accumulation_steps_range",
                TrainingTaskCodec.toJson(value.gradientAccumulationStepsRange()));
        result.add("default_training_settings", encodeSettings(value.defaultTrainingSettings()));
        if (!value.flowLossWeightingOptions().isEmpty()) {
            result.add(
                    "flow_loss_weighting_options",
                    TrainingJson.strings(value.flowLossWeightingOptions()));
        }
        return result;
    }

    private static JsonObject encodeAvailability(TrainingAvailability value) {
        Objects.requireNonNull(value, "training availability");
        JsonObject result = new JsonObject();
        if (value instanceof TrainingAvailable available) {
            result.addProperty("type", "available");
            var options = new JsonArray();
            for (var option : available.options()) {
                var object = new JsonObject();
                object.add("capability", encodeCapability(option.capability()));
                object.add(
                        "class_initializations",
                        TrainingJson.strings(
                                option.classInitializations().stream()
                                        .map(Initialization::wireValue)
                                        .toList()));
                options.add(object);
            }
            result.add("options", options);
        } else if (value instanceof TrainingUnavailable unavailable) {
            result.addProperty("type", "unavailable");
            result.addProperty("code", unavailable.code());
            result.addProperty("message", unavailable.message());
        } else {
            throw new IllegalArgumentException("unsupported training availability");
        }
        return result;
    }

    private static JsonObject encodeProfile(TrainingAugmentationProfileChoice value) {
        Objects.requireNonNull(value, "augmentation profile");
        JsonObject result = new JsonObject();
        result.addProperty("profile_id", value.profileId());
        result.addProperty("display_name", value.displayName());
        result.addProperty("description", value.description());
        JsonArray operators = new JsonArray();
        value.operators().forEach(item -> operators.add(encodeOperator(item)));
        result.add("operators", operators);
        return result;
    }

    private static JsonObject encodeOperator(TrainingAugmentationOperatorChoice value) {
        Objects.requireNonNull(value, "augmentation operator");
        JsonObject result = new JsonObject();
        result.addProperty("operator_id", value.operatorId());
        result.addProperty("display_name", value.displayName());
        result.addProperty("description", value.description());
        result.addProperty("default_enabled", value.defaultEnabled());
        return result;
    }

    private static JsonObject encodeGpu(TrainingGpuChoice value) {
        Objects.requireNonNull(value, "training GPU");
        JsonObject result = new JsonObject();
        result.addProperty("training_gpu_id", value.trainingGpuId());
        result.addProperty("display_name", value.displayName());
        return result;
    }

    private static JsonObject encodeSettings(TrainingSettings value) {
        Objects.requireNonNull(value, "training settings");
        JsonObject result = new JsonObject();
        result.addProperty("training_gpu_id", value.trainingGpuId());
        result.addProperty("training_batch_size", value.trainingBatchSize());
        result.addProperty("data_loading_workers", value.dataLoadingWorkers());
        result.addProperty("gradient_accumulation_steps", value.gradientAccumulationSteps());
        result.addProperty("total_epochs", value.totalEpochs());
        result.addProperty("warmup_mode", value.warmupMode());
        result.addProperty("warmup_epochs", value.warmupEpochs());
        if (value.flowLossWeighting() != null) {
            result.addProperty("flow_loss_weighting", value.flowLossWeighting());
        }
        if (value.oversample() != null) {
            result.addProperty("oversample", value.oversample());
        }
        return result;
    }

    private static JsonObject encodeAutomaticWarmup(AutomaticWarmupPolicy value) {
        Objects.requireNonNull(value, "automatic warmup");
        JsonObject result = new JsonObject();
        result.addProperty("divisor", value.divisor());
        result.addProperty("maximum_epochs", value.maximumEpochs());
        return result;
    }

    private static JsonObject encodeSelection(TrainingAugmentationSelection value) {
        Objects.requireNonNull(value, "training augmentation selection");
        JsonObject result = new JsonObject();
        result.addProperty("profile_id", value.profileId());
        result.add("enabled_operator_ids", TrainingJson.strings(value.enabledOperatorIds()));
        return result;
    }

    private static JsonObject encodeTaskFromCapability(
            ModelTrainingCapability value,
            ModelOutput modelOutput,
            double modelMicronsPerPixel,
            Map<String, String> annotationClassMap) {
        JsonObject result = new JsonObject();
        result.addProperty("capability_id", value.capabilityId());
        result.addProperty("input_kind", value.inputKind());
        result.add("model_output", ModelOutputCodec.toJson(modelOutput));
        result.addProperty("supports_negative_rois", value.supportsNegativeRois());
        result.addProperty("model_microns_per_pixel", modelMicronsPerPixel);
        var mapping = new JsonObject();
        annotationClassMap.forEach(mapping::addProperty);
        result.add("annotation_class_map", mapping);
        return result;
    }
}
