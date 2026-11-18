package qupath.ext.histoforge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link UvInstaller}; a fake {@link ProcessRunner} stands in for real uv. */
class UvInstallerTest {

    @TempDir Path temp;

    private UvInstaller uv(ProcessRunner runner) {
        return new UvInstaller(
                runner,
                temp,
                false,
                new BundledCliRuntime(
                        temp, "bundled wheel".getBytes(), "dependency==1.0\n".getBytes()));
    }

    private Path privateUv(Path userHome, boolean windows) {
        return userHome.resolve(".histoforge/runtime/uv")
                .resolve(BundledCliRuntime.UV_VERSION)
                .resolve(windows ? "uv.exe" : "uv");
    }

    @Test
    void privateUvPinsTheQualifiedWindowsLongPathFix() {
        assertEquals("0.12.16", BundledCliRuntime.UV_VERSION);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void installHistoforgeUsesPrivateVenvAndVerifiesDependencyHashes(boolean windows)
            throws Exception {
        var captured = new ArrayList<List<String>>();
        var runtime =
                new BundledCliRuntime(
                        temp, "bundled wheel".getBytes(), "dependency==1.0\n".getBytes());
        ProcessRunner runner =
                (cmd, env, stdin, onErr) -> {
                    captured.add(List.copyOf(cmd));
                    if (cmd.contains(BundledCliRuntime.WHEEL_NAME)
                            || cmd.stream().anyMatch(arg -> arg.endsWith(".whl"))) {
                        Path executable = runtime.executable();
                        Files.createDirectories(executable.getParent());
                        Files.writeString(executable, "installed");
                        executable.toFile().setExecutable(true);
                    }
                    return new ProcessRunner.Completed(0, "");
                };
        new UvInstaller(runner, temp, windows, runtime).installHistoforge(line -> {});

        List<String> venv = captured.get(0);
        String uvPath = privateUv(temp, windows).toString();
        assertEquals(List.of(uvPath, "venv", "--quiet", "--clear"), venv.subList(0, 4));
        assertTrue(venv.contains("--no-config"));
        assertTrue(venv.contains("--no-project"));
        assertEquals(BundledCliRuntime.PYTHON_VERSION, venv.get(venv.indexOf("--python") + 1));
        assertTrue(venv.contains("--managed-python"));
        assertEquals(runtime.venvDirectory().toString(), venv.getLast());

        List<String> dependencies = captured.get(1);
        assertEquals(List.of(uvPath, "pip", "install", "--quiet"), dependencies.subList(0, 4));
        assertTrue(dependencies.contains("--require-hashes"));
        assertTrue(dependencies.contains("--no-build"));
        assertTrue(dependencies.getLast().endsWith(BundledCliRuntime.LOCK_NAME));

        List<String> wheel = captured.get(2);
        assertEquals(List.of(uvPath, "pip", "install", "--quiet"), wheel.subList(0, 4));
        assertTrue(wheel.contains("--no-deps"));
        assertTrue(wheel.contains("--no-index"));
        assertTrue(wheel.getLast().endsWith(BundledCliRuntime.WHEEL_NAME));
        assertEquals(List.of("self-check", "--json"), captured.get(3).subList(1, 3));
    }

    @Test
    void isInstalledTrueOnExitZero() {
        assertTrue(
                uv((cmd, env, stdin, onErr) ->
                                new ProcessRunner.Completed(
                                        0,
                                        "uv "
                                                + BundledCliRuntime.UV_VERSION
                                                + " (qualified build)"))
                        .isInstalled());
    }

    @Test
    void isInstalledRejectsDifferentUvRelease() {
        assertFalse(
                uv((cmd, env, stdin, onErr) -> new ProcessRunner.Completed(0, "uv 0.5.0"))
                        .isInstalled());
    }

    @Test
    void isInstalledFalseWhenExecutableMissing() {
        ProcessRunner missing =
                (cmd, env, stdin, onErr) -> {
                    throw new ProcessRunner.NotFoundException("uv not found", null);
                };
        assertFalse(uv(missing).isInstalled());
    }

    @Test
    void nonZeroExitBecomesCliError() {
        ProcessRunner failing = (cmd, env, stdin, onErr) -> new ProcessRunner.Completed(1, "");
        var e = assertThrows(CliException.class, () -> uv(failing).installHistoforge(line -> {}));
        assertEquals(CliException.Type.CLI_ERROR, e.type());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bootstrapCommandTargetsAstralInstaller(boolean windows) {
        List<String> args = UvInstaller.uvBootstrapCommand(windows);
        String command = String.join(" ", args);
        assertTrue(command.contains("astral.sh/uv/" + BundledCliRuntime.UV_VERSION + "/install"));
        assertEquals(windows ? "powershell" : "sh", args.getFirst());
        if (windows) {
            assertTrue(args.contains("-NoProfile"));
            assertTrue(args.contains("-NonInteractive"));
            assertTrue(command.endsWith("install.ps1 | iex"));
        } else {
            assertTrue(command.endsWith("install.sh | sh"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bootstrapAndProbeUseOnlyThePrivatePathWithoutShellInterpolation(boolean windows)
            throws Exception {
        Path userHome = temp.resolve("Researcher's home with spaces");
        var commands = new ArrayList<List<String>>();
        var environments = new ArrayList<Map<String, String>>();
        ProcessRunner runner =
                (cmd, env, stdin, onErr) -> {
                    commands.add(List.copyOf(cmd));
                    environments.add(Map.copyOf(env));
                    return new ProcessRunner.Completed(0, "uv " + BundledCliRuntime.UV_VERSION);
                };
        var installer =
                new UvInstaller(
                        runner,
                        userHome,
                        windows,
                        new BundledCliRuntime(temp, "wheel".getBytes(), "lock".getBytes()));

        installer.bootstrapUv(null);
        assertTrue(installer.isInstalled());

        Path executable = privateUv(userHome, windows);
        assertEquals(UvInstaller.uvBootstrapCommand(windows), commands.getFirst());
        assertFalse(String.join(" ", commands.getFirst()).contains(userHome.toString()));
        assertEquals(
                Map.of(
                        "UV_INSTALL_DIR", executable.getParent().toString(),
                        "UV_UNMANAGED_INSTALL", executable.getParent().toString()),
                environments.getFirst());
        assertEquals(List.of(executable.toString(), "--version"), commands.get(1));
        assertEquals(commands.get(1), commands.get(2));
        assertEquals(Map.of(), environments.get(1));
        assertEquals(Map.of(), environments.get(2));
    }

    @Test
    void missingPrivateUvNeverFallsBackToResearcherUv() throws Exception {
        Path sharedUv = temp.resolve(".local/bin/uv");
        Files.createDirectories(sharedUv.getParent());
        Files.writeString(sharedUv, "researcher-owned newer uv");
        sharedUv.toFile().setExecutable(true);
        var commands = new ArrayList<List<String>>();
        ProcessRunner runner =
                (cmd, env, stdin, onErr) -> {
                    commands.add(List.copyOf(cmd));
                    if (cmd.getFirst().equals(privateUv(temp, false).toString())) {
                        throw new ProcessRunner.NotFoundException("private uv absent", null);
                    }
                    return new ProcessRunner.Completed(0, "uv " + BundledCliRuntime.UV_VERSION);
                };

        assertFalse(uv(runner).isInstalled());
        assertEquals(List.of(List.of(privateUv(temp, false).toString(), "--version")), commands);
        assertEquals("researcher-owned newer uv", Files.readString(sharedUv));
    }

    @Test
    void failedBootstrapDoesNotProbeOrInstallTheHelper() {
        var commands = new ArrayList<List<String>>();
        ProcessRunner runner =
                (cmd, env, stdin, onErr) -> {
                    commands.add(List.copyOf(cmd));
                    return new ProcessRunner.Completed(1, "");
                };
        var failure = assertThrows(CliException.class, () -> uv(runner).bootstrapUv(null));
        assertEquals(CliException.Type.CLI_ERROR, failure.type());
        assertEquals(List.of(UvInstaller.uvBootstrapCommand(false)), commands);
    }

    @Test
    void failedBootstrapShowsCapturedInstallerOutput() {
        ProcessRunner failing =
                (cmd, env, stdin, onErr) -> {
                    onErr.accept("stderr detail");
                    return new ProcessRunner.Completed(
                            1, "Downloading uv\r\nArchive download failed\r\n");
                };
        var progress = new ArrayList<String>();

        var failure =
                assertThrows(CliException.class, () -> uv(failing).bootstrapUv(progress::add));

        assertEquals(CliException.Type.CLI_ERROR, failure.type());
        assertEquals("failed to install uv (exit 1)", failure.getMessage());
        assertEquals(
                List.of("stderr detail", "Downloading uv", "Archive download failed"), progress);
    }

    @Test
    void failedBootstrapBoundsOutputAndKeepsTheFinalError() {
        String detail = "x".repeat(20_000) + "\nArchive download failed";
        ProcessRunner failing = (cmd, env, stdin, onErr) -> new ProcessRunner.Completed(1, detail);
        var progress = new ArrayList<String>();

        assertThrows(CliException.class, () -> uv(failing).bootstrapUv(progress::add));

        assertEquals("Installer output (last 8192 characters):", progress.getFirst());
        assertEquals("Archive download failed", progress.getLast());
        assertEquals(
                detail.substring(detail.length() - 8192),
                String.join("\n", progress.subList(1, progress.size())));
        assertThrows(CliException.class, () -> uv(failing).bootstrapUv(null));
    }

    @Test
    void bootstrapIoFailureIsVisibleWithoutFallback() {
        var commands = new ArrayList<List<String>>();
        ProcessRunner runner =
                (cmd, env, stdin, onErr) -> {
                    commands.add(List.copyOf(cmd));
                    throw new IOException("private directory is not writable");
                };
        var failure = assertThrows(CliException.class, () -> uv(runner).bootstrapUv(null));
        assertEquals(CliException.Type.CLI_ERROR, failure.type());
        assertTrue(failure.getMessage().contains("private directory is not writable"));
        assertEquals(1, commands.size());
    }

    @Test
    void interruptedBootstrapPreservesCancellation() {
        ProcessRunner runner =
                (cmd, env, stdin, onErr) -> {
                    throw new InterruptedException("cancelled");
                };
        try {
            var failure = assertThrows(CliException.class, () -> uv(runner).bootstrapUv(null));
            assertEquals(CliException.Type.INTERRUPTED, failure.type());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void bootstrapRequiresTheExactPinnedUvRelease() {
        ProcessRunner wrongRelease =
                (cmd, env, stdin, onErr) ->
                        new ProcessRunner.Completed(0, cmd.contains("--version") ? "uv 0.5.0" : "");

        var failure =
                assertThrows(CliException.class, () -> uv(wrongRelease).bootstrapUv(line -> {}));

        assertEquals(CliException.Type.CLI_ERROR, failure.type());
        assertTrue(failure.getMessage().contains(BundledCliRuntime.UV_VERSION));
    }

    @Test
    void failedSelfCheckDoesNotPublishInstallationReceipt() throws Exception {
        var runtime =
                new BundledCliRuntime(
                        temp, "bundled wheel".getBytes(), "dependency==1.0\n".getBytes());
        Path existing = runtime.executable();
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "previous installation");
        existing.toFile().setExecutable(true);
        runtime.markInstalled();
        assertTrue(runtime.isInstalled());
        ProcessRunner runner =
                (cmd, env, stdin, onErr) -> {
                    if (cmd.get(0).equals(privateUv(temp, false).toString())) {
                        Path executable = runtime.executable();
                        Files.createDirectories(executable.getParent());
                        Files.writeString(executable, "installed");
                        executable.toFile().setExecutable(true);
                        return new ProcessRunner.Completed(0, "");
                    }
                    return new ProcessRunner.Completed(1, "");
                };

        var installer = new UvInstaller(runner, temp, false, runtime);
        assertThrows(CliException.class, () -> installer.installHistoforge(line -> {}));
        assertFalse(runtime.isInstalled());
    }
}
