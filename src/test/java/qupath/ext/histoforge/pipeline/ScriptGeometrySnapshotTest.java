package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;

class ScriptGeometrySnapshotTest {

    private static final String SQUARE = "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0))";

    @ParameterizedTest
    @ValueSource(
            strings = {
                SQUARE,
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (2 2, 2 4, 4 4, 4 2, 2 2))",
                "MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0)), ((20 0, 30 0, 30 10, 20 10, 20 0)))"
            })
    void unchangedCoordinatesDoNotRebuildTheBaseline(String wkt) throws ParseException {
        Geometry before = read(wkt);
        var snapshot = ScriptGeometrySnapshot.capture(before);
        assertTrue(
                snapshot.matches(
                        before.copy(),
                        () -> {
                            throw new AssertionError(
                                    "unchanged geometry must not rebuild or canonicalize");
                        }));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON ((10 10, 0 10, 0 0, 10 0, 10 10))",
                "POLYGON ((0 0, 0 10, 10 10, 10 0, 0 0))",
                "POLYGON ((0 0, 0 0, 10 0, 10 10, 0 10, 0 0))",
                "POLYGON ((-0.0 0, 10 0, 10 10, 0 10, -0.0 0))"
            })
    void fallbackPreservesCanonicalEquivalence(String wkt) throws ParseException {
        Geometry before = read(SQUARE);
        Geometry after = read(wkt);
        assertTrue(
                CanonicalGeometryCodec.canonicalDigest(before)
                        .equals(CanonicalGeometryCodec.canonicalDigest(after)));
        assertTrue(ScriptGeometrySnapshot.capture(before).matches(after, before::copy));
    }

    @Test
    void reorderedHolesAndPolygonsKeepExistingEquivalence() throws ParseException {
        Geometry before =
                read(
                        "MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0), (1 1, 1 2, 2 2, 2 1, 1 1), (3 3, 3 4, 4 4, 4 3, 3 3)), ((20 0, 30 0, 30 10, 20 10, 20 0)))");
        Geometry after =
                read(
                        "MULTIPOLYGON (((20 0, 30 0, 30 10, 20 10, 20 0)), ((0 0, 10 0, 10 10, 0 10, 0 0), (3 3, 3 4, 4 4, 4 3, 3 3), (1 1, 1 2, 2 2, 2 1, 1 1)))");
        assertTrue(ScriptGeometrySnapshot.capture(before).matches(after, before::copy));
    }

    @Test
    void changedSourceCannotBecomeTheNewBaseline() throws ParseException {
        Geometry before = read(SQUARE);
        var snapshot = ScriptGeometrySnapshot.capture(before);
        Geometry changed = read("POLYGON ((0 0, 11 0, 10 10, 0 10, 0 0))");
        assertFalse(snapshot.matches(changed, changed::copy));
        assertFalse(snapshot.matches(changed, before::copy));
    }

    @Test
    void polygonTypeAndRingStructureArePartOfTheFingerprint() throws ParseException {
        Geometry before = read(SQUARE);
        Geometry multi =
                before.getFactory().createMultiPolygon(new Polygon[] {(Polygon) before.copy()});
        assertFalse(ScriptGeometrySnapshot.capture(before).matches(multi, before::copy));
        Geometry withHole =
                read("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (2 2, 2 4, 4 4, 4 2, 2 2))");
        assertFalse(ScriptGeometrySnapshot.capture(before).matches(withHole, before::copy));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON ((0 0, 10 10, 10 0, 0 10, 0 0))",
                "POLYGON EMPTY",
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), EMPTY)"
            })
    void invalidTopologyNeverPassesCaptureOrFallback(String wkt) throws ParseException {
        Geometry before = read(SQUARE);
        Geometry invalid = read(wkt);
        assertThrows(IllegalArgumentException.class, () -> ScriptGeometrySnapshot.capture(invalid));
        var snapshot = ScriptGeometrySnapshot.capture(before);
        assertThrows(IllegalArgumentException.class, () -> snapshot.matches(invalid, before::copy));
    }

    @Test
    void rejectsNonfiniteXYAndAnyPresentZOrM() throws ParseException {
        for (String dimension : new String[] {"Z", "M"}) {
            Geometry before =
                    read(
                            "POLYGON "
                                    + dimension
                                    + " ((0 0 NaN, 10 0 NaN, 10 10 NaN, 0 10 NaN, 0 0 NaN))");
            var snapshot = ScriptGeometrySnapshot.capture(before);
            for (double forbidden :
                    new double[] {0, 1, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                Geometry after = before.copy();
                CoordinateSequence points =
                        ((Polygon) after).getExteriorRing().getCoordinateSequence();
                points.setOrdinate(1, 2, forbidden);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> snapshot.matches(after, before::copy));
            }
            for (double forbidden :
                    new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                for (int axis = 0; axis < 2; axis++) {
                    Geometry after = before.copy();
                    ((Polygon) after)
                            .getExteriorRing()
                            .getCoordinateSequence()
                            .setOrdinate(1, axis, forbidden);
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> snapshot.matches(after, before::copy));
                }
            }
        }
    }

    private static Geometry read(String wkt) throws ParseException {
        return new WKTReader().read(wkt);
    }
}
