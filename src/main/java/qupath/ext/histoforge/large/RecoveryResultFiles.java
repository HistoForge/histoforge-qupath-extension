package qupath.ext.histoforge.large;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import qupath.ext.histoforge.contract.ArtifactRefCodec.ArtifactRef;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LocalFileRecord;

/**
 * Bounded storage of the backend's original result bytes inside project recovery. Publication is
 * serialized across this runtime's image workers: existing results and incomplete downloads
 * together must fit the project budget. No result is re-encoded.
 */
public final class RecoveryResultFiles {
    public static final long PROJECT_BUDGET_BYTES = 32L * 1024 * 1024 * 1024;
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;

    private final LargeActionHandleStore handles;
    private final long budgetBytes;
    private final AvailableSpace availableSpace;

    RecoveryResultFiles(LargeActionHandleStore handles) {
        this(handles, PROJECT_BUDGET_BYTES, path -> Files.getFileStore(path).getUsableSpace());
    }

    RecoveryResultFiles(
            LargeActionHandleStore handles, long budgetBytes, AvailableSpace availableSpace) {
        this.handles = handles;
        this.budgetBytes =
                CanonicalCodec.requirePositiveSafeInteger(budgetBytes, "result_file_budget");
        this.availableSpace = availableSpace;
    }

    /** Stable names permit adoption after publication succeeded but handle saving did not. */
    public static LocalFileRecord record(
            LargeActionHandle handle, String runId, ArtifactRef artifact) {
        String run = CanonicalCodec.requireCanonicalUuid(runId, "model_run_id");
        String extension =
                artifact.mediaType().equals("application/json") ? ".json" : ".geojsonseq.gz";
        return new LocalFileRecord(
                directory(handle) + "/" + run + "/" + artifact.artifactId() + extension,
                artifact.sizeBytes(),
                artifact.sha256());
    }

    static String directory(LargeActionHandle handle) {
        return "results/" + handle.actionId() + "/" + handle.handleGenerationId();
    }

    /** Cheap Jobs display hint. Recovery still validates hashes and contents before using bytes. */
    boolean appearsAvailable(LocalFileRecord expected) throws IOException {
        requireResultPath(expected);
        Optional<Path> existing = handles.findRegularFile(expected.projectRelativePosixPath());
        if (existing.isEmpty()) return false;
        try {
            return Files.size(existing.orElseThrow()) == expected.sizeBytes();
        } catch (java.nio.file.NoSuchFileException missing) {
            return false; // Confirmed-save cleanup may have removed it after the presence check.
        }
    }

    /**
     * Missing or altered bytes are repairable; unsafe paths and ordinary I/O failures are not
     * hidden.
     */
    public synchronized <T> Optional<T> read(LocalFileRecord expected, Decoder<T> decoder)
            throws IOException {
        requireResultPath(expected);
        Optional<Path> existing = handles.findRegularFile(expected.projectRelativePosixPath());
        if (existing.isEmpty()) return Optional.empty();
        Path path = existing.orElseThrow();
        if (Files.size(path) != expected.sizeBytes()
                || !LargeActionHandleStore.sha256(path).equals(expected.sha256()))
            return Optional.empty();
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(decoder.decode(input));
        }
    }

    /**
     * Stream the exact representation to a forced staging file, validate it, then publish
     * atomically. A failed download never overwrites an existing result. Its own incomplete file is
     * disposable.
     */
    public synchronized <T> T retain(
            LocalFileRecord expected, InputStream source, Decoder<T> decoder) throws IOException {
        requireResultPath(expected);
        Path target = handles.prepareFilePath(expected.projectRelativePosixPath());
        Path staging = handles.prepareFilePath(expected.projectRelativePosixPath() + ".download");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recovery result is not a regular file");
        }
        // This exact path belongs to the same artifact, not another worker's download.
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Incomplete recovery result is not a regular file");
        }
        Files.deleteIfExists(staging);
        requireCapacity(expected.sizeBytes());
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            staging,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                long written = 0;
                int count;
                while ((count = source.read(buffer)) != -1) {
                    written = Math.addExact(written, count);
                    if (written > expected.sizeBytes())
                        throw new IOException("Result exceeds its declared size");
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                    while (bytes.hasRemaining()) channel.write(bytes);
                }
                if (written != expected.sizeBytes())
                    throw new IOException("Result download is incomplete");
                channel.force(true);
            }
            if (!LargeActionHandleStore.sha256(staging).equals(expected.sha256())) {
                throw new IOException("Result download does not match its SHA-256");
            }
            T decoded;
            try (InputStream input = Files.newInputStream(staging, LinkOption.NOFOLLOW_LINKS)) {
                decoded = decoder.decode(input);
            }
            Files.move(
                    staging,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return decoded;
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private void requireCapacity(long incomingBytes) throws IOException {
        long used = resultBytes();
        if (incomingBytes > budgetBytes - used
                || incomingBytes > availableSpace.bytes(handles.root())) {
            // A batch can save earlier targets while later targets still need download space.
            // Confirmed saves no longer need these bytes; uncertain saves must keep them.
            handles.cleanupSavedResultFiles();
            used = resultBytes();
        }
        if (incomingBytes > budgetBytes - used) {
            throw new CapacityException(
                    "Local recovery results have reached the project storage limit. "
                            + "Save or dismiss other completed analyses, then continue this analysis.");
        }
        if (incomingBytes > availableSpace.bytes(handles.root())) {
            throw new CapacityException(
                    "Not enough free disk space to retain this result. "
                            + "Free disk space, then continue this analysis.");
        }
    }

    private long resultBytes() throws IOException {
        long used = 0;
        Path results = handles.root().resolve("results");
        try (var paths = Files.walk(results)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path))
                    throw new IOException("Recovery results contain a symbolic link");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    used = Math.addExact(used, Files.size(path));
                } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Recovery results contain an unsupported file");
                }
            }
        }
        return used;
    }

    private static void requireResultPath(LocalFileRecord file) {
        if (!file.projectRelativePosixPath().startsWith("results/")) {
            throw new IllegalArgumentException(
                    "Result files must be inside the project recovery results directory");
        }
    }

    /** An actionable storage failure suspends recovery; it does not invalidate the run. */
    public static final class CapacityException extends IOException {
        CapacityException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface Decoder<T> {
        T decode(InputStream input) throws IOException;
    }

    @FunctionalInterface
    interface AvailableSpace {
        long bytes(Path path) throws IOException;
    }
}
