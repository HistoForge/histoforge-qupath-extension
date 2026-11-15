package qupath.ext.histoforge.ui.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.data.MetricFormat;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMetric;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricImageResult;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionResult;
import qupath.ext.histoforge.data.MetricTypes.MetricSchema;
import qupath.ext.histoforge.data.MetricsAggregator;
import qupath.ext.histoforge.data.ProjectSummaryData;
import qupath.ext.histoforge.data.ProjectSummaryData.CachedImageMetrics;
import qupath.ext.histoforge.data.ProjectSummaryData.RoiSummary;
import qupath.ext.histoforge.ui.HistoforgeStyles;

/** Builds Project-tab metric tables exclusively from exact process-local cache hits. */
final class ProjectMetricsTableBuilder {

    private static final String LEVEL_GROUP = "Per Group";
    private static final String LEVEL_IMAGE = "Per Image";
    private static final String LEVEL_REGION = "Per ROI / Region";
    private static final String ALL_KEY = "__all__";

    private final Map<String, String> selectedLevelByModule = new HashMap<>();
    private ProjectSummaryData summaryData;

    /** Build current metric tables and any capture-failure warning from one detached snapshot. */
    Node build(ProjectSummaryData summaryData) {
        this.summaryData = Objects.requireNonNull(summaryData, "summaryData");
        List<ModuleView> modules = moduleViews(summaryData.cachedMetrics());
        if (modules.isEmpty()) {
            return emptyOrFailureLabel();
        }

        List<TitledPane> panes = modules.stream().map(this::modulePane).toList();
        Node metricContent;
        if (panes.size() == 1) {
            metricContent = panes.getFirst();
            VBox.setVgrow(metricContent, Priority.ALWAYS);
        } else {
            SplitPane split = new SplitPane();
            split.setOrientation(javafx.geometry.Orientation.VERTICAL);
            split.getItems().addAll(panes);
            VBox.setVgrow(split, Priority.ALWAYS);
            metricContent = split;
        }

        if (summaryData.metricFailures().isEmpty()) {
            return metricContent;
        }
        VBox combined = new VBox(6, failureLabel(), metricContent);
        VBox.setVgrow(metricContent, Priority.ALWAYS);
        return combined;
    }

    private Node emptyOrFailureLabel() {
        boolean hasFailure = !summaryData.metricFailures().isEmpty();
        Label label = hasFailure ? failureLabel() : new Label("No current metrics computed yet.");
        if (!hasFailure) {
            label.getStyleClass().add(HistoforgeStyles.MUTED);
        }
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(javafx.geometry.Pos.CENTER);
        VBox.setVgrow(label, Priority.ALWAYS);
        return label;
    }

    private Label failureLabel() {
        int count = summaryData.metricFailures().size();
        Label label =
                new Label(
                        "Metrics unavailable for "
                                + count
                                + " image"
                                + (count == 1 ? "" : "s")
                                + "; no stale values are shown.");
        HistoforgeStyles.setSemantic(label, HistoforgeStyles.WARNING);
        return label;
    }

    private TitledPane modulePane(ModuleView module) {
        String moduleRef = module.moduleRef();
        ComboBox<String> level = new ComboBox<>();
        level.getItems().addAll(LEVEL_GROUP, LEVEL_IMAGE, LEVEL_REGION);
        level.setValue(selectedLevelByModule.getOrDefault(moduleRef, LEVEL_GROUP));

        int regions =
                module.images().stream()
                        .map(CachedImageMetrics::result)
                        .mapToInt(result -> result.regionResults().size())
                        .sum();
        TitledPane pane = new TitledPane();
        pane.setGraphic(
                new HBox(
                        8,
                        new Label(
                                module.displayName()
                                        + "  ("
                                        + module.images().size()
                                        + " images, "
                                        + regions
                                        + " regions)"),
                        level));
        ((HBox) pane.getGraphic()).setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        pane.setExpanded(true);
        pane.setContent(buildLevelTable(module, level.getValue()));
        level.valueProperty()
                .addListener(
                        (ignored, oldValue, newValue) -> {
                            if (newValue != null) {
                                selectedLevelByModule.put(moduleRef, newValue);
                                pane.setContent(buildLevelTable(module, newValue));
                            }
                        });
        return pane;
    }

    private TableView<MetricsTableView.MetricsTableRow> buildLevelTable(
            ModuleView module, String level) {
        if (LEVEL_GROUP.equals(level)) {
            return groupTable(module);
        }
        if (LEVEL_IMAGE.equals(level)) {
            return imageTable(module);
        }
        return regionTable(module);
    }

