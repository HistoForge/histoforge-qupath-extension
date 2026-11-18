package qupath.ext.histoforge.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.ResumableAction;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;

/** Recipe and pixel-reading plans recaptured from the current authoritative image. */
record LargeActionRecipePlan(ImageExecutionPlan execution, SourceInputPlan source) {

    LargeActionRecipePlan {
        execution = Objects.requireNonNull(execution, "execution");
        source = Objects.requireNonNull(source, "source");
    }

    static LargeActionRecipePlan capture(
            ResumableAction action, ImageLifecycle.WorkingImage working) {
        return capture(
                action,
                working,
                planningSourcePyramid(working.sourcePyramid(), action.authorizedInputs())
                        .sourceRevision());
    }

    static LargeActionRecipePlan capture(
            ResumableAction action,
            ImageLifecycle.WorkingImage working,
            RasterInputCodec.ActionSourceRevision originalRevision) {
        return capture(action, working, working.sourcePyramid(), originalRevision);
    }

    static LargeActionRecipePlan capture(
            ResumableAction action,
            ImageLifecycle.WorkingImage working,
            CapturedSourcePyramid planningSource) {
        return capture(
                action,
                working,
                planningSource,
                planningSourcePyramid(planningSource, action.authorizedInputs()).sourceRevision());
    }

    static LargeActionRecipePlan capture(
            ResumableAction action,
            ImageLifecycle.WorkingImage working,
            CapturedSourcePyramid planningSource,
            RasterInputCodec.ActionSourceRevision originalRevision) {
        var handle = action.handle();
        ImageExecutionPlan execution =
                ImageActionPlanner.plan(
                                handle.actionId(),
                                handle.clientBuild(),
                                handle.imageId(),
                                handle.module(),
                                handle.action(),
                                working.imageData().getHierarchy(),
                                working.imageData().getServer().getWidth(),
                                working.imageData().getServer().getHeight())
                        .withSelectionRegions(handle.selectionRegions());
        if (!execution.targetRegions().equals(List.of(handle.target()))) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh recipe plan does not retain the captured large-action target");
        }
        CapturedSourcePyramid pyramid = withOriginalRevision(planningSource, originalRevision);
        try {
            return new LargeActionRecipePlan(
                    execution,
                    RecipeSourcePlanner.planAgainstCapturedSource(
                            execution,
                            pyramid,
                            working.imageData().getHierarchy(),
                            handle.sourcePlan()));
        } catch (IllegalArgumentException mismatch) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh source plan differs from the retained large action", mismatch);
        }
    }

    /** Keep the recorded image revision while inspecting the current reader's level mappings. */
    static CapturedSourcePyramid planningSourcePyramid(
            CapturedSourcePyramid current,
            Map<String, LargeActionHandleStore.AuthorizedModelInput> authorizedInputs) {
        RasterInputCodec.ActionSourceRevision original = null;
        for (var input : authorizedInputs.values()) {
            var revision = input.input().rasterInput().frame().sourceRevision();
            if (original == null) {
                original = revision;
            } else if (!original.equals(revision)) {
                throw new LargeActionTransactionValidator.Conflict(
                        "retained model inputs use different source revisions");
            }
        }
        return withOriginalRevision(
                current, original == null ? current.sourceRevision() : original);
    }

    private static CapturedSourcePyramid withOriginalRevision(
            CapturedSourcePyramid current, RasterInputCodec.ActionSourceRevision original) {
        if (!original.pixelSource().equals(current.sourceRevision().pixelSource())) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh pixel source differs from the captured recipe source");
        }
        // Outside-target edits may change the image revision. Reader mappings are checked through
        // the source plan; the coordinator separately checks every object the recipe could use.
        return new CapturedSourcePyramid(
                original,
                current.seriesIndex(),
                current.levelZeroPixelsToSlide(),
                current.levels());
    }
}
