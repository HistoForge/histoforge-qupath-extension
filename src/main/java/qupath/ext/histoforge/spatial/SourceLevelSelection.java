package qupath.ext.histoforge.spatial;

import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;

/** One source-level selection rule shared by inference and training capture. */
final class SourceLevelSelection {

    private SourceLevelSelection() {}

    static PinnedSourceLevel select(CapturedSourcePyramid source, double targetMpp) {
        double requested = CanonicalCodec.requirePositiveFiniteDouble(targetMpp, "target_mpp");
        SourceIdentityCodec.PixelCalibration calibration =
                source.sourceRevision().pixelSource().calibration();
        SourceLevel selected = null;
        double selectedArea = -1;
        for (SourceLevel level : source.levels()) {
            SourceIdentityCodec.ScaleTranslate2D pixelsToSlide =
                    SourceViewGeometry.selectedLevelPixelsToSlide(
                            source.levelZeroPixelsToSlide(), level.pixelsToLevelZero());
            double mppX = calibration.mppX() * pixelsToSlide.scaleX();
            double mppY = calibration.mppY() * pixelsToSlide.scaleY();
            double area = mppX * mppY;
            if (mppX <= requested && mppY <= requested && area > selectedArea) {
                selected = level;
                selectedArea = area;
            }
        }
        if (selected == null) {
            selected =
                    source.levels().stream()
                            .filter(level -> level.resolutionLevel() == 0)
                            .findFirst()
                            .orElseThrow();
        }
        return new PinnedSourceLevel(
                source.seriesIndex(),
                selected.resolutionLevel(),
                selected.dimensions(),
                selected.pixelsToLevelZero());
    }
}
