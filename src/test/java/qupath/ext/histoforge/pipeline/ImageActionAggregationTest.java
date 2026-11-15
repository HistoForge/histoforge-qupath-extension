package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;

class ImageActionAggregationTest {

    @Test
    void savedSuccessFollowedByFailureIsPartialAndRetainsPersistenceTruth() {
        RunResults.ImageRunResult result =
                ImageActionAggregation.reduce(
                        RecipeExecutorTestData.IMAGE_ID,
                        List.of(
                                target(
                                        RecipeExecutorTestData.ROI_A,
                                        image(
                                                RunResults.ImageOutcomeType.SUCCEEDED,
                                                true,
                                                List.of())),
                                target(
                                        RecipeExecutorTestData.ROI_B,
                                        image(
                                                RunResults.ImageOutcomeType.FAILED,
                                                false,
                                                List.of(
                                                        new RunResults.FailureDetail(
                                                                RunResults.FailureType.BACKEND,
                                                                "later target failed"))))),
                        Optional.empty());

        assertEquals(RunResults.ImageOutcomeType.PARTIAL, result.outcome());
        assertTrue(result.hierarchySaved());
        assertEquals(1, result.imageFailures().size());
    }

    @Test
    void cancellationAndConflictRemainClosedWhenNoTargetCompleted() {
        RunResults.ImageRunResult cancelled =
                ImageActionAggregation.reduce(
                        RecipeExecutorTestData.IMAGE_ID,
                        List.of(
                                target(
                                        RecipeExecutorTestData.ROI_A,
                                        image(
                                                RunResults.ImageOutcomeType.CANCELLED,
                                                false,
                                                List.of(
                                                        new RunResults.FailureDetail(
                                                                RunResults.FailureType.CANCELLED,
                                                                "cancelled"))))),
                        Optional.empty());
        RunResults.ImageRunResult conflict =
                ImageActionAggregation.reduce(
                        RecipeExecutorTestData.IMAGE_ID,
                        List.of(
                                target(
                                        RecipeExecutorTestData.ROI_A,
                                        image(
                                                RunResults.ImageOutcomeType.CONFLICT,
                                                false,
                                                List.of(
                                                        new RunResults.FailureDetail(
                                                                RunResults.FailureType
                                                                        .REVISION_CONFLICT,
                                                                "changed"))))),
                        Optional.empty());

        assertEquals(RunResults.ImageOutcomeType.CANCELLED, cancelled.outcome());
        assertEquals(RunResults.ImageOutcomeType.CONFLICT, conflict.outcome());
    }

    @Test
    void successfulMetricsCannotUpgradeCompletelyFailedOrCancelledModelWork() {
        RunResults.ImageRunResult successfulMetrics =
                image(RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE, false, List.of());
        RunResults.ImageRunResult failed =
                ImageActionAggregation.reduce(
                        RecipeExecutorTestData.IMAGE_ID,
                        List.of(
                                target(
                                        RecipeExecutorTestData.ROI_A,
                                        image(
                                                RunResults.ImageOutcomeType.FAILED,
                                                false,
                                                List.of(
                                                        new RunResults.FailureDetail(
                                                                RunResults.FailureType.BACKEND,
                                                                "model failed"))))),
                        Optional.of(successfulMetrics));
        RunResults.ImageRunResult cancelled =
                ImageActionAggregation.reduce(
                        RecipeExecutorTestData.IMAGE_ID,
                        List.of(
                                target(
                                        RecipeExecutorTestData.ROI_A,
                                        image(
                                                RunResults.ImageOutcomeType.CANCELLED,
                                                false,
                                                List.of(
                                                        new RunResults.FailureDetail(
                                                                RunResults.FailureType.CANCELLED,
                                                                "cancelled"))))),
                        Optional.of(successfulMetrics));

        assertEquals(RunResults.ImageOutcomeType.FAILED, failed.outcome());
        assertEquals(RunResults.ImageOutcomeType.CANCELLED, cancelled.outcome());
    }

    @Test
    void independentlyExecutedTargetsCannotDuplicateOneScope() {
        RunResults.ScopeOutcome duplicate =
                new RunResults.ScopeOutcome(
                        new RunResults.RequestedTarget(
                                new InferenceIdentityCodec.ModelStepIdentity(
                                        RecipeExecutorTestData.MODULE, "parent"),
                                new ActionSelectionCodec.RoiTarget(RecipeExecutorTestData.ROI_A)),
                        RunResults.ScopeOutcomeType.SKIPPED_PRESENT,
                        false,
                        Optional.empty(),
                        Optional.empty());
        RunResults.ImageRunResult target =
                new RunResults.ImageRunResult(
                        RecipeExecutorTestData.IMAGE_ID,
                        RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                        false,
                        List.of(duplicate),
                        List.of(),
                        List.of(),
                        Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ImageActionAggregation.reduce(
                                RecipeExecutorTestData.IMAGE_ID,
                                List.of(
                                        target(RecipeExecutorTestData.ROI_A, target),
                                        target(RecipeExecutorTestData.ROI_A, target)),
                                Optional.empty()));
    }

    @Test
    void onlyFailedModelRegionsArePrefailedForTheFinalMetricPass() {
        RunResults.FailureDetail failure =
                new RunResults.FailureDetail(RunResults.FailureType.BACKEND, "ROI B model failed");

        var failures =
                ImageActionAggregation.failedMetricRegions(
                        List.of(
                                target(
                                        RecipeExecutorTestData.ROI_A,
                                        image(
                                                RunResults.ImageOutcomeType
                                                        .SUCCEEDED_NO_HIERARCHY_CHANGE,
                                                false,
                                                List.of())),
                                target(
                                        RecipeExecutorTestData.ROI_B,
                                        image(
                                                RunResults.ImageOutcomeType.FAILED,
                                                false,
                                                List.of(failure)))));

        assertEquals(
                Set.of(new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_B)),
                failures.keySet());
        assertEquals(RunResults.FailureType.METRICS, failures.values().iterator().next().type());
    }

    private static RunResults.ImageRunResult image(
            RunResults.ImageOutcomeType outcome,
            boolean hierarchySaved,
            List<RunResults.FailureDetail> failures) {
        return new RunResults.ImageRunResult(
                RecipeExecutorTestData.IMAGE_ID,
                outcome,
                hierarchySaved,
                List.of(),
                List.of(),
                failures,
                Optional.empty());
    }

    private static ImageActionAggregation.TargetResult target(
            String roiId, RunResults.ImageRunResult result) {
        return new ImageActionAggregation.TargetResult(
                List.of(PipelineTestData.target(new ExecutionScopeCodec.Roi(roiId))), result);
    }
}
