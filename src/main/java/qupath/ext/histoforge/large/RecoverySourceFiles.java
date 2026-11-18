package qupath.ext.histoforge.large;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;

/** Locates prepared images and interrupted encodings in an existing project recovery store. */
public final class RecoverySourceFiles {
    private RecoverySourceFiles() {}

    public static String directory(LargeActionHandle handle) {
        return "actions/" + handle.actionId() + "/" + handle.handleGenerationId() + "/source";
    }

    /** Remember paths, not a QuPath project, viewer or callback that may already have closed. */
    public static Set<Path> directories(Path root, List<LargeActionHandle> handles) {
        Set<Path> directories = new LinkedHashSet<>();
        for (var handle : handles) {
            directories.add(root.resolve(directory(handle)));
            RunImageSources.sources(handle)
                    .flatMap(source -> source.ownedUploadFile().stream())
                    .forEach(
                            file -> {
                                directories.add(
                                        root.resolve(file.projectRelativePosixPath()).getParent());
                            });
        }
        return directories;
    }

    static Set<Path> unreferencedImages(
            LargeActionHandleStore store, LargeActionHandle handle, List<LargeActionHandle> all)
            throws IOException {
        var referenced =
                all.stream()
                        .flatMap(RunImageSources::sources)
                        .flatMap(source -> source.ownedUploadFile().stream())
                        .map(file -> store.root().resolve(file.projectRelativePosixPath()))
                        .collect(Collectors.toSet());
        var images =
                new LinkedHashSet<>(
                        imagesInDirectory(store.root(), store.root().resolve(directory(handle))));
        images.removeAll(referenced);
        return images;
    }

    /** Inspect only owned directory entries, without following a replaced parent directory. */
    public static Set<Path> imagesInDirectory(Path root, Path source) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Recovery root is not a real directory: " + root);
        Path current = root;
        boolean absent = false;
        for (Path part : root.relativize(source)) {
            current = current.resolve(part);
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                absent = true;
                break;
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException(
                        "Prepared-image directory is not a real directory: " + current);
        }
        return absent ? Set.of() : imagesInDirectory(source);
    }

    public static Set<Path> imagesInDirectory(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return Set.of();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Prepared-image directory is not a real directory: " + directory);
        try (var entries = Files.list(directory)) {
            return entries.filter(RecoverySourceFiles::isImageEntry).collect(Collectors.toSet());
        }
    }

    private static boolean isImageEntry(Path path) {
        String name = path.getFileName().toString();
        // A macOS metadata companion is not a prepared image or an interrupted encoding.
        if (name.startsWith("._")) return false;
        // The writer stages PNG in a temporary .png file and TIFF in a private directory.
        // A staging directory counts as one image, not as every encoder file it contains.
        return name.endsWith(".png")
                || name.endsWith(".ome.tif")
                || name.endsWith(".zip")
                || name.startsWith(".") && (name.contains(".ome.tif.") || name.contains(".zip."));
    }
}
