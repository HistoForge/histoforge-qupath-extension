package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.RecipeDependencyGraph;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Restricts one captured image action to an exact nonempty subset of its logical targets. */
final class ImageActionSubsetPlanner {

    private ImageActionSubsetPlanner() {}

    /** Exact process-local execution and source plan captured for one target subset. */
    record Subset(
            ImageExecutionPlan execution,
            SourceInputPlan sources,
            Map<String, String> boundaryDigests) {
        Subset {
            execution = Objects.requireNonNull(execution, "execution");
            sources = Objects.requireNonNull(sources, "sources");
            boundaryDigests = Map.copyOf(boundaryDigests);
            var expectedBoundaries =
                    execution.targetRegions().stream()
                            .map(ExecutionScopeCodec.TargetRegion::key)
                            .filter(ExecutionScopeCodec.Roi.class::isInstance)
                            .map(ExecutionScopeCodec.Roi.class::cast)
                            .map(ExecutionScopeCodec.Roi::roiId)
                            .collect(java.util.stream.Collectors.toSet());
            if (!boundaryDigests.keySet().equals(expectedBoundaries)) {
                throw new IllegalArgumentException(
                        "Captured boundary state must cover every ROI target");
            }
            if (!sources.targets().stream()
                    .map(SourceInputPlan.TargetPlanningResult::target)
                    .toList()
                    .equals(execution.targetRegions())) {
                throw new IllegalArgumentException(
                        "source-plan targets differ from the captured execution subset");
            }
        }
    }

    /** A target region or selected closure changed after the action-wide capture. */
    static final class CapturedPlanConflict extends RuntimeException {
        CapturedPlanConflict(String message, Throwable cause) {
            super(message, cause);
        }

        CapturedPlanConflict(String message) {
            super(message);
        }
    }

    static Subset subset(
            ImageExecutionPlan imagePlan,
            SourceInputPlan sourcePlan,
            List<ExecutionScopeCodec.TargetRegion> targets,
            PathObjectHierarchy hierarchy,
            int imageWidth,
            int imageHeight) {
        ImageExecutionPlan execution = plan(imagePlan, targets, hierarchy, imageWidth, imageHeight);
        Map<ExecutionScopeCodec.RegionKey, SourceInputPlan.TargetPlanningResult> sources =
                new LinkedHashMap<>();
        Objects.requireNonNull(sourcePlan, "sourcePlan")
                .targets()
                .forEach(target -> sources.put(target.target().key(), target));
        List<SourceInputPlan.TargetPlanningResult> selected = new ArrayList<>();
        for (ExecutionScopeCodec.TargetRegion target : execution.targetRegions()) {
            SourceInputPlan.TargetPlanningResult source = sources.get(target.key());
            if (source == null || !source.target().equals(target)) {
                throw new IllegalArgumentException(
                        "captured execution target is absent from the source plan");
            }
            selected.add(source);
        }
        return new Subset(
                execution,
                new SourceInputPlan(selected),
                boundaryDigests(execution.targetRegions(), hierarchy));
    }

    /** Revalidate immutable action-plan fields against the current target hierarchy. */
    static void requireCurrent(
            Subset subset, PathObjectHierarchy hierarchy, int width, int height) {
        ImageExecutionPlan expected = Objects.requireNonNull(subset, "subset").execution();
        final ImageExecutionPlan current;
        try {
            current =
                    ImageActionPlanner.plan(
                                    expected.actionId(),
                                    expected.clientBuild(),
                                    expected.imageId(),
                                    expected.module(),
                                    expected.action(),
                                    Objects.requireNonNull(hierarchy, "hierarchy"),
                                    width,
                                    height)
                            .withSelectionRegions(expected.selectionRegions());
        } catch (IllegalArgumentException mismatch) {
            throw new CapturedPlanConflict(
                    "captured target plan can no longer be reconstructed", mismatch);
        }
        if (!current.equals(expected)) {
            throw new CapturedPlanConflict(
                    "target geometry or selected closure changed after action capture");
        }
        if (!subset.boundaryDigests()
                .equals(boundaryDigests(expected.targetRegions(), hierarchy))) {
            throw new CapturedPlanConflict(
                    "An annotation boundary changed after capture. Refresh and start a new action.");
        }
    }

    /**
     * Bind an unstarted target to its current image revision after validating its captured plan.
     * Earlier targets may have saved objects into the same image. Their commits change the full
     * image revision, but not this target's pixels, source representation or raster geometry. This
     * is never used to rewrite a retained request during recovery.
     */
    static SourceInputPlan sourcesForNewTarget(
            Subset subset, RasterInputCodec.ActionSourceRevision current) {
        return new SourceInputPlan(
                subset.sources().targets().stream()
                        .map(
                                target ->
                                        new SourceInputPlan.TargetPlanningResult(
                                                target.target(),
                                                target.sourcePlan()
                                                        .map(
                                                                source ->
                                                                        atRevision(
                                                                                source, current))))
                        .toList());
    }

