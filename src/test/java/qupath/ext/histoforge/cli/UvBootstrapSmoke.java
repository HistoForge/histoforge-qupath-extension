package qupath.ext.histoforge.cli;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in macOS bootstrap check using the real pinned installer in a new disposable directory. Does
 * not install Python/the helper, contact a gateway, or change the actual user home or PATH. The
 * Windows command contract is unit-tested separately; this is not Windows qualification.
 */
public final class UvBootstrapSmoke {

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").startsWith("Mac")) {
            throw new IllegalStateException("This native bootstrap probe qualifies macOS only");
        }
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Supply a new disposable evidence directory");
        }
        Path root = Files.createDirectory(Path.of(args[0]).toAbsolutePath());
        Path simulatedUserHome = root.resolve("Researcher's home with spaces");
        Path sharedBin = Files.createDirectories(simulatedUserHome.resolve(".local/bin"));
        Path sharedUv = sharedBin.resolve("uv");
        Files.writeString(sharedUv, "#!/bin/sh\nprintf 'uv 99.0.0 (researcher-owned)\\n'\n");
        require(sharedUv.toFile().setExecutable(true), "Could not create executable fixture");
        Files.writeString(sharedBin.resolve("uvx"), "researcher-owned uvx\n");
        Path sharedReceipt = simulatedUserHome.resolve(".config/uv/uv-receipt.json");
        Files.createDirectories(sharedReceipt.getParent());
        Files.writeString(sharedReceipt, "researcher-owned receipt\n");
        List<String> protectedNames =
                List.of(
                        ".local/bin/uv",
                        ".local/bin/uvx",
                        ".config/uv/uv-receipt.json",
                        ".profile",
                        ".bashrc",
                        ".bash_profile",
                        ".bash_login",
                        ".zshrc",
                        ".zshenv",
                        ".config/fish/conf.d/uv.env.fish");
        for (String name : protectedNames.subList(3, protectedNames.size())) {
            Path profile = simulatedUserHome.resolve(name);
            Files.createDirectories(profile.getParent());
            Files.writeString(profile, "# researcher-owned shell configuration\n");
        }
        Path actualUserHome = Path.of(System.getProperty("user.home"));
        var protectedPaths = new ArrayList<Path>();
        for (String name : protectedNames) {
            protectedPaths.add(simulatedUserHome.resolve(name));
            protectedPaths.add(actualUserHome.resolve(name));
        }
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfig != null && !xdgConfig.isBlank()) {
            protectedPaths.add(Path.of(xdgConfig, "uv", "uv-receipt.json"));
        }
        Map<String, String> before = snapshot(protectedPaths);
        String inheritedPath = System.getenv("PATH");
        var calls = new ArrayList<Map<String, Object>>();
        var report = new LinkedHashMap<String, Object>();
        report.put("started_at", Instant.now().toString());
        report.put("pid", ProcessHandle.current().pid());
        report.put("java_version", System.getProperty("java.version"));
        report.put("uv_version", BundledCliRuntime.UV_VERSION);
        report.put(
                "code_origin",
                UvInstaller.class.getProtectionDomain().getCodeSource().getLocation());
        report.put("protected_before", before);
        report.put("calls", calls);
        report.put("status", "RUNNING");
        ProcessRunner runner =
                (command, environment, stdin, sink) -> {
                    var childEnvironment = new LinkedHashMap<String, String>();
                    childEnvironment.put("PATH", sharedBin + ":" + inheritedPath);
                    // Real inherited override precedence must not redirect the installer.
                    childEnvironment.put("UV_INSTALL_DIR", sharedBin.toString());
                    childEnvironment.put("CARGO_DIST_FORCE_INSTALL_DIR", sharedBin.toString());
                    childEnvironment.put("UV_UNMANAGED_INSTALL", sharedBin.toString());
                    childEnvironment.putAll(environment);
                    var call = new LinkedHashMap<String, Object>();
                    call.put("command", List.copyOf(command));
                    call.put("bootstrap_overrides", Map.copyOf(environment));
                    calls.add(call);
                    try {
                        var result =
                                ProcessRunner.SYSTEM.run(command, childEnvironment, stdin, sink);
                        call.put("exit_code", result.exitCode());
                        call.put("stdout", result.stdout());
                        return result;
                    } catch (Exception failure) {
                        call.put("failure", failure.toString());
                        throw failure;
                    }
                };
        try {
            var installer =
                    new UvInstaller(
                            runner,
                            simulatedUserHome,
                            false,
                            new BundledCliRuntime(
                                    root.resolve("unused-helper"), new byte[0], new byte[0]));
            require(!installer.isInstalled(), "Missing private uv must not use shared uv");
            installer.bootstrapUv(System.out::println);
            require(installer.isInstalled(), "The private pinned uv was not installed");
            Path uv =
                    simulatedUserHome
                            .resolve(".histoforge/runtime/uv")
                            .resolve(BundledCliRuntime.UV_VERSION)
                            .resolve("uv");
            require(Files.isExecutable(uv), "Private uv is not executable");
            report.put("private_uv", uv.toString());
            report.put("private_uv_sha256", sha256(Files.readAllBytes(uv)));
            report.put("status", "PASS");
        } catch (Exception | AssertionError failure) {
            report.put("status", "FAILED");
            report.put("failure", failure.toString());
            throw failure;
        } finally {
            Map<String, String> after = snapshot(protectedPaths);
            boolean preserved = before.equals(after) && inheritedPath.equals(System.getenv("PATH"));
            report.put("protected_after", after);
            report.put("shared_files_and_parent_path_unchanged", preserved);
            report.put("finished_at", Instant.now().toString());
            if (!preserved) report.put("status", "FAILED");
            Files.writeString(
                    root.resolve("receipt.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            require(preserved, "Bootstrap changed protected uv or shell state");
        }
        System.out.println("PASS: private uv bootstrap, shared tool and shell state unchanged");
    }

    private static Map<String, String> snapshot(List<Path> paths) throws Exception {
        var result = new LinkedHashMap<String, String>();
        for (Path path : paths) {
            result.put(
                    path.toString(),
                    Files.exists(path) ? sha256(Files.readAllBytes(path)) : "ABSENT");
        }
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
