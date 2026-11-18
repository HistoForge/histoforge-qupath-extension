package qupath.ext.histoforge.ui.training;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Deterministic inline SVG rendering for one or more explicitly compatible series. */
final class TrainingMetricChart {

    private static final int WIDTH = 560;
    private static final int HEIGHT = 252;
    private static final int LEFT = 78;
    private static final int RIGHT = 16;
    private static final int TOP = 18;
    private static final int BOTTOM = 44;

    private TrainingMetricChart() {}

    static String render(List<Point> points, int totalEpochs) {
        Line line = new Line(0, points);
        double minimum = line.points().stream().mapToDouble(Point::value).min().orElseThrow();
        double maximum = line.points().stream().mapToDouble(Point::value).max().orElseThrow();
        return render(List.of(line), totalEpochs, minimum, maximum, false);
    }

    /** Render declared unit-interval series together on one fixed, scientifically honest axis. */
    static String renderUnitInterval(List<Line> lines, int totalEpochs) {
        return render(lines, totalEpochs, 0, 1, true);
    }

    private static String render(
            List<Line> lines,
            int totalEpochs,
            double minimum,
            double maximum,
            boolean unitInterval) {
        List<Line> rendered = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (rendered.isEmpty() || totalEpochs < 1) {
            throw new IllegalArgumentException("metric chart requires data and total epochs");
        }
        BigDecimal maximumDecimal = BigDecimal.valueOf(maximum);
        BigDecimal range = maximumDecimal.subtract(BigDecimal.valueOf(minimum));
        int graphWidth = WIDTH - LEFT - RIGHT;
        int graphHeight = HEIGHT - TOP - BOTTOM;
        List<String> paths = new ArrayList<>(rendered.size());
        for (Line line : rendered) {
            paths.add(
                    line.points().stream()
                            .map(
                                    point -> {
                                        double x =
                                                LEFT
                                                        + graphWidth
                                                                * (point.epoch() - 1.0)
                                                                / Math.max(1, totalEpochs - 1);
                                        double y =
                                                TOP
                                                        + graphHeight
                                                                * verticalFraction(
                                                                        point.value(),
                                                                        maximumDecimal,
                                                                        range);
                                        return number(x) + "," + number(y);
                                    })
                            .collect(Collectors.joining(" ")));
        }
        StringBuilder svg = new StringBuilder(512);
        svg.append("<svg class=\"chart\" viewBox=\"0 0 ")
                .append(WIDTH)
                .append(' ')
                .append(HEIGHT)
                .append("\" role=\"img\" aria-label=\"Metric values by epoch\">");
        int tickCount = range.signum() == 0 ? 1 : 5;
        for (int tick = 0; tick < tickCount; tick++) {
            double fraction = tickCount == 1 ? 0.5 : tick / 4.0;
            double y = TOP + graphHeight * fraction;
            double value =
                    maximumDecimal
                            .subtract(range.multiply(BigDecimal.valueOf(fraction)))
                            .doubleValue();
            svg.append("<line class=\"grid\" x1=\"")
                    .append(LEFT)
                    .append("\" y1=\"")
                    .append(number(y))
                    .append("\" x2=\"")
                    .append(WIDTH - RIGHT)
                    .append("\" y2=\"")
                    .append(number(y))
                    .append("\"/>")
                    .append("<text text-anchor=\"end\" x=\"")
                    .append(LEFT - 10)
                    .append("\" y=\"")
                    .append(number(y + 4))
                    .append("\">")
                    .append(axisNumber(value, unitInterval))
                    .append("</text>");
        }
        svg.append("<line class=\"axis\" x1=\"")
                .append(LEFT)
                .append("\" y1=\"")
                .append(HEIGHT - BOTTOM)
                .append("\" x2=\"")
                .append(WIDTH - RIGHT)
                .append("\" y2=\"")
                .append(HEIGHT - BOTTOM)
                .append("\"/>");
        for (int index = 0; index < rendered.size(); index++) {
            String cssClass = "series-" + rendered.get(index).styleIndex() % 6;
            svg.append("<polyline class=\"series ")
                    .append(cssClass)
                    .append("\" points=\"")
                    .append(paths.get(index))
                    .append("\"/>");
            if (rendered.get(index).points().size() == 1) {
                String[] coordinates = paths.get(index).split(",", 2);
                svg.append("<circle class=\"point ")
                        .append(cssClass)
                        .append("\" cx=\"")
                        .append(coordinates[0])
                        .append("\" cy=\"")
                        .append(coordinates[1])
                        .append("\" r=\"3.5\"/>");
            }
        }
        svg.append("<text x=\"")
                .append(LEFT)
                .append("\" y=\"")
                .append(HEIGHT - BOTTOM + 18)
                .append("\">1</text>");
        if (totalEpochs > 1) {
            svg.append("<text text-anchor=\"end\" x=\"")
                    .append(WIDTH - RIGHT)
                    .append("\" y=\"")
                    .append(HEIGHT - BOTTOM + 18)
                    .append("\">")
                    .append(totalEpochs)
                    .append("</text>");
        }
        svg.append("<text text-anchor=\"middle\" x=\"")
                .append(LEFT + graphWidth / 2)
                .append("\" y=\"")
                .append(HEIGHT - 5)
                .append("\">Epoch</text></svg>");
        return svg.toString();
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String axisNumber(double value, boolean unitInterval) {
        return unitInterval
                ? String.format(java.util.Locale.ROOT, "%.3f", value)
                : TrainingHtml.displayReal(value);
    }

    private static double verticalFraction(double value, BigDecimal maximum, BigDecimal range) {
        if (range.signum() == 0) {
            return 0.5;
        }
        return maximum.subtract(BigDecimal.valueOf(value))
                .divide(range, MathContext.DECIMAL64)
                .doubleValue();
    }

    record Line(int styleIndex, List<Point> points) {
        Line {
            if (styleIndex < 0) {
                throw new IllegalArgumentException("metric chart style index must be nonnegative");
            }
            points = List.copyOf(Objects.requireNonNull(points, "points"));
            if (points.isEmpty()) {
                throw new IllegalArgumentException("metric chart line requires at least one point");
            }
        }
    }

    record Point(int epoch, double value) {}
}
