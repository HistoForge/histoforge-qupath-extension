package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.valid.IsValidOp;

/** Canonical Polygon/MultiPolygon coordinates and SHA-256 identities. */
public final class CanonicalGeometryCodec {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private CanonicalGeometryCodec() {}

    /** Validate native point collections without applying area or polygon precision rules. */
    public static void validatePointGeometry(Geometry geometry) {
        if (!(geometry instanceof Point || geometry instanceof MultiPoint) || geometry.isEmpty()) {
            throw new IllegalArgumentException("point geometry must contain finite 2D points");
        }
        for (int part = 0; part < geometry.getNumGeometries(); part++) {
            var points = ((Point) geometry.getGeometryN(part)).getCoordinateSequence();
            if (points.size() != 1
                    || !Double.isFinite(points.getX(0))
                    || !Double.isFinite(points.getY(0))
                    || (points.hasZ() && !Double.isNaN(points.getZ(0)))
                    || (points.hasM() && !Double.isNaN(points.getM(0)))) {
                throw new IllegalArgumentException("point geometry must contain finite 2D points");
            }
        }
    }

    /** The exact XY array of one point; no rounding or marker polygon is introduced. */
    public static JsonArray pointCoordinates(Point point) {
        validatePointGeometry(point);
        JsonArray coordinates = new JsonArray();
        coordinates.add(point.getX() == 0 ? 0.0 : point.getX());
        coordinates.add(point.getY() == 0 ? 0.0 : point.getY());
        return coordinates;
    }

    static Point parsePoint(JsonObject value) {
        if (value == null
                || !value.keySet().equals(Set.of("type", "coordinates"))
                || !"Point".equals(requiredString(value.get("type"), "geometry.type"))) {
            throw new IllegalArgumentException("geometry must contain one exact 2D Point");
        }
        JsonArray coordinates = requiredArray(value.get("coordinates"), "point coordinates");
        if (coordinates.size() != 2) throw new IllegalArgumentException("point must be exactly 2D");
        double[] xy = new double[2];
        for (int index = 0; index < 2; index++) {
            JsonElement ordinate = coordinates.get(index);
            if (!ordinate.isJsonPrimitive() || !ordinate.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("point coordinate must be numeric");
            }
            xy[index] =
                    CanonicalCodec.requireFiniteDouble(ordinate.getAsDouble(), "point coordinate");
        }
        return GEOMETRY_FACTORY.createPoint(new CoordinateXY(xy[0], xy[1]));
    }

    /** Parse one closed GeoJSON Polygon/MultiPolygon object into validated 2D JTS geometry. */
    public static Geometry parseGeoJson(JsonObject value) {
        return parseGeoJsonWithCoordinates(value).geometry();
    }

    /** Keep the normalized coordinates for callers that also check or identify the geometry. */
    static ParsedGeometry parseGeoJsonWithCoordinates(JsonObject value) {
        Geometry geometry = parsePolygonCoordinates(value);
        return new ParsedGeometry(geometry, canonicalCoordinates(geometry));
    }

    /**
     * Parse numeric polygon coordinates without topology or canonical-order validation. Callers
     * must perform those checks or verify these are the exact bytes of an already validated file.
     */
    static Geometry parsePolygonCoordinates(JsonObject value) {
        if (value == null || !value.keySet().equals(Set.of("type", "coordinates"))) {
            throw new IllegalArgumentException(
                    "geometry must contain exactly type and coordinates");
        }
        String type = requiredString(value.get("type"), "geometry.type");
        JsonArray coordinates = requiredArray(value.get("coordinates"), "geometry.coordinates");

        final Geometry geometry;
        try {
            if ("Polygon".equals(type)) {
                geometry = polygon(coordinates);
            } else if ("MultiPolygon".equals(type)) {
                Polygon[] polygons = new Polygon[coordinates.size()];
                for (int index = 0; index < coordinates.size(); index++) {
                    polygons[index] =
                            polygon(
                                    requiredArray(
                                            coordinates.get(index),
                                            "geometry.coordinates[" + index + "]"));
                }
                geometry = GEOMETRY_FACTORY.createMultiPolygon(polygons);
            } else {
                throw new IllegalArgumentException("geometry.type must be Polygon or MultiPolygon");
            }
        } catch (ClassCastException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("geometry coordinates are malformed", e);
        }
        return geometry;
    }

    /** Return the normalized RFC 7946 coordinate array for a valid 2D polygonal geometry. */
    public static JsonArray canonicalCoordinates(Geometry geometry) {
        validatePolygonalGeometry(geometry);
        return canonicalCoordinatesOfValidatedGeometry(geometry);
    }

