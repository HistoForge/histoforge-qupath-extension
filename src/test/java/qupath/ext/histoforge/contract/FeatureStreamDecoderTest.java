package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;

class FeatureStreamDecoderTest {

    private static final Set<String> CLASSES = Set.of("Tubule");
    private static final FiniteInferenceCodec.ClientResultBudget BUDGET =
            new FiniteInferenceCodec.ClientResultBudget(1_000, 10_000, 1_000, 10, 100);

    @Test
    void sharedBackendStreamsValidateThenReadWithExactCountsAndCanonicalFeatures()
            throws IOException {
        for (var element : fixture("feature-stream-v1.json").getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            byte[] compressed =
                    Base64.getDecoder().decode(testCase.get("gzipBase64").getAsString());
            assertEquals(testCase.get("sizeBytes").getAsLong(), compressed.length);
            assertEquals(testCase.get("sha256").getAsString(), CanonicalCodec.sha256(compressed));

            var validated =
                    validate(
                            compressed,
                            BUDGET,
                            testCase.get("featureCount").getAsLong(),
                            testCase.get("vertexCount").getAsLong());
            List<InferenceFeatureCodec.Feature> features = new ArrayList<>();
            FeatureStreamDecoder.readValidated(
                    new ByteArrayInputStream(compressed), validated, features::add);
            byte[] expected =
                    CanonicalCodec.canonicalBytes(
                            testCase.get("collectionCanonical").getAsString());
            assertArrayEquals(
                    expected,
                    CanonicalCodec.canonicalBytes(
                            InferenceFeatureCodec.toJson(
                                    new InferenceFeatureCodec.FeatureCollection(features))));
            assertEquals(testCase.get("featureCount").getAsLong(), validated.featureCount());
            assertEquals(testCase.get("vertexCount").getAsLong(), validated.vertexCount());
            assertEquals(BUDGET.maxRecordBytes(), validated.maxRecordBytes());
        }
    }

    @Test
    void sharedFiniteFeatureFixturesKeepDuplicateOrdinalsAndMultipolygons() throws IOException {
        for (var item : fixture("inference-features-v1.json").getAsJsonArray("validCases")) {
            var testCase = item.getAsJsonObject();
            var collection =
                    JsonParser.parseString(testCase.get("canonical").getAsString())
                            .getAsJsonObject();
            Set<String> classes = Set.of("Tubule", "Lumen", "Tubule 🧪");
            var expected = InferenceFeatureCodec.validate(collection, classes);
            long vertices =
                    expected.features().stream()
                            .mapToLong(InferenceFeatureCodec::vertexCount)
                            .sum();
            byte[] compressed = gzip(records(collection.getAsJsonArray("features")));
            var validated =
                    FeatureStreamDecoder.validate(
                            new ByteArrayInputStream(compressed),
                            BUDGET,
                            classes,
                            expected.features().size(),
                            vertices);
            List<InferenceFeatureCodec.Feature> actual = new ArrayList<>();
            FeatureStreamDecoder.readValidated(
                    new ByteArrayInputStream(compressed), validated, actual::add);
            assertEquals(expected.features(), actual, testCase.get("name").getAsString());
            for (var feature : actual) {
                assertEquals(
                        CanonicalGeometryCodec.vertexCount(feature.geometry()),
                        InferenceFeatureCodec.vertexCount(feature));
                assertEquals(
                        CanonicalCodec.canonicalBytes(
                                        InferenceFeatureCodec.toJson(
                                                        new InferenceFeatureCodec.FeatureCollection(
                                                                List.of(feature)))
                                                .getAsJsonArray("features")
                                                .get(0))
                                .length,
                        InferenceFeatureCodec.canonicalRecordBytes(feature));
            }
        }
    }

