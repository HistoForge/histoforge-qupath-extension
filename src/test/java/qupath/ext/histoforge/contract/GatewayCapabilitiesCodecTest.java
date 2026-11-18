package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Runs the shared deployment-health and authenticated-capability contract. */
class GatewayCapabilitiesCodecTest {

    @Test
    void sharedHealthFixtureHasExactBytesDigestAndRoundTrip() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        for (JsonElement item : fixture.getAsJsonArray("healthCases")) {
            JsonObject testCase = item.getAsJsonObject();
            var health = GatewayCapabilitiesCodec.parseGatewayHealth(testCase.get("value"));
            JsonObject value = GatewayCapabilitiesCodec.toJson(health);
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
                    health, GatewayCapabilitiesCodec.parseGatewayHealth(value), name(testCase));
        }
    }

    @Test
    void sharedCapabilityFixturesHaveExactBytesDigestAndRoundTrip() throws IOException {
        for (JsonElement item : fixture().getAsJsonArray("capabilityCases")) {
            JsonObject testCase = item.getAsJsonObject();
            var capabilities =
                    GatewayCapabilitiesCodec.parseGatewayCapabilities(testCase.get("value"));
            JsonObject value = GatewayCapabilitiesCodec.toJson(capabilities);
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
                    capabilities,
                    GatewayCapabilitiesCodec.parseGatewayCapabilities(value),
                    name(testCase));
        }
    }

    @Test
    void inferenceGpuChoicesAreNonemptyUniqueStrings() throws IOException {
        JsonObject source =
                fixture()
                        .getAsJsonArray("capabilityCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value");
        assertEquals(
                java.util.List.of("A10G", "L40S", "A100"),
                GatewayCapabilitiesCodec.parseGatewayCapabilities(source).inferenceGpus());

        for (JsonArray invalid :
                java.util.List.of(new JsonArray(), array("A100", "A100"), array(""))) {
            JsonObject changed = source.deepCopy();
            changed.add("inference_gpus", invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> GatewayCapabilitiesCodec.parseGatewayCapabilities(changed));
        }
    }

    @Test
    void supportRequiresEveryCurrentVersion() throws IOException {
        JsonObject value =
                fixture()
                        .getAsJsonArray("capabilityCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value");
        assertTrue(
                GatewayCapabilitiesCodec.supports(
                        GatewayCapabilitiesCodec.parseGatewayCapabilities(value)));

        for (String field :
                new String[] {
                    "api_major",
                    "module_schema_version",
                    "source_artifact_schema_version",
                    "finite_inference_schema_version",
                    "durable_model_run_schema_version",
                    "training_api_schema_version"
                }) {
            int current = value.get(field).getAsInt();
            for (int unsupported : new int[] {current - 1, current + 1}) {
                if (unsupported == 0) continue;
                JsonObject incompatible = value.deepCopy();
                incompatible.addProperty(field, unsupported);
                assertFalse(
                        GatewayCapabilitiesCodec.supports(
                                GatewayCapabilitiesCodec.parseGatewayCapabilities(incompatible)),
                        field + "=" + unsupported);
            }
        }
    }

    @Test
    void syntheticFieldBindingCaseDetectsSwappedFields() throws IOException {
        JsonObject fixture = fixture();
        JsonObject source =
                fixture.getAsJsonArray("capabilityCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        JsonObject bindingCase = fixture.getAsJsonObject("fieldBindingCase");
        for (JsonElement item : bindingCase.getAsJsonArray("mutations")) {
            JsonObject mutation = item.getAsJsonObject();
            setPath(source, mutation.getAsJsonArray("path"), mutation.get("value"));
        }

        var parsed = GatewayCapabilitiesCodec.parseGatewayCapabilities(source);
        assertEquals(source, GatewayCapabilitiesCodec.toJson(parsed), name(bindingCase));
    }

    @Test
    void sharedInvalidHandshakeMutationsFailClosed() throws IOException {
        JsonObject fixture = fixture();
        JsonObject healthBase =
                fixture.getAsJsonArray("healthCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value");
        JsonObject capabilityBase =
                fixture.getAsJsonArray("capabilityCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value");

        for (JsonElement item : fixture.getAsJsonArray("invalidMutations")) {
            JsonObject testCase = item.getAsJsonObject();
            boolean health = "health".equals(testCase.get("target").getAsString());
            JsonObject value = (health ? healthBase : capabilityBase).deepCopy();
            applyMutation(value, testCase);
            assertThrows(
                    RuntimeException.class,
                    () -> {
                        if (health) {
                            GatewayCapabilitiesCodec.parseGatewayHealth(value);
                        } else {
                            GatewayCapabilitiesCodec.parseGatewayCapabilities(value);
                        }
                    },
                    name(testCase));
        }
    }

    private static void applyMutation(JsonObject value, JsonObject mutation) {
        JsonArray path = mutation.getAsJsonArray("path");
        JsonObject parent = value;
        for (int index = 0; index < path.size() - 1; index++) {
            parent = parent.getAsJsonObject(path.get(index).getAsString());
        }
        String field = path.get(path.size() - 1).getAsString();
        if ("remove".equals(mutation.get("operation").getAsString())) {
            parent.remove(field);
        } else {
            parent.add(field, mutation.get("value").deepCopy());
        }
    }

    private static void setPath(JsonObject value, JsonArray path, JsonElement replacement) {
        JsonObject parent = value;
        for (int index = 0; index < path.size() - 1; index++) {
            parent = parent.getAsJsonObject(path.get(index).getAsString());
        }
        parent.add(path.get(path.size() - 1).getAsString(), replacement.deepCopy());
    }

    private static String name(JsonObject testCase) {
        return testCase.get("name").getAsString();
    }

    private static JsonArray array(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                GatewayCapabilitiesCodecTest.class.getResourceAsStream(
                        "/contracts/gateway-capabilities-v2.json")) {
            if (stream == null) {
                throw new IOException("missing gateway capability fixture");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
