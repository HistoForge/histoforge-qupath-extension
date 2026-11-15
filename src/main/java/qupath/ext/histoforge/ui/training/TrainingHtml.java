package qupath.ext.histoforge.ui.training;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/** Escaping shared by every self-contained training-report HTML section. */
final class TrainingHtml {

    private static final MathContext FOUR_SIGNIFICANT_DIGITS =
            new MathContext(4, RoundingMode.HALF_UP);

    private TrainingHtml() {}

    static String escape(String value) {
        String text = Objects.requireNonNull(value, "HTML text");
        StringBuilder escaped = new StringBuilder(text.length());
        text.codePoints()
                .forEach(
                        point -> {
                            switch (point) {
                                case '&' -> escaped.append("&amp;");
                                case '<' -> escaped.append("&lt;");
                                case '>' -> escaped.append("&gt;");
                                case '"' -> escaped.append("&quot;");
                                case '\'' -> escaped.append("&#39;");
                                default -> escaped.appendCodePoint(point);
                            }
                        });
        return escaped.toString();
    }

    /** Compact presentation for real-valued metrics; exact values live in report details. */
    static String displayReal(double value) {
        if (value == 0) {
            return "0";
        }
        double magnitude = Math.abs(value);
        if (magnitude >= 10_000 || magnitude < 0.001) {
            return String.format(Locale.ROOT, "%.3e", value);
        }
        return BigDecimal.valueOf(value)
                .round(FOUR_SIGNIFICANT_DIGITS)
                .stripTrailingZeros()
                .toPlainString();
    }
}
