package qupath.ext.histoforge.contract;

import java.util.Objects;
import java.util.Optional;
import java.util.function.IntToDoubleFunction;

/** Exact source-level view transforms shared by planning and backend admission fixtures. */
public final class SourceViewGeometry {

    private SourceViewGeometry() {}

    /** Positive width and height of one source-pyramid level or derived artifact. */
    public record Dimensions(int width, int height) {
        public Dimensions {
            width = CanonicalCodec.requirePositiveInt32(width, "width");
            height = CanonicalCodec.requirePositiveInt32(height, "height");
        }
    }

    /** Compose one selected pyramid level's pixel-edge mapping into slide coordinates. */
    public static SourceIdentityCodec.ScaleTranslate2D selectedLevelPixelsToSlide(
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            SourceIdentityCodec.ScaleTranslate2D selectedLevelPixelsToLevelZero) {
        Objects.requireNonNull(levelZeroPixelsToSlide, "level_zero_pixels_to_slide");
        Objects.requireNonNull(
                selectedLevelPixelsToLevelZero, "selected_level_pixels_to_level_zero");
        return new SourceIdentityCodec.ScaleTranslate2D(
                levelZeroPixelsToSlide.scaleX() * selectedLevelPixelsToLevelZero.scaleX(),
                levelZeroPixelsToSlide.scaleY() * selectedLevelPixelsToLevelZero.scaleY(),
                levelZeroPixelsToSlide.translateX()
                        + selectedLevelPixelsToLevelZero.translateX()
                                * levelZeroPixelsToSlide.scaleX(),
                levelZeroPixelsToSlide.translateY()
                        + selectedLevelPixelsToLevelZero.translateY()
                                * levelZeroPixelsToSlide.scaleY());
    }

    /** Map one original-pyramid view's local pixel edges into slide space. */
    public static SourceIdentityCodec.ScaleTranslate2D deriveOriginalViewTransform(
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            SourceIdentityCodec.ScaleTranslate2D selectedLevelPixelsToLevelZero,
            Dimensions selectedLevelDimensions,
            SourceIdentityCodec.IntRect pixelRect) {
        Objects.requireNonNull(levelZeroPixelsToSlide, "level_zero_pixels_to_slide");
        Objects.requireNonNull(
                selectedLevelPixelsToLevelZero, "selected_level_pixels_to_level_zero");
        Objects.requireNonNull(selectedLevelDimensions, "selected_level_dimensions");
        requireContained(pixelRect, selectedLevelDimensions, "original source view");

        double scaleX = levelZeroPixelsToSlide.scaleX() * selectedLevelPixelsToLevelZero.scaleX();
        double scaleY = levelZeroPixelsToSlide.scaleY() * selectedLevelPixelsToLevelZero.scaleY();
        return new SourceIdentityCodec.ScaleTranslate2D(
                scaleX,
                scaleY,
                levelZeroPixelsToSlide.translateX()
                        + (selectedLevelPixelsToLevelZero.translateX()
                                        + pixelRect.x() * selectedLevelPixelsToLevelZero.scaleX())
                                * levelZeroPixelsToSlide.scaleX(),
                levelZeroPixelsToSlide.translateY()
                        + (selectedLevelPixelsToLevelZero.translateY()
                                        + pixelRect.y() * selectedLevelPixelsToLevelZero.scaleY())
                                * levelZeroPixelsToSlide.scaleY());
    }

    /** Map one derived-artifact view's local pixel edges into slide space. */
    public static SourceIdentityCodec.ScaleTranslate2D deriveDerivedViewTransform(
            SourceIdentityCodec.ScaleTranslate2D artifactPixelsToSlide,
            Dimensions artifactDimensions,
            SourceIdentityCodec.IntRect pixelRect) {
        Objects.requireNonNull(artifactPixelsToSlide, "artifact_pixels_to_slide");
        Objects.requireNonNull(artifactDimensions, "artifact_dimensions");
        requireContained(pixelRect, artifactDimensions, "derived source view");
        return new SourceIdentityCodec.ScaleTranslate2D(
                artifactPixelsToSlide.scaleX(),
                artifactPixelsToSlide.scaleY(),
                artifactPixelsToSlide.translateX() + pixelRect.x() * artifactPixelsToSlide.scaleX(),
                artifactPixelsToSlide.translateY()
                        + pixelRect.y() * artifactPixelsToSlide.scaleY());
    }

