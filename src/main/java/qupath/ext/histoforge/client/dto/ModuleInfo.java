package qupath.ext.histoforge.client.dto;

import java.util.List;

/**
 * A module the plugin can run on a QuPath project.
 *
 * <p>Built by {@code InstalledModule.toModuleInfo}: {@link #id} is the qualified module ref; {@link
 * #name}, {@link #description}, and {@link #classifications} come from the recipe. {@link #metrics}
 * are parsed from the module's metrics script (its {@code @histoforge-metrics} header).
 *
 * @param id qualified module ref (e.g. "histoforge/tubule")
 * @param name display name
 * @param description what the module does
 * @param metrics metric declarations from the metrics script's {@code @histoforge-metrics} header
 * @param classifications QuPath class names declared by the recipe
 */
public record ModuleInfo(
        String id,
        String name,
        String description,
        List<MetricInfo> metrics,
        List<String> classifications) {

    /** Absent JSON lists become empty lists; supplied lists are copied. */
    public ModuleInfo {
        metrics = metrics != null ? List.copyOf(metrics) : List.of();
        classifications = classifications != null ? List.copyOf(classifications) : List.of();
    }

    /** True when this module declares at least one metric — false for a segment-only recipe. */
    public boolean hasMetrics() {
        return !metrics.isEmpty();
    }
}
