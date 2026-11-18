package qupath.ext.histoforge.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Publishes local state by atomic replacement. On POSIX filesystems, temporary files are created
 * with owner-only permissions, and existing private files are restricted before use.
 *
 * <p>On non-POSIX systems (Windows) both methods fall back to default filesystem permissions.
 *
 * <p>Used for deployment and cloud credentials. These values must not be readable or replaceable by
 * other local users on multi-user machines. Project-scoped recovery and destination export files
 * reuse the same atomic-publication rule through their own domain owners.
 */
public final class SecureFileStore {
    private static final int TRANSIENT_ACCESS_DENIED_RETRIES = 50;
    private static final long MOVE_RETRY_DELAY_MILLIS = 10;
    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private SecureFileStore() {}

    /**
     * Write {@code content} to {@code file} atomically with POSIX 600 permissions on POSIX systems,
     * or default permissions on non-POSIX systems. The sibling temp file is created with {@link
     * PosixFilePermissions#asFileAttribute}, so it is never visible on disk at a looser permission.
     * All bytes are written and forced before the atomic replace. On failure the temp file is
     * deleted and the exception rethrown; there is no in-place fallback.
     */
    public static void writeOwnerOnly(Path file, String content) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        FileAttribute<?>[] attrs = ownerOnlyAttributes(file);
        Path tmp = Files.createTempFile(parent, "." + file.getFileName() + ".", ".tmp", attrs);
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            atomicReplace(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Enforce POSIX 600 on an existing private-user file and propagate failure to a security owner.
     * No-op on non-POSIX systems, where the current user's profile ACL is the platform boundary.
     */
    public static void enforceExistingOwnerOnly(Path file) throws IOException {
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Files.setPosixFilePermissions(file, OWNER_ONLY_PERMISSIONS);
        if (!Files.getPosixFilePermissions(file).equals(OWNER_ONLY_PERMISSIONS)) {
            throw new IOException("owner-only permissions were not applied to " + file);
        }
    }

    private static FileAttribute<?>[] ownerOnlyAttributes(Path file) {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS)
            };
        }
        return new FileAttribute<?>[0];
    }

    private static void atomicReplace(Path temporary, Path target) throws IOException {
        for (int retry = 0; ; retry++) {
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AccessDeniedException denied) {
                // Windows can briefly reject replacement while an ordinary reader still owns a
                // handle to the previous generation. Retrying the same atomic move preserves the
                // publication boundary; there is deliberately no in-place or non-atomic fallback.
                if (!isWindows() || retry >= TRANSIENT_ACCESS_DENIED_RETRIES) {
                    throw denied;
                }
                try {
                    Thread.sleep(MOVE_RETRY_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException failure =
                            new IOException(
                                    "interrupted while retrying atomic replacement of " + target,
                                    interrupted);
                    failure.addSuppressed(denied);
                    throw failure;
                }
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