    private TableView<MetricsTableView.MetricsTableRow> groupTable(ModuleView module) {
        Map<String, List<CachedImageMetrics>> groups = new TreeMap<>();
        module.images()
                .forEach(
                        image ->
                                groups.computeIfAbsent(image.group(), k -> new ArrayList<>())
                                        .add(image));
        Map<String, Map<String, AggregatedMetric>> values = new LinkedHashMap<>();
        groups.forEach((group, images) -> values.put(group, aggregate(module.schema(), images)));
        values.put(ALL_KEY, aggregate(module.schema(), module.images()));

        List<String> keys = new ArrayList<>(groups.keySet());
        List<String> headers = new ArrayList<>(keys);
        keys.add(ALL_KEY);
        headers.add("All");
        return MetricsTableView.build(aggregateRows(module.schema(), values), keys, headers);
    }

    private TableView<MetricsTableView.MetricsTableRow> imageTable(ModuleView module) {
        Map<String, Map<String, AggregatedMetric>> values = new LinkedHashMap<>();
        module.images()
                .forEach(
                        image ->
                                values.put(
                                        image.imageId(),
                                        aggregate(module.schema(), List.of(image))));
        values.put(ALL_KEY, aggregate(module.schema(), module.images()));
        List<MetricsTableView.MetricsTableRow> rows = aggregateRows(module.schema(), values);

        TableView<MetricsTableView.MetricsTableRow> table = baseTable(rows);
        Map<String, List<CachedImageMetrics>> groups = new TreeMap<>();
        module.images()
                .forEach(
                        image ->
                                groups.computeIfAbsent(image.group(), k -> new ArrayList<>())
                                        .add(image));
        groups.forEach(
                (group, images) -> {
                    TableColumn<MetricsTableView.MetricsTableRow, ?> parent =
                            new TableColumn<>(group);
                    images.forEach(
                            image ->
                                    parent.getColumns()
                                            .add(leafColumn(image.imageName(), image.imageId())));
                    table.getColumns().add(parent);
                });
        table.getColumns().add(leafColumn("All", ALL_KEY));
        size(table, rows.size());
        return table;
    }

    private TableView<MetricsTableView.MetricsTableRow> regionTable(ModuleView module) {
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        for (MetricDefinition definition : module.schema().definitions()) {
            values.put(definition.name(), new LinkedHashMap<>());
        }

        TableView<MetricsTableView.MetricsTableRow> table = new TableView<>();
        table.getColumns().add(labelColumn());
        Map<String, List<CachedImageMetrics>> groups = new TreeMap<>();
        module.images()
                .forEach(
                        image ->
                                groups.computeIfAbsent(image.group(), k -> new ArrayList<>())
                                        .add(image));
        groups.forEach(
                (group, images) -> {
                    TableColumn<MetricsTableView.MetricsTableRow, ?> groupColumn =
                            new TableColumn<>(group);
                    for (CachedImageMetrics image : images) {
                        TableColumn<MetricsTableView.MetricsTableRow, ?> imageColumn =
                                new TableColumn<>(image.imageName());
                        MetricImageResult result = result(image);
                        for (Map.Entry<ExecutionScopeCodec.RegionKey, MetricRegionResult> region :
                                result.regionResults().entrySet()) {
                            String key = regionColumnKey(image.imageId(), region.getKey());
                            module.schema()
                                    .definitions()
                                    .forEach(
                                            definition ->
                                                    values.get(definition.name())
                                                            .put(
                                                                    key,
                                                                    MetricFormat.region(
                                                                            region.getValue(),
                                                                            definition)));
                            imageColumn
                                    .getColumns()
                                    .add(
                                            leafColumn(
                                                    regionLabel(image.imageId(), region.getKey()),
                                                    key));
                        }
                        if (!imageColumn.getColumns().isEmpty()) {
                            groupColumn.getColumns().add(imageColumn);
                        }
                    }
                    if (!groupColumn.getColumns().isEmpty()) {
                        table.getColumns().add(groupColumn);
                    }
                });

        Map<String, AggregatedMetric> all = aggregate(module.schema(), module.images());
        for (MetricDefinition definition : module.schema().definitions()) {
            values.get(definition.name())
                    .put(ALL_KEY, MetricFormat.aggregate(all.get(definition.name()), definition));
        }
        table.getColumns().add(leafColumn("All", ALL_KEY));
        List<MetricsTableView.MetricsTableRow> rows =
                module.schema().definitions().stream()
                        .map(
                                definition ->
                                        new MetricsTableView.MetricsTableRow(
                                                MetricFormat.rowLabel(definition),
                                                values.get(definition.name())))
                        .toList();
        table.getItems().setAll(rows);
        size(table, rows.size());
        return table;
    }

