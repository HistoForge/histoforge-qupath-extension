package qupath.ext.histoforge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.cli.HistoforgeCli.ModalContext;
import qupath.ext.histoforge.cli.HistoforgeCli.StatusResult;
import qupath.ext.histoforge.cli.ProcessRunner.NotFoundException;
import qupath.ext.histoforge.client.Deployment;

/**
 * Unit tests for {@link HistoforgeCli}. A lambda {@link ProcessRunner} fake stands in for the real
 * subprocess (no Mockito, no CLI, no network), so these assert argument construction, JSON→record
 * mapping, stderr streaming, and the error paths.
 */
class HistoforgeCliTest {

    /** Records the command it received and replays a canned (exit, stdout) + optional stderr. */
    private static final class FakeRunner implements ProcessRunner {
        List<String> command;
        private final int exit;
        private final String stdout;
        private final List<String> stderr;

        FakeRunner(int exit, String stdout, String... stderr) {
            this.exit = exit;
            this.stdout = stdout;
            this.stderr = List.of(stderr);
        }

        @Override
        public Completed run(
                List<String> command,
                Map<String, String> environment,
                byte[] stdin,
                Consumer<String> onStderrLine) {
            this.command = command;
            stderr.forEach(onStderrLine);
            return new Completed(exit, stdout);
        }
    }

    private static HistoforgeCli cli(ProcessRunner runner) {
        return new HistoforgeCli(runner, () -> "histoforge");
    }

    private static Deployment target() {
        return new Deployment(
                1,
                "deployment-a",
                "https://gateway.example",
                "lab",
                "histoforge-gateway",
                1,
                "local-test-token",
                Instant.parse("2026-11-16T00:00:00.000Z"));
    }

    private static String targetResult(String status) {
        return "{\"status\":\""
                + status
                + "\",\"deployment_id\":\"deployment-a\",\"gateway_url\":\"https://gateway.example\","
                + "\"profile\":\"lab\",\"app_name\":\"histoforge-gateway\",\"environment\":\"research\"}";
    }

    // ── Argument construction ───────────────────────────────────────

    @Test
    void statusIncludesJsonAndProfileFlag() throws Exception {
        var runner = new FakeRunner(0, "{\"status\":\"not_configured\",\"hint\":\"x\"}");
        cli(runner).status("histopath-lab");
        assertEquals(
                List.of("histoforge", "status", "--json", "-p", "histopath-lab"), runner.command);
    }

    @Test
    void nullProfileOmitsFlag() throws Exception {
        var runner = new FakeRunner(0, "{\"status\":\"not_configured\"}");
        cli(runner).status(null);
        assertEquals(List.of("histoforge", "status", "--json"), runner.command);
    }

    @Test
    void executableOverrideUsedAsCommandHead() throws Exception {
        var runner = new FakeRunner(0, "{\"status\":\"not_configured\"}");
        new HistoforgeCli(runner, () -> "/opt/tools/histoforge").status(null);
        assertEquals("/opt/tools/histoforge", runner.command.get(0));
    }

    // ── Result mapping ──────────────────────────────────────────────

    @Test
    void updatePassesExplicitCancellationConfirmation() throws Exception {
        var runner = new FakeRunner(0, targetResult("upgraded"));
        cli(runner).upgrade(target(), null);
        assertEquals(
                List.of(
                        "histoforge",
                        "upgrade",
                        "--json",
                        "-p",
                        "lab",
                        "--cancel-work",
                        "--expected-gateway",
                        "https://gateway.example",
                        "--expected-deployment-id",
                        "deployment-a"),
                runner.command);
        assertFalse(runner.command.contains(target().token()));
    }

    @Test
    void updateTargetCheckNeverConfirmsCancellation() throws Exception {
        var runner = new FakeRunner(0, targetResult("ready_to_update"));
        cli(runner).checkUpgrade(target(), null);
        assertEquals(
                List.of(
                        "histoforge",
                        "upgrade",
                        "--json",
                        "-p",
                        "lab",
                        "--check",
                        "--expected-gateway",
                        "https://gateway.example",
                        "--expected-deployment-id",
                        "deployment-a"),
                runner.command);
        assertFalse(runner.command.contains("--cancel-work"));
        assertFalse(runner.command.contains(target().token()));
    }

    @Test
    void reconnectKeepsTheCapturedTargetAndAllowsProtocolDifferences() throws Exception {
        String response =
                targetResult("connected")
                        .replace(
                                "}",
                                ",\"protocol_mismatches\":{\"module_schema_version\":{\"gateway\":3,\"helper\":4}}}");
        var runner = new FakeRunner(0, response);
        assertEquals("connected", cli(runner).reconnect(target(), null).status());
        assertEquals(
                List.of(
                        "histoforge",
                        "connect",
                        "--json",
                        "-p",
                        "lab",
                        "--expected-gateway",
                        "https://gateway.example",
                        "--expected-deployment-id",
                        "deployment-a"),
                runner.command);
        assertFalse(runner.command.contains(target().token()));
    }

