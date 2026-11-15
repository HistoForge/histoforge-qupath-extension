package qupath.ext.histoforge.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;

class ClientBuildIdentityProviderTest {

    @TempDir Path directory;

    @Test
    void capturesExactArtifactManifestRuntimeAndProductionLimits() throws IOException {
        Path jar = directory.resolve("histoforge.jar");
        Files.write(jar, "exact jar bytes".getBytes(StandardCharsets.UTF_8));

        var identity =
                ClientBuildIdentityProvider.capture(
                        jar,
                        manifest(),
                        resource(ClientBuildIdentityProvider.LIMITS_RESOURCE),
                        "0.2.1",
                        "0.6.0",
                        Map.of("java", "21.0.10", "groovy", "4.0.24", "jts", "1.20.0"));

        assertEquals("0.2.1", identity.extension().version());
        assertEquals(
                CanonicalCodec.sha256(Files.readAllBytes(jar)),
                identity.extension().artifactDigest());
        assertEquals("0.6.0", identity.quPathVersion());
        assertEquals(512_000, identity.limits().maxHierarchyObjects());
        assertEquals(65_000_000, identity.limits().maxHierarchyGeometryVertices());
        assertEquals(4_194_304, identity.limits().maxMetricValuesPerRegion());
        assertEquals(8_388_608, identity.limits().maxMetricValuesPerImage());
        assertEquals(17_179_869_184L, identity.limits().maxLocalSourceArtifactBytes());
    }

    @Test
    void rejectsMissingArtifactAndWrongManifestComponent() throws IOException {
        Path missing = directory.resolve("missing.jar");
        assertThrows(
                IllegalStateException.class,
                () ->
                        ClientBuildIdentityProvider.capture(
                                missing,
                                manifest(),
                                resource(ClientBuildIdentityProvider.LIMITS_RESOURCE),
                                "1",
                                "0.6.0",
                                Map.of()));

        Path jar = directory.resolve("histoforge.jar");
        Files.write(jar, new byte[] {1});
        String wrong =
                new String(manifest(), StandardCharsets.UTF_8)
                        .replace("histoforge-qupath-extension", "wrong-component");
        assertThrows(
                IllegalStateException.class,
                () ->
                        ClientBuildIdentityProvider.capture(
                                jar,
                                wrong.getBytes(StandardCharsets.UTF_8),
                                resource(ClientBuildIdentityProvider.LIMITS_RESOURCE),
                                "1",
                                "0.6.0",
                                Map.of()));
    }

    @Test
    void explicitEnvelopeScalesOnlyHierarchyAndMetricLimitsAndChangesIdentity() throws IOException {
        Path jar = directory.resolve("histoforge.jar");
        Files.write(jar, "exact jar bytes".getBytes(StandardCharsets.UTF_8));
        var packaged =
                ClientBuildIdentityProvider.capture(
                        jar,
                        manifest(),
                        resource(ClientBuildIdentityProvider.LIMITS_RESOURCE),
                        "0.2.1",
                        "0.6.0",
                        Map.of("java", "21.0.10"));

        assertEquals(
                List.of(1, 2, 4, 8),
                java.util.Arrays.stream(LocalResourceEnvelope.values())
                        .map(LocalResourceEnvelope::multiplier)
                        .toList());
        assertEquals(
                List.of("Standard", "Large", "Very large", "High capacity"),
                java.util.Arrays.stream(LocalResourceEnvelope.values())
                        .map(LocalResourceEnvelope::displayName)
                        .toList());
        assertEquals(
                packaged,
                ClientBuildIdentityProvider.withLocalResourceEnvelope(
                        packaged, LocalResourceEnvelope.SAFE));
        var selected =
                ClientBuildIdentityProvider.withLocalResourceEnvelope(
                        packaged, LocalResourceEnvelope.FOURFOLD);

        assertEquals(packaged.extension(), selected.extension());
        assertEquals(packaged.quPathVersion(), selected.quPathVersion());
        assertEquals(packaged.runtimeVersions(), selected.runtimeVersions());
        assertEquals(2_048_000, selected.limits().maxHierarchyObjects());
        assertEquals(260_000_000, selected.limits().maxHierarchyGeometryVertices());
        assertEquals(8_400_000, selected.limits().maxHierarchyMeasurements());
        assertEquals(536_870_912, selected.limits().maxHierarchyTextUtf8Bytes());
        assertEquals(16_777_216, selected.limits().maxMetricValuesPerRegion());
        assertEquals(33_554_432, selected.limits().maxMetricValuesPerImage());
        assertEquals(
                packaged.limits().maxGenerationStoreUtf8Bytes(),
                selected.limits().maxGenerationStoreUtf8Bytes());
        assertEquals(
                packaged.limits().maxLocalSourceArtifactBytes(),
                selected.limits().maxLocalSourceArtifactBytes());
        assertNotEquals(
                BuildIdentityCodec.clientBuildIdentityDigest(packaged),
                BuildIdentityCodec.clientBuildIdentityDigest(selected));

        assertEquals(
                List.of(65_000_000L, 130_000_000L, 260_000_000L, 520_000_000L),
                java.util.Arrays.stream(LocalResourceEnvelope.values())
                        .map(
                                envelope ->
                                        ClientBuildIdentityProvider.withLocalResourceEnvelope(
                                                        packaged, envelope)
                                                .limits()
                                                .maxHierarchyGeometryVertices())
                        .toList());

        assertThrows(
                NullPointerException.class,
                () -> ClientBuildIdentityProvider.withLocalResourceEnvelope(packaged, null));
    }

