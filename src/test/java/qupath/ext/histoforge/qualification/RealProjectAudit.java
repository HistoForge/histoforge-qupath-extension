package qupath.ext.histoforge.qualification;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.lib.io.PathIO;

/** Opt-in Phase 7 audit of persisted real-project hierarchies without opening image servers. */
public final class RealProjectAudit {

    private static final List<Path> PROBE_SOURCES =
            List.of(
                    Path.of(
                            "src/test/java/qupath/ext/histoforge/qualification/"
                                    + "RealProjectAudit.java"),
                    Path.of(
                            "src/test/java/qupath/ext/histoforge/qualification/"
                                    + "RealProjectHierarchySummary.java"),
                    Path.of(
                            "src/test/java/qupath/ext/histoforge/qualification/"
                                    + "RealProjectSourceSummary.java"));
    private static final List<Class<?>> PROBE_CLASS_ROOTS =
            List.of(
                    RealProjectAudit.class,
                    RealProjectHierarchySummary.class,
                    RealProjectSourceSummary.class);

    private RealProjectAudit() {}

    /** Read one project, prove it remained byte-identical, and write canonical audit evidence. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected PROJECT_DIRECTORY EVIDENCE_OUTPUT");
        }
        Path project = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        writeEvidence(project, output);
    }

    static void writeEvidence(Path project, Path output) throws Exception {
        Path target = evidenceTarget(project, output);
        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema_version", 1);
        evidence.addProperty("captured_at", Instant.now().toString());
        evidence.add("runtime", runtimeIdentity());
        evidence.add("project", auditProject(project));
        evidence.addProperty("evidence_digest", CanonicalCodec.canonicalDigest(evidence));

        publishEvidence(target, appendNewline(CanonicalCodec.canonicalBytes(evidence)));
        System.out.println(
                "PHASE7_REAL_PROJECT_AUDIT_JSON="
                        + new String(
                                CanonicalCodec.canonicalBytes(evidence), StandardCharsets.UTF_8));
    }

    private static Path evidenceTarget(Path project, Path output) throws IOException {
        Path projectReal = project.toRealPath();
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("evidence output parent must already exist");
        }
        Path parentReal = parent.toRealPath();
        if (parentReal.startsWith(projectReal)) {
            throw new IllegalArgumentException(
                    "evidence output must be outside the audited project");
        }
        return parentReal.resolve(output.getFileName());
    }

    private static void publishEvidence(Path target, byte[] content) throws IOException {
        Path temporary =
                Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
        try {
            Files.write(
                    temporary,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static JsonObject auditProject(Path project) throws IOException {
        Path normalized = project.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("project directory is missing: " + normalized);
        }
        ProjectSnapshot before = snapshot(normalized);
        List<Path> projectFiles = rootProjectFiles(normalized);
        if (projectFiles.size() != 1) {
            throw new IOException("project must contain exactly one root-level .qpproj file");
        }
        FileIdentity projectFileIdentity =
                snapshotIdentity(before, normalized, projectFiles.getFirst());
        Map<String, RealProjectSourceSummary> declaredEntries =
                projectEntries(projectFiles.getFirst());

        Path data = normalized.resolve("data");
        List<Path> dataFiles;
        if (!Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("project data directory is missing");
        }
        try (var paths = Files.list(data)) {
            dataFiles =
                    paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                            .map(path -> path.resolve("data.qpdata"))
                            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .sorted(
                                    Comparator.comparing(
                                            path -> path.getParent().getFileName().toString()))
                            .toList();
        }
        if (dataFiles.isEmpty()) {
            throw new IOException("project contains no persisted data.qpdata files");
        }
        Set<String> persistedEntryIds = new HashSet<>();
        for (Path dataFile : dataFiles) {
            persistedEntryIds.add(dataFile.getParent().getFileName().toString());
        }
        if (!declaredEntries.keySet().equals(persistedEntryIds)) {
            throw new IOException("project declarations and persisted data entries differ");
        }

        JsonArray entries = new JsonArray();
        RealProjectHierarchySummary totals = new RealProjectHierarchySummary();
        for (Path dataFile : dataFiles) {
            String entryId = dataFile.getParent().getFileName().toString();
            RealProjectHierarchySummary counts =
                    RealProjectHierarchySummary.audit(PathIO.readHierarchy(dataFile));
            totals.add(counts);
            JsonObject entry = counts.toJson();
            entry.addProperty("entry_id", entryId);
            entry.addProperty("qpdata_sha256", sha256File(dataFile));
            entry.addProperty("qpdata_size_bytes", Files.size(dataFile));
            entries.add(entry);
        }

        ProjectSnapshot after = snapshot(normalized);
        if (!before.equals(after)) {
            throw new IOException("project tree changed during read-only audit");
        }

        JsonObject result = new JsonObject();
        result.addProperty("project_file_sha256", projectFileIdentity.sha256());
        result.addProperty("project_file_size_bytes", projectFileIdentity.sizeBytes());
        result.addProperty("declared_image_entries", declaredEntries.size());
        result.addProperty("persisted_data_entries", persistedEntryIds.size());
        result.addProperty("declared_and_persisted_entry_ids_match", true);
        result.addProperty("regular_file_count", before.files().size());
        result.addProperty("regular_file_bytes", before.totalBytes());
        result.addProperty("project_tree_digest", before.digest());
        result.addProperty("read_only_project_tree_unchanged", true);
        result.addProperty("image_servers_opened", 0);
        result.addProperty("pixel_bytes_read", 0);
        result.add("entries", entries);
        result.add(
                "source_metadata_totals",
                RealProjectSourceSummary.totals(declaredEntries.values()));
        result.add("totals", totals.toJson());
        return result;
    }

    private static FileIdentity snapshotIdentity(ProjectSnapshot snapshot, Path project, Path file)
            throws IOException {
        String relative = project.relativize(file).toString().replace('\\', '/');
        return snapshot.files().stream()
                .filter(identity -> identity.path().equals(relative))
                .findFirst()
                .orElseThrow(
                        () -> new IOException("project file was absent from initial snapshot"));
    }

    private static List<Path> rootProjectFiles(Path project) throws IOException {
        try (var paths = Files.list(project)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".qpproj"))
                    .sorted()
                    .toList();
        }
    }

    private static Map<String, RealProjectSourceSummary> projectEntries(Path projectFile)
            throws IOException {
        JsonObject project;
        try {
            project = CanonicalCodec.parseJson(Files.readAllBytes(projectFile)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("project file is not strict JSON", error);
        }
        if (!project.has("images") || !project.get("images").isJsonArray()) {
            throw new IOException("project file has no images array");
        }
        Map<String, RealProjectSourceSummary> entries = new TreeMap<>();
        for (var value : project.getAsJsonArray("images")) {
            if (!value.isJsonObject() || !value.getAsJsonObject().has("entryID")) {
                throw new IOException("project image has no entryID");
            }
            int identifier;
            try {
                identifier =
                        CanonicalCodec.requireNonnegativeInt32(
                                value.getAsJsonObject().get("entryID"), "project.images.entryID");
            } catch (IllegalArgumentException error) {
                throw new IOException("project image has invalid entryID", error);
            }
            String key = Integer.toString(identifier);
            if (entries.put(key, RealProjectSourceSummary.fromProjectImage(value.getAsJsonObject()))
                    != null) {
                throw new IOException("project image entryID is duplicated");
            }
        }
        return Map.copyOf(entries);
    }

    private static ProjectSnapshot snapshot(Path project) throws IOException {
        List<FileIdentity> files = new ArrayList<>();
        try (var paths = Files.walk(project)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("project tree contains a symbolic link");
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                files.add(
                        new FileIdentity(
                                project.relativize(path).toString().replace('\\', '/'),
                                Files.size(path),
                                sha256File(path)));
            }
        }
        JsonArray identities = new JsonArray();
        long totalBytes = 0;
        for (FileIdentity file : files) {
            JsonObject item = new JsonObject();
            item.addProperty("path", file.path());
            item.addProperty("size_bytes", file.sizeBytes());
            item.addProperty("sha256", file.sha256());
            identities.add(item);
            totalBytes = Math.addExact(totalBytes, file.sizeBytes());
        }
        return new ProjectSnapshot(
                List.copyOf(files), totalBytes, CanonicalCodec.canonicalDigest(identities));
    }

    private static JsonObject runtimeIdentity() throws IOException, URISyntaxException {
        JsonObject result = new JsonObject();
        result.addProperty("java_version", System.getProperty("java.version"));
        result.addProperty("os_name", System.getProperty("os.name"));
        result.addProperty("os_arch", System.getProperty("os.arch"));
        JsonArray sources = new JsonArray();
        for (Path source : PROBE_SOURCES) {
            JsonObject item = new JsonObject();
            item.addProperty("relative_path", source.toString().replace('\\', '/'));
            item.addProperty("sha256", sha256File(source));
            sources.add(item);
        }
        result.add("probe_sources", sources);
        JsonArray classes = new JsonArray();
        for (Class<?> type : probeClasses()) {
            JsonObject item = new JsonObject();
            item.addProperty("class_name", type.getName());
            item.addProperty("sha256", classDigest(type));
            classes.add(item);
        }
        result.add("probe_classes", classes);
        Path qupathCore =
                Path.of(PathIO.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        result.addProperty("qupath_core_sha256", sha256File(qupathCore));
        return result;
    }

    private static List<Class<?>> probeClasses() {
        TreeMap<String, Class<?>> classes = new TreeMap<>();
        ArrayDeque<Class<?>> pending = new ArrayDeque<>(PROBE_CLASS_ROOTS);
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (classes.putIfAbsent(type.getName(), type) != null) {
                continue;
            }
            for (Class<?> nested : type.getDeclaredClasses()) {
                pending.addLast(nested);
            }
        }
        return List.copyOf(classes.values());
    }

    private static String classDigest(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("missing class resource " + resource);
            return CanonicalCodec.sha256(stream.readAllBytes());
        }
    }

    private static String sha256File(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(path)) {
                byte[] block = new byte[8 * 1024 * 1024];
                int count;
                while ((count = stream.read(block)) >= 0) {
                    digest.update(block, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] appendNewline(byte[] value) {
        byte[] result = new byte[value.length + 1];
        System.arraycopy(value, 0, result, 0, value.length);
        result[value.length] = '\n';
        return result;
    }

    private record FileIdentity(String path, long sizeBytes, String sha256) {}

    private record ProjectSnapshot(List<FileIdentity> files, long totalBytes, String digest) {}
}
