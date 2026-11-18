package qupath.ext.histoforge.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;

class BundledCliRuntimeTest {

    @TempDir Path temp;

    @Test
    void productionRuntimeIsBundled() {
        assertTrue(BundledCliRuntime.current().runtimeDigest().matches("[0-9a-f]{64}"));
    }

    @Test
    void bundledBackendProtocolsMatchClientRequirements() throws Exception {
        String resource = "/qupath/ext/histoforge/runtime/" + BundledCliRuntime.WHEEL_NAME;
        String versions = null;
        try (var wheel = new ZipInputStream(getClass().getResourceAsStream(resource))) {
            for (var entry = wheel.getNextEntry(); entry != null; entry = wheel.getNextEntry()) {
                if (entry.getName().equals("histoforge/cloud/protocol_versions.py")) {
                    versions = new String(wheel.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        assertTrue(versions != null, "Bundled backend has no protocol version declarations");
        var expected =
                Map.of(
                        "GATEWAY_API_MAJOR", GatewayCapabilitiesCodec.API_MAJOR,
                        "MODULE_SCHEMA_VERSION", GatewayCapabilitiesCodec.MODULE_SCHEMA_VERSION,
                        "SOURCE_ARTIFACT_SCHEMA_VERSION",
                                GatewayCapabilitiesCodec.SOURCE_ARTIFACT_SCHEMA_VERSION,
                        "FINITE_INFERENCE_SCHEMA_VERSION",
                                GatewayCapabilitiesCodec.FINITE_INFERENCE_SCHEMA_VERSION,
                        "MODEL_RUN_SCHEMA_VERSION",
                                GatewayCapabilitiesCodec.DURABLE_MODEL_RUN_SCHEMA_VERSION,
                        "TRAINING_API_SCHEMA_VERSION",
                                GatewayCapabilitiesCodec.TRAINING_API_SCHEMA_VERSION);
        for (var protocol : expected.entrySet()) {
            var declaration =
                    Pattern.compile("(?m)^" + protocol.getKey() + "\\s*=\\s*(\\d+)\\s*$")
                            .matcher(versions);
            assertTrue(declaration.find(), "Missing backend protocol: " + protocol.getKey());
            assertEquals(
                    protocol.getValue().intValue(),
                    Integer.parseInt(declaration.group(1)),
                    "Rebuild the bundled backend: " + protocol.getKey());
            assertFalse(declaration.find(), "Duplicate backend protocol: " + protocol.getKey());
        }
    }

    @Test
    void publishesRuntimeUnderItsContentDigestAndRepairsCorruption() throws Exception {
        byte[] wheelBytes = "exact wheel bytes".getBytes();
        byte[] lockBytes = "dependency==1.0\n".getBytes();
        var runtime = new BundledCliRuntime(temp, wheelBytes, lockBytes);

        var prepared = runtime.prepare();
        assertTrue(prepared.wheel().startsWith(temp.resolve(runtime.runtimeDigest())));
        assertArrayEquals(wheelBytes, Files.readAllBytes(prepared.wheel()));
        assertArrayEquals(lockBytes, Files.readAllBytes(prepared.lock()));

        Files.writeString(prepared.wheel(), "corrupt");
        Files.writeString(prepared.lock(), "corrupt");
        var repaired = runtime.prepare();
        assertArrayEquals(wheelBytes, Files.readAllBytes(repaired.wheel()));
        assertArrayEquals(lockBytes, Files.readAllBytes(repaired.lock()));
    }

    @Test
    void installationRequiresExecutableAndExactReceipt() throws Exception {
        var runtime = new BundledCliRuntime(temp, "wheel".getBytes(), "lock".getBytes());
        assertFalse(runtime.isInstalled());
        assertFalse(runtime.launchExecutable().equals(runtime.executable()));
        assertThrows(IOException.class, runtime::markInstalled);

        Path executable = runtime.executable();
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "installed");
        executable.toFile().setExecutable(true);
        runtime.markInstalled();

        assertTrue(runtime.isInstalled());
        assertTrue(runtime.launchExecutable().equals(runtime.executable()));
    }

    @Test
    void publicationFailurePreservesExistingTargetAndCleansStage() throws Exception {
        var runtime = new BundledCliRuntime(temp, "wheel".getBytes(), "lock".getBytes());
        Path target = temp.resolve(runtime.runtimeDigest()).resolve(BundledCliRuntime.WHEEL_NAME);
        Path sentinel = Files.createDirectories(target).resolve("existing");
        Files.writeString(sentinel, "unchanged");

        assertThrows(IOException.class, runtime::prepare);

        assertEquals("unchanged", Files.readString(sentinel));
        try (var paths = Files.list(target.getParent())) {
            assertTrue(
                    paths.noneMatch(
                            path -> path.getFileName().toString().startsWith(".histoforge-")));
        }
    }
}
