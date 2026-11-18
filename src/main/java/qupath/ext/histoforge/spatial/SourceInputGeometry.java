package qupath.ext.histoforge.spatial;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import org.locationtech.jts.operation.overlayng.OverlayNGRobust;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

/** Coordinate transforms, target clipping and QuPath-compatible prediction outlines. */
final class SourceInputGeometry {

    private SourceInputGeometry() {}

    static SpatialFrame spatialFrame(
            Envelope frame,
            double targetMpp,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.ImageSourceIdentity source,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            PinnedSourceLevel selectedLevel,
            ExecutionLimitsCodec.TargetRasterLimits finiteLimits) {
        RasterInputCodec.RasterTransform transform =
                rasterTransform(frame, targetMpp, plane, source);
        SourceIdentityCodec.IntRect sourceRect =
                SourceViewGeometry.requiredOriginalSourcePixelRect(
                        levelZeroPixelsToSlide,
                        selectedLevel.pixelsToLevelZero(),
                        selectedLevel.dimensions(),
                        transform);
        return new SpatialFrame(
                transform,
                sourceRect,
                ExecutionLimitsCodec.fitsTargetRaster(transform.raster(), finiteLimits));
    }

    static Geometry geometryToRaster(
            Geometry slideGeometry, RasterInputCodec.RasterTransform transform) {
        SourceIdentityCodec.ScaleTranslate2D mapping = transform.rasterToSlide();
        Geometry result = slideGeometry.copy();
        result.apply(
                new CoordinateSequenceFilter() {
                    @Override
                    public void filter(CoordinateSequence sequence, int index) {
                        sequence.setOrdinate(
                                index,
                                0,
                                (sequence.getX(index) - mapping.translateX()) / mapping.scaleX());
                        sequence.setOrdinate(
                                index,
                                1,
                                (sequence.getY(index) - mapping.translateY()) / mapping.scaleY());
                    }

                    @Override
                    public boolean isDone() {
                        return false;
                    }

                    @Override
                    public boolean isGeometryChanged() {
                        return true;
                    }
                });
        result.geometryChanged();
        CanonicalGeometryCodec.validatePolygonalGeometry(result);
        return result;
    }

    static Geometry geometryToSlide(
            Geometry rasterGeometry, RasterInputCodec.RasterTransform transform) {
        SourceIdentityCodec.ScaleTranslate2D mapping = transform.rasterToSlide();
        Geometry result = rasterGeometry.copy();
        result.apply(
                new CoordinateSequenceFilter() {
                    @Override
                    public void filter(CoordinateSequence sequence, int index) {
                        sequence.setOrdinate(
                                index,
                                0,
                                mapping.translateX() + sequence.getX(index) * mapping.scaleX());
                        sequence.setOrdinate(
                                index,
                                1,
                                mapping.translateY() + sequence.getY(index) * mapping.scaleY());
                    }

                    @Override
                    public boolean isDone() {
                        return false;
                    }

                    @Override
                    public boolean isGeometryChanged() {
                        return true;
                    }
                });
        result.geometryChanged();
        if (result instanceof Point) CanonicalGeometryCodec.validatePointGeometry(result);
        else CanonicalGeometryCodec.validatePolygonalGeometry(result);
        return result;
    }

    /**
     * Materialize slide geometry into the coordinate representation QuPath persists for its ROI.
     *
     * <p>QuPath writes a {@code GeometryROI} as 2D WKB. Use the factory observed through the loaded
     * host's public reader, including its coordinate precision and storage. Simple polygons are
     * instead converted through QuPath's shape-backed ROI implementations. Applying both branches
     * before staging keeps the in-memory candidate equal to its later {@code .qpdata}
     * representation, so scripts, metrics, revision checks, and persistence all observe one
     * geometry. Points instead use native float32 XY without the host's generic geometry
     * conversion.
     */
    static Geometry geometryForQuPathPersistence(
            Geometry slideGeometry, SourceIdentityCodec.ImagePlaneIndex plane) {
        if (slideGeometry instanceof Point point)
            return NativeRoiGeometry.convert(persistentPoint(point, plane));
        return geometryForValidatedQuPathPersistence(
                validatedGeometryCopy(slideGeometry, "slide geometry"), plane);
    }

    private static Geometry geometryForValidatedQuPathPersistence(
            Geometry materialized, SourceIdentityCodec.ImagePlaneIndex plane) {
        Objects.requireNonNull(plane, "plane");
        if (usesGeometryRoiPersistence(materialized)) {
            materialized = GeometryRoiPersistence.materialize(materialized);
        }
        Geometry result =
                NativeRoiGeometry.convert(
                        GeometryTools.geometryToROI(
                                materialized, ImagePlane.getPlane(plane.z(), plane.t())));
        CanonicalGeometryCodec.validatePolygonalGeometry(result);
        if (!(result.getArea() > 0.0)) {
            throw new IllegalArgumentException(
                    "QuPath persistent ROI geometry must have positive area");
        }
        return result;
    }

