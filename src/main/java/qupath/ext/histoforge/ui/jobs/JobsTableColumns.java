package qupath.ext.histoforge.ui.jobs;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.contract.AdminJobCodec.JobType;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.util.BrowserLauncher;
import qupath.fx.dialogs.Dialogs;

/** Builds the unified Jobs table. Rendering never loads reports or mutates backend state. */
final class JobsTableColumns {

    private static final DateTimeFormatter UPDATED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Controller-owned actions and the current installed-module projection. */
    interface Actions {
        void clear(JobRow job);

        void cancel(JobRow.Training job);

        void details(JobRow.Training job);

        void details(JobRow.Inference job);

        void reconnect(JobRow.Inference job);

        void stop(JobRow.Inference job);

        Optional<InstalledModule> resultModule(JobRow.Training job);

        void openModule(InstalledModule module);

        boolean isBusy(JobRow job);
    }

    private JobsTableColumns() {}

    static void install(TableView<JobRow> table, Actions actions) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        TableColumn<JobRow, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().name()));
        nameCol.setMinWidth(150);

        TableColumn<JobRow, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().type()));
        typeCol.setMinWidth(55);
        typeCol.setMaxWidth(85);

        TableColumn<JobRow, JobRow> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        statusCol.setCellFactory(col -> statusCell());
        statusCol.setMinWidth(170);

        TableColumn<JobRow, String> timeCol = new TableColumn<>("Updated");
        timeCol.setCellValueFactory(cd -> new SimpleStringProperty(updated(cd.getValue())));
        timeCol.setMinWidth(145);
        timeCol.setMaxWidth(180);

        TableColumn<JobRow, JobRow> actionCol = new TableColumn<>();
        actionCol.setCellValueFactory(cd -> new SimpleObjectProperty<>(cd.getValue()));
        actionCol.setCellFactory(col -> actionCell(actions));
        actionCol.setMinWidth(180);
        actionCol.setMaxWidth(230);

        table.getColumns().setAll(List.of(nameCol, typeCol, statusCol, timeCol, actionCol));
    }

    private static TableCell<JobRow, JobRow> statusCell() {
        return new TableCell<>() {
            private final ProgressIndicator spinner = new ProgressIndicator();
            private final Label phase = new Label();
            private final Label metric = muted("");
            private final HBox status = new HBox(6, spinner, phase);
            private final VBox box = new VBox(4, status, metric);
            private final Tooltip detail = new Tooltip();

            {
                spinner.setPrefSize(13, 13);
                spinner.setMaxSize(13, 13);
                status.setAlignment(Pos.CENTER_LEFT);
                // Keep labels single-line: wrapping before VirtualFlow knows the column width
                // can cache an oversized row. The tooltip preserves the full status and metric.
                setOnMouseClicked(
                        event -> {
                            JobRow row = getItem();
                            if (row != null) {
                                row.problem()
                                        .ifPresent(
                                                error ->
                                                        Dialogs.showErrorMessage(
                                                                "Job details", error));
                            }
                        });
            }

            @Override
            protected void updateItem(JobRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                if (empty || row == null) {
                    setGraphic(null);
                    setTooltip(null);
                    HistoforgeStyles.setClickable(this, false);
                    return;
                }
                phase.setText(row.status());
                HistoforgeStyles.setSemantic(
                        phase,
                        row.problem().isPresent()
                                ? HistoforgeStyles.ERROR
                                : row.isRunning()
                                        ? HistoforgeStyles.BUSY
                                        : row.needsAttention()
                                                        || row instanceof JobRow.Inference inference
                                                                && !inference.saved()
                                                ? HistoforgeStyles.NEUTRAL
                                                : row instanceof JobRow.Training training
                                                                && !training.succeeded()
                                                        ? HistoforgeStyles.NEUTRAL
                                                        : HistoforgeStyles.SUCCESS);
                spinner.setVisible(row.isRunning());
                spinner.setManaged(row.isRunning());
                metric.setText(
                        row instanceof JobRow.Training training ? training.primaryMetric() : "");
                metric.setVisible(!metric.getText().isEmpty());
                metric.setManaged(metric.isVisible());
                HistoforgeStyles.setClickable(this, row.problem().isPresent());
                detail.setText(detailText(row));
                setTooltip(detail);
                setGraphic(box);
            }
        };
    }

    private static TableCell<JobRow, JobRow> actionCell(Actions actions) {
        return new TableCell<>() {
            private final Button view = new Button("View on Hugging Face");
            private final Button clear = new Button("Clear history");
            private final Button details = new Button("Open latest details");
            private final Button cancel = new Button("Cancel");
            private final Button open = new Button("Open in Modules");
            private final Button inferenceDetails = new Button("Details");
            private final Button reconnect = new Button();
            private final Button stop = new Button();
            private final Label missing = muted("Result module not listed");
            private final VBox box = new VBox(4);

            @Override
            protected void updateItem(JobRow row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                box.getChildren().clear();
                box.setDisable(actions.isBusy(row));
                clear.setOnAction(event -> actions.clear(row));
                if (row instanceof JobRow.Inference inference) {
                    inferenceDetails.setOnAction(event -> actions.details(inference));
                    box.getChildren().add(inferenceDetails);
                    if (!inference.reconnectLabel().isEmpty()) {
                        reconnect.setText(inference.reconnectLabel());
                        reconnect.setDisable(!inference.reconnectUnavailable().isEmpty());
                        reconnect.setTooltip(
                                inference.reconnectUnavailable().isEmpty()
                                        ? null
                                        : new Tooltip(inference.reconnectUnavailable()));
                        reconnect.setOnAction(event -> actions.reconnect(inference));
                        box.getChildren().add(reconnect);
                    }
                    if (!inference.stopLabel().isEmpty()) {
                        stop.setText(inference.stopLabel());
                        stop.setOnAction(event -> actions.stop(inference));
                        box.getChildren().add(stop);
                    }
                    if (inference.canClearHistory()) box.getChildren().add(clear);
                } else if (row instanceof JobRow.Training training) {
                    details.setOnAction(event -> actions.details(training));
                    box.getChildren().add(details);
                    if (training.canCancel()) {
                        cancel.setOnAction(event -> actions.cancel(training));
                        box.getChildren().add(cancel);
                    }
                    if (training.succeeded()) {
                        Optional<InstalledModule> module = actions.resultModule(training);
                        if (module.isPresent()) {
                            open.setOnAction(event -> actions.openModule(module.orElseThrow()));
                            box.getChildren().add(open);
                        } else {
                            box.getChildren().add(missing);
                        }
                    }
                    if (training.isTerminal()) {
                        box.getChildren().add(clear);
                    }
                } else {
                    String url = hfUrl((JobRow.Admin) row);
                    if (url != null) {
                        view.setOnAction(event -> BrowserLauncher.open(url));
                        box.getChildren().add(view);
                    }
                    box.getChildren().add(clear);
                }
                setGraphic(box);
            }
        };
    }

    /** Administrative publication navigation is independent of its outcome. */
    static String hfUrl(JobRow.Admin row) {
        return row.job().type() == JobType.PUBLISH
                ? "https://huggingface.co/" + row.job().requestedModuleRef()
                : null;
    }

    static String detailText(JobRow row) {
        if (row instanceof JobRow.Inference inference) return inference.details();
        String text =
                row instanceof JobRow.Training training
                        ? "Source: "
                                + training.run().sourceModuleRef()
                                + "\nModel: "
                                + training.run().selectedModelId()
                                + "\nResult: "
                                + ("selected_model_only".equals(training.run().resultForm())
                                        ? "Standalone model"
                                        : "Copy of the source module")
                        : row.name();
        text += "\nStatus: " + row.status();
        if (row instanceof JobRow.Training training && !training.primaryMetric().isEmpty()) {
            text += "\n" + training.primaryMetric();
        }
        return text + row.failure().map(error -> "\n" + error.message()).orElse("");
    }

    static String updated(JobRow row) {
        return row.updatedAt().atZone(ZoneId.systemDefault()).format(UPDATED_FMT);
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add(HistoforgeStyles.MUTED);
        return label;
    }
}
