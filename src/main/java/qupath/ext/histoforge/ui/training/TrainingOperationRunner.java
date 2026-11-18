package qupath.ext.histoforge.ui.training;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.application.Platform;
import qupath.ext.histoforge.training.TrainingDatasetWorkflow;

/** Owns one cancellable Train-tab local operation and fences stale FX callbacks. */
final class TrainingOperationRunner {

    @FunctionalInterface
    interface Operation<T> {
        T run(
                java.util.function.BooleanSupplier cancelled,
                Consumer<TrainingDatasetWorkflow.Progress> progress)
                throws Exception;
    }

    private final Consumer<Boolean> runningChanged;
    private final Consumer<Runnable> uiDispatcher;
    private boolean running;
    private long generation;
    private AtomicBoolean cancellation;
    private Thread worker;
    private CompletableFuture<Void> stopped = CompletableFuture.completedFuture(null);

    TrainingOperationRunner(Consumer<Boolean> runningChanged) {
        this(runningChanged, Platform::runLater);
    }

    TrainingOperationRunner(Consumer<Boolean> runningChanged, Consumer<Runnable> uiDispatcher) {
        this.runningChanged = java.util.Objects.requireNonNull(runningChanged, "runningChanged");
        this.uiDispatcher = java.util.Objects.requireNonNull(uiDispatcher, "uiDispatcher");
    }

    boolean isRunning() {
        return running;
    }

    <T> void start(
            Operation<T> operation,
            Consumer<TrainingDatasetWorkflow.Progress> progress,
            Consumer<T> success,
            Consumer<Throwable> failure) {
        if (running) {
            return;
        }
        long token = ++generation;
        AtomicBoolean cancelled = new AtomicBoolean();
        cancellation = cancelled;
        stopped = new CompletableFuture<>();
        setRunning(true);
        Thread task =
                Thread.ofVirtual()
                        .unstarted(
                                () -> {
                                    try {
                                        T result =
                                                operation.run(
                                                        cancelled::get,
                                                        update ->
                                                                uiDispatcher.accept(
                                                                        () -> {
                                                                            if (token == generation
                                                                                    && !cancelled
                                                                                            .get()) {
                                                                                progress.accept(
                                                                                        update);
                                                                            }
                                                                        }));
                                        uiDispatcher.accept(
                                                () -> {
                                                    if (!owns(token, cancelled)) {
                                                        return;
                                                    }
                                                    boolean wasCancelled = cancelled.get();
                                                    clear();
                                                    if (!wasCancelled) {
                                                        success.accept(result);
                                                    }
                                                });
                                    } catch (Exception | Error error) {
                                        uiDispatcher.accept(
                                                () -> {
                                                    if (!owns(token, cancelled)) {
                                                        return;
                                                    }
                                                    boolean wasCancelled = cancelled.get();
                                                    clear();
                                                    if (!wasCancelled) {
                                                        failure.accept(error);
                                                    }
                                                });
                                    }
                                });
        worker = task;
        task.start();
    }

    void cancel() {
        if (cancellation != null) {
            cancellation.set(true);
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** Called on the UI thread; completes only after the worker has actually exited. */
    CompletableFuture<Void> cancelAndAwait() {
        cancel();
        return stopped;
    }

    private boolean owns(long token, AtomicBoolean cancelled) {
        return token == generation && cancellation == cancelled;
    }

    private void clear() {
        CompletableFuture<Void> completed = stopped;
        cancellation = null;
        worker = null;
        setRunning(false);
        completed.complete(null);
    }

    private void setRunning(boolean value) {
        if (running == value) {
            return;
        }
        running = value;
        runningChanged.accept(value);
    }
}
