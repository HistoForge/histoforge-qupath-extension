package qupath.ext.histoforge.ui.training;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainerMetricSeries;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainerMetricValue;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingEpochMetricRecord;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingReport;

/** Compact metric presentation driven only by the report's frozen metric policy. */
final class TrainingMetricsHtml {

    private TrainingMetricsHtml() {}

    static String render(TrainingReport report) {
        boolean hasValidation = report.validationCounts().isPresent();
        List<TrainerMetricSeries> visible =
                report.metricPolicy().series().stream()
                        .filter(series -> hasValidation || "training".equals(series.partition()))
                        .toList();
        List<SeriesData> unitValidation = new ArrayList<>();
        List<SeriesData> separate = new ArrayList<>();
        for (TrainerMetricSeries series : visible) {
            SeriesData data = new SeriesData(series, points(report, series.seriesId()));
            if ("validation".equals(series.partition())
                    && "unit_interval".equals(series.valueDomain())) {
                unitValidation.add(data);
            } else {
                separate.add(data);
            }
        }

        StringBuilder html = new StringBuilder(8_192);
        html.append("<section id=\"metrics\"><h2>Metrics</h2><div class=\"metric-grid\">");
        if (!unitValidation.isEmpty()) {
            groupedValidation(html, report, unitValidation);
        }
        for (SeriesData data : separate) {
            separateSeries(html, report, data);
        }
        html.append("</div>");
        if (!hasValidation) {
            html.append("<p class=\"empty\">No held-out validation.</p>");
        }
        metricDetails(html, report, visible);
        html.append("</section>");
        return html.toString();
    }

    static int completedEpochs(TrainingReport report) {
        return report.epochs().isEmpty() ? 0 : report.epochs().getLast().epoch();
    }

    static long elapsedSeconds(TrainingReport report) {
        return report.epochs().isEmpty() ? 0 : report.epochs().getLast().elapsedSeconds();
    }

    static String duration(long totalSeconds) {
        long hours = totalSeconds / 3_600;
        long minutes = totalSeconds % 3_600 / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds);
        }
        return seconds + "s";
    }

    static String displayValue(TrainerMetricSeries series, double value) {
        if ("unit_interval".equals(series.valueDomain())) {
            return String.format(Locale.ROOT, "%.3f", value);
        }
        return TrainingHtml.displayReal(value);
    }

    private static void groupedValidation(
            StringBuilder html, TrainingReport report, List<SeriesData> series) {
        html.append("<article class=\"metric-panel\"><div class=\"metric-heading\"><h3>")
                .append(series.size() == 1 ? "Validation metric" : "Validation metrics")
                .append("</h3><span class=\"pill\">0–1 scale</span></div>");
        List<TrainingMetricChart.Line> lines = new ArrayList<>();
        for (int index = 0; index < series.size(); index++) {
            if (!series.get(index).points().isEmpty()) {
                lines.add(new TrainingMetricChart.Line(index, series.get(index).points()));
            }
        }
        if (!lines.isEmpty()) {
            html.append(TrainingMetricChart.renderUnitInterval(lines, report.totalEpochs()));
        } else {
            html.append("<div class=\"chart-empty\">No validation values recorded</div>");
        }
        html.append("<div class=\"metric-values\">");
        for (int index = 0; index < series.size(); index++) {
            SeriesData data = series.get(index);
            html.append("<div><span class=\"legend series-")
                    .append(index % 6)
                    .append("\"></span><span>")
                    .append(TrainingHtml.escape(data.series().displayName()))
                    .append("</span><strong>")
                    .append(latestOrStatus(report, data))
                    .append("</strong></div>");
        }
        html.append("</div></article>");
    }

    private static void separateSeries(StringBuilder html, TrainingReport report, SeriesData data) {
        html.append("<article class=\"metric-panel\"><div class=\"metric-heading\"><h3>")
                .append(TrainingHtml.escape(data.series().displayName()))
                .append("</h3><span class=\"pill\">")
                .append(TrainingHtml.escape(capitalize(data.series().partition())))
                .append(" · auto scale</span></div>");
        if (!data.points().isEmpty()) {
            html.append(TrainingMetricChart.render(data.points(), report.totalEpochs()));
        } else {
            html.append("<div class=\"chart-empty\">No values recorded</div>");
        }
        html.append("<p class=\"latest\">")
                .append(latestOrStatus(report, data))
                .append("</p></article>");
    }

    private static String latestOrStatus(TrainingReport report, SeriesData data) {
        if (!data.points().isEmpty()) {
            TrainingMetricChart.Point latest = data.points().getLast();
            return displayValue(data.series(), latest.value()) + " · epoch " + latest.epoch();
        }
        return unavailableReason(report, data.series().seriesId())
                .map(TrainingHtml::escape)
                .orElseGet(
                        () ->
                                switch (report.state()) {
                                    case "QUEUED", "RUNNING", "FINALIZING" -> "Pending";
                                    default -> "Not recorded";
                                });
    }

    private static Optional<String> unavailableReason(TrainingReport report, String seriesId) {
        return report.unavailableMetrics().stream()
                .filter(item -> item.seriesId().equals(seriesId))
                .map(item -> item.reason())
                .findFirst();
    }

    private static void metricDetails(
            StringBuilder html, TrainingReport report, List<TrainerMetricSeries> series) {
        html.append(
                "<details class=\"metric-details\"><summary>Metric definitions and exact values</summary>");
        html.append("<dl>");
        for (TrainerMetricSeries item : series) {
            html.append("<dt>")
                    .append(TrainingHtml.escape(item.displayName()))
                    .append("</dt><dd>")
                    .append(TrainingHtml.escape(item.definition()))
                    .append("</dd>");
        }
        html.append("</dl>");
        if (report.epochs().isEmpty()) {
            html.append("<p class=\"empty\">No completed epoch values yet.</p>");
        } else {
            exactTable(html, report, series);
        }
        html.append("</details>");
    }

    private static void exactTable(
            StringBuilder html, TrainingReport report, List<TrainerMetricSeries> series) {
        html.append(
                "<div class=\"table-scroll\"><table><thead><tr><th>Epoch</th><th>Training time</th>");
        for (TrainerMetricSeries item : series) {
            html.append("<th>").append(TrainingHtml.escape(item.displayName())).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (TrainingEpochMetricRecord epoch : report.epochs()) {
            Map<String, Double> values = new LinkedHashMap<>();
            for (TrainerMetricValue metric : epoch.metrics()) {
                values.put(metric.seriesId(), metric.value());
            }
            html.append("<tr><td>")
                    .append(epoch.epoch())
                    .append("</td><td>")
                    .append(duration(epoch.elapsedSeconds()))
                    .append("</td>");
            for (TrainerMetricSeries item : series) {
                Double value = values.get(item.seriesId());
                html.append("<td>")
                        .append(
                                value == null
                                        ? "Not recorded"
                                        : TrainingHtml.escape(Double.toString(value)))
                        .append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table></div>");
    }

    private static List<TrainingMetricChart.Point> points(TrainingReport report, String seriesId) {
        List<TrainingMetricChart.Point> result = new ArrayList<>();
        for (TrainingEpochMetricRecord epoch : report.epochs()) {
            for (TrainerMetricValue metric : epoch.metrics()) {
                if (metric.seriesId().equals(seriesId)) {
                    result.add(new TrainingMetricChart.Point(epoch.epoch(), metric.value()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static String capitalize(String value) {
        return value.isEmpty()
                ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record SeriesData(TrainerMetricSeries series, List<TrainingMetricChart.Point> points) {
        private SeriesData {
            points = List.copyOf(points);
        }
    }
}
