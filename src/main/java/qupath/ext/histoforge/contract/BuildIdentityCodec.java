package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical build manifests and compact executable identities. */
public final class BuildIdentityCodec {

    private static final Set<String> BUILD_MANIFEST_FIELDS =
            Set.of(
                    "schema_version",
                    "component",
                    "source_digest",
                    "dependency_lock_digest",
                    "resolved_environment_digest",
                    "build_configuration_digest",
                    "behavior_policy_digest");
    private static final Set<String> SOFTWARE_BUILD_IDENTITY_FIELDS =
            Set.of("version", "artifact_digest", "manifest_digest");
    private static final Set<String> CLIENT_BUILD_IDENTITY_FIELDS =
            Set.of("extension", "qupath_version", "runtime_versions", "limits");

    private BuildIdentityCodec() {}

    /** Closed manifest for the behavior-relevant inputs to one built component. */
    public record BuildManifest(
            // Schema for the immutable manifest artifact itself.
            int schemaVersion,
            // Process or behavior surface described by the five input-group digests.
            String component,
            // Digest of the executable source file set.
            String sourceDigest,
            // Digest of the exact dependency-lock file set.
            String dependencyLockDigest,
            // Digest of the observed resolved packages and runtime environment.
            String resolvedEnvironmentDigest,
            // Digest of the image, JAR, compiler, and packaging configuration.
            String buildConfigurationDigest,
            // Digest of explicit behavior policies and calibrated contract fixtures.
            String behaviorPolicyDigest) {
        public BuildManifest {
            if (schemaVersion != 1) {
                throw new IllegalArgumentException("unsupported build manifest schema_version");
            }
            component = CanonicalCodec.requireNonblank(component, "component");
            sourceDigest = CanonicalCodec.requireSha256(sourceDigest, "source_digest");
            dependencyLockDigest =
                    CanonicalCodec.requireSha256(dependencyLockDigest, "dependency_lock_digest");
            resolvedEnvironmentDigest =
                    CanonicalCodec.requireSha256(
                            resolvedEnvironmentDigest, "resolved_environment_digest");
            buildConfigurationDigest =
                    CanonicalCodec.requireSha256(
                            buildConfigurationDigest, "build_configuration_digest");
            behaviorPolicyDigest =
                    CanonicalCodec.requireSha256(behaviorPolicyDigest, "behavior_policy_digest");
        }
    }

    /** Compact identity carried by requests and scientific provenance. */
    public record SoftwareBuildIdentity(
            // Diagnostic release/build label; digests, not this label, establish identity.
            String version,
            // Digest of the executable artifact actually run by the process.
            String artifactDigest,
            // Digest of the complete immutable {@link BuildManifest}.
            String manifestDigest) {
        public SoftwareBuildIdentity {
            version = CanonicalCodec.requireNonblank(version, "version");
            artifactDigest = CanonicalCodec.requireSha256(artifactDigest, "artifact_digest");
            manifestDigest = CanonicalCodec.requireSha256(manifestDigest, "manifest_digest");
        }
    }

    /** Exact extension, QuPath, runtime, and enforced-limit identity for one client action. */
    public record ClientBuildIdentity(
            // Exact installed extension artifact and behavior-manifest identity.
            SoftwareBuildIdentity extension,
            // Exact QuPath version running the extension.
            String quPathVersion,
            // Behavior-relevant loaded component versions keyed by component name.
            Map<String, String> runtimeVersions,
            // Client-side resource ceilings enforced for the action.
            ExecutionLimitsCodec.ClientExecutionLimits limits) {
        public ClientBuildIdentity {
            extension = Objects.requireNonNull(extension, "extension");
            quPathVersion = CanonicalCodec.requireNonblank(quPathVersion, "qupath_version");
            runtimeVersions = immutableRuntimeVersions(runtimeVersions);
            limits = Objects.requireNonNull(limits, "limits");
        }
    }

    /** Describe exact named bytes in deterministic unsigned UTF-8 path order. */
    public static JsonObject fileSetManifest(Map<String, byte[]> files) {
        if (files == null) {
            throw new IllegalArgumentException("file set must not be null");
        }
        List<FileEntry> entries = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String path = requireLogicalPath(file.getKey());
            if (file.getValue() == null) {
                throw new IllegalArgumentException("file " + path + " content must be bytes");
            }
            entries.add(
                    new FileEntry(
                            path.getBytes(StandardCharsets.UTF_8),
                            path,
                            CanonicalCodec.sha256(file.getValue())));
        }
        entries.sort((left, right) -> Arrays.compareUnsigned(left.pathBytes(), right.pathBytes()));