    /**
     * Return the smallest selected-level rectangle containing the exact bilinear footprint.
     *
     * <p>The candidate view origin is settled against the same view-local {@code float} coordinates
     * consumed by the pinned backend OpenCV kernel. Only nonzero-weight neighbors inside the
     * physical selected level are retained; true image-border contributions remain opaque white in
     * the backend.
     */
    public static SourceIdentityCodec.IntRect requiredSourcePixelRect(
            SourceIdentityCodec.ScaleTranslate2D selectedLevelPixelsToSlide,
            Dimensions selectedLevelDimensions,
            RasterInputCodec.RasterTransform target) {
        Objects.requireNonNull(selectedLevelPixelsToSlide, "selected_level_pixels_to_slide");
        Objects.requireNonNull(selectedLevelDimensions, "selected_level_dimensions");
        Objects.requireNonNull(target, "target");
        return requiredSourcePixelRect(
                selectedLevelDimensions,
                target,
                selectedLevelPixelsToSlide.scaleX(),
                selectedLevelPixelsToSlide.scaleY(),
                origin ->
                        selectedLevelPixelsToSlide.translateX()
                                + origin * selectedLevelPixelsToSlide.scaleX(),
                origin ->
                        selectedLevelPixelsToSlide.translateY()
                                + origin * selectedLevelPixelsToSlide.scaleY());
    }

    /**
     * Return the smallest original-pyramid rectangle using the backend's exact composition order.
     *
     * <p>Keeping the two source mappings separate is significant: composing them first and then
     * adding the view offset is algebraically equivalent but can differ by one binary64 unit.
     */
    public static SourceIdentityCodec.IntRect requiredOriginalSourcePixelRect(
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            SourceIdentityCodec.ScaleTranslate2D selectedLevelPixelsToLevelZero,
            Dimensions selectedLevelDimensions,
            RasterInputCodec.RasterTransform target) {
        Objects.requireNonNull(levelZeroPixelsToSlide, "level_zero_pixels_to_slide");
        Objects.requireNonNull(
                selectedLevelPixelsToLevelZero, "selected_level_pixels_to_level_zero");
        Objects.requireNonNull(selectedLevelDimensions, "selected_level_dimensions");
        Objects.requireNonNull(target, "target");
        double scaleX = levelZeroPixelsToSlide.scaleX() * selectedLevelPixelsToLevelZero.scaleX();
        double scaleY = levelZeroPixelsToSlide.scaleY() * selectedLevelPixelsToLevelZero.scaleY();
        return requiredSourcePixelRect(
                selectedLevelDimensions,
                target,
                scaleX,
                scaleY,
                origin ->
                        levelZeroPixelsToSlide.translateX()
                                + (selectedLevelPixelsToLevelZero.translateX()
                                                + origin * selectedLevelPixelsToLevelZero.scaleX())
                                        * levelZeroPixelsToSlide.scaleX(),
                origin ->
                        levelZeroPixelsToSlide.translateY()
                                + (selectedLevelPixelsToLevelZero.translateY()
                                                + origin * selectedLevelPixelsToLevelZero.scaleY())
                                        * levelZeroPixelsToSlide.scaleY());
    }