    /**
     * Create the native outline used by staging and saving. Keep the final conversion: float
     * rounding in the first conversion can turn a near-rectangle into a native rectangle. The
     * materialized coordinates are already validated; later internal handoffs share the ROI without
     * repeating its shape-to-polygon conversion. A point is stored directly as one native mark.
     */
    static ROI roiForQuPathPersistence(
            Geometry slideGeometry, SourceIdentityCodec.ImagePlaneIndex plane) {
        if (slideGeometry instanceof Point point) return persistentPoint(point, plane);
        return roiForValidatedQuPathPersistence(
                validatedGeometryCopy(slideGeometry, "slide geometry"), plane);
    }

    private static ROI roiForValidatedQuPathPersistence(
            Geometry slideGeometry, SourceIdentityCodec.ImagePlaneIndex plane) {
        ROI result =
                GeometryTools.geometryToROI(
                        geometryForValidatedQuPathPersistence(slideGeometry, plane),
                        ImagePlane.getPlane(plane.z(), plane.t()));
        // Float-backed polygon vertices can become a rectangle in this final conversion.
        // Rectangles use the host's different precision path, which may collapse a tiny shape.
        // Check that representation too; ordinary polygons already hold validated native points.
        return result instanceof RectangleROI ? validatedPredictionRoi(result, plane) : result;
    }

    /** A conversion outcome, not evidence that a candidate was committed or saved. */
    record PredictionOutline(Optional<ROI> roi, boolean precisionRepaired) {}

    /**
     * Apply the approved native-precision repair only after a valid model polygon collapses in
     * QuPath's representation. Exact source validation, parent admission and target clipping happen
     * before this method. Points only project to native XY; they never enter polygon repair. Never
     * fix malformed model geometry or repair an existing shared ROI.
     */
    static PredictionOutline materializePrediction(
            Geometry slideGeometry, SourceIdentityCodec.ImagePlaneIndex plane) {
        if (slideGeometry instanceof Point point)
            return new PredictionOutline(Optional.of(persistentPoint(point, plane)), false);
        Geometry validated = validatedGeometryCopy(slideGeometry, "slide geometry");
        Objects.requireNonNull(plane, "plane");
        try {
            return new PredictionOutline(
                    Optional.of(roiForValidatedQuPathPersistence(validated, plane)), false);
        } catch (IllegalArgumentException originalFailure) {
            Geometry projected = projectedNativeGeometry(validated, plane);
            if (projected.isValid() && !projected.isEmpty() && projected.getArea() > 0.0) {
                throw originalFailure;
            }
            // GeometryFixer discards collapsed components rather than turning them into lines.
            // Run once at the host's actual precision; do not clip or simplify the repaired shape.
            Geometry repaired = GeometryFixer.fix(projected, false);
            if (repaired.isEmpty()) {
                return new PredictionOutline(Optional.empty(), true);
            }
            try {
                return new PredictionOutline(
                        Optional.of(roiForQuPathPersistence(repaired, plane)), true);
            } catch (IllegalArgumentException repairFailure) {
                repairFailure.addSuppressed(originalFailure);
                throw repairFailure;
            }
        }
    }

    private static Geometry projectedNativeGeometry(
            Geometry geometry, SourceIdentityCodec.ImagePlaneIndex plane) {
        Geometry projected =
                usesGeometryRoiPersistence(geometry)
                        ? GeometryRoiPersistence.materialize(geometry)
                        : geometry;
        var nativePlane = ImagePlane.getPlane(plane.z(), plane.t());
        Geometry first =
                NativeRoiGeometry.convert(GeometryTools.geometryToROI(projected, nativePlane));
        if (!first.isValid() || first.isEmpty() || !(first.getArea() > 0.0)) {
            return first;
        }
        // A float polygon can become a rectangle on the final conversion. Its different native
        // precision may collapse a shape whose first projection was still valid.
        return NativeRoiGeometry.convert(GeometryTools.geometryToROI(first, nativePlane));
    }

