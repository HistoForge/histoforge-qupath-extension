package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourceInputPlanner;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CropModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.FieldModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.ParentCandidate;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SelectedModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.TargetWork;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Maps one captured recipe/action and current target populations into calibrated source work. */
public final class RecipeSourcePlanner {

    private RecipeSourcePlanner() {}

    /**
     * Build the source-input plan consumed by finite and recoverable recipe execution.
     *
     * <p>This method owns recipe selection, capability projection, and current-parent snapshots.
     * {@link SourceInputPlanner} remains the sole owner of source level, envelope, target grid,
     * transform, representation, and finite-versus-recoverable geometry.
     */
    public static SourceInputPlan plan(
            ImageExecutionPlan imagePlan,
            CapturedSourcePyramid source,
            PathObjectHierarchy hierarchy,
            Optional<EligibleOriginalSource> eligibleOriginal,
            SourcePlanningLimits limits) {
        ImageExecutionPlan action = Objects.requireNonNull(imagePlan, "imagePlan");
        CapturedSourcePyramid capturedSource = Objects.requireNonNull(source, "source");
        PathObjectHierarchy currentHierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(eligibleOriginal, "eligibleOriginal");
        Objects.requireNonNull(limits, "limits");
        if (action.action() instanceof ActionSelectionCodec.Metrics) {
            throw new IllegalArgumentException("Metrics actions do not plan model source inputs");
        }
        if (!capturedSource
                .sourceRevision()
                .imageDataRevision()
                .imageId()
                .equals(action.imageId())) {
            throw new IllegalArgumentException("captured source belongs to another project image");
        }

        SourceInputPlan sourcePlan =
                SourceInputPlanner.plan(
                        capturedSource,
                        targetWork(action, currentHierarchy),
                        eligibleOriginal,
                        limits);
        requireKnownInputSupport(action, currentHierarchy, sourcePlan);
        return sourcePlan;
    }

    /** Replan one recoverable action against its exact already-published source representation. */
    public static SourceInputPlan planAgainstPublishedSource(
            ImageExecutionPlan imagePlan,
            CapturedSourcePyramid source,
            PathObjectHierarchy hierarchy,
            SourceArtifactCodec.SourceArtifactStartRequest startRequest) {
        ImageExecutionPlan action = Objects.requireNonNull(imagePlan, "imagePlan");
        CapturedSourcePyramid capturedSource = Objects.requireNonNull(source, "source");
        PathObjectHierarchy currentHierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        if (action.action() instanceof ActionSelectionCodec.Metrics) {
            throw new IllegalArgumentException("Metrics actions do not plan model source inputs");
        }
        if (!capturedSource
                .sourceRevision()
                .imageDataRevision()
                .imageId()
                .equals(action.imageId())) {
            throw new IllegalArgumentException("captured source belongs to another project image");
        }
        return SourceInputPlanner.planAgainstPublishedSource(
                capturedSource,
                targetWork(action, currentHierarchy),
                Objects.requireNonNull(startRequest, "startRequest"));
    }

    /** Continue preparation using the representation captured for the original image action. */
    public static SourceInputPlan planAgainstCapturedSource(
            ImageExecutionPlan action,
            CapturedSourcePyramid source,
            PathObjectHierarchy hierarchy,
            SourceInputPlan.SourceArtifactPlan artifact) {
        if (!source.sourceRevision().imageDataRevision().imageId().equals(action.imageId())) {
            throw new IllegalArgumentException("captured source belongs to another project image");
        }
        return SourceInputPlanner.planAgainstCapturedSource(
                source, targetWork(action, hierarchy), artifact);
    }