    @Test
    void highCapacityRaisesOnlyObjectsMeasurementsAndText() throws IOException {
        Path jar = directory.resolve("histoforge.jar");
        Files.write(jar, new byte[] {1});
        var packaged =
                ClientBuildIdentityProvider.capture(
                        jar,
                        manifest(),
                        resource(ClientBuildIdentityProvider.LIMITS_RESOURCE),
                        "0.2.1",
                        "0.6.0",
                        Map.of("java", "21.0.10"));
        var selected =
                ClientBuildIdentityProvider.withLocalResourceEnvelope(
                        packaged, LocalResourceEnvelope.EIGHTFOLD);
        var limits = selected.limits();

        assertEquals(16_384_000, limits.maxHierarchyObjects());
        assertEquals(168_000_000, limits.maxHierarchyMeasurements());
        assertEquals(8_589_934_592L, limits.maxHierarchyTextUtf8Bytes());
        assertEquals(520_000_000, limits.maxHierarchyGeometryVertices());
        assertEquals(33_554_432, limits.maxMetricValuesPerRegion());
        assertEquals(67_108_864, limits.maxMetricValuesPerImage());
        assertEquals(33_554_432, limits.maxGenerationStoreUtf8Bytes());
        assertEquals(17_179_869_184L, limits.maxLocalSourceArtifactBytes());
        assertEquals(packaged.extension(), selected.extension());
        assertEquals(packaged.runtimeVersions(), selected.runtimeVersions());
        assertNotEquals(
                BuildIdentityCodec.clientBuildIdentityDigest(packaged),
                BuildIdentityCodec.clientBuildIdentityDigest(selected));
        // Selecting High capacity must not mutate the shared packaged/default policy.
        assertEquals(512_000, packaged.limits().maxHierarchyObjects());
        assertEquals(2_100_000, packaged.limits().maxHierarchyMeasurements());
        assertEquals(134_217_728, packaged.limits().maxHierarchyTextUtf8Bytes());
    }

    private static byte[] manifest() {
        BuildIdentityCodec.BuildManifest manifest =
                new BuildIdentityCodec.BuildManifest(
                        1,
                        "histoforge-qupath-extension",
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        "4".repeat(64),
                        "5".repeat(64));
        return CanonicalCodec.canonicalBytes(BuildIdentityCodec.toJson(manifest));
    }

    private static byte[] resource(String name) throws IOException {
        try (var stream = ClientBuildIdentityProviderTest.class.getResourceAsStream(name)) {
            if (stream == null) throw new IOException("missing resource " + name);
            return stream.readAllBytes();
        }
    }
}
