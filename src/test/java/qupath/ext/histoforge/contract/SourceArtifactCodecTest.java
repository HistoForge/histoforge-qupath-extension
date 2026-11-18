package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Runs the shared schema-1 source-artifact reservation contract. */
class SourceArtifactCodecTest {

    @Test
    void sharedSourceIdentityFixtureHasExactBytesAndDigest() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        for (JsonElement item : fixture.getAsJsonArray("sourceIdentityCases")) {
            JsonObject testCase = item.getAsJsonObject();
            var source = SourceIdentityCodec.parseImageSourceIdentity(testCase.get("value"));
            JsonObject value = SourceIdentityCodec.toJson(source);
            byte[] encoded = CanonicalCodec.canonicalBytes(value);

            assertEquals(
                    testCase.get("canonical").getAsString(),
                    new String(encoded, StandardCharsets.UTF_8),
                    name(testCase));
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    CanonicalCodec.sha256(encoded),
                    name(testCase));
            assertEquals(
                    source, SourceIdentityCodec.parseImageSourceIdentity(value), name(testCase));
        }
    }

    @Test
    void sharedStartFixturesNormalizeAndBindAcceptanceExactly() throws IOException {
        for (JsonElement item : fixture().getAsJsonArray("startCases")) {
            JsonObject testCase = item.getAsJsonObject();
            var request = SourceArtifactCodec.parseStartRequest(testCase.get("value"));
            JsonObject value = SourceArtifactCodec.toJson(request);
            byte[] encoded = CanonicalCodec.canonicalBytes(value);

            assertEquals(
                    testCase.get("canonical").getAsString(),
                    new String(encoded, StandardCharsets.UTF_8),
                    name(testCase));
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    CanonicalCodec.sha256(encoded),
                    name(testCase));
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    SourceArtifactCodec.startRequestDigest(request),
                    name(testCase));
            assertEquals(request, SourceArtifactCodec.parseStartRequest(value), name(testCase));

            var accepted = SourceArtifactCodec.parseAccepted(testCase.get("accepted"));
            assertEquals(
                    accepted,
                    SourceArtifactCodec.validateAcceptance(request, accepted),
                    name(testCase));
            byte[] acceptedBytes =
                    CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(accepted));
            assertEquals(
                    testCase.get("accepted_canonical").getAsString(),
                    new String(acceptedBytes, StandardCharsets.UTF_8),
                    name(testCase));
            assertEquals(
                    testCase.get("accepted_sha256").getAsString(),
                    CanonicalCodec.sha256(acceptedBytes),
                    name(testCase));
        }
    }

    @Test
    void sharedChunkPlanCasesAreExactAndLazy() throws IOException {
        for (JsonElement item : fixture().getAsJsonArray("startCases")) {
            JsonObject testCase = item.getAsJsonObject();
            var request = SourceArtifactCodec.parseStartRequest(testCase.get("value"));
            assertEquals(
                    testCase.get("chunk_count").getAsInt(),
                    SourceArtifactCodec.chunkCount(request.totalBytes(), request.chunkSizeBytes()),
                    name(testCase));

            for (JsonElement rangeItem : testCase.getAsJsonArray("chunk_ranges")) {
                JsonObject expected = rangeItem.getAsJsonObject();
                assertEquals(
                        new SourceArtifactCodec.ChunkRange(
                                expected.get("chunk_id").getAsInt(),
                                expected.get("offset").getAsLong(),
                                expected.get("size").getAsInt()),
                        SourceArtifactCodec.chunkRange(
                                request.totalBytes(),
                                request.chunkSizeBytes(),
                                expected.get("chunk_id").getAsInt()),
                        name(testCase));
            }
        }
    }

    @Test
    void sharedInvalidStartMutationsFailClosed() throws IOException {
        JsonObject fixture = fixture();
        for (JsonElement item : fixture.getAsJsonArray("invalidStartMutations")) {
            JsonObject mutation = item.getAsJsonObject();
            JsonObject value =
                    startCase(fixture, mutation.get("base").getAsString())
                            .getAsJsonObject("value")
                            .deepCopy();
            applyMutation(value, mutation);
            assertThrows(
                    RuntimeException.class,
                    () -> SourceArtifactCodec.parseStartRequest(value),
                    name(mutation));
        }
    }

    @Test
    void sharedInvalidAcceptanceMutationsFailClosed() throws IOException {
        JsonObject fixture = fixture();
        JsonObject testCase = fixture.getAsJsonArray("startCases").get(0).getAsJsonObject();
        var request = SourceArtifactCodec.parseStartRequest(testCase.get("value"));

        for (JsonElement item : fixture.getAsJsonArray("invalidAcceptedMutations")) {
            JsonObject mutation = item.getAsJsonObject();
            JsonObject acceptedValue = testCase.getAsJsonObject("accepted").deepCopy();
            applyMutation(acceptedValue, mutation);
            assertThrows(
                    RuntimeException.class,
                    () -> {
                        var accepted = SourceArtifactCodec.parseAccepted(acceptedValue);
                        SourceArtifactCodec.validateAcceptance(request, accepted);
                    },
                    name(mutation));
        }
    }

    @Test
    void requiredLevelsAreSortedWithoutRetainingMutableInput() throws IOException {
        JsonObject value =
                fixture()
                        .getAsJsonArray("startCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        var request = SourceArtifactCodec.parseStartRequest(value);

        assertEquals(
                List.of(2, 0, 1),
                value.getAsJsonArray("required_levels").asList().stream()
                        .map(item -> item.getAsJsonObject().get("resolution_level").getAsInt())
                        .toList());
        assertEquals(
                List.of(0, 1, 2),
                request.requiredLevels().stream()
                        .map(SourceArtifactCodec.SourceLevelRequirement::resolutionLevel)
                        .toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.requiredLevels().add(request.requiredLevels().getFirst()));
    }

    @Test
    void duplicateLevelKeysAndNonfiniteTransformsFailClosed() throws IOException {
        JsonObject duplicate =
                fixture()
                        .getAsJsonArray("startCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        JsonArray levels = duplicate.getAsJsonArray("required_levels");
        levels.add(levels.get(0).deepCopy());
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactCodec.parseStartRequest(duplicate));

        assertThrows(
                IllegalArgumentException.class,
                () -> new SourceIdentityCodec.ScaleTranslate2D(1.0, 1.0, Double.NaN, 0.0));
    }

    @Test
    void derivedSourceMayOmitVersionButOriginalSourceMayNot() throws IOException {
        JsonObject fixture = fixture();
        JsonObject derived =
                fixture.getAsJsonArray("startCases")
                        .get(2)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        derived.getAsJsonObject("origin").getAsJsonObject("source").remove("content_version");
        var derivedRequest = SourceArtifactCodec.parseStartRequest(derived);
        var derivedOrigin = (SourceArtifactCodec.DerivedRegion) derivedRequest.origin();
        assertEquals(Optional.empty(), derivedOrigin.source().contentVersion());

        JsonObject original =
                fixture.getAsJsonArray("startCases")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        original.getAsJsonObject("origin").getAsJsonObject("source").remove("content_version");
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceArtifactCodec.parseStartRequest(original));
    }

    @Test
    void localModificationTimeUsesTheSignedJcsSafeRange() {
        var version =
                new SourceIdentityCodec.LocalFile(1L, -9_007_199_254_740_991L, Optional.empty());
        assertEquals(
                version,
                SourceIdentityCodec.parseSourceContentVersion(SourceIdentityCodec.toJson(version)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SourceIdentityCodec.LocalFile(
                                1L, -9_007_199_254_740_992L, Optional.empty()));
    }

    @Test
    void s3EtagRequiresANonemptyQuotedIdentifier() {
        assertThrows(IllegalArgumentException.class, () -> new SourceIdentityCodec.S3ETag("\"\""));
    }

    @Test
    void chunkPlanRejectsUnaddressableCountAndOutOfRangeId() {
        assertThrows(
                IllegalArgumentException.class, () -> SourceArtifactCodec.chunkCount(1L << 31, 1));
        assertThrows(
                IllegalArgumentException.class, () -> SourceArtifactCodec.chunkRange(10, 4, 3));
    }

    private static JsonObject startCase(JsonObject fixture, String name) {
        for (JsonElement item : fixture.getAsJsonArray("startCases")) {
            JsonObject testCase = item.getAsJsonObject();
            if (name.equals(testCase.get("name").getAsString())) {
                return testCase;
            }
        }
        throw new IllegalArgumentException("unknown fixture case: " + name);
    }

    private static void applyMutation(JsonObject value, JsonObject mutation) {
        JsonArray path = mutation.getAsJsonArray("path");
        JsonElement parent = value;
        for (int index = 0; index < path.size() - 1; index++) {
            JsonElement segment = path.get(index);
            parent =
                    parent.isJsonArray()
                            ? parent.getAsJsonArray().get(segment.getAsInt())
                            : parent.getAsJsonObject().get(segment.getAsString());
        }
        JsonElement field = path.get(path.size() - 1);
        if (parent.isJsonArray()) {
            parent.getAsJsonArray().set(field.getAsInt(), mutation.get("value").deepCopy());
        } else if ("remove".equals(mutation.get("operation").getAsString())) {
            parent.getAsJsonObject().remove(field.getAsString());
        } else {
            parent.getAsJsonObject().add(field.getAsString(), mutation.get("value").deepCopy());
        }
    }

    private static String name(JsonObject testCase) {
        return testCase.get("name").getAsString();
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                SourceArtifactCodecTest.class.getResourceAsStream(
                        "/contracts/source-artifacts-v2.json")) {
            if (stream == null) {
                throw new IOException("missing source-artifact fixture");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
