package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.CandidateGeneration.SlideCandidate;

/** Executes the extension-owned same-class candidate-preservation fixture. */
class CandidatePipelineTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void suppressionEligibilityDoesNotRequireGeometry() {
        for (var type :
                List.of(
                        CandidatePipeline.ExistingObjectType.ANNOTATION,
                        CandidatePipeline.ExistingObjectType.DETECTION)) {
            assertTrue(
                    CandidatePipeline.isSuppressionSource(
                            type, Optional.of("Tubule"), false, false));
            assertFalse(
                    CandidatePipeline.isSuppressionSource(type, Optional.empty(), false, false));
            assertFalse(
                    CandidatePipeline.isSuppressionSource(
                            type, Optional.of("Tubule"), true, false));
            assertFalse(
                    CandidatePipeline.isSuppressionSource(
                            type, Optional.of("Tubule"), false, true));
        }
        assertFalse(
                CandidatePipeline.isSuppressionSource(
                        CandidatePipeline.ExistingObjectType.OTHER,
                        Optional.of("Tubule"),
                        false,
                        false));
    }

    @Test
    void fixturePinsTheCompleteSuppressionPredicate() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());
        assertEquals(true, fixture.get("defaultEnabled").getAsBoolean());

        for (JsonElement element : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            var candidate =
                    candidate(
                            merged(
                                    fixture.getAsJsonObject("defaultCandidate"),
                                    testCase.getAsJsonObject("candidate")),
                            fixture);
            runCase(
                    testCase,
                    List.of(candidate),
                    List.of(
                            existing(
                                    merged(
                                            fixture.getAsJsonObject("defaultExisting"),
                                            testCase.getAsJsonObject("existing")),
                                    fixture)),
                    testCase.get("expectedRetained").getAsBoolean()
                            ? List.of(candidate.candidateId())
                            : List.of());
        }

        JsonObject testCase = fixture.getAsJsonObject("multiCandidateCase");
        runCase(
                testCase,
                candidates(testCase.getAsJsonArray("candidates"), fixture),
                existing(testCase.getAsJsonArray("existing"), fixture),
                strings(testCase.getAsJsonArray("expectedRetainedIds")));

        testCase = fixture.getAsJsonObject("sharedExistingCase");
        runCase(
                testCase,
                candidates(testCase.getAsJsonArray("candidates"), fixture),
                existing(testCase.getAsJsonArray("existing"), fixture),
                strings(testCase.getAsJsonArray("expectedRetainedIds")));
    }

    @Test
    void snapshotsAndResultsOwnTheirCollectionsAndGeometry() throws IOException {
        JsonObject fixture = fixture();
        Geometry suppliedCandidate = geometry("candidate", fixture).copy();
        Geometry suppliedExisting = geometry("positive-overlap", fixture).copy();
        var candidate =
                new SlideCandidate(
                        "11111111-1111-4111-8111-111111111111",
                        suppliedCandidate,
                        "Tubule",
                        plane(0, 0));
        var existing =
                new CandidatePipeline.ExistingObjectSnapshot(
                        suppliedExisting,
                        Optional.of("Tubule"),
                        plane(0, 0),
                        CandidatePipeline.ExistingObjectType.DETECTION,
                        false,
                        false);
        String candidateBefore = candidate.geometrySlide().toText();
        String existingBefore = existing.geometrySlide().toText();

        shiftX(suppliedCandidate);
        shiftX(suppliedExisting);
        shiftX(candidate.geometrySlide());
        shiftX(existing.geometrySlide());

        var result =
                CandidatePipeline.suppressOverlappingSameClassDuplicates(
                        List.of(candidate), List.of(existing), false);
        assertEquals(candidateBefore, candidate.geometrySlide().toText());
        assertEquals(existingBefore, existing.geometrySlide().toText());
        assertEquals(List.of(candidate), result.retainedCandidates());
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.retainedCandidates().add(candidate));
    }

    @Test
    void malformedCandidatesFailAndNonpolygonExistingObjectsCannotSuppress() throws IOException {
        JsonObject fixture = fixture();
        Geometry point = GEOMETRY_FACTORY.createPoint(new CoordinateXY(5, 5));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SlideCandidate(
                                "11111111-1111-4111-8111-111111111111",
                                point,
                                "Tubule",
                                plane(0, 0)));

        var candidate = candidate(fixture.getAsJsonObject("defaultCandidate"), fixture);
        var existingPoint =
                new CandidatePipeline.ExistingObjectSnapshot(
                        point,
                        Optional.of("Tubule"),
                        plane(0, 0),
                        CandidatePipeline.ExistingObjectType.DETECTION,
                        false,
                        false);
        var result =
                CandidatePipeline.suppressOverlappingSameClassDuplicates(
                        List.of(candidate), List.of(existingPoint), true);
        assertEquals(List.of(candidate), result.retainedCandidates());
        assertEquals(0, result.suppressedCount());
    }

    @Test
    void duplicateCandidateIdsFailBeforeFiltering() throws IOException {
        JsonObject fixture = fixture();
        var first = candidate(fixture.getAsJsonObject("defaultCandidate"), fixture);
        var duplicate =
                new SlideCandidate(
                        first.candidateId(),
                        geometry("candidate-two", fixture),
                        first.pathClass(),
                        first.plane());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CandidatePipeline.suppressOverlappingSameClassDuplicates(
                                List.of(first, duplicate), List.of(), true));
    }

    private static void runCase(
            JsonObject testCase,
            List<SlideCandidate> candidates,
            List<CandidatePipeline.ExistingObjectSnapshot> existingObjects,
            List<String> expectedRetainedIds) {
        List<ExistingState> before = existingObjects.stream().map(ExistingState::capture).toList();
        var result =
                CandidatePipeline.suppressOverlappingSameClassDuplicates(
                        candidates, existingObjects, testCase.get("enabled").getAsBoolean());

        assertEquals(
                expectedRetainedIds,
                result.retainedCandidates().stream().map(SlideCandidate::candidateId).toList(),
                testCase.get("name").getAsString());
        assertEquals(
                testCase.get("expectedSuppressedCount").getAsLong(),
                result.suppressedCount(),
                testCase.get("name").getAsString());
        assertEquals(
                before,
                existingObjects.stream().map(ExistingState::capture).toList(),
                testCase.get("name").getAsString());
    }

    private static List<SlideCandidate> candidates(JsonArray values, JsonObject fixture) {
        List<SlideCandidate> result = new ArrayList<>();
        for (JsonElement value : values) {
            result.add(candidate(value.getAsJsonObject(), fixture));
        }
        return List.copyOf(result);
    }

    private static SlideCandidate candidate(JsonObject value, JsonObject fixture) {
        return new SlideCandidate(
                value.get("id").getAsString(),
                geometry(value.get("geometry").getAsString(), fixture),
                value.get("pathClass").getAsString(),
                plane(value.getAsJsonObject("plane")));
    }

    private static List<CandidatePipeline.ExistingObjectSnapshot> existing(
            JsonArray values, JsonObject fixture) {
        List<CandidatePipeline.ExistingObjectSnapshot> result = new ArrayList<>();
        for (JsonElement value : values) {
            result.add(existing(value.getAsJsonObject(), fixture));
        }
        return List.copyOf(result);
    }

    private static CandidatePipeline.ExistingObjectSnapshot existing(
            JsonObject value, JsonObject fixture) {
        return new CandidatePipeline.ExistingObjectSnapshot(
                geometry(value.get("geometry").getAsString(), fixture),
                value.has("pathClass") && !value.get("pathClass").isJsonNull()
                        ? Optional.of(value.get("pathClass").getAsString())
                        : Optional.empty(),
                plane(value.getAsJsonObject("plane")),
                CandidatePipeline.ExistingObjectType.valueOf(
                        value.get("objectType").getAsString().toUpperCase(Locale.ROOT)),
                value.get("attributed").getAsBoolean(),
                value.get("administrative").getAsBoolean());
    }

    private static JsonObject merged(JsonObject defaults, JsonObject overrides) {
        JsonObject result = defaults.deepCopy();
        if (overrides != null) {
            for (String field : overrides.keySet()) {
                result.add(field, overrides.get(field).deepCopy());
            }
        }
        return result;
    }

    private static Geometry geometry(String name, JsonObject fixture) {
        return CanonicalGeometryCodec.parseGeoJson(
                fixture.getAsJsonObject("geometries").getAsJsonObject(name));
    }

    private static SourceIdentityCodec.ImagePlaneIndex plane(JsonObject value) {
        return plane(value.get("z").getAsInt(), value.get("t").getAsInt());
    }

    private static SourceIdentityCodec.ImagePlaneIndex plane(int z, int t) {
        return new SourceIdentityCodec.ImagePlaneIndex(z, t);
    }

    private static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            result.add(value.getAsString());
        }
        return List.copyOf(result);
    }

    private static void shiftX(Geometry geometry) {
        geometry.apply((CoordinateFilter) coordinate -> coordinate.x += 100.0);
        geometry.geometryChanged();
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream input =
                CandidatePipelineTest.class
                        .getClassLoader()
                        .getResourceAsStream("contracts/candidate-preservation-v1.json")) {
            if (input == null) {
                throw new IOException("missing candidate-preservation fixture");
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private record ExistingState(
            String geometry,
            Optional<String> pathClass,
            SourceIdentityCodec.ImagePlaneIndex plane,
            CandidatePipeline.ExistingObjectType objectType,
            boolean attributed,
            boolean administrative) {
        private static ExistingState capture(CandidatePipeline.ExistingObjectSnapshot existing) {
            return new ExistingState(
                    existing.geometrySlide().toText(),
                    existing.pathClass(),
                    existing.plane(),
                    existing.objectType(),
                    existing.attributed(),
                    existing.administrative());
        }
    }
}