    /**
     * Source-level pixels needed for a processing rectangle on the complete model grid. The
     * captured view origin stays fixed: rebasing it to each file would change float32 sampling. An
     * empty result means the rectangle samples only the true image's white border.
     */
    public static Optional<SourceIdentityCodec.IntRect> requiredCapturedSourcePixelRect(
            SourceCaptureCodec.View view,
            RasterInputCodec.RasterTransform target,
            SourceIdentityCodec.IntRect processingRect) {
        var raster = target.raster();
        requireContained(
                processingRect,
                new Dimensions(raster.width(), raster.height()),
                "processing rectangle");
        var capture = view.capture();
        var calibration = capture.source().calibration();
        var targetMapping = target.rasterToSlide();
        if (!raster.plane().equals(capture.plane())
                || targetMapping.scaleX() != raster.targetMpp() / calibration.mppX()
                || targetMapping.scaleY() != raster.targetMpp() / calibration.mppY()) {
            throw new IllegalArgumentException("target grid differs from the source capture");
        }
        var geometry = SourceCaptureCodec.viewGeometry(view);
        var rectangle = geometry.sourceRectAtLevel();
        var mapping = geometry.viewPixelsToSlide();
        AxisRange x =
                requiredAxisAtViewOrigin(
                        processingRect.x(),
                        Math.addExact(processingRect.x(), processingRect.width()),
                        targetMapping.translateX(),
                        targetMapping.scaleX(),
                        mapping.scaleX(),
                        capture.sourceLevel().width(),
                        rectangle.x(),
                        ignored -> mapping.translateX());
        AxisRange y =
                requiredAxisAtViewOrigin(
                        processingRect.y(),
                        Math.addExact(processingRect.y(), processingRect.height()),
                        targetMapping.translateY(),
                        targetMapping.scaleY(),
                        mapping.scaleY(),
                        capture.sourceLevel().height(),
                        rectangle.y(),
                        ignored -> mapping.translateY());
        if (x == null || y == null) return Optional.empty();
        var required =
                new SourceIdentityCodec.IntRect(
                        x.minimum(),
                        y.minimum(),
                        x.maximum() - x.minimum() + 1,
                        y.maximum() - y.minimum() + 1);
        SourceCaptureCodec.requireContained(required, rectangle);
        return Optional.of(required);
    }

    private static SourceIdentityCodec.IntRect requiredSourcePixelRect(
            Dimensions dimensions,
            RasterInputCodec.RasterTransform target,
            double sourceScaleX,
            double sourceScaleY,
            IntToDoubleFunction viewTranslateX,
            IntToDoubleFunction viewTranslateY) {
        AxisRange x =
                requiredAxis(
                        target.raster().width(),
                        target.rasterToSlide().translateX(),
                        target.rasterToSlide().scaleX(),
                        sourceScaleX,
                        dimensions.width(),
                        viewTranslateX);
        AxisRange y =
                requiredAxis(
                        target.raster().height(),
                        target.rasterToSlide().translateY(),
                        target.rasterToSlide().scaleY(),
                        sourceScaleY,
                        dimensions.height(),
                        viewTranslateY);
        if (x == null || y == null) {
            throw new IllegalArgumentException(
                    "target raster has no bilinear sampling footprint inside the source");
        }
        return new SourceIdentityCodec.IntRect(
                x.minimum(),
                y.minimum(),
                x.maximum() - x.minimum() + 1,
                y.maximum() - y.minimum() + 1);
    }

    private static AxisRange requiredAxis(
            int targetCount,
            double targetTranslate,
            double targetScale,
            double sourceScale,
            int sourceExtent,
            IntToDoubleFunction viewTranslate) {
        AxisRange current =
                requiredAxisAtViewOrigin(
                        0,
                        targetCount,
                        targetTranslate,
                        targetScale,
                        sourceScale,
                        sourceExtent,
                        0,
                        viewTranslate);
        for (int attempt = 0; attempt < 8; attempt++) {
            if (current == null) {
                return null;
            }
            AxisRange next =
                    requiredAxisAtViewOrigin(
                            0,
                            targetCount,
                            targetTranslate,
                            targetScale,
                            sourceScale,
                            sourceExtent,
                            current.minimum(),
                            viewTranslate);
            if (current.equals(next)) {
                return current;
            }
            current = next;
        }
        throw new IllegalArgumentException("source-view footprint did not converge");
    }

