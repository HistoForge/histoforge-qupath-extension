package qupath.ext.histoforge.data;

import java.util.Locale;
import qupath.ext.histoforge.data.MetricTypes.AggregatedDistribution;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMetric;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMissing;
import qupath.ext.histoforge.data.MetricTypes.AggregatedScalar;
import qupath.ext.histoforge.data.MetricTypes.Distribution;
import qupath.ext.histoforge.data.MetricTypes.DistributionStats;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionResult;
import qupath.ext.histoforge.data.MetricTypes.Missing;
import qupath.ext.histoforge.data.MetricTypes.Scalar;

/** Presentation-only formatting for typed metric values. */
public final class MetricFormat {

    public static final String NO_VALUE = "—";
    public static final String PRESENT_EMPTY = "Empty";

    private MetricFormat() {}

    /** Author-defined display label plus its optional unit. */
    public static String rowLabel(MetricDefinition definition) {
        return definition.label() + definition.unit().map(unit -> " (" + unit + ")").orElse("");
    }

    /** Format one aggregate without collapsing missing or present-empty into numeric zero. */
    public static String aggregate(AggregatedMetric aggregate, MetricDefinition definition) {
        if (aggregate == null || aggregate.value() instanceof AggregatedMissing) {
            return NO_VALUE;
        }
        if (aggregate.value() instanceof AggregatedScalar scalar) {
            return number(scalar.value(), definition);
        }
        DistributionStats statistics = ((AggregatedDistribution) aggregate.value()).statistics();
        if (statistics.sampleCount() == 0) {
            return PRESENT_EMPTY;
        }
        double mean = statistics.mean().orElseThrow();
        double std =
                Math.sqrt(
                        statistics.sumSquaredDeviations().orElseThrow() / statistics.sampleCount());
        return meanStd(mean, std, definition);
    }

    /** Format one exact region value under its schema definition. */
    public static String region(MetricRegionResult result, MetricDefinition definition) {
        if (result == null || result.values().get(definition.name()) instanceof Missing) {
            return NO_VALUE;
        }
        var value = result.values().get(definition.name());
        if (value instanceof Scalar scalar) {
            return number(scalar.value(), definition);
        }
        Distribution distribution = (Distribution) value;
        if (distribution.values().isEmpty()) {
            return PRESENT_EMPTY;
        }
        double mean =
                distribution.values().stream()
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElseThrow();
        double sumSquared = 0.0;
        for (double member : distribution.values()) {
            double delta = member - mean;
            sumSquared += delta * delta;
        }
        return meanStd(mean, Math.sqrt(sumSquared / distribution.values().size()), definition);
    }

    private static String meanStd(double mean, double std, MetricDefinition definition) {
        if (std == 0.0) {
            return number(mean, definition);
        }
        return number(mean, definition) + " ± " + number(std, definition);
    }

    private static String number(double value, MetricDefinition definition) {
        return String.format(Locale.ROOT, "%." + definition.decimalPlaces() + "f", value);
    }
}
