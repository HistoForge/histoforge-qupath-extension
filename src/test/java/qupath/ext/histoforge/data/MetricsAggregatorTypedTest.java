package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.data.MetricTypes.AggregatedDistribution;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMetric;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMissing;
import qupath.ext.histoforge.data.MetricTypes.AggregatedScalar;
import qupath.ext.histoforge.data.MetricTypes.Distribution;
import qupath.ext.histoforge.data.MetricTypes.MetricImageResult;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionResult;
import qupath.ext.histoforge.data.MetricTypes.MetricSchema;
import qupath.ext.histoforge.data.MetricTypes.Missing;
import qupath.ext.histoforge.data.MetricTypes.Scalar;
import qupath.ext.histoforge.pipeline.RunResults;

/** Stable typed aggregation over zero, empty, missing, and failed regions. */
class MetricsAggregatorTypedTest {

    @Test
    void aggregatesPresentValuesWithoutConflatingAbsenceOrFailure() {
        MetricSchema schema =
                new MetricSchema(
                        List.of(
                                MetricTypesTest.scalar("sum", MetricTypes.AggregationType.SUM),
                                MetricTypesTest.scalar("mean", MetricTypes.AggregationType.MEAN),
                                MetricTypesTest.distribution("pool")));
        Map<ExecutionScopeCodec.RegionKey, MetricRegionResult> values = new LinkedHashMap<>();
        values.put(
                new ExecutionScopeCodec.Roi(MetricTestData.ROI_A),
                new MetricRegionResult(
                        Map.of(
                                "sum",
                                new Scalar(0),
                                "mean",
                                new Scalar(2),
                                "pool",
                                new Distribution(List.of()))));
        values.put(
                new ExecutionScopeCodec.Roi(MetricTestData.ROI_B),
                new MetricRegionResult(
                        Map.of(
                                "sum",
                                new Missing(),
                                "mean",
                                new Scalar(4),
                                "pool",
                                new Distribution(List.of(1.0, 3.0)))));
        MetricImageResult result =
                new MetricImageResult(
                        "1".repeat(64),
                        MetricTestData.revision('1'),
                        values,
                        Map.of(
                                new ExecutionScopeCodec.Roi(MetricTestData.ROI_C),
                                new RunResults.FailureDetail(
                                        RunResults.FailureType.METRICS, "failed")));

        Map<String, AggregatedMetric> aggregates =
                MetricsAggregator.aggregate(schema, result).stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        AggregatedMetric::name, value -> value));

        assertEquals(new AggregatedScalar(0), aggregates.get("sum").value());
        assertEquals(new AggregatedScalar(3), aggregates.get("mean").value());
        AggregatedDistribution pool =
                assertInstanceOf(AggregatedDistribution.class, aggregates.get("pool").value());
        assertEquals(2, pool.statistics().sampleCount());
        assertEquals(2.0, pool.statistics().mean().orElseThrow());
        assertEquals(2.0, pool.statistics().sumSquaredDeviations().orElseThrow());
        assertEquals(1, aggregates.get("sum").coverage().presentRegions());
        assertEquals(1, aggregates.get("sum").coverage().missingRegions());
        assertEquals(1, aggregates.get("sum").coverage().failedRegions());
        assertEquals(2, aggregates.get("pool").coverage().presentRegions());
    }

    @Test
    void allMissingProducesTypedMissingRatherThanZero() {
        MetricSchema schema =
                new MetricSchema(
                        List.of(
                                MetricTypesTest.scalar(
                                        "missing", MetricTypes.AggregationType.SUM)));
        MetricImageResult result =
                new MetricImageResult(
                        "1".repeat(64),
                        MetricTestData.revision('1'),
                        Map.of(
                                new ExecutionScopeCodec.Roi(MetricTestData.ROI_A),
                                new MetricRegionResult(Map.of("missing", new Missing()))),
                        Map.of());

        assertInstanceOf(
                AggregatedMissing.class,
                MetricsAggregator.aggregate(schema, result).getFirst().value());
    }

    @Test
    void allPresentEmptyPoolRemainsAPresentZeroSampleDistribution() {
        MetricSchema schema = new MetricSchema(List.of(MetricTypesTest.distribution("pool")));
        MetricImageResult result =
                new MetricImageResult(
                        "1".repeat(64),
                        MetricTestData.revision('1'),
                        Map.of(
                                new ExecutionScopeCodec.Roi(MetricTestData.ROI_A),
                                new MetricRegionResult(Map.of("pool", new Distribution(List.of()))),
                                new ExecutionScopeCodec.Roi(MetricTestData.ROI_B),
                                new MetricRegionResult(
                                        Map.of("pool", new Distribution(List.of())))),
                        Map.of());

        AggregatedMetric aggregate = MetricsAggregator.aggregate(schema, result).getFirst();
        AggregatedDistribution distribution =
                assertInstanceOf(AggregatedDistribution.class, aggregate.value());
        assertEquals(0, distribution.statistics().sampleCount());
        assertEquals(java.util.Optional.empty(), distribution.statistics().mean());
        assertEquals(java.util.Optional.empty(), distribution.statistics().sumSquaredDeviations());
        assertEquals(2, aggregate.coverage().presentRegions());
    }

    @Test
    void aggregatesAcrossImagesWithoutWeightingScalarMeansByImage() {
        MetricSchema schema =
                new MetricSchema(
                        List.of(MetricTypesTest.scalar("mean", MetricTypes.AggregationType.MEAN)));
        MetricImageResult first =
                new MetricImageResult(
                        "1".repeat(64),
                        MetricTestData.revision('1'),
                        Map.of(
                                new ExecutionScopeCodec.Roi(MetricTestData.ROI_A),
                                new MetricRegionResult(Map.of("mean", new Scalar(2))),
                                new ExecutionScopeCodec.Roi(MetricTestData.ROI_B),
                                new MetricRegionResult(Map.of("mean", new Scalar(4)))),
                        Map.of());
        MetricImageResult second =
                new MetricImageResult(
                        "2".repeat(64),
                        new qupath.ext.histoforge.contract.RasterInputCodec.ImageRevisionToken(
                                "metric-image-2", "f".repeat(64), "3".repeat(64)),
                        Map.of(
                                new ExecutionScopeCodec.Roi(MetricTestData.ROI_C),
                                new MetricRegionResult(Map.of("mean", new Scalar(12)))),
                        Map.of(
                                new ExecutionScopeCodec.Roi("10000000-0000-4000-8000-000000000004"),
                                new RunResults.FailureDetail(
                                        RunResults.FailureType.METRICS, "failed")));

        AggregatedMetric aggregate =
                MetricsAggregator.aggregate(schema, List.of(first, second)).getFirst();

        assertEquals(new AggregatedScalar(6), aggregate.value());
        assertEquals(4, aggregate.coverage().requestedRegions());
        assertEquals(3, aggregate.coverage().presentRegions());
        assertEquals(0, aggregate.coverage().missingRegions());
        assertEquals(1, aggregate.coverage().failedRegions());
    }
}
