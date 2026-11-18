package qupath.ext.histoforge.export;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.data.MetricTypes.AggregatedDistribution;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMetric;
import qupath.ext.histoforge.data.MetricTypes.AggregatedMissing;
import qupath.ext.histoforge.data.MetricTypes.AggregatedScalar;
import qupath.ext.histoforge.data.MetricTypes.Distribution;
import qupath.ext.histoforge.data.MetricTypes.DistributionStats;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricImageResult;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionResult;
import qupath.ext.histoforge.data.MetricTypes.MetricValue;
import qupath.ext.histoforge.data.MetricTypes.Missing;
import qupath.ext.histoforge.data.MetricTypes.Scalar;
import qupath.ext.histoforge.data.MetricsAggregator;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.export.ExportTypes.ExportManifest;
import qupath.ext.histoforge.export.ExportTypes.WorkbookValueStatus;

/** Normalized, analysis-friendly XLSX representation of complete detached metric results. */
final class ExportWorkbookWriter {

    private static final int EXCEL_MAX_ROWS = 1_048_576;
    private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");

    private ExportWorkbookWriter() {}

    /** Workbook values and display context, without capture populations or object attribution. */
    record ImageMetrics(
            String imageId,
            String imageName,
            Map<String, String> studyFields,
            List<MetricsEngine.Evaluation> evaluations) {
        ImageMetrics {
            imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
            imageName = CanonicalCodec.requireNonblank(imageName, "image_name");
            studyFields = Map.copyOf(studyFields);
            evaluations = List.copyOf(evaluations);
            if (evaluations.isEmpty()) {
                throw new IllegalArgumentException("workbook image metrics must be nonempty");
            }
            var revision = evaluations.getFirst().context().sourceRevision();
            if (!revision.imageId().equals(imageId)) {
                throw new IllegalArgumentException("workbook metrics belong to another image");
            }
            var modules = new HashSet<>();
            for (MetricsEngine.Evaluation evaluation : evaluations) {
                if (!evaluation.complete()) {
                    throw new IllegalArgumentException(
                            "workbook cannot contain failed metric regions");
                }
                if (!evaluation.context().sourceRevision().equals(revision)
                        || !modules.add(evaluation.context().module())) {
                    throw new IllegalArgumentException(
                            "workbook modules must be unique and share one image capture");
                }
            }
        }
    }

