package qupath.ext.histoforge.spatial;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import qupath.lib.io.PathIO;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryROI;
import qupath.lib.roi.GeometryTools;

/** Reconstructs WKB with the coordinate factory used by the loaded host's GeometryROI reader. */
final class GeometryRoiPersistence {
    private static GeometryFactory factory;

    private GeometryRoiPersistence() {}

    static Geometry materialize(Geometry geometry) {
        try {
            return new WKBReader(hostFactory()).read(new WKBWriter(2).write(geometry));
        } catch (ParseException error) {
            throw new IllegalStateException(
                    "QuPath could not reconstruct model geometry from its own WKB", error);
        }
    }

    private static synchronized GeometryFactory hostFactory() {
        if (factory == null) factory = readHostFactory();
        return factory;
    }

    /**
     * Probe once through public serialization instead of guessing from a release version or
     * serializing every prediction. The synthetic multipart outline selects GeometryROI. Stock
     * hosts return their fixed/float factory; corrected hosts return floating/double.
     */
    private static GeometryFactory readHostFactory() {
        var bytes = new ByteArrayOutputStream();
        try {
            Geometry probe =
                    new WKTReader()
                            .read(
                                    "MULTIPOLYGON (((0.123456789 0,1.123456789 0,"
                                            + "1.123456789 1,0.123456789 0)),"
                                            + "((120000.0011 170000.0012,120000.0031 170000.0012,"
                                            + "120000.0031 170000.0032,120000.0011 170000.0012)))");
            var plane = ImagePlane.getDefaultPlane();
            var roi = GeometryTools.geometryToROI(probe, plane);
            try (var output = new ObjectOutputStream(bytes)) {
                output.writeObject(roi);
            }
            try (var input =
                    PathIO.createObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                var restored = (GeometryROI) input.readObject();
                Geometry geometry = restored.getGeometry();
                GeometryFactory factory = geometry.getFactory();
                Geometry replay = new WKBReader(factory).read(new WKBWriter(2).write(probe));
                if (!restored.getImagePlane().equals(plane) || !geometry.equalsExact(replay)) {
                    throw new IllegalStateException(
                            "QuPath native geometry persistence differs from its WKB factory");
                }
                return factory;
            }
        } catch (IOException | ClassNotFoundException | ParseException error) {
            throw new IllegalStateException(
                    "QuPath could not determine its native geometry persistence factory", error);
        }
    }
}
