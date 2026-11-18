package qupath.ext.histoforge.ui.training;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.TrainingDatasetGatewayClient;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetCleanupRecord;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetListRecord;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetSummary;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.HistoforgeTooltip;
import qupath.ext.histoforge.ui.InformationButton;
import qupath.ext.histoforge.util.BrowserLauncher;
import qupath.ext.histoforge.util.Errors;
import qupath.fx.dialogs.Dialogs;

/** Lists retained training pixels and performs the contract's explicit delete/retry action. */
final class TrainingDatasetManagerDialog {

    private static final Logger logger =
            LoggerFactory.getLogger(TrainingDatasetManagerDialog.class);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final String CLOUD_USAGE_URL = "https://modal.com/settings/usage";

    private final TrainingDatasetGatewayClient client;
    private final Dialog<Void> dialog = new Dialog<>();
    private final TableView<TrainingDatasetSummary> table = new TableView<>();
    private final Label status = new Label();
    private final Label cleanupDetails = new Label();
    private final Button delete = new Button("Delete");
    private final Button copyId = new Button("Copy ID");

    TrainingDatasetManagerDialog(Stage owner, TrainingDatasetGatewayClient client) {
        this.client = java.util.Objects.requireNonNull(client, "client");
        dialog.initOwner(owner);
        dialog.setTitle("Uploaded training data");
        dialog.setHeaderText("Manage uploaded training data");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        HistoforgeStyles.apply(dialog.getDialogPane().getScene());
        installTable();

        Button refresh = new Button("Refresh");
        refresh.setOnAction(event -> load());
        delete.setOnAction(event -> deleteSelected());
        copyId.setOnAction(event -> copySelectedId());
        table.getSelectionModel()
                .selectedItemProperty()
                .addListener((ignored, previous, current) -> updateButtons(current));

        Label storageNotice =
                new Label(
                        "Uploaded training data uses storage in your cloud account and remains until deleted. Storage may be billable.");
        storageNotice.setWrapText(true);
        Hyperlink storageUsage = new Hyperlink("View cloud storage usage");
        storageUsage.setOnAction(event -> BrowserLauncher.open(CLOUD_USAGE_URL));
        cleanupDetails.setWrapText(true);
        cleanupDetails.setVisible(false);
        cleanupDetails.setManaged(false);
        HBox actions = new HBox(8, refresh, copyId, delete);
        VBox content =
                new VBox(8, storageNotice, storageUsage, table, cleanupDetails, actions, status);
        content.setPadding(new Insets(4));
        VBox.setVgrow(table, Priority.ALWAYS);
        content.setPrefSize(850, 430);
        dialog.getDialogPane().setContent(content);
        updateButtons(null);
    }

    void show() {
        load();
        dialog.show();
    }