        JsonArray encoded = new JsonArray();
        for (FileEntry entry : entries) {
            JsonObject value = new JsonObject();
            value.addProperty("path", entry.path());
            value.addProperty("sha256", entry.sha256());
            encoded.add(value);
        }
        JsonObject result = new JsonObject();
        result.add("files", encoded);
        return result;
    }

    /** Return the JCS digest of {@link #fileSetManifest(Map)}. */
    public static String fileSetDigest(Map<String, byte[]> files) {
        return CanonicalCodec.canonicalDigest(fileSetManifest(files));
    }

    /** Parse one closed snake-case schema-1 build manifest. */
    public static BuildManifest parseBuildManifest(JsonElement value) {
        JsonObject object = requireExactObject(value, BUILD_MANIFEST_FIELDS, "build manifest");
        return new BuildManifest(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "component"),
                requiredString(object, "source_digest"),
                requiredString(object, "dependency_lock_digest"),
                requiredString(object, "resolved_environment_digest"),
                requiredString(object, "build_configuration_digest"),
                requiredString(object, "behavior_policy_digest"));
    }

    /** Encode one build manifest as its exact snake-case schema-1 JSON object. */
    public static JsonObject toJson(BuildManifest manifest) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", manifest.schemaVersion());
        value.addProperty("component", manifest.component());
        value.addProperty("source_digest", manifest.sourceDigest());
        value.addProperty("dependency_lock_digest", manifest.dependencyLockDigest());
        value.addProperty("resolved_environment_digest", manifest.resolvedEnvironmentDigest());
        value.addProperty("build_configuration_digest", manifest.buildConfigurationDigest());
        value.addProperty("behavior_policy_digest", manifest.behaviorPolicyDigest());
        return value;
    }

    /** Return the canonical digest of a validated build manifest. */
    public static String buildManifestDigest(BuildManifest manifest) {
        return CanonicalCodec.canonicalDigest(toJson(manifest));
    }

    /** Create the compact identity carried by requests and provenance. */
    public static SoftwareBuildIdentity softwareBuildIdentity(
            String version, String artifactDigest, BuildManifest manifest) {
        return new SoftwareBuildIdentity(version, artifactDigest, buildManifestDigest(manifest));
    }

    /** Parse one closed snake-case compact software-build identity. */
    public static SoftwareBuildIdentity parseSoftwareBuildIdentity(JsonElement value) {
        JsonObject object =
                requireExactObject(
                        value, SOFTWARE_BUILD_IDENTITY_FIELDS, "software build identity");
        return new SoftwareBuildIdentity(
                requiredString(object, "version"),
                requiredString(object, "artifact_digest"),
                requiredString(object, "manifest_digest"));
    }

    /** Parse one exact client build and its enforced local resource ceilings. */
    public static ClientBuildIdentity parseClientBuildIdentity(JsonElement value) {
        JsonObject object =
                requireExactObject(value, CLIENT_BUILD_IDENTITY_FIELDS, "client build identity");
        return new ClientBuildIdentity(
                parseSoftwareBuildIdentity(object.get("extension")),
                requiredString(object, "qupath_version"),
                parseRuntimeVersions(object.get("runtime_versions")),
                ExecutionLimitsCodec.parseClientExecutionLimits(object.get("limits")));
    }

    /** Encode one compact software-build identity as exact snake-case JSON. */
    public static JsonObject toJson(SoftwareBuildIdentity identity) {
        JsonObject value = new JsonObject();
        value.addProperty("version", identity.version());
        value.addProperty("artifact_digest", identity.artifactDigest());
        value.addProperty("manifest_digest", identity.manifestDigest());
        return value;
    }

    /** Encode one exact client build identity using its persisted lower-snake fields. */
    public static JsonObject toJson(ClientBuildIdentity identity) {
        JsonObject value = new JsonObject();
        value.add("extension", toJson(identity.extension()));
        value.addProperty("qupath_version", identity.quPathVersion());
        JsonObject runtimeVersions = new JsonObject();
        identity.runtimeVersions().forEach(runtimeVersions::addProperty);
        value.add("runtime_versions", runtimeVersions);
        value.add("limits", ExecutionLimitsCodec.toJson(identity.limits()));
        return value;
    }

    /** Return the canonical digest retained by action and generation evidence. */
    public static String clientBuildIdentityDigest(ClientBuildIdentity identity) {
        return CanonicalCodec.canonicalDigest(toJson(identity));
    }

    private static Map<String, String> parseRuntimeVersions(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("runtime_versions must be an object");
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            versions.put(
                    CanonicalCodec.requireNonblank(entry.getKey(), "runtime_versions component"),
                    requiredString(entry.getValue(), "runtime_versions." + entry.getKey()));
        }
        return immutableRuntimeVersions(versions);
    }

    private static Map<String, String> immutableRuntimeVersions(Map<String, String> versions) {
        if (versions == null) {
            throw new IllegalArgumentException("runtime_versions must not be null");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : versions.entrySet()) {
            String component =
                    CanonicalCodec.requireNonblank(entry.getKey(), "runtime_versions component");
            result.put(
                    component,
                    CanonicalCodec.requireNonblank(
                            entry.getValue(), "runtime_versions." + component));
        }
        return Map.copyOf(result);
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static String requiredString(JsonObject value, String field) {
        return requiredString(value.get(field), field);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }

    private static String requireLogicalPath(String value) {
        String path = CanonicalCodec.requireNonblank(value, "file path");
        if (path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("file path must be a relative POSIX path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "file path must be canonical without empty or dot segments");
            }
        }
        return path;
    }

    private record FileEntry(byte[] pathBytes, String path, String sha256) {}
}
