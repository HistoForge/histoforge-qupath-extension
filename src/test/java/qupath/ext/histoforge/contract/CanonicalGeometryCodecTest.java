package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.CoordinateXYM;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.operation.valid.TopologyValidationError;

/** Runs the shared schema-1 canonical Polygon/MultiPolygon contract against JTS. */
class CanonicalGeometryCodecTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void sharedFixturesMatchExactBytesAndDigests() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        for (JsonElement item : fixture.getAsJsonArray("validCases")) {
            JsonObject testCase = item.getAsJsonObject();
            Geometry geometry = geometry(testCase.getAsJsonObject("geometry"));
            CanonicalGeometryCodec.validatePolygonalGeometry(geometry);
            assertTrue(CanonicalGeometryCodec.isValidPolygonalGeometry(geometry));
            byte[] encoded = CanonicalGeometryCodec.canonicalBytes(geometry);
            assertArrayEquals(
                    encoded,
                    CanonicalCodec.canonicalBytes(
                            CanonicalGeometryCodec.canonicalCoordinatesOfValidatedGeometry(
                                    geometry)),
                    testCase.get("name").getAsString());

            assertEquals(
                    testCase.get("canonical").getAsString(),
                    new String(encoded, StandardCharsets.UTF_8),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    CanonicalGeometryCodec.canonicalDigest(geometry),
                    testCase.get("name").getAsString());
        }
    }

    @Test
    void sharedInvalidFixturesFailClosed() throws IOException {
        for (JsonElement item : fixture().getAsJsonArray("invalidCases")) {
            JsonObject testCase = item.getAsJsonObject();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            CanonicalGeometryCodec.validatePolygonalGeometry(
                                    geometry(testCase.getAsJsonObject("geometry"))),
                    testCase.get("name").getAsString());
            assertThrows(
                    RuntimeException.class,
                    () ->
                            CanonicalGeometryCodec.canonicalCoordinates(
                                    geometry(testCase.getAsJsonObject("geometry"))),
                    testCase.get("name").getAsString());
        }
    }

    @Test
    void rejectsNonfiniteAndPresentThirdDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CanonicalGeometryCodec.canonicalCoordinates(
                                polygon(
                                        new Coordinate(0, 0),
                                        new Coordinate(1, 0),
                                        new Coordinate(0, Double.NaN),
                                        new Coordinate(0, 0))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CanonicalGeometryCodec.canonicalCoordinates(
                                polygon(
                                        new Coordinate(0, 0, 1),
                                        new Coordinate(1, 0, 1),
                                        new Coordinate(0, 1, 1),
                                        new Coordinate(0, 0, 1))));
    }

    @Test
    void acceptsOrdinaryJtsCoordinatesWhoseAbsentZIsNan() {
        Geometry geometry =
                polygon(
                        new Coordinate(0, 0),
                        new Coordinate(1, 0),
                        new Coordinate(0, 1),
                        new Coordinate(0, 0));
        assertEquals("[[[0,0],[1,0],[0,1],[0,0]]]", utf8(geometry));
    }

    @Test
    void validationOnlyRejectsInvalidCoordinatesAndDegenerateRings() {
        List<Geometry> invalid =
                List.of(
                        GEOMETRY_FACTORY.createPolygon(),
                        polygon(
                                new Coordinate(0, 0), new Coordinate(1, 0),
                                new Coordinate(0, Double.NaN), new Coordinate(0, 0)),
                        polygon(
                                new Coordinate(0, 0), new Coordinate(1, 0),
                                new Coordinate(Double.POSITIVE_INFINITY, 1), new Coordinate(0, 0)),
                        polygon(
                                new Coordinate(0, 0, 1), new Coordinate(1, 0, 1),
                                new Coordinate(0, 1, 1), new Coordinate(0, 0, 1)),
                        polygon(
                                new CoordinateXYM(0, 0, 1), new CoordinateXYM(1, 0, 1),
                                new CoordinateXYM(0, 1, 1), new CoordinateXYM(0, 0, 1)),
                        polygon(
                                new Coordinate(0, 0), new Coordinate(1, 0),
                                new Coordinate(1, 0), new Coordinate(0, 0)));
        for (Geometry geometry : invalid) {
            assertFalse(CanonicalGeometryCodec.isValidPolygonalGeometry(geometry));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CanonicalGeometryCodec.validatePolygonalGeometry(geometry));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CanonicalGeometryCodec.canonicalCoordinates(geometry));
        }
    }

    @Test
    void rejectsEmptyHolesEvenThoughJtsAllowsThem() {
        Polygon shell =
                polygon(
                        new Coordinate(0, 0),
                        new Coordinate(4, 0),
                        new Coordinate(0, 4),
                        new Coordinate(0, 0));
        Polygon geometry =
                GEOMETRY_FACTORY.createPolygon(
                        shell.getExteriorRing(),
                        new LinearRing[] {GEOMETRY_FACTORY.createLinearRing()});
        assertTrue(geometry.isValid());
        assertFalse(CanonicalGeometryCodec.isValidPolygonalGeometry(geometry));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalGeometryCodec.canonicalCoordinates(geometry));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalGeometryCodec.validatePolygonalGeometry(geometry));
    }

    @Test
    void invalidTopologyUsesOneDiagnosticPass() {
        var geometry =
                polygon(
                        new Coordinate(0, 0),
                        new Coordinate(8, 8),
                        new Coordinate(8, 0),
                        new Coordinate(0, 8),
                        new Coordinate(0, 0));
        var diagnostic =
                new TopologyValidationError(
                        TopologyValidationError.SELF_INTERSECTION, new Coordinate(4, 4));
        try (var checks =
                mockConstruction(
                        IsValidOp.class,
                        (check, context) ->
                                when(check.getValidationError()).thenReturn(diagnostic))) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> CanonicalGeometryCodec.validatePolygonalGeometry(geometry));
            assertEquals("invalid polygonal geometry: " + diagnostic, error.getMessage());
            assertEquals(1, checks.constructed().size());
            verify(checks.constructed().getFirst()).getValidationError();
            verify(checks.constructed().getFirst(), never()).isValid();
        }
    }

    @Test
    void predicateRejectsWrongTypeAndDoesNotHideLibraryFailures() {
        assertFalse(CanonicalGeometryCodec.isValidPolygonalGeometry(null));
        assertFalse(
                CanonicalGeometryCodec.isValidPolygonalGeometry(
                        GEOMETRY_FACTORY.createPoint(new Coordinate(1, 2))));
        var geometry =
                spy(
                        polygon(
                                new Coordinate(0, 0),
                                new Coordinate(8, 0),
                                new Coordinate(0, 8),
                                new Coordinate(0, 0)));
        var failure = new IllegalArgumentException("ring read failed");
        doThrow(failure).when(geometry).getExteriorRing();
        assertSame(
                failure,
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CanonicalGeometryCodec.isValidPolygonalGeometry(geometry)));
        assertSame(
                failure,
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CanonicalGeometryCodec.validatePolygonalGeometry(geometry)));
    }

    private static String utf8(Geometry geometry) {
        return new String(CanonicalGeometryCodec.canonicalBytes(geometry), StandardCharsets.UTF_8);
    }

    private static Polygon polygon(Coordinate... coordinates) {
        return GEOMETRY_FACTORY.createPolygon(coordinates);
    }

    private static Geometry geometry(JsonObject value) {
        if (value == null || !value.has("type")) {
            throw new IllegalArgumentException("geometry type is required");
        }
        String type = value.get("type").getAsString();
        if ("Point".equals(type)) {
            return GEOMETRY_FACTORY.createPoint(coordinate(requiredArray(value, "coordinates")));
        }
        if ("Polygon".equals(type)) {
            return polygon(requiredArray(value, "coordinates"));
        }
        if ("MultiPolygon".equals(type)) {
            JsonArray values = requiredArray(value, "coordinates");
            Polygon[] polygons = new Polygon[values.size()];
            for (int index = 0; index < values.size(); index++) {
                polygons[index] = polygon(values.get(index).getAsJsonArray());
            }
            return GEOMETRY_FACTORY.createMultiPolygon(polygons);
        }
        throw new IllegalArgumentException("unsupported fixture geometry type: " + type);
    }

    private static Polygon polygon(JsonArray value) {
        if (value.isEmpty()) {
            return GEOMETRY_FACTORY.createPolygon();
        }
        LinearRing shell = ring(value.get(0).getAsJsonArray());
        LinearRing[] holes = new LinearRing[value.size() - 1];
        for (int index = 1; index < value.size(); index++) {
            holes[index - 1] = ring(value.get(index).getAsJsonArray());
        }
        return GEOMETRY_FACTORY.createPolygon(shell, holes);
    }

    private static LinearRing ring(JsonArray value) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (JsonElement element : value) {
            coordinates.add(coordinate(element.getAsJsonArray()));
        }
        if (!coordinates.isEmpty()
                && !coordinates.get(0).equals2D(coordinates.get(coordinates.size() - 1))) {
            coordinates.add(coordinates.get(0).copy());
        }
        return GEOMETRY_FACTORY.createLinearRing(coordinates.toArray(Coordinate[]::new));
    }

    private static Coordinate coordinate(JsonArray value) {
        if (value.size() != 2
                || !value.get(0).isJsonPrimitive()
                || !value.get(0).getAsJsonPrimitive().isNumber()
                || !value.get(1).isJsonPrimitive()
                || !value.get(1).getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("fixture coordinate must contain two numbers");
        }
        double x = value.get(0).getAsDouble();
        double y = value.get(1).getAsDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("fixture coordinates must be finite");
        }
        return new CoordinateXY(x, y);
    }

    private static JsonArray requiredArray(JsonObject value, String field) {
        if (!value.has(field) || !value.get(field).isJsonArray()) {
            throw new IllegalArgumentException("geometry " + field + " must be an array");
        }
        return value.getAsJsonArray(field);
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                CanonicalGeometryCodecTest.class.getResourceAsStream(
                        "/contracts/canonical-geometry-v1.json")) {
            if (stream == null) {
                throw new IOException("missing canonical geometry fixture");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
