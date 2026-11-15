package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

/** Exact class/shape occurrence checks, independent of the validator's bookkeeping. */
class FeatureIdValidationTest {

    private static final Set<String> CLASSES = Set.of("Cell", "Tubule 🧪");

    @Test
    void idsCannotBeExchangedBetweenDifferentShapesOrClasses() throws Exception {
        Geometry first = square(0);
        Geometry second = square(10);
        var original = feature(first, "Cell", 0);
        for (var other : List.of(feature(second, "Cell", 0), feature(first, "Tubule 🧪", 0))) {
            var swapped =
                    collection(
                            new InferenceFeatureCodec.Feature(other.id(), first, "Cell"),
                            new InferenceFeatureCodec.Feature(
                                    original.id(), other.geometry(), other.pathClass()));
            assertRejectedIds(swapped);
        }
    }

    @Test
    void interleavedGroupsMatchIndependentOccurrenceSets() throws Exception {
        Random random = new Random(8913);
        for (int trial = 0; trial < 40; trial++) {
            List<InferenceFeatureCodec.Feature> population = new ArrayList<>();
            for (int group = 0; group < 8; group++) {
                Geometry geometry = square(group * 10);
                String pathClass = group % 2 == 0 ? "Cell" : "Tubule 🧪";
                int count = 1 + random.nextInt(12);
                for (int occurrence = 0; occurrence < count; occurrence++) {
                    population.add(feature(geometry, pathClass, occurrence));
                }
            }
            var value = collection(population.toArray(InferenceFeatureCodec.Feature[]::new));
            assertTrue(exactOccurrenceSetsMatch(value));
            var validated = InferenceFeatureCodec.validate(value, CLASSES);
            assertEquals(value, InferenceFeatureCodec.toJson(validated));
            assertEquals(population.size(), validateStream(value).featureCount());

            // Replacing an ID preserves its syntax/order but not the group's occurrence set.
            var corrupted = value.deepCopy();
            corrupted
                    .getAsJsonArray("features")
                    .get(0)
                    .getAsJsonObject()
                    .addProperty("id", "0".repeat(64));
            assertRejectedIds(corrupted);
        }
    }

    @Test
    void balancedDuplicatePrefixKeepsTheNextOccurrenceNumber() throws Exception {
        // These four valid IDs sort as occurrences 1, 0, 3, 2, balancing after each pair.
        Geometry geometry = square(12);
        var features =
                List.of(
                        feature(geometry, "Cell", 0),
                        feature(geometry, "Cell", 1),
                        feature(geometry, "Cell", 2),
                        feature(geometry, "Cell", 3));
        var value = collection(features.toArray(InferenceFeatureCodec.Feature[]::new));
        assertEquals(
                List.of(
                        features.get(1).id(),
                        features.get(0).id(),
                        features.get(3).id(),
                        features.get(2).id()),
                value.getAsJsonArray("features").asList().stream()
                        .map(item -> item.getAsJsonObject().get("id").getAsString())
                        .toList());
        assertEquals(4, InferenceFeatureCodec.validate(value, CLASSES).features().size());
        assertEquals(4, validateStream(value).featureCount());
    }

    @Test
    void missingInteriorOccurrenceAndNonzeroSingletonAreRejected() throws Exception {
        Geometry geometry = square(12);
        assertRejectedIds(
                collection(
                        feature(geometry, "Cell", 0),
                        feature(geometry, "Cell", 1),
                        feature(geometry, "Cell", 3)));
        assertRejectedIds(collection(feature(geometry, "Cell", 1)));
    }

    @Test
    void expectedZeroIdOfAnotherGroupCannotHideAnIncorrectOccurrence() throws Exception {
        Geometry first = square(0);
        Geometry second = square(10);
        var a0 = feature(first, "Cell", 0);
        var a1 = feature(first, "Cell", 1);
        var b0 = feature(second, "Cell", 0);
        // Globally, these IDs equal A0/A1/B0. Their class/shape association is wrong.
        assertRejectedIds(
                collection(
                        a0,
                        new InferenceFeatureCodec.Feature(b0.id(), first, "Cell"),
                        new InferenceFeatureCodec.Feature(a1.id(), second, "Cell")));
    }