    /**
     * Reject unsupported, exactly known field inputs before exporting or uploading pixels.
     * Parent-derived crops are checked at dispatch, once their actual outlines are available.
     */
    private static void requireKnownInputSupport(
            ImageExecutionPlan action, PathObjectHierarchy hierarchy, SourceInputPlan sourcePlan) {
        OutputIndex present = OutputIndex.fromHierarchy(hierarchy);
        for (var target : sourcePlan.targets()) {
            if (target.sourcePlan().isEmpty()) continue;
            for (var input : target.sourcePlan().orElseThrow().fieldInputs()) {
                if (action.action() instanceof ActionSelectionCodec.Fill
                        && present.contains(input.output())) continue;
                String modelId = input.output().producer().stepId();
                var model = requiredActivatedModel(action.module(), modelId);
                var raster = input.frame().transform().raster();
                if (ExecutionLimitsCodec.fitsTargetRaster(
                        raster, model.capabilities().finite().targetRaster())) continue;
                if (model.largeRegionProfile().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Model "
                                    + modelId
                                    + " requires a "
                                    + raster.width()
                                    + " × "
                                    + raster.height()
                                    + " model-pixel input, but this backend "
                                    + "does not advertise large-region inference for it. "
                                    + "Use a smaller ROI or a backend that supports this model's "
                                    + "large-region inference. No image was uploaded.");
                }
                var limits =
                        model.capabilities()
                                .durableRun()
                                .orElseThrow()
                                .run()
                                .targetRasterPerInput();
                if (!ExecutionLimitsCodec.fitsTargetRaster(raster, limits)) {
                    throw new IllegalArgumentException(
                            "Model "
                                    + modelId
                                    + " requires a "
                                    + raster.width()
                                    + " × "
                                    + raster.height()
                                    + " model-pixel input, but this backend supports "
                                    + "at most "
                                    + limits.maxWidth()
                                    + " × "
                                    + limits.maxHeight()
                                    + " model pixels ("
                                    + limits.maxPixels()
                                    + " pixels total). "
                                    + "Use a smaller ROI. No image was uploaded.");
                }
            }
        }
    }

    private static List<TargetWork> targetWork(
            ImageExecutionPlan action, PathObjectHierarchy currentHierarchy) {

        Map<String, ModuleRecipe.Step> recipeModels = recipeModels(action.module().recipe());
        Map<String, ImageExecutionPlan.PlannedModelStep> plannedModels =
                RecipeScopeResolver.plannedModels(action);
        List<TargetWork> work = new ArrayList<>(action.targetRegions().size());
        for (ExecutionScopeCodec.TargetRegion target : action.targetRegions()) {
            StagedRegionView current =
                    StagedRegionView.capture(target, currentHierarchy, action.selectionRegions());
            Set<String> selectedModelIds = selectedModelIds(plannedModels, target.key());
            List<SelectedModel> selected = new ArrayList<>();
            for (ImageExecutionPlan.PlannedModelStep planned : action.modelsInRecipeOrder()) {
                RecipeScopeResolver.TargetSelection selection =
                        RecipeScopeResolver.selectionForTarget(planned.targets(), target.key());
                if (selection.empty()) {
                    continue;
                }
                ModuleRecipe.Step step = requiredRecipeStep(recipeModels, planned.stepId());
                ModuleSnapshot.ModelSnapshot model =
                        requiredActivatedModel(action.module(), planned.stepId());
                InferenceIdentityCodec.ModelStepIdentity producer =
                        producer(action.module(), planned.stepId());
                boolean durable =
                        model.capabilities()
                                .modes()
                                .contains(RunnerCapabilitiesCodec.InferenceMode.DURABLE_MODEL_RUN);
                if (!step.isRegionGated()) {
                    if (!selection.broad()) {
                        throw new IllegalArgumentException(
                                "field model source work requires a region target");
                    }
                    selected.add(
                            new FieldModel(
                                    producer,
                                    model.input().targetMpp(),
                                    model.input().fieldProfile(),
                                    model.capabilities().finite().targetRaster(),
                                    durable,
                                    model.capabilities().sourceContainers()));
                    continue;
                }

                selected.add(
                        new CropModel(
                                producer,
                                model.input().targetMpp(),
                                model.capabilities().finite().targetRaster(),
                                durable,
                                model.capabilities().sourceContainers(),
                                model.input().framing().orElseThrow().paddingModelPx(),
                                producer(action.module(), step.parentModel()),
                                Set.copyOf(step.parentClass()),
                                RecipePopulationPolicy.reachableScriptManualClassesBeforeModel(
                                        action.module(), step.model(), selectedModelIds),
                                selection.broad()
                                        && discoversParentsAcrossRegion(
                                                plannedModels, step.parentModel(), target.key()),
                                currentParents(current, selection)));
            }
            if (selected.isEmpty()) {
                throw new IllegalArgumentException(
                        "captured target has no selected model source work");
            }
            work.add(new TargetWork(target, selected));
        }
        return List.copyOf(work);
    }

    private static List<ParentCandidate> currentParents(
            StagedRegionView current, RecipeScopeResolver.TargetSelection selection) {
        List<PathObject> objects =
                selection.broad()
                        ? current.objects()
                        : selection.parentIds().stream()
                                .sorted()
                                .map(current::object)
                                .flatMap(Optional::stream)
                                .toList();
        List<ParentCandidate> result = new ArrayList<>();
        for (PathObject object : objects) {
            if (RoiRegistry.isAdministrativeRoi(object)) {
                continue;
            }
            if (object.getROI() == null || !object.getROI().isArea()) {
                if (!selection.broad()) {
                    throw new IllegalArgumentException(
                            "Point results cannot be used as area crop inputs.");
                }
                continue;
            }
            Optional<String> pathClass =
                    Optional.ofNullable(object.getPathClass()).map(Object::toString);
            Optional<InferenceIdentityCodec.ModelStepIdentity> attributedProducer =
                    object.hasMetadata()
                            ? ProvenanceCodec.readMetadata(object.getMetadata())
                                    .map(value -> value.output().producer())
                            : Optional.empty();
            result.add(
                    new ParentCandidate(
                            pathClass,
                            attributedProducer,
                            NativeRoiGeometry.convert(object.getROI())));
        }
        return List.copyOf(result);
    }

    private static boolean discoversParentsAcrossRegion(
            Map<String, ImageExecutionPlan.PlannedModelStep> plannedModels,
            String parentModel,
            ExecutionScopeCodec.RegionKey target) {
        ImageExecutionPlan.PlannedModelStep plannedParent = plannedModels.get(parentModel);
        return plannedParent != null
                && RecipeScopeResolver.selectionForTarget(plannedParent.targets(), target).broad();
    }

    private static Set<String> selectedModelIds(
            Map<String, ImageExecutionPlan.PlannedModelStep> plannedModels,
            ExecutionScopeCodec.RegionKey target) {
        Set<String> result = new LinkedHashSet<>();
        for (ImageExecutionPlan.PlannedModelStep planned : plannedModels.values()) {
            if (!RecipeScopeResolver.selectionForTarget(planned.targets(), target).empty()) {
                result.add(planned.stepId());
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, ModuleRecipe.Step> recipeModels(ModuleRecipe recipe) {
        Map<String, ModuleRecipe.Step> result = new LinkedHashMap<>();
        for (ModuleRecipe.Step step : recipe.steps()) {
            if (step.isModelStep() && result.putIfAbsent(step.model(), step) != null) {
                throw new IllegalArgumentException(
                        "captured recipe repeats model step " + step.model());
            }
        }
        return Map.copyOf(result);
    }

    private static ModuleRecipe.Step requiredRecipeStep(
            Map<String, ModuleRecipe.Step> recipeModels, String modelId) {
        ModuleRecipe.Step step = recipeModels.get(modelId);
        if (step == null) {
            throw new IllegalArgumentException(
                    "planned model is absent from the captured recipe: " + modelId);
        }
        return step;
    }

    private static ModuleSnapshot.ModelSnapshot requiredActivatedModel(
            ModuleSnapshot module, String modelId) {
        ModuleSnapshot.ModelSnapshot model = module.activatedModels().get(modelId);
        if (model == null) {
            throw new IllegalArgumentException(
                    "planned model is absent from the executable snapshot: " + modelId);
        }
        return model;
    }

    private static InferenceIdentityCodec.ModelStepIdentity producer(
            ModuleSnapshot module, String modelId) {
        return new InferenceIdentityCodec.ModelStepIdentity(module.module().moduleRef(), modelId);
    }
}