    /** Validate a shared, read-only prediction outline without retaining another geometry copy. */
    static ROI validatedPredictionRoi(ROI roi, SourceIdentityCodec.ImagePlaneIndex plane) {
        Objects.requireNonNull(roi, "prediction ROI");
        Objects.requireNonNull(plane, "prediction plane");
        if (!roi.getImagePlane().equals(ImagePlane.getPlane(plane.z(), plane.t()))) {
            throw new IllegalArgumentException(
                    "prediction ROI plane does not match its recorded plane");
        }
        Geometry geometry = NativeRoiGeometry.convert(roi);
        if (roi.isPoint()) {
            CanonicalGeometryCodec.validatePointGeometry(geometry);
            if (!(geometry instanceof Point point)
                    || point.getX() != (double) (float) point.getX()
                    || point.getY() != (double) (float) point.getY()) {
                throw new IllegalArgumentException(
                        "prediction point must use persistent float32 coordinates");
            }
            return roi;
        }
        CanonicalGeometryCodec.validatePolygonalGeometry(geometry);
        if (!(geometry.getArea() > 0.0)) {
            throw new IllegalArgumentException("prediction ROI must have positive area");
        }
        return roi;
    }

    private static ROI persistentPoint(Point point, SourceIdentityCodec.ImagePlaneIndex plane) {
        CanonicalGeometryCodec.validatePointGeometry(point);
        Objects.requireNonNull(plane, "plane");
        float x = (float) point.getX();
        float y = (float) point.getY();
        if (!Float.isFinite(x) || !Float.isFinite(y))
            throw new IllegalArgumentException("point exceeds native coordinate range");
        return ROIs.createPointsROI(x, y, ImagePlane.getPlane(plane.z(), plane.t()));
    }

    static Geometry intersection(Geometry target, Geometry parent) {
        Geometry result = OverlayNGRobust.overlay(target, parent, OverlayNG.INTERSECTION);
        if (result.isEmpty() || result.getArea() <= 0.0) {
            throw new IllegalArgumentException(
                    "source parent must have positive-area intersection with its target");
        }
        return validatedGeometryCopy(result, "target/parent intersection");
    }

