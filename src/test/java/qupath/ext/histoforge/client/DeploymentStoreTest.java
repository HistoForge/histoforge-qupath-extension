package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;

/** Exact persisted deployment credential and cached-store behavior. */
class DeploymentStoreTest {

    @Test
    void sharedCredentialFixtureRoundTripsExactly() throws Exception {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        for (var item : fixture.getAsJsonArray("validCases")) {
            JsonObject testCase = item.getAsJsonObject();
            JsonObject value = testCase.getAsJsonObject("value");
            Deployment deployment = Deployment.parse(value);
            assertEquals(value, deployment.toJson(), testCase.get("name").getAsString());
        }
    }

    @Test
    void sharedInvalidCredentialMutationsFailClosed() throws Exception {
        JsonObject fixture = fixture();
        JsonObject base =
                fixture()
                        .getAsJsonArray("validCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value");

        for (var item : fixture.getAsJsonArray("invalidMutations")) {
            JsonObject testCase = item.getAsJsonObject();
            JsonObject value = base.deepCopy();
            JsonArray path = testCase.getAsJsonArray("path");
            JsonObject parent = value;
            for (int index = 0; index < path.size() - 1; index++) {
                parent = parent.getAsJsonObject(path.get(index).getAsString());
            }
            String field = path.get(path.size() - 1).getAsString();
            if ("remove".equals(testCase.get("operation").getAsString())) {
                parent.remove(field);
            } else {
                parent.add(field, testCase.get("value").deepCopy());
            }
            try {
                Deployment.parse(value);
                throw new AssertionError(
                        "accepted invalid fixture: " + testCase.get("name").getAsString());
            } catch (IllegalArgumentException expected) {
                // Expected closed rejection.
            }
        }
    }

    @Test
    void storeLoadsEveryExactField(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deployment.json");
        JsonObject value =
                fixture()
                        .getAsJsonArray("validCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value");
        Files.writeString(file, value.toString());

        Deployment deployment = new DeploymentStore(file).load().orElseThrow();
        assertEquals(1, deployment.schemaVersion());
        assertEquals("deployment-2026-08-15-a", deployment.deploymentId());
        assertEquals(
                "https://research--histoforge-gateway-fastapi-app.modal.run",
                deployment.gatewayUrl());
        assertEquals("research-lab", deployment.modalProfile());
        assertEquals("histoforge-gateway", deployment.modalAppName());
        assertEquals(7, deployment.authRevision());
        assertEquals("secret-token_ABC-123", deployment.token());
        assertEquals("2026-08-15T12:34:56.789Z", deployment.updatedAt().toString());
    }

    @Test
    void storeSecuresAnExistingCredentialBeforeUsingIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deployment.json");
        assumeTrue(file.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.writeString(
                file,
                fixture()
                        .getAsJsonArray("validCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .toString());
        Files.setPosixFilePermissions(
                file,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ));

        assertTrue(new DeploymentStore(file).load().isPresent());
        assertEquals(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
    }

    @Test
    void missingOrInvalidFileIsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deployment.json");
        assertTrue(new DeploymentStore(file).load().isEmpty());

        Files.writeString(file, "{\"gateway_url\":\"https://gateway.example\"}");
        assertTrue(new DeploymentStore(file).load().isEmpty());
    }

    @Test
    void reloadPicksUpOnlyACompleteValidRotation(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("deployment.json");
        JsonObject first = credential("old", 1);
        Files.writeString(file, first.toString());
        var store = new DeploymentStore(file);

        assertEquals("old", store.load().orElseThrow().token());
        Files.writeString(file, credential("new", 2).toString());
        assertEquals("old", store.load().orElseThrow().token(), "load() is cached");
        assertEquals("new", store.reload().orElseThrow().token(), "reload() re-reads");

        Files.writeString(file, "{}");
        assertFalse(store.reload().isPresent());
    }

    private static JsonObject credential(String token, long revision) throws Exception {
        JsonObject value =
                fixture()
                        .getAsJsonArray("validCases")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        value.addProperty("token", token);
        value.addProperty("auth_revision", revision);
        return value;
    }

    private static JsonObject fixture() throws Exception {
        try (var stream =
                DeploymentStoreTest.class.getResourceAsStream(
                        "/contracts/local-deployment-credential-v1.json")) {
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
