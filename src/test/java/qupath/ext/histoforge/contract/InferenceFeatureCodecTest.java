package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

/** Runs the shared schema-1 fixed inference-feature contract against JTS and QuPath classes. */
class InferenceFeatureCodecTest {

    @Test
    void sharedValidFixturesMatchExactBytesDigestsAndValidation() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        for (JsonElement item : fixture.getAsJsonArray("validCases")) {
            JsonObject testCase = item.getAsJsonObject();
            JsonObject collection =
                    CanonicalCodec.parseJson(testCase.get("canonical").getAsString())
                            .getAsJsonObject();
            InferenceFeatureCodec.FeatureCollection validated =
                    InferenceFeatureCodec.validate(
                            collection, strings(testCase, "declaredClasses"));
            byte[] encoded = CanonicalCodec.canonicalBytes(collection);

            assertEquals(
                    testCase.get("canonical").getAsString(),
                    new String(encoded, StandardCharsets.UTF_8),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    CanonicalCodec.sha256(encoded),
                    testCase.get("name").getAsString());
            assertEquals(
                    collection.getAsJsonArray("features").size(),
                    validated.features().size(),
                    testCase.get("name").getAsString());
            assertEquals(collection, InferenceFeatureCodec.toJson(validated));
            validated.features().forEach(InferenceFeatureCodecTest::assertSizeCounts);
        }
    }

    @Test
    void sharedInvalidFixturesFailClosed() throws IOException {
        for (JsonElement item : fixture().getAsJsonArray("invalidCases")) {
            JsonObject testCase = item.getAsJsonObject();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            InferenceFeatureCodec.validate(
                                    testCase.getAsJsonObject("featureCollection"),
                                    strings(testCase, "declaredClasses")),
                    testCase.get("name").getAsString());
        }
    }

    @Test
    void validEmptyCollectionIsSuccess() throws IOException {
        JsonObject testCase = fixture().getAsJsonArray("validCases").get(0).getAsJsonObject();
        JsonObject collection =
                CanonicalCodec.parseJson(testCase.get("canonical").getAsString()).getAsJsonObject();

        assertEquals(
                0,
                InferenceFeatureCodec.validate(collection, strings(testCase, "declaredClasses"))
                        .features()
                        .size());
    }

    @Test
    void escapedClassNamesAndDuplicateOccurrencesKeepTheirExactIdsAndSizes() throws Exception {
        Geometry geometry =
                new WKTReader()
                        .read("POLYGON ((0 0, 8 0, 8 8, 0 8, 0 0), (2 2, 2 4, 4 4, 4 2, 2 2))");
        String pathClass = "Cell é 🧪 \"quoted\" \\ subgroup";
        List<InferenceFeatureCodec.Feature> features = new ArrayList<>();
        for (int occurrence = 0; occurrence < 12; occurrence++) {
            JsonArray preimage = new JsonArray();
            preimage.add(pathClass);
            preimage.add(CanonicalGeometryCodec.canonicalCoordinates(geometry));
            preimage.add(occurrence);
            features.add(
                    new InferenceFeatureCodec.Feature(
                            CanonicalCodec.sha256(CanonicalCodec.canonicalBytes(preimage)),
                            geometry,
                            pathClass));
        }
        features.sort(Comparator.comparing(InferenceFeatureCodec.Feature::id));
        JsonObject value =
                InferenceFeatureCodec.toJson(new InferenceFeatureCodec.FeatureCollection(features));
        var result = InferenceFeatureCodec.validate(value, Set.of(pathClass));
        assertEquals(features, result.features());
        result.features().forEach(InferenceFeatureCodecTest::assertSizeCounts);

        // The input JSON is caller-owned, not retained alongside validated geometry.
        value.getAsJsonArray("features")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("geometry")
                .add("coordinates", new JsonArray());
        result.features().forEach(InferenceFeatureCodecTest::assertSizeCounts);
    }

    @Test
    void featureOwnsGeometryAndCountsCanonicalVerticesWithoutAdjacentDuplicates() throws Exception {
        Geometry source = new WKTReader().read("POLYGON ((0 0, 4 0, 4 0, 4 4, 0 4, 0 0))");
        var feature = new InferenceFeatureCodec.Feature("a".repeat(64), source, "Cell");
        var copy = new InferenceFeatureCodec.Feature(feature.id(), source, feature.pathClass());
        String before = CanonicalGeometryCodec.canonicalDigest(feature.geometry());
        assertEquals(4, InferenceFeatureCodec.vertexCount(feature));
        assertSizeCounts(feature);

        source.apply((CoordinateFilter) coordinate -> coordinate.x += 100);
        source.geometryChanged();
        Geometry returned = feature.geometry();
        returned.apply((CoordinateFilter) coordinate -> coordinate.y += 100);
        returned.geometryChanged();

        assertEquals(before, CanonicalGeometryCodec.canonicalDigest(feature.geometry()));
        assertEquals(copy, feature);
        assertEquals(copy.hashCode(), feature.hashCode());
        assertSizeCounts(feature);
        var rotated =
                new InferenceFeatureCodec.Feature(
                        feature.id(),
                        new WKTReader().read("POLYGON ((4 0, 4 4, 0 4, 0 0, 4 0))"),
                        feature.pathClass());
        assertNotEquals(feature, rotated); // Value equality remains structural, not topological.
    }

    private static void assertSizeCounts(InferenceFeatureCodec.Feature feature) {
        JsonElement encoded =
                InferenceFeatureCodec.toJson(
                                new InferenceFeatureCodec.FeatureCollection(List.of(feature)))
                        .getAsJsonArray("features")
                        .get(0);
        assertEquals(
                CanonicalCodec.canonicalBytes(encoded).length,
                InferenceFeatureCodec.canonicalRecordBytes(feature));
        assertEquals(
                CanonicalGeometryCodec.vertexCount(feature.geometry()),
                InferenceFeatureCodec.vertexCount(feature));
    }

    private static Set<String> strings(JsonObject value, String field) {
        Set<String> result = new HashSet<>();
        for (JsonElement item : value.getAsJsonArray(field)) {
            result.add(item.getAsString());
        }
        return result;
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                InferenceFeatureCodecTest.class.getResourceAsStream(
                        "/contracts/inference-features-v1.json")) {
            if (stream == null) {
                throw new IOException("missing inference-feature fixture");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
