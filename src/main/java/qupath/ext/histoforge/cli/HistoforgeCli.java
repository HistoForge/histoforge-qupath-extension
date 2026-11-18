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
import qupath.ext.histoforge.client.Deployment;
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
    public DeployResult deploy(ModalContext context, Consumer<String> onProgress)
            throws CliException {
        return inContext("deploy", context, onProgress);
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
    public DeployResult upgrade(Deployment target, Consumer<String> onProgress)
            throws CliException {
        return toDeployResult(runForSavedBackend("upgrade", target, onProgress, "--cancel-work"));
    }

    /** Verify the update target before any local work is stopped. */
    public Deployment checkUpgrade(Deployment target, Consumer<String> onProgress)
            throws CliException {
        JsonObject result = runForSavedBackend("upgrade", target, onProgress, "--check");
        return target.withEnvironment(getString(result, "environment"));
    }

    /** {@code histoforge connect}: join the gateway selected by one local Modal profile. */
    public DeployResult connect(ModalContext context, Consumer<String> onProgress)
            throws CliException {
        return inContext("connect", context, onProgress);
    }

    private DeployResult inContext(
            String command, ModalContext context, Consumer<String> onProgress) throws CliException {
        JsonObject result =
                runJson(
                        command,
                        context.profile(),
                        onProgress,
                        "--environment",
                        context.environment());
        if (!context.profile().equals(getString(result, "profile"))
                || !context.environment().equals(getString(result, "environment"))) {
            throw new CliException(
                    CliException.Type.BAD_OUTPUT,
                    "The runtime helper returned a different Modal location.");
        }
        return toDeployResult(result);
    }

    /** Refresh access to the captured backend, without joining a different deployment. */
    public DeployResult reconnect(Deployment target, Consumer<String> onProgress)
            throws CliException {
        return toDeployResult(runForSavedBackend("connect", target, onProgress));
    }

    private JsonObject runForSavedBackend(
            String command, Deployment target, Consumer<String> onProgress, String... arguments)
            throws CliException {
        List<String> flags = targetArguments(target, arguments);
        JsonObject result =
                runJson(command, target.modalProfile(), onProgress, flags.toArray(String[]::new));
        requireSavedBackendResult(command, target, flags.contains("--check"), result);
        return result;
    }

    private static List<String> targetArguments(Deployment target, String... arguments) {
        List<String> flags = new ArrayList<>(List.of(arguments));
        flags.addAll(
                List.of(
                        "--expected-gateway",
                        target.gatewayUrl(),
                        "--expected-deployment-id",
                        target.deploymentId()));
        if (target.modalEnvironment() != null) {
            flags.addAll(List.of("--environment", target.modalEnvironment()));
        }
        return flags;
    }

    private static void requireSavedBackendResult(
            String command, Deployment target, boolean checkOnly, JsonObject result)
            throws CliException {
        String status =
                switch (command) {
                    case "connect" -> "connected";
                    case "login" -> "authenticated";
                    case "huggingface-token" -> "saved";
                    default -> checkOnly ? "ready_to_update" : "upgraded";
                };
        String gateway = getString(result, "gateway_url");
        String environment = getString(result, "environment");
        if (!status.equals(getString(result, "status"))
                || !target.deploymentId().equals(getString(result, "deployment_id"))
                || !target.modalProfile().equals(getString(result, "profile"))
                || !target.modalAppName().equals(getString(result, "app_name"))
                || gateway == null
                || gateway.isBlank()
                || environment == null
                || environment.isBlank()
                || (target.modalEnvironment() != null
                        && !target.modalEnvironment().equals(environment))
                || ((!"upgrade".equals(command) || checkOnly)
                        && !target.gatewayUrl().equals(gateway))) {
            throw new CliException(
                    CliException.Type.BAD_OUTPUT,
                    "Could not verify the saved backend returned by the runtime helper.");
        }
    }

    /** Resolve the provider's actual location once for first setup's explicit actions. */
    public ModalContext login(String profile, Consumer<String> onProgress) throws CliException {
        ModalContext context = contextFromLogin(runJson("login", profile, onProgress), null);
        if (profile != null && !profile.equals(context.profile())) {
            throw new CliException(
                    CliException.Type.BAD_OUTPUT, "Modal returned a different profile.");
        }
        return context;
    }

    private static ModalContext contextFromLogin(JsonObject result, ModalContext expected)
            throws CliException {
        try {
            ModalContext context =
                    new ModalContext(
                            getString(result, "profile"), getString(result, "environment"));
            if (!"authenticated".equals(getString(result, "status"))
                    || (expected != null && !expected.equals(context))) {
                throw new IllegalArgumentException("unexpected login location");
            }
            return context;
        } catch (RuntimeException failure) {
            throw new CliException(
                    CliException.Type.BAD_OUTPUT,
                    "Could not confirm the Modal profile and environment.");
        }
    }

    /**
     * Verify and save a registry token via stdin. Never expose helper diagnostics or response text.
     */
    public ModalContext saveHuggingFaceToken(
            ModalContext context, Deployment target, String token, Consumer<String> onProgress)
            throws CliException {
        if (token == null
                || token.isBlank()
                || token.length() > 4096
                || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new CliException(
                    CliException.Type.CLI_ERROR,
                    "Enter a nonblank Hugging Face token without whitespace.");
        }
        // No HF credential enters login. Only its subsequent stdin call has closed output.
        JsonObject login =
                target != null
                        ? runForSavedBackend("login", target, onProgress)
                        : context == null
                                ? runJson("login", null, onProgress)
                                : runJson(
                                        "login",
                                        context.profile(),
                                        onProgress,
                                        "--environment",
                                        context.environment());
        ModalContext selected = contextFromLogin(login, target == null ? context : null);
        List<String> command = command("huggingface-token", selected.profile());
        command.addAll(
                target == null
                        ? List.of("--environment", selected.environment())
                        : targetArguments(target.withEnvironment(selected.environment())));
        byte[] input = token.getBytes(StandardCharsets.UTF_8);
        try {
            var result = runner.run(command, Map.of(), input, line -> {});
            JsonObject json = tryParse(result.stdout());
            if (result.exitCode() == 0
                    && json != null
                    && "saved".equals(getString(json, "status"))
                    && selected.profile().equals(getString(json, "profile"))
                    && selected.environment().equals(getString(json, "environment"))) {
                if (target != null) {
                    requireSavedBackendResult(
                            "huggingface-token",
                            target.withEnvironment(selected.environment()),
                            false,
                            json);
                }
                return selected;
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
                        case "GATEWAY_TARGET_MISMATCH" ->
                                "Could not verify the selected cloud backend. Check the Modal workspace and environment. No token was changed.";
                        case "IDENTITY_RECOVERY_REQUIRED" ->
                                "The cloud connection record is unavailable. No token was changed. Contact HistoForge for help.";
                        case "RECONNECT_REQUIRED" ->
                                "The saved cloud connection is unavailable. Restore the connection before saving a token. No token was changed.";
                        case null, default ->
                                "Could not confirm that Modal saved the token. Check Modal access and try again.";
                    };
            CliException.Type type =
                    switch (code) {
                        case "GATEWAY_TARGET_MISMATCH" -> CliException.Type.GATEWAY_TARGET_MISMATCH;
                        case "IDENTITY_RECOVERY_REQUIRED" ->
                                CliException.Type.IDENTITY_RECOVERY_REQUIRED;
                        case "RECONNECT_REQUIRED" -> CliException.Type.RECONNECT_REQUIRED;
                        case null, default -> CliException.Type.CLI_ERROR;
                    };
            throw new CliException(type, message);
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
            // Exception and response content may contain the candidate token.
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
        String code = json == null ? null : getString(json, "code");
        CliException.Type type =
                switch (code) {
                    case "GATEWAY_NOT_FOUND" -> CliException.Type.GATEWAY_NOT_FOUND;
                    case "GATEWAY_TARGET_MISMATCH" -> CliException.Type.GATEWAY_TARGET_MISMATCH;
                    case "RECONNECT_REQUIRED" -> CliException.Type.RECONNECT_REQUIRED;
                    case "IDENTITY_RECOVERY_REQUIRED" ->
                            CliException.Type.IDENTITY_RECOVERY_REQUIRED;
                    case null, default -> CliException.Type.CLI_ERROR;
                };
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

    /** Provider-verified location carried between first setup's explicit actions. */
    public record ModalContext(String profile, String environment) {
        public ModalContext {
            if (profile == null
                    || profile.isBlank()
                    || environment == null
                    || environment.isBlank()) {
                throw new IllegalArgumentException("Modal profile and environment are required");
            }
        }
    }
}
