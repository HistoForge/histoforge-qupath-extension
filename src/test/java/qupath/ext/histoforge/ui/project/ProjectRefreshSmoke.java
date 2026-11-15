package qupath.ext.histoforge.ui.project;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import qupath.ext.histoforge.data.ProjectInferenceScope;
import qupath.ext.histoforge.data.ProjectSummaryData;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;

/** Native JavaFX scheduling tests with controlled workers; no images, cloud calls or saves. */
public final class ProjectRefreshSmoke {
    private ProjectRefreshSmoke() {}

    public static void main(String[] args) throws Exception {
        var started = new CountDownLatch(1);
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    started.countDown();
                });
        require(started.await(10, TimeUnit.SECONDS), "FX startup timed out");
        try {
            summaryPrecedesHeldValidation();
            coalescesBeforeCaptureAndRejectsStaleCallbacks();
            cancellationAndFailuresFinishExactlyOnce();
            reentrantCompletionPreservesNewestRequest();
            hiddenWindowsAndMissingScenesDoNotStrandRefresh();
            saveAndRefreshReleasesBusyBeforeOptionalRefresh();
            ProjectRefreshControllerChecks.run();
            System.out.println(
                    "PASS: summary-first native pulse, held validation, coalescing, stale responses, reentry, cancellation, disposal, hidden-window bypass and startup failure.");
        } finally {
            Platform.exit();
        }
    }

    private static void summaryPrecedesHeldValidation() throws Exception {
        var harness = fx(() -> new Harness(true));
        try {
            fx(
                    () -> {
                        harness.request("first");
                        harness.summaries.getFirst().success.accept(harness.result);
                        harness.stage.getScene().addPostLayoutPulseListener(harness::markPulse);
                        harness.requirePulse = true;
                        require(
                                harness.label.getText().equals("first"),
                                "Summary not available immediately");
                        require(
                                harness.validations.isEmpty(),
                                "Validation ran inside summary callback");
                        require(harness.completed.get() == 0, "Validation already completed");
                        return null;
                    });
            require(
                    harness.validationStarted.await(10, TimeUnit.SECONDS),
                    "Pulse never dispatched validation");
            fx(
                    () -> {
                        require(
                                harness.label.getText().equals("first"),
                                "Held validation hid summary");
                        require(harness.completed.get() == 0, "Held validation ended early");
                        harness.validations.getFirst().success.accept(harness.result.summary());
                        require(
                                harness.attached.get() == 1 && harness.completed.get() == 1,
                                "Validation not attached exactly once");
                        return null;
                    });
        } finally {
            fx(
                    () -> {
                        harness.close();
                        return null;
                    });
        }
    }

    private static void coalescesBeforeCaptureAndRejectsStaleCallbacks() throws Exception {
        var harness = fx(() -> new Harness(false));
        fx(
                () -> {
                    harness.request("old");
                    harness.request("discarded");
                    harness.request("new");
                    require(
                            harness.captures.equals(List.of("old")),
                            "Pending requests captured hierarchies");
                    require(harness.completed.get() == 1, "Displaced request not completed");
                    harness.summaries.getFirst().success.accept(harness.result);
                    require(
                            harness.captures.equals(List.of("old", "new")),
                            "Newest request not admitted");
                    require(harness.label.getText().isEmpty(), "Stale summary was applied");
                    harness.summaries.get(1).success.accept(harness.result);
                    return null;
                });
        require(
                harness.validationStarted.await(10, TimeUnit.SECONDS),
                "New validation did not start");
        fx(
                () -> {
                    harness.session.cancel(); // module reassignment / new action invalidation
                    harness.validations.getFirst().success.accept(harness.result.summary());
                    require(harness.attached.get() == 0, "Invalidated measurements reappeared");
                    require(harness.completed.get() == 3, "Completion count changed");
                    harness.close();
                    return null;
                });
    }

    private static void cancellationAndFailuresFinishExactlyOnce() throws Exception {
        fx(
                () -> {
                    var harness = new Harness(false);
                    harness.request("cancelled pulse");
                    harness.summaries.getFirst().success.accept(harness.result);
                    harness.session.cancel();
                    require(harness.completed.get() == 1, "Pulse cancellation did not finish");
                    harness.session.request(
                            () -> {
                                throw new IllegalStateException("capture startup");
                            },
                            ignored -> {},
                            ignored -> {},
                            ignored -> harness.failed.incrementAndGet(),
                            harness.completed::incrementAndGet);
                    require(
                            harness.failed.get() == 1 && harness.completed.get() == 2,
                            "Startup failure leaked ownership");
                    harness.request("worker failure");
                    harness.summaries.get(1).failure.accept(new IllegalStateException("worker"));
                    require(
                            harness.failed.get() == 2 && harness.completed.get() == 3,
                            "Worker failure leaked ownership");
                    harness.close();
                    harness.request("closed");
                    require(
                            harness.completed.get() == 4 && harness.captures.size() == 2,
                            "Closed session captured work");
                    return null;
                });
        fx(() -> null); // cancelled runLater continuation must be harmless
    }

    private static void reentrantCompletionPreservesNewestRequest() throws Exception {
        fx(
                () -> {
                    var harness = new Harness(false);
                    harness.request("active");
                    harness.request("pending", () -> harness.request("reentrant newest"));
                    harness.request("incoming");
                    harness.summaries.getFirst().success.accept(harness.result);
                    require(
                            harness.captures.equals(List.of("active", "reentrant newest")),
                            "Reentrant newest request was overwritten");
                    harness.close();
                    harness.summaries.get(1).failure.accept(new CancellationException());
                    return null;
                });
    }

    private static void hiddenWindowsAndMissingScenesDoNotStrandRefresh() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            final int current = mode;
            var harness =
                    fx(
                            () -> {
                                var h = new Harness(current != 0);
                                if (current == 1) h.stage.hide();
                                h.request("hidden");
                                h.summaries.getFirst().success.accept(h.result);
                                if (current == 2)
                                    h.stage.hide(); // hide between registration and pulse
                                return h;
                            });
            require(
                    harness.validationStarted.await(10, TimeUnit.SECONDS),
                    "Hidden/no-scene refresh stranded");
            fx(
                    () -> {
                        harness.validations.getFirst().success.accept(harness.result.summary());
                        require(harness.completed.get() == 1, "Window transition dispatched twice");
                        harness.close();
                        return null;
                    });
        }
    }

    @SuppressWarnings("unchecked")
    private static void saveAndRefreshReleasesBusyBeforeOptionalRefresh() throws Exception {
        fx(
                () -> {
                    var controller = spy(new ProjectTabController());
                    var loader =
                            new javafx.fxml.FXMLLoader(
                                    ProjectTabController.class.getResource("project_tab.fxml"));
                    loader.setControllerFactory(type -> controller);
                    loader.load();
                    var qupath = mock(qupath.lib.gui.QuPathGUI.class);
                    var project =
                            (qupath.lib.projects.Project<java.awt.image.BufferedImage>)
                                    mock(qupath.lib.projects.Project.class);
                    var entry =
                            (qupath.lib.projects.ProjectImageEntry<java.awt.image.BufferedImage>)
                                    mock(qupath.lib.projects.ProjectImageEntry.class);
                    var image =
                            (qupath.lib.images.ImageData<java.awt.image.BufferedImage>)
                                    mock(qupath.lib.images.ImageData.class);
                    var lifecycle = mock(qupath.ext.histoforge.pipeline.ImageLifecycle.class);
                    when(qupath.getProject()).thenReturn(project);
                    when(qupath.getImageData()).thenReturn(image);
                    when(project.getEntry(image)).thenReturn(entry);
                    set(controller, "qupath", qupath);
                    set(controller, "imageLifecycle", lifecycle);
                    var busy = ProjectTabController.class.getDeclaredField("saveRefreshRunning");
                    busy.setAccessible(true);
                    var events = new ArrayList<String>();
                    doAnswer(
                                    call -> {
                                        require(
                                                busy.getBoolean(controller),
                                                "Save lost busy state");
                                        events.add("save");
                                        return null;
                                    })
                            .when(lifecycle)
                            .saveCurrentViewerImage(entry, image);
                    doAnswer(
                                    call -> {
                                        require(
                                                busy.getBoolean(controller),
                                                "Sync lost busy state");
                                        events.add("sync");
                                        return null;
                                    })
                            .when(project)
                            .syncChanges();
                    doAnswer(
                                    call -> {
                                        require(
                                                !busy.getBoolean(controller),
                                                "Optional refresh retained save busy state");
                                        events.add("refresh");
                                        return null;
                                    })
                            .when(controller)
                            .loadProjectData();
                    var action =
                            ProjectTabController.class.getDeclaredMethod("handleSaveAndRefresh");
                    action.setAccessible(true);
                    action.invoke(controller);
                    require(
                            events.equals(List.of("save", "sync", "refresh")),
                            "Public save/sync/refresh order changed");
                    // Neither a failed save nor a failed sync starts optional refresh or strands
                    // busy.
                    try (var dialogs = mockStatic(Class.forName("qupath.fx.dialogs.Dialogs"))) {
                        events.clear();
                        doThrow(new java.io.IOException("save failed"))
                                .when(lifecycle)
                                .saveCurrentViewerImage(entry, image);
                        action.invoke(controller);
                        require(
                                !busy.getBoolean(controller) && events.isEmpty(),
                                "Failed save retained busy or refreshed");
                        doNothing().when(lifecycle).saveCurrentViewerImage(entry, image);
                        doThrow(new java.io.IOException("sync failed")).when(project).syncChanges();
                        action.invoke(controller);
                        require(
                                !busy.getBoolean(controller) && events.isEmpty(),
                                "Failed sync retained busy or refreshed");
                    }
                    return null;
                });
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = ProjectTabController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Harness {
        final ProjectDataLoader loader = mock(ProjectDataLoader.class);
        final List<Callback<ProjectDataLoader.Result>> summaries = new ArrayList<>();
        final List<Callback<ProjectSummaryData>> validations = new ArrayList<>();
        final List<String> captures = new ArrayList<>();
        final AtomicInteger completed = new AtomicInteger(),
                attached = new AtomicInteger(),
                failed = new AtomicInteger();
        final CountDownLatch validationStarted = new CountDownLatch(1);
        boolean requirePulse;
        boolean pulseObserved;

        void markPulse() {
            pulseObserved = true;
        }

        final Label label = new Label();
        final Stage stage;
        final ProjectRefreshSession session;
        final ProjectDataLoader.Result result =
                new ProjectDataLoader.Result(mock(ProjectSummaryData.class), List.of());

        Harness(boolean visible) {
            if (visible) {
                stage = new Stage();
                stage.setScene(new Scene(new StackPane(label), 320, 100));
                stage.setTitle("HistoForge refresh scheduling test");
                stage.show();
            } else stage = null;
            doAnswer(
                            call -> {
                                summaries.add(
                                        new Callback<>(
                                                call.getArgument(1),
                                                call.getArgument(2),
                                                call.getArgument(3)));
                                return null;
                            })
                    .when(loader)
                    .loadSummary(any(), any(), any(), any());
            doAnswer(
                            call -> {
                                require(
                                        Platform.isFxApplicationThread(),
                                        "Validation dispatch lost FX ownership");
                                require(
                                        !requirePulse || pulseObserved,
                                        "Validation started before the post-summary pulse");
                                validations.add(
                                        new Callback<>(
                                                call.getArgument(3),
                                                call.getArgument(4),
                                                call.getArgument(5)));
                                validationStarted.countDown();
                                return null;
                            })
                    .when(loader)
                    .validateMetrics(any(), any(), any(), any(), any(), any());
            session =
                    new ProjectRefreshSession(
                            loader, () -> stage == null ? null : stage.getScene());
        }

        void request(String name) {
            request(name, () -> {});
        }

        void request(String name, Runnable afterComplete) {
            session.request(
                    () -> {
                        require(
                                Platform.isFxApplicationThread(),
                                "Hierarchy capture is not on FX thread");
                        captures.add(name);
                        return new ProjectDataLoader.Request(
                                List.of(),
                                Optional.empty(),
                                Optional.empty(),
                                List.of(),
                                LocalResourceEnvelope.SAFE,
                                ProjectInferenceScope.defaults());
                    },
                    ignored -> label.setText(name),
                    ignored -> attached.incrementAndGet(),
                    ignored -> failed.incrementAndGet(),
                    () -> {
                        completed.incrementAndGet();
                        afterComplete.run();
                    });
        }

        void close() {
            session.close();
            if (stage != null) stage.close();
        }
    }

    private record Callback<T>(
            BooleanSupplier stopped, Consumer<T> success, Consumer<Exception> failure) {}

    private static <T> T fx(Callable<T> action) throws Exception {
        var result = new FutureTask<>(action);
        Platform.runLater(result);
        return result.get(15, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