    static void write(Path destination, ExportManifest manifest, List<ImageMetrics> images)
            throws IOException {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(manifest, "manifest");
        images = List.copyOf(images);
        if (images.size() != manifest.images().size()) {
            throw new IllegalArgumentException("workbook images do not match manifest images");
        }
        List<SheetExtent> sheetExtents = new ArrayList<>();
        try (OutputStream output = Files.newOutputStream(destination);
                Workbook workbook = new Workbook(output, "HistoForge", null)) {
            writeSummary(workbook, images, sheetExtents);
            writePerRegion(workbook, images, sheetExtents);
            writeRawDistributions(workbook, images, sheetExtents);
            writeCoverage(workbook, images, sheetExtents);
            writeEvidence(workbook, manifest, sheetExtents);
        }

        Path absoluteDestination = destination.toAbsolutePath();
        Path normalized =
                Files.createTempFile(
                        absoluteDestination.getParent(), ".histoforge-xlsx-dimensions-", ".tmp");
        try {
            XlsxDimensionNormalizer.rewrite(
                    destination,
                    normalized,
                    sheetExtents.stream().map(SheetExtent::reference).toList());
            Files.move(normalized, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(normalized);
        }
    }

    private static void writeSummary(
            Workbook workbook, List<ImageMetrics> images, List<SheetExtent> sheetExtents) {
        SheetCursor sheet =
                new SheetCursor(
                        workbook,
                        "Summary",
                        List.of(
                                "image_id",
                                "image_name",
                                "group",
                                "module_ref",
                                "metric_name",
                                "label",
                                "unit",
                                "type",
                                "aggregation",
                                "status",
                                "value",
                                "mean",
                                "population_std",
                                "n",
                                "requested_regions",
                                "present_regions",
                                "missing_regions",
                                "failed_regions"),
                        sheetExtents);
        for (ImageMetrics image : images) {
            for (MetricsEngine.Evaluation evaluation : image.evaluations()) {
                MetricImageResult result = evaluation.result();
                List<AggregatedMetric> aggregates =
                        MetricsAggregator.aggregate(evaluation.context().schema(), result);
                Map<String, AggregatedMetric> byName = new LinkedHashMap<>();
                aggregates.forEach(metric -> byName.put(metric.name(), metric));
                for (MetricDefinition definition : evaluation.context().schema().definitions()) {
                    AggregatedMetric metric = byName.get(definition.name());
                    ValueCells cells = aggregateCells(metric);
                    Row row = sheet.next();
                    writeIdentity(row, image, evaluation);
                    writeMetricDefinition(row, 4, definition);
                    text(row, 8, definition.aggregation().name());
                    text(row, 9, cells.status().name());
                    number(row, 10, cells.value());
                    number(row, 11, cells.mean());
                    number(row, 12, cells.populationStd());
                    number(row, 13, cells.sampleCount());
                    number(row, 14, metric.coverage().requestedRegions());
                    number(row, 15, metric.coverage().presentRegions());
                    number(row, 16, metric.coverage().missingRegions());
                    number(row, 17, metric.coverage().failedRegions());
                }
            }
        }
    }

    private static void writePerRegion(
            Workbook workbook, List<ImageMetrics> images, List<SheetExtent> sheetExtents) {
        SheetCursor sheet =
                new SheetCursor(
                        workbook,
                        "Per Region",
                        List.of(
                                "image_id",
                                "image_name",
                                "group",
                                "module_ref",
                                "region_type",
                                "region_id",
                                "target_region_digest",
                                "metric_name",
                                "label",
                                "unit",
                                "type",
                                "status",
                                "value",
                                "mean",
                                "population_std",
                                "n"),
                        sheetExtents);
        forEachRegion(
                images,
                (image, evaluation, region, result, definition, value) -> {
                    Row row = sheet.next();
                    writeIdentity(row, image, evaluation);
                    writeRegion(row, 4, region);
                    writeMetricDefinition(row, 7, definition);
                    ValueCells cells = valueCells(value);
                    text(row, 11, cells.status().name());
                    number(row, 12, cells.value());
                    number(row, 13, cells.mean());
                    number(row, 14, cells.populationStd());
                    number(row, 15, cells.sampleCount());
                });
    }

    private static void writeRawDistributions(
            Workbook workbook, List<ImageMetrics> images, List<SheetExtent> sheetExtents) {
        SheetCursor sheet =
                new SheetCursor(
                        workbook,
                        "Raw Distribution",
                        List.of(
                                "image_id",
                                "image_name",
                                "group",
                                "module_ref",
                                "region_type",
                                "region_id",
                                "target_region_digest",
                                "metric_name",
                                "value_index",
                                "value"),
                        sheetExtents);
        forEachRegion(
                images,
                (image, evaluation, region, result, definition, value) -> {
                    if (!(value instanceof Distribution distribution)) {
                        return;
                    }
                    for (int index = 0; index < distribution.values().size(); index++) {
                        Row row = sheet.next();
                        writeIdentity(row, image, evaluation);
                        writeRegion(row, 4, region);
                        text(row, 7, definition.name());
                        number(row, 8, index);
                        number(row, 9, distribution.values().get(index));
                    }
                });
    }

    private static void writeCoverage(
            Workbook workbook, List<ImageMetrics> images, List<SheetExtent> sheetExtents) {
        SheetCursor sheet =
                new SheetCursor(
                        workbook,
                        "Coverage",
                        List.of(
                                "image_id",
                                "image_name",
                                "group",
                                "module_ref",
                                "metric_name",
                                "requested_regions",
                                "present_regions",
                                "missing_regions",
                                "failed_regions"),
                        sheetExtents);
        for (ImageMetrics image : images) {
            for (MetricsEngine.Evaluation evaluation : image.evaluations()) {
                List<AggregatedMetric> aggregates =
                        MetricsAggregator.aggregate(
                                evaluation.context().schema(), evaluation.result());
                for (AggregatedMetric metric : aggregates) {
                    Row row = sheet.next();
                    writeIdentity(row, image, evaluation);
                    text(row, 4, metric.name());
                    number(row, 5, metric.coverage().requestedRegions());
                    number(row, 6, metric.coverage().presentRegions());
                    number(row, 7, metric.coverage().missingRegions());
                    number(row, 8, metric.coverage().failedRegions());
                }
            }
        }
    }

    private static void writeEvidence(
            Workbook workbook, ExportManifest manifest, List<SheetExtent> sheetExtents) {
        SheetCursor sheet =
                new SheetCursor(
                        workbook,
                        "Evidence",
                        List.of("path", "node_type", "value_text", "value_number", "value_boolean"),
                        sheetExtents);
        JsonElement canonical = CanonicalCodec.canonicalTree(ExportManifestCodec.toJson(manifest));
        List<EvidenceNode> nodes = new ArrayList<>();
        flatten(canonical, "/", nodes);
        for (EvidenceNode node : nodes) {
            Row row = sheet.next();
            text(row, 0, node.path());
            text(row, 1, node.type());
            text(row, 2, node.text());
            number(row, 3, node.number());
            bool(row, 4, node.bool());
        }
    }

    private static void forEachRegion(List<ImageMetrics> images, RegionMetricConsumer consumer) {
        for (ImageMetrics image : images) {
            for (MetricsEngine.Evaluation evaluation : image.evaluations()) {
                for (var target : evaluation.context().regions()) {
                    ExecutionScopeCodec.RegionKey key = target.key();
                    MetricRegionResult result = evaluation.regionResults().get(key);
                    if (result == null) {
                        throw new IllegalArgumentException(
                                "complete export evaluation is missing a requested region");
                    }
                    // The captured target cannot change. Encode its complete identity once for
                    // this table, not once for every metric or raw distribution member.
                    RegionColumns region = regionColumns(target);
                    for (MetricDefinition definition :
                            evaluation.context().schema().definitions()) {
                        consumer.accept(
                                image,
                                evaluation,
                                region,
                                result,
                                definition,
                                result.values().get(definition.name()));
                    }
                }
            }
        }
    }

    private static ValueCells aggregateCells(AggregatedMetric metric) {
        if (metric.value() instanceof AggregatedMissing) {
            return ValueCells.missing();
        }
        if (metric.value() instanceof AggregatedScalar scalar) {
            return ValueCells.scalar(scalar.value());
        }
        DistributionStats statistics = ((AggregatedDistribution) metric.value()).statistics();
        return ValueCells.distribution(statistics);
    }

    private static ValueCells valueCells(MetricValue value) {
        if (value instanceof Missing) {
            return ValueCells.missing();
        }
        if (value instanceof Scalar scalar) {
            return ValueCells.scalar(scalar.value());
        }
        return ValueCells.distribution(distributionStats(((Distribution) value).values()));
    }

    private static DistributionStats distributionStats(List<Double> values) {
        if (values.isEmpty()) {
            return new DistributionStats(0, java.util.Optional.empty(), java.util.Optional.empty());
        }
        long count = 0;
        double mean = 0;
        double sumSquaredDeviations = 0;
        for (double value : values) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            sumSquaredDeviations += delta * (value - mean);
        }
        return new DistributionStats(
                count, java.util.Optional.of(mean), java.util.Optional.of(sumSquaredDeviations));
    }

