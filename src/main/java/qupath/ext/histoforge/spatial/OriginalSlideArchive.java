package qupath.ext.histoforge.spatial;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import qupath.ext.histoforge.contract.SlideFileSetCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;

/** Deterministic bounded STORED ZIP transport; owns only its incomplete destination. */
final class OriginalSlideArchive {
    private static final long ZIP32_MAX = 0xffffffffL;
    private static final int BUFFER_BYTES = 1024 * 1024;
    // Exactly midnight Jan 1 is a JDK sentinel that adds timezone-dependent timestamp extras.
    private static final LocalDateTime FIXED_DOS_TIME = LocalDateTime.of(1980, 1, 2, 0, 0);

    private OriginalSlideArchive() {}

    /** Exact JDK STORED archive size, including UTF-8 names and required ZIP64 fields. */
    static long size(List<SourceIdentityCodec.LocalFileMember> members) {
        long offset = 0;
        long directory = 0;
        for (var member : members) {
            int nameBytes = member.path().getBytes(StandardCharsets.UTF_8).length;
            boolean largeSize = member.sizeBytes() >= ZIP32_MAX;
            int centralZip64 = (largeSize ? 16 : 0) + (offset >= ZIP32_MAX ? 8 : 0);
            directory =
                    Math.addExact(
                            directory,
                            46L + nameBytes + (centralZip64 == 0 ? 0 : centralZip64 + 4));
            offset =
                    Math.addExact(
                            offset,
                            Math.addExact(
                                    member.sizeBytes(), 30L + nameBytes + (largeSize ? 20 : 0)));
        }
        return Math.addExact(
                offset,
                directory + 22 + ((offset >= ZIP32_MAX || directory >= ZIP32_MAX) ? 76 : 0));
    }

    static List<SlideFileSetCodec.Member> write(
            SlideFileInventory files, Path destination, long expectedSize, long limit)
            throws IOException {
        files.requireCurrent();
        if (!files.packaged() || size(files.members()) != expectedSize || expectedSize > limit)
            throw new IOException("original archive exceeds or differs from its captured size");
        Path requestedOutput = destination.toAbsolutePath().normalize();
        Path existingParent = requestedOutput.getParent();
        while (!Files.exists(existingParent, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            existingParent = existingParent.getParent();
        // Resolve containing-directory aliases before checking protected paths, and
        // before creating any new directory that could change source membership.
        Path output =
                existingParent.toRealPath().resolve(existingParent.relativize(requestedOutput));
        if (files.members().stream()
                        .anyMatch(member -> output.equals(files.root().resolve(member.path())))
                || files.membershipDirectory().stream().anyMatch(output::startsWith))
            throw new IOException("original archive destination overlaps protected source members");
        Files.createDirectories(output.getParent());
        Path incomplete =
                Files.createTempFile(
                        output.getParent(), "." + output.getFileName() + ".", ".incomplete");
        var members = new ArrayList<SlideFileSetCodec.Member>();
        byte[] buffer = new byte[BUFFER_BYTES];
        try {
            try (var zip =
                    new ZipOutputStream(
                            new BufferedOutputStream(
                                    Files.newOutputStream(incomplete), BUFFER_BYTES),
                            StandardCharsets.UTF_8)) {
                for (var member : files.members()) {
                    Path source = files.root().resolve(member.path());
                    // The JDK requires CRC before a STORED entry. Hash the actual second-pass
                    // bytes while copying; no decoder, pixel conversion or archive framework.
                    CRC32 crc = new CRC32();
                    copyMember(
                            source,
                            member.sizeBytes(),
                            buffer,
                            (bytes, count) -> crc.update(bytes, 0, count));
                    var entry = new ZipEntry(member.path());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setTimeLocal(FIXED_DOS_TIME);
                    entry.setSize(member.sizeBytes());
                    entry.setCompressedSize(member.sizeBytes());
                    entry.setCrc(crc.getValue());
                    zip.putNextEntry(entry);
                    var digest = sha256();
                    copyMember(
                            source,
                            member.sizeBytes(),
                            buffer,
                            (bytes, count) -> {
                                zip.write(bytes, 0, count);
                                digest.update(bytes, 0, count);
                            });
                    zip.closeEntry();
                    members.add(
                            new SlideFileSetCodec.Member(
                                    member.path(),
                                    member.sizeBytes(),
                                    HexFormat.of().formatHex(digest.digest())));
                }
            }
            files.requireCurrent();
            if (Files.size(incomplete) != expectedSize)
                throw new IOException("original ZIP size differs from the captured plan");
            try (var channel = FileChannel.open(incomplete, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(incomplete, output, StandardCopyOption.ATOMIC_MOVE);
            return List.copyOf(members);
        } finally {
            Files.deleteIfExists(incomplete);
        }
    }

    private static void copyMember(Path path, long size, byte[] buffer, BlockConsumer consumer)
            throws IOException {
        long readBytes = 0;
        try (var input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException(
                            "slide packaging cancelled");
                readBytes = Math.addExact(readBytes, count);
                if (readBytes > size) throw new IOException("slide member grew during packaging");
                consumer.accept(buffer, count);
            }
        }
        if (readBytes != size) throw new IOException("slide member changed during packaging");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @FunctionalInterface
    private interface BlockConsumer {
        void accept(byte[] bytes, int count) throws IOException;
    }
}
