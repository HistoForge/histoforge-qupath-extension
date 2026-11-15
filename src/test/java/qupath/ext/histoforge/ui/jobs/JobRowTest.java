package qupath.ext.histoforge.ui.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.contract.TrainingRunPageCodec;
import qupath.ext.histoforge.modules.InstalledModule;

class JobRowTest {

    @Test
    void trainingActionsFollowTheBackendStateBoundary() throws Exception {
        for (String state :
                List.of("QUEUED", "RUNNING", "FINALIZING", "SUCCEEDED", "FAILED", "CANCELLED")) {
            var row = TrainingJobsFixtures.row(state);
            assertEquals(List.of("QUEUED", "RUNNING").contains(state), row.canCancel(), state);
            assertEquals(
                    List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state),
                    row.isTerminal(),
                    state);
            assertEquals("SUCCEEDED".equals(state), row.succeeded(), state);
            assertEquals("FAILED".equals(state), row.failure().isPresent(), state);
        }
        assertEquals("Epoch 2 of 3", TrainingJobsFixtures.row("RUNNING").status());
        assertEquals("Preparing result module", TrainingJobsFixtures.row("FINALIZING").status());
        assertEquals("Cancelled", TrainingJobsFixtures.row("CANCELLED").status());
    }

    @Test
    void metricIsGenericAndUnavailableIsNotZero() throws Exception {
        var value = TrainingJobsFixtures.page().getAsJsonArray("items").get(2).getAsJsonObject();
        var metric = value.getAsJsonObject("primary_metric");
        metric.addProperty("display_name", "Any new scientific metric");
        metric.addProperty("value", 0);
        var row =
                new JobRow.Training(
                        TrainingRunPageCodec.parseSummary(
                                value, TrainingJobsFixtures.capabilities().trainingApiLimits()));
        assertEquals("Any new scientific metric 0 (epoch 2)", row.primaryMetric());
        assertEquals("", TrainingJobsFixtures.row("RUNNING").primaryMetric());
        assertTrue(JobsTableColumns.detailText(row).contains("Model: leydig_nuclear"));
        assertTrue(JobsTableColumns.detailText(row).contains("Copy of the source module"));
        assertTrue(JobsTableColumns.detailText(row).contains("Status: Epoch 2 of 3"));
        assertTrue(JobsTableColumns.detailText(row).contains(row.primaryMetric()));
    }

    @Test
    void onlyTheSucceededRunsReservedWorkspaceModuleIsNavigable() throws Exception {
        var succeeded = TrainingJobsFixtures.row("SUCCEEDED");
        var exact = module(new ModuleRef("workspace", "run-" + succeeded.id()));
        assertTrue(succeeded.ownsResult(exact));
        assertFalse(TrainingJobsFixtures.row("FINALIZING").ownsResult(exact));
        assertFalse(TrainingJobsFixtures.row("FAILED").ownsResult(exact));
        assertFalse(
                succeeded.ownsResult(module(new ModuleRef("histoforge", "run-" + succeeded.id()))));
        assertFalse(
                succeeded.ownsResult(
                        module(
                                new ModuleRef(
                                        "workspace", "run-66666666-6666-4666-8666-666666666666"))));
    }

    private static InstalledModule module(ModuleRef ref) {
        var identity =
                new ModuleContentIdentity(ref, Optional.empty(), "a".repeat(64), "b".repeat(64));
        var descriptor = mock(InstalledModuleDescriptor.class);
        when(descriptor.identity()).thenReturn(identity);
        var module = mock(InstalledModule.class);
        when(module.descriptor()).thenReturn(descriptor);
        return module;
    }
}
