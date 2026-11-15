package qupath.ext.histoforge.spatial;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import qupath.ext.histoforge.contract.SlideFileSetCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;

/** Frozen local dependencies. Capturing versions never hashes or decodes slide pixels. */
public record SlideFileInventory(
        Path root,
        String entryPath,
        List<SourceIdentityCodec.LocalFileMember> members,
        Optional<Path> membershipDirectory,
        Map<Path, BasicFileAttributes> directoryVersions) {
    public SlideFileInventory(
            Path root,
            String entryPath,
            List<SourceIdentityCodec.LocalFileMember> members,
            Optional<Path> membershipDirectory) {
        this(root, entryPath, members, membershipDirectory, Map.of());
        if (membershipDirectory.isPresent())
            throw new IllegalArgumentException(
                    "directory membership requires captured directory versions");
    }

    public SlideFileInventory {
        root = root.toAbsolutePath().normalize();
        entryPath = SlideFileSetCodec.relativePath(entryPath);
        members = new SourceIdentityCodec.LocalFileSet(members).members();
        String entry = entryPath;
        if (members.stream()
                .noneMatch(member -> member.path().equals(entry) && member.sizeBytes() > 0))
            throw new IllegalArgumentException("slide inventory requires its nonempty entry file");
        membershipDirectory = membershipDirectory.map(path -> path.toAbsolutePath().normalize());
        directoryVersions = Map.copyOf(directoryVersions);
    }

    /** Resolve reader-discovered dependencies relative to the entry's directory, unchanged. */
    public static SlideFileInventory capture(
            Path entry, List<Path> dependencies, Optional<Path> membershipDirectory)
            throws IOException {
        Path lexicalEntry = entry.toAbsolutePath().normalize();
        Path lexicalRoot = lexicalEntry.getParent();
        Path root = lexicalRoot.toRealPath();
        Path file = root.resolve(lexicalEntry.getFileName());
        // Resolve aliases of the containing directory (for example macOS /var), not
        // member links. captureMember still rejects links inside the slide file set.
        var paths = new java.util.LinkedHashSet<Path>();
        for (Path dependency : dependencies)
            paths.add(resolveDependency(dependency, lexicalRoot, root));
        paths.add(file);
        Optional<Path> companionDirectory = Optional.empty();
        var directories = new LinkedHashMap<Path, BasicFileAttributes>();
        if (membershipDirectory.isPresent()) {
            Path directory =
                    resolveDependency(membershipDirectory.orElseThrow(), lexicalRoot, root);
            companionDirectory = Optional.of(directory);
            var companions = new ArrayList<Path>();
            scanDirectory(directory, companions, directories);
            paths.addAll(companions);
        }
        if (paths.size() > SlideFileSetCodec.MAX_MEMBERS)
            throw new IOException("too many slide dependencies");
        var members = new ArrayList<SourceIdentityCodec.LocalFileMember>();
        for (Path path : paths) {
            String relative =
                    root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
            members.add(captureMember(root, relative));
        }
        var inventory =
                new SlideFileInventory(
                        root,
                        file.getFileName().toString(),
                        members,
                        companionDirectory,
                        directories);
        inventory.requireCurrent();
        return inventory;
    }

    private static Path resolveDependency(Path dependency, Path lexicalRoot, Path root)
            throws IOException {
        Path path = dependency.toAbsolutePath().normalize();
        Path base = path.startsWith(lexicalRoot) ? lexicalRoot : root;
        if (!path.startsWith(base) || path.equals(base))
            throw new IOException("slide dependency is outside the entry directory: " + path);
        return root.resolve(base.relativize(path));
    }

    /** MRXS owns its entry plus complete same-stem directory; do not guess referenced tiles. */
    private static void scanDirectory(
            Path directory, List<Path> files, Map<Path, BasicFileAttributes> versions)
            throws IOException {
        var attributes =
                Files.readAttributes(
                        directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory())
            throw new LocalFileSourceVersion.SourceVersionChangedException(
                    "slide companion directory is missing or is a link: " + directory);
        if (versions.size() >= SlideFileSetCodec.MAX_MEMBERS)
            throw new IOException("too many slide companion directories");
        // Capture before enumeration, then recheck after discovery. Later FX checks only stat
        // this bounded list; they never enumerate directories or reopen a reader.
        versions.put(directory, attributes);
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    scanDirectory(path, files, versions);
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new LocalFileSourceVersion.SourceVersionChangedException(
                            "slide companion is not a regular file: " + path);
                files.add(path);
                if (files.size() >= SlideFileSetCodec.MAX_MEMBERS)
                    throw new IOException("too many slide companions");
            }
        }
    }

    public boolean packaged() {
        return members.size() != 1 || !members.getFirst().path().equals(entryPath);
    }

    public SourceIdentityCodec.SourceContentVersion contentVersion() {
        if (packaged()) return new SourceIdentityCodec.LocalFileSet(members);
        var entry = members.getFirst();
        return new SourceIdentityCodec.LocalFile(
                entry.sizeBytes(), entry.lastModifiedEpochMillis(), entry.fileKey());
    }

    public void requireCurrent() throws IOException {
        for (var expected : members) {
            SourceIdentityCodec.LocalFileMember actual;
            try {
                actual = captureMember(root, expected.path());
            } catch (NoSuchFileException | NotDirectoryException missing) {
                throw new LocalFileSourceVersion.SourceVersionChangedException(
                        "slide companion disappeared: " + expected.path());
            }
            if (actual.sizeBytes() != expected.sizeBytes()
                    || actual.lastModifiedEpochMillis() != expected.lastModifiedEpochMillis()
                    || (expected.fileKey().isPresent()
                            && !actual.fileKey().equals(expected.fileKey())))
                throw new LocalFileSourceVersion.SourceVersionChangedException(
                        "slide companion changed: " + expected.path());
        }
        for (var directory : directoryVersions.entrySet()) {
            BasicFileAttributes actual;
            try {
                actual =
                        Files.readAttributes(
                                directory.getKey(),
                                BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException | NotDirectoryException missing) {
                throw new LocalFileSourceVersion.SourceVersionChangedException(
                        "slide companion directory disappeared: " + directory.getKey());
            }
            var expected = directory.getValue();
            if (!actual.isDirectory()
                    || !actual.lastModifiedTime().equals(expected.lastModifiedTime())
                    || expected.fileKey() != null && !expected.fileKey().equals(actual.fileKey()))
                throw new LocalFileSourceVersion.SourceVersionChangedException(
                        "slide companion membership changed: " + directory.getKey());
        }
    }

    private static SourceIdentityCodec.LocalFileMember captureMember(Path root, String relative)
            throws IOException {
        SlideFileSetCodec.relativePath(relative);
        Path path = root;
        for (Path part : Path.of(relative)) {
            path = path.resolve(part);
            if (Files.isSymbolicLink(path))
                throw new LocalFileSourceVersion.SourceVersionChangedException(
                        "slide dependencies cannot contain links: " + relative);
        }
        var attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile())
            throw new LocalFileSourceVersion.SourceVersionChangedException(
                    "slide dependency is not a regular file: " + relative);
        return new SourceIdentityCodec.LocalFileMember(
                relative,
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
                Optional.ofNullable(attributes.fileKey()).map(Object::toString));
    }
}
