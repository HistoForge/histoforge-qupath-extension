package qupath.ext.histoforge.ui.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static qupath.ext.histoforge.ui.training.TrainingFormSmoke.applyQuPathStyle;
import static qupath.ext.histoforge.ui.training.TrainingFormSmoke.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.InputEvent;
import javafx.stage.Stage;
import qupath.ext.histoforge.client.BackendCompatibility;
import qupath.ext.histoforge.client.ConnectionProbe;
import qupath.ext.histoforge.client.Deployment;
import qupath.ext.histoforge.client.DeploymentStore;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.contract.AdminJobCodec;
import qupath.ext.histoforge.contract.AdminJobCodec.AdminJobStatus;
import qupath.ext.histoforge.contract.AdminJobCodec.JobPhase;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.training.TrainingFormFixtures;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.ui.app.MainController;
import qupath.ext.histoforge.ui.app.MainControllerRecoverySmoke;
import qupath.ext.histoforge.ui.modules.ModulesTabController;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/** Native lifecycle checks with controlled local responses; never contacts a cloud gateway. */
public final class CatalogJobsRefreshSmoke {
    private static final long STARTED_NANOS = System.nanoTime();
    private static final AtomicReference<String> CONNECTION_HEADER_STEP =
            new AtomicReference<>("not queued");
    private static final AtomicReference<Throwable> UNCAUGHT_FX_ERROR = new AtomicReference<>();