    /** Clip a prediction to the outer target, retaining all area components as one feature. */
    static Optional<Geometry> clipToTarget(Geometry prediction, PreparedGeometry target) {
        if (target.covers(prediction)) {
            // Preserve the exact coordinates and ordering when the intersection is an identity.
            return Optional.of(prediction.copy());
        }
        Geometry clipped =
                GeometryTools.ensurePolygonal(
                        OverlayNGRobust.overlay(
                                prediction, target.getGeometry(), OverlayNG.INTERSECTION));
        if (clipped.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(validatedGeometryCopy(clipped, "target-clipped prediction"));
    }

    static boolean hasPositiveIntersection(Geometry target, Geometry parent) {
        Geometry result = OverlayNGRobust.overlay(target, parent, OverlayNG.INTERSECTION);
        return !result.isEmpty() && result.getArea() > 0.0;
    }

    static Envelope parentFrame(
            Envelope parent,
            int paddingModelPx,
            double targetMpp,
            SourceIdentityCodec.ImageSourceIdentity source,
            Envelope sourceBounds) {
        CropFraming.SourcePadding padding =
                CropFraming.toSourcePixels(
                        paddingModelPx,
                        targetMpp,
                        source.calibration().mppX(),
                        source.calibration().mppY());
        return clippedFrame(parent, padding.x(), padding.y(), sourceBounds);
    }

    /**
     * Center real context on each short ROI axis, then clip at the image edge without shifting.
     * Inference requests the saved minimum field side; training capture reserves the capability's
     * maximum permitted side so later runs may choose any admitted field profile.
     */
    static Envelope contextualFieldFrame(
            Envelope roi,
            int minimumSideModelPx,
            double targetMpp,
            SourceIdentityCodec.ImageSourceIdentity source,
            Envelope sourceBounds) {
        double minimumWidth = minimumSideModelPx * targetMpp / source.calibration().mppX();
        double minimumHeight = minimumSideModelPx * targetMpp / source.calibration().mppY();
        double paddingX = Math.max(0.0, (minimumWidth - roi.getWidth()) / 2.0);
        double paddingY = Math.max(0.0, (minimumHeight - roi.getHeight()) / 2.0);
        return clippedFrame(roi, paddingX, paddingY, sourceBounds);
    }

    static Envelope clippedFrame(
            Envelope envelope, double paddingX, double paddingY, Envelope sourceBounds) {
        double minX = Math.max(sourceBounds.getMinX(), envelope.getMinX() - paddingX);
        double minY = Math.max(sourceBounds.getMinY(), envelope.getMinY() - paddingY);
        double maxX = Math.min(sourceBounds.getMaxX(), envelope.getMaxX() + paddingX);
        double maxY = Math.min(sourceBounds.getMaxY(), envelope.getMaxY() + paddingY);
        if (!(maxX > minX) || !(maxY > minY)) {
            throw new IllegalArgumentException("model input frame is empty after source clipping");
        }
        return new Envelope(minX, maxX, minY, maxY);
    }

    static Envelope rasterDomain(RasterInputCodec.RasterTransform transform) {
        RasterInputCodec.RasterDescriptor raster = transform.raster();
        SourceIdentityCodec.ScaleTranslate2D mapping = transform.rasterToSlide();
        return new Envelope(
                mapping.translateX(),
                mapping.translateX() + raster.width() * mapping.scaleX(),
                mapping.translateY(),
                mapping.translateY() + raster.height() * mapping.scaleY());
    }

    static Envelope sourceBounds(
            SourceIdentityCodec.ImageSourceIdentity source,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide) {
        return new Envelope(
                levelZeroPixelsToSlide.translateX(),
                levelZeroPixelsToSlide.translateX()
                        + source.width() * levelZeroPixelsToSlide.scaleX(),
                levelZeroPixelsToSlide.translateY(),
                levelZeroPixelsToSlide.translateY()
                        + source.height() * levelZeroPixelsToSlide.scaleY());
    }

    static SourceIdentityCodec.IntRect unionRect(List<SourceIdentityCodec.IntRect> rectangles) {
        if (rectangles.isEmpty()) {
            throw new IllegalArgumentException("source rectangles must be nonempty");
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        long maxX = Long.MIN_VALUE;
        long maxY = Long.MIN_VALUE;
        for (SourceIdentityCodec.IntRect rectangle : rectangles) {
            minX = Math.min(minX, rectangle.x());
            minY = Math.min(minY, rectangle.y());
            maxX = Math.max(maxX, (long) rectangle.x() + rectangle.width());
            maxY = Math.max(maxY, (long) rectangle.y() + rectangle.height());
        }
        return new SourceIdentityCodec.IntRect(
                minX, minY, Math.toIntExact(maxX - minX), Math.toIntExact(maxY - minY));
    }

    static void requireContained(
            SourceIdentityCodec.IntRect inner, SourceIdentityCodec.IntRect outer) {
        if (inner.x() < outer.x()
                || inner.y() < outer.y()
                || (long) inner.x() + inner.width() > (long) outer.x() + outer.width()
                || (long) inner.y() + inner.height() > (long) outer.y() + outer.height()) {
            throw new IllegalArgumentException(
                    "resolved parent source view lies outside the planned source envelope");
        }
    }

    static void requireCovered(Envelope inner, Envelope outer, String path) {
        if (inner.getMinX() < outer.getMinX()
                || inner.getMinY() < outer.getMinY()
                || inner.getMaxX() > outer.getMaxX()
                || inner.getMaxY() > outer.getMaxY()) {
            throw new IllegalArgumentException(path + " lies outside the captured source");
        }
    }

    static void requireSchemaOnePlane(
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.ImageSourceIdentity source) {
        if (plane.z() != 0 || plane.t() != 0) {
            throw new IllegalArgumentException("source artifact schema 1 supports only z=0, t=0");
        }
        if (plane.z() >= source.zSlices() || plane.t() >= source.timepoints()) {
            throw new IllegalArgumentException("target plane lies outside the source");
        }
    }

    static Geometry validatedGeometryCopy(Geometry geometry, String path) {
        Geometry copy = Objects.requireNonNull(geometry, path).copy();
        try {
            CanonicalGeometryCodec.validatePolygonalGeometry(copy);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(path + " is invalid", error);
        }
        return copy;
    }

    static RasterInputCodec.RasterTransform rasterTransform(
            Envelope frame,
            double targetMpp,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.ImageSourceIdentity source) {
        double stepX = targetMpp / source.calibration().mppX();
        double stepY = targetMpp / source.calibration().mppY();
        int width = rasterDimension(frame.getWidth(), stepX);
        int height = rasterDimension(frame.getHeight(), stepY);
        return new RasterInputCodec.RasterTransform(
                new RasterInputCodec.RasterDescriptor(
                        width,
                        height,
                        plane,
                        targetMpp,
                        RasterInputCodec.ResamplingType.BILINEAR,
                        RasterInputCodec.RasterPixelFormat.SRGB_UINT8),
                new SourceIdentityCodec.ScaleTranslate2D(
                        stepX, stepY, frame.getMinX(), frame.getMinY()));
    }

    private static int rasterDimension(double extent, double step) {
        double count = Math.ceil(extent / step);
        if (!Double.isFinite(count) || count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("target raster dimension exceeds int32");
        }
        return Math.max(1, (int) count);
    }

    private static boolean usesGeometryRoiPersistence(Geometry geometry) {
        return geometry.getNumGeometries() > 1
                || geometry instanceof Polygon polygon && polygon.getNumInteriorRing() > 0;
    }

    record SpatialFrame(
            RasterInputCodec.RasterTransform transform,
            SourceIdentityCodec.IntRect sourceRect,
            boolean fitsFinite) {}
}
