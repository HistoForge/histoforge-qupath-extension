package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;

/** Independent reader for backend-produced scored-point bytes and identities. */
class InferencePointFeatureCodecTest {
    @Test
    void pointBoundsAreHalfOpenWhilePolygonBoundsRemainClosed() {
        var raster =
                new RasterInputCodec.RasterDescriptor(
                        10,
                        20,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        1,
                        RasterInputCodec.ResamplingType.BILINEAR,
                        RasterInputCodec.RasterPixelFormat.SRGB_UINT8);
        for (var bounds :
                List.of(
                        new org.locationtech.jts.geom.Envelope(0, 10, 0, 1),
                        new org.locationtech.jts.geom.Envelope(0, 1, 0, 20))) {
            var points =
                    new FeatureStreamDecoder.ValidatedFeatureStream(
                            100, 1, 1, 1000, bounds, InferenceFeatureCodec.GeometryKind.POINT);
            assertThrows(IllegalArgumentException.class, () -> points.requireInsideRaster(raster));
            new FeatureStreamDecoder.ValidatedFeatureStream(
                            100, 1, 4, 1000, bounds, InferenceFeatureCodec.GeometryKind.POLYGONAL)
                    .requireInsideRaster(raster);
        }
        new FeatureStreamDecoder.ValidatedFeatureStream(
                        100,
                        1,
                        1,
                        1000,
                        new org.locationtech.jts.geom.Envelope(
                                0, Math.nextDown(10.0), 0, Math.nextDown(20.0)),
                        InferenceFeatureCodec.GeometryKind.POINT)
                .requireInsideRaster(raster);
    }

