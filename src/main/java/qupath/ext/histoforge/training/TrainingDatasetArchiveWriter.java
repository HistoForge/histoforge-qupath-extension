package qupath.ext.histoforge.training;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streaming ZIP64 transport writer for one already-complete canonical package root. */
final class TrainingDatasetArchiveWriter {

    private static final int BUFFER_BYTES = 1024 * 1024;

    private TrainingDatasetArchiveWriter() {}

    static void write(Path packageRoot, List<String> memberPaths, Path destination)
            throws IOException {
        Path root = Objects.requireNonNull(packageRoot, "packageRoot").toAbsolutePath().normalize();
        Path output =
                Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        List<String> members = memberPaths.stream().sorted().toList();
        if (members.isEmpty() || members.size() != members.stream().distinct().count()) {
            throw new IllegalArgumentException(
                    "training archive members must be unique and nonempty");
        }
        byte[] buffer = new byte[BUFFER_BYTES];
        try (var zip =
                new ZipOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(output), BUFFER_BYTES))) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            for (String relative : members) {
                Path source = root.resolve(relative).normalize();
                if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                    throw new IOException("training archive member is missing: " + relative);
                }
                ZipEntry entry = new ZipEntry(relative);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                try (InputStream input =
                        new BufferedInputStream(Files.newInputStream(source), BUFFER_BYTES)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            zip.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }
}
