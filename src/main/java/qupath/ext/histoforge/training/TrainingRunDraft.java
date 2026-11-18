package qupath.ext.histoforge.training;

import java.util.List;
import java.util.Objects;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.Available;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAugmentationSelection;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAvailable;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingOption;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingSettings;
import qupath.ext.histoforge.contract.TrainingClassSchema;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec.TrainingRunRequest;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.training.TrainingDatasetWorkflow.SealedDataset;

/** User choices frozen before capture. Dataset identity is supplied only after upload seals. */
public record TrainingRunDraft(
        InstalledModule source,
        String modelId,
        TrainingTask task,
        Initialization classInitialization,
        ResultForm resultForm,
        String resultName,
        String resultInferenceGpu,
        TrainingFieldProfile fieldProfile,
        Integer minInstancePixels,
        TrainingAugmentationSelection augmentation,
        TrainingSettings settings) {

    public enum ResultForm {
        COMPLETE_WORKFLOW("complete_source_workflow", "Copy module and replace this model"),
        STANDALONE("selected_model_only", "Create a standalone module with this model");

        private final String wireValue;
        private final String label;

        ResultForm(String wireValue, String label) {
            this.wireValue = wireValue;
            this.label = label;
        }

        public String wireValue() {
            return wireValue;
        }

        /** Return the approved user-facing label for a validated wire value. */
        public static String labelForWireValue(String wireValue) {
            for (ResultForm value : values()) {
                if (value.wireValue.equals(wireValue)) {
                    return value.label;
                }
            }
            throw new IllegalArgumentException("Unknown training result form.");
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public TrainingRunDraft {
        ModelTrainingCapability capability = capability(source, modelId, task, classInitialization);
        var limits = source.gateway().trainingApiLimits();
        if (resultForm == null || !resultForms(source, modelId).contains(resultForm)) {
            throw new IllegalArgumentException("This result cannot run on the selected backend.");
        }
        resultName = TrainingRunRequestCodec.requireResultDisplayName(resultName, limits);
        resultInferenceGpu = TrainingRunRequestCodec.requireResultInferenceGpu(resultInferenceGpu);
        String sourceInferenceGpu = source.recipe().models().get(modelId).gpu();
        if (!resultInferenceGpu.equals(sourceInferenceGpu)
                && !source.gateway().inferenceGpus().contains(resultInferenceGpu)) {
            throw new IllegalArgumentException(
                    "Result inference GPU is not supported by this backend.");
        }
        fieldProfile = requireField(capability, fieldProfile);
        minInstancePixels =
                TrainingRunRequestCodec.requireInstanceThreshold(
                        minInstancePixels, capability.outputType());
        augmentation =
                TrainingCapabilityCodec.parseAugmentationSelection(
                        TrainingCapabilityCodec.toJson(augmentation),
                        capability,
                        limits.report().maxTextBytes());
        TrainingCapabilityCodec.requireSettingsAllowed(settings, capability);
    }

    /** Bind the exact capture and split to this draft; no current form or catalog is consulted. */
    public TrainingRunRequest request(SealedDataset dataset, long seed) {
        if (!dataset.manifest().task().equals(task)) {
            throw new IllegalArgumentException(
                    "Captured data differs from the frozen training target.");
        }
        return new TrainingRunRequest(
                dataset.record().datasetId(),
                dataset.record().datasetDigest(),
                source.descriptor().identity(),
                modelId,
                resultForm.wireValue(),
                resultName,
                resultInferenceGpu,
                dataset.assignment().requirePlan(),
                fieldProfile,
                minInstancePixels,
                augmentation,
                settings,
                seed,
                classInitialization);
    }

    /** Complete workflows require every retained model step to be executable, not trainable. */
    public static List<ResultForm> resultForms(InstalledModule source, String modelId) {
        var recipe = source.recipe();
        var model = recipe.models().get(modelId);
        boolean completeRunnable =
                recipe.steps().stream()
                        .filter(step -> step.isModelStep())
                        .allMatch(
                                step ->
                                        source.descriptor()
                                                        .models()
                                                        .get(step.model())
                                                        .runnerAvailability()
                                                instanceof Available);
        if ("crop".equals(model.input().type())) {
            return completeRunnable ? List.of(ResultForm.COMPLETE_WORKFLOW) : List.of();
        }
        if (!completeRunnable || (recipe.models().size() == 1 && recipe.steps().size() == 1)) {
            return List.of(ResultForm.STANDALONE);
        }
        return List.of(ResultForm.COMPLETE_WORKFLOW, ResultForm.STANDALONE);
    }

    public static List<TrainingOption> options(InstalledModule source, String modelId) {
        var descriptor =
                Objects.requireNonNull(source, "source module").descriptor().models().get(modelId);
        if (descriptor == null
                || !(descriptor.trainingAvailability() instanceof TrainingAvailable available)
                || source.recipe().steps().stream()
                        .noneMatch(step -> modelId.equals(step.model()))) {
            throw new IllegalArgumentException("Select a model available for training.");
        }
        return available.options();
    }

    public static ModelTrainingCapability capability(
            InstalledModule source,
            String modelId,
            TrainingTask task,
            Initialization initialization) {
        TrainingClassSchema.requireTransition(
                source.recipe().models().get(modelId).output(), task.modelOutput(), initialization);
        var capability =
                options(source, modelId).stream()
                        .filter(
                                option ->
                                        option.capability()
                                                        .capabilityId()
                                                        .equals(task.capabilityId())
                                                && option.capability()
                                                        .outputType()
                                                        .equals(task.modelOutput().type())
                                                && option.classInitializations()
                                                        .contains(initialization))
                        .map(TrainingOption::capability)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "This training target is not available on the backend."));
        TrainingCapabilityCodec.requireTaskMatchesCapability(task, capability);
        return capability;
    }

    /** Read the model-owned final instance-area threshold used as the editable run default. */
    public static int sourceMinInstancePixels(InstalledModule source, String modelId) {
        Objects.requireNonNull(source, "source module");
        var model = source.recipe().models().get(modelId);
        if (model == null || model.params() == null) {
            throw new IllegalArgumentException("The selected model has no decoder settings.");
        }
        Object value = model.params().get("min_instance_pixels");
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "The selected model has no minimum detected object area.");
        }
        double exact = number.doubleValue();
        if (!Double.isFinite(exact)
                || exact < 0
                || exact > Integer.MAX_VALUE
                || exact != Math.rint(exact)) {
            throw new IllegalArgumentException(
                    "The selected model has an invalid minimum detected object area.");
        }
        return (int) exact;
    }

    private static TrainingFieldProfile requireField(
            ModelTrainingCapability capability, TrainingFieldProfile profile) {
        if (capability.defaultFieldProfile().equals(profile)) {
            return profile;
        }
        if (!(profile instanceof VariableFieldProfile variable)
                || !(capability.defaultFieldProfile() instanceof VariableFieldProfile)) {
            throw new IllegalArgumentException("This model's view size is fixed.");
        }
        var expected =
                TrainingFieldSizes.resolve(
                        capability,
                        1,
                        TrainingFieldSizes.Unit.MODEL_PIXELS,
                        Integer.toString(variable.minSideModelPx()),
                        Integer.toString(variable.maxSideModelPx()),
                        Integer.toString(variable.preferredTileSideModelPx()),
                        variable.smallRoiContext());
        if (!expected.equals(profile)) {
            throw new IllegalArgumentException("The field profile changes a fixed model setting.");
        }
        return profile;
    }
}