    private static AxisRange requiredAxisAtViewOrigin(
            int targetStart,
            int targetCount,
            double targetTranslate,
            double targetScale,
            double sourceScale,
            int sourceExtent,
            int viewOrigin,
            IntToDoubleFunction viewTranslate) {
        int first =
                firstCoordinateAbove(
                        targetCount,
                        -1.0,
                        targetTranslate,
                        targetScale,
                        sourceScale,
                        viewOrigin,
                        viewTranslate);
        int afterLast =
                firstCoordinateAtLeast(
                        targetCount,
                        sourceExtent,
                        targetTranslate,
                        targetScale,
                        sourceScale,
                        viewOrigin,
                        viewTranslate);
        first = Math.max(first, targetStart);
        if (first >= afterLast) {
            return null;
        }
        float firstCoordinate =
                sourceCoordinate(
                        first,
                        targetTranslate,
                        targetScale,
                        sourceScale,
                        viewOrigin,
                        viewTranslate);
        float lastCoordinate =
                sourceCoordinate(
                        afterLast - 1,
                        targetTranslate,
                        targetScale,
                        sourceScale,
                        viewOrigin,
                        viewTranslate);
        int minimum = inImageMinimum(firstCoordinate, viewOrigin);
        int maximum = inImageMaximum(lastCoordinate, viewOrigin, sourceExtent);
        return minimum <= maximum ? new AxisRange(minimum, maximum) : null;
    }

    private static int firstCoordinateAbove(
            int count,
            double threshold,
            double targetTranslate,
            double targetScale,
            double sourceScale,
            int viewOrigin,
            IntToDoubleFunction viewTranslate) {
        int low = 0;
        int high = count;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (globalCoordinate(
                            middle,
                            targetTranslate,
                            targetScale,
                            sourceScale,
                            viewOrigin,
                            viewTranslate)
                    > threshold) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private static int firstCoordinateAtLeast(
            int count,
            double threshold,
            double targetTranslate,
            double targetScale,
            double sourceScale,
            int viewOrigin,
            IntToDoubleFunction viewTranslate) {
        int low = 0;
        int high = count;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (globalCoordinate(
                            middle,
                            targetTranslate,
                            targetScale,
                            sourceScale,
                            viewOrigin,
                            viewTranslate)
                    >= threshold) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private static double globalCoordinate(
            int targetIndex,
            double targetTranslate,
            double targetScale,
            double sourceScale,
            int viewOrigin,
            IntToDoubleFunction viewTranslate) {
        return (double)
                        sourceCoordinate(
                                targetIndex,
                                targetTranslate,
                                targetScale,
                                sourceScale,
                                viewOrigin,
                                viewTranslate)
                + viewOrigin;
    }

    private static float sourceCoordinate(
            int targetIndex,
            double targetTranslate,
            double targetScale,
            double sourceScale,
            int viewOrigin,
            IntToDoubleFunction viewTranslate) {
        float value =
                (float)
                        ((targetTranslate
                                                + (targetIndex + 0.5) * targetScale
                                                - viewTranslate.applyAsDouble(viewOrigin))
                                        / sourceScale
                                - 0.5);
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("target-to-source mapping is not finite");
        }
        return value;
    }

    private static int inImageMinimum(float coordinate, int viewOrigin) {
        int base = (int) Math.floor(coordinate);
        return Math.max(0, base + viewOrigin);
    }

    private static int inImageMaximum(float coordinate, int viewOrigin, int sourceExtent) {
        int base = (int) Math.floor(coordinate);
        double fraction = (double) coordinate - base;
        return Math.min(sourceExtent - 1, base + viewOrigin + (fraction > 0.0 ? 1 : 0));
    }

    private static void requireContained(
            SourceIdentityCodec.IntRect rectangle, Dimensions dimensions, String path) {
        Objects.requireNonNull(rectangle, "pixel_rect");
        long maxX = (long) rectangle.x() + rectangle.width();
        long maxY = (long) rectangle.y() + rectangle.height();
        if (maxX > dimensions.width() || maxY > dimensions.height()) {
            throw new IllegalArgumentException(path + " lies outside its selected level");
        }
    }

    private record AxisRange(int minimum, int maximum) {}
}
