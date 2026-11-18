package qupath.ext.histoforge.ui.project;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import qupath.ext.histoforge.modules.InstalledModule;

/** One shared enablement rule for explicit Metrics and metrics export. */
final class ProjectMetricsActionGate {

    private ProjectMetricsActionGate() {}

    record Availability(boolean enabled, String explanation) {
        Availability {
            explanation = Objects.requireNonNull(explanation, "explanation");
        }
    }

    static Availability evaluate(
            boolean busy,
            List<ImageRowModel> selected,
            Function<String, Optional<InstalledModule>> moduleResolver) {
        List<ImageRowModel> rows = List.copyOf(Objects.requireNonNull(selected, "selected"));
        Function<String, Optional<InstalledModule>> resolver =
                Objects.requireNonNull(moduleResolver, "moduleResolver");
        if (busy) {
            return new Availability(false, "Another project action is running.");
        }
        if (rows.isEmpty()) {
            return new Availability(false, "Select one or more images first.");
        }
        for (ImageRowModel row : rows) {
            InstalledModule module =
                    row.moduleId == null ? null : resolver.apply(row.moduleId).orElse(null);
            if (module == null) {
                return new Availability(
                        false, "Every selected image needs an installed module with metrics.");
            }
            if (module.metricsScript() == null) {
                return new Availability(
                        false,
                        "The selected module " + module.name() + " does not provide measurements.");
            }
        }
        return new Availability(
                true,
                "Calculate or export measurements for the selected images and regions using their current objects. This does not run models or confirm that all model steps have finished.");
    }

    /** Drop invalidations that no longer belong to a current metrics-capable assignment. */
    static Set<String> retainedInvalidations(
            Collection<String> invalidated,
            List<ImageRowModel> currentRows,
            Function<String, Optional<InstalledModule>> moduleResolver) {
        Set<String> eligible = metricEligibleImageIds(currentRows, true, moduleResolver);
        Set<String> result =
                new LinkedHashSet<>(Objects.requireNonNull(invalidated, "invalidated"));
        result.retainAll(eligible);
        return Set.copyOf(result);
    }

    /** Project retained invalidations into the metrics currently visible for one region scope. */
    static Set<String> outstandingRecomputations(
            Collection<String> invalidated,
            List<ImageRowModel> currentRows,
            Collection<String> currentMetricImageIds,
            boolean wholeSlide,
            Function<String, Optional<InstalledModule>> moduleResolver) {
        Set<String> eligible = metricEligibleImageIds(currentRows, wholeSlide, moduleResolver);
        Set<String> result =
                new LinkedHashSet<>(Objects.requireNonNull(invalidated, "invalidated"));
        result.retainAll(eligible);
        result.removeAll(Objects.requireNonNull(currentMetricImageIds, "currentMetricImageIds"));
        return Set.copyOf(result);
    }

    private static Set<String> metricEligibleImageIds(
            List<ImageRowModel> currentRows,
            boolean wholeSlide,
            Function<String, Optional<InstalledModule>> moduleResolver) {
        Function<String, Optional<InstalledModule>> resolver =
                Objects.requireNonNull(moduleResolver, "moduleResolver");
        Set<String> eligible = new LinkedHashSet<>();
        for (ImageRowModel row : List.copyOf(Objects.requireNonNull(currentRows, "currentRows"))) {
            if (!wholeSlide && row.roiCount == 0) {
                continue;
            }
            InstalledModule module =
                    row.moduleId == null ? null : resolver.apply(row.moduleId).orElse(null);
            if (module != null && module.metricsScript() != null) {
                eligible.add(row.entry.getID());
            }
        }
        return eligible;
    }
}
