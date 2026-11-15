package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.platform.ExtensionRelease;

class BackendCompatibilityTest {
    private final BackendCompatibility policy =
            new BackendCompatibility(
                    new ExtensionRelease("1.0.0", "0.10.0", "0.8.0", "unused.whl"));

    @Test
    void releaseMatrixUsesNumericOrderingAndKeepsCompatibilitySeparateFromUpdateDirection() {
        assertDecision("0.7.0", true, BackendCompatibility.State.UPDATE_REQUIRED, false, true);
        assertDecision("0.8.0", true, BackendCompatibility.State.UPDATE_AVAILABLE, true, true);
        assertDecision("0.9.0", true, BackendCompatibility.State.UPDATE_AVAILABLE, true, true);
        assertDecision("0.10.0", true, BackendCompatibility.State.CURRENT, true, true);
        assertDecision(
                "0.10.0+different.build", true, BackendCompatibility.State.CURRENT, true, true);
        assertDecision("0.11.0", true, BackendCompatibility.State.NEWER_SUPPORTED, true, false);
        assertDecision("0.9.0", false, BackendCompatibility.State.UPDATE_REQUIRED, false, true);
        assertDecision("0.10.0", false, BackendCompatibility.State.UNKNOWN, false, false);
        assertDecision(
                "0.10.0+different.build", false, BackendCompatibility.State.UNKNOWN, false, false);
        assertDecision(
                "0.11.0",
                false,
                BackendCompatibility.State.EXTENSION_UPDATE_REQUIRED,
                false,
                false);
    }

    @Test
    void unknownLabelsAreNotOrderedOrReinterpretedAsLegacyVersions() {
        for (String version :
                List.of("", "boundary", "0.10.0-rc1", "01.10.0", "0.10", " 0.10.0", "0.10.0+")) {
            assertDecision(version, true, BackendCompatibility.State.UNKNOWN, false, false);
            assertDecision(version, false, BackendCompatibility.State.UNKNOWN, false, false);
        }
        assertEquals(
                "0.10.0-rc1", policy.evaluate("0.10.0-rc1", true).backendVersion().orElseThrow());
        assertTrue(policy.evaluate(null, true).backendVersion().isEmpty());
    }

    @Test
    void imageDeliveryReleaseOffersCloudUpdateFromThePreviousProtocols() throws IOException {
        var nextRelease =
                new BackendCompatibility(
                        new ExtensionRelease("0.2.1", "0.1.1", "0.1.1", "unused.whl"));
        try (var stream =
                getClass().getResourceAsStream("/contracts/gateway-capabilities-v1.json")) {
            assertNotNull(stream);
            var capabilityJson =
                    CanonicalCodec.parseJson(stream.readAllBytes())
                            .getAsJsonObject()
                            .getAsJsonArray("capabilityCases")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonObject("value");
            capabilityJson.getAsJsonObject("gateway_build").addProperty("version", "0.1.1");
            assertEquals(
                    BackendCompatibility.State.CURRENT,
                    nextRelease
                            .evaluate(
                                    GatewayCapabilitiesCodec.parseGatewayCapabilities(
                                            capabilityJson))
                            .state());

            capabilityJson.addProperty("finite_inference_schema_version", 1);
            capabilityJson.addProperty("durable_model_run_schema_version", 2);
            capabilityJson.getAsJsonObject("gateway_build").addProperty("version", "0.1.0");
            var previous =
                    nextRelease.evaluate(
                            GatewayCapabilitiesCodec.parseGatewayCapabilities(capabilityJson));
            assertEquals(BackendCompatibility.State.UPDATE_REQUIRED, previous.state());
            assertFalse(previous.permitsWork());
            assertTrue(previous.permitsCloudUpdate());
            assertTrue(previous.message().contains("Update cloud backend to 0.1.1"));

            // Keeping the old package release would prevent a safe update direction decision.
            capabilityJson.getAsJsonObject("gateway_build").addProperty("version", "0.1.1");
            assertFalse(
                    nextRelease
                            .evaluate(
                                    GatewayCapabilitiesCodec.parseGatewayCapabilities(
                                            capabilityJson))
                            .permitsCloudUpdate());
        }
    }

    @Test
    void dualReaderReleaseOffersCloudUpdateFromSourceSchemaOne() throws IOException {
        try (var stream =
                getClass().getResourceAsStream("/contracts/gateway-capabilities-v1.json")) {
            assertNotNull(stream);
            var capabilityJson =
                    CanonicalCodec.parseJson(stream.readAllBytes())
                            .getAsJsonObject()
                            .getAsJsonArray("capabilityCases")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonObject("value");
            capabilityJson.getAsJsonObject("gateway_build").addProperty("version", "0.1.1");
            capabilityJson.addProperty("source_artifact_schema_version", 1);
            var previous =
                    BackendCompatibility.current()
                            .evaluate(
                                    GatewayCapabilitiesCodec.parseGatewayCapabilities(
                                            capabilityJson));
            assertEquals(BackendCompatibility.State.UPDATE_REQUIRED, previous.state());
            assertFalse(previous.permitsWork());
            assertTrue(previous.permitsCloudUpdate());
        }
    }

    @Test
    void minimumCannotExceedTheBundleAndOrderingCannotOverflow() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BackendCompatibility(
                                new ExtensionRelease("1.0.0", "0.10.0", "0.11.0", "unused.whl")));
        assertDecision(
                "999999999999999999999.0.0",
                true,
                BackendCompatibility.State.NEWER_SUPPORTED,
                true,
                false);
    }

    @Test
    void precisionReleaseRequiresCloudUpdateEvenWhenOlderProtocolsStillMatch() {
        var previous = BackendCompatibility.current().evaluate("0.1.2", true);
        assertEquals(BackendCompatibility.State.UPDATE_REQUIRED, previous.state());
        assertFalse(previous.permitsWork());
        assertTrue(previous.permitsCloudUpdate());
        assertTrue(previous.message().contains("Update cloud backend"));
    }

    @Test
    void packagedMetadataNamesItsActualWheel() {
        var release = ExtensionRelease.current();
        assertFalse(release.extensionVersion().isBlank());
        assertEquals(
                "histoforge-" + release.bundledBackendVersion() + "-py3-none-any.whl",
                release.bundledWheel());
        assertNotNull(
                getClass().getResource("/qupath/ext/histoforge/runtime/" + release.bundledWheel()));
        assertTrue(
                BackendCompatibility.current()
                        .evaluate(release.bundledBackendVersion(), true)
                        .permitsWork());
    }

    private void assertDecision(
            String version,
            boolean supported,
            BackendCompatibility.State state,
            boolean work,
            boolean update) {
        var decision = policy.evaluate(version, supported);
        assertAll(
                version + " supported=" + supported,
                () -> assertEquals(state, decision.state()),
                () -> assertEquals(work, decision.permitsWork()),
                () -> assertEquals(update, decision.permitsCloudUpdate()));
    }
}
