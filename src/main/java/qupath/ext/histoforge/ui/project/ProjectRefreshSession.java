package qupath.ext.histoforge.ui.project;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.data.ProjectSummaryData;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;

/**
 * FX-owned refresh scheduling: one worker, one uncaptured pending request, two display stages.
 * Cancellation fences callbacks but never interrupts an image capture holding its lease.
 */
final class ProjectRefreshSession implements AutoCloseable {
    private final ProjectDataLoader loader;
    private final Supplier<Scene> scene;
    private Refresh active;
    private Refresh pending;
    private boolean closed;

    ProjectRefreshSession(ProjectDataLoader loader, Supplier<Scene> scene) {
        this.loader = loader;
        this.scene = scene;
    }

    void request(
            Supplier<ProjectDataLoader.Request> capture,
            Consumer<ProjectDataLoader.Result> summary,
            Consumer<ProjectSummaryData> measurements,
            Consumer<Exception> failure,
            Runnable complete) {
        Refresh displaced = pending;
        pending = null;
        if (closed) {
            notifyComplete(complete);
            return;
        }
        pending = new Refresh(capture, summary, measurements, failure, complete);
        cancelActive();
        if (displaced != null) notifyComplete(displaced.complete);
        startPending();
    }

    /** Also used before hierarchy edits, module reassignment, and explicit image actions. */
    void cancel() {
        Refresh obsolete = pending;
        pending = null;
        cancelActive();
        if (obsolete != null) notifyComplete(obsolete.complete);
    }

    private void cancelActive() {
        if (active != null) {
            active.cancelled = true;
            if (active.pulse != null) {
                active.pulse.close();
                finish(active);
            }
        }
    }

    private void startPending() {
        if (active != null || pending == null || closed) return;
        Refresh refresh = pending;
        pending = null;
        active = refresh;
        try {
            ProjectDataLoader.Request request = refresh.capture.get();
            refresh.capture = null;
            // Copy only validation parameters into the callback, never the hierarchy capture.
            List<InstalledModule> modules = request.installedModules();
            LocalResourceEnvelope envelope = request.resourceEnvelope();
            loader.loadSummary(
                    request,
                    () -> refresh.cancelled,
                    result -> summaryReady(refresh, result, modules, envelope),
                    failure -> failed(refresh, failure));
        } catch (RuntimeException failure) {
            failed(refresh, failure);
        }
    }

    private void summaryReady(
            Refresh refresh,
            ProjectDataLoader.Result result,
            List<InstalledModule> modules,
            LocalResourceEnvelope envelope) {
        if (refresh.cancelled) {
            finish(refresh);
            return;
        }
        try {
            refresh.summary.accept(result);
            if (refresh.cancelled) {
                finish(refresh);
                return;
            }
            refresh.pulse =
                    new PulseContinuation(
                            scene.get(),
                            () -> {
                                refresh.pulse = null;
                                if (refresh.cancelled) {
                                    finish(refresh);
                                    return;
                                }
                                try {
                                    loader.validateMetrics(
                                            result.summary(),
                                            modules,
                                            envelope,
                                            () -> refresh.cancelled,
                                            measured -> {
                                                try {
                                                    if (!refresh.cancelled)
                                                        refresh.measurements.accept(measured);
                                                } catch (RuntimeException failure) {
                                                    if (!refresh.cancelled)
                                                        refresh.failure.accept(failure);
                                                } finally {
                                                    finish(refresh);
                                                }
                                            },
                                            failure -> failed(refresh, failure));
                                } catch (RuntimeException failure) {
                                    failed(refresh, failure);
                                }
                            });
        } catch (RuntimeException failure) {
            failed(refresh, failure);
        }
    }

    private void failed(Refresh refresh, Exception failure) {
        try {
            if (!refresh.cancelled) refresh.failure.accept(failure);
        } finally {
            finish(refresh);
        }
    }

    private void finish(Refresh refresh) {
        if (active != refresh) return;
        if (refresh.pulse != null) refresh.pulse.close();
        active = null;
        try {
            notifyComplete(refresh.complete);
        } finally {
            startPending();
        }
    }

    @Override
    public void close() {
        closed = true;
        cancel();
    }

    private static void notifyComplete(Runnable complete) {
        try {
            complete.run();
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(ProjectRefreshSession.class)
                    .warn("Project refresh completion callback failed", failure);
        }
    }

    private static final class Refresh {
        private Supplier<ProjectDataLoader.Request> capture;
        private final Consumer<ProjectDataLoader.Result> summary;
        private final Consumer<ProjectSummaryData> measurements;
        private final Consumer<Exception> failure;
        private final Runnable complete;
        private volatile boolean cancelled;
        private PulseContinuation pulse;

        private Refresh(
                Supplier<ProjectDataLoader.Request> capture,
                Consumer<ProjectDataLoader.Result> summary,
                Consumer<ProjectSummaryData> measurements,
                Consumer<Exception> failure,
                Runnable complete) {
            this.capture = capture;
            this.summary = summary;
            this.measurements = measurements;
            this.failure = failure;
            this.complete = complete;
        }
    }

    /** One rendering opportunity, not a promise that the physical display has refreshed. */
    private static final class PulseContinuation implements Runnable, AutoCloseable {
        private final Scene scene;
        private final Window window;
        private final Runnable continuation;
        private boolean cancelled;
        private boolean dispatched;
        private final ChangeListener<Boolean> visibility =
                (observable, previous, showing) -> {
                    if (!showing) run();
                };
        private final ChangeListener<Window> attachment = (observable, previous, current) -> run();

        private PulseContinuation(Scene scene, Runnable continuation) {
            this.scene = scene;
            this.window = scene == null ? null : scene.getWindow();
            this.continuation = continuation;
            if (window == null || !window.isShowing()) {
                Platform.runLater(this);
            } else {
                window.showingProperty().addListener(visibility);
                scene.windowProperty().addListener(attachment);
                scene.addPostLayoutPulseListener(this);
                Platform.requestNextPulse();
            }
        }

        @Override
        public void run() {
            if (dispatched || cancelled) return;
            dispatched = true;
            detach();
            // The pulse callback never performs capture or starts expensive validation itself.
            Platform.runLater(
                    () -> {
                        if (!cancelled) continuation.run();
                    });
        }

        @Override
        public void close() {
            cancelled = true;
            detach();
        }

        private void detach() {
            if (scene != null) {
                scene.removePostLayoutPulseListener(this);
                scene.windowProperty().removeListener(attachment);
            }
            if (window != null) window.showingProperty().removeListener(visibility);
        }
    }
}
