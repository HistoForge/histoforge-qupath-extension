package qupath.ext.histoforge.ui.training;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.training.TrainingAssignment;
import qupath.ext.histoforge.training.TrainingProjectIndex.UnitRow;
import qupath.ext.histoforge.ui.HistoforgeStyles;

/** Two stacked assignment tables with one shared multi-row drag owner. */
final class TrainingAssignmentTable {

    private static final String DRAG_MARKER = "histoforge-training-assignment";

    private final TableView<DisplayRow> trainingTable;
    private final TableView<DisplayRow> validationTable;
    private final Label trainingLabel;
    private final Label validationLabel;
    private final VBox validationBox;
    private final BiConsumer<List<Integer>, String> onMove;
    private List<Integer> draggedUnitIndices = List.of();
    private String draggedFrom;

    TrainingAssignmentTable(
            TableView<DisplayRow> trainingTable,
            TableView<DisplayRow> validationTable,
            Label trainingLabel,
            Label validationLabel,
            VBox validationBox,
            BiConsumer<List<Integer>, String> onMove) {
        this.trainingTable = Objects.requireNonNull(trainingTable, "trainingTable");
        this.validationTable = Objects.requireNonNull(validationTable, "validationTable");
        this.trainingLabel = Objects.requireNonNull(trainingLabel, "trainingLabel");
        this.validationLabel = Objects.requireNonNull(validationLabel, "validationLabel");
        this.validationBox = Objects.requireNonNull(validationBox, "validationBox");
        this.onMove = Objects.requireNonNull(onMove, "onMove");
        configure(this.trainingTable, TrainingAssignment.TRAINING, "No training rows.");
        configure(
                this.validationTable,
                TrainingAssignment.VALIDATION,
                "No validation rows. Drag rows here.");
    }

    void show(TrainingAssignment assignment, int validationPercent) {
        boolean showValidation = validationPercent > 0;
        validationBox.setVisible(showValidation);
        validationBox.setManaged(showValidation);
        if (assignment == null) {
            trainingTable.setItems(FXCollections.observableArrayList());
            validationTable.setItems(FXCollections.observableArrayList());
            trainingLabel.setText("Training");
            validationLabel.setText("Validation");
            return;
        }
        showPartition(trainingTable, trainingLabel, assignment, TrainingAssignment.TRAINING);
        showPartition(validationTable, validationLabel, assignment, TrainingAssignment.VALIDATION);
    }

    void setDisabled(boolean disabled) {
        trainingTable.setDisable(disabled);
        validationTable.setDisable(disabled);
    }

    private static void showPartition(
            TableView<DisplayRow> table,
            Label label,
            TrainingAssignment assignment,
            String partition) {
        List<UnitRow> units =
                assignment.unitIndices(partition).stream()
                        .map(index -> assignment.projectIndex().rows().get(index))
                        .toList();
        table.setItems(
                FXCollections.observableArrayList(
                        units.stream().map(unit -> new DisplayRow(partition, unit)).toList()));
        Set<String> images = new HashSet<>();
        int rois = 0;
        for (UnitRow unit : units) {
            images.addAll(unit.imageIds());
            rois = Math.addExact(rois, unit.roiCount());
        }
        String name = TrainingAssignment.TRAINING.equals(partition) ? "Training" : "Validation";
        label.setText(
                name
                        + " · "
                        + images.size()
                        + (images.size() == 1 ? " image · " : " images · ")
                        + rois
                        + (rois == 1 ? " ROI" : " ROIs"));
    }

    private void configure(TableView<DisplayRow> table, String partition, String emptyText) {
        installColumns(table);
        installRows(table, partition);
        installDropTarget(table, partition);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        Label placeholder = new Label(emptyText);
        placeholder.getStyleClass().add(HistoforgeStyles.MUTED);
        table.setPlaceholder(placeholder);
    }

