package qupath.ext.histoforge.pipeline;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;

/** Checks one script copy's coordinates without retaining another complete geometry. */
final class ScriptGeometrySnapshot {

    private final byte[] coordinatesBefore;

    private ScriptGeometrySnapshot(byte[] coordinatesBefore) {
        this.coordinatesBefore = coordinatesBefore;
    }

    static ScriptGeometrySnapshot capture(Geometry geometry) {
        if (isPoint(geometry)) {
            CanonicalGeometryCodec.validatePointGeometry(geometry);
        } else {
            CanonicalGeometryCodec.validatePolygonalGeometry(geometry);
        }
        return new ScriptGeometrySnapshot(orderedCoordinatesDigest(geometry));
    }

    /**
     * The baseline supplier recreates the initial script copy from the unexposed source ROI. It is
     * needed only when coordinates differ; ordinary class changes take the fast path.
     */
    boolean matches(Geometry current, Supplier<Geometry> recreateBaseline) {
        if (MessageDigest.isEqual(coordinatesBefore, orderedCoordinatesDigest(current))) {
            return true;
        }
        Geometry baseline = recreateBaseline.get();
        if (!MessageDigest.isEqual(coordinatesBefore, orderedCoordinatesDigest(baseline))) {
            return false;
        }
        if (isPoint(baseline) || isPoint(current)) {
            return false;
        }
        // Ring rotation, reversal and repeated vertices can preserve the original outline.
        return CanonicalGeometryCodec.canonicalDigest(baseline)
                .equals(CanonicalGeometryCodec.canonicalDigest(current));
    }

    /**
     * Temporary, order-sensitive fingerprint, not a persisted geometry identity. Counts separate
     * polygons and rings; every XY value is covered. Equal content preserves the topology already
     * validated at capture. Z/M must remain absent even when native ROI serialization omits them.
     */
    private static byte[] orderedCoordinatesDigest(Geometry geometry) {
        if (!(geometry instanceof Polygon
                || geometry instanceof MultiPolygon
                || isPoint(geometry))) {
            throw new IllegalArgumentException("script geometry must be polygonal or point-based");
        }
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
        ByteBuffer number = ByteBuffer.allocate(2 * Double.BYTES);
        digest.update(
                (byte)
                        (geometry instanceof Polygon
                                ? 1
                                : geometry instanceof MultiPolygon
                                        ? 2
                                        : geometry instanceof Point ? 3 : 4));
        updateCount(digest, number, geometry.getNumGeometries());
        for (int index = 0; index < geometry.getNumGeometries(); index++) {
            if (isPoint(geometry)) {
                updateCoordinates(
                        digest,
                        number,
                        ((Point) geometry.getGeometryN(index)).getCoordinateSequence());
                continue;
            }
            Polygon polygon = (Polygon) geometry.getGeometryN(index);
            updateCount(digest, number, polygon.getNumInteriorRing());
            updateRing(digest, number, polygon.getExteriorRing());
            for (int hole = 0; hole < polygon.getNumInteriorRing(); hole++) {
                updateRing(digest, number, polygon.getInteriorRingN(hole));
            }
        }
        return digest.digest();
    }

    private static void updateRing(MessageDigest digest, ByteBuffer number, LineString ring) {
        updateCoordinates(digest, number, ring.getCoordinateSequence());
    }

    private static boolean isPoint(Geometry geometry) {
        return geometry instanceof Point || geometry instanceof MultiPoint;
    }

    private static void updateCoordinates(
            MessageDigest digest, ByteBuffer number, CoordinateSequence coordinates) {
        updateCount(digest, number, coordinates.size());
        for (int index = 0; index < coordinates.size(); index++) {
            double x = coordinates.getX(index);
            double y = coordinates.getY(index);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("script geometry coordinates must be finite");
            }
            if ((coordinates.hasZ() && !Double.isNaN(coordinates.getZ(index)))
                    || (coordinates.hasM() && !Double.isNaN(coordinates.getM(index)))) {
                throw new IllegalArgumentException(
                        "script geometry coordinates must be exactly 2D");
            }
            number.clear();
            number.putDouble(x).putDouble(y);
            digest.update(number.array());
        }
    }

    private static void updateCount(MessageDigest digest, ByteBuffer number, int count) {
        number.clear();
        number.putInt(count);
        digest.update(number.array(), 0, Integer.BYTES);
    }
}