    private String regionLabel(String imageId, ExecutionScopeCodec.RegionKey key) {
        if (key instanceof ExecutionScopeCodec.Slide) {
            return "Slide";
        }
        String roiId = ((ExecutionScopeCodec.Roi) key).roiId();
        return summaryData.getImageSummaries().stream()
                .filter(image -> image.entry().getID().equals(imageId))
                .flatMap(image -> image.rois().stream())
                .filter(roi -> roi.roiId().equals(roiId))
                .map(RoiSummary::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(roiId.substring(0, Math.min(8, roiId.length())));
    }

    private static List<MetricsTableView.MetricsTableRow> aggregateRows(
            MetricSchema schema, Map<String, Map<String, AggregatedMetric>> values) {
        return schema.definitions().stream()
                .map(
                        definition -> {
                            Map<String, String> row = new LinkedHashMap<>();
                            values.forEach(
                                    (key, aggregate) ->
                                            row.put(
                                                    key,
                                                    MetricFormat.aggregate(
                                                            aggregate.get(definition.name()),
                                                            definition)));
                            return new MetricsTableView.MetricsTableRow(
                                    MetricFormat.rowLabel(definition), row);
                        })
                .toList();
    }

    private static Map<String, AggregatedMetric> aggregate(
            MetricSchema schema, List<CachedImageMetrics> images) {
        Map<String, AggregatedMetric> result = new LinkedHashMap<>();
        MetricsAggregator.aggregate(
                        schema, images.stream().map(ProjectMetricsTableBuilder::result).toList())
                .forEach(metric -> result.put(metric.name(), metric));
        return result;
    }

    private static MetricImageResult result(CachedImageMetrics image) {
        return image.result();
    }

    private static List<ModuleView> moduleViews(List<CachedImageMetrics> metrics) {
        Map<String, List<CachedImageMetrics>> grouped = new LinkedHashMap<>();
        metrics.forEach(
                image -> {
                    String ref = image.module().moduleRef().toString();
                    grouped.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(image);
                });
        List<ModuleView> result = new ArrayList<>();
        grouped.forEach(
                (ref, images) -> {
                    CachedImageMetrics first = images.getFirst();
                    var moduleIdentity = first.module();
                    MetricSchema schema = first.schema();
                    for (CachedImageMetrics image : images) {
                        if (!moduleIdentity.equals(image.module())
                                || !schema.equals(image.schema())) {
                            throw new IllegalStateException(
                                    "Project metrics cannot mix module revisions under " + ref);
                        }
                    }
                    result.add(
                            new ModuleView(ref, first.moduleName(), schema, List.copyOf(images)));
                });
        result.sort(Comparator.comparing(ModuleView::moduleRef));
        return List.copyOf(result);
    }

    private static String regionColumnKey(String imageId, ExecutionScopeCodec.RegionKey region) {
        return imageId + ":" + CanonicalCodec.canonicalDigest(ExecutionScopeCodec.toJson(region));
    }

    private static TableView<MetricsTableView.MetricsTableRow> baseTable(
            List<MetricsTableView.MetricsTableRow> rows) {
        TableView<MetricsTableView.MetricsTableRow> table = new TableView<>();
        table.getColumns().add(labelColumn());
        table.getItems().setAll(rows);
        return table;
    }

    private static TableColumn<MetricsTableView.MetricsTableRow, String> labelColumn() {
        TableColumn<MetricsTableView.MetricsTableRow, String> column = new TableColumn<>("Metric");
        column.setCellValueFactory(value -> new SimpleStringProperty(value.getValue().label()));
        column.setMinWidth(200);
        return column;
    }

    private static TableColumn<MetricsTableView.MetricsTableRow, String> leafColumn(
            String header, String key) {
        TableColumn<MetricsTableView.MetricsTableRow, String> column = new TableColumn<>(header);
        column.setCellValueFactory(
                value ->
                        new SimpleStringProperty(
                                value.getValue()
                                        .values()
                                        .getOrDefault(key, MetricFormat.NO_VALUE)));
        column.setMinWidth(80);
        return column;
    }

    private static void size(TableView<MetricsTableView.MetricsTableRow> table, int rowCount) {
        table.setFixedCellSize(28);
        table.setPrefHeight(28 * (rowCount + 1) + 2);
        table.setMaxHeight(table.getPrefHeight());
    }

    private record ModuleView(
            String moduleRef,
            String displayName,
            MetricSchema schema,
            List<CachedImageMetrics> images) {}
}
