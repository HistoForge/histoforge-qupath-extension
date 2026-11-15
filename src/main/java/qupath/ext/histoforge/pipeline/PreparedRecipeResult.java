package qupath.ext.histoforge.pipeline;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import qupath.ext.histoforge.large.LargeActionHandleStore.ResumableAction;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;

/** One completed recipe, consumed once after checking the freshly reopened image. */
final class PreparedRecipeResult implements LargeActionCommitCoordinator.TransactionPreparation {

    private final ResumableAction action;
    private final LargeActionRecipePlan originalPlan;
    private final List<RunResults.ScopeWorkResult> outcomes;
    private final Clock clock;
    private RecipeStaging.Target staged;

    PreparedRecipeResult(
            ResumableAction action,
            ImageExecutionPlan executionPlan,
            SourceInputPlan sourcePlan,
            RecipeStaging.Target staged,
            Clock clock) {
        this.action = Objects.requireNonNull(action, "action");
        this.originalPlan = new LargeActionRecipePlan(executionPlan, sourcePlan);
        this.staged = Objects.requireNonNull(staged, "staged");
        this.outcomes = staged.outcomes();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    List<RunResults.ScopeWorkResult> outcomes() {
        return outcomes;
    }

    @Override
    public void requireAction(ResumableAction current) {
        if (!action.equals(current)) {
            throw new IllegalStateException(
                    "prepared recipe belongs to another retained action state");
        }
    }

    @Override
    public HierarchyDelta stageModelDelta(
            ImageLifecycle.WorkingImage working, ModelOutputCommitter committer) {
        return stageModelDelta(working, committer, working.sourcePyramid());
    }

    @Override
    public HierarchyDelta stageModelDelta(
            ImageLifecycle.WorkingImage working,
            ModelOutputCommitter committer,
            CapturedSourcePyramid planningSource) {
        if (staged == null) {
            throw new IllegalStateException("prepared recipe has already been consumed");
        }
        var originalRevision =
                originalPlan
                        .source()
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .sourceRevision();
        var currentPlan =
                LargeActionRecipePlan.capture(action, working, planningSource, originalRevision);
        if (!originalPlan.equals(currentPlan)) {
            throw new LargeActionTransactionValidator.Conflict(
                    "fresh recipe or pixel-reading plan differs from the prepared result");
        }

        // Never retain the original working image or reader. Transfer candidates to the delta so
        // this owner cannot keep a second population alive through QuPath's public save.
        RecipeStaging.Target completed = staged;
        staged = null;
        return RecipeExecutor.stageDelta(
                originalPlan.execution(), committer, completed, clock.instant());
    }
}
