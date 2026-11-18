package qupath.ext.histoforge.qualification;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;

/** Exact source, build, dependency, and bytecode identity of the Phase 7 probe harnesses. */
public final class QualificationProbeIdentity {

    private static final String COMPONENT = "histoforge-phase7-qualification-probes";
    private static final String INPUT_RESOURCE =
            "/qupath/ext/histoforge/qualification/phase7-qualification-probe-v1.json";
    private static final Set<String> INPUT_FIELDS =
            Set.of("schema_version", "component", "file_set", "file_set_digest");
    private static final Set<String> FILE_SET_FIELDS = Set.of("files");
    private static final Set<String> FILE_FIELDS = Set.of("path", "sha256");
    private static final Set<String> COMMON_INPUT_PATHS =
            Set.of(
                    "build.gradle.kts",
                    "gradle/qupath-hosts/qupath-0.6.0.versions.toml",
                    "gradle/qupath-hosts/qupath-0.7.0.versions.toml",
                    "gradle/wrapper/gradle-wrapper.properties",
                    "resolved-test-runtime-artifacts.txt",
                    "selected-qupath-version.txt",
                    "settings-gradle.lockfile",
                    "settings.gradle.kts",
                    "src/test/java/qupath/ext/histoforge/client/SourcePublicationQualification.java",
                    "src/test/java/qupath/ext/histoforge/qualification/QualificationProbeIdentity.java",
                    "src/test/java/qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryFixture.java",
                    "src/test/java/qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryQualification.java");
    private static final Set<String> SUPPORTED_DEPENDENCY_LOCK_PATHS =
            Set.of("gradle.lockfile", "gradle/dependency-locks/qupath-0.7.0.lockfile");
    private static final Map<String, Set<String>> EXPECTED_CLASSES =
            Map.of(
                    "qupath/ext/histoforge/client/SourcePublicationQualification",
                    Set.of("qupath/ext/histoforge/client/SourcePublicationQualification.class"),
                    "qupath/ext/histoforge/qualification/QualificationProbeIdentity",
                    Set.of("qupath/ext/histoforge/qualification/QualificationProbeIdentity.class"),
                    "qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryFixture",
                    Set.of(
                            "qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryFixture.class",
                            "qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryFixture$CropEvidence.class",
                            "qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryFixture$GuiSession.class",
                            "qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryFixture$LoadedProject.class"),
                    "qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryQualification",
                    Set.of(
                            "qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryQualification.class",
                            "qupath/ext/histoforge/ui/project/PrivateGatewayRecoveryQualification$GatewayContext.class"));

    private QualificationProbeIdentity() {}

    /** Capture the generated input manifest and the actual compiled harness class bytes. */
    public static JsonObject capture() {
        JsonObject inputManifest = readInputManifest();
        Map<String, byte[]> classFiles = readExactClassFiles();
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", 1);
        value.addProperty("component", COMPONENT);
        value.add("input_manifest", inputManifest);
        value.addProperty("input_manifest_digest", CanonicalCodec.canonicalDigest(inputManifest));
        value.add("compiled_class_file_set", BuildIdentityCodec.fileSetManifest(classFiles));
        value.addProperty(
                "compiled_class_file_set_digest", BuildIdentityCodec.fileSetDigest(classFiles));
        return value;
    }

    private static JsonObject readInputManifest() {
        byte[] bytes;
        try (InputStream stream =
                QualificationProbeIdentity.class.getResourceAsStream(INPUT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing Phase 7 qualification input manifest");
            }
            bytes = stream.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException(
                    "cannot read Phase 7 qualification input manifest", error);
        }

        JsonElement parsed = CanonicalCodec.parseJson(bytes);
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException(
                    "Phase 7 qualification input manifest is not an object");
        }
        JsonObject manifest = parsed.getAsJsonObject();
        requireFields(manifest, INPUT_FIELDS, "qualification input manifest");
        if (CanonicalCodec.requireInt32(manifest.get("schema_version"), "schema_version") != 1
                || !COMPONENT.equals(
                        CanonicalCodec.requireNonblank(
                                manifest.get("component").getAsString(), "component"))) {
            throw new IllegalStateException(
                    "Phase 7 qualification input manifest identity differs");
        }
        byte[] canonical = CanonicalCodec.canonicalBytes(manifest);
        byte[] expected = Arrays.copyOf(canonical, canonical.length + 1);
        expected[expected.length - 1] = '\n';
        if (!Arrays.equals(bytes, expected)) {
            throw new IllegalStateException(
                    "Phase 7 qualification input manifest is not canonical JSON plus newline");
        }

