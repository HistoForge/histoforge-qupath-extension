package qupath.ext.histoforge.pipeline;

import java.util.Objects;
import qupath.ext.histoforge.contract.SourceIdentityCodec;

/** Framework-wide physical-calibration gate shared by ordinary and metric Groovy scripts. */
public final class ScriptCalibrationGate {

    /** Maximum relative X/Y MPP mismatch accepted by script API version 1. */
    public static final double SQUARE_MPP_RELATIVE_TOLERANCE = 0.0d;

    private ScriptCalibrationGate() {}

    /**
     * Validate and return the two-axis calibration that may be exposed to a script.
     *
     * <p>The returned record has already passed the positive-finite scalar contract and the global
     * square-MPP rule. Callers must not bind calibration from another source.
     */
    public static SourceIdentityCodec.PixelCalibration requireSupported(double mppX, double mppY) {
        var calibration = new SourceIdentityCodec.PixelCalibration(mppX, mppY);
        double relativeDifference = relativeDifference(calibration);
        if (relativeDifference > SQUARE_MPP_RELATIVE_TOLERANCE) {
            throw new IllegalArgumentException(
                    "script calibration is not square: mpp_x="
                            + mppX
                            + ", mpp_y="
                            + mppY
                            + ", relative_difference="
                            + relativeDifference
                            + ", tolerance="
                            + SQUARE_MPP_RELATIVE_TOLERANCE);
        }
        return calibration;
    }

    /** Compute the exact relative anisotropy used by the gate and diagnostics. */
    public static double relativeDifference(SourceIdentityCodec.PixelCalibration calibration) {
        Objects.requireNonNull(calibration, "calibration");
        return Math.abs(calibration.mppX() - calibration.mppY())
                / Math.max(calibration.mppX(), calibration.mppY());
    }
}
