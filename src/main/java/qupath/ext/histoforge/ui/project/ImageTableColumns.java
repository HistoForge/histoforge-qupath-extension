package qupath.ext.histoforge.ui.project;

import java.util.function.Consumer;
import java.util.regex.Pattern;
import javafx.beans.property.SimpleStringProperty;
import javafx.event.ActionEvent;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.stage.Window;
import qupath.ext.histoforge.ui.HistoforgeStyles;

/**
 * Creates the Project image table. Cell factories clear transient styles and actions when JavaFX
 * reuses a cell for another image; the controller owns row values and group selection.
 */
final class ImageTableColumns {

    private static final Pattern OUTPUT_PRESENCE_PATTERN = StatusStrings.OUTPUT_PRESENCE;

    private ImageTableColumns() {}

    /**
     * Build every image-table column and install them on {@code imageTable}, returning the checkbox
     * header's select {@link MenuButton} (the controller adds per-group selection items to it).
     * {@code onInstallMissing} is invoked with the assigned ref when a missing-module cell is
     * clicked (the controller opens the installer).
     */
    @SuppressWarnings("unchecked")
    static MenuButton install(
            TableView<ImageRowModel> imageTable, Consumer<String> onInstallMissing) {
        imageTable.getColumns().clear();

        // Make all columns proportionally fill the table width on window resize.
        // Without this the table uses the default UNCONSTRAINED policy and an
        // empty area appears on the right when the window is widened. Columns
        // with a maxWidth (checkCol, thumbCol) stay pinned at their fixed size;
        // the rest share the remaining width.
        imageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        var selectMenu = new MenuButton("☐");
        selectMenu.getStyleClass().add("label");
        for (boolean selected : new boolean[] {true, false}) {
            var choice = new MenuItem(selected ? "Select All" : "Select None");
            choice.setOnAction(
                    event -> imageTable.getItems().forEach(row -> row.selected.set(selected)));
            selectMenu.getItems().add(choice);
        }
        TableColumn<ImageRowModel, Boolean> checkCol = fixedColumn(30);
        checkCol.setGraphic(selectMenu);
        checkCol.setCellValueFactory(cell -> cell.getValue().selectedProperty());
        checkCol.setCellFactory(column -> new CheckBoxTableCell<>());
        TableColumn<ImageRowModel, Image> thumbCol = fixedColumn(60);
        thumbCol.setCellValueFactory(
                cell ->
                        new javafx.beans.property.ReadOnlyObjectWrapper<>(
                                cell.getValue().wsiThumbnail));
        thumbCol.setCellFactory(column -> new ThumbnailCell());
        var nameCol = new TableColumn<ImageRowModel, String>("Image");
        nameCol.setCellValueFactory(
                cell -> new SimpleStringProperty(cell.getValue().entry.getImageName()));
        var groupCol = new TableColumn<ImageRowModel, String>("Group");
        groupCol.setPrefWidth(100);
        groupCol.setCellValueFactory(cell -> cell.getValue().groupProperty());

        // Module — the assigned module's name, or a clickable "⚠ <ref> — not installed" warning
        // when that ref isn't in the installed set (incl. a stale bare id). Clicking opens the
        // installer pre-filled with the ref.
        TableColumn<ImageRowModel, String> moduleCol = new TableColumn<>("Module");
        moduleCol.setPrefWidth(140);
        moduleCol.setCellValueFactory(cd -> cd.getValue().moduleNameProperty());
        moduleCol.setCellFactory(
                col ->
                        new TableCell<>() {
                            @Override
                            protected void updateItem(String item, boolean empty) {
                                super.updateItem(item, empty);
                                // Recycled cells carry prior state — always reset the affordance.
                                HistoforgeStyles.setSemantic(this, null);
                                HistoforgeStyles.setClickable(this, false);
                                setTooltip(null);
                                setOnMouseClicked(null);
                                ImageRowModel row =
                                        getTableRow() != null ? getTableRow().getItem() : null;
                                if (empty || row == null) {
                                    setText(null);
                                    return;
                                }
                                if (row.missingModuleRef == null) {
                                    setText(item);
                                    // Display names aren't unique — a fork keeps its name, so two
                                    // installed modules can both read "Leydig". The ref settles
                                    // which one this row actually runs.
                                    if (row.moduleId != null) {
                                        setTooltip(new Tooltip(row.moduleId));
                                    }
                                    return;
                                }
                                setText("⚠ " + row.missingModuleRef + " — not installed");
                                HistoforgeStyles.setSemantic(this, HistoforgeStyles.WARNING);
                                HistoforgeStyles.setClickable(this, true);
                                setTooltip(
                                        new Tooltip(
                                                row.missingModuleRef
                                                        + " isn't installed — click to install"));
                                setOnMouseClicked(
                                        e -> onInstallMissing.accept(row.missingModuleRef));
                            }
                        });

        TableColumn<ImageRowModel, Number> roiCol = new TableColumn<>("ROIs");
        roiCol.setPrefWidth(45);
        roiCol.setCellValueFactory(
                cd -> new javafx.beans.property.SimpleIntegerProperty(cd.getValue().roiCount));

        // Status: current attributed-output presence only, never an execution-completeness ledger.
        TableColumn<ImageRowModel, String> statusCol = new TableColumn<>("Status");
        statusCol.setPrefWidth(120);
        statusCol.setCellValueFactory(cd -> cd.getValue().statusProperty());
        statusCol.setCellFactory(
                col ->
                        new TableCell<>() {
                            @Override
                            protected void updateItem(String item, boolean empty) {
                                super.updateItem(item, empty);
                                String value = empty ? null : item;
                                setText(value);
                                String style = null;
                                if (StatusStrings.OUTPUT_STATE_INVALID.equals(value))
                                    style = HistoforgeStyles.ERROR;
                                else if (isPartialOutputPresence(value))
                                    style = HistoforgeStyles.WARNING;
                                HistoforgeStyles.setSemantic(this, style);
                            }
                        });

        // Last run: process-local typed action result; it is not persisted as completion state.
        TableColumn<ImageRowModel, String> lastBatchCol = new TableColumn<>("Last Run");
        lastBatchCol.setPrefWidth(160);
        lastBatchCol.setCellValueFactory(cd -> cd.getValue().lastBatchStatusProperty());
        lastBatchCol.setCellFactory(
                col ->
                        new TableCell<>() {
                            @Override
                            protected void updateItem(String item, boolean empty) {
                                super.updateItem(item, empty);
                                // Reused cells must not keep another image's error or click action.
                                HistoforgeStyles.setSemantic(this, null);
                                HistoforgeStyles.setClickable(this, false);
                                setOnMouseClicked(null);
                                setTooltip(null);
                                if (empty || item == null || item.isEmpty()) {
                                    setText(null);
                                    return;
                                }
                                setText(item);
                                boolean partial = item.startsWith(StatusStrings.PARTIAL_PREFIX);
                                boolean failed =
                                        item.startsWith(StatusStrings.FAILED_PREFIX)
                                                || item.startsWith(StatusStrings.CONFLICT_PREFIX);
                                if (partial || StatusStrings.CANCELLED.equals(item)) {
                                    HistoforgeStyles.setSemantic(this, HistoforgeStyles.WARNING);
                                } else if (failed) {
                                    HistoforgeStyles.setSemantic(this, HistoforgeStyles.ERROR);
                                }
                                boolean hasFailure = partial || failed;
                                Tooltip tooltip =
                                        new Tooltip(
                                                item
                                                        + (hasFailure
                                                                ? "\n\nClick to view and copy the full message."
                                                                : ""));
                                tooltip.setWrapText(true);
                                tooltip.setMaxWidth(600);
                                setTooltip(tooltip);
                                if (hasFailure) {
                                    HistoforgeStyles.setClickable(this, true);
                                    setOnMouseClicked(
                                            event -> {
                                                if (event.getButton() != MouseButton.PRIMARY)
                                                    return;
                                                var row = getTableRow().getItem();
                                                if (row != null && event.getClickCount() == 1) {
                                                    showRunDetails(
                                                            getScene().getWindow(),
                                                            row.entry.getImageName(),
                                                            getItem());
                                                }
                                                event.consume();
                                            });
                                }
                            }
                        });

        imageTable
                .getColumns()
                .addAll(
                        checkCol,
                        thumbCol,
                        nameCol,
                        groupCol,
                        moduleCol,
                        roiCol,
                        statusCol,
                        lastBatchCol);
        imageTable.setEditable(true);
        return selectMenu;
    }

