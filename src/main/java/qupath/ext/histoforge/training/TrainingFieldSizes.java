package qupath.ext.histoforge.training;

import java.math.BigDecimal;
import java.math.RoundingMode;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingTaskCodec.ClosedIntegerRange;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;

/** Resolves editable view sizes into the exact model-pixel integers carried by the run. */
public final class TrainingFieldSizes {

    public enum Unit {
        MICROMETRES("µm"),
        MODEL_PIXELS("model px");

        private final String label;

        Unit(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private TrainingFieldSizes() {}

    /** Round each requested side to its nearest alignment, with halfway ties upward. */
    public static VariableFieldProfile resolve(
            ModelTrainingCapability capability,
            double mpp,
            Unit unit,
            String minimum,
            String maximum,
            String preferred,
            boolean context) {
        var defaults = (VariableFieldProfile) capability.defaultFieldProfile();
        int alignment = defaults.alignmentModelPx();
        ClosedIntegerRange allowed = capability.allowedFieldSideModelPx();
        int min = resolveSide(minimum, unit, mpp, alignment, allowed);
        int max = resolveSide(maximum, unit, mpp, alignment, allowed);
        int tile = resolveSide(preferred, unit, mpp, alignment, allowed);
        if (min > tile || tile > max) {
            throw new IllegalArgumentException(
                    "View sizes must follow minimum <= larger-ROI view <= maximum.");
        }
        if (!capability.smallRoiContextEditable() && context != defaults.smallRoiContext()) {
            throw new IllegalArgumentException("Surrounding tissue is fixed for this model.");
        }
        return new VariableFieldProfile(
                min,
                max,
                tile,
                alignment,
                defaults.outerArtificialPaddingModelPx(),
                context,
                defaults.tileOverlap(),
                defaults.aggregation(),
                defaults.preprocessing());
    }

    /** Exact decimal display of an authoritative model-pixel side in either UI unit. */
    public static String display(int modelPixels, Unit unit, double mpp) {
        BigDecimal value = BigDecimal.valueOf(modelPixels);
        if (unit == Unit.MICROMETRES) {
            value = value.multiply(BigDecimal.valueOf(mpp));
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static int resolveSide(
            String text, Unit unit, double mpp, int alignment, ClosedIntegerRange allowed) {
        BigDecimal requested;
        try {
            requested = new BigDecimal(text.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Enter a positive number for every view size.", error);
        }
        if (requested.signum() <= 0) {
            throw new IllegalArgumentException("Every view size must be positive.");
        }
        BigDecimal spacing = BigDecimal.valueOf(alignment);
        if (unit == Unit.MICROMETRES) {
            spacing = spacing.multiply(BigDecimal.valueOf(mpp));
        }
        BigDecimal resolved =
                requested
                        .divide(spacing, 0, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(alignment));
        if (resolved.signum() <= 0
                || resolved.compareTo(BigDecimal.valueOf(allowed.minimum())) < 0
                || resolved.compareTo(BigDecimal.valueOf(allowed.maximum())) > 0) {
            throw new IllegalArgumentException(
                    "Aligned view sizes must be between "
                            + allowed.minimum()
                            + " and "
                            + allowed.maximum()
                            + " model pixels.");
        }
        try {
            return resolved.intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("The view size is too large.", error);
        }
    }
}
