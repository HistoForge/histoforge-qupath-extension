package qupath.ext.histoforge.ui.jobs;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.TrainingRunGatewayClient;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.ui.training.TrainingReportViewer;
import qupath.ext.histoforge.util.Errors;
import qupath.fx.dialogs.Dialogs;

/** Wires the shared Jobs view to explicit backend actions. No run state is persisted locally. */
public class JobsTabController implements JobsTableColumns.Actions {

    private static final Logger logger = LoggerFactory.getLogger(JobsTabController.class);

    @FXML private TableView<JobRow> jobsTable;
    @FXML private Button refreshButton;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private Label loadStatusLabel;

    private Supplier<ModuleGatewayClient> client;
    private Supplier<GatewayTransport> transport;
    private JobsPoller poller;
    private ModulesRegistry modules;
    private Consumer<String> openModule;
    private InferenceJobActions inference;
    private final BiPredicate<String, String> confirmation;
    private final Set<String> pendingActions = new HashSet<>();

    public JobsTabController() {
        this(Dialogs::showYesNoDialog);
    }

    JobsTabController(BiPredicate<String, String> confirmation) {
        this.confirmation = Objects.requireNonNull(confirmation);
    }

    public void init(
            Supplier<ModuleGatewayClient> client,
            Supplier<GatewayTransport> transport,
            JobsPoller poller,
            ModulesRegistry modules,
            Consumer<String> openModule,
            InferenceJobActions inference) {
        this.client = client;
        this.transport = transport;
        this.poller = poller;
        this.modules = modules;
        this.openModule = openModule;
        this.inference = inference;
        poller.observeInference(inference.inferenceRows());
        JobsTableColumns.install(jobsTable, this);
        jobsTable.setItems(poller.jobs());
        Label empty = new Label();
        empty.getStyleClass().add(HistoforgeStyles.MUTED);
        jobsTable.setPlaceholder(empty);
        Runnable renderStatus =
                () -> {
                    boolean loading = poller.loadingProperty().get();
                    boolean failed = poller.failedProperty().get();
                    boolean loaded = poller.hasBaseline();
                    loadingIndicator.setVisible(loading && !loaded && !failed);
                    loadingIndicator.setManaged(loadingIndicator.isVisible());
                    loadStatusLabel.setText(
                            !inference.inferenceProblem().get().isEmpty()
                                    ? "Couldn’t read project analyses: "
                                            + inference.inferenceProblem().get()
                                    : failed
                                            ? "Couldn’t refresh cloud jobs. Use Refresh to try again."
                                            : loading && !loaded ? "Loading jobs…" : "");
                    empty.setText(
                            loaded
                                    ? "No jobs."
                                    : loading || failed
                                            ? ""
                                            : "No project analyses. Connect to load cloud jobs.");
                    // Background polls must not flash the button. Requests during a poll are
                    // coalesced by JobsPoller, so Refresh can stay available after the first load.
                    refreshButton.setDisable(loading && !loaded);
                };
        poller.loadingProperty().addListener(obs -> renderStatus.run());
        poller.loadedProperty().addListener(obs -> renderStatus.run());
        poller.failedProperty().addListener(obs -> renderStatus.run());
        inference.inferenceProblem().addListener(obs -> renderStatus.run());
        refreshButton.setOnAction(
                event -> {
                    poller.refresh();
                    inference.refreshInference();
                });
        renderStatus.run();
        modules.asObservable()
                .addListener((ListChangeListener<InstalledModule>) change -> jobsTable.refresh());
    }

    /** Clearing training history is terminal-only and never deletes the result module or data. */
    @Override
    public void clear(JobRow job) {
        if (job instanceof JobRow.Inference analysis) {
            if (analysis.canClearHistory()) inference.clearHistory(analysis);
            return;
        }
        if (job instanceof JobRow.Training && !job.isTerminal()) {
            return;
        }
        if (job instanceof JobRow.Training training
                && !confirmation.test(
                        "Clear training history", trainingClearConfirmation(training))) {
            return;
        }
        if (job instanceof JobRow.Admin
                && !job.isTerminal()
                && !confirmation.test(
                        "Remove job",
                        job.name()
                                + " is still active. Removing it will not cancel it; it finishes on the"
                                + " server. Remove?")) {
            return;
        }
        runAction(
                job,
                "Clear history failed",
                () -> {
                    if (job instanceof JobRow.Training) {
                        trainingClient().clear(job.id());
                    } else {
                        client.get().deleteJob(job.id());
                    }
                    Platform.runLater(() -> poller.forget(job));
                });
    }

    static String trainingClearConfirmation(JobRow.Training job) {
        return "Clear the training history for "
                + job.name()
                + "?\n\nRun: "
                + job.id()
                + "\n\nThis removes the Jobs entry and its report copy. It does not delete "
                + "uploaded training data or completed result modules, including their reports.";
    }

    /** Cancellation is a backend state transition, never a local row deletion. */
    @Override
    public void cancel(JobRow.Training job) {
        if (!job.canCancel()) {
            return;
        }
        runAction(job, "Cancellation failed", () -> trainingClient().cancel(job.id()));
    }

    /** Fetch the full accepted run before binding, rendering, and opening its report snapshot. */
    @Override
    public void details(JobRow.Training job) {
        runAction(
                job,
                "Training report unavailable",
                () -> {
                    TrainingRunGatewayClient training = trainingClient();
                    TrainingReportViewer.open(training.report(training.get(job.id())));
                });
    }

    @Override
    public void details(JobRow.Inference job) {
        TextArea text = new TextArea(job.details());
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefSize(680, 420);
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Analysis details — " + job.name());
        dialog.getDialogPane().setContent(text);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (jobsTable.getScene() != null) dialog.initOwner(jobsTable.getScene().getWindow());
        dialog.show();
    }

    @Override
    public void reconnect(JobRow.Inference job) {
        inference.reconnect(job);
    }

    @Override
    public void stop(JobRow.Inference job) {
        inference.stop(job);
    }

    @Override
    public Optional<InstalledModule> resultModule(JobRow.Training job) {
        return modules.asObservable().stream().filter(job::ownsResult).findFirst();
    }

    @Override
    public void openModule(InstalledModule module) {
        openModule.accept(module.ref());
    }

    @Override
    public boolean isBusy(JobRow job) {
        return pendingActions.contains(job.key());
    }

    private TrainingRunGatewayClient trainingClient() throws Exception {
        return new TrainingRunGatewayClient(transport.get(), client.get().capabilities());
    }

    /** Serialize clicks for one row without blocking other jobs or the FX thread. */
    private void runAction(JobRow job, String failureTitle, Action action) {
        if (!pendingActions.add(job.key())) {
            return;
        }
        jobsTable.refresh();
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                action.run();
                            } catch (Exception error) {
                                logger.warn("{} for {} failed", failureTitle, job.key(), error);
                                Platform.runLater(
                                        () ->
                                                Dialogs.showErrorMessage(
                                                        failureTitle,
                                                        job.name() + ": " + Errors.message(error)));
                            } finally {
                                Platform.runLater(
                                        () -> {
                                            pendingActions.remove(job.key());
                                            jobsTable.refresh();
                                        });
                            }
                        });
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