    /**
     * Encode coordinates without repeating the polygon checks. Use only after {@link
     * #validatePolygonalGeometry} has checked this geometry and it has remained unchanged. JTS
     * {@code isValid()} alone does not check all required coordinate and ring rules.
     */
    public static JsonArray canonicalCoordinatesOfValidatedGeometry(Geometry geometry) {
        if (geometry instanceof Polygon polygon) {
            return canonicalPolygon(polygon);
        }

        MultiPolygon multiPolygon = (MultiPolygon) geometry;
        List<EncodedArray> polygons = new ArrayList<>();
        for (int index = 0; index < multiPolygon.getNumGeometries(); index++) {
            polygons.add(encoded(canonicalPolygon((Polygon) multiPolygon.getGeometryN(index))));
        }
        polygons.sort((left, right) -> Arrays.compareUnsigned(left.bytes(), right.bytes()));

        JsonArray result = new JsonArray();
        polygons.forEach(polygon -> result.add(polygon.value()));
        return result;
    }

    /** Return JCS bytes for only the canonical coordinate array. */
    public static byte[] canonicalBytes(Geometry geometry) {
        return CanonicalCodec.canonicalBytes(canonicalCoordinates(geometry));
    }

    /** Return lowercase SHA-256 over canonical coordinate-array bytes. */
    public static String canonicalDigest(Geometry geometry) {
        return CanonicalCodec.sha256(canonicalBytes(geometry));
    }

    /** Count canonical polygon vertices without each ring's repeated closing coordinate. */
    public static long vertexCount(Geometry geometry) {
        return vertexCount(geometry, canonicalCoordinates(geometry));
    }

    static long vertexCount(Geometry geometry, JsonArray coordinates) {
        if (geometry instanceof Polygon) {
            return polygonVertexCount(coordinates);
        }
        long total = 0;
        for (JsonElement polygon : coordinates) {
            total =
                    Math.addExact(
                            total, polygonVertexCount(requiredArray(polygon, "geometry polygon")));
        }
        return total;
    }

    /** Validate a nonempty, finite, valid 2D Polygon/MultiPolygon without encoding coordinates. */
    public static void validatePolygonalGeometry(Geometry geometry) {
        String error = polygonalGeometryError(geometry);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
    }

    /**
     * Apply the same complete checks as {@link #validatePolygonalGeometry} without throwing for
     * invalid geometry. Unexpected library failures still propagate.
     */
    public static boolean isValidPolygonalGeometry(Geometry geometry) {
        return polygonalGeometryError(geometry) == null;
    }

    private static String polygonalGeometryError(Geometry geometry) {
        if (!(geometry instanceof Polygon) && !(geometry instanceof MultiPolygon)) {
            return "geometry type must be Polygon or MultiPolygon";
        }
        if (geometry.isEmpty()) {
            return "geometry must be nonempty";
        }

        int count = geometry instanceof Polygon ? 1 : geometry.getNumGeometries();
        for (int index = 0; index < count; index++) {
            Polygon polygon =
                    geometry instanceof Polygon value
                            ? value
                            : (Polygon) geometry.getGeometryN(index);
            if (polygon.isEmpty()) {
                return "geometry must not contain an empty polygon";
            }
            String error = ringError(polygon.getExteriorRing());
            if (error != null) return error;
            for (int hole = 0; hole < polygon.getNumInteriorRing(); hole++) {
                error = ringError(polygon.getInteriorRingN(hole));
                if (error != null) return error;
            }
        }

        // Both IsValidOp query methods run validation; get the diagnostic in the same pass.
        var error = new IsValidOp(geometry).getValidationError();
        return error == null ? null : "invalid polygonal geometry: " + error;
    }

