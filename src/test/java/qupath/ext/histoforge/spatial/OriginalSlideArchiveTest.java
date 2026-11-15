package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.SourceIdentityCodec.LocalFileMember;

class OriginalSlideArchiveTest {
    @TempDir Path root;

    @Test
    void storedArchivePreservesNamesEmptyFilesBytesAndExactSizeAcrossTimezones() throws Exception {
        var entry = root.resolve("tissue.vsi");
        Files.write(entry, new byte[] {1, 2, 3});
        var companion = root.resolve("_tissue_/level-é.ets");
        Files.createDirectory(companion.getParent());
        Files.write(companion, new byte[] {9, 8, 7, 6});
        var empty = Files.createFile(companion.resolveSibling("empty.dat"));
        var inventory =
                SlideFileInventory.capture(entry, List.of(companion, empty), Optional.empty());
        long size = OriginalSlideArchive.size(inventory.members());
        byte[] reference = null;
        var originalZone = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "Pacific/Honolulu", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var destination = root.resolve("archive-" + zone.replace('/', '-') + ".zip");
                var manifest = OriginalSlideArchive.write(inventory, destination, size, size);
                assertEquals(size, Files.size(destination));
                byte[] bytes = Files.readAllBytes(destination);
                if (reference == null) reference = bytes;
                else assertArrayEquals(reference, bytes);
                try (var archive = new ZipFile(destination.toFile())) {
                    assertEquals(
                            inventory.members().stream().map(LocalFileMember::path).toList(),
                            archive.stream().map(ZipEntry::getName).toList());
                    for (var member : manifest) {
                        var zipped = archive.getEntry(member.path());
                        assertEquals(ZipEntry.STORED, zipped.getMethod());
                        assertEquals(member.sizeBytes(), zipped.getSize());
                        try (var input = archive.getInputStream(zipped)) {
                            byte[] original = Files.readAllBytes(root.resolve(member.path()));
                            assertArrayEquals(original, input.readAllBytes());
                            assertEquals(
                                    HexFormat.of()
                                            .formatHex(
                                                    MessageDigest.getInstance("SHA-256")
                                                            .digest(original)),
                                    member.sha256());
                        }
                    }
                }
            }
        } finally {
            TimeZone.setDefault(originalZone);
        }
        inventory.requireCurrent();
    }

    @Test
    void failurePreservesSourcesAndPriorDestinationAndRemovesOnlyIncompleteFile() throws Exception {
        var entry = Files.write(root.resolve("tissue.mrxs"), new byte[] {1});
        var folder = Files.createDirectory(root.resolve("tissue"));
        var tile = Files.write(folder.resolve("data.dat"), new byte[] {2, 3});
        var inventory = SlideFileInventory.capture(entry, List.of(), Optional.of(folder));
        long size = OriginalSlideArchive.size(inventory.members());
        var output = Files.write(root.resolve("upload.zip"), new byte[] {4, 5});
        assertThrows(
                IOException.class,
                () -> OriginalSlideArchive.write(inventory, output, size, size - 1));
        assertThrows(
                IOException.class, () -> OriginalSlideArchive.write(inventory, tile, size, size));
        assertThrows(
                IOException.class,
                () ->
                        OriginalSlideArchive.write(
                                inventory, folder.resolve("upload.zip"), size, size));
        try {
            Thread.currentThread().interrupt();
            assertThrows(
                    CancellationException.class,
                    () -> OriginalSlideArchive.write(inventory, output, size, size));
        } finally {
            Thread.interrupted();
        }
        assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(output));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(entry));
        assertArrayEquals(new byte[] {2, 3}, Files.readAllBytes(tile));
        try (var paths = Files.list(root)) {
            assertEquals(
                    List.of("tissue", "tissue.mrxs", "upload.zip"),
                    paths.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void sizeIncludesZip64SizeOffsetAndEndRecordsWithoutAllocatingLargeFiles() {
        long maximum = 0xffffffffL;
        var large = new LocalFileMember("a", maximum, 0, Optional.empty());
        var small = new LocalFileMember("b", 1, 0, Optional.empty());
        // First member needs 20 extra local bytes and 20 central bytes; the second
        // member needs 12 central offset bytes; ZIP64 end records add 76 bytes.
        assertEquals(
                maximum + 1 + 2 * (30 + 1 + 46 + 1) + 20 + 20 + 12 + 22 + 76,
                OriginalSlideArchive.size(List.of(large, small)));
    }
}
