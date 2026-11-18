package qupath.ext.histoforge.spatial;

import java.util.ArrayList;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import qupath.lib.geom.Point2;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/** Dense rounded rectangle and an independent oracle reading only the native stored points. */
public final class PolygonOutlineFixture {
    private PolygonOutlineFixture() {}

    public static PolygonROI dense(double centerX, double centerY, double radiusX, double radiusY) {
        var points = new ArrayList<Point2>();
        for (int index = 0; index < 2400; index++) {
            double angle = 2 * Math.PI * index / 2400;
            double x = Math.cos(angle);
            double y = Math.sin(angle);
            points.add(
                    new Point2(
                            centerX + radiusX * Math.copySign(Math.sqrt(Math.abs(x)), x),
                            centerY + radiusY * Math.copySign(Math.sqrt(Math.abs(y)), y)));
        }
        return ROIs.createPolygonROI(points, ImagePlane.getDefaultPlane());
    }

    public static Polygon rawPolygon(ROI roi) {
        var points = roi.getAllPoints();
        boolean closed = points.getFirst().equals(points.getLast());
        var coordinates = new CoordinateXY[points.size() + (closed ? 0 : 1)];
        for (int index = 0; index < points.size(); index++) {
            var point = points.get(index);
            coordinates[index] = new CoordinateXY(point.getX(), point.getY());
        }
        if (!closed) coordinates[coordinates.length - 1] = new CoordinateXY(coordinates[0]);
        return new GeometryFactory().createPolygon(coordinates);
    }
}
