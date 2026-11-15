package qupath.ext.histoforge.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import qupath.ext.histoforge.platform.ExtensionRelease;

/** Exact HistoForge CLI wheel, dependency lock, and content-addressed private installation. */
final class BundledCliRuntime {

    static final String WHEEL_NAME = ExtensionRelease.current().bundledWheel();
    static final String LOCK_NAME = "requirements-bundled-cli.lock";
    // Includes atomic metadata writes to long Windows paths (astral-sh/uv#21627).
    static final String UV_VERSION = "0.12.16";
    static final String PYTHON_VERSION = "3.12.11";
    private static final int INSTALL_PROTOCOL_VERSION = 3;
    private static final String WHEEL_RESOURCE = "/qupath/ext/histoforge/runtime/" + WHEEL_NAME;
    private static final String LOCK_RESOURCE = "/qupath/ext/histoforge/runtime/" + LOCK_NAME;
    private static final String RECEIPT_NAME = "installed-runtime.sha256";
    private static final BundledCliRuntime CURRENT =
            new BundledCliRuntime(
                    defaultRoot(),
                    loadBundledResource(WHEEL_RESOURCE),
                    loadBundledResource(LOCK_RESOURCE));

    private final Path root;
    private final byte[] wheelBytes;
    private final byte[] lockBytes;
    private final String runtimeDigest;

    BundledCliRuntime(Path root, byte[] wheelBytes, byte[] lockBytes) {
        this.root = root;
        this.wheelBytes = wheelBytes.clone();
        this.lockBytes = lockBytes.clone();
        this.runtimeDigest =
                sha256(
                        ("install-protocol="
                                        + INSTALL_PROTOCOL_VERSION
                                        + "\nuv="
                                        + UV_VERSION
                                        + "\npython="
                                        + PYTHON_VERSION
                                        + "\nos="
                                        + System.getProperty("os.name", "")
                                        + "\narch="
                                        + System.getProperty("os.arch", "")
                                        + "\nwheel="
                                        + sha256(this.wheelBytes)
                                        + "\nlock="
                                        + sha256(this.lockBytes)
                                        + "\n")
                                .getBytes(StandardCharsets.US_ASCII));
    }

    static BundledCliRuntime current() {
        return CURRENT;
    }

    String runtimeDigest() {
        return runtimeDigest;
    }

    Path executable() {
        String name = isWindows() ? "histoforge.exe" : "histoforge";
        return scriptsDirectory().resolve(name);
    }

    Path pythonExecutable() {
        String name = isWindows() ? "python.exe" : "python";
        return scriptsDirectory().resolve(name);
    }

    Path launchExecutable() {
        return isInstalled() ? executable() : bundleDirectory().resolve("not-installed");
    }

    boolean isInstalled() {
        Path executable = executable();
        if (!Files.isRegularFile(executable) || (!isWindows() && !Files.isExecutable(executable))) {
            return false;
        }
        try {
            return Files.readString(receipt(), StandardCharsets.US_ASCII)
                    .strip()
                    .equals(runtimeDigest);
        } catch (IOException e) {
            return false;
        }
    }

    Prepared prepare() throws IOException {
        Path directory = bundleDirectory();
        Files.createDirectories(directory);
        Path wheel = directory.resolve(WHEEL_NAME);
        publishIfMissingOrCorrupt(wheel, wheelBytes);
        Path lock = directory.resolve(LOCK_NAME);
        publishIfMissingOrCorrupt(lock, lockBytes);
        return new Prepared(wheel, lock);
    }

    void beginInstallation() throws IOException {
        Files.deleteIfExists(receipt());
    }

    void markInstalled() throws IOException {
        Path executable = executable();
        if (!Files.isRegularFile(executable) || (!isWindows() && !Files.isExecutable(executable))) {
            throw new IOException("uv did not install the bundled histoforge executable");
        }
        publishAtomically(receipt(), (runtimeDigest + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    private Path bundleDirectory() {
        return root.resolve(runtimeDigest);
    }

    Path venvDirectory() {
        return bundleDirectory().resolve("venv");
    }

    private Path scriptsDirectory() {
        return venvDirectory().resolve(isWindows() ? "Scripts" : "bin");
    }

    private Path receipt() {
        return bundleDirectory().resolve(RECEIPT_NAME);
    }

    private static Path defaultRoot() {
        return Path.of(System.getProperty("user.home", ""), ".histoforge", "runtime", "cli");
    }

    private static byte[] loadBundledResource(String resource) {
        try (InputStream input = BundledCliRuntime.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "bundled HistoForge CLI resource is missing: " + resource);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read bundled HistoForge CLI resource", e);
        }
    }

    private static void publishIfMissingOrCorrupt(Path target, byte[] bytes) throws IOException {
        if (!Files.isRegularFile(target)
                || !sha256(bytes).equals(sha256(Files.readAllBytes(target)))) {
            publishAtomically(target, bytes);
        }
    }

    private static void publishAtomically(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path stage = Files.createTempFile(target.getParent(), ".histoforge-", ".stage");
        try {
            Files.write(stage, bytes);
            Files.move(
                    stage,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    record Prepared(Path wheel, Path lock) {}
}