    private static void installColumns(TableView<DisplayRow> table) {
        TableColumn<DisplayRow, String> handle = stringColumn("", row -> "⋮⋮");
        handle.setMinWidth(34);
        handle.setPrefWidth(34);
        handle.setMaxWidth(34);
        TableColumn<DisplayRow, String> unit = stringColumn("Unit", DisplayRow::unitText);
        unit.setPrefWidth(180);
        TableColumn<DisplayRow, String> images = stringColumn("Images", DisplayRow::imageText);
        images.setPrefWidth(180);
        TableColumn<DisplayRow, String> group = stringColumn("Group", DisplayRow::groupText);
        TableColumn<DisplayRow, Number> rois = numberColumn("ROIs", row -> row.roiCount());
        TableColumn<DisplayRow, Number> used = numberColumn("Used", row -> row.used());
        TableColumn<DisplayRow, Number> noClass = numberColumn("No class", row -> row.noClass());
        TableColumn<DisplayRow, Number> notUsed = numberColumn("Not used", row -> row.notUsed());
        table.getColumns().setAll(handle, unit, images, group, rois, used, noClass, notUsed);
    }

    private static TableColumn<DisplayRow, String> stringColumn(
            String title, java.util.function.Function<DisplayRow, String> value) {
        TableColumn<DisplayRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(item -> new ReadOnlyStringWrapper(value.apply(item.getValue())));
        column.setReorderable(false);
        column.setSortable(false);
        return column;
    }

    private static TableColumn<DisplayRow, Number> numberColumn(
            String title, java.util.function.ToIntFunction<DisplayRow> value) {
        TableColumn<DisplayRow, Number> column = new TableColumn<>(title);
        column.setCellValueFactory(
                item -> new ReadOnlyObjectWrapper<>(value.applyAsInt(item.getValue())));
        column.setReorderable(false);
        column.setSortable(false);
        return column;
    }

    private void installRows(TableView<DisplayRow> table, String partition) {
        table.setRowFactory(
                ignored -> {
                    TableRow<DisplayRow> row = new TableRow<>();
                    row.itemProperty()
                            .addListener(
                                    (observable, previous, current) ->
                                            row.setCursor(
                                                    current == null
                                                            ? Cursor.DEFAULT
                                                            : Cursor.HAND));
                    row.setOnDragDetected(
                            event -> {
                                DisplayRow item = row.getItem();
                                if (item == null) {
                                    return;
                                }
                                List<DisplayRow> selected =
                                        table.getSelectionModel().getSelectedItems().stream()
                                                .filter(
                                                        candidate ->
                                                                candidate
                                                                        .partition()
                                                                        .equals(partition))
                                                .toList();
                                if (!selected.contains(item)) {
                                    table.getSelectionModel().clearSelection();
                                    table.getSelectionModel().select(row.getIndex());
                                    selected = List.of(item);
                                }
                                draggedUnitIndices =
                                        selected.stream()
                                                .map(candidate -> candidate.unit().unitIndex())
                                                .distinct()
                                                .toList();
                                draggedFrom = partition;
                                ClipboardContent content = new ClipboardContent();
                                content.putString(DRAG_MARKER);
                                row.startDragAndDrop(TransferMode.MOVE).setContent(content);
                                event.consume();
                            });
                    row.setOnDragDone(
                            event -> {
                                draggedUnitIndices = List.of();
                                draggedFrom = null;
                            });
                    return row;
                });
    }

    private void installDropTarget(TableView<DisplayRow> table, String destination) {
        table.setOnDragOver(
                event -> {
                    if (DRAG_MARKER.equals(event.getDragboard().getString())
                            && !destination.equals(draggedFrom)) {
                        event.acceptTransferModes(TransferMode.MOVE);
                        event.consume();
                    }
                });
        table.setOnDragDropped(
                event -> {
                    boolean accepted =
                            DRAG_MARKER.equals(event.getDragboard().getString())
                                    && !destination.equals(draggedFrom)
                                    && !draggedUnitIndices.isEmpty();
                    if (accepted) {
                        onMove.accept(draggedUnitIndices, destination);
                    }
                    event.setDropCompleted(accepted);
                    event.consume();
                });
    }

    record DisplayRow(String partition, UnitRow unit) {
        DisplayRow {
            partition = Objects.requireNonNull(partition, "partition");
            unit = Objects.requireNonNull(unit, "unit");
        }

        String unitText() {
            return unit.unitLabel();
        }

        String imageText() {
            return unit.imageDisplay();
        }

        String groupText() {
            return unit.projectGroup().orElse("Not assigned");
        }

        int roiCount() {
            return unit.roiCount();
        }

        int used() {
            return unit.counts().used();
        }

        int noClass() {
            return unit.counts().noClass();
        }

        int notUsed() {
            return unit.counts().notUsed();
        }
    }
}
