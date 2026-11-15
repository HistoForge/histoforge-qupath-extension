package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static qupath.ext.histoforge.data.MetricResultRetentionAssertions.assertOnlyResultData;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectType;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;

/** Evaluated and displayed values cannot own the hierarchy populations used to compute them. */
class MetricResultLifetimeTest {

    @Test
    void evaluationAndProjectDisplayRetainOnlyResultFacts() throws Exception {
        var object =
                new MetricObjectSnapshot(
                        MetricTestData.objectId(1),
                        MetricObjectType.DETECTION,
                        "Cell",
                        MetricTestData.square(1, 1, 5, 5),
                        Optional.empty());
        var preparation =
                MetricTestData.preparation(
                        MetricTestData.clientBuild(10, 20),
                        MetricTestData.script(
                                "return [count: children.size()]",
                                MetricTypesTest.scalar("count", MetricTypes.AggregationType.SUM)),
                        MetricTestData.revision('1'),
                        new MetricRegionSnapshot(
                                MetricTestData.target(MetricTestData.ROI_B), List.of(object)),
                        MetricTestData.region(MetricTestData.ROI_A));
        var input = preparation.snapshot().metricInput();
        var engine = new MetricsEngine();

        var evaluation = engine.evaluate(preparation, Map.of(), false, () -> false);
        assertEquals(Map.of(), evaluation.regionFailures());
        var context = evaluation.context();
        var published = engine.publish(evaluation, MetricTestData.revision('2'));
        var display =
                new ProjectSummaryData.CachedImageMetrics(
                        MetricTestData.IMAGE_ID,
                        "Image A",
                        "Control",
                        context.module(),
                        "Metric test",
                        context.schema(),
                        published);

        assertEquals(input.metricInputDigest(), context.metricInputDigest());
        assertEquals(input.clientBuild(), context.clientBuild());
        assertEquals(input.module(), context.module());
        assertEquals(input.script().digest(), context.metricsScriptDigest());
        assertEquals(input.script().schema(), context.schema());
        assertEquals(
                input.regions().stream().map(MetricRegionSnapshot::target).toList(),
                context.regions());
        assertEquals(MetricTestData.revision('1'), evaluation.result().sourceRevision());
        assertEquals(MetricTestData.revision('2'), display.result().sourceRevision());
        assertEquals(input.metricInputDigest(), display.result().metricInputDigest());
        assertEquals(evaluation.regionResults(), display.result().regionResults());
        assertThrows(UnsupportedOperationException.class, () -> context.regions().clear());
        assertOnlyResultData(evaluation);
        assertOnlyResultData(display);
    }
}