    @Test
    void sharedInvalidRecordsFailDuringValidation() throws IOException {
        for (var item : fixture("inference-features-v1.json").getAsJsonArray("invalidCases")) {
            var testCase = item.getAsJsonObject();
            var collection = testCase.getAsJsonObject("featureCollection");
            if (!collection.has("features")) continue; // Streams have no collection wrapper.
            var features = collection.getAsJsonArray("features");
            byte[] compressed = gzip(records(features));
            Set<String> classes =
                    testCase.getAsJsonArray("declaredClasses").asList().stream()
                            .map(JsonElement::getAsString)
                            .collect(java.util.stream.Collectors.toSet());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            FeatureStreamDecoder.validate(
                                    new ByteArrayInputStream(compressed),
                                    BUDGET,
                                    classes,
                                    features.size(),
                                    4L * features.size()),
                    testCase.get("name").getAsString());
        }
    }

    @Test
    void missingDuplicateOccurrenceIsRejectedAfterAllRecords() throws IOException {
        var collection =
                JsonParser.parseString(
                                fixture("inference-features-v1.json")
                                        .getAsJsonArray("validCases")
                                        .get(1)
                                        .getAsJsonObject()
                                        .get("canonical")
                                        .getAsString())
                        .getAsJsonObject();
        // The second identical Tubule has occurrence 1; occurrence 0 cannot be omitted.
        JsonArray feature = new JsonArray();
        feature.add(collection.getAsJsonArray("features").get(2));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> validate(gzip(records(feature)), BUDGET, 1, 4));
        assertTrue(error.getMessage().contains("ids do not match"));
    }

    @Test
    void framingCanonicalJsonAndMalformedTailFailClosed() throws IOException {
        String record =
                new String(CanonicalCodec.canonicalBytes(firstFeature()), StandardCharsets.UTF_8);
        for (String malformed :
                List.of(
                        record + "\n",
                        "\u001e" + record,
                        "\u001e\n",
                        "\u001e \n",
                        "\u001e" + record + "\u001e\n",
                        "\u001e " + record + "\n",
                        "\u001e"
                                + record.replace(
                                        "\"type\":\"Feature\"",
                                        "\"type\":\"Feature\",\"type\":\"Feature\"")
                                + "\n",
                        "\u001e{}\n",
                        "\u001e[]\n",
                        "\u001e" + record + "\n\u001e{\"broken\":\n",
                        "\u001e" + record + "\nnot-a-record")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> validate(gzip(malformed.getBytes(StandardCharsets.UTF_8)), BUDGET, 1, 3),
                    malformed);
        }
        byte[] invalidUtf8 = new byte[] {0x1e, (byte) 0xc0, (byte) 0xaf, '\n'};
        assertThrows(
                IllegalArgumentException.class, () -> validate(gzip(invalidUtf8), BUDGET, 1, 3));
    }

    @Test
    void everyBudgetAndManifestCountFailsClosed() throws IOException {
        byte[] valid = validStream();
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(valid, withDecoded(BUDGET, 1), 2, 6));
        assertThrows(
                IllegalArgumentException.class, () -> validate(valid, withRecord(BUDGET, 2), 2, 6));
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(valid, withFeatures(BUDGET, 1), 2, 6));
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(valid, withVertices(BUDGET, 5), 2, 6));
        assertThrows(IllegalArgumentException.class, () -> validate(valid, BUDGET, 1, 6));
        assertThrows(IllegalArgumentException.class, () -> validate(valid, BUDGET, 2, 5));

        // Actual counts, not only declared manifest counts, must respect each budget.
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(valid, withFeatures(BUDGET, 1), 1, 6));
        assertThrows(
                IllegalArgumentException.class,
                () -> validate(valid, withVertices(BUDGET, 5), 2, 5));
    }

    @Test
    void incorrectSortedIdsCannotAccumulateBeyondTheActualFeatureBudget() throws IOException {
        JsonArray features = new JsonArray();
        for (int index = 0; index < 3; index++) {
            JsonObject feature = firstFeature().deepCopy();
            feature.addProperty("id", "%064x".formatted(index));
            features.add(feature);
        }
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> validate(gzip(records(features)), withFeatures(BUDGET, 2), 2, 6));
        assertTrue(error.getMessage().contains("max_features"));
    }

    @Test
    void gzipHeaderTruncationAndTrailerCorruptionFailInBothPasses() throws IOException {
        byte[] valid = validStream();
        var validated = validate(valid, BUDGET, 2, 6);
        byte[] wrongHeader = valid.clone();
        wrongHeader[0] = 0;
        byte[] wrongTrailer = valid.clone();
        wrongTrailer[wrongTrailer.length - 8] ^= 1;
        byte[] truncated = Arrays.copyOf(valid, valid.length - 3);
        for (byte[] broken : List.of(wrongHeader, wrongTrailer, truncated)) {
            assertThrows(IOException.class, () -> validate(broken, BUDGET, 2, 6));
            assertThrows(
                    IOException.class,
                    () ->
                            FeatureStreamDecoder.readValidated(
                                    new ByteArrayInputStream(broken), validated, feature -> {}));
        }
    }

    @Test
    void secondPassKeepsFramingCountsAndBoundsChecks() throws IOException {
        byte[] valid = validStream();
        var validated = validate(valid, BUDGET, 2, 6);
        byte[] oneFeature = gzip(records(newJsonArray(firstFeature())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FeatureStreamDecoder.readValidated(
                                new ByteArrayInputStream(oneFeature), validated, feature -> {}));

        var wrongVertices =
                new FeatureStreamDecoder.ValidatedFeatureStream(
                        validated.decodedBytes(),
                        2,
                        5,
                        1_000,
                        validated.bounds(),
                        validated.geometryKind());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FeatureStreamDecoder.readValidated(
                                new ByteArrayInputStream(valid), wrongVertices, feature -> {}));
        var wrongBounds =
                new FeatureStreamDecoder.ValidatedFeatureStream(
                        validated.decodedBytes(),
                        2,
                        6,
                        1_000,
                        new Envelope(0, 7, 0, 2),
                        validated.geometryKind());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FeatureStreamDecoder.readValidated(
                                new ByteArrayInputStream(valid), wrongBounds, feature -> {}));
    }

    @Test
    void aggregateBoundsUseTheSameRasterRuleWithoutRetainingPolygons() throws IOException {
        var validated = validate(validStream(), BUDGET, 2, 6);
        assertEquals(new Envelope(0, 6, 0, 2), validated.bounds());
        validated.requireInsideRaster(raster(6, 2));
        assertThrows(
                IllegalArgumentException.class, () -> validated.requireInsideRaster(raster(5, 2)));
        assertThrows(
                IllegalArgumentException.class, () -> validated.requireInsideRaster(raster(6, 1)));

        Envelope exposed = validated.bounds();
        exposed.setToNull();
        assertFalse(validated.bounds().isNull());
        var empty = validate(gzip(new byte[0]), BUDGET, 0, 0);
        assertEquals(0, empty.decodedBytes());
        assertTrue(empty.bounds().isNull());
        empty.requireInsideRaster(raster(1, 1));

        Envelope source = new Envelope(-1, 5, 0, 2);
        var negative =
                new FeatureStreamDecoder.ValidatedFeatureStream(
                        20, 1, 3, 1_000, source, InferenceFeatureCodec.GeometryKind.POLYGONAL);
        source.translate(1, 0);
        assertThrows(
                IllegalArgumentException.class, () -> negative.requireInsideRaster(raster(6, 2)));
    }

    @Test
    void cancellationStopsBothPassesBetweenRecords() throws IOException {
        byte[] valid = validStream();
        AtomicInteger checks = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        FeatureStreamDecoder.validate(
                                new ByteArrayInputStream(valid),
                                BUDGET,
                                CLASSES,
                                2,
                                6,
                                () -> checks.incrementAndGet() >= 3));

        var validated = validate(valid, BUDGET, 2, 6);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger consumed = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        FeatureStreamDecoder.readValidated(
                                new ByteArrayInputStream(valid),
                                validated,
                                feature -> {
                                    consumed.incrementAndGet();
                                    stop.set(true);
                                },
                                stop::get));
        assertEquals(1, consumed.get());
    }

    @Test
    void secondPassDeliversRecordsBeforeReadingTheEntireFile() throws IOException {
        List<InferenceFeatureCodec.Feature> features = new ArrayList<>();
        GeometryFactory geometries = new GeometryFactory();
        for (int index = 0; index < 800; index++) {
            var polygon =
                    geometries.createPolygon(
                            new Coordinate[] {
                                new Coordinate(index, 0), new Coordinate(index + 1, 0),
                                new Coordinate(index, 1), new Coordinate(index, 0)
                            });
            JsonArray identity = new JsonArray();
            identity.add("Tubule");
            identity.add(CanonicalGeometryCodec.canonicalCoordinates(polygon));
            identity.add(0);
            features.add(
                    new InferenceFeatureCodec.Feature(
                            CanonicalCodec.canonicalDigest(identity), polygon, "Tubule"));
        }
        features.sort(Comparator.comparing(InferenceFeatureCodec.Feature::id));
        var collection =
                InferenceFeatureCodec.toJson(new InferenceFeatureCodec.FeatureCollection(features));
        byte[] compressed = gzip(records(collection.getAsJsonArray("features")));
        var budget =
                new FiniteInferenceCodec.ClientResultBudget(
                        compressed.length, 1_000_000, 1_000, features.size(), features.size() * 3L);
        var validated = validate(compressed, budget, features.size(), features.size() * 3L);
        var input = new CountingInput(compressed);
        assertThrows(
                CancellationException.class,
                () ->
                        FeatureStreamDecoder.readValidated(
                                input,
                                validated,
                                feature -> {
                                    assertTrue(input.bytesRead < compressed.length);
                                    throw new CancellationException(
                                            "Stop after the first converted feature");
                                }));
    }

    @Test
    void framingAcrossDecodedBlockBoundariesKeepsExactLimitsAndBothPasses() throws IOException {
        for (int length : List.of(65534, 65535, 65536, 65537, 131071)) {
            JsonObject feature = featureOfLength(length);
            String pathClass =
                    feature.getAsJsonObject("properties").get("path_class").getAsString();
            byte[] decoded = records(newJsonArray(feature));
            assertEquals(length + 2, decoded.length);
            byte[] compressed = gzip(decoded);
            var budget =
                    new FiniteInferenceCodec.ClientResultBudget(
                            compressed.length, decoded.length, length, 1, 3);
            var summary =
                    FeatureStreamDecoder.validate(
                            new ByteArrayInputStream(compressed), budget, Set.of(pathClass), 1, 3);
            assertEquals(decoded.length, summary.decodedBytes());
            List<InferenceFeatureCodec.Feature> read = new ArrayList<>();
            FeatureStreamDecoder.readValidated(
                    new ByteArrayInputStream(compressed), summary, read::add);
            assertEquals(1, read.size());
            assertEquals(pathClass, read.getFirst().pathClass());
            for (var limited :
                    List.of(
                            withRecord(budget, length - 1),
                            withDecoded(budget, decoded.length - 1))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                FeatureStreamDecoder.validate(
                                        new ByteArrayInputStream(compressed),
                                        limited,
                                        Set.of(pathClass),
                                        1,
                                        3));
            }
            decoded[decoded.length - 1] = 0x1e;
            byte[] invalid = gzip(decoded);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            FeatureStreamDecoder.validate(
                                    new ByteArrayInputStream(invalid),
                                    budget,
                                    Set.of(pathClass),
                                    1,
                                    3));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            FeatureStreamDecoder.readValidated(
                                    new ByteArrayInputStream(invalid), summary, ignored -> {}));
        }
    }

    @Test
    void cancellationIsCheckedInsideALargeRecord() throws IOException {
        var feature = featureOfLength(4 * 65536);
        String pathClass = feature.getAsJsonObject("properties").get("path_class").getAsString();
        byte[] compressed = gzip(records(newJsonArray(feature)));
        var budget =
                new FiniteInferenceCodec.ClientResultBudget(
                        compressed.length, 300000, 300000, 1, 3);
        var summary =
                FeatureStreamDecoder.validate(
                        new ByteArrayInputStream(compressed), budget, Set.of(pathClass), 1, 3);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        FeatureStreamDecoder.validate(
                                new ByteArrayInputStream(compressed),
                                budget,
                                Set.of(pathClass),
                                1,
                                3,
                                () -> checks.incrementAndGet() == 4));
        checks.set(0);
        assertThrows(
                CancellationException.class,
                () ->
                        FeatureStreamDecoder.readValidated(
                                new ByteArrayInputStream(compressed),
                                summary,
                                ignored -> {
                                    throw new AssertionError("cancel before a complete record");
                                },
                                () -> checks.incrementAndGet() == 4));
    }

    @Test
    void fragmentedCompressedReadsPreserveUtf8AcrossDecodedBlockBoundaries() throws IOException {
        JsonObject feature = featureOfLength(140000);
        String canonical =
                new String(CanonicalCodec.canonicalBytes(feature), StandardCharsets.UTF_8);
        int classStart = canonical.indexOf("\"path_class\":\"") + "\"path_class\":\"".length() + 1;
        String pathClass = "X".repeat(65535 - classStart) + "🧪" + "Y".repeat(70000);
        feature.getAsJsonObject("properties").addProperty("path_class", pathClass);
        JsonArray identity = new JsonArray();
        identity.add(pathClass);
        identity.add(feature.getAsJsonObject("geometry").get("coordinates"));
        identity.add(0);
        feature.addProperty("id", CanonicalCodec.canonicalDigest(identity));
        byte[] decoded = records(newJsonArray(feature));
        assertEquals(0xf0, decoded[65535] & 0xff);
        byte[] compressed = gzip(decoded);
        var budget =
                new FiniteInferenceCodec.ClientResultBudget(
                        compressed.length, decoded.length, decoded.length - 2, 1, 3);
        for (boolean fragmented : List.of(false, true)) {
            var summary =
                    FeatureStreamDecoder.validate(
                            fragmented
                                    ? new CountingInput(compressed)
                                    : new ByteArrayInputStream(compressed),
                            budget,
                            Set.of(pathClass),
                            1,
                            3);
            List<InferenceFeatureCodec.Feature> read = new ArrayList<>();
            FeatureStreamDecoder.readValidated(
                    fragmented
                            ? new CountingInput(compressed)
                            : new ByteArrayInputStream(compressed),
                    summary,
                    read::add);
            assertEquals(pathClass, read.getFirst().pathClass());
        }
        for (byte[] malformed :
                List.of(
                        Arrays.copyOf(decoded, decoded.length - 1),
                        append(decoded, new byte[] {0x1e, '\n'}),
                        append(decoded, new byte[] {0x1e, '{'}))) {
            byte[] invalid = gzip(malformed);
            var generous =
                    new FiniteInferenceCodec.ClientResultBudget(1000000, 1000000, 200000, 2, 6);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            FeatureStreamDecoder.validate(
                                    new CountingInput(invalid), generous, Set.of(pathClass), 1, 3));
        }
    }

    private static byte[] append(byte[] prefix, byte[] suffix) {
        byte[] result = Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private static JsonObject featureOfLength(int length) throws IOException {
        JsonObject feature = firstFeature().deepCopy();
        int current = CanonicalCodec.canonicalBytes(feature).length;
        String pathClass = "X".repeat(length - current + "Tubule".length());
        feature.getAsJsonObject("properties").addProperty("path_class", pathClass);
        JsonArray identity = new JsonArray();
        identity.add(pathClass);
        identity.add(feature.getAsJsonObject("geometry").get("coordinates"));
        identity.add(0);
        feature.addProperty("id", CanonicalCodec.canonicalDigest(identity));
        return feature;
    }

    private static FeatureStreamDecoder.ValidatedFeatureStream validate(
            byte[] value,
            FiniteInferenceCodec.ClientResultBudget budget,
            long features,
            long vertices)
            throws IOException {
        return FeatureStreamDecoder.validate(
                new ByteArrayInputStream(value), budget, CLASSES, features, vertices);
    }

    private static byte[] validStream() throws IOException {
        return Base64.getDecoder()
                .decode(
                        fixture("feature-stream-v1.json")
                                .getAsJsonArray("cases")
                                .get(0)
                                .getAsJsonObject()
                                .get("gzipBase64")
                                .getAsString());
    }

    private static JsonObject firstFeature() throws IOException {
        return JsonParser.parseString(
                        fixture("feature-stream-v1.json")
                                .getAsJsonArray("cases")
                                .get(0)
                                .getAsJsonObject()
                                .get("collectionCanonical")
                                .getAsString())
                .getAsJsonObject()
                .getAsJsonArray("features")
                .get(0)
                .getAsJsonObject();
    }

    private static JsonArray newJsonArray(JsonElement item) {
        JsonArray result = new JsonArray();
        result.add(item);
        return result;
    }

    private static byte[] records(JsonArray features) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (var feature : features) {
            result.write(0x1e);
            result.write(CanonicalCodec.canonicalBytes(feature));
            result.write('\n');
        }
        return result.toByteArray();
    }

    private static byte[] gzip(byte[] decoded) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (var output = new GZIPOutputStream(result)) {
            output.write(decoded);
        }
        return result.toByteArray();
    }

    private static RasterInputCodec.RasterDescriptor raster(int width, int height) {
        return new RasterInputCodec.RasterDescriptor(
                width,
                height,
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                0.25,
                RasterInputCodec.ResamplingType.BILINEAR,
                RasterInputCodec.RasterPixelFormat.SRGB_UINT8);
    }

    private static FiniteInferenceCodec.ClientResultBudget withDecoded(
            FiniteInferenceCodec.ClientResultBudget value, long limit) {
        return new FiniteInferenceCodec.ClientResultBudget(
                value.maxTransferredBytes(),
                limit,
                value.maxRecordBytes(),
                value.maxFeatures(),
                value.maxVertices());
    }

    private static FiniteInferenceCodec.ClientResultBudget withRecord(
            FiniteInferenceCodec.ClientResultBudget value, long limit) {
        return new FiniteInferenceCodec.ClientResultBudget(
                value.maxTransferredBytes(),
                value.maxDecodedBytes(),
                limit,
                value.maxFeatures(),
                value.maxVertices());
    }

    private static FiniteInferenceCodec.ClientResultBudget withFeatures(
            FiniteInferenceCodec.ClientResultBudget value, long limit) {
        return new FiniteInferenceCodec.ClientResultBudget(
                value.maxTransferredBytes(),
                value.maxDecodedBytes(),
                value.maxRecordBytes(),
                limit,
                value.maxVertices());
    }

    private static FiniteInferenceCodec.ClientResultBudget withVertices(
            FiniteInferenceCodec.ClientResultBudget value, long limit) {
        return new FiniteInferenceCodec.ClientResultBudget(
                value.maxTransferredBytes(),
                value.maxDecodedBytes(),
                value.maxRecordBytes(),
                value.maxFeatures(),
                limit);
    }

    private static JsonObject fixture(String name) throws IOException {
        try (var reader =
                new InputStreamReader(
                        FeatureStreamDecoderTest.class.getResourceAsStream("/contracts/" + name),
                        StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static final class CountingInput extends ByteArrayInputStream {
        private int bytesRead;

        CountingInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value != -1) bytesRead++;
            return value;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            int count = super.read(bytes, offset, Math.min(length, 128));
            if (count > 0) bytesRead += count;
            return count;
        }
    }
}
