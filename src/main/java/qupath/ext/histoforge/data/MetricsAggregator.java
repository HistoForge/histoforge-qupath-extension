package qupath.ext.histoforge.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.data.MetricTypes.AggregatedDistribution;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMetric;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMetricValue;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMissing;
import qupath.ext.histoforge.data.MetricTypes.AggregatedScalar;
import qupath.ext.histoforge.data.MetricTypes.AggregationType;
import qupath.ext.histoforge.data.MetricTypes.Distribution;
import qupath.ext.histoforge.data.MetricTypes.DistributionStats;
import qupath.ext.histoforge.data.MetricTypes.MetricCoverage;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricImageResult;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionResult;
import qupath.ext.histoforge.data.MetricTypes.MetricSchema;
import qupath.ext.histoforge.data.MetricTypes.MetricValue;
import qupath.ext.histoforge.data.MetricTypes.Missing;
import qupath.ext.histoforge.data.MetricTypes.Scalar;

/** Pure schema-directed aggregation of detached metric results. */
public final class MetricsAggregator {

    private MetricsAggregator() {}

    /** Aggregate every region in one image result. */
    public static List<AggregatedMetric> aggregate(
            MetricSchema schema, MetricImageResult imageResult) {
        return aggregate(schema, List.of(Objects.requireNonNull(imageResult, "imageResult")));
    }

    /**
     * Aggregate every region across several independently captured image results.
     *
     * <p>Scalar sums use compensated addition. Scalar means are unweighted region means.
     * Distributions stream through one Welford state and never reconstruct a pooled raw list.
     * Region failures and explicit missing values remain distinct in coverage.
     */
    public static List<AggregatedMetric> aggregate(
            MetricSchema schema, List<MetricImageResult> imageResults) {
        Objects.requireNonNull(schema, "schema");
        if (imageResults == null || imageResults.isEmpty()) {
            throw new IllegalArgumentException("metric image results must be nonempty");
        }
        List<MetricImageResult> results =
                imageResults.stream()
                        .map(result -> Objects.requireNonNull(result, "result"))
                        .toList();
        Set<String> expected =
                schema.definitions().stream()
                        .map(MetricDefinition::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (MetricImageResult result : results) {
            result.regionResults()
                    .values()
                    .forEach(
                            region -> {
                                if (!region.values().keySet().equals(expected)) {
                                    throw new IllegalArgumentException(
                                            "metric region values do not match the schema");
                                }
                            });
        }

        int requested = 0;
        int failed = 0;
        for (MetricImageResult result : results) {
            requested =
                    Math.addExact(
                            requested,
                            Math.addExact(
                                    result.regionResults().size(), result.regionFailures().size()));
            failed = Math.addExact(failed, result.regionFailures().size());
        }

        List<AggregatedMetric> aggregates = new ArrayList<>();
        for (MetricDefinition definition : schema.definitions()) {
            int present = 0;
            int missing = 0;
            CompensatedSum scalarSum = new CompensatedSum();
            Welford distribution = new Welford();
            for (MetricImageResult result : results) {
                for (MetricRegionResult region : result.regionResults().values()) {
                    MetricValue value = region.values().get(definition.name());
                    if (value instanceof Missing) {
                        missing++;
                    } else if (value instanceof Scalar scalar) {
                        if (definition.type() != MetricTypes.MetricType.SCALAR) {
                            throw new IllegalArgumentException(
                                    "metric value type does not match the schema");
                        }
                        present++;
                        scalarSum.add(scalar.value());
                    } else if (value instanceof Distribution values) {
                        if (definition.type() != MetricTypes.MetricType.DISTRIBUTION) {
                            throw new IllegalArgumentException(
                                    "metric value type does not match the schema");
                        }
                        present++;
                        values.values().forEach(distribution::add);
                    } else {
                        throw new IllegalArgumentException("unsupported metric value");
                    }
                }
            }

            MetricCoverage coverage = new MetricCoverage(requested, present, missing, failed);
            AggregatedMetricValue value;
            if (present == 0) {
                value = new AggregatedMissing();
            } else if (definition.type() == MetricTypes.MetricType.SCALAR) {
                double total = scalarSum.value();
                value =
                        new AggregatedScalar(
                                definition.aggregation() == AggregationType.MEAN
                                        ? total / present
                                        : total);
            } else {
                value = new AggregatedDistribution(distribution.snapshot());
            }
            aggregates.add(new AggregatedMetric(definition.name(), value, coverage));
        }
        return List.copyOf(aggregates);
    }

    private static final class CompensatedSum {
        private double sum;
        private double correction;

        private void add(double value) {
            double adjusted = value - correction;
            double next = sum + adjusted;
            correction = (next - sum) - adjusted;
            sum = next;
        }

        private double value() {
            return sum;
        }
    }

    private static final class Welford {
        private long count;
        private double mean;
        private double sumSquaredDeviations;

        private void add(double value) {
            count = Math.incrementExact(count);
            double delta = value - mean;
            mean += delta / count;
            double deltaAfter = value - mean;
            sumSquaredDeviations += delta * deltaAfter;
        }

        private DistributionStats snapshot() {
            return count == 0
                    ? new DistributionStats(0, Optional.empty(), Optional.empty())
                    : new DistributionStats(
                            count, Optional.of(mean), Optional.of(sumSquaredDeviations));
        }
    }
}