    private static Polygon polygon(JsonArray rings) {
        if (rings.isEmpty()) {
            return GEOMETRY_FACTORY.createPolygon();
        }
        LinearRing shell = ring(requiredArray(rings.get(0), "geometry shell"));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int index = 1; index < rings.size(); index++) {
            holes[index - 1] =
                    ring(requiredArray(rings.get(index), "geometry hole[" + (index - 1) + "]"));
        }
        return GEOMETRY_FACTORY.createPolygon(shell, holes);
    }

    private static LinearRing ring(JsonArray value) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (int index = 0; index < value.size(); index++) {
            coordinates.add(coordinate(requiredArray(value.get(index), "geometry coordinate")));
        }
        if (!coordinates.isEmpty()
                && !coordinates.get(0).equals2D(coordinates.get(coordinates.size() - 1))) {
            coordinates.add(coordinates.get(0).copy());
        }
        return GEOMETRY_FACTORY.createLinearRing(coordinates.toArray(Coordinate[]::new));
    }

    private static Coordinate coordinate(JsonArray value) {
        if (value.size() != 2) {
            throw new IllegalArgumentException("geometry coordinate must have exactly two values");
        }
        double x = requiredNumber(value.get(0), "geometry coordinate x");
        double y = requiredNumber(value.get(1), "geometry coordinate y");
        return new CoordinateXY(x == 0.0 ? 0.0 : x, y == 0.0 ? 0.0 : y);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }

    private static JsonArray requiredArray(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static double requiredNumber(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + " must be a number");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(path + " must be finite");
        }
        return result;
    }

    private static String ringError(LineString ring) {
        CoordinateSequence coordinates = ring.getCoordinateSequence();
        // JTS permits empty holes, but they cannot form a canonical polygon ring.
        if (coordinates.size() == 0) {
            return "ring must contain at least three distinct vertices";
        }
        for (int index = 0; index < coordinates.size(); index++) {
            double x = coordinates.getX(index);
            double y = coordinates.getY(index);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return "geometry coordinates must be finite";
            }
            if ((coordinates.hasZ() && !Double.isNaN(coordinates.getZ(index)))
                    || (coordinates.hasM() && !Double.isNaN(coordinates.getM(index)))) {
                return "geometry coordinates must be exactly 2D";
            }
        }
        return null;
    }

    private static JsonArray canonicalPolygon(Polygon polygon) {
        JsonArray shell = canonicalRing(polygon.getExteriorRing(), true);
        List<EncodedArray> holes = new ArrayList<>();
        for (int index = 0; index < polygon.getNumInteriorRing(); index++) {
            holes.add(encoded(canonicalRing(polygon.getInteriorRingN(index), false)));
        }
        holes.sort((left, right) -> Arrays.compareUnsigned(left.bytes(), right.bytes()));

        JsonArray result = new JsonArray();
        result.add(shell);
        holes.forEach(hole -> result.add(hole.value()));
        return result;
    }

    private static long polygonVertexCount(JsonArray rings) {
        long total = 0;
        for (JsonElement ring : rings) {
            JsonArray values = requiredArray(ring, "geometry ring");
            total = Math.addExact(total, values.size() - 1L);
        }
        return total;
    }

    private static JsonArray canonicalRing(LineString ring, boolean counterclockwise) {
        CoordinateSequence coordinates = ring.getCoordinateSequence();
        List<Coordinate2D> cycle = new ArrayList<>();
        for (int index = 0; index < coordinates.size(); index++) {
            Coordinate2D point =
                    new Coordinate2D(
                            normalizeZero(coordinates.getX(index)),
                            normalizeZero(coordinates.getY(index)));
            if (cycle.isEmpty() || !point.equals(cycle.get(cycle.size() - 1))) {
                cycle.add(point);
            }
        }
        if (cycle.size() > 1 && cycle.get(cycle.size() - 1).equals(cycle.get(0))) {
            cycle.remove(cycle.size() - 1);
        }
        if (cycle.size() < 3) {
            throw new IllegalArgumentException(
                    "ring must contain at least three distinct vertices");
        }

        if (isCounterclockwise(cycle) != counterclockwise) {
            Collections.reverse(cycle);
        }
        int start = leastRotation(cycle);

        JsonArray result = new JsonArray();
        for (int offset = 0; offset <= cycle.size(); offset++) {
            Coordinate2D point = cycle.get((start + offset) % cycle.size());
            JsonArray coordinate = new JsonArray();
            coordinate.add(point.x());
            coordinate.add(point.y());
            result.add(coordinate);
        }
        return result;
    }

    private static boolean isCounterclockwise(List<Coordinate2D> cycle) {
        Coordinate[] closed = new Coordinate[cycle.size() + 1];
        for (int index = 0; index < cycle.size(); index++) {
            Coordinate2D point = cycle.get(index);
            closed[index] = new CoordinateXY(point.x(), point.y());
        }
        closed[cycle.size()] = closed[0].copy();
        return Orientation.isCCW(closed);
    }

    private static int leastRotation(List<Coordinate2D> values) {
        int size = values.size();
        int left = 0;
        int right = 1;
        int offset = 0;
        while (left < size && right < size && offset < size) {
            Coordinate2D first = values.get((left + offset) % size);
            Coordinate2D second = values.get((right + offset) % size);
            int comparison = first.compareTo(second);
            if (comparison == 0) {
                offset++;
                continue;
            }
            if (comparison > 0) {
                left += offset + 1;
                if (left <= right) {
                    left = right + 1;
                }
            } else {
                right += offset + 1;
                if (right <= left) {
                    right = left + 1;
                }
            }
            offset = 0;
        }
        return Math.min(left, right);
    }

    private static double normalizeZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }

    private static EncodedArray encoded(JsonArray value) {
        return new EncodedArray(CanonicalCodec.canonicalBytes(value), value);
    }

    private record EncodedArray(byte[] bytes, JsonArray value) {}

    record ParsedGeometry(Geometry geometry, JsonArray coordinates) {}

    private record Coordinate2D(double x, double y) implements Comparable<Coordinate2D> {
        @Override
        public int compareTo(Coordinate2D other) {
            int xComparison = Double.compare(x, other.x);
            return xComparison != 0 ? xComparison : Double.compare(y, other.y);
        }
    }
}
