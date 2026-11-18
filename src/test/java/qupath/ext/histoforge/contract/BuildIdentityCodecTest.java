package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildIdentityCodecTest {

    @Test
    void sharedFileSetFixturesHaveExactOrderBytesAndDigest() throws IOException {
        JsonObject fixture = fixture();
        for (var item : fixture.getAsJsonArray("fileSetCases")) {
            JsonObject testCase = item.getAsJsonObject();
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (var fileValue : testCase.getAsJsonArray("files")) {
                JsonObject file = fileValue.getAsJsonObject();
                files.put(
                        file.get("path").getAsString(),
                        file.get("utf8").getAsString().getBytes(StandardCharsets.UTF_8));
            }

            JsonObject manifest = BuildIdentityCodec.fileSetManifest(files);
            assertEquals(testCase.get("manifest"), manifest, name(testCase));
            assertEquals(
                    testCase.get("canonical").getAsString(),
                    new String(CanonicalCodec.canonicalBytes(manifest), StandardCharsets.UTF_8),
                    name(testCase));
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    BuildIdentityCodec.fileSetDigest(files),
                    name(testCase));
        }
    }

    @Test
    void sharedBuildManifestAndIdentityFixturesAreExact() throws IOException {
        for (var item : fixture().getAsJsonArray("manifestCases")) {
            JsonObject testCase = item.getAsJsonObject();
            BuildIdentityCodec.BuildManifest manifest =
                    BuildIdentityCodec.parseBuildManifest(testCase.get("value"));
            JsonObject manifestJson = BuildIdentityCodec.toJson(manifest);
            assertEquals(
                    testCase.get("canonical").getAsString(),
                    new String(CanonicalCodec.canonicalBytes(manifestJson), StandardCharsets.UTF_8),
                    name(testCase));
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    BuildIdentityCodec.buildManifestDigest(manifest),
                    name(testCase));

            BuildIdentityCodec.SoftwareBuildIdentity identity =
                    BuildIdentityCodec.parseSoftwareBuildIdentity(testCase.get("identity"));
            assertEquals(
                    identity,
                    BuildIdentityCodec.softwareBuildIdentity(
                            identity.version(), identity.artifactDigest(), manifest),
                    name(testCase));
            JsonObject identityJson = BuildIdentityCodec.toJson(identity);
            assertEquals(
                    testCase.get("identityCanonical").getAsString(),
                    new String(CanonicalCodec.canonicalBytes(identityJson), StandardCharsets.UTF_8),
                    name(testCase));
            assertEquals(
                    testCase.get("identitySha256").getAsString(),
                    CanonicalCodec.canonicalDigest(identityJson),
                    name(testCase));
        }
    }

    @Test
    void sharedInvalidBuildValuesFailClosed() throws IOException {
        JsonObject fixture = fixture();
        for (var item : fixture.getAsJsonArray("invalidManifestCases")) {
            JsonObject testCase = item.getAsJsonObject();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BuildIdentityCodec.parseBuildManifest(testCase.get("value")),
                    name(testCase));
        }
        for (var item : fixture.getAsJsonArray("invalidIdentityCases")) {
            JsonObject testCase = item.getAsJsonObject();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BuildIdentityCodec.parseSoftwareBuildIdentity(testCase.get("value")),
                    name(testCase));
        }
        for (var item : fixture.getAsJsonArray("invalidPaths")) {
            String path = item.getAsString();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> BuildIdentityCodec.fileSetManifest(Map.of(path, new byte[0])),
                    path);
        }
    }

    private static String name(JsonObject testCase) {
        return testCase.get("name").getAsString();
    }

    private static JsonObject fixture() throws IOException {
        try (var stream =
                BuildIdentityCodecTest.class.getResourceAsStream(
                        "/contracts/build-identities-v1.json")) {
            if (stream == null) {
                throw new IOException("missing build-identities-v1.json");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
