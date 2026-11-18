package qupath.ext.histoforge.ui.project;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.pipeline.BatchOrchestrator;
import qupath.ext.histoforge.pipeline.ImageActionExecutor;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/** Owns one bounded process-local Project-tab batch and its suspension UI. */
final class ProjectBatchSession {

    private static final Logger logger = LoggerFactory.getLogger(ProjectBatchSession.class);

    private final QuPathGUI qupath;
    private final TableView<ImageRowModel> imageTable;
    private final Button actionButton;
    private final VBox progressBox;
    private final ProgressBar progressBar;
    private final Label progressLabel;
    private final Consumer<Boolean> setBusy;
    private final Runnable onIdle;
    private final Runnable defaultAction;
    private final Consumer<Runnable> reload;
    private final ProjectActionResultSink resultSink;
    private ProjectInferenceJobs jobs;
    private Runnable openJobs;
    private BiConsumer<Object, String> stopAnalysis;

    private Operation active;

    ProjectBatchSession(
            QuPathGUI qupath,
            TableView<ImageRowModel> imageTable,
            Button actionButton,
            VBox progressBox,
            ProgressBar progressBar,
            Label progressLabel,
            Consumer<Boolean> setBusy,
            Runnable onIdle,
            Runnable defaultAction,
            Consumer<Runnable> reload,
            ProjectActionResultSink resultSink) {
        this.qupath = Objects.requireNonNull(qupath, "qupath");
        this.imageTable = Objects.requireNonNull(imageTable, "imageTable");
        this.actionButton = Objects.requireNonNull(actionButton, "actionButton");
        this.progressBox = Objects.requireNonNull(progressBox, "progressBox");
        this.progressBar = Objects.requireNonNull(progressBar, "progressBar");
        this.progressLabel = Objects.requireNonNull(progressLabel, "progressLabel");
        this.setBusy = Objects.requireNonNull(setBusy, "setBusy");
        this.onIdle = Objects.requireNonNull(onIdle, "onIdle");
        this.defaultAction = Objects.requireNonNull(defaultAction, "defaultAction");
        this.reload = Objects.requireNonNull(reload, "reload");
        this.resultSink = Objects.requireNonNull(resultSink, "resultSink");
    }

    void connectJobs(
            ProjectInferenceJobs jobs, Runnable openJobs, BiConsumer<Object, String> stopAnalysis) {
        this.jobs = Objects.requireNonNull(jobs);
        this.openJobs = Objects.requireNonNull(openJobs);
        this.stopAnalysis = Objects.requireNonNull(stopAnalysis);
    }

    boolean owns(String actionId, String imageId) {
        return active != null
                && active.actionId.equals(actionId)
                && active.modules.containsKey(imageId);
    }

    void resume(String imageId) {
        if (active == null || !active.batch.resume(imageId))
            throw new IllegalStateException(
                    "This image is no longer waiting to continue. Refresh Jobs.");
    }

    boolean active() {
        return active != null;
    }

    /** Accept runtime ownership only after {@link BatchOrchestrator#runBatch} starts. */
    void start(
            BatchOrchestrator batch,
            ProjectActionRuntime runtime,
            List<ImageActionExecutor.Request> requests,
            Object projectIdentity,
            Map<String, ModuleContentIdentity> modules,
            String actionName,
            boolean wholeSlide) {
        if (active()) {
            throw new IllegalStateException("another Project-tab batch is active");
        }
        Operation operation =
                new Operation(
                        batch,
                        runtime,
                        projectIdentity,
                        modules,
                        actionName,
                        wholeSlide,
                        requests.getFirst().actionId());
        active = operation;
        try {
            beginControls();
            batch.runBatch(
                            requests,
                            progress -> Platform.runLater(() -> onProgress(operation, progress)))
                    .whenComplete(
                            (result, failure) -> {
                                RuntimeException cleanupFailure = null;
                                try {
                                    operation.runtime.close();
                                } catch (RuntimeException error) {
                                    cleanupFailure = error;
                                    logger.warn("Completed batch resource cleanup failed", error);
                                }
                                var cleanup = cleanupFailure;
                                Platform.runLater(
                                        () -> finish(operation, result, failure, cleanup));
                            });
        } catch (RuntimeException failure) {
            active = null;
            resetControls();
            throw failure;
        }
    }

    void cancel() {
        if (active != null) stopAnalysis.accept(active.projectIdentity, active.actionId);
    }

    /** Stop without a second prompt, retaining ownership until execution and saving finish. */
    CompletableFuture<Void> cancelForCloudUpdate() {
        if (active == null) return CompletableFuture.completedFuture(null);
        if (active.updateStop == null || active.updateStop.isDone()) {
            active.updateStop = new CompletableFuture<>();
        }
        requestCancellation();
        return active.updateStop;
    }

    private void requestCancellation() {
        active.batch.cancel();
        actionButton.setText("Cancelling...");
        actionButton.setDisable(true);
    }

    private void beginControls() {
        setBusy.accept(true);
        progressBox.setVisible(true);
        progressBox.setManaged(true);
        progressBar.setProgress(0);
        actionButton.setText("Cancel batch");
        actionButton.setDisable(false);
        actionButton.setOnAction(event -> cancel());
    }

