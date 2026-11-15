package qupath.ext.histoforge.client.dto;

/**
 * DTO for a single metric descriptor.
 *
 * <p>Authored in each module's {@code metrics.groovy} {@code @histoforge-metrics} header — the
 * single source of truth for what a script produces and how to display it. Parsed by {@link
 * qupath.ext.histoforge.modules.ScriptHeaderParser#parseMetrics} from the module's inline scripts.
 *
 * @param name metric identifier (e.g. "count", "diameter") — the script's return-map key
 * @param label display label authored by the module author (e.g. "Tubule Diameter", "Round Leydig
 *     N:C Ratio")
 * @param type "scalar" or "distribution"
 * @param unit authored display unit, or an empty string for a unitless value
 * @param decimals number of decimal places shown
 * @param aggregation declared combination rule: sum, mean, or pool
 */
public record MetricInfo(
        String name, String label, String type, String unit, int decimals, String aggregation) {

    public boolean isDistribution() {
        return "distribution".equals(type);
    }

    public boolean isSum() {
        return "sum".equals(aggregation);
    }

    public boolean isMean() {
        return "mean".equals(aggregation);
    }

    public boolean isPool() {
        return "pool".equals(aggregation);
    }

    public String unitOrEmpty() {
        return unit != null ? unit : "";
    }

    /** Display label, falling back to the raw {@link #name} when unauthored. */
    public String labelOrName() {
        return (label != null && !label.isEmpty()) ? label : name;
    }
}