    private static SourceInputPlan.TargetSourcePlan atRevision(
            SourceInputPlan.TargetSourcePlan source,
            RasterInputCodec.ActionSourceRevision current) {
        var captured = source.sourceRevision();
        if (!captured.imageDataRevision().imageId().equals(current.imageDataRevision().imageId())
                || !captured.pixelSource().equals(current.pixelSource())) {
            throw new CapturedPlanConflict("Image or pixel source changed after action capture");
        }
        if (captured.equals(current)) return source;
        var fields =
                source.fieldInputs().stream()
                        .map(
                                input ->
                                        new SourceInputPlan.PlannedModelInput(
                                                input.output(),
                                                input.acceptance(),
                                                RasterInputCodec.buildRasterFrame(
                                                        current,
                                                        input.frame().region(),
                                                        input.frame().transform(),
                                                        input.frame().inclusionMaskRaster()),
                                                input.sourceView()))
                        .toList();
        return new SourceInputPlan.TargetSourcePlan(
                source.target(),
                current,
                source.levelZeroPixelsToSlide(),
                source.protocol(),
                source.sourceLevel(),
                source.sourceEnvelopeAtLevel(),
                source.artifact(),
                fields,
                source.cropInputs());
    }

    private static Map<String, String> boundaryDigests(
            List<ExecutionScopeCodec.TargetRegion> targets, PathObjectHierarchy hierarchy) {
        RoiRegistry registry = RoiRegistry.fromHierarchy(hierarchy);
        Map<String, String> digests = new LinkedHashMap<>();
        for (var target : targets) {
            if (target.key() instanceof ExecutionScopeCodec.Roi roi) {
                var boundary = registry.findRoi(roi.roiId()).orElseThrow();
                // Children added by a completed target do not change this boundary's own state.
                digests.put(
                        roi.roiId(),
                        ImageDataRevisionDigester.objectTransactionSemanticDigest(boundary));
            }
        }
        return Map.copyOf(digests);
    }

    static ImageExecutionPlan plan(
            ImageExecutionPlan imagePlan,
            List<ExecutionScopeCodec.TargetRegion> targets,
            PathObjectHierarchy hierarchy,
            int imageWidth,
            int imageHeight) {
        ImageExecutionPlan source = Objects.requireNonNull(imagePlan, "imagePlan");
        List<ExecutionScopeCodec.TargetRegion> selected = requireCapturedTargets(source, targets);
        Set<ExecutionScopeCodec.RegionKey> keys = new LinkedHashSet<>();
        selected.forEach(target -> keys.add(target.key()));

        ActionSelectionCodec.ImageAction action = actionForTargets(source.action(), keys);
        ModuleSnapshot module = moduleForAction(source.module(), action);
        ImageExecutionPlan result =
                ImageActionPlanner.plan(
                        source.actionId(),
                        source.clientBuild(),
                        source.imageId(),
                        module,
                        action,
                        Objects.requireNonNull(hierarchy, "hierarchy"),
                        imageWidth,
                        imageHeight);
        if (result.targetRegions().size() != selected.size()
                || !new LinkedHashSet<>(result.targetRegions())
                        .equals(new LinkedHashSet<>(selected))) {
            throw new IllegalArgumentException(
                    "target geometry or plane changed before subset planning");
        }
        return result.withSelectionRegions(source.selectionRegions());
    }