    private static <T> TableColumn<ImageRowModel, T> fixedColumn(int width) {
        var column = new TableColumn<ImageRowModel, T>();
        column.setMinWidth(width);
        column.setPrefWidth(width);
        column.setMaxWidth(width);
        return column;
    }

    private static final class ThumbnailCell extends TableCell<ImageRowModel, Image> {
        private final ImageView image = new ImageView();

        ThumbnailCell() {
            image.setFitHeight(40);
            image.setPreserveRatio(true);
            setOnMouseClicked(
                    event -> {
                        if (isEmpty() || getItem() == null || getTableRow() == null) return;
                        var available =
                                getTableView().getItems().stream()
                                        .filter(row -> row.wsiThumbnail != null)
                                        .toList();
                        int selected = available.indexOf(getTableRow().getItem());
                        if (selected >= 0)
                            ThumbnailViewer.show(getScene().getWindow(), available, selected);
                    });
        }

        @Override
        protected void updateItem(Image item, boolean empty) {
            super.updateItem(item, empty);
            image.setImage(empty ? null : item);
            setGraphic(image.getImage() == null ? null : image);
        }
    }

    private static void showRunDetails(Window owner, String imageName, String message) {
        var dialog = new Dialog<Void>();
        dialog.initOwner(owner);
        dialog.setTitle("Last Run details");
        dialog.setHeaderText(imageName);
        var text = new TextArea(message);
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefSize(680, 260);
        dialog.getDialogPane().setContent(text);
        var copy = new ButtonType("Copy", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(copy, ButtonType.CLOSE);
        dialog.getDialogPane()
                .lookupButton(copy)
                .addEventFilter(
                        ActionEvent.ACTION,
                        event -> {
                            var content = new ClipboardContent();
                            content.putString(message);
                            Clipboard.getSystemClipboard().setContent(content);
                            event.consume();
                        });
        dialog.setResizable(true);
        dialog.setOnShown(event -> HistoforgeStyles.apply(dialog.getDialogPane().getScene()));
        dialog.show();
    }

    /** True when current attributed output covers some, but not all, selected ROIs. */
    private static boolean isPartialOutputPresence(String s) {
        if (s == null) return false;
        var m = OUTPUT_PRESENCE_PATTERN.matcher(s);
        return m.matches() && Integer.parseInt(m.group(1)) < Integer.parseInt(m.group(2));
    }
}
