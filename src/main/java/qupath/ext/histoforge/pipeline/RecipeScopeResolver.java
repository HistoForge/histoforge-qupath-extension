package qupath.ext.histoforge.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.state.OutputIndex;

/** Pure owner of action selectors and exact Fill/Redo output scopes. */
final class RecipeScopeResolver {

    private RecipeScopeResolver() {}

    static Map<String, ImageExecutionPlan.PlannedModelStep> plannedModels(ImageExecutionPlan plan) {
        Map<String, ImageExecutionPlan.PlannedModelStep> result = new LinkedHashMap<>();
        for (ImageExecutionPlan.PlannedModelStep model : plan.modelsInRecipeOrder()) {
            result.put(model.stepId(), model);
        }
        return Collections.unmodifiableMap(result);
    }

    static Set<ExecutionScopeCodec.OutputKey> replacementClosure(
            ImageExecutionPlan plan,
            ExecutionScopeCodec.TargetRegion target,
            OutputIndex outputIndex,
            Map<InferenceIdentityCodec.ModelStepIdentity, Set<ExecutionScopeCodec.OutputKey>>
                    pendingProducerWideReplacements,
            Map<String, ImageExecutionPlan.PlannedModelStep> plannedModels) {
        if (!(plan.action() instanceof ActionSelectionCodec.Redo)) {
            return new LinkedHashSet<>();
        }
        Set<ExecutionScopeCodec.OutputKey> result = new LinkedHashSet<>();
        Map<String, ModuleRecipe.Step> recipeModels = recipeModels(plan.module().recipe());
        for (ImageExecutionPlan.PlannedModelStep planned : plannedModels.values()) {
            TargetSelection selection = selectionForTarget(planned.targets(), target.key());
            if (selection.empty()) {
                continue;
            }
            InferenceIdentityCodec.ModelStepIdentity producer =
                    new InferenceIdentityCodec.ModelStepIdentity(
                            plan.module().module().moduleRef(), planned.stepId());
            Set<ExecutionScopeCodec.OutputKey> modeReplacements =
                    pendingProducerWideReplacements.get(producer);
            if (modeReplacements != null) {
                result.addAll(modeReplacements);
            }
            ModuleRecipe.Step step = recipeModels.get(planned.stepId());
            if (!step.isRegionGated()) {
                result.add(
                        new ExecutionScopeCodec.OutputKey(
                                producer, new ExecutionScopeCodec.FieldScope(target.key())));
                continue;
            }
            if (selection.broad()) {
                outputIndex.materialization(producer).currentOutputs().stream()
                        .filter(
                                output ->
                                        ExecutionScopeCodec.regionOf(output.scope())
                                                .equals(target.key()))
                        .forEach(result::add);
            }
            for (String parentId : selection.parentIds()) {
                result.add(
                        new ExecutionScopeCodec.OutputKey(
                                producer,
                                new ExecutionScopeCodec.ParentScope(target.key(), parentId)));
            }
        }
        return result;
    }

    static TargetSelection selectionForTarget(
            ActionSelectionCodec.TargetSelection selection, ExecutionScopeCodec.RegionKey target) {
        boolean broad = false;
        Set<String> parents = new LinkedHashSet<>();
        for (ActionSelectionCodec.TargetSelector selector : selection.selectors()) {
            if (selector instanceof ActionSelectionCodec.RoiTarget roi
                    && target instanceof ExecutionScopeCodec.Roi targetRoi
                    && roi.roiId().equals(targetRoi.roiId())) {
                broad = true;
            } else if (selector instanceof ActionSelectionCodec.SlideTarget
                    && target instanceof ExecutionScopeCodec.Slide) {
                broad = true;
            } else if (selector instanceof ActionSelectionCodec.ParentTarget parent
                    && parent.region().equals(target)) {
                parents.add(parent.parentId());
            }
        }
        return new TargetSelection(broad, parents);
    }

    static boolean hasCurrentCrossModeReplacement(
            OutputIndex outputIndex,
            Set<ExecutionScopeCodec.OutputKey> closure,
            InferenceIdentityCodec.ModelStepIdentity producer,
            ExecutionScopeCodec.RegionKey target) {
        return closure.stream()
                .filter(output -> output.producer().equals(producer))
                .filter(output -> !ExecutionScopeCodec.regionOf(output.scope()).equals(target))
                .anyMatch(outputIndex::contains);
    }

    private static Map<String, ModuleRecipe.Step> recipeModels(ModuleRecipe recipe) {
        Map<String, ModuleRecipe.Step> result = new LinkedHashMap<>();
        for (ModuleRecipe.Step step : recipe.steps()) {
            if (step.isModelStep()) {
                result.put(step.model(), step);
            }
        }
        return result;
    }

    record TargetSelection(boolean broad, Set<String> parentIds) {
        TargetSelection {
            parentIds = Set.copyOf(parentIds);
            if (broad && !parentIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "broad target and exact parent targets must be normalized");
            }
        }

        static TargetSelection none() {
            return new TargetSelection(false, Set.of());
        }

        boolean empty() {
            return !broad && parentIds.isEmpty();
        }
    }
}
