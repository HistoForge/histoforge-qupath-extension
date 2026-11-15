package qupath.ext.histoforge.platform;

import groovy.lang.GroovySystem;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.locationtech.jts.JTSVersion;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec.BuildManifest;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.BuildIdentityCodec.SoftwareBuildIdentity;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.lib.gui.QuPathGUI;

/** Captures the exact packaged extension, host runtime, and enforced client resource policy. */
public final class ClientBuildIdentityProvider {

    static final String MANIFEST_RESOURCE =
            "/qupath/ext/histoforge/build/client-build-manifest-v1.json";
    static final String LIMITS_RESOURCE =
            "/qupath/ext/histoforge/build/client-execution-limits-v1.json";
    private static final String COMPONENT = "histoforge-qupath-extension";

    private ClientBuildIdentityProvider() {}

    /**
     * Capture the installed extension identity for a scientific action. The application owner
     * memoizes the returned value for the installed plugin's process lifetime.
     *
     * <p>Scientific actions require the packaged JAR. An exploded IDE class directory has no exact
     * installed artifact and therefore fails rather than recording invented provenance.
     */
    public static ClientBuildIdentity capture() {
        Path artifact = installedJar();
        String version =
                Objects.requireNonNullElse(
                        ClientBuildIdentityProvider.class.getPackage().getImplementationVersion(),
                        "");
        if (version.isBlank()) {
            throw new IllegalStateException(
                    "the installed HistoForge extension JAR has no implementation version");
        }
        return capture(
                artifact,
                readResource(MANIFEST_RESOURCE),
                readResource(LIMITS_RESOURCE),
                version,
                String.valueOf(QuPathGUI.getVersion()),
                Map.of(
                        "java", Runtime.version().toString(),
                        "groovy", GroovySystem.getVersion(),
                        "jts", JTSVersion.CURRENT_VERSION.toString()));
    }

    static ClientBuildIdentity capture(
            Path artifact,
            byte[] manifestBytes,
            byte[] limitsBytes,
            String version,
            String quPathVersion,
            Map<String, String> runtimeVersions) {
        Objects.requireNonNull(artifact, "artifact");
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException("extension artifact is not a regular JAR file");
        }
        BuildManifest manifest =
                BuildIdentityCodec.parseBuildManifest(CanonicalCodec.parseJson(manifestBytes));
        if (!COMPONENT.equals(manifest.component())) {
            throw new IllegalStateException("extension build manifest has the wrong component");
        }
        SoftwareBuildIdentity extension =
                BuildIdentityCodec.softwareBuildIdentity(version, digest(artifact), manifest);
        return new ClientBuildIdentity(
                extension,
                quPathVersion,
                runtimeVersions,
                ExecutionLimitsCodec.parseClientExecutionLimits(
                        CanonicalCodec.parseJson(limitsBytes)));
    }

    /**
     * Derive one action identity from the packaged default policy and an explicit count preset.
     * Only hierarchy and metric ceilings scale; persistence and derived-source ceilings remain the
     * packaged values. A preset does not change or predict the host's memory allocation.
     */
    public static ClientBuildIdentity withLocalResourceEnvelope(
            ClientBuildIdentity packagedIdentity, LocalResourceEnvelope envelope) {
        Objects.requireNonNull(packagedIdentity, "packaged_identity");
        Objects.requireNonNull(envelope, "local_resource_envelope");
        int multiplier = envelope.multiplier();
        var base = packagedIdentity.limits();
        var selected =
                new ExecutionLimitsCodec.ClientExecutionLimits(
                        scaled(base.maxHierarchyObjects(), envelope.objectMultiplier()),
                        scaled(base.maxHierarchyGeometryVertices(), multiplier),
                        scaled(base.maxHierarchyMeasurements(), envelope.measurementMultiplier()),
                        scaled(base.maxHierarchyTextUtf8Bytes(), envelope.textMultiplier()),
                        base.maxGenerationStoreUtf8Bytes(),
                        scaled(base.maxMetricValuesPerRegion(), multiplier),
                        scaled(base.maxMetricValuesPerImage(), multiplier),
                        base.maxLocalSourceArtifactBytes());
        return new ClientBuildIdentity(
                packagedIdentity.extension(),
                packagedIdentity.quPathVersion(),
                packagedIdentity.runtimeVersions(),
                selected);
    }

    private static long scaled(long value, int multiplier) {
        return Math.multiplyExact(value, multiplier);
    }

    private static Path installedJar() {
        URL location =
                ClientBuildIdentityProvider.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation();
        try {
            Path path = Path.of(location.toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)
                    || !path.getFileName().toString().toLowerCase().endsWith(".jar")) {
                throw new IllegalStateException(
                        "HistoForge scientific actions require the packaged extension JAR");
            }
            return path;
        } catch (URISyntaxException error) {
            throw new IllegalStateException("cannot resolve the installed extension JAR", error);
        }
    }

    private static byte[] readResource(String name) {
        try (InputStream stream = ClientBuildIdentityProvider.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("missing packaged build identity resource " + name);
            }
            return stream.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException(
                    "cannot read packaged build identity resource " + name, error);
        }
    }

    private static String digest(Path artifact) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream stream = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = stream.read(buffer)) >= 0; ) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("cannot hash the installed extension JAR", error);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
