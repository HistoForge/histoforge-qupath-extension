package qupath.ext.histoforge.client;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.platform.ExtensionRelease;

/** Release support policy. Exact build digests remain owned by scientific action contracts. */
public final class BackendCompatibility {

    public enum State {
        CURRENT,
        UPDATE_AVAILABLE,
        NEWER_SUPPORTED,
        UPDATE_REQUIRED,
        EXTENSION_UPDATE_REQUIRED,
        UNKNOWN
    }

    public record Decision(State state, Optional<String> backendVersion, String message) {
        public boolean permitsWork() {
            return state == State.CURRENT
                    || state == State.UPDATE_AVAILABLE
                    || state == State.NEWER_SUPPORTED;
        }

        /** Explicit same-release deployment is useful during development; never downgrade. */
        public boolean permitsCloudUpdate() {
            return state == State.CURRENT
                    || state == State.UPDATE_AVAILABLE
                    || state == State.UPDATE_REQUIRED;
        }
    }

    // Launch uses stable numeric releases. Build labels do not change precedence. Other
    // diagnostic labels (including prereleases) remain unknown, not guessed older/newer.
    private static final Pattern RELEASE =
            Pattern.compile(
                    "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private static final BackendCompatibility CURRENT =
            new BackendCompatibility(ExtensionRelease.current());

    private final ExtensionRelease release;
    private final List<BigInteger> bundled;
    private final List<BigInteger> minimum;

    public BackendCompatibility(ExtensionRelease release) {
        this.release = release;
        bundled =
                parts(release.bundledBackendVersion())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Invalid bundled backend release"));
        minimum =
                parts(release.minimumBackendVersion())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Invalid minimum backend release"));
        if (compare(minimum, bundled) > 0) {
            throw new IllegalArgumentException("Minimum backend release exceeds bundled release");
        }
    }

    public static BackendCompatibility current() {
        return CURRENT;
    }

    public Decision evaluate(GatewayCapabilities capabilities) {
        return evaluate(
                capabilities.gatewayBuild().version(),
                GatewayCapabilitiesCodec.supports(capabilities));
    }

    public Decision evaluate(GatewayCapabilities capabilities, int healthApiMajor) {
        return capabilities.apiMajor() == healthApiMajor
                ? evaluate(capabilities)
                : unknown(capabilities.gatewayBuild().version());
    }

    /** Only a fully parsed response may establish supported or unsupported contracts. */
    Decision evaluate(String version, boolean contractsSupported) {
        var deployed = parts(version);
        if (deployed.isEmpty()) return unknown(version);
        var knownVersion = Optional.of(version);
        int relativeToBundle = compare(deployed.orElseThrow(), bundled);
        if (!contractsSupported) {
            if (relativeToBundle > 0) {
                return new Decision(
                        State.EXTENSION_UPDATE_REQUIRED,
                        knownVersion,
                        "Cloud backend "
                                + version
                                + " requires a newer HistoForge extension. Update the extension in QuPath.");
            }
            if (relativeToBundle == 0) return unknown(version);
            return updateRequired(knownVersion);
        }
        if (compare(deployed.orElseThrow(), minimum) < 0) return updateRequired(knownVersion);
        if (relativeToBundle < 0) {
            return new Decision(
                    State.UPDATE_AVAILABLE,
                    knownVersion,
                    "Cloud backend "
                            + release.bundledBackendVersion()
                            + " is available. Backend "
                            + version
                            + " remains supported.");
        }
        if (relativeToBundle > 0) {
            return new Decision(
                    State.NEWER_SUPPORTED,
                    knownVersion,
                    "Cloud backend "
                            + version
                            + " is newer than the bundled "
                            + release.bundledBackendVersion()
                            + " and is compatible. No cloud update needed.");
        }
        return new Decision(State.CURRENT, knownVersion, "Cloud backend is compatible.");
    }

    private Decision updateRequired(Optional<String> version) {
        return new Decision(
                State.UPDATE_REQUIRED,
                version,
                "Cloud backend "
                        + version.orElseThrow()
                        + " is not supported. Update cloud backend to "
                        + release.bundledBackendVersion()
                        + ".");
    }

    static Decision unknown(String version) {
        return new Decision(
                State.UNKNOWN,
                Optional.ofNullable(version)
                        .filter(value -> !value.isBlank() && value.length() <= 128),
                "Cloud compatibility could not be established. Check the extension and backend versions before updating.");
    }

    private static Optional<List<BigInteger>> parts(String version) {
        if (version == null || version.length() > 128 || !RELEASE.matcher(version).matches())
            return Optional.empty();
        return Optional.of(
                Arrays.stream(version.split("\\+", 2)[0].split("\\."))
                        .map(BigInteger::new)
                        .toList());
    }

    private static int compare(List<BigInteger> left, List<BigInteger> right) {
        for (int index = 0; index < 3; index++) {
            int result = left.get(index).compareTo(right.get(index));
            if (result != 0) return result;
        }
        return 0;
    }
}
