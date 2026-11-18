package qupath.ext.histoforge.spatial;

import qupath.ext.histoforge.contract.CanonicalCodec;

/** Converts crop context from target-model pixels to level-zero source pixels. */
public final class CropFraming {

    private CropFraming() {}

    /** Independent level-zero padding distances for the source X and Y axes. */
    public record SourcePadding(double x, double y) {
        public SourcePadding {
            x = requireNonnegativeFinite(x, "source_padding_x");
            y = requireNonnegativeFinite(y, "source_padding_y");
        }
    }

    /**
     * Convert model-pixel padding through physical distance into source pixels.
     *
     * <p>{@code paddingModelPx * targetMpp} is the requested context in microns. Dividing that
     * distance by each source-axis calibration preserves the same physical context when source
     * pixels are anisotropic.
     */
    public static SourcePadding toSourcePixels(
            int paddingModelPx, double targetMpp, double sourceMppX, double sourceMppY) {
        if (paddingModelPx < 0) {
            throw new IllegalArgumentException("padding_model_px must be nonnegative");
        }
        double checkedTargetMpp =
                CanonicalCodec.requirePositiveFiniteDouble(targetMpp, "target_mpp");
        double checkedSourceMppX =
                CanonicalCodec.requirePositiveFiniteDouble(sourceMppX, "source_mpp_x");
        double checkedSourceMppY =
                CanonicalCodec.requirePositiveFiniteDouble(sourceMppY, "source_mpp_y");
        double paddingMicrons = paddingModelPx * checkedTargetMpp;
        return new SourcePadding(
                paddingMicrons / checkedSourceMppX, paddingMicrons / checkedSourceMppY);
    }

    private static double requireNonnegativeFinite(double value, String name) {
        double checked = CanonicalCodec.requireFiniteDouble(value, name);
        if (checked < 0.0) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
        return checked;
    }
}
