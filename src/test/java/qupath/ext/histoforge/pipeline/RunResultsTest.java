package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes;

/** Invariants for process-local scoped, metric, image, and batch action results. */
class RunResultsTest {

    @Test
    void persistenceBindingDistinguishesWorkFromSavedHierarchy() {
        RunResults.ScopeWorkResult skipped =
                new RunResults.ScopeWorkResult(
                        requestedTarget(),
                        RunResults.ScopeOutcomeType.SKIPPED_PRESENT,
                        false,
                        Optional.empty(),
                        Optional.empty());

        RunResults.ScopeOutcome outcome = skipped.afterPersistence(true);

        assertEquals(RunResults.ScopeOutcomeType.SKIPPED_PRESENT, outcome.type());
        assertFalse(outcome.hierarchySaved());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RunResults.ScopeWorkResult(
                                requestedTarget(),
                                RunResults.ScopeOutcomeType.PRODUCED,
                                false,
                                Optional.empty(),
                                Optional.empty()));
    }

    @Test
    void metricValuesPreserveZeroEmptyAndMissing() {
        Map<String, MetricTypes.MetricValue> values = new LinkedHashMap<>();
        values.put("zero", new MetricTypes.Scalar(0.0));
        values.put("empty", new MetricTypes.Distribution(List.of()));
        values.put("missing", new MetricTypes.Missing());
        MetricTypes.MetricRegionResult result = new MetricTypes.MetricRegionResult(values);

        assertEquals(new MetricTypes.Scalar(0.0), result.values().get("zero"));
        assertEquals(new MetricTypes.Distribution(List.of()), result.values().get("empty"));
        assertEquals(new MetricTypes.Missing(), result.values().get("missing"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.values().put("other", new MetricTypes.Scalar(1.0)));
        assertThrows(IllegalArgumentException.class, () -> new MetricTypes.Scalar(Double.NaN));
    }

    @Test
    void imageAndBatchResultsEnforceTheirIdentityAndSavedState() {
        RunResults.ScopeOutcome skipped =
                new RunResults.ScopeOutcome(
                        requestedTarget(),
                        RunResults.ScopeOutcomeType.SKIPPED_PRESENT,
                        false,
                        Optional.empty(),
                        Optional.empty());
        RunResults.ImageRunResult image =
                new RunResults.ImageRunResult(
                        RecipeExecutorTestData.IMAGE_ID,
                        RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                        false,
                        List.of(skipped),
                        List.of(),
                        List.of(),
                        Optional.empty());
        RunResults.BatchRunResult batch =
                new RunResults.BatchRunResult(RecipeExecutorTestData.ACTION_ID, List.of(image));

        assertEquals(RecipeExecutorTestData.ACTION_ID, batch.actionId());
        assertEquals(List.of(image), batch.images());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RunResults.ImageRunResult(
                                RecipeExecutorTestData.IMAGE_ID,
                                RunResults.ImageOutcomeType.SUCCEEDED,
                                false,
                                List.of(skipped),
                                List.of(),
                                List.of(),
                                Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunResults.BatchRunResult(RecipeExecutorTestData.ACTION_ID, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RunResults.ImageRunResult(
                                RecipeExecutorTestData.IMAGE_ID,
                                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                                false,
                                List.of(skipped, skipped),
                                List.of(),
                                List.of(),
                                Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RunResults.BatchRunResult(
                                RecipeExecutorTestData.ACTION_ID, List.of(image, image)));
        assertTrue(batch.images().getFirst().imageFailures().isEmpty());
    }

    private static RunResults.RequestedTarget requestedTarget() {
        return new RunResults.RequestedTarget(
                new InferenceIdentityCodec.ModelStepIdentity(
                        RecipeExecutorTestData.MODULE, "parent"),
                new ActionSelectionCodec.RoiTarget(RecipeExecutorTestData.ROI_A));
    }
}