    private static void writeIdentity(
            Row row, ImageMetrics image, MetricsEngine.Evaluation evaluation) {
        text(row, 0, image.imageId());
        text(row, 1, image.imageName());
        text(row, 2, image.studyFields().get("group"));
        text(row, 3, evaluation.context().module().moduleRef().toString());
    }

    private static RegionColumns regionColumns(ExecutionScopeCodec.TargetRegion target) {
        String type;
        String id;
        if (target.key() instanceof ExecutionScopeCodec.Roi roi) {
            type = "ROI";
            id = roi.roiId();
        } else {
            type = "SLIDE";
            id = "slide";
        }
        return new RegionColumns(type, id, ExecutionScopeCodec.targetRegionDigest(target));
    }

    private static void writeRegion(Row row, int offset, RegionColumns region) {
        text(row, offset, region.type());
        text(row, offset + 1, region.id());
        text(row, offset + 2, region.digest());
    }

    private static void writeMetricDefinition(Row row, int offset, MetricDefinition definition) {
        text(row, offset, definition.name());
        text(row, offset + 1, definition.label());
        text(row, offset + 2, definition.unit().orElse(null));
        text(row, offset + 3, definition.type().name());
    }

    private static void flatten(JsonElement value, String path, List<EvidenceNode> output) {
        if (value.isJsonObject()) {
            output.add(new EvidenceNode(path, "OBJECT", null, null, null));
            JsonObject object = value.getAsJsonObject();
            object.entrySet()
                    .forEach(
                            entry ->
                                    flatten(
                                            entry.getValue(),
                                            childPath(path, escape(entry.getKey())),
                                            output));
            return;
        }
        if (value.isJsonArray()) {
            output.add(new EvidenceNode(path, "ARRAY", null, null, null));
            for (int index = 0; index < value.getAsJsonArray().size(); index++) {
                flatten(
                        value.getAsJsonArray().get(index),
                        childPath(path, Integer.toString(index)),
                        output);
            }
            return;
        }
        if (value.isJsonNull()) {
            output.add(new EvidenceNode(path, "NULL", null, null, null));
            return;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) {
            output.add(new EvidenceNode(path, "STRING", primitive.getAsString(), null, null));
        } else if (primitive.isBoolean()) {
            output.add(new EvidenceNode(path, "BOOLEAN", null, null, primitive.getAsBoolean()));
        } else {
            String canonical = primitive.toString();
            Number number =
                    INTEGER.matcher(canonical).matches()
                            ? Long.parseLong(canonical)
                            : Double.parseDouble(canonical);
            output.add(
                    new EvidenceNode(
                            path,
                            INTEGER.matcher(canonical).matches() ? "INTEGER" : "NUMBER",
                            null,
                            number,
                            null));
        }
    }

