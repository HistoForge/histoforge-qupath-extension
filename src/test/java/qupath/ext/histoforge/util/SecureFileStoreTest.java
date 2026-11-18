package qupath.ext.histoforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failure-boundary tests for private, atomic local credential publication. */
class SecureFileStoreTest {

    @Test
    void writesCompletePrivateUtf8File(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("credentials.json");
        String content = "{\"token\":\"" + "é".repeat(600_000) + "\"}\n";

        SecureFileStore.writeOwnerOnly(file, content);

        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
        }
        assertNoTemporaryFiles(dir);
    }

    @Test
    void replacesAnExistingFile(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("credentials.json"), "old");

        SecureFileStore.writeOwnerOnly(file, "new");

        assertEquals("new", Files.readString(file));
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
        }
        assertNoTemporaryFiles(dir);
    }

    @Test
    void strictPermissionEnforcementTightensExistingPosixFile(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("deployment.json"), "state");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(
                    file,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.GROUP_READ));
        }

        SecureFileStore.enforceExistingOwnerOnly(file);

        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
        }
    }

    @Test
    void publicationFailurePreservesTargetAndCleansTemporaryFile(@TempDir Path dir)
            throws Exception {
        Path target = Files.createDirectory(dir.resolve("credentials.json"));
        Path sentinel = Files.writeString(target.resolve("old"), "unchanged");

        assertThrows(
                IOException.class, () -> SecureFileStore.writeOwnerOnly(target, "replacement"));

        assertTrue(Files.isDirectory(target));
        assertEquals("unchanged", Files.readString(sentinel));
        assertNoTemporaryFiles(dir);
    }

    private static void assertNoTemporaryFiles(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            assertTrue(
                    files.noneMatch(
                            path ->
                                    path.getFileName()
                                            .toString()
                                            .startsWith(".credentials.json.")));
        }
    }
}
