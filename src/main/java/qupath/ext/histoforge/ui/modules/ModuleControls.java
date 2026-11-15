package qupath.ext.histoforge.ui.modules;

import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import qupath.ext.histoforge.ui.HistoforgeStyles;

/** Shared native typography and aligned fields for the module browser. */
final class ModuleControls {
    private ModuleControls() {}

    static Label label(String text) {
        var label = new Label(text);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setWrapText(true);
        return label;
    }

    static Label heading(String text) {
        var label = label(text);
        label.getStyleClass().add(HistoforgeStyles.HEADING);
        return label;
    }

    static Label muted(String text) {
        var label = label(text);
        label.getStyleClass().add(HistoforgeStyles.MUTED);
        return label;
    }

    static GridPane fields() {
        var grid = new GridPane();
        grid.setMinWidth(0);
        grid.setHgap(18);
        grid.setVgap(7);
        var key = new ColumnConstraints();
        key.setPercentWidth(30);
        var value = new ColumnConstraints();
        value.setPercentWidth(70);
        value.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(key, value);
        return grid;
    }

    static void field(GridPane grid, String name, String value) {
        int row = grid.getRowCount();
        grid.add(muted(name), 0, row);
        grid.add(label(value), 1, row);
    }
}