    @Test
    void completionStillChecksCancellationAfterEveryRecordPassed() throws Exception {
        var value = collection(feature(square(0), "Cell", 0), feature(square(10), "Cell", 0));
        var records = value.getAsJsonArray("features");
        for (int stopAt : List.of(1, 3)) {
            var validator = new InferenceFeatureCodec.RecordValidator(CLASSES);
            for (int index = 0; index < records.size(); index++) {
                validator.validate(records.get(index), index);
            }
            AtomicInteger checks = new AtomicInteger();
            assertThrows(
                    CancellationException.class,
                    () ->
                            validator.finish(
                                    () -> {
                                        if (checks.incrementAndGet() == stopAt) {
                                            throw new CancellationException("Stop at EOF");
                                        }
                                    }));
            assertEquals(stopAt, checks.get());
        }
    }

    private static void assertRejectedIds(JsonObject value) throws IOException {
        assertFalse(exactOccurrenceSetsMatch(value));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> InferenceFeatureCodec.validate(value, CLASSES));
        assertTrue(error.getMessage().contains("ids do not match"));
        var streamError = assertThrows(IllegalArgumentException.class, () -> validateStream(value));
        assertTrue(streamError.getMessage().contains("ids do not match"));
    }

    private static FeatureStreamDecoder.ValidatedFeatureStream validateStream(JsonObject value)
            throws IOException {
        var records = value.getAsJsonArray("features");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new GZIPOutputStream(bytes)) {
            for (var record : records) {
                output.write(0x1e);
                output.write(CanonicalCodec.canonicalBytes(record));
                output.write('\n');
            }
        }
        var budget =
                new FiniteInferenceCodec.ClientResultBudget(
                        1_000_000, 1_000_000, 10_000, 1_000, 10_000);
        // All synthetic test shapes are four-vertex squares.
        return FeatureStreamDecoder.validate(
                new ByteArrayInputStream(bytes.toByteArray()),
                budget,
                CLASSES,
                records.size(),
                4L * records.size());
    }

    private static InferenceFeatureCodec.Feature feature(
            Geometry geometry, String pathClass, int occurrence) {
        return new InferenceFeatureCodec.Feature(
                occurrenceId(
                        pathClass,
                        CanonicalGeometryCodec.canonicalCoordinates(geometry),
                        occurrence),
                geometry,
                pathClass);
    }

    private static String occurrenceId(String pathClass, JsonArray coordinates, int occurrence) {
        JsonArray identity = new JsonArray();
        identity.add(pathClass);
        identity.add(coordinates);
        identity.add(occurrence);
        return CanonicalCodec.canonicalDigest(identity);
    }

    /** The previous acceptance rule: exact class/coordinate keys and complete expected ID sets. */
    private static boolean exactOccurrenceSetsMatch(JsonObject collection) {
        record Shape(String pathClass, JsonArray coordinates) {}
        Map<Shape, Set<String>> observed = new HashMap<>();
        for (var item : collection.getAsJsonArray("features")) {
            JsonObject feature = item.getAsJsonObject();
            var shape =
                    new Shape(
                            feature.getAsJsonObject("properties").get("path_class").getAsString(),
                            feature.getAsJsonObject("geometry").getAsJsonArray("coordinates"));
            observed.computeIfAbsent(shape, ignored -> new HashSet<>())
                    .add(feature.get("id").getAsString());
        }
        for (var entry : observed.entrySet()) {
            var shape = entry.getKey();
            Set<String> expected = new HashSet<>();
            for (int occurrence = 0; occurrence < entry.getValue().size(); occurrence++) {
                expected.add(occurrenceId(shape.pathClass(), shape.coordinates(), occurrence));
            }
            if (!expected.equals(entry.getValue())) return false;
        }
        return true;
    }

    private static JsonObject collection(InferenceFeatureCodec.Feature... features) {
        var ordered = new ArrayList<>(Arrays.asList(features));
        ordered.sort(Comparator.comparing(InferenceFeatureCodec.Feature::id));
        return InferenceFeatureCodec.toJson(new InferenceFeatureCodec.FeatureCollection(ordered));
    }

    private static Geometry square(int x) throws Exception {
        return new WKTReader()
                .read(
                        "POLYGON (("
                                + x
                                + " 0, "
                                + (x + 4)
                                + " 0, "
                                + (x + 4)
                                + " 4, "
                                + x
                                + " 4, "
                                + x
                                + " 0))");
    }
}
