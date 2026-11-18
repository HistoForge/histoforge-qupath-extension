package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.modules.ModuleSnapshot;

/** Immutable, fully normalized input to one image's staged recipe execution. */
public record ImageExecutionPlan(
        // User-action UUID shared by every image in the originating batch.
        String actionId,
        // Exact extension, QuPath, runtime, and resource-limit identity captured for the action.
        BuildIdentityCodec.ClientBuildIdentity clientBuild,
        // Stable project-image identity on which the action operates.
        String imageId,
        // Exact executable module snapshot captured before planning.
        ModuleSnapshot module,
        // Closed user intent from which this plan was derived.
        ActionSelectionCodec.ImageAction action,
        // Immutable logical regions whose geometry and plane remain fixed through commit.
        List<ExecutionScopeCodec.TargetRegion> targetRegions,
        // Closure-expanded model work in recipe order; empty only for Metrics.
        List<PlannedModelStep> modelsInRecipeOrder,
        // Regions whose post-action state requires metrics, when the module defines metrics.
        Optional<ActionSelectionCodec.RegionSelection> metricSelection,
        // Full original selection, retained when this plan executes only a subset of its targets.
        List<ExecutionScopeCodec.TargetRegion> selectionRegions) {

    public ImageExecutionPlan(
            String actionId,
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            String imageId,
            ModuleSnapshot module,
            ActionSelectionCodec.ImageAction action,
            List<ExecutionScopeCodec.TargetRegion> targetRegions,
            List<PlannedModelStep> modelsInRecipeOrder,
            Optional<ActionSelectionCodec.RegionSelection> metricSelection) {
        this(
                actionId,
                clientBuild,
                imageId,
                module,
                action,
                targetRegions,
                modelsInRecipeOrder,
                metricSelection,
                targetRegions);
    }

    public ImageExecutionPlan withSelectionRegions(
            List<ExecutionScopeCodec.TargetRegion> selection) {
        return new ImageExecutionPlan(
                actionId,
                clientBuild,
                imageId,
                module,
                action,
                targetRegions,
                modelsInRecipeOrder,
                metricSelection,
                selection);
    }

    public ImageExecutionPlan {
        actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
        clientBuild = Objects.requireNonNull(clientBuild, "clientBuild");
        imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
        module = Objects.requireNonNull(module, "module");
        action = Objects.requireNonNull(action, "action");
        targetRegions = normalizedRegions(targetRegions);
        selectionRegions = normalizedRegions(selectionRegions);
        if (!selectionRegions.containsAll(targetRegions)) {
            throw new IllegalArgumentException(
                    "Executed targets must belong to the captured selection");
        }
        modelsInRecipeOrder = normalizedModels(modelsInRecipeOrder);
        metricSelection = Objects.requireNonNull(metricSelection, "metricSelection");

        boolean metricsOnly = action instanceof ActionSelectionCodec.Metrics;
        if (metricsOnly != modelsInRecipeOrder.isEmpty()) {
            throw new IllegalArgumentException("only Metrics may have no planned model steps");
        }
        if (metricsOnly && metricSelection.isEmpty()) {
            throw new IllegalArgumentException("Metrics requires a metric region selection");
        }
        if (!metricsOnly && (module.metrics().isPresent() != metricSelection.isPresent())) {
            throw new IllegalArgumentException(
                    "model-action metric selection must match module metric availability");
        }
    }

    /** One closure-expanded model and its normalized target selectors. */
    public record PlannedModelStep(String stepId, ActionSelectionCodec.TargetSelection targets) {
        public PlannedModelStep {
            stepId = ModuleContract.requireModelId(stepId, "step_id");
            targets = Objects.requireNonNull(targets, "targets");
        }
    }

    private static List<ExecutionScopeCodec.TargetRegion> normalizedRegions(
            List<ExecutionScopeCodec.TargetRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException("targetRegions must be nonempty");
        }
        List<ExecutionScopeCodec.TargetRegion> result = new ArrayList<>(regions.size());
        Set<ExecutionScopeCodec.RegionKey> keys = new HashSet<>();
        for (ExecutionScopeCodec.TargetRegion region : regions) {
            ExecutionScopeCodec.TargetRegion value =
                    Objects.requireNonNull(region, "target region");
            if (!keys.add(value.key())) {
                throw new IllegalArgumentException("targetRegions must have unique region keys");
            }
            result.add(value);
        }
        result.sort(
                (left, right) ->
                        java.util.Arrays.compareUnsigned(
                                CanonicalCodec.canonicalBytes(
                                        ExecutionScopeCodec.toJson(left.key())),
                                CanonicalCodec.canonicalBytes(
                                        ExecutionScopeCodec.toJson(right.key()))));
        return List.copyOf(result);
    }

    private static List<PlannedModelStep> normalizedModels(List<PlannedModelStep> models) {
        Objects.requireNonNull(models, "modelsInRecipeOrder");
        List<PlannedModelStep> result = new ArrayList<>(models.size());
        Set<String> stepIds = new HashSet<>();
        for (PlannedModelStep model : models) {
            PlannedModelStep value = Objects.requireNonNull(model, "planned model");
            if (!stepIds.add(value.stepId())) {
                throw new IllegalArgumentException("planned model step IDs must be unique");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }
}
