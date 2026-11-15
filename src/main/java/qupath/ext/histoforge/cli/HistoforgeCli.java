package qupath.ext.histoforge.cli;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import qupath.ext.histoforge.platform.ExtensionRelease;

/**
 * Drives the {@code histoforge} CLI from behind plugin buttons: runs {@code histoforge <cmd> --json
 * [-p profile]} as a subprocess, parses the clean-JSON stdout into a typed result ({@link
 * StatusResult} / {@link DeployResult}), and surfaces failures as a {@link CliException} the UI can
 * branch on. Long-running commands ({@code deploy}/{@code upgrade}/{@code connect}) stream their
 * stderr, including Modal build logs and the browser-auth prompt, to an {@code onProgress} callback
 * as it arrives.
 *
 * <p>The result vocabulary and the subprocess plumbing live here as nested types: one cohesive unit
 * rather than a scatter of one-line files. The {@link ProcessRunner} seam keeps it unit-testable:
 * the default constructor spawns real processes; the package-private constructor takes a fake
 * runner + executable supplier so tests assert argument construction and JSON mapping without a
 * real CLI. Run each call off the FX thread.
 */
public final class HistoforgeCli {

    private static final Gson GSON = new Gson();

    private final ProcessRunner runner;
    private final Supplier<String> exe;

    public HistoforgeCli() {
        this(ProcessRunner.SYSTEM, () -> BundledCliRuntime.current().launchExecutable().toString());
    }

    HistoforgeCli(ProcessRunner runner, Supplier<String> exe) {
        this.runner = runner;
        this.exe = exe;
    }

    // ── Commands ────────────────────────────────────────────────────

    /** {@code histoforge status}: read-only deployment and connection state (no Modal auth). */
    public StatusResult status(String profile) throws CliException {
        JsonObject j = runJson("status", profile, null);
        if ("not_configured".equals(getString(j, "status"))) {
            return new StatusResult(
                    StatusResult.Type.NOT_CONFIGURED,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    null,
                    getString(j, "hint"));
        }
        return new StatusResult(
                StatusResult.Type.CONFIGURED,
                getString(j, "gateway_url"),
                getString(j, "profile"),
                getString(j, "app_name"),
                j.has("reachable") && j.get("reachable").getAsBoolean(),
                j.has("connected") && j.get("connected").getAsBoolean(),
                getInteger(j, "api_major"),
                getLong(j, "auth_revision"),
                null);
    }

    /** {@code histoforge deploy}: provision the gateway (may open a browser for Modal login). */
    public DeployResult deploy(String profile, Consumer<String> onProgress) throws CliException {
        return toDeployResult(runJson("deploy", profile, onProgress));
    }

    /** Verify the bundled helper before interrupting any local work. */
    public void verifyRuntime() throws CliException {
        JsonObject result = runJson("self-check", null, null);
        if (!"ok".equals(getString(result, "status"))
                || !ExtensionRelease.current()
                        .bundledBackendVersion()
                        .equals(getString(result, "version"))) {
            throw new CliException(
                    CliException.Type.BAD_OUTPUT,
                    "The installed helper does not match this extension's bundled backend.");
        }
    }

    /** Explicitly confirmed cancellation followed by deployment; never call before confirmation. */
    public DeployResult upgrade(String profile, Consumer<String> onProgress) throws CliException {
        return toDeployResult(runJson("upgrade", profile, onProgress, "--cancel-work"));
    }

    /** {@code histoforge connect}: join the gateway selected by one local Modal profile. */
    public DeployResult connect(String profile, Consumer<String> onProgress) throws CliException {
        return toDeployResult(runJson("connect", profile, onProgress));
    }