    @Test
    void boundResultsMustIdentifyTheSavedBackend() {
        for (String response :
                List.of(
                        "{}",
                        targetResult("ready_to_update").replace("deployment-a", "other"),
                        targetResult("ready_to_update").replace("gateway.example", "other.example"),
                        targetResult("ready_to_update").replace("\"lab\"", "\"other\""))) {
            assertEquals(
                    CliException.Type.BAD_OUTPUT,
                    assertThrows(
                                    CliException.class,
                                    () ->
                                            cli(new FakeRunner(0, response))
                                                    .checkUpgrade(target(), null))
                            .type());
        }
    }

    @Test
    void preflightVerifiesTheBundledHelperWithoutCloudMutation() throws Exception {
        String version =
                qupath.ext.histoforge.platform.ExtensionRelease.current().bundledBackendVersion();
        var runner = new FakeRunner(0, "{\"status\":\"ok\",\"version\":\"" + version + "\"}");
        cli(runner).verifyRuntime();
        assertEquals(List.of("histoforge", "self-check", "--json"), runner.command);
        for (String response : List.of("{\"status\":\"ok\",\"version\":\"wrong\"}", "{}")) {
            assertEquals(
                    CliException.Type.BAD_OUTPUT,
                    assertThrows(
                                    CliException.class,
                                    () -> cli(new FakeRunner(0, response)).verifyRuntime())
                            .type());
        }
    }

    @Test
    void statusNotConfiguredMapsToType() throws Exception {
        var r =
                cli(new FakeRunner(
                                0,
                                "{\"status\":\"not_configured\",\"hint\":\"run `histoforge"
                                        + " deploy`\"}"))
                        .status(null);
        assertEquals(StatusResult.Type.NOT_CONFIGURED, r.type());
        assertEquals("run `histoforge deploy`", r.hint());
    }

    @Test
    void statusConfiguredMapsAllFields() throws Exception {
        var r =
                cli(new FakeRunner(
                                0,
                                "{\"status\":\"configured\",\"gateway_url\":\"https://g\","
                                        + "\"profile\":\"lab\",\"app_name\":\"histoforge-gateway\","
                                        + "\"reachable\":true,\"connected\":true,"
                                        + "\"api_major\":1,\"auth_revision\":7}"))
                        .status(null);
        assertEquals(StatusResult.Type.CONFIGURED, r.type());
        assertEquals("https://g", r.gatewayUrl());
        assertEquals("lab", r.profile());
        assertEquals("histoforge-gateway", r.appName());
        assertTrue(r.reachable());
        assertTrue(r.connected());
        assertEquals(1, r.apiMajor().intValue());
        assertEquals(7, r.authRevision().longValue());
    }

    @Test
    void statusConfiguredHandlesDisconnectedNullSchema() throws Exception {
        var r =
                cli(new FakeRunner(
                                0,
                                "{\"status\":\"configured\",\"gateway_url\":\"https://g\","
                                        + "\"profile\":\"lab\",\"reachable\":false,"
                                        + "\"connected\":false,\"api_major\":null,"
                                        + "\"auth_revision\":7}"))
                        .status(null);
        assertEquals(StatusResult.Type.CONFIGURED, r.type());
        assertFalse(r.reachable());
        assertFalse(r.connected());
        assertNull(r.apiMajor());
    }

    @Test
    void deployMapsResultAndStreamsStderrInOrder() throws Exception {
        var runner =
                new FakeRunner(
                        0,
                        "{\"status\":\"deployed\",\"gateway_url\":\"https://g\",\"profile\":\"lab\","
                                + "\"app_name\":\"histoforge-gateway\",\"environment\":\"research\"}",
                        "No Modal credentials found. Opening a browser to log in...",
                        "Building image…",
                        "✓ deployed");
        List<String> progress = new ArrayList<>();
        var result = cli(runner).deploy(new ModalContext("lab", "research"), progress::add);
        assertEquals("deployed", result.status());
        assertEquals("https://g", result.gatewayUrl());
        assertEquals("lab", result.profile());
        assertEquals(
                List.of(
                        "No Modal credentials found. Opening a browser to log in...",
                        "Building image…",
                        "✓ deployed"),
                progress);
    }

    @Test
    void joinMapsLoginResultWithProtocolDifferences() throws Exception {
        var runner =
                new FakeRunner(
                        0,
                        """
                        {"status":"connected","gateway_url":"https://gateway.example",
                         "profile":"lab","environment":"research","app_name":"histoforge-gateway",
                         "protocol_mismatches":{
                           "module_schema_version":{"gateway":3,"helper":4},
                           "finite_inference_schema_version":{"gateway":2,"helper":3}}}
                        """
                                .replace("\n", ""));
        var result = cli(runner).connect(new ModalContext("lab", "research"), null);
        assertEquals("connected", result.status());
        assertEquals("https://gateway.example", result.gatewayUrl());
        assertEquals("lab", result.profile());
        assertEquals("histoforge-gateway", result.appName());
        assertEquals(
                List.of(
                        "histoforge",
                        "connect",
                        "--json",
                        "-p",
                        "lab",
                        "--environment",
                        "research"),
                runner.command);
    }

