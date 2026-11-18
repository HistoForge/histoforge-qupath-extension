package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.ModuleRef;

/** Runs the shared schema-1 scalar, map-key, and collection-order contract. */
class CanonicalScalarContractTest {

    @Test
    void scalarFixturesEncodeExactValuesAndMapKeys() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        for (JsonElement item : fixture.getAsJsonArray("scalarCases")) {
            JsonObject testCase = item.getAsJsonObject();
            JsonPrimitive value = validateScalar(testCase);
            byte[] encoded = CanonicalCodec.canonicalBytes(value);
            assertEquals(
                    testCase.get("canonicalJson").getAsString(),
                    new String(encoded, StandardCharsets.UTF_8),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("mapKey").getAsString(),
                    value.isNumber()
                            ? CanonicalCodec.scalarMapKey(value.getAsInt())
                            : CanonicalCodec.scalarMapKey(value.getAsString()),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    CanonicalCodec.sha256(encoded),
                    testCase.get("name").getAsString());
        }
    }

    @Test
    void invalidScalarFixturesFailClosed() throws IOException {
        for (JsonElement item : fixture().getAsJsonArray("invalidScalarCases")) {
            JsonObject testCase = item.getAsJsonObject();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> validateScalar(testCase),
                    testCase.get("name").getAsString());
        }
    }

    @Test
    void moduleRouteFixturesRequireCanonicalRawSegments() throws IOException {
        for (JsonElement item : fixture().getAsJsonArray("moduleRefRouteCases")) {
            JsonObject testCase = item.getAsJsonObject();
            var parsed =
                    ModuleRef.fromRouteSegments(
                            testCase.get("owner").getAsString(),
                            testCase.get("module").getAsString());
            if (testCase.has("canonical")) {
                assertEquals(
                        testCase.get("canonical").getAsString(),
                        parsed.orElseThrow().toString(),
                        testCase.get("name").getAsString());
            } else {
                assertTrue(parsed.isEmpty(), testCase.get("name").getAsString());
            }
        }
    }

    @Test
    void compoundMapAndUnorderedFixturesUseCanonicalByteOrder() throws IOException {
        JsonObject fixture = fixture();
        JsonObject compound = fixture.getAsJsonObject("compoundMap");
        List<Map.Entry<JsonElement, JsonElement>> entries = new ArrayList<>();
        for (JsonElement item : compound.getAsJsonArray("entries")) {
            JsonObject entry = item.getAsJsonObject();
            entries.add(Map.entry(entry.get("key"), entry.get("value")));
        }
        assertEncoded(
                CanonicalCodec.canonicalMapEntries(entries),
                compound.get("canonicalJson").getAsString(),
                compound.get("sha256").getAsString());

        JsonObject unordered = fixture.getAsJsonObject("unordered");
        List<JsonElement> values = new ArrayList<>();
        unordered.getAsJsonArray("values").forEach(values::add);
        assertEncoded(
                CanonicalCodec.canonicalUnordered(values),
                unordered.get("canonicalJson").getAsString(),
                unordered.get("sha256").getAsString());
    }

    @Test
    void canonicalCollectionsRejectIdentityCollisions() {
        JsonElement left = CanonicalCodec.parseJson("9007199254740992");
        JsonElement right = CanonicalCodec.parseJson("9007199254740993");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CanonicalCodec.canonicalMapEntries(
                                List.of(
                                        Map.entry(left, new JsonPrimitive(1)),
                                        Map.entry(right, new JsonPrimitive(2)))));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalCodec.canonicalUnordered(List.of(left, right)));
    }

    private static JsonPrimitive validateScalar(JsonObject testCase) {
        String kind = testCase.get("kind").getAsString();
        JsonElement input = testCase.get("input");
        return switch (kind) {
            case "nonblank-string" ->
                    new JsonPrimitive(CanonicalCodec.requireNonblank(input.getAsString(), "value"));
            case "sha256" ->
                    new JsonPrimitive(CanonicalCodec.requireSha256(input.getAsString(), "value"));
            case "path-class-name" ->
                    new JsonPrimitive(
                            CanonicalCodec.requirePathClassName(input.getAsString(), "value"));
            case "module-ref" ->
                    new JsonPrimitive(
                            ModuleRef.parse(input.getAsString())
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "invalid module ref"))
                                    .toString());
            case "nonnegative-int32" ->
                    new JsonPrimitive(CanonicalCodec.requireNonnegativeInt32(input, "value"));
            case "canonical-uuid" ->
                    new JsonPrimitive(
                            CanonicalCodec.requireCanonicalUuid(input.getAsString(), "value"));
            default -> throw new AssertionError("unknown fixture scalar kind: " + kind);
        };
    }

    private static void assertEncoded(JsonArray value, String expected, String digest) {
        byte[] encoded = CanonicalCodec.canonicalBytes(value);
        assertEquals(expected, new String(encoded, StandardCharsets.UTF_8));
        assertEquals(digest, CanonicalCodec.sha256(encoded));
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                CanonicalScalarContractTest.class.getResourceAsStream(
                        "/contracts/canonical-scalars-v1.json")) {
            if (stream == null) {
                throw new IOException("missing canonical scalar fixture");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
