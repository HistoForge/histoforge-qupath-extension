package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes.AggregationType;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricInputDraft;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectType;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricType;

/** Canonical detached-input identity and metric-schema invariants. */
class MetricTypesTest {

    @Test
    void distributionReusesFiniteMembersAndNormalizesOnlyNegativeZero() {
        List<Double> values =
                List.of(
                        4.25,
                        0.0,
                        -0.0,
                        Double.MIN_VALUE,
                        -Double.MIN_VALUE,
                        Double.MAX_VALUE,
                        -Double.MAX_VALUE,
                        4.25);
        var result = new MetricTypes.Distribution(values).values();
        assertEquals(values.size(), result.size());
        for (int index = 0; index < values.size(); index++) {
            if (index == 2) {
                assertEquals(
                        Double.doubleToRawLongBits(0.0),
                        Double.doubleToRawLongBits(result.get(index)));
                assertNotSame(values.get(index), result.get(index));
            } else {
                assertSame(values.get(index), result.get(index));
                assertEquals(
                        Double.doubleToRawLongBits(values.get(index)),
                        Double.doubleToRawLongBits(result.get(index)));
            }
        }
    }

    @Test
    void distributionOwnsAnUnmodifiableListIncludingEmptyResults() {
        var values = new ArrayList<>(List.of(1.0, 2.0));
        var distribution = new MetricTypes.Distribution(values);
        values.set(0, 9.0);
        values.clear();
        assertEquals(List.of(1.0, 2.0), distribution.values());
        assertThrows(UnsupportedOperationException.class, () -> distribution.values().set(0, 3.0));
        assertThrows(UnsupportedOperationException.class, () -> distribution.values().clear());
        var empty = new MetricTypes.Distribution(List.of());
        assertEquals(List.of(), empty.values());
        assertThrows(UnsupportedOperationException.class, () -> empty.values().add(1.0));
    }