    private static List<ExecutionScopeCodec.TargetRegion> requireCapturedTargets(
            ImageExecutionPlan source, List<ExecutionScopeCodec.TargetRegion> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("target subset must be nonempty");
        }
        Map<ExecutionScopeCodec.RegionKey, ExecutionScopeCodec.TargetRegion> captured =
                new LinkedHashMap<>();
        source.targetRegions().forEach(target -> captured.put(target.key(), target));
        List<ExecutionScopeCodec.TargetRegion> result = new ArrayList<>();
        Set<ExecutionScopeCodec.RegionKey> unique = new LinkedHashSet<>();
        for (ExecutionScopeCodec.TargetRegion target : targets) {
            ExecutionScopeCodec.TargetRegion exact = Objects.requireNonNull(target, "target");
            if (!unique.add(exact.key())) {
                throw new IllegalArgumentException("target subset contains duplicate regions");
            }
            ExecutionScopeCodec.TargetRegion current = captured.get(exact.key());
            if (current == null) {
                throw new IllegalArgumentException("target is absent from the captured image plan");
            }
            if (!current.equals(exact)) {
                throw new IllegalArgumentException(
                        "target differs from the captured image-plan region");
            }
            result.add(exact);
        }
        return List.copyOf(result);
    }

    private static ActionSelectionCodec.ImageAction actionForTargets(
            ActionSelectionCodec.ImageAction action, Set<ExecutionScopeCodec.RegionKey> targets) {
        if (action instanceof ActionSelectionCodec.Metrics metrics) {
            return new ActionSelectionCodec.Metrics(subsetRegions(metrics.targets(), targets));
        }
        ActionSelectionCodec.ModelWorkSelection sourceWork = modelWork(action);
        ActionSelectionCodec.RegionSelection regions = subsetRegions(regions(sourceWork), targets);
        final ActionSelectionCodec.ModelWorkSelection targetWork;
        if (sourceWork instanceof ActionSelectionCodec.AllModels) {
            targetWork = new ActionSelectionCodec.AllModels(regions);
        } else {
            List<ActionSelectionCodec.StepTargets> steps = new ArrayList<>();
            for (ActionSelectionCodec.StepTargets step :
                    ((ActionSelectionCodec.SelectedModels) sourceWork).steps()) {
                List<ActionSelectionCodec.TargetSelector> selectors =
                        step.targets().selectors().stream()
                                .filter(selector -> selectorBelongsTo(selector, targets))
                                .toList();
                if (!selectors.isEmpty()) {
                    steps.add(
                            new ActionSelectionCodec.StepTargets(
                                    step.stepId(),
                                    new ActionSelectionCodec.TargetSelection(selectors)));
                }
            }
            if (steps.isEmpty()) {
                throw new IllegalArgumentException(
                        "selected model action contains no work for the target subset");
            }
            targetWork = new ActionSelectionCodec.SelectedModels(regions, steps);
        }
        return action instanceof ActionSelectionCodec.Fill fill
                ? new ActionSelectionCodec.Fill(
                        targetWork, fill.removeOverlappingSameClassDuplicates())
                : new ActionSelectionCodec.Redo(
                        targetWork,
                        ((ActionSelectionCodec.Redo) action)
                                .removeOverlappingSameClassDuplicates());
    }

    static ModuleSnapshot moduleForAction(
            ModuleSnapshot module, ActionSelectionCodec.ImageAction action) {
        List<String> required =
                RecipeDependencyGraph.from(module.recipe()).requiredModelIds(action);
        Map<String, ModuleSnapshot.ModelSnapshot> models = new LinkedHashMap<>();
        for (String modelId : required) {
            ModuleSnapshot.ModelSnapshot model = module.activatedModels().get(modelId);
            if (model == null) {
                throw new IllegalArgumentException(
                        "captured module lacks target-required model " + modelId);
            }
            models.put(modelId, model);
        }
        return new ModuleSnapshot(
                module.deploymentId(),
                module.module(),
                module.exactRecipe(),
                module.scriptSetDigest(),
                models,
                module.scripts(),
                module.metrics());
    }

    private static ActionSelectionCodec.RegionSelection subsetRegions(
            ActionSelectionCodec.RegionSelection regions,
            Set<ExecutionScopeCodec.RegionKey> targets) {
        if (regions instanceof ActionSelectionCodec.RoiRegions rois) {
            Set<String> selected = new LinkedHashSet<>();
            for (ExecutionScopeCodec.RegionKey target : targets) {
                if (!(target instanceof ExecutionScopeCodec.Roi roi)
                        || !rois.roiIds().contains(roi.roiId())) {
                    throw new IllegalArgumentException(
                            "ROI target is absent from the action region selection");
                }
                selected.add(roi.roiId());
            }
            return new ActionSelectionCodec.RoiRegions(selected);
        }
        if (targets.size() != 1
                || !(targets.iterator().next() instanceof ExecutionScopeCodec.Slide)) {
            throw new IllegalArgumentException("slide action cannot be restricted to ROI targets");
        }
        return regions;
    }

    private static boolean selectorBelongsTo(
            ActionSelectionCodec.TargetSelector selector,
            Set<ExecutionScopeCodec.RegionKey> targets) {
        return switch (selector) {
            case ActionSelectionCodec.RoiTarget roi ->
                    targets.contains(new ExecutionScopeCodec.Roi(roi.roiId()));
            case ActionSelectionCodec.ParentTarget parent -> targets.contains(parent.region());
            case ActionSelectionCodec.SlideTarget ignored ->
                    targets.stream().anyMatch(ExecutionScopeCodec.Slide.class::isInstance);
        };
    }

    private static ActionSelectionCodec.ModelWorkSelection modelWork(
            ActionSelectionCodec.ImageAction action) {
        return action instanceof ActionSelectionCodec.Fill fill
                ? fill.work()
                : ((ActionSelectionCodec.Redo) action).work();
    }

    private static ActionSelectionCodec.RegionSelection regions(
            ActionSelectionCodec.ModelWorkSelection work) {
        return work instanceof ActionSelectionCodec.AllModels all
                ? all.regions()
                : ((ActionSelectionCodec.SelectedModels) work).regions();
    }
}
