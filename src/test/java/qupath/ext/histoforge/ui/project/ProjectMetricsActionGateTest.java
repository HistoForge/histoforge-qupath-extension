package qupath.ext.histoforge.ui.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.lib.projects.ProjectImageEntry;

class ProjectMetricsActionGateTest {

    @Test
    void requiresEverySelectedRowToResolveToAMetricsCapableModule() {
        InstalledModule first = module("First", true);
        InstalledModule second = module("Second", true);
        InstalledModule withoutMetrics = module("No metrics", false);
        Map<String, InstalledModule> installed =
                Map.of(
                        "first/module",
                        first,
                        "second/module",
                        second,
                        "plain/module",
                        withoutMetrics);

        assertFalse(gate(false, List.of(), installed).enabled());
        assertFalse(gate(false, List.of(row(null)), installed).enabled());
        assertFalse(gate(false, List.of(row("missing/module")), installed).enabled());
        assertFalse(gate(false, List.of(row("plain/module")), installed).enabled());
        assertEquals(
                "The selected module No metrics does not provide measurements.",
                gate(false, List.of(row("plain/module")), installed).explanation());
        assertFalse(
                gate(false, List.of(row("first/module"), row("plain/module")), installed)
                        .enabled());
        assertTrue(
                gate(false, List.of(row("first/module"), row("second/module")), installed)
                        .enabled());
        assertFalse(gate(true, List.of(row("first/module")), installed).enabled());
    }

    @Test
    void metricsCapabilityDoesNotDisableFillOrRedo() {
        assertTrue(
                ProjectTabController.actionAvailability(false, false, false, true)
                        .selectedActionsEnabled());
    }

    @Test
    void invalidationsAreRetainedSeparatelyFromCurrentScopeVisibility() {
        InstalledModule metrics = module("Metrics", true);
        InstalledModule plain = module("Plain", false);
        Map<String, InstalledModule> installed =
                Map.of("metrics/module", metrics, "plain/module", plain);
        List<ImageRowModel> rows =
                List.of(
                        row("fresh", "metrics/module", 1),
                        row("stale", "metrics/module", 1),
                        row("plain", "plain/module", 1),
                        row("no-roi", "metrics/module", 0));

        java.util.Set<String> retained =
                ProjectMetricsActionGate.retainedInvalidations(
                        java.util.Set.of("fresh", "stale", "plain", "no-roi", "deleted"),
                        rows,
                        ref -> Optional.ofNullable(installed.get(ref)));

        assertEquals(java.util.Set.of("fresh", "stale", "no-roi"), retained);
        assertEquals(
                java.util.Set.of("stale"),
                ProjectMetricsActionGate.outstandingRecomputations(
                        retained,
                        rows,
                        java.util.Set.of("fresh"),
                        false,
                        ref -> Optional.ofNullable(installed.get(ref))));
        assertEquals(
                java.util.Set.of("no-roi", "stale"),
                ProjectMetricsActionGate.outstandingRecomputations(
                        retained,
                        rows,
                        java.util.Set.of("fresh"),
                        true,
                        ref -> Optional.ofNullable(installed.get(ref))));
    }

    private static ProjectMetricsActionGate.Availability gate(
            boolean busy, List<ImageRowModel> rows, Map<String, InstalledModule> installed) {
        return ProjectMetricsActionGate.evaluate(
                busy, rows, ref -> Optional.ofNullable(installed.get(ref)));
    }

    private static InstalledModule module(String name, boolean metrics) {
        InstalledModule module = mock(InstalledModule.class);
        when(module.name()).thenReturn(name);
        when(module.metricsScript()).thenReturn(metrics ? "return []" : null);
        return module;
    }

    @SuppressWarnings("unchecked")
    private static ImageRowModel row(String moduleRef) {
        return row("image", moduleRef, 0);
    }

    @SuppressWarnings("unchecked")
    private static ImageRowModel row(String imageId, String moduleRef, int roiCount) {
        ProjectImageEntry<java.awt.image.BufferedImage> entry = mock(ProjectImageEntry.class);
        when(entry.getID()).thenReturn(imageId);
        return new ImageRowModel(
                entry,
                null,
                null,
                moduleRef,
                roiCount == 0 ? java.util.Set.of() : java.util.Set.of("roi"),
                "No output",
                "",
                null,
                null);
    }
}
