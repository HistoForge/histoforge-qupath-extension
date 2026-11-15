package qupath.ext.histoforge.ui.project;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import qupath.ext.histoforge.data.MetricFormat;

/** Displays formatted metrics as rows and the caller's ordered entities as columns. */
public final class MetricsTableView {
    private MetricsTableView() {}

    public record MetricsTableRow(String label, Map<String, String> values) {}

    public static TableView<MetricsTableRow> build(
            List<MetricsTableRow> rows, List<String> columnKeys, List<String> columnHeaders) {
        var table = new TableView<MetricsTableRow>();
        table.getColumns().add(column("Metric", 200, MetricsTableRow::label));
        for (int index = 0; index < columnKeys.size(); index++) {
            String key = columnKeys.get(index);
            table.getColumns()
                    .add(
                            column(
                                    columnHeaders.get(index),
                                    80,
                                    row -> row.values().getOrDefault(key, MetricFormat.NO_VALUE)));
        }
        table.getItems().setAll(rows);
        table.setFixedCellSize(28);
        table.setPrefHeight(28 * (rows.size() + 1) + 2);
        table.setMaxHeight(table.getPrefHeight());
        return table;
    }

    private static TableColumn<MetricsTableRow, String> column(
            String title, int width, Function<MetricsTableRow, String> value) {
        var column = new TableColumn<MetricsTableRow, String>(title);
        column.setMinWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }
}
