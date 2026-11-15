package qupath.ext.histoforge.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.ChunkRange;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactStartRequest;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion.SourceVersionChangedException;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;

/** Forced local request persistence and bounded exact-range reads for source publication. */
final class SourceArtifactLocalFiles {

    private static final int HASH_BUFFER_BYTES = 8 * 1024 * 1024;

    private SourceArtifactLocalFiles() {}

    static void publishCanonicalStart(Path file, byte[] bytes) throws IOException {
        Path target = normalized(file, "startRequestFile");
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("source start request requires a parent directory");
        }
        Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(target) != bytes.length
                    || !Arrays.equals(readExactSize(target, bytes.length), bytes)) {
                throw new IOException("source start request path contains different state");
            }
            return;
        }
        Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static SourceArtifactStartRequest readCanonicalStart(Path file, long maxBytes)
            throws IOException {
        Path path = normalized(file, "startRequestFile");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("source start request is missing or is not a regular file");
        }
        long size = Files.size(path);
        if (size <= 0 || size > maxBytes || size >= Integer.MAX_VALUE) {
            throw new IOException("source start request exceeds its captured byte limit");
        }
        byte[] bytes = readExactSize(path, Math.toIntExact(size));
        try {
            SourceArtifactStartRequest start =
                    SourceArtifactCodec.parseStartRequest(CanonicalCodec.parseJson(bytes));
            byte[] canonical = CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(start));
            if (!Arrays.equals(bytes, canonical)) {
                throw new IOException("source start request is not canonical JSON");
            }
            return start;
        } catch (IllegalArgumentException error) {
            throw new IOException("source start request is invalid", error);
        }
    }

    static void requireExactSourceBytes(PreparedSourceArtifact source) throws IOException {
        if (!sha256Stable(source.path(), source.startRequest())
                .equals(source.sealRequest().bytesDigest())) {
            if (source.startRequest().container()
                    == SourceArtifactCodec.SourceContainer.SLIDE_FILE) {
                throw new SourceVersionChangedException(
                        "original source bytes changed after preparation");
            }
            throw new ModelGatewayExecutor.PreparedSourceCorruptException(
                    "owned upload bytes changed after preparation");
        }
    }

    static String sha256Stable(Path path, SourceArtifactStartRequest start) throws IOException {
        requireLength(path, start);
        try {
            requireOriginalLocalVersion(path, start);
            String digest = sha256(path);
            requireOriginalLocalVersion(path, start);
            return digest;
        } catch (NoSuchFileException error) {
            throw changedSource(start, "source artifact disappeared while it was hashed");
        }
    }

    static void requireLength(Path path, SourceArtifactStartRequest start) throws IOException {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != start.totalBytes()) {
                throw changedSource(
                        start, "source artifact length does not match its start request");
            }
        } catch (NoSuchFileException error) {
            throw changedSource(start, "source artifact disappeared before transfer");
        }
    }

    static byte[] readRange(Path path, SourceArtifactStartRequest start, ChunkRange range)
            throws IOException {
        requireLength(path, start);
        ByteBuffer buffer = ByteBuffer.allocate(range.size());
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long position = range.offset();
            while (buffer.hasRemaining()) {
                int count = channel.read(buffer, position);
                if (count < 0) {
                    throw changedSource(start, "source artifact ended inside a canonical chunk");
                }
                position += count;
            }
        } catch (NoSuchFileException error) {
            throw changedSource(start, "source artifact disappeared during chunk transfer");
        }
        return buffer.array();
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireOriginalLocalVersion(Path path, SourceArtifactStartRequest start)
            throws IOException {
        if (start.container() != SourceArtifactCodec.SourceContainer.SLIDE_FILE) {
            return;
        }
        var original = (SourceArtifactCodec.OriginalWsi) start.origin();
        SourceIdentityCodec.SourceContentVersion version =
                original.source().contentVersion().orElseThrow();
        if (!(version instanceof SourceIdentityCodec.LocalFile expected)) {
            throw new IOException(
                    "original source recovery requires a supported local-file version");
        }
        LocalFileSourceVersion.requireCurrent(path, expected);
    }

    private static IOException changedSource(SourceArtifactStartRequest start, String message) {
        if (start.container() == SourceArtifactCodec.SourceContainer.SLIDE_FILE) {
            return new SourceVersionChangedException(message);
        }
        return new ModelGatewayExecutor.PreparedSourceCorruptException(message);
    }

    private static byte[] readExactSize(Path path, int expected) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(Math.addExact(expected, 1));
            if (bytes.length != expected) {
                throw new IOException("local recovery file changed while it was read");
            }
            return bytes;
        }
    }

    static Path normalized(Path path, String field) {
        return Objects.requireNonNull(path, field).toAbsolutePath().normalize();
    }
}
