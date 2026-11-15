package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion.SourceVersionChangedException;

class SlideFileInventoryTest {
    @TempDir Path root;

    @Test
    void entryOnlyUsesLocalFileWhileCompanionsUseOneCanonicalMemberSet() throws Exception {
        var entry = Files.write(root.resolve("image.vsi"), new byte[] {1});
        var single = SlideFileInventory.capture(entry, List.of(entry), Optional.empty());
        assertFalse(single.packaged());
        assertInstanceOf(SourceIdentityCodec.LocalFile.class, single.contentVersion());
        var companion = Files.createFile(root.resolve("image.ets"));
        var set =
                SlideFileInventory.capture(
                        entry, List.of(companion, entry, companion), Optional.empty());
        assertTrue(set.packaged());
        assertEquals(
                List.of("image.ets", "image.vsi"),
                set.members().stream().map(SourceIdentityCodec.LocalFileMember::path).toList());
        assertInstanceOf(SourceIdentityCodec.LocalFileSet.class, set.contentVersion());
        set.requireCurrent();
    }

    @Test
    void detectsChangedMissingAndNewCompanionsWithoutReopeningReader() throws Exception {
        var entry = Files.write(root.resolve("image.mrxs"), new byte[] {1});
        var directory = Files.createDirectory(root.resolve("image"));
        var tile = Files.write(directory.resolve("tile.dat"), new byte[] {2});
        var beforeChange = SlideFileInventory.capture(entry, List.of(), Optional.of(directory));
        Files.write(tile, new byte[] {2, 3});
        assertThrows(SourceVersionChangedException.class, beforeChange::requireCurrent);
        var beforeDelete = SlideFileInventory.capture(entry, List.of(), Optional.of(directory));
        Files.delete(tile);
        assertThrows(SourceVersionChangedException.class, beforeDelete::requireCurrent);
        Files.write(tile, new byte[] {2});
        var beforeAdd = SlideFileInventory.capture(entry, List.of(), Optional.of(directory));
        var previousTime = Files.getLastModifiedTime(directory);
        Files.write(directory.resolve("extra.dat"), new byte[] {3});
        Files.setLastModifiedTime(directory, FileTime.fromMillis(previousTime.toMillis() + 2000));
        assertThrows(SourceVersionChangedException.class, beforeAdd::requireCurrent);
    }

    @Test
    void allowsAnAliasedContainingDirectoryButNotLinksInsideTheFileSet() throws Exception {
        var directory = Files.createDirectory(root.resolve("real"));
        var alias = Files.createSymbolicLink(root.resolve("alias"), directory.toRealPath());
        var entry = Files.write(directory.resolve("image.vsi"), new byte[] {1});
        var companion = Files.write(directory.resolve("image.ets"), new byte[] {2});
        var captured =
                SlideFileInventory.capture(
                        alias.resolve("image.vsi"),
                        List.of(
                                companion.toRealPath(),
                                alias.resolve("image.ets"),
                                entry.toRealPath()),
                        Optional.empty());
        assertEquals(2, captured.members().size());
        captured.requireCurrent();
        Files.createSymbolicLink(directory.resolve("link.ets"), companion.toRealPath());
        assertThrows(
                SourceVersionChangedException.class,
                () ->
                        SlideFileInventory.capture(
                                alias.resolve("image.vsi"),
                                List.of(alias.resolve("link.ets")),
                                Optional.empty()));
    }

    @Test
    void rejectsOutsideDependenciesAndLinksAndBoundsDirectoryDiscovery() throws Exception {
        var folder = Files.createDirectory(root.resolve("slide"));
        var entry = Files.write(folder.resolve("image.vsi"), new byte[] {1});
        var outside = Files.write(root.resolve("outside.ets"), new byte[] {2});
        assertThrows(
                IOException.class,
                () -> SlideFileInventory.capture(entry, List.of(outside), Optional.empty()));
        var link = Files.createSymbolicLink(folder.resolve("link.ets"), outside);
        assertThrows(
                SourceVersionChangedException.class,
                () -> SlideFileInventory.capture(entry, List.of(link), Optional.empty()));
        Files.delete(link);
        var companions = Files.createDirectory(folder.resolve("companions"));
        for (int i = 0; i < 256; i++) Files.createFile(companions.resolve("tile-" + i));
        assertThrows(
                IOException.class,
                () -> SlideFileInventory.capture(entry, List.of(), Optional.of(companions)));
    }
}