    private void onProgress(Operation operation, BatchOrchestrator.BatchProgress progress) {
        if (active != operation) {
            return;
        }
        boolean stoppingForUpdate = operation.updateStop != null && !operation.updateStop.isDone();
        if (jobs != null && !operation.actionName.equals("Metrics"))
            jobs.progress(
                    operation.projectIdentity,
                    operation.actionId,
                    operation.modules.get(progress.imageId()).moduleRef().toString(),
                    progress);
        if (progress.phase() == BatchOrchestrator.BatchPhase.SUSPENDED && !stoppingForUpdate) {
            actionButton.setText("Open Jobs");
            actionButton.setDisable(false);
            actionButton.setOnAction(event -> openJobs.run());
        }
        if (stoppingForUpdate && progress.phase() == BatchOrchestrator.BatchPhase.SUSPENDED) {
            progress.suspensionFailure()
                    .ifPresent(
                            message ->
                                    operation.updateStop.completeExceptionally(
                                            new IllegalStateException(
                                                    "Could not cancel "
                                                            + progress.imageName()
                                                            + ": "
                                                            + message)));
        }
        progressLabel.setText(progressLabel(progress, operation.wholeSlide));
        progressBar.setProgress((double) progress.terminalImages() / progress.totalImages());
        if (qupath.getProject() != operation.projectIdentity) {
            return;
        }
        imageTable.getItems().stream()
                .filter(row -> row.entry.getID().equals(progress.imageId()))
                .findFirst()
                .ifPresent(
                        row -> {
                            if (progress.phase() == BatchOrchestrator.BatchPhase.TERMINAL) {
                                RunResults.ImageRunResult result = progress.result().orElseThrow();
                                resultSink.accept(
                                        operation.projectIdentity,
                                        progress.imageId(),
                                        operation.modules.get(progress.imageId()),
                                        result);
                                row.lastBatchStatus.set(
                                        ProjectRowStatus.latest(Optional.of(result)));
                            } else {
                                row.lastBatchStatus.set(status(progress.phase()));
                            }
                        });
    }

    private void finish(
            Operation operation,
            RunResults.BatchRunResult result,
            Throwable failure,
            RuntimeException cleanupFailure) {
        if (active != operation) {
            return;
        }
        if (result != null) {
            for (RunResults.ImageRunResult image : result.images()) {
                resultSink.accept(
                        operation.projectIdentity,
                        image.imageId(),
                        operation.modules.get(image.imageId()),
                        image);
            }
        }
        Throwable cause = unwrap(failure);
        long capacityFailures =
                result == null
                        ? 0
                        : result.images().stream()
                                .filter(ProjectRowStatus::hasLocalCapacityFailure)
                                .count();
        if (cause != null) {
            if (jobs != null && !operation.actionName.equals("Metrics"))
                jobs.batchFailed(operation.projectIdentity, operation.actionId, cause);
            logger.error(
                    "{} batch failed outside typed image execution", operation.actionName, cause);
        }
        active = null;
        resetControls();
        boolean stopping = operation.updateStop != null && !operation.updateStop.isDone();
        if (stopping) {
            Throwable stopFailure = cause == null ? cleanupFailure : cause;
            if (stopFailure == null) operation.updateStop.complete(null);
            else operation.updateStop.completeExceptionally(stopFailure);
        }
        try {
            reload.accept(() -> {});
        } catch (RuntimeException refreshFailure) {
            logger.warn("Completed batch could not start optional display refresh", refreshFailure);
        }
        if (stopping) return;
        if (cause != null) {
            Dialogs.showErrorMessage(operation.actionName + " failed", describe(cause));
        } else if (capacityFailures > 0) {
            Dialogs.showErrorMessage(
                    "Analysis exceeds selected result setting",
                    ProjectRowStatus.localCapacityMessage(capacityFailures));
        }
    }

    private void resetControls() {
        progressBox.setVisible(false);
        progressBox.setManaged(false);
        actionButton.setText("Fill");
        actionButton.setDisable(false);
        actionButton.setOnAction(event -> defaultAction.run());
        setBusy.accept(false);
        onIdle.run();
    }

    private static String status(BatchOrchestrator.BatchPhase phase) {
        return switch (phase) {
            case QUEUED -> "Queued";
            case SUSPENDED -> "Needs attention — open Jobs";
            case RUNNING -> "Running";
            case TERMINAL -> throw new IllegalArgumentException("terminal status requires result");
        };
    }

    static String progressLabel(BatchOrchestrator.BatchProgress progress, boolean wholeSlide) {
        String phase =
                progress.phase() == BatchOrchestrator.BatchPhase.TERMINAL
                        ? progress.result().orElseThrow().outcome().toString()
                        : progress.phase().toString();
        String label =
                progress.imageName()
                        + " ("
                        + (progress.imageIndex() + 1)
                        + "/"
                        + progress.totalImages()
                        + ") — "
                        + phase
                        + " · "
                        + progress.terminalImages()
                        + "/"
                        + progress.totalImages()
                        + " finished";
        return wholeSlide ? label + " · hierarchy edits will conflict" : label;
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure == null) {
            return null;
        }
        return failure.getCause() == null ? failure : failure.getCause();
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static final class Operation {

        private final BatchOrchestrator batch;
        private final ProjectActionRuntime runtime;
        private final Object projectIdentity;
        private final Map<String, ModuleContentIdentity> modules;
        private final String actionName;
        private final boolean wholeSlide;
        private final String actionId;
        private CompletableFuture<Void> updateStop;

        private Operation(
                BatchOrchestrator batch,
                ProjectActionRuntime runtime,
                Object projectIdentity,
                Map<String, ModuleContentIdentity> modules,
                String actionName,
                boolean wholeSlide,
                String actionId) {
            this.batch = Objects.requireNonNull(batch, "batch");
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.projectIdentity = Objects.requireNonNull(projectIdentity, "projectIdentity");
            this.modules = Map.copyOf(modules);
            this.actionName = Objects.requireNonNull(actionName, "actionName");
            this.wholeSlide = wholeSlide;
            this.actionId = actionId;
        }
    }
}