    @Test
    void distributionStillRejectsMissingAndNonfiniteMembers() {
        for (double invalid :
                new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new MetricTypes.Distribution(List.of(1.0, invalid)));
        }
        assertThrows(NullPointerException.class, () -> new MetricTypes.Distribution(null));
        assertThrows(
                NullPointerException.class,
                () -> new MetricTypes.Distribution(Arrays.asList(1.0, null)));
    }

    @Test
    void regionUsesSignedUuidOrderAndPreservesPublicPopulationValidation() {
        UUID parentId = new UUID(Long.MIN_VALUE, 1L);
        var parent =
                new MetricObjectSnapshot(
                        parentId,
                        MetricObjectType.DETECTION,
                        "Cell",
                        MetricTestData.square(0, 0, 10, 10),
                        Optional.empty());
        var child =
                new MetricObjectSnapshot(
                        MetricTestData.objectId(1),
                        MetricObjectType.DETECTION,
                        "Cell",
                        MetricTestData.square(1, 1, 2, 2),
                        Optional.of(parentId));
        var target = MetricTestData.target(MetricTestData.ROI_A);
        var supplied = new ArrayList<>(List.of(child, parent));
        var region = new MetricRegionSnapshot(target, supplied);
        supplied.clear();
        assertEquals(List.of(parent, child), region.objects());
        assertThrows(UnsupportedOperationException.class, () -> region.objects().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetricRegionSnapshot(target, List.of(child, parent, child)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetricRegionSnapshot(target, List.of(child)));
        assertThrows(
                NullPointerException.class,
                () -> new MetricRegionSnapshot(target, Arrays.asList(parent, null)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MetricObjectSnapshot(
                                parentId,
                                MetricObjectType.DETECTION,
                                " ",
                                MetricTestData.square(0, 0, 10, 10),
                                Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MetricObjectSnapshot(
                                parentId,
                                MetricObjectType.DETECTION,
                                "Cell",
                                MetricTestData.square(0, 0, 10, 10),
                                Optional.of(parentId)));
    }

    @Test
    void objectSnapshotOwnsItsGeometryAndDoesNotExposeMutableState() {
        var geometry = MetricTestData.square(10, 10, 30, 30);
        var expected = geometry.copy();
        var object =
                new MetricObjectSnapshot(
                        MetricTestData.objectId(1),
                        MetricObjectType.DETECTION,
                        "Cell",
                        geometry,
                        Optional.empty());

        geometry.apply((CoordinateFilter) coordinate -> coordinate.x += 100);
        geometry.geometryChanged();
        var exposed = object.geometrySlide();
        exposed.apply((CoordinateFilter) coordinate -> coordinate.y += 100);
        exposed.geometryChanged();

        assertEquals(expected, object.geometrySlide());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON EMPTY",
                "POLYGON ((0 0,8 8,8 0,0 8,0 0))",
                "POLYGON ((0 0,1 0,0 NaN,0 0))",
                "POLYGON Z ((0 0 1,1 0 1,0 1 1,0 0 1))",
                "POLYGON M ((0 0 1,1 0 1,0 1 1,0 0 1))"
            })
    void snapshotConstructionStillRejectsInvalidTopologyAndCoordinates(String shape)
            throws ParseException {
        var geometry = new WKTReader().read(shape);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MetricObjectSnapshot(
                                MetricTestData.objectId(1),
                                MetricObjectType.DETECTION,
                                "Cell",
                                geometry,
                                Optional.empty()));
    }

    @Test
    void snapshotConstructionRejectsEmptyHolesAllowedByJts() {
        Polygon shell = (Polygon) MetricTestData.square(0, 0, 8, 8);
        var factory = shell.getFactory();
        var geometry =
                factory.createPolygon(
                        shell.getExteriorRing(), new LinearRing[] {factory.createLinearRing()});

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MetricObjectSnapshot(
                                MetricTestData.objectId(1),
                                MetricObjectType.DETECTION,
                                "Cell",
                                geometry,
                                Optional.empty()));
    }

    @Test
    void inputDigestCoversScriptCalibrationClientAndOrderedRegionContent() {
        MetricDefinition count = scalar("count", AggregationType.SUM);
        MetricScriptSnapshot firstScript = MetricTestData.script("return [count: 1]", count);
        MetricObjectSnapshot parent =
                new MetricObjectSnapshot(
                        MetricTestData.objectId(1),
                        MetricObjectType.DETECTION,
                        "Parent",
                        MetricTestData.square(10, 10, 30, 30),
                        Optional.empty());
        MetricObjectSnapshot child =
                new MetricObjectSnapshot(
                        MetricTestData.objectId(2),
                        MetricObjectType.DETECTION,
                        "Child",
                        MetricTestData.square(15, 15, 16, 16),
                        Optional.of(parent.objectId()));
        MetricRegionSnapshot populated =
                new MetricRegionSnapshot(
                        MetricTestData.target(MetricTestData.ROI_A), List.of(child, parent));
        MetricInputDraft first =
                MetricInputDraft.build(
                        MetricTestData.clientBuild(10, 20),
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25),
                        MetricTestData.MODULE,
                        firstScript,
                        List.of(MetricTestData.region(MetricTestData.ROI_B), populated));
        MetricInputDraft reordered =
                MetricInputDraft.build(
                        MetricTestData.clientBuild(10, 20),
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25),
                        MetricTestData.MODULE,
                        firstScript,
                        List.of(populated, MetricTestData.region(MetricTestData.ROI_B)));
        MetricInputDraft changedScript =
                MetricInputDraft.build(
                        first.clientBuild(),
                        first.calibration(),
                        first.module(),
                        MetricTestData.script("return [count: 2]", count),
                        first.regions());
        MetricInputDraft changedCalibration =
                MetricInputDraft.build(
                        MetricTestData.clientBuild(10, 20),
                        new SourceIdentityCodec.PixelCalibration(0.5, 0.5),
                        MetricTestData.MODULE,
                        firstScript,
                        first.regions());
        MetricInputDraft changedClient =
                MetricInputDraft.build(
                        MetricTestData.clientBuild(11, 20),
                        first.calibration(),
                        first.module(),
                        first.script(),
                        first.regions());
        MetricObjectSnapshot detachedChild =
                new MetricObjectSnapshot(
                        child.objectId(),
                        child.objectType(),
                        child.pathClass(),
                        child.geometrySlide(),
                        Optional.empty());
        MetricInputDraft changedParent =
                MetricInputDraft.build(
                        first.clientBuild(),
                        first.calibration(),
                        first.module(),
                        first.script(),
                        List.of(
                                MetricTestData.region(MetricTestData.ROI_B),
                                new MetricRegionSnapshot(
                                        populated.target(), List.of(parent, detachedChild))));

        assertEquals(first.metricInputDigest(), reordered.metricInputDigest());
        assertEquals(
                first.metricInputDigest(),
                qupath.ext.histoforge.contract.CanonicalCodec.sha256(
                        MetricInputEncodingTest.encoded(first)));
        assertNotEquals(first.metricInputDigest(), changedScript.metricInputDigest());
        assertNotEquals(first.metricInputDigest(), changedCalibration.metricInputDigest());
        assertNotEquals(first.metricInputDigest(), changedClient.metricInputDigest());
        assertNotEquals(first.metricInputDigest(), changedParent.metricInputDigest());
        // The old JCS fingerprint is deliberately not reused by the binary v2 contract.
        assertNotEquals(
                "5eb93985301383b505b8dfaf99dfdab28d71d43bddf96b65c680d0f9511cb126",
                first.metricInputDigest());
    }

    @Test
    void suppliedDigestConstructionMatchesFactoryAndValueEquality() {
        MetricInputDraft expected = inputWithoutObjects();
        List<MetricRegionSnapshot> reordered = expected.regions().reversed();
        MetricInputDraft supplied =
                copyWithDigest(expected, expected.metricInputDigest(), reordered);
        MetricInputDraft derived =
                MetricInputDraft.build(
                        expected.clientBuild(),
                        expected.calibration(),
                        expected.module(),
                        expected.script(),
                        reordered);

        assertEquals(expected, supplied);
        assertEquals(expected, derived);
        assertEquals(expected.hashCode(), supplied.hashCode());
        assertEquals(expected.toString(), supplied.toString());
        assertArrayEquals(
                MetricInputEncodingTest.encoded(expected),
                MetricInputEncodingTest.encoded(supplied));
        assertNotEquals(expected, null);
        assertNotEquals(expected, expected.metricInputDigest());
    }

    @Test
    void suppliedDigestMustBeValidAndMatchTheCompleteInput() {
        MetricInputDraft input = inputWithoutObjects();
        for (String digest : Arrays.asList(null, "", "bad", "A".repeat(64), "0".repeat(64))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> copyWithDigest(input, digest, input.regions()));
        }
    }

    @Test
    void bothConstructionPathsRejectMissingRequiredFields() {
        MetricInputDraft input = inputWithoutObjects();
        List<Runnable> constructors =
                List.of(
                        () ->
                                MetricInputDraft.build(
                                        null,
                                        input.calibration(),
                                        input.module(),
                                        input.script(),
                                        input.regions()),
                        () ->
                                MetricInputDraft.build(
                                        input.clientBuild(),
                                        null,
                                        input.module(),
                                        input.script(),
                                        input.regions()),
                        () ->
                                MetricInputDraft.build(
                                        input.clientBuild(),
                                        input.calibration(),
                                        null,
                                        input.script(),
                                        input.regions()),
                        () ->
                                MetricInputDraft.build(
                                        input.clientBuild(),
                                        input.calibration(),
                                        input.module(),
                                        null,
                                        input.regions()),
                        () ->
                                new MetricInputDraft(
                                        input.metricInputDigest(),
                                        null,
                                        input.calibration(),
                                        input.module(),
                                        input.script(),
                                        input.regions()),
                        () ->
                                new MetricInputDraft(
                                        input.metricInputDigest(),
                                        input.clientBuild(),
                                        null,
                                        input.module(),
                                        input.script(),
                                        input.regions()),
                        () ->
                                new MetricInputDraft(
                                        input.metricInputDigest(),
                                        input.clientBuild(),
                                        input.calibration(),
                                        null,
                                        input.script(),
                                        input.regions()),
                        () ->
                                new MetricInputDraft(
                                        input.metricInputDigest(),
                                        input.clientBuild(),
                                        input.calibration(),
                                        input.module(),
                                        null,
                                        input.regions()));
        constructors.forEach(
                constructor -> assertThrows(NullPointerException.class, constructor::run));
    }

    @Test
    void bothConstructionPathsRejectInvalidRegionCollections() {
        MetricInputDraft input = inputWithoutObjects();
        MetricRegionSnapshot region = input.regions().getFirst();
        List<List<MetricRegionSnapshot>> invalidCollections =
                Arrays.asList(null, List.of(), List.of(region, region));
        for (List<MetricRegionSnapshot> regions : invalidCollections) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> copyWithDigest(input, input.metricInputDigest(), regions));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            MetricInputDraft.build(
                                    input.clientBuild(),
                                    input.calibration(),
                                    input.module(),
                                    input.script(),
                                    regions));
        }
        List<MetricRegionSnapshot> nullMember = Arrays.asList(region, null);
        assertThrows(
                NullPointerException.class,
                () -> copyWithDigest(input, input.metricInputDigest(), nullMember));
        assertThrows(
                NullPointerException.class,
                () ->
                        MetricInputDraft.build(
                                input.clientBuild(),
                                input.calibration(),
                                input.module(),
                                input.script(),
                                nullMember));
    }

    @Test
    void bothConstructionPathsCopyAndFreezeTheRegionList() {
        MetricInputDraft input = inputWithoutObjects();
        List<MetricRegionSnapshot> regions = new ArrayList<>(input.regions().reversed());
        MetricInputDraft supplied = copyWithDigest(input, input.metricInputDigest(), regions);
        MetricInputDraft derived =
                MetricInputDraft.build(
                        input.clientBuild(),
                        input.calibration(),
                        input.module(),
                        input.script(),
                        regions);
        regions.clear();

        assertEquals(input.regions(), supplied.regions());
        assertEquals(input.regions(), derived.regions());
        assertThrows(UnsupportedOperationException.class, () -> supplied.regions().clear());
        assertThrows(UnsupportedOperationException.class, () -> derived.regions().clear());
    }

    @Test
    void schemaRejectsDuplicateNamesAndIncompatibleAggregations() {
        MetricDefinition count = scalar("count", AggregationType.SUM);

        assertThrows(
                IllegalArgumentException.class,
                () -> new MetricTypes.MetricSchema(List.of(count, count)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MetricDefinition(
                                "bad",
                                "Bad",
                                MetricType.SCALAR,
                                Optional.empty(),
                                0,
                                AggregationType.POOL));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MetricDefinition(
                                "bad",
                                "Bad",
                                MetricType.DISTRIBUTION,
                                Optional.empty(),
                                0,
                                AggregationType.MEAN));
    }

    static MetricDefinition scalar(String name, AggregationType aggregation) {
        return new MetricDefinition(
                name, name, MetricType.SCALAR, Optional.empty(), 0, aggregation);
    }

    static MetricDefinition distribution(String name) {
        return new MetricDefinition(
                name, name, MetricType.DISTRIBUTION, Optional.empty(), 2, AggregationType.POOL);
    }

    private static MetricInputDraft inputWithoutObjects() {
        return MetricInputDraft.build(
                MetricTestData.clientBuild(10, 20),
                new SourceIdentityCodec.PixelCalibration(0.25, 0.25),
                MetricTestData.MODULE,
                MetricTestData.script("return [count: 1]", scalar("count", AggregationType.SUM)),
                List.of(
                        MetricTestData.region(MetricTestData.ROI_B),
                        MetricTestData.region(MetricTestData.ROI_A)));
    }

    private static MetricInputDraft copyWithDigest(
            MetricInputDraft input, String digest, List<MetricRegionSnapshot> regions) {
        return new MetricInputDraft(
                digest,
                input.clientBuild(),
                input.calibration(),
                input.module(),
                input.script(),
                regions);
    }
}