        JsonObject fileSet = requireObject(manifest.get("file_set"), "file_set");
        requireFields(fileSet, FILE_SET_FIELDS, "qualification input file_set");
        JsonArray files = requireArray(fileSet.get("files"), "file_set.files");
        Set<String> paths = new LinkedHashSet<>();
        for (JsonElement element : files) {
            JsonObject file = requireObject(element, "file_set.files item");
            requireFields(file, FILE_FIELDS, "qualification input file");
            String path =
                    CanonicalCodec.requireNonblank(file.get("path").getAsString(), "file path");
            if (path.startsWith("/") || path.contains("\\") || path.contains("..")) {
                throw new IllegalStateException("qualification input file has an invalid path");
            }
            CanonicalCodec.requireSha256(file.get("sha256").getAsString(), "file sha256");
            if (!paths.add(path)) {
                throw new IllegalStateException("qualification input file paths must be unique");
            }
        }
        Set<String> commonPaths = new LinkedHashSet<>(paths);
        commonPaths.removeAll(SUPPORTED_DEPENDENCY_LOCK_PATHS);
        long dependencyLockCount =
                paths.stream().filter(SUPPORTED_DEPENDENCY_LOCK_PATHS::contains).count();
        if (!commonPaths.equals(COMMON_INPUT_PATHS) || dependencyLockCount != 1) {
            throw new IllegalStateException("qualification input file set differs");
        }
        String claimed =
                CanonicalCodec.requireSha256(
                        manifest.get("file_set_digest").getAsString(), "file_set_digest");
        if (!claimed.equals(CanonicalCodec.canonicalDigest(fileSet))) {
            throw new IllegalStateException("qualification input file-set digest differs");
        }
        return manifest.deepCopy();
    }

    private static Map<String, byte[]> readExactClassFiles() {
        Path root;
        try {
            root =
                    Path.of(
                                    QualificationProbeIdentity.class
                                            .getProtectionDomain()
                                            .getCodeSource()
                                            .getLocation()
                                            .toURI())
                            .toAbsolutePath()
                            .normalize();
        } catch (URISyntaxException error) {
            throw new IllegalStateException("cannot resolve qualification class output", error);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "Phase 7 qualification classes must run from an exact class directory");
        }

        Set<String> expected = new LinkedHashSet<>();
        Set<String> observed = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> owner : EXPECTED_CLASSES.entrySet()) {
            expected.addAll(owner.getValue());
            Path logicalOwner = Path.of(owner.getKey());
            Path directory = root.resolve(logicalOwner.getParent()).normalize();
            String simpleName = logicalOwner.getFileName().toString();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (name.equals(simpleName + ".class")
                            || (name.startsWith(simpleName + "$") && name.endsWith(".class"))) {
                        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IllegalStateException(
                                    "qualification class entry is not a regular file");
                        }
                        observed.add(root.relativize(entry).toString().replace('\\', '/'));
                    }
                }
            } catch (IOException error) {
                throw new IllegalStateException(
                        "cannot enumerate qualification class files", error);
            }
        }
        if (!observed.equals(expected)) {
            throw new IllegalStateException(
                    "qualification compiled class set differs: expected="
                            + expected
                            + ", observed="
                            + observed);
        }

        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String logicalPath : expected) {
            try {
                result.put(logicalPath, Files.readAllBytes(root.resolve(logicalPath)));
            } catch (IOException error) {
                throw new IllegalStateException("cannot read qualification class bytes", error);
            }
        }
        return result;
    }

    private static JsonObject requireObject(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalStateException(field + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonElement value, String field) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalStateException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static void requireFields(JsonObject value, Set<String> expected, String field) {
        if (!value.keySet().equals(expected)) {
            throw new IllegalStateException(field + " fields differ");
        }
    }
}
