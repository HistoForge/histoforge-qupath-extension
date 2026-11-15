package qupath.ext.histoforge.data;

import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.GeometryROI;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

/** Checks whether QuPath 0.6/0.7 saving changes an outline used by Metrics. */
final class MetricRoiPersistence {

    private MetricRoiPersistence() {}

    static boolean isUnchangedBySave(ROI roi) {
        Class<?> type = roi.getClass();
        if (type == PolygonROI.class) {
            // Its serialization proxy retains the immutable float vertices and image plane.
            return true;
        }
        if (type == RectangleROI.class || type == EllipseROI.class) {
            // Their proxies reconstruct stored opposite corners through width/height subtraction.
            var points = roi.getAllPoints();
            return sameBits(roi.getBoundsX() + roi.getBoundsWidth(), points.get(1).getX())
                    && sameBits(roi.getBoundsY() + roi.getBoundsHeight(), points.get(2).getY());
        }
        if (type != GeometryROI.class) {
            // In particular, legacy AreaROI can expose a mutated transient geometry cache.
            return false;
        }
        // GeometryROI returns a private copy. Inspect it only for this call; retain no geometry.
        Geometry geometry = roi.getGeometry();
        if (!hasSavedMetadata(geometry)) {
            return false;
        }
        if (geometry.getClass() == Polygon.class) {
            return polygonIsUnchanged((Polygon) geometry);
        }
        if (geometry.getClass() != MultiPolygon.class) {
            return false;
        }
        for (int index = 0; index < geometry.getNumGeometries(); index++) {
            Geometry part = geometry.getGeometryN(index);
            if (part.getClass() != Polygon.class || !polygonIsUnchanged((Polygon) part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean polygonIsUnchanged(Polygon polygon) {
        if (!hasSavedMetadata(polygon) || !ringIsUnchanged(polygon.getExteriorRing())) {
            return false;
        }
        for (int index = 0; index < polygon.getNumInteriorRing(); index++) {
            if (!ringIsUnchanged(polygon.getInteriorRingN(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean ringIsUnchanged(Geometry ring) {
        if (ring.getClass() != LinearRing.class || !hasSavedMetadata(ring)) {
            return false;
        }
        CoordinateSequence points = ((LinearRing) ring).getCoordinateSequence();
        // The host writes WKB(2), then reads with its default FLOAT coordinate-sequence factory.
        if (points.getClass() != PackedCoordinateSequence.Float.class
                || points.getDimension() != 2
                || points.getMeasures() != 0) {
            return false;
        }
        var precision = GeometryTools.getDefaultFactory().getPrecisionModel();
        for (int point = 0; point < points.size(); point++) {
            for (int ordinate = 0; ordinate < 2; ordinate++) {
                double value = points.getOrdinate(point, ordinate);
                if (!Double.isFinite(value)
                        || !sameBits(value, (float) precision.makePrecise(value))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasSavedMetadata(Geometry geometry) {
        return geometry.getFactory() == GeometryTools.getDefaultFactory()
                && geometry.getSRID() == 0
                && geometry.getUserData() == null;
    }

    private static boolean sameBits(double left, double right) {
        return Double.doubleToRawLongBits(left) == Double.doubleToRawLongBits(right);
    }
}
