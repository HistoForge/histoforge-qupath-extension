package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3CredentialStoreTest {

    @Test
    void persistedJsonFieldNamesRemainStable(@TempDir Path dir) throws Exception {
        var store = new S3CredentialStore(dir);
        var config = new S3ConnectionConfig("start", "region", "bucket", "prefix");
        var token = new SsoTokenData("access", "refresh", 123, "client", "secret");
        store.saveConfig(config);
        store.saveSsoToken(token);
        assertEquals(
                "{\"ssoStartUrl\":\"start\",\"ssoRegion\":\"region\",\"bucket\":\"bucket\",\"keyPrefix\":\"prefix\"}",
                Files.readString(dir.resolve("config.json")));
        assertEquals(
                "{\"accessToken\":\"access\",\"refreshToken\":\"refresh\",\"expiresAtEpochSec\":123,\"clientId\":\"client\",\"clientSecret\":\"secret\"}",
                Files.readString(dir.resolve("sso_token.json")));
        assertEquals(token, store.loadSsoToken().orElseThrow());
        assertEquals(config, store.loadConfig().orElseThrow());
    }

    @Test
    void loadSecuresExistingCredentialsBeforeUsingThem(@TempDir Path dir) throws Exception {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        S3CredentialStore store = new S3CredentialStore(dir);
        S3ConnectionConfig config =
                new S3ConnectionConfig(
                        "https://example.awsapps.com/start", "us-east-1", "slides", "study/");
        store.saveConfig(config);
        Path file = dir.resolve("config.json");
        Files.setPosixFilePermissions(
                file,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ));

        assertEquals(config, store.loadConfig().orElseThrow());
        assertEquals(
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
    }

    @Test
    void clearingLoginTokenPreservesAllConnectionFieldsAndCanRepeat(@TempDir Path dir)
            throws Exception {
        S3CredentialStore store = new S3CredentialStore(dir);
        S3ConnectionConfig config =
                new S3ConnectionConfig(
                        "https://example.awsapps.com/start", "us-west-2", "slides", "study/");
        store.saveConfig(config);
        Path token = dir.resolve("sso_token.json");
        Files.writeString(token, "test-token");

        store.clearSsoToken();
        store.clearSsoToken();

        assertEquals(config, store.loadConfig().orElseThrow());
        assertFalse(Files.exists(token));
    }

    @Test
    void failedTokenDeletionReportsFailureWithoutDeletingConnectionFields(@TempDir Path dir)
            throws Exception {
        S3CredentialStore store = new S3CredentialStore(dir);
        S3ConnectionConfig config =
                new S3ConnectionConfig(
                        "https://example.awsapps.com/start", "us-east-1", "slides", "study/");
        store.saveConfig(config);
        Path token = dir.resolve("sso_token.json");
        Files.createDirectories(token);
        Files.writeString(token.resolve("child"), "occupied");

        assertThrows(IOException.class, store::clearSsoToken);

        assertEquals(config, store.loadConfig().orElseThrow());
        assertTrue(Files.exists(token));
    }
}
