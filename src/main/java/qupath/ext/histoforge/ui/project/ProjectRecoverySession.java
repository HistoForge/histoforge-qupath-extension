package qupath.ext.histoforge.ui.project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
import qupath.ext.histoforge.large.LargeActionHandleStore.UnrecoverableRecoveryStateException;
import qupath.ext.histoforge.pipeline.BatchOrchestrator.BatchPhase;
import qupath.ext.histoforge.pipeline.PersistedActionRecovery;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.ProjectImageEntry;

/** Owns one explicitly started post-restart recovery attempt and its retained reservation. */
final class ProjectRecoverySession {

    private static final Logger logger = LoggerFactory.getLogger(ProjectRecoverySession.class);

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
    private BiConsumer<Object, PersistedActionRecovery.Item> stopAnalysis;

    private Operation active;

    ProjectRecoverySession(
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

    boolean active() {
        return active != null;
    }

    void connectJobs(
            ProjectInferenceJobs jobs,
            Runnable openJobs,
            BiConsumer<Object, PersistedActionRecovery.Item> stopAnalysis) {
        this.jobs = Objects.requireNonNull(jobs);
        this.openJobs = Objects.requireNonNull(openJobs);
        this.stopAnalysis = Objects.requireNonNull(stopAnalysis);
    }

    boolean owns(String actionId, String imageId) {
        return active != null
                && active.item.actionId().equals(actionId)
                && active.item.imageId().equals(imageId);
    }

    void resume() {
        if (active == null || active.inFlight || active.paused == null || active.cancelled.get())
            throw new IllegalStateException(
                    "This analysis is not available to continue. Refresh Jobs.");
        run(active, active.paused);
    }

    private void publish(
            Operation operation,
            BatchPhase phase,
            Optional<RunResults.ImageRunResult> result,
            String problem) {
        if (jobs != null)
            jobs.recovery(
                    operation.projectIdentity,
                    operation.item,
                    operation.entry.getImageName(),
                    phase,
                    result,
                    Optional.ofNullable(problem));
    }

    /** Start explicit recovery; the caller retains its resource ownership if this throws. */
    void start(
            PersistedActionRecovery recovery,
            PersistedActionRecovery.Item item,
            ProjectImageEntry<BufferedImage> entry,
            Object projectIdentity) {
        if (active()) {
            throw new IllegalStateException("another persisted recovery is active");
        }
        Operation operation = new Operation(recovery, item, entry, projectIdentity);
        active = operation;
        try {
            beginControls();
            run(operation, null);
        } catch (RuntimeException failure) {
            active = null;
            resetControls();
            throw failure;
        }
    }

    void cancel() {
        if (active != null) stopAnalysis.accept(active.projectIdentity, active.item);
    }

    /** Cancel in-flight recovery or abandon its paused target under the update confirmation. */
    CompletableFuture<Void> cancelForCloudUpdate() {
        Operation operation = active;
        if (operation == null) return CompletableFuture.completedFuture(null);
        if (operation.updateStop == null || operation.updateStop.isDone()) {
            operation.updateStop = new CompletableFuture<>();
        }
        operation.cancelled.set(true);
        actionButton.setText("Cancelling recovery...");
        actionButton.setDisable(true);
        if (!operation.inFlight) abandon(operation);
        return operation.updateStop;
    }

    private void beginControls() {
        setBusy.accept(true);
        progressBox.setVisible(true);
        progressBox.setManaged(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        actionButton.setText("Cancel recovery");
        actionButton.setDisable(false);
        actionButton.setOnAction(event -> cancel());
    }

    private void run(Operation operation, PersistedActionRecovery.Paused paused) {
        if (active != operation || operation.inFlight) {
            return;
        }
        operation.inFlight = true;
        operation.paused = null;
        publish(operation, BatchPhase.RUNNING, Optional.empty(), null);
        progressLabel.setText(
                recoveryProgressLabel(operation.entry.getImageName(), operation.item));
        updateRow(operation.projectIdentity, operation.item.imageId(), "Recovering");
        actionButton.setText("Cancel recovery");
        actionButton.setDisable(false);
        actionButton.setOnAction(event -> cancel());
        Thread.startVirtualThread(
                () -> {
                    try {
                        PersistedActionRecovery.Attempt attempt =
                                paused == null
                                        ? operation.recovery.resume(
                                                operation.item,
                                                operation.entry,
                                                operation.cancelled::get)
                                        : operation.recovery.resume(
                                                paused, operation.entry, operation.cancelled::get);
                        Platform.runLater(() -> onAttempt(operation, attempt, null));
                    } catch (Exception failure) {
                        Platform.runLater(() -> onAttempt(operation, null, failure));
                    }
                });
    }

    private void onAttempt(
            Operation operation, PersistedActionRecovery.Attempt attempt, Throwable failure) {
        if (active != operation) {
            return;
        }
        operation.inFlight = false;
        if (failure != null) {
            publish(operation, BatchPhase.TERMINAL, Optional.empty(), describe(failure));
            logger.error("Explicit persisted recovery failed", failure);
            finish(operation);
            if (completeUpdateStop(operation, failure)) return;
            Dialogs.showErrorMessage("HistoForge recovery failed", describe(failure));
            return;
        }
        if (attempt instanceof PersistedActionRecovery.Completed completed) {
            RunResults.ImageRunResult result = completed.result();
            publish(operation, BatchPhase.TERMINAL, Optional.of(result), null);
            resultSink.accept(
                    operation.projectIdentity,
                    operation.item.imageId(),
                    operation.item.module(),
                    result);
            updateRow(
                    operation.projectIdentity,
                    operation.item.imageId(),
                    ProjectRowStatus.latest(Optional.of(result)));
            finish(operation);
            boolean stopping = completeUpdateStop(operation, null);
            try {
                reload.accept(() -> {});
            } catch (RuntimeException refreshFailure) {
                logger.warn(
                        "Completed recovery could not start optional display refresh",
                        refreshFailure);
            }
            if (stopping) return;
            if (ProjectRowStatus.hasLocalCapacityFailure(result)) {
                Dialogs.showErrorMessage(
                        "Analysis exceeds selected result setting",
                        ProjectRowStatus.localCapacityMessage(1));
            } else {
                Dialogs.showInfoNotification(
                        "HistoForge recovery",
                        "The retained target is now " + result.outcome() + ".");
            }
            return;
        }
        operation.paused = (PersistedActionRecovery.Paused) attempt;
        publish(
                operation,
                BatchPhase.SUSPENDED,
                Optional.empty(),
                operation.paused.failureMessage());
        if (operation.updateStop != null && !operation.updateStop.isDone()) {
            abandon(operation);
            return;
        }
        progressLabel.setText(
                recoveryProgressLabel(operation.entry.getImageName(), operation.item)
                        + " — paused");
        updateRow(
                operation.projectIdentity, operation.item.imageId(), "Needs attention — open Jobs");
        actionButton.setText("Open Jobs");
        actionButton.setDisable(false);
        actionButton.setOnAction(event -> openJobs.run());
    }

    private void abandon(Operation operation) {
        if (active != operation || operation.inFlight || operation.paused == null) {
            return;
        }
        PersistedActionRecovery.Paused paused = operation.paused;
        operation.inFlight = true;
        progressLabel.setText(operation.entry.getImageName() + " — abandoning retained target");
        actionButton.setText("Abandoning...");
        actionButton.setDisable(true);
        Thread.startVirtualThread(
                () -> {
                    try {
                        operation.recovery.abandon(paused);
                        Platform.runLater(() -> onAbandoned(operation));
                    } catch (UnrecoverableRecoveryStateException failure) {
                        Platform.runLater(() -> onLostDuringAbandon(operation, failure));
                    } catch (IOException | RuntimeException failure) {
                        Platform.runLater(() -> onAbandonFailure(operation, failure));
                    }
                });
    }

    private void onAbandoned(Operation operation) {
        if (active != operation) {
            return;
        }
        publish(operation, BatchPhase.TERMINAL, Optional.empty(), null);
        updateRow(
                operation.projectIdentity,
                operation.item.imageId(),
                "Previous recoverable work abandoned");
        finish(operation);
        if (completeUpdateStop(operation, null)) return;
        Dialogs.showInfoNotification(
                "HistoForge",
                "Local processing stopped. Jobs shows whether cloud work has stopped too.");
    }

    private void onLostDuringAbandon(
            Operation operation, UnrecoverableRecoveryStateException failure) {
        if (active != operation) {
            return;
        }
        logger.error("Persisted recovery state was lost during abandonment", failure);
        publish(operation, BatchPhase.TERMINAL, Optional.empty(), describe(failure));
        finish(operation);
        if (completeUpdateStop(operation, failure)) return;
        Dialogs.showErrorMessage("HistoForge recovery state unavailable", describe(failure));
    }

    private void onAbandonFailure(Operation operation, Exception failure) {
        if (active != operation) {
            return;
        }
        operation.inFlight = false;
        publish(operation, BatchPhase.SUSPENDED, Optional.empty(), describe(failure));
        actionButton.setText("Open Jobs");
        actionButton.setDisable(false);
        actionButton.setOnAction(event -> openJobs.run());
        if (completeUpdateStop(operation, failure)) return;
    }

    private static boolean completeUpdateStop(Operation operation, Throwable failure) {
        if (operation.updateStop == null || operation.updateStop.isDone()) return false;
        Throwable stopFailure = failure == null ? operation.cleanupFailure : failure;
        if (stopFailure == null) operation.updateStop.complete(null);
        else operation.updateStop.completeExceptionally(stopFailure);
        return true;
    }

    private void finish(Operation operation) {
        if (active != operation) {
            return;
        }
        active = null;
        try {
            operation.recovery.close();
        } catch (RuntimeException failure) {
            operation.cleanupFailure = failure;
            logger.warn("Completed recovery resource cleanup failed", failure);
        } finally {
            resetControls();
        }
    }

    private void updateRow(Object projectIdentity, String imageId, String status) {
        if (qupath.getProject() != projectIdentity) {
            return;
        }
        imageTable.getItems().stream()
                .filter(row -> row.entry.getID().equals(imageId))
                .findFirst()
                .ifPresent(row -> row.lastBatchStatus.set(status));
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

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    static String recoveryProgressLabel(String imageName, PersistedActionRecovery.Item item) {
        String label =
                Objects.requireNonNull(imageName, "imageName")
                        + " — recovering retained target "
                        + Objects.requireNonNull(item, "item").phase();
        return item.wholeSlide() ? label + " · hierarchy edits will conflict" : label;
    }

    private static final class Operation {

        private final PersistedActionRecovery recovery;
        private final PersistedActionRecovery.Item item;
        private final ProjectImageEntry<BufferedImage> entry;
        private final Object projectIdentity;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private PersistedActionRecovery.Paused paused;
        private boolean inFlight;
        private CompletableFuture<Void> updateStop;
        private RuntimeException cleanupFailure;

        private Operation(
                PersistedActionRecovery recovery,
                PersistedActionRecovery.Item item,
                ProjectImageEntry<BufferedImage> entry,
                Object projectIdentity) {
            this.recovery = Objects.requireNonNull(recovery, "recovery");
            this.item = Objects.requireNonNull(item, "item");
            this.entry = Objects.requireNonNull(entry, "entry");
            this.projectIdentity = Objects.requireNonNull(projectIdentity, "projectIdentity");
            if (!item.imageId().equals(entry.getID())) {
                throw new IllegalArgumentException(
                        "persisted recovery belongs to another project image entry");
            }
        }
    }
}