    private static String childPath(String parent, String token) {
        return "/".equals(parent) ? "/" + token : parent + "/" + token;
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static void text(Row row, int column, String value) {
        if (value != null) {
            row.sheet().value(row.index(), column, value);
        }
    }

    private static void number(Row row, int column, Number value) {
        if (value != null) {
            row.sheet().value(row.index(), column, value);
        }
    }

    private static void bool(Row row, int column, Boolean value) {
        if (value != null) {
            row.sheet().value(row.index(), column, value);
        }
    }

    private record ValueCells(
            WorkbookValueStatus status,
            Double value,
            Double mean,
            Double populationStd,
            Long sampleCount) {
        private static ValueCells missing() {
            return new ValueCells(WorkbookValueStatus.MISSING, null, null, null, null);
        }

        private static ValueCells scalar(double value) {
            return new ValueCells(WorkbookValueStatus.PRESENT, value, null, null, null);
        }

        private static ValueCells distribution(DistributionStats statistics) {
            if (statistics.sampleCount() == 0) {
                return new ValueCells(WorkbookValueStatus.PRESENT_EMPTY, null, null, null, 0L);
            }
            double std =
                    Math.sqrt(
                            statistics.sumSquaredDeviations().orElseThrow()
                                    / statistics.sampleCount());
            return new ValueCells(
                    WorkbookValueStatus.PRESENT,
                    null,
                    statistics.mean().orElseThrow(),
                    std,
                    statistics.sampleCount());
        }
    }

    @FunctionalInterface
    private interface RegionMetricConsumer {
        void accept(
                ImageMetrics image,
                MetricsEngine.Evaluation evaluation,
                RegionColumns region,
                MetricRegionResult result,
                MetricDefinition definition,
                MetricValue value);
    }

    private record EvidenceNode(
            String path, String type, String text, Number number, Boolean bool) {}

    private record RegionColumns(String type, String id, String digest) {}

    private record Row(Worksheet sheet, int index) {}

    private static final class SheetExtent {
        private final int columns;
        private int rows = 1;

        private SheetExtent(int columns) {
            if (columns < 1) {
                throw new IllegalArgumentException("worksheet must have at least one column");
            }
            this.columns = columns;
        }

        private void include(int zeroBasedRow) {
            rows = Math.max(rows, zeroBasedRow + 1);
        }

        private String reference() {
            return "A1:" + columnName(columns) + rows;
        }

        private static String columnName(int oneBasedColumn) {
            StringBuilder result = new StringBuilder();
            int remaining = oneBasedColumn;
            while (remaining > 0) {
                remaining--;
                result.append((char) ('A' + remaining % 26));
                remaining /= 26;
            }
            return result.reverse().toString();
        }
    }

    /** Shards long normalized tables at Excel's real row limit without changing row semantics. */
    private static final class SheetCursor {
        private final Workbook workbook;
        private final String baseName;
        private final List<String> headers;
        private final List<SheetExtent> sheetExtents;
        private Worksheet sheet;
        private SheetExtent sheetExtent;
        private int row;
        private int part;

        private SheetCursor(
                Workbook workbook,
                String baseName,
                List<String> headers,
                List<SheetExtent> sheetExtents) {
            this.workbook = workbook;
            this.baseName = baseName;
            this.headers = List.copyOf(headers);
            this.sheetExtents = sheetExtents;
            openNext();
        }

        private Row next() {
            if (row >= EXCEL_MAX_ROWS) {
                openNext();
            }
            int nextRow = row++;
            sheetExtent.include(nextRow);
            return new Row(sheet, nextRow);
        }

        private void openNext() {
            part++;
            String name = part == 1 ? baseName : baseName + " " + part;
            sheet = workbook.newWorksheet(name);
            sheetExtent = new SheetExtent(headers.size());
            sheetExtents.add(sheetExtent);
            for (int column = 0; column < headers.size(); column++) {
                sheet.value(0, column, headers.get(column));
                sheet.style(0, column).bold().set();
                sheet.width(column, Math.min(32, Math.max(14, headers.get(column).length() + 2)));
            }
            sheet.freezePane(0, 1);
            row = 1;
        }
    }
}