    @Test
    void verifiedV1PreflightCarriesItsEnvironmentIntoTheActualUpdate() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        HistoforgeCli helper =
                cli(
                        (command, environment, input, error) -> {
                            commands.add(List.copyOf(command));
                            return new ProcessRunner.Completed(
                                    0,
                                    targetResult(
                                            command.contains("--check")
                                                    ? "ready_to_update"
                                                    : "upgraded"));
                        });
        Deployment old = target();
        Deployment verified = helper.checkUpgrade(old, null);
        assertNull(old.modalEnvironment());
        assertEquals("research", verified.modalEnvironment());
        helper.upgrade(verified, null);
        assertFalse(commands.get(0).contains("--environment"));
        int flag = commands.get(1).indexOf("--environment");
        assertEquals("research", commands.get(1).get(flag + 1));
        assertFalse(commands.toString().contains(old.token()));
    }

    @Test
    void savedEnvironmentIsPassedAndMustMatchTheHelperResult() throws Exception {
        Deployment saved = target().withEnvironment("research");
        FakeRunner runner = new FakeRunner(0, targetResult("connected"));
        cli(runner).reconnect(saved, null);
        assertEquals("research", runner.command.get(runner.command.indexOf("--environment") + 1));
        String wrong = targetResult("connected").replace("research", "different");
        assertEquals(
                CliException.Type.BAD_OUTPUT,
                assertThrows(
                                CliException.class,
                                () -> cli(new FakeRunner(0, wrong)).reconnect(saved, null))
                        .type());
    }

    // ── Error paths ─────────────────────────────────────────────────

    @Test
    void errorJsonOnStdoutBecomesCliError() {
        var e =
                assertThrows(
                        CliException.class,
                        () ->
                                cli(new FakeRunner(1, "{\"status\":\"error\",\"error\":\"boom\"}"))
                                        .status(null));
        assertEquals(CliException.Type.CLI_ERROR, e.type());
        assertEquals("boom", e.getMessage());
    }

    @Test
    void gatewayNotFoundJsonBecomesTypedAbsence() {
        var e =
                assertThrows(
                        CliException.class,
                        () ->
                                cli(new FakeRunner(
                                                1,
                                                "{\"status\":\"error\","
                                                        + "\"code\":\"GATEWAY_NOT_FOUND\","
                                                        + "\"error\":\"absent\"}"))
                                        .connect(new ModalContext("lab", "research"), null));
        assertEquals(CliException.Type.GATEWAY_NOT_FOUND, e.type());
        assertEquals("absent", e.getMessage());
    }

    @Test
    void managementFailuresKeepTheirActionableTypes() {
        for (CliException.Type type :
                List.of(
                        CliException.Type.GATEWAY_TARGET_MISMATCH,
                        CliException.Type.RECONNECT_REQUIRED,
                        CliException.Type.IDENTITY_RECOVERY_REQUIRED)) {
            var failure =
                    assertThrows(
                            CliException.class,
                            () ->
                                    cli(new FakeRunner(
                                                    1,
                                                    "{\"status\":\"error\",\"code\":\""
                                                            + type.name()
                                                            + "\",\"error\":\"details\"}"))
                                            .reconnect(target(), null));
            assertEquals(type, failure.type());
            assertEquals("details", failure.getMessage());
        }
    }

    @Test
    void systemExitTextOnStderrBecomesCliError() {
        var e =
                assertThrows(
                        CliException.class,
                        () ->
                                cli(new FakeRunner(
                                                1,
                                                "",
                                                "No gateway found in this workspace. Run"
                                                        + " `histoforge deploy` first."))
                                        .connect(new ModalContext("lab", "research"), null));
        assertEquals(CliException.Type.CLI_ERROR, e.type());
        assertTrue(e.getMessage().contains("No gateway found"));
    }

    @Test
    void executableNotFoundBecomesNotInstalled() {
        ProcessRunner missing =
                (cmd, env, stdin, onErr) -> {
                    throw new NotFoundException("histoforge not found", null);
                };
        var e = assertThrows(CliException.class, () -> cli(missing).status(null));
        assertEquals(CliException.Type.NOT_INSTALLED, e.type());
    }

    @Test
    void successWithNonJsonStdoutIsBadOutput() {
        var e =
                assertThrows(
                        CliException.class,
                        () -> cli(new FakeRunner(0, "not json at all")).status(null));
        assertEquals(CliException.Type.BAD_OUTPUT, e.type());
    }
}
