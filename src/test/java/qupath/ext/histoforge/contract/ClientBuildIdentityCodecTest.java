package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Pins the persisted client-build identity without selecting production release limits. */
class ClientBuildIdentityCodecTest {

    @Test
    void fixtureHasExactCanonicalBytesDigestAndFieldBindings() throws IOException {
        JsonObject testCase = testCase();
        BuildIdentityCodec.ClientBuildIdentity identity =
                BuildIdentityCodec.parseClientBuildIdentity(testCase.get("value"));

        JsonObject encoded = BuildIdentityCodec.toJson(identity);
        assertEquals(testCase.get("value"), encoded);
        assertEquals(
                testCase.get("canonical").getAsString(),
                new String(CanonicalCodec.canonicalBytes(encoded), StandardCharsets.UTF_8));
        assertEquals(
                testCase.get("sha256").getAsString(),
                BuildIdentityCodec.clientBuildIdentityDigest(identity));

        ExecutionLimitsCodec.ClientExecutionLimits limits = identity.limits();
        assertEquals(1009, limits.maxHierarchyObjects());
        assertEquals(2003, limits.maxHierarchyGeometryVertices());
        assertEquals(3001, limits.maxHierarchyMeasurements());
        assertEquals(4001, limits.maxHierarchyTextUtf8Bytes());
        assertEquals(5003, limits.maxGenerationStoreUtf8Bytes());
        assertEquals(6007, limits.maxMetricValuesPerRegion());
        assertEquals(7001, limits.maxMetricValuesPerImage());
        assertEquals(8009, limits.maxLocalSourceArtifactBytes());
    }

    @Test
    void everyLimitContributesToClientIdentity() throws IOException {
        JsonObject original = testCase().getAsJsonObject("value");
        String originalDigest =
                BuildIdentityCodec.clientBuildIdentityDigest(
                        BuildIdentityCodec.parseClientBuildIdentity(original));

        for (String field : original.getAsJsonObject("limits").keySet()) {
            JsonObject changed = original.deepCopy();
            JsonObject limits = changed.getAsJsonObject("limits");
            limits.addProperty(field, limits.get(field).getAsLong() + 1);
            assertNotEquals(
                    originalDigest,
                    BuildIdentityCodec.clientBuildIdentityDigest(
                            BuildIdentityCodec.parseClientBuildIdentity(changed)),
                    field);
        }
    }

    @Test
    void clientLimitsAcceptTheExactJcsSafeIntegerMaximum() throws IOException {
        JsonObject value = testCase().getAsJsonObject("value").deepCopy();
        value.getAsJsonObject("limits")
                .addProperty("max_local_source_artifact_bytes", 9_007_199_254_740_991L);

        assertEquals(
                9_007_199_254_740_991L,
                BuildIdentityCodec.parseClientBuildIdentity(value)
                        .limits()
                        .maxLocalSourceArtifactBytes());
    }

    @Test
    void runtimeVersionsAreDetachedAndImmutable() throws IOException {
        BuildIdentityCodec.ClientBuildIdentity parsed =
                BuildIdentityCodec.parseClientBuildIdentity(testCase().getAsJsonObject("value"));
        assertEquals(
                Map.of("java", "21.0.6", "groovy", "4.0.22", "jts", "1.20.0"),
                parsed.runtimeVersions());
        assertThrows(
                UnsupportedOperationException.class,
                () -> parsed.runtimeVersions().put("java", "changed"));

        Map<String, String> source = new LinkedHashMap<>(parsed.runtimeVersions());
        BuildIdentityCodec.ClientBuildIdentity copied =
                new BuildIdentityCodec.ClientBuildIdentity(
                        parsed.extension(), parsed.quPathVersion(), source, parsed.limits());
        source.put("new-runtime", "1");
        assertEquals(parsed.runtimeVersions(), copied.runtimeVersions());
    }

    @Test
    void malformedClientBuildAndLimitsFailClosed() throws IOException {
        assertInvalid(value -> value.remove("extension"));
        assertInvalid(value -> value.addProperty("unknown", true));
        assertInvalid(value -> value.addProperty("qupath_version", "\u00a0"));
        assertInvalid(value -> value.add("extension", new JsonObject()));
        assertInvalid(value -> value.add("runtime_versions", new JsonArray()));
        assertInvalid(
                value ->
                        value.getAsJsonObject("runtime_versions")
                                .addProperty("\u00a0", "invalid component"));
        assertInvalid(value -> value.getAsJsonObject("runtime_versions").addProperty("java", 21));
        assertInvalid(value -> value.getAsJsonObject("limits").remove("max_hierarchy_objects"));
        assertInvalid(value -> value.getAsJsonObject("limits").addProperty("unknown", 1));
        assertInvalid(
                value -> value.getAsJsonObject("limits").addProperty("max_hierarchy_objects", 0));
        assertInvalid(
                value -> value.getAsJsonObject("limits").addProperty("max_hierarchy_objects", 1.5));
        assertInvalid(
                value ->
                        value.getAsJsonObject("limits")
                                .addProperty("max_hierarchy_objects", 9_007_199_254_740_992L));
    }

    private static void assertInvalid(Consumer<JsonObject> mutation) throws IOException {
        JsonObject value = testCase().getAsJsonObject("value").deepCopy();
        mutation.accept(value);
        assertThrows(
                IllegalArgumentException.class,
                () -> BuildIdentityCodec.parseClientBuildIdentity(value));
    }

    private static JsonObject testCase() throws IOException {
        try (var stream =
                ClientBuildIdentityCodecTest.class.getResourceAsStream(
                        "/contracts/client-build-identity-v1.json")) {
            if (stream == null) {
                throw new IOException("missing client-build-identity-v1.json");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes())
                    .getAsJsonObject()
                    .getAsJsonObject("case");
        }
    }
}
