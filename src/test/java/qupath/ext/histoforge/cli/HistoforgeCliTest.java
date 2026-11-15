package qupath.ext.histoforge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.cli.HistoforgeCli.StatusResult;
import qupath.ext.histoforge.cli.ProcessRunner.NotFoundException;

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
        var runner = new FakeRunner(0, "{\"status\":\"upgraded\"}");
        cli(runner).upgrade("lab", null);
        assertEquals(
                List.of("histoforge", "upgrade", "--json", "-p", "lab", "--cancel-work"),
                runner.command);
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
                                + "\"app_name\":\"histoforge-gateway\"}",
                        "No Modal credentials found. Opening a browser to log in...",
                        "Building image…",
                        "✓ deployed");
        List<String> progress = new ArrayList<>();
        var result = cli(runner).deploy("ws", progress::add);
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
                                        .connect(null, null));
        assertEquals(CliException.Type.GATEWAY_NOT_FOUND, e.type());
        assertEquals("absent", e.getMessage());
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
                                        .connect(null, null));
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
