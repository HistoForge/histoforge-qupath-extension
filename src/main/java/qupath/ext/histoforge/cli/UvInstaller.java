package qupath.ext.histoforge.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Installs the toolchain the zero-terminal setup needs: a private pinned uv and the exact CLI wheel
 * bundled in this extension in a content-addressed private environment. When the private uv release
 * is missing, it bootstraps that release without changing the researcher's uv or shell. Shells out
 * through the shared {@link ProcessRunner} seam, so it's unit-testable with a fake (no real uv or
 * network).
 */
public final class UvInstaller {

    private static final int MAX_FAILURE_OUTPUT_CHARS = 8192;

    private final ProcessRunner runner;
    private final Path uvExecutable;
    private final boolean windows;
    private final BundledCliRuntime runtime;

    public UvInstaller() {
        this(
                ProcessRunner.SYSTEM,
                Path.of(System.getProperty("user.home", "")),
                isWindows(),
                BundledCliRuntime.current());
    }

    UvInstaller(ProcessRunner runner, Path userHome, boolean windows, BundledCliRuntime runtime) {
        this.runner = runner;
        this.windows = windows;
        this.uvExecutable =
                userHome.toAbsolutePath()
                        .normalize()
                        .resolve(".histoforge/runtime/uv")
                        .resolve(BundledCliRuntime.UV_VERSION)
                        .resolve(windows ? "uv.exe" : "uv");
        this.runtime = runtime;
    }

    /** True only when the exact qualified uv release is available in HistoForge's directory. */
    public boolean isInstalled() {
        try {
            ProcessRunner.Completed result =
                    runner.run(List.of(uvExecutable.toString(), "--version"), line -> {});
            return result.exitCode() == 0 && isExpectedUvVersion(result.stdout());
        } catch (IOException e) {
            return false; // NotFoundException (uv absent) or other I/O failure
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Install the exact bundled wheel and hash-verified dependency closure privately. */
    public void installHistoforge(Consumer<String> onProgress) throws CliException {
        BundledCliRuntime.Prepared prepared;
        try {
            prepared = runtime.prepare();
            runtime.beginInstallation();
        } catch (IOException e) {
            throw new CliException(
                    CliException.Type.CLI_ERROR,
                    "could not prepare the bundled histoforge CLI: " + e.getMessage());
        }
        run(
                List.of(
                        uvExecutable.toString(),
                        "venv",
                        "--quiet",
                        "--clear",
                        "--no-config",
                        "--no-project",
                        "--python",
                        BundledCliRuntime.PYTHON_VERSION,
                        "--managed-python",
                        runtime.venvDirectory().toString()),
                Map.of(),
                onProgress,
                "create the histoforge environment");
        run(
                List.of(
                        uvExecutable.toString(),
                        "pip",
                        "install",
                        "--quiet",
                        "--no-config",
                        "--python",
                        runtime.pythonExecutable().toString(),
                        "--require-hashes",
                        "--no-build",
                        "--requirements",
                        prepared.lock().toString()),
                Map.of(),
                onProgress,
                "install the histoforge dependency lock");
        run(
                List.of(
                        uvExecutable.toString(),
                        "pip",
                        "install",
                        "--quiet",
                        "--no-config",
                        "--python",
                        runtime.pythonExecutable().toString(),
                        "--no-build",
                        "--no-deps",
                        "--no-index",
                        prepared.wheel().toString()),
                Map.of(),
                onProgress,
                "install the bundled histoforge wheel");
        run(
                List.of(runtime.executable().toString(), "self-check", "--json"),
                Map.of(),
                onProgress,
                "verify histoforge");
        try {
            runtime.markInstalled();
        } catch (IOException e) {
            throw new CliException(CliException.Type.CLI_ERROR, e.getMessage());
        }
    }

    /** Bootstrap only our pinned uv, without shell edits, a shared receipt, or self-updates. */
    public void bootstrapUv(Consumer<String> onProgress) throws CliException {
        String directory = uvExecutable.getParent().toString();
        // The pinned installer gives UV_INSTALL_DIR precedence over UV_UNMANAGED_INSTALL.
        // Set both so an inherited researcher setting cannot redirect our private install.
        run(
                uvBootstrapCommand(windows),
                Map.of("UV_INSTALL_DIR", directory, "UV_UNMANAGED_INSTALL", directory),
                onProgress,
                "install uv");
        ProcessRunner.Completed version =
                run(
                        List.of(uvExecutable.toString(), "--version"),
                        Map.of(),
                        onProgress,
                        "verify uv");
        if (!isExpectedUvVersion(version.stdout())) {
            throw new CliException(
                    CliException.Type.CLI_ERROR,
                    "the uv installer did not provide the required uv "
                            + BundledCliRuntime.UV_VERSION);
        }
    }

    private ProcessRunner.Completed run(
            List<String> command,
            Map<String, String> environment,
            Consumer<String> onProgress,
            String what)
            throws CliException {
        Consumer<String> sink = onProgress != null ? onProgress : line -> {};
        ProcessRunner.Completed result;
        try {
            result = runner.run(command, environment, sink);
        } catch (ProcessRunner.NotFoundException e) {
            throw new CliException(CliException.Type.NOT_INSTALLED, command.get(0) + " not found");
        } catch (IOException e) {
            throw new CliException(
                    CliException.Type.CLI_ERROR, "could not " + what + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(CliException.Type.INTERRUPTED, "cancelled");
        }
        if (result.exitCode() != 0) {
            // The Windows uv installer reports caught errors on stdout, not stderr.
            // These installation commands contain no account credentials; keep their final
            // diagnostic output without changing the shared account-command logging path.
            String output = result.stdout().strip();
            if (output.length() > MAX_FAILURE_OUTPUT_CHARS) {
                sink.accept("Installer output (last " + MAX_FAILURE_OUTPUT_CHARS + " characters):");
                output = output.substring(output.length() - MAX_FAILURE_OUTPUT_CHARS);
            }
            output.lines().forEach(sink);
            throw new CliException(
                    CliException.Type.CLI_ERROR,
                    "failed to " + what + " (exit " + result.exitCode() + ")");
        }
        return result;
    }

    private static boolean isExpectedUvVersion(String output) {
        String expected = "uv " + BundledCliRuntime.UV_VERSION;
        String observed = output.strip();
        return observed.equals(expected) || observed.startsWith(expected + " ");
    }

    /** The official uv installer command for this OS (curl | sh on Unix, PowerShell on Windows). */
    static List<String> uvBootstrapCommand(boolean windows) {
        if (windows) {
            return List.of(
                    "powershell",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy",
                    "ByPass",
                    "-Command",
                    "irm https://astral.sh/uv/"
                            + BundledCliRuntime.UV_VERSION
                            + "/install.ps1 | iex");
        }
        return List.of(
                "sh",
                "-c",
                "curl -LsSf https://astral.sh/uv/"
                        + BundledCliRuntime.UV_VERSION
                        + "/install.sh | sh");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
