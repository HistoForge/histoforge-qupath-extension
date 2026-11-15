package qupath.ext.histoforge.spatial;

import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.lib.geom.Point2;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.interfaces.ROI;

/** Converts native outlines for HistoForge-owned spatial operations without altering the ROI. */
public final class NativeRoiGeometry {

    private static final GeometryFactory FACTORY =
            new GeometryFactory(
                    new PrecisionModel(), 0, PackedCoordinateSequenceFactory.DOUBLE_FACTORY);

    private NativeRoiGeometry() {}

    /**
     * Read a valid native polygon directly from its stored image-pixel coordinates.
     *
     * <p>QuPath 0.6/0.7 routes PolygonROI conversion through AWT Area. On Java 25, Area can split a
     * large outline into adjoining bands that QuPath's polygonizer does not fully retain. A simple,
     * valid polygon needs no such reconstruction: its native points define the boundary. Keep their
     * exact values, including fractional coordinates, in a fresh two-dimensional JTS polygon; no
     * native shape, cache, or hierarchy is changed.
     *
     * <p>Self-crossing outlines deliberately keep QuPath's existing filled-area interpretation.
     * Other ROI types also retain their public conversion, including holes and multipart shapes.
     * Callers still apply their existing area and canonical-geometry admission checks. This fixes
     * HistoForge's reads, not third-party scripts that call ROI.getGeometry() directly.
     */
    public static Geometry convert(ROI roi) {
        Polygon polygon = nativePolygon(roi);
        return polygon != null && polygon.isValid() ? polygon : roi.getGeometry();
    }

    /**
     * Return a private, completely validated 2D Polygon/MultiPolygon for strict spatial reads. A
     * valid native polygon is constructed and checked once. Invalid native outlines retain the
     * host's filled-area interpretation; that different geometry is copied and checked separately.
     * Other ROI implementations may expose mutable geometry, so they also require a copy. The
     * caller still applies any positive-area requirement.
     */
    public static Geometry convertValidatedPolygonal(ROI roi) {
        Polygon polygon = nativePolygon(roi);
        if (polygon != null && CanonicalGeometryCodec.isValidPolygonalGeometry(polygon)) {
            return polygon;
        }
        Geometry geometry = roi.getGeometry().copy();
        CanonicalGeometryCodec.validatePolygonalGeometry(geometry);
        return geometry;
    }

    private static Polygon nativePolygon(ROI roi) {
        if (roi.getClass() != PolygonROI.class) {
            return null;
        }
        List<Point2> points = roi.getAllPoints();
        if (points.size() < 3) {
            return null;
        }
        Point2 first = points.getFirst();
        Point2 last = points.getLast();
        boolean closed = first.getX() == last.getX() && first.getY() == last.getY();
        var coordinates =
                FACTORY.getCoordinateSequenceFactory().create(points.size() + (closed ? 0 : 1), 2);
        for (int index = 0; index < coordinates.size(); index++) {
            Point2 point = points.get(index % points.size());
            coordinates.setOrdinate(index, 0, point.getX());
            coordinates.setOrdinate(index, 1, point.getY());
        }
        // A three-point closed outline cannot form a polygon; retain the host's behavior.
        if (coordinates.size() < 4) {
            return null;
        }
        return FACTORY.createPolygon(coordinates);
    }
}