    private void installTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No uploaded training data."));
        TableColumn<TrainingDatasetSummary, String> idColumn =
                column("Dataset", item -> shortId(item.record().datasetId()));
        TableColumn<TrainingDatasetSummary, String> statusColumn =
                column("", TrainingDatasetManagerDialog::statusText);
        statusColumn.setGraphic(
                new HBox(
                        4,
                        new Label("Status"),
                        new InformationButton(
                                "Ready: available for training or deletion. In use: an active "
                                        + "training run prevents deletion. Cleanup failed: retry "
                                        + "deletion.")));
        List<TableColumn<TrainingDatasetSummary, ?>> columns =
                List.of(
                        idColumn,
                        column("Task", item -> item.record().taskDisplayName()),
                        column("Images", item -> Long.toString(item.record().imageCount())),
                        column("ROIs", item -> Long.toString(item.record().roiCount())),
                        column("Stored", item -> storageText(item.record())),
                        column("Created", item -> TIME_FORMAT.format(item.record().createdAt())),
                        statusColumn);
        table.getColumns().setAll(columns);
        idColumn.setCellFactory(
                ignored ->
                        new TableCell<>() {
                            @Override
                            protected void updateItem(String value, boolean empty) {
                                super.updateItem(value, empty);
                                setText(empty ? null : value);
                                if (empty || getTableRow().getItem() == null) {
                                    setTooltip(null);
                                } else {
                                    setTooltip(
                                            new HistoforgeTooltip(
                                                    getTableRow().getItem().record().datasetId()));
                                }
                            }
                        });
    }

    private static TableColumn<TrainingDatasetSummary, String> column(
            String name, java.util.function.Function<TrainingDatasetSummary, String> value) {
        TableColumn<TrainingDatasetSummary, String> column = new TableColumn<>(name);
        column.setCellValueFactory(item -> new ReadOnlyStringWrapper(value.apply(item.getValue())));
        column.setReorderable(false);
        return column;
    }

    private void load() {
        setBusy("Loading uploaded training data...");
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                List<TrainingDatasetSummary> rows = new ArrayList<>();
                                Optional<String> cursor = Optional.empty();
                                do {
                                    var page = client.page(client.limits().maxPageSize(), cursor);
                                    rows.addAll(page.items());
                                    cursor = page.nextCursor();
                                } while (cursor.isPresent());
                                Platform.runLater(
                                        () -> {
                                            table.setItems(FXCollections.observableArrayList(rows));
                                            status.setText(
                                                    rows.size()
                                                            + (rows.size() == 1
                                                                    ? " dataset"
                                                                    : " datasets"));
                                            HistoforgeStyles.setSemantic(
                                                    status, HistoforgeStyles.NEUTRAL);
                                            updateButtons(
                                                    table.getSelectionModel().getSelectedItem());
                                        });
                            } catch (Exception error) {
                                logger.warn("training dataset list failed", error);
                                Platform.runLater(
                                        () -> {
                                            status.setText(Errors.message(error));
                                            HistoforgeStyles.setSemantic(
                                                    status, HistoforgeStyles.ERROR);
                                            updateButtons(null);
                                        });
                            }
                        });
    }

    private void deleteSelected() {
        TrainingDatasetSummary selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        TrainingDatasetListRecord record = selected.record();
        String action = record instanceof TrainingDatasetCleanupRecord ? "Retry cleanup" : "Delete";
        String message =
                action
                        + " dataset "
                        + shortId(record.datasetId())
                        + "?\n\nCreated: "
                        + TIME_FORMAT.format(record.createdAt())
                        + (record instanceof TrainingDatasetCleanupRecord
                                ? "\nLast known stored data: "
                                : "\nStored data: ")
                        + formatBytes(remainingBytes(record))
                        + "\n\nAn identical future capture must be uploaded again. Completed modules remain available.";
        if (!Dialogs.showYesNoDialog(action + " training data", message)) {
            return;
        }
        setBusy(action + " in progress...");
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                client.delete(record.datasetId());
                                Platform.runLater(this::load);
                            } catch (Exception error) {
                                logger.warn(
                                        "training dataset delete {} failed",
                                        record.datasetId(),
                                        error);
                                Platform.runLater(
                                        () -> {
                                            Dialogs.showErrorMessage(
                                                    action + " failed", Errors.message(error));
                                            load();
                                        });
                            }
                        });
    }

    private void copySelectedId() {
        TrainingDatasetSummary selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(selected.record().datasetId());
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void setBusy(String text) {
        status.setText(text);
        HistoforgeStyles.setSemantic(status, HistoforgeStyles.BUSY);
        delete.setDisable(true);
        copyId.setDisable(true);
    }

    private void updateButtons(TrainingDatasetSummary selected) {
        boolean unavailable = selected == null || selected.inUse();
        delete.setDisable(unavailable);
        copyId.setDisable(selected == null);
        if (selected != null) {
            delete.setText(
                    selected.record() instanceof TrainingDatasetCleanupRecord
                            ? "Retry cleanup"
                            : "Delete");
        }
        if (selected != null && selected.record() instanceof TrainingDatasetCleanupRecord cleanup) {
            cleanupDetails.setText(cleanupMessage(cleanup));
            HistoforgeStyles.setSemantic(cleanupDetails, HistoforgeStyles.ERROR);
            cleanupDetails.setVisible(true);
            cleanupDetails.setManaged(true);
        } else {
            cleanupDetails.setText("");
            cleanupDetails.setVisible(false);
            cleanupDetails.setManaged(false);
        }
    }

    private static String statusText(TrainingDatasetSummary item) {
        if (item.record() instanceof TrainingDatasetCleanupRecord) {
            return "Cleanup failed";
        }
        return item.inUse() ? "In use" : "Ready";
    }

    static String cleanupMessage(TrainingDatasetCleanupRecord cleanup) {
        String prefix =
                "Last known stored data: " + formatBytes(cleanup.lastKnownRemainingBytes()) + ". ";
        return prefix
                + cleanup.lastError()
                        .map(error -> "Cleanup failed: " + error.message() + " Retry cleanup.")
                        .orElse("Cleanup did not complete. Retry cleanup.");
    }

    private static long remainingBytes(TrainingDatasetListRecord record) {
        return switch (record) {
            case TrainingDatasetRecord retained -> retained.retainedBytes();
            case TrainingDatasetCleanupRecord cleanup -> cleanup.lastKnownRemainingBytes();
        };
    }

    static String storageText(TrainingDatasetListRecord record) {
        String value = formatBytes(remainingBytes(record));
        return record instanceof TrainingDatasetCleanupRecord ? "Last known: " + value : value;
    }

    private static String shortId(String id) {
        return id.substring(0, 8);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int index = -1;
        do {
            value /= 1024.0;
            index++;
        } while (value >= 1024.0 && index < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.2f %s", value, units[index]);
    }
}
