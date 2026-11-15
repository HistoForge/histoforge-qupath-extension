package qupath.ext.histoforge.ui.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.Deployment;
import qupath.ext.histoforge.client.DeploymentStore;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.AdminJobCodec.AdminJobStatus;
import qupath.ext.histoforge.contract.AdminJobCodec.JobPhase;
import qupath.ext.histoforge.contract.AdminJobCodec.JobType;
import qupath.ext.histoforge.contract.GatewayErrorCodec;

/** Deterministic initial-baseline and terminal-transition coverage for the UI poll projection. */
class JobsPollerTest {

    @Test
    void localInferenceNeedsNoCloudBaselineAndSurvivesServerRefresh() {
        List<JobRow> finished = new ArrayList<>();
        var poller =
                new JobsPoller(
                        () -> {
                            throw new AssertionError("Local Jobs requested a module client");
                        },
                        () -> {
                            throw new AssertionError("Local Jobs requested a cloud transport");
                        },
                        finished::add,
                        () -> {});
        var inference =
                new JobRow.Inference(
                        "project",
                        "action",
                        "image",
                        "Slide",
                        "owner/module",
                        "Results saved",
                        Instant.now(),
                        false,
                        true,
                        true,
                        "details",
                        "",
                        "",
                        "",
                        Optional.empty(),
                        false);
        var local = FXCollections.observableArrayList(inference);
        poller.observeInference(local);
        poller.refresh();
        assertFalse(poller.hasBaseline());
        assertEquals(List.of(inference), poller.jobs());
        var admin = new JobRow.Admin(status(JobPhase.QUEUED));
        poller.apply(List.of(admin));
        assertEquals(List.of(inference, admin), poller.jobs());
        poller.apply(List.of());
        assertEquals(List.of(inference), poller.jobs());
        assertTrue(finished.isEmpty());
        local.clear();
        assertTrue(poller.jobs().isEmpty());
    }

    @Test
    void restartSilentlyAdoptsTerminalTrainingAndUnchangedRowsDoNotFlicker() throws Exception {
        List<JobRow> finished = new ArrayList<>();
        var poller = new JobsPoller(null, null, finished::add, () -> {});
        var changes = new AtomicInteger();
        poller.jobs().addListener((ListChangeListener<JobRow>) change -> changes.incrementAndGet());
        var training = TrainingJobsFixtures.row("SUCCEEDED");
        var rows = List.<JobRow>of(training);
        poller.apply(rows);
        poller.apply(rows);
        assertTrue(finished.isEmpty());
        assertEquals(1, changes.get());
        assertEquals(rows, poller.jobs());
    }

    @Test
    void administrativeAndTrainingIdsDoNotShareNotificationState() throws Exception {
        List<JobRow> finished = new ArrayList<>();
        var poller = new JobsPoller(null, null, finished::add, () -> {});
        var training = TrainingJobsFixtures.row("SUCCEEDED");
        var admin =
                new JobRow.Admin(
                        new AdminJobStatus(
                                training.id(),
                                JobType.INSTALL,
                                new ModuleRef("owner", "module"),
                                JobPhase.FAILED,
                                training.updatedAt(),
                                Optional.empty(),
                                status(JobPhase.FAILED).failure()));
        poller.apply(List.of());
        poller.apply(List.of(admin, training));
        poller.apply(List.of(admin, training));
        assertEquals(List.of(admin, training), finished);
    }

    @Test
    void trainingTerminalTransitionsAreAnnouncedOnce() throws Exception {
        List<JobRow> finished = new ArrayList<>();
        var poller = new JobsPoller(null, null, finished::add, () -> {});
        poller.apply(List.of(TrainingJobsFixtures.row("RUNNING")));
        var cancelled = TrainingJobsFixtures.row("CANCELLED");
        poller.apply(List.of(cancelled));
        poller.apply(List.of(cancelled));
        assertEquals(List.of(cancelled), finished);
    }

    @Test
    void failedRefreshAndAnOvertakenSnapshotCannotReplaceCurrentRows() throws Exception {
        var fail = new AtomicBoolean();
        var page = TrainingJobsFixtures.page();
        page.remove("next_cursor");
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext(
                "/v1/training/runs",
                exchange -> {
                    byte[] bytes =
                            (fail.get() ? "{}" : page.toString()).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(fail.get() ? 503 : 200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    }
                });
        server.start();
        try {
            var modules = mock(ModuleGatewayClient.class);
            when(modules.capabilities()).thenReturn(TrainingJobsFixtures.capabilities());
            when(modules.listJobs()).thenReturn(List.of(status(JobPhase.QUEUED)));
            var deployment =
                    new Deployment(
                            1,
                            "deployment-2026-08-12-a",
                            "http://localhost:" + server.getAddress().getPort(),
                            "research-lab",
                            "histoforge-gateway",
                            1,
                            "token",
                            Instant.parse("2026-09-04T12:00:00.000Z"));
            var store = mock(DeploymentStore.class);
            when(store.load()).thenReturn(Optional.of(deployment));
            when(store.reload()).thenReturn(Optional.of(deployment));
            var poller =
                    new JobsPoller(
                            () -> modules,
                            () -> new GatewayTransport(store),
                            ignored -> {},
                            () -> {});
            poller.fetchUpdate().run();
            assertEquals(4, poller.jobs().size());
            assertTrue(poller.hasBaseline());
            assertTrue(poller.jobs().getFirst() instanceof JobRow.Training);
            var prior = List.copyOf(poller.jobs());
            fail.set(true);
            assertThrows(Exception.class, poller::fetchUpdate);
            assertEquals(prior, poller.jobs());

            fail.set(false);
            Runnable oldResponse = poller.fetchUpdate();
            JobRow removed = poller.jobs().getFirst();
            poller.forget(removed);
            oldResponse.run();
            assertEquals(3, poller.jobs().size());
            assertFalse(poller.jobs().contains(removed));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void baselinePrecedesAndDoesNotAnnounceLaterTerminalTransitionsTwice() {
        AtomicInteger baselines = new AtomicInteger();
        List<JobRow> finished = new ArrayList<>();
        JobsPoller poller = new JobsPoller(null, null, finished::add, baselines::incrementAndGet);

        poller.apply(List.of(new JobRow.Admin(status(JobPhase.QUEUED))));
        assertTrue(poller.hasBaseline());
        assertEquals(1, baselines.get());
        assertTrue(finished.isEmpty());

        JobRow.Admin failed = new JobRow.Admin(status(JobPhase.FAILED));
        poller.apply(List.of(failed));
        poller.apply(List.of(failed));

        assertEquals(1, baselines.get());
        assertEquals(List.of(failed), finished);
    }

    private static AdminJobStatus status(JobPhase phase) {
        Optional<GatewayErrorCodec.Error> failure =
                phase == JobPhase.FAILED
                        ? Optional.of(
                                new GatewayErrorCodec.Error(
                                        1,
                                        GatewayErrorCodec.Code.INTERNAL_ERROR,
                                        "failed",
                                        Optional.empty(),
                                        Optional.empty()))
                        : Optional.empty();
        return new AdminJobStatus(
                "11111111-1111-4111-8111-111111111111",
                JobType.INSTALL,
                new ModuleRef("owner", "module"),
                phase,
                Instant.parse("2026-08-15T01:02:03.004Z"),
                Optional.empty(),
                failure);
    }
}