    private CatalogJobsRefreshSmoke() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var jobs = new AtomicReference<List<AdminJobStatus>>(List.of());
        var modules = new AtomicReference<List<InstalledModule>>(List.of());
        var jobGate = new AtomicReference<Gate>();
        var moduleGate = new AtomicReference<Gate>();
        var jobFetches = new AtomicInteger();
        var moduleFetches = new AtomicInteger();
        var failJobs = new AtomicBoolean();
        var failModules = new AtomicBoolean();
        JsonObject emptyPage = TrainingJobsFixtures.page();
        emptyPage.remove("next_cursor");
        emptyPage.add("items", new JsonArray());
        var trainingPage = new AtomicReference<>(emptyPage);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/training/runs",
                exchange -> {
                    byte[] bytes = trainingPage.get().toString().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(failJobs.get() ? 503 : 200, bytes.length);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(bytes);
                    }
                });
        server.start();
        var client = mock(ModuleGatewayClient.class);
        when(client.capabilities()).thenReturn(TrainingJobsFixtures.capabilities());
        when(client.listJobs())
                .thenAnswer(
                        ignored -> {
                            var snapshot = jobs.get();
                            jobFetches.incrementAndGet();
                            Gate gate = jobGate.getAndSet(null);
                            if (gate != null) gate.block();
                            return snapshot;
                        });
        when(client.listInstalled())
                .thenAnswer(
                        ignored -> {
                            var snapshot = modules.get();
                            moduleFetches.incrementAndGet();
                            Gate gate = moduleGate.getAndSet(null);
                            if (gate != null) gate.block();
                            if (failModules.get())
                                throw new java.io.IOException("Synthetic catalog failure");
                            return snapshot;
                        });
        var deployment =
                new Deployment(
                        1,
                        "deployment-2026-08-12-a",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "research-lab",
                        "histoforge-gateway",
                        1,
                        "fixture-token",
                        Instant.parse("2026-09-04T12:00:00.000Z"));
        var store = mock(DeploymentStore.class);
        when(store.load()).thenReturn(Optional.of(deployment));
        when(store.reload()).thenReturn(Optional.of(deployment));
        var transport = new GatewayTransport(store);
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    Thread.currentThread()
                            .setUncaughtExceptionHandler(
                                    (thread, error) -> UNCAUGHT_FX_ERROR.set(error));
                });
        try {
            MainControllerRecoverySmoke.verify();
            connectionHeaderStep("queued");
            fx(
                    () -> {
                        verifyConnectionHeader(output);
                        return null;
                    });
            var registry = fx(() -> new ModulesRegistry(client));
            var baseline = new AtomicInteger();
            var finished = new ArrayList<JobRow>();
            var poller =
                    fx(
                            () ->
                                    new JobsPoller(
                                            () -> client,
                                            () -> transport,
                                            finished::add,
                                            () -> {
                                                baseline.incrementAndGet();
                                                registry.refresh();
                                            }));
            Parent moduleView =
                    fx(
                            () -> {
                                var loader =
                                        new FXMLLoader(
                                                ModulesTabController.class.getResource(
                                                        "modules_tab.fxml"));
                                Parent root = loader.load();
                                ((ModulesTabController) loader.getController())
                                        .init(
                                                null,
                                                new ModuleGatewayClient(transport),
                                                registry,
                                                poller::refresh,
                                                poller::refresh);
                                return root;
                            });
            Parent jobsView =
                    fx(
                            () -> {
                                var loader =
                                        new FXMLLoader(
                                                JobsTabController.class.getResource(
                                                        "jobs_tab.fxml"));
                                Parent root = loader.load();
                                var inference = mock(InferenceJobActions.class);
                                when(inference.inferenceRows())
                                        .thenReturn(
                                                javafx.collections.FXCollections
                                                        .observableArrayList());
                                when(inference.inferenceProblem())
                                        .thenReturn(
                                                new javafx.beans.property.ReadOnlyStringWrapper(
                                                        ""));
                                ((JobsTabController) loader.getController())
                                        .init(
                                                () -> client,
                                                () -> transport,
                                                poller,
                                                registry,
                                                ignored -> {},
                                                inference);
                                return root;
                            });
            Stage stage =
                    fx(
                            () -> {
                                var tabs =
                                        new TabPane(
                                                new Tab("Modules", moduleView),
                                                new Tab("Jobs", jobsView));
                                tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
                                var value = new Stage();
                                value.setTitle("HistoForge refresh verification — fixtures only");
                                value.setScene(new Scene(tabs, 900, 560));
                                value.getScene()
                                        .addEventFilter(InputEvent.ANY, event -> event.consume());
                                HistoforgeStyles.apply(value.getScene());
                                value.show();
                                return value;
                            });

            // Connection-gated startup runs both fetches together. Jobs' first snapshot queues a
            // second catalog fetch, even if the first catalog captured an older empty installation.
            Gate firstJobs = new Gate();
            Gate firstModules = new Gate();
            jobGate.set(firstJobs);
            moduleGate.set(firstModules);
            fx(
                    () -> {
                        poller.refresh();
                        return null;
                    });
            require(jobFetches.get() == 0, "Jobs fetched before connection succeeded");
            fx(
                    () -> {
                        poller.start();
                        registry.refresh();
                        return null;
                    });
            firstJobs.awaitEntered();
            firstModules.awaitEntered();
            fx(
                    () -> {
                        require(
                                text(moduleView, "#loadStatusLabel").equals("Loading modules…"),
                                "Initial Modules state");
                        require(
                                text(jobsView, "#loadStatusLabel").equals("Loading jobs…"),
                                "Initial Jobs state");
                        captureView(stage, moduleView, output.resolve("modules-loading.png"));
                        return null;
                    });
            InstalledModule module =
                    TrainingFormFixtures.module(
                            TrainingFormFixtures.capability(false), ignored -> {}, true);
            modules.set(List.of(module));
            failJobs.set(true);
            firstJobs.release();
            await(() -> poller.failedProperty().get() && !poller.loadingProperty().get());
            require(!fx(poller::hasBaseline), "Failed first fetch established an empty baseline");
            fx(
                    () -> {
                        captureView(stage, jobsView, output.resolve("jobs-initial-failure.png"));
                        return null;
                    });
            failJobs.set(false);
            fx(
                    () -> {
                        ((Button) jobsView.lookup("#refreshButton")).fire();
                        return null;
                    });
            await(() -> baseline.get() == 1 && !poller.loadingProperty().get());
            firstModules.release();
            await(() -> moduleFetches.get() == 2 && !registry.loadingProperty().get());
            require(
                    fx(() -> registry.asObservable().size()) == 1,
                    "Baseline follow-up lost installed module");
            int idleFetches = jobFetches.get();
            Thread.sleep(2300);
            require(jobFetches.get() == idleFetches, "Successful idle snapshot kept polling");

            // A submission's refresh must survive an in-flight empty snapshot; many refresh
            // requests coalesce into one follow-up rather than overlapping or disappearing.
            Gate staleIdle = new Gate();
            jobGate.set(staleIdle);
            fx(
                    () -> {
                        poller.refresh();
                        return null;
                    });
            staleIdle.awaitEntered();
            jobs.set(List.of(admin(JobPhase.QUEUED)));
            fx(
                    () -> {
                        for (int i = 0; i < 8; i++) poller.refresh();
                        return null;
                    });
            require(jobFetches.get() == idleFetches + 1, "Refreshes overlapped");
            staleIdle.release();
            await(() -> poller.jobs().size() == 1 && !poller.loadingProperty().get());
            require(jobFetches.get() == idleFetches + 2, "Pending refresh was lost or duplicated");

            // A partial failure cannot replace rows or imply idleness. Failure remains visible
            // until a successful retry, and known active work polls even with the window hidden.
            jobs.set(List.of());
            failJobs.set(true);
            fx(
                    () -> {
                        poller.refresh();
                        return null;
                    });
            await(() -> poller.failedProperty().get() && !poller.loadingProperty().get());
            require(
                    fx(() -> poller.jobs().size()) == 1,
                    "Partial failure replaced previous Jobs snapshot");
            fx(
                    () -> {
                        require(
                                text(jobsView, "#loadStatusLabel").contains("Couldn’t refresh"),
                                "Retry state not visible");
                        captureView(stage, jobsView, output.resolve("jobs-retrying.png"));
                        stage.hide();
                        return null;
                    });
            int failedFetches = jobFetches.get();
            jobs.set(List.of(admin(JobPhase.SUCCEEDED)));
            failJobs.set(false);
            await(
                    () ->
                            jobFetches.get() > failedFetches
                                    && !poller.loadingProperty().get()
                                    && !poller.failedProperty().get());
            require(fx(() -> finished.size()) == 1, "Terminal transition was not announced once");
            require(baseline.get() == 1, "Idle wake reset the baseline");

            // FINALIZING remains active despite completed epochs. The subsequent successful
            // terminal snapshot stops the actual native timer again.
            JsonObject finalizing = emptyPage.deepCopy();
            var finalizingRow =
                    TrainingJobsFixtures.page()
                            .getAsJsonArray("items")
                            .get(1)
                            .getAsJsonObject()
                            .deepCopy();
            finalizingRow.addProperty("state", "FINALIZING");
            finalizingRow.remove("result");
            finalizing.getAsJsonArray("items").add(finalizingRow);
            trainingPage.set(finalizing);
            fx(
                    () -> {
                        poller.start();
                        return null;
                    });
            await(
                    () ->
                            !poller.loadingProperty().get()
                                    && poller.jobs().stream().anyMatch(job -> !job.isTerminal()));
            int finalizingFetches = jobFetches.get();
            await(() -> jobFetches.get() > finalizingFetches && !poller.loadingProperty().get());
            trainingPage.set(emptyPage);
            await(
                    () ->
                            !poller.loadingProperty().get()
                                    && poller.jobs().stream().allMatch(JobRow::isTerminal));
            int terminalFetches = jobFetches.get();
            Thread.sleep(2300);
            require(jobFetches.get() == terminalFetches, "Terminal snapshot left a running timer");
            require(fx(() -> finished.size()) == 1, "Repeated terminal snapshot announced twice");

            // Reopening/manual refresh is read-only and wakes an idle poller. Clearing a row
            // during a fetch invalidates that response and requests one authoritative follow-up.
            Gate staleCleared = new Gate();
            jobGate.set(staleCleared);
            fx(
                    () -> {
                        ((Button) jobsView.lookup("#refreshButton")).fire();
                        return null;
                    });
            staleCleared.awaitEntered();
            jobs.set(List.of());
            fx(
                    () -> {
                        poller.forget(poller.jobs().getFirst());
                        return null;
                    });
            staleCleared.release();
            await(() -> !poller.loadingProperty().get() && poller.jobs().isEmpty());

            // Administrative error callbacks must discover a server-retained job from idle too.
            // Suppress only the notification; exercise each actual dialog's failure handler.
            var qupath = mock(QuPathGUI.class);
            when(qupath.getStage()).thenReturn(stage);
            for (String dialogName : List.of("InstallDialog", "PublishDialog")) {
                jobs.set(List.of(admin(JobPhase.QUEUED)));
                fx(
                        () -> {
                            Class<?> type =
                                    Class.forName("qupath.ext.histoforge.ui.modules." + dialogName);
                            boolean install = dialogName.equals("InstallDialog");
                            var constructor =
                                    type.getDeclaredConstructor(
                                            QuPathGUI.class,
                                            ModuleGatewayClient.class,
                                            install ? String.class : InstalledModule.class,
                                            Runnable.class);
                            constructor.setAccessible(true);
                            Object dialog =
                                    constructor.newInstance(
                                            qupath,
                                            client,
                                            install ? "owner/module" : module,
                                            (Runnable) poller::refresh);
                            var failureHandler =
                                    type.getDeclaredMethod("onFailed", Exception.class);
                            failureHandler.setAccessible(true);
                            try (var notifications =
                                    mockStatic(Class.forName("qupath.fx.dialogs.Dialogs"))) {
                                failureHandler.invoke(
                                        dialog,
                                        new java.io.IOException("Acceptance response lost"));
                                require(
                                        poller.loadingProperty().get(),
                                        dialogName + " did not wake Jobs");
                            } finally {
                                var windowField = type.getDeclaredField("stage");
                                windowField.setAccessible(true);
                                ((Stage) windowField.get(dialog)).close();
                            }
                            return null;
                        });
                await(() -> !poller.loadingProperty().get() && poller.jobs().size() == 1);
                jobs.set(List.of());
                fx(
                        () -> {
                            poller.refresh();
                            return null;
                        });
                await(() -> !poller.loadingProperty().get() && poller.jobs().isEmpty());
            }

            // Status-only changes retain the selected detail node and its open Workflow tab.
            Parent detail = fx(() -> (Parent) moduleView.lookup("#moduleDetails"));
            Gate refreshingModule = new Gate();
            moduleGate.set(refreshingModule);
            failModules.set(true);
            fx(
                    () -> {
                        stage.show();
                        ((TabPane) detail.lookup("#moduleDetailTabs"))
                                .getSelectionModel()
                                .select(1);
                        registry.refresh();
                        return null;
                    });
            refreshingModule.awaitEntered();
            fx(
                    () -> {
                        require(
                                detail == moduleView.lookup("#moduleDetails")
                                        && ((TabPane) detail.lookup("#moduleDetailTabs"))
                                                        .getSelectionModel()
                                                        .getSelectedIndex()
                                                == 1,
                                "Loading replaced selected module details");
                        captureView(stage, moduleView, output.resolve("modules-refreshing.png"));
                        return null;
                    });
            refreshingModule.release();
            await(() -> registry.failedProperty().get() && !registry.loadingProperty().get());
            fx(
                    () -> {
                        require(
                                detail == moduleView.lookup("#moduleDetails")
                                        && ((TabPane) detail.lookup("#moduleDetailTabs"))
                                                        .getSelectionModel()
                                                        .getSelectedIndex()
                                                == 1,
                                "Failure removed selected module details");
                        require(
                                moduleView.lookup("#retryButton").isVisible(),
                                "Catalog retry is missing");
                        captureView(stage, moduleView, output.resolve("modules-failed.png"));
                        applyQuPathStyle("Modena Dark", moduleView);
                        captureView(stage, moduleView, output.resolve("modules-failed-dark.png"));
                        applyQuPathStyle("Modena Light", moduleView);
                        return null;
                    });
            failModules.set(false);
            fx(
                    () -> {
                        ((Button) moduleView.lookup("#retryButton")).fire();
                        return null;
                    });
            await(() -> !registry.loadingProperty().get() && !registry.failedProperty().get());
            verifyRemovalFeedback(moduleView, registry, modules, module, server, stage, output);
            fx(
                    () -> {
                        ((TextField) moduleView.lookup("#searchField")).setText("no-such-module");
                        require(
                                text(moduleView, "#emptyStateLabel").startsWith("No modules match"),
                                "Search state confused with empty catalog");
                        return null;
                    });
            modules.set(List.of());
            fx(
                    () -> {
                        registry.refresh();
                        return null;
                    });
            await(() -> !registry.loadingProperty().get() && registry.asObservable().isEmpty());
            fx(
                    () -> {
                        require(
                                text(moduleView, "#emptyStateLabel")
                                        .equals("No modules installed."),
                                "Empty catalog state");
                        captureView(stage, moduleView, output.resolve("modules-empty.png"));
                        var displayed =
                                javafx.collections.FXCollections
                                        .<JobRow.Inference>observableArrayList();
                        poller.observeInference(displayed);
                        var now = Instant.now();
                        displayed.setAll(
                                new JobRow.Inference(
                                        "project",
                                        "action-a",
                                        "slide-a",
                                        "Slide A",
                                        "histoforge/leydig",
                                        "Running in cloud",
                                        now,
                                        true,
                                        false,
                                        false,
                                        "Completed 4/12 processing regions",
                                        "Reconnect to analysis",
                                        "",
                                        "Stop analysis",
                                        Optional.empty(),
                                        false),
                                new JobRow.Inference(
                                        "project",
                                        "action-b",
                                        "slide-b",
                                        "Slide B",
                                        "histoforge/tubule",
                                        "Needs attention",
                                        now.minusSeconds(1),
                                        false,
                                        false,
                                        false,
                                        "Network interrupted",
                                        "Continue analysis",
                                        "",
                                        "Stop analysis",
                                        Optional.of("Network interrupted"),
                                        false),
                                new JobRow.Inference(
                                        "project",
                                        "action-c",
                                        "slide-c",
                                        "Slide C",
                                        "histoforge/ignite",
                                        "Results saved",
                                        now.minusSeconds(2),
                                        false,
                                        true,
                                        true,
                                        "Results saved in QuPath",
                                        "",
                                        "",
                                        "",
                                        Optional.empty(),
                                        false));
                        captureView(stage, jobsView, output.resolve("inference-jobs.png"));
                        for (var node : jobsView.lookupAll(".button")) {
                            if (node instanceof Button button
                                    && button.isVisible()
                                    && button.getWidth() > 0)
                                require(
                                        button.getWidth() >= button.prefWidth(-1) - 1,
                                        "Clipped Jobs action: " + button.getText());
                        }
                        stage.close();
                        return null;
                    });
            if (UNCAUGHT_FX_ERROR.get() != null)
                throw new AssertionError("Uncaught FX failure", UNCAUGHT_FX_ERROR.get());
            System.out.println(
                    "PASS: connection header states; native loading/error/empty states; idle stop; pending refresh; partial failure; baseline; finalization; hidden-window polling; clear-generation; explicit catalog retry.");
        } finally {
            server.stop(0);
            Platform.exit();
        }
    }

    private static void verifyRemovalFeedback(
            Parent view,
            ModulesRegistry registry,
            AtomicReference<List<InstalledModule>> modules,
            InstalledModule module,
            HttpServer server,
            Stage stage,
            Path output)
            throws Exception {
        var gate = new AtomicReference<Gate>();
        var requests = new AtomicInteger();
        var fail = new AtomicBoolean();
        var removalError =
                new GatewayErrorCodec.Error(
                        1,
                        GatewayErrorCodec.Code.SERVICE_UNAVAILABLE,
                        "Synthetic removal failure",
                        Optional.empty(),
                        Optional.empty());
        byte[] errorBody =
                GatewayErrorCodec.toJson(removalError).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/v1/modules/" + module.ref(),
                exchange -> {
                    try {
                        require(exchange.getRequestMethod().equals("DELETE"), "Expected DELETE");
                        require(
                                "Bearer fixture-token"
                                        .equals(
                                                exchange.getRequestHeaders()
                                                        .getFirst("Authorization")),
                                "Missing fixture authentication");
                        requests.incrementAndGet();
                        Gate current = gate.getAndSet(null);
                        if (current != null) current.block();
                        if (fail.get()) {
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(503, errorBody.length);
                            exchange.getResponseBody().write(errorBody);
                        } else {
                            modules.set(List.of());
                            exchange.sendResponseHeaders(204, -1);
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("Interrupted fixture response", error);
                    } finally {
                        exchange.close();
                    }
                });
        var notifications =
                fx(
                        () -> {
                            var value = mockStatic(Dialogs.class);
                            value.when(
                                            () ->
                                                    Dialogs.showYesNoDialog(
                                                            "Remove module",
                                                            removalConfirmation(module)))
                                    .thenReturn(false);
                            removalAction(view).fire();
                            require(requests.get() == 0, "Cancelled removal reached the server");
                            require(
                                    !view.lookup("#loadStatus").isVisible(),
                                    "Cancelled removal looks active");
                            value.when(
                                            () ->
                                                    Dialogs.showYesNoDialog(
                                                            "Remove module",
                                                            removalConfirmation(module)))
                                    .thenReturn(true);
                            return value;
                        });
        Gate success = new Gate();
        Gate failure = new Gate();
        try {
            gate.set(success);
            fx(
                    () -> {
                        removalAction(view).fire();
                        return null;
                    });
            success.awaitEntered();
            fx(
                    () -> {
                        require(
                                text(view, "#loadStatusLabel")
                                        .equals("Removing " + module.ref() + "…"),
                                "Missing removal progress");
                        require(
                                view.lookup("#loadStatus").isVisible()
                                        && view.lookup("#loadingIndicator").isVisible(),
                                "Missing removal spinner");
                        require(removalAction(view).isDisable(), "Removal action was not disabled");
                        require(
                                registry.asObservable().size() == 1,
                                "Pending removal hid the module");
                        removalAction(view).fire();
                        captureView(stage, view, output.resolve("module-removal-pending.png"));
                        applyQuPathStyle("Modena Dark", view);
                        captureView(stage, view, output.resolve("module-removal-pending-dark.png"));
                        applyQuPathStyle("Modena Light", view);
                        registry.refresh();
                        return null;
                    });
            await(() -> !registry.loadingProperty().get());
            fx(
                    () -> {
                        require(
                                removalAction(view).isDisable(),
                                "Catalog refresh re-enabled duplicate removal");
                        require(
                                text(view, "#loadStatusLabel").startsWith("Removing "),
                                "Catalog refresh hid removal progress");
                        require(requests.get() == 1, "Removal was submitted more than once");
                        notifications.verify(
                                () ->
                                        Dialogs.showInfoNotification(
                                                "Module removed", module.ref() + " was removed."),
                                never());
                        return null;
                    });
            success.release();
            await(() -> !registry.loadingProperty().get() && registry.asObservable().isEmpty());
            fx(
                    () -> {
                        require(
                                !view.lookup("#loadStatus").isVisible(),
                                "Completed removal stayed busy");
                        notifications.verify(
                                () ->
                                        Dialogs.showInfoNotification(
                                                "Module removed", module.ref() + " was removed."),
                                times(1));
                        notifications.verify(
                                () ->
                                        Dialogs.showYesNoDialog(
                                                "Remove module", removalConfirmation(module)),
                                times(2));
                        notifications.clearInvocations();
                        return null;
                    });

            modules.set(List.of(module));
            fx(
                    () -> {
                        registry.refresh();
                        return null;
                    });
            await(() -> !registry.loadingProperty().get() && registry.asObservable().size() == 1);
            gate.set(failure);
            fail.set(true);
            fx(
                    () -> {
                        removalAction(view).fire();
                        return null;
                    });
            failure.awaitEntered();
            failure.release();
            await(() -> !removalAction(view).isDisable());
            fx(
                    () -> {
                        require(
                                registry.asObservable().size() == 1,
                                "Failed removal hid the module");
                        require(
                                !view.lookup("#loadStatus").isVisible(),
                                "Failed removal stayed busy");
                        require(requests.get() == 2, "Failure caused an automatic retry");
                        notifications.verify(
                                () ->
                                        Dialogs.showErrorMessage(
                                                "Module removal not confirmed",
                                                module.ref()
                                                        + ": SERVICE_UNAVAILABLE: Synthetic removal failure"),
                                times(1));
                        notifications.verify(
                                () ->
                                        Dialogs.showInfoNotification(
                                                "Module removed", module.ref() + " was removed."),
                                never());
                        captureView(stage, view, output.resolve("module-removal-failed.png"));
                        return null;
                    });
        } finally {
            success.release();
            failure.release();
            Gate pending = gate.getAndSet(null);
            if (pending != null) pending.release();
            fx(
                    () -> {
                        notifications.close();
                        return null;
                    });
        }
        JsonObject evidence = new JsonObject();
        evidence.addProperty("status", "PASS");
        evidence.addProperty("java", Runtime.version().toString());
        evidence.addProperty("os", System.getProperty("os.name"));
        evidence.addProperty("arch", System.getProperty("os.arch"));
        evidence.addProperty(
                "qupath", System.getProperty("histoforge.test.qupath.version", "unrecorded"));
        evidence.addProperty("delete_requests", requests.get());
        evidence.addProperty(
                "removal_transport",
                "Packaged ModuleGatewayClient and GatewayTransport; controlled authenticated loopback HTTP204/503 responses");
        evidence.addProperty(
                "dialog_boundary",
                "Confirmation decisions and notification/error calls are intercepted; native popup interaction and rendered toast/error layout are not qualified");
        evidence.addProperty(
                "controller_origin",
                ModulesTabController.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toExternalForm());
        var fxml = ModulesTabController.class.getResource("modules_tab.fxml");
        evidence.addProperty("fxml_origin", fxml.toExternalForm());
        String artifact = System.getProperty("histoforge.test.distribution.artifact");
        if (artifact != null) {
            Path jar = Path.of(artifact).toRealPath();
            for (Class<?> type :
                    List.of(
                            ModulesTabController.class,
                            ModuleGatewayClient.class,
                            GatewayTransport.class,
                            Class.forName("qupath.ext.histoforge.ui.modules.ModuleDetailsView"))) {
                require(
                        Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                                .toRealPath()
                                .equals(jar),
                        "Production class did not load from the packaged JAR");
            }
            require(
                    fxml.openConnection() instanceof JarURLConnection,
                    "FXML did not load from a JAR");
            var connection = (JarURLConnection) fxml.openConnection();
            require(
                    Path.of(connection.getJarFileURL().toURI()).toRealPath().equals(jar),
                    "FXML loaded from the wrong JAR");
            evidence.addProperty(
                    "jar_sha256",
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(jar))));
        }
        JsonArray checks = new JsonArray();
        for (String check :
                List.of(
                        "cancel_no_request",
                        "visible_progress_while_blocked",
                        "duplicate_removal_prevented",
                        "refresh_preserves_pending_state",
                        "success_after_server_confirmation",
                        "failure_preserves_catalog",
                        "failure_clears_busy_without_retry")) checks.add(check);
        evidence.add("checks", checks);
        Files.writeString(output.resolve("module-removal-feedback.json"), evidence.toString());
    }

    private static String removalConfirmation(InstalledModule module) throws Exception {
        var method =
                ModulesTabController.class.getDeclaredMethod("deleteConfirmation", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, module.ref());
    }

    private static MenuItem removalAction(Parent view) {
        return ((MenuButton) view.lookup("#moduleActions"))
                .getItems().stream()
                        .filter(item -> item.getText().equals("Remove module…"))
                        .findFirst()
                        .orElseThrow();
    }

    private static void verifyConnectionHeader(Path output) throws Exception {
        connectionHeaderStep("FXML load started");
        var loader = new FXMLLoader(MainController.class.getResource("main.fxml"));
        Parent root = loader.load();
        connectionHeaderStep("FXML load completed");
        MainController controller = loader.getController();
        var progress = (Label) root.lookup("#connectionProgress");
        require(!progress.isVisible() && !progress.isManaged(), "Initial connection label");
        require(progress.getText().equals("Connecting…"), "Connection label wording");
        var checking = MainController.class.getDeclaredMethod("renderChecking");
        checking.setAccessible(true);
        var stage = new Stage();
        stage.setTitle("HistoForge connection verification — fixtures only");
        stage.setScene(new Scene(root, 700, 100));
        stage.getScene().addEventFilter(InputEvent.ANY, event -> event.consume());
        HistoforgeStyles.apply(stage.getScene());
        connectionHeaderStep("scene and extension style prepared");
        try {
            checking.invoke(controller);
            connectionHeaderStep("Stage.show started");
            stage.show();
            connectionHeaderStep("Stage.show completed");
            for (String theme : List.of("Modena Light", "Modena Dark")) {
                connectionHeaderStep(theme + " style started");
                applyQuPathStyle(theme, root);
                connectionHeaderStep(theme + " CSS started");
                root.applyCss();
                connectionHeaderStep(theme + " layout started");
                root.layout();
                connectionHeaderStep(theme + " layout completed");
                require(
                        progress.getBoundsInParent().getMaxX()
                                <= root.lookup("#connectionChip").getBoundsInParent().getMinX(),
                        "Connection label must sit left of the indicator");
                connectionHeaderStep(theme + " snapshot and PNG write started");
                capture(root, output.resolve("connecting-" + theme.replace(' ', '-') + ".png"));
                connectionHeaderStep(theme + " snapshot and PNG write completed");
            }
            for (String state :
                    List.of(
                            "renderConnected",
                            "renderUnreachable",
                            "renderAuthenticationRequired",
                            "renderIncompatible",
                            "renderRestartRequired")) {
                connectionHeaderStep(state + " assertions started");
                checking.invoke(controller);
                require(progress.isVisible() && progress.isManaged(), "Missing connecting label");
                require(!root.lookup("#connectionBanner").isVisible(), "Stale error on retry");
                var render =
                        state.equals("renderIncompatible")
                                ? MainController.class.getDeclaredMethod(
                                        state, ConnectionProbe.Result.class)
                                : MainController.class.getDeclaredMethod(state);
                render.setAccessible(true);
                if (state.equals("renderIncompatible"))
                    render.invoke(
                            controller,
                            new ConnectionProbe.Result(
                                    ConnectionProbe.Status.INCOMPATIBLE,
                                    Optional.of(
                                            new BackendCompatibility.Decision(
                                                    BackendCompatibility.State.UNKNOWN,
                                                    Optional.empty(),
                                                    "Synthetic incompatible backend")),
                                    false));
                else render.invoke(controller);
                require(
                        !progress.isVisible() && !progress.isManaged(),
                        "Connection label remained after " + state);
                require(
                        root.lookup("#connectionBanner").isVisible()
                                == !state.equals("renderConnected"),
                        "Connection error banner changed for " + state);
                connectionHeaderStep(state + " assertions completed");
            }
        } finally {
            connectionHeaderStep("Stage.close started");
            stage.close();
            connectionHeaderStep("Stage.close completed");
        }
    }

    private static void connectionHeaderStep(String step) {
        CONNECTION_HEADER_STEP.set(step);
        System.err.println(
                "Connection header ["
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - STARTED_NANOS)
                        + " ms]: "
                        + step);
    }

    private static AdminJobStatus admin(JobPhase phase) throws Exception {
        var value =
                TrainingJobsFixtures.fixture("admin-jobs-v1.json")
                        .getAsJsonArray("statusCases")
                        .get(phase == JobPhase.QUEUED ? 0 : 1)
                        .getAsJsonObject()
                        .getAsJsonObject("value");
        value.addProperty("job_id", "11111111-1111-4111-8111-111111111111");
        return AdminJobCodec.parseStatus(value);
    }

    private static String text(Parent root, String selector) {
        return ((Label) root.lookup(selector)).getText();
    }

    private static void captureView(Stage stage, Parent view, Path path) {
        var tabs = (TabPane) stage.getScene().getRoot();
        tabs.getSelectionModel()
                .select(
                        tabs.getTabs().stream()
                                .filter(tab -> tab.getContent() == view)
                                .findFirst()
                                .orElseThrow());
        tabs.applyCss();
        tabs.layout();
        capture(view, path);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return awaitFxTask(task);
    }

    static <T> T awaitFxTask(FutureTask<T> task) throws Exception {
        try {
            return task.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            // Read stacks from this worker; the FX thread may be blocked or not running the task.
            // Keep the original deadline, exception and outer cleanup; never cancel the task here.
            try {
                System.err.print(
                        fxTimeoutDetails(
                                CONNECTION_HEADER_STEP.get(),
                                UNCAUGHT_FX_ERROR.get(),
                                Thread.getAllStackTraces()));
            } catch (RuntimeException diagnosticFailure) {
                error.addSuppressed(diagnosticFailure);
            }
            throw error;
        }
    }

    static String fxTimeoutDetails(
            String headerStep, Throwable fxError, Map<Thread, StackTraceElement[]> stacks) {
        int maxCharacters = 16_384;
        var message =
                new StringBuilder("FX task exceeded its existing 10-second deadline.\n")
                        .append("Last connection-header step: ")
                        .append(headerStep)
                        .append('\n');
        if (fxError != null) {
            message.append("Uncaught FX error: ").append(fxError).append('\n');
            var frames = fxError.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 32); i++)
                message.append("  at ").append(frames[i]).append('\n');
        }
        var threads = new ArrayList<>(stacks.entrySet());
        threads.sort(
                Comparator.comparingInt(
                                (Map.Entry<Thread, StackTraceElement[]> entry) -> {
                                    String name = entry.getKey().getName();
                                    return name.contains("JavaFX")
                                                    || name.contains("Quantum")
                                                    || name.startsWith("AWT")
                                                    || name.contains("Java2D")
                                            ? 0
                                            : 1;
                                })
                        .thenComparing(entry -> entry.getKey().getName())
                        .thenComparingLong(entry -> entry.getKey().threadId()));
        int threadCount = 0;
        snapshot:
        for (var entry : threads) {
            if (threadCount++ == 64) {
                message.append("Additional threads omitted.\n");
                break;
            }
            Thread thread = entry.getKey();
            message.append("Thread ")
                    .append(thread.getName())
                    .append(" [")
                    .append(thread.getState())
                    .append("]\n");
            StackTraceElement[] frames = entry.getValue();
            for (int i = 0; i < Math.min(frames.length, 32); i++) {
                message.append("  at ").append(frames[i]).append('\n');
                if (message.length() >= maxCharacters) break snapshot;
            }
            if (frames.length > 32) message.append("  Additional frames omitted.\n");
        }
        if (message.length() > maxCharacters) {
            String marker = "\n[Thread snapshot truncated]\n";
            return message.substring(0, maxCharacters - marker.length()) + marker;
        }
        return message.toString();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(condition)) {
            if (System.nanoTime() >= deadline)
                throw new AssertionError("Timed out waiting for refresh");
            Thread.sleep(20);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void block() throws InterruptedException {
            entered.countDown();
            if (!released.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Unreleased fetch gate");
        }

        void awaitEntered() throws InterruptedException {
            require(entered.await(10, TimeUnit.SECONDS), "Fetch did not start");
        }

        void release() {
            released.countDown();
        }
    }
}