    @Test
    void sharedPointFixturesKeepScoresIdsMultiplicityAndExactByteBudgets() throws Exception {
        JsonObject fixture;
        try (var input =
                getClass().getResourceAsStream("/contracts/inference-point-features-v1.json")) {
            assertNotNull(input);
            fixture = CanonicalCodec.parseJson(input.readAllBytes()).getAsJsonObject();
        }
        Set<String> classes =
                fixture.getAsJsonArray("declaredClasses").asList().stream()
                        .map(element -> element.getAsString())
                        .collect(Collectors.toSet());
        for (var item : fixture.getAsJsonArray("cases")) {
            var testCase = item.getAsJsonObject();
            var collection =
                    CanonicalCodec.parseJson(testCase.get("collectionCanonical").getAsString())
                            .getAsJsonObject();
            var parsed =
                    InferenceFeatureCodec.validate(
                            collection, classes, InferenceFeatureCodec.GeometryKind.POINT);
            assertEquals(
                    testCase.get("collectionSha256").getAsString(),
                    CanonicalCodec.sha256(
                            CanonicalCodec.canonicalBytes(InferenceFeatureCodec.toJson(parsed))));
            assertEquals(collection, InferenceFeatureCodec.toJson(parsed));
            for (var feature : parsed.features()) {
                assertInstanceOf(Point.class, feature.geometry());
                assertTrue(feature.score().isPresent());
                assertEquals(1, InferenceFeatureCodec.vertexCount(feature));
                var single =
                        InferenceFeatureCodec.toJson(
                                        new InferenceFeatureCodec.FeatureCollection(
                                                List.of(feature)))
                                .getAsJsonArray("features")
                                .get(0);
                assertEquals(
                        CanonicalCodec.canonicalBytes(single).length,
                        InferenceFeatureCodec.canonicalRecordBytes(feature));
            }
            byte[] compressed =
                    Base64.getDecoder().decode(testCase.get("gzipBase64").getAsString());
            assertEquals(
                    testCase.get("streamSha256").getAsString(), CanonicalCodec.sha256(compressed));
            long count = parsed.features().size();
            long[] ceilings = {
                compressed.length,
                Math.max(1, testCase.get("decodedBytes").getAsLong()),
                testCase.get("maxRecordBytes").getAsLong(),
                Math.max(1, count),
                Math.max(1, count)
            };
            var validated = validate(compressed, classes, count, ceilings);
            var read = new ArrayList<InferenceFeatureCodec.Feature>();
            FeatureStreamDecoder.readValidated(
                    new ByteArrayInputStream(compressed), validated, read::add);
            assertEquals(parsed.features(), read, "retained-file reader must preserve scores");
            if (count == 0) continue;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> InferenceFeatureCodec.validate(collection, classes));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            FeatureStreamDecoder.validate(
                                    new ByteArrayInputStream(compressed),
                                    budget(ceilings),
                                    classes,
                                    count,
                                    count));
            // Compressed length/SHA are checked by the artifact transport before this decoder.
            for (int field = 1; field < ceilings.length; field++) {
                long[] reduced = ceilings.clone();
                reduced[field]--;
                assertThrows(
                        IllegalArgumentException.class,
                        () -> validate(compressed, classes, count, reduced));
            }
        }
    }

    @Test
    void pointSchemaRejectsMissingNonfiniteNonFloat32AndTamperedScores() {
        var value = point();
        var classes = Set.of("Mitotic figure");
        assertEquals(
                0.0,
                InferenceFeatureCodec.validate(
                                value, classes, InferenceFeatureCodec.GeometryKind.POINT)
                        .features()
                        .getFirst()
                        .score()
                        .orElseThrow());
        for (String literal : List.of("null", "true", "\"0.5\"", "0.9", "-0.5", "1.5", "0.5")) {
            var changed = value.deepCopy();
            changed.getAsJsonArray("features")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("properties")
                    .add("score", CanonicalCodec.parseJson(literal));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            InferenceFeatureCodec.validate(
                                    changed, classes, InferenceFeatureCodec.GeometryKind.POINT));
        }
        var missing = value.deepCopy();
        missing.getAsJsonArray("features")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("properties")
                .remove("score");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        InferenceFeatureCodec.validate(
                                missing, classes, InferenceFeatureCodec.GeometryKind.POINT));
        for (String coordinates : List.of("[]", "[1,2,3]", "[true,2]", "[[1,2]]")) {
            var changed = value.deepCopy();
            changed.getAsJsonArray("features")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("geometry")
                    .add("coordinates", CanonicalCodec.parseJson(coordinates));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            InferenceFeatureCodec.validate(
                                    changed, classes, InferenceFeatureCodec.GeometryKind.POINT));
        }
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0.9, -1, 2}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> InferenceFeatureCodec.requireDetectionScore(invalid));
        }
        assertEquals((double) 0.9f, InferenceFeatureCodec.requireDetectionScore(0.9f));
    }

    private static JsonObject point() {
        var value =
                CanonicalCodec.parseJson(
                                "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,2]},\"properties\":{\"object_type\":\"DETECTION\",\"path_class\":\"Mitotic figure\",\"score\":0}}]}")
                        .getAsJsonObject();
        var preimage = CanonicalCodec.parseJson("[\"Mitotic figure\",[1,2],0,0]");
        value.getAsJsonArray("features")
                .get(0)
                .getAsJsonObject()
                .addProperty("id", CanonicalCodec.sha256(CanonicalCodec.canonicalBytes(preimage)));
        return value;
    }

    private static FeatureStreamDecoder.ValidatedFeatureStream validate(
            byte[] data, Set<String> classes, long count, long[] ceilings) throws Exception {
        return FeatureStreamDecoder.validate(
                new ByteArrayInputStream(data),
                budget(ceilings),
                classes,
                count,
                count,
                InferenceFeatureCodec.GeometryKind.POINT,
                () -> false);
    }

    private static FiniteInferenceCodec.ClientResultBudget budget(long[] ceilings) {
        return new FiniteInferenceCodec.ClientResultBudget(
                ceilings[0], ceilings[1], ceilings[2], ceilings[3], ceilings[4]);
    }
}
