package qupath.ext.histoforge.spatial;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.contract.SourceIdentityCodec;

/** Exact filesystem check for one captured local source-content version. */
public final class LocalFileSourceVersion {

    /** The local file no longer has the exact content version captured for this action. */
    public static final class SourceVersionChangedException extends IOException {

        public SourceVersionChangedException(String message) {
            super(message);
        }
    }

    private LocalFileSourceVersion() {}

    /** Capture the current regular-file version used by one local source adapter. */
    public static SourceIdentityCodec.LocalFile capture(Path path) throws IOException {
        Path source = Objects.requireNonNull(path, "path").toRealPath();
        if (!Files.isRegularFile(source)) {
            throw new IOException("local source is not a regular file");
        }
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        return new SourceIdentityCodec.LocalFile(
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
                Optional.ofNullable(attributes.fileKey()).map(Object::toString));
    }

    /** Require the current regular file to retain its captured size, mtime, and optional key. */
    public static void requireCurrent(Path path, SourceIdentityCodec.LocalFile expected)
            throws IOException {
        Path source = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Objects.requireNonNull(expected, "expected");
        if (!Files.isRegularFile(source)) {
            throw new SourceVersionChangedException("original source is not a regular file");
        }
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(source, BasicFileAttributes.class);
        } catch (NoSuchFileException error) {
            throw new SourceVersionChangedException("original source is not a regular file");
        }
        Optional<String> actualKey =
                Optional.ofNullable(attributes.fileKey()).map(Object::toString);
        if (attributes.size() != expected.sizeBytes()
                || attributes.lastModifiedTime().toMillis() != expected.lastModifiedEpochMillis()
                || (expected.fileKey().isPresent() && !expected.fileKey().equals(actualKey))) {
            throw new SourceVersionChangedException("original source content version changed");
        }
    }
}