    /**
     * Verify and save a registry token via stdin. Never expose helper diagnostics or response text.
     */
    public String saveHuggingFaceToken(String profile, String token, Consumer<String> onProgress)
            throws CliException {
        if (token == null
                || token.isBlank()
                || token.length() > 4096
                || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new CliException(
                    CliException.Type.CLI_ERROR,
                    "Enter a nonblank Hugging Face token without whitespace.");
        }
        // Login never receives the HF token. Its browser URL/code are ordinary
        // visible progress; the subsequent credential operation is closed-output.
        JsonObject login = runJson("login", profile, onProgress);
        String selected;
        String loginStatus;
        try {
            selected = getString(login, "profile");
            loginStatus = getString(login, "status");
        } catch (RuntimeException malformed) {
            throw new CliException(
                    CliException.Type.BAD_OUTPUT, "Could not confirm the selected Modal profile.");
        }
        if (!"authenticated".equals(loginStatus)
                || selected == null
                || selected.isBlank()
                || (profile != null && !profile.isBlank() && !profile.equals(selected))) {
            throw new CliException(
                    CliException.Type.BAD_OUTPUT, "Could not confirm the selected Modal profile.");
        }
        byte[] input = token.getBytes(StandardCharsets.UTF_8);
        try {
            var result =
                    runner.run(command("huggingface-token", selected), Map.of(), input, line -> {});
            JsonObject json = tryParse(result.stdout());
            String selectedProfile = json == null ? null : getString(json, "profile");
            if (result.exitCode() == 0
                    && json != null
                    && "saved".equals(getString(json, "status"))
                    && selectedProfile != null
                    && !selectedProfile.isBlank()
                    && selected.equals(selectedProfile)) {
                return selectedProfile;
            }
            String code = json == null ? null : getString(json, "code");
            String message =
                    switch (code) {
                        case "HF_TOKEN_INVALID" ->
                                "Enter a nonblank Hugging Face token without whitespace.";
                        case "HF_TOKEN_REJECTED" ->
                                "Hugging Face rejected this token. Check or replace it.";
                        case "HF_VERIFICATION_FAILED" ->
                                "Could not verify the token with Hugging Face. Try again.";
                        case "MODAL_AUTH_FAILED" ->
                                "Could not authenticate with the selected Modal profile.";
                        case null, default ->
                                "Could not confirm that Modal saved the token. Check Modal access and try again.";
                    };
            throw new CliException(CliException.Type.CLI_ERROR, message);
        } catch (ProcessRunner.NotFoundException failure) {
            throw new CliException(
                    CliException.Type.NOT_INSTALLED,
                    "The bundled runtime helper must be installed first.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CliException(
                    CliException.Type.INTERRUPTED,
                    "Token setup interrupted. If saving had started, retry to confirm it.");
        } catch (IOException | RuntimeException failure) {
            // Even malformed JSON and OS/SDK exceptions can contain request material.
            // Do not retain the exception as a cause or echo any stdout/stderr snippet.
            throw new CliException(
                    CliException.Type.CLI_ERROR,
                    "Could not confirm that Modal saved the token. Check Modal access and try again.");
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    private List<String> command(String subcommand, String profile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.get());
        cmd.add(subcommand);
        cmd.add("--json");
        if (profile != null && !profile.isBlank()) {
            cmd.add("-p");
            cmd.add(profile);
        }
        return cmd;
    }

    private JsonObject runJson(
            String subcommand, String profile, Consumer<String> onProgress, String... arguments)
            throws CliException {
        List<String> cmd = command(subcommand, profile);
        cmd.addAll(List.of(arguments));

        // Tee stderr to the progress callback while keeping a bounded tail for error reporting
        // (a SystemExit error, for example connect's "No gateway found", prints to stderr).
        StringBuilder stderrTail = new StringBuilder();
        Consumer<String> sink =
                line -> {
                    if (onProgress != null) {
                        onProgress.accept(line);
                    }
                    if (stderrTail.length() < 8192) {
                        stderrTail.append(line).append('\n');
                    }
                };

        ProcessRunner.Completed result;
        try {
            result = runner.run(cmd, sink);
        } catch (ProcessRunner.NotFoundException e) {
            throw new CliException(
                    CliException.Type.NOT_INSTALLED,
                    "the configured histoforge CLI is not installed");
        } catch (IOException e) {
            throw new CliException(
                    CliException.Type.CLI_ERROR,
                    "could not run histoforge " + subcommand + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(CliException.Type.INTERRUPTED, "cancelled");
        }

        JsonObject json = tryParse(result.stdout());
        if (result.exitCode() == 0) {
            if (json == null) {
                throw new CliException(
                        CliException.Type.BAD_OUTPUT,
                        "histoforge "
                                + subcommand
                                + " returned no JSON: "
                                + snippet(result.stdout()));
            }
            return json;
        }

        // Non-zero exit: the error is either a status/error JSON on stdout (the
        // generic Exception path) or plain text on stderr (a SystemExit). Prefer the typed field.
        String message = json != null ? getString(json, "error") : null;
        if (message == null || message.isBlank()) {
            message = stderrTail.toString().strip();
        }
        if (message.isBlank()) {
            message = "histoforge " + subcommand + " exited " + result.exitCode();
        }
        CliException.Type type =
                json != null && "GATEWAY_NOT_FOUND".equals(getString(json, "code"))
                        ? CliException.Type.GATEWAY_NOT_FOUND
                        : CliException.Type.CLI_ERROR;
        throw new CliException(type, message);
    }

    private static DeployResult toDeployResult(JsonObject j) {
        return new DeployResult(
                getString(j, "status"),
                getString(j, "gateway_url"),
                getString(j, "profile"),
                getString(j, "app_name"));
    }

    // ── JSON helpers ────────────────────────────────────────────────

    private static JsonObject tryParse(String stdout) {
        String line = lastNonBlankLine(stdout);
        if (line == null) {
            return null;
        }
        try {
            return GSON.fromJson(line, JsonObject.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    private static String lastNonBlankLine(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String stripped = lines[i].strip();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }
        return null;
    }

    private static String snippet(String s) {
        String t = s == null ? "" : s.strip();
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }

    private static String getString(JsonObject j, String key) {
        return j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsString() : null;
    }

    private static Integer getInteger(JsonObject j, String key) {
        return j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsInt() : null;
    }

    private static Long getLong(JsonObject j, String key) {
        return j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsLong() : null;
    }

    // ── Result vocabulary ───────────────────────────────────────────

    /**
     * Parsed result of {@code status}. {@link Type#NOT_CONFIGURED} carries only {@link #hint};
     * {@link Type#CONFIGURED} carries the deployment fields plus live reachability, authenticated
     * compatibility, API major, and credential revision.
     */
    public record StatusResult(
            Type type,
            String gatewayUrl,
            String profile,
            String appName,
            boolean reachable,
            boolean connected,
            Integer apiMajor,
            Long authRevision,
            String hint) {

        public enum Type {
            NOT_CONFIGURED,
            CONFIGURED
        }
    }

    /**
     * Parsed result of {@code deploy}, {@code upgrade}, or {@code connect}: they share the shape
     * {@code {status, gateway_url, profile, app_name}}. The raw token is never in stdout (it lands
     * in {@code ~/.histoforge/deployment.json}). {@link #status} is {@code deployed} / {@code
     * upgraded} / {@code connected}.
     */
    public record DeployResult(String status, String gatewayUrl, String profile, String appName) {}
}
