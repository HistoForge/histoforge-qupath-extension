package qupath.ext.histoforge.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.cli.HistoforgeCli.ModalContext;

class HuggingFaceTokenCliTest {
    private static final String TOKEN = "hf_do_not_log_this_test_token";

    private HistoforgeCli cli(ProcessRunner runner) {
        return new HistoforgeCli(
                (cmd, env, input, err) -> {
                    if (cmd.get(1).equals("login")) {
                        assertEquals(0, input.length);
                        String profile =
                                cmd.contains("-p") ? cmd.get(cmd.indexOf("-p") + 1) : "lab";
                        return new ProcessRunner.Completed(
                                0,
                                "{\"status\":\"authenticated\",\"profile\":\""
                                        + profile
                                        + "\",\"environment\":\"research\"}");
                    }
                    return runner.run(cmd, env, input, err);
                },
                () -> "histoforge");
    }

    @Test
    void credentialUsesOnlyStdinAndTransportBufferIsCleared() throws Exception {
        AtomicReference<byte[]> transported = new AtomicReference<>();
        var cli =
                cli(
                        (command, environment, stdin, stderr) -> {
                            assertEquals(
                                    List.of(
                                            "histoforge",
                                            "huggingface-token",
                                            "--json",
                                            "-p",
                                            "research-lab",
                                            "--environment",
                                            "research"),
                                    command);
                            assertTrue(environment.isEmpty());
                            assertEquals(TOKEN, new String(stdin, StandardCharsets.UTF_8));
                            transported.set(stdin);
                            stderr.accept(
                                    TOKEN); // It must not become progress, a message or a log.
                            return new ProcessRunner.Completed(
                                    0,
                                    "{\"status\":\"saved\",\"profile\":\"research-lab\",\"environment\":\"research\"}");
                        });
        assertEquals(
                new ModalContext("research-lab", "research"),
                cli.saveHuggingFaceToken(
                        new ModalContext("research-lab", "research"), null, TOKEN, null));
        assertArrayEquals(new byte[TOKEN.length()], transported.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "contains space", "contains\nnewline", "é"})
    void malformedInputDoesNotStartAProcess(String token) {
        assertThrows(
                CliException.class,
                () ->
                        cli((cmd, env, input, err) -> {
                                    fail("Process should not start");
                                    return null;
                                })
                                .saveHuggingFaceToken(null, null, token, null));
    }

    @Test
    void failedHelperOutputCannotLeakThroughExceptionOrCause() {
        for (String output :
                List.of(
                        TOKEN,
                        "{\"status\":\"error\",\"code\":\"HF_TOKEN_REJECTED\",\"error\":\""
                                + TOKEN
                                + "\"}",
                        "{\"code\":{\"secret\":\"" + TOKEN + "\"}}")) {
            var failure =
                    assertThrows(
                            CliException.class,
                            () ->
                                    cli((cmd, env, input, err) -> {
                                                err.accept(TOKEN);
                                                return new ProcessRunner.Completed(1, output);
                                            })
                                            .saveHuggingFaceToken(null, null, TOKEN, null));
            assertFalse(failure.toString().contains(TOKEN));
            assertNull(failure.getCause());
        }
    }

    @Test
    void unconfirmedSuccessDoesNotReportSaved() {
        for (String output :
                List.of(
                        TOKEN,
                        "{}",
                        "{\"status\":\"saved\"}",
                        "{\"status\":\"saved\",\"profile\":\"another-profile\",\"environment\":\"research\"}")) {
            var failure =
                    assertThrows(
                            CliException.class,
                            () ->
                                    cli((cmd, env, input, err) ->
                                                    new ProcessRunner.Completed(0, output))
                                            .saveHuggingFaceToken(
                                                    new ModalContext("lab", "research"),
                                                    null,
                                                    TOKEN,
                                                    null));
            assertFalse(failure.getMessage().contains(TOKEN));
        }
    }

    @Test
    void ioErrorsAreSanitizedAndInputBufferCleared() {
        AtomicReference<byte[]> transported = new AtomicReference<>();
        var failure =
                assertThrows(
                        CliException.class,
                        () ->
                                cli((cmd, env, input, err) -> {
                                            transported.set(input);
                                            throw new IOException(TOKEN);
                                        })
                                        .saveHuggingFaceToken(null, null, TOKEN, null));
        assertFalse(failure.getMessage().contains(TOKEN));
        assertNull(failure.getCause());
        assertArrayEquals(new byte[TOKEN.length()], transported.get());
    }

    @Test
    void interruptionRemainsExplicitAndDoesNotPromiseNoWrite() {
        try {
            var failure =
                    assertThrows(
                            CliException.class,
                            () ->
                                    cli((cmd, env, input, err) -> {
                                                throw new InterruptedException(TOKEN);
                                            })
                                            .saveHuggingFaceToken(null, null, TOKEN, null));
            assertEquals(CliException.Type.INTERRUPTED, failure.type());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(failure.getMessage().contains("retry"));
            assertFalse(failure.getMessage().contains(TOKEN));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void missingHelperRetainsInstallationRecovery() {
        var failure =
                assertThrows(
                        CliException.class,
                        () ->
                                cli((cmd, env, input, err) -> {
                                            throw new ProcessRunner.NotFoundException(TOKEN, null);
                                        })
                                        .saveHuggingFaceToken(null, null, TOKEN, null));
        assertEquals(CliException.Type.NOT_INSTALLED, failure.type());
        assertFalse(failure.getMessage().contains(TOKEN));
    }

    @Test
    void loginInstructionsAreVisibleBeforeCredentialProcessStarts() throws Exception {
        var progress = new java.util.ArrayList<String>();
        var commands = new java.util.ArrayList<String>();
        var cli =
                new HistoforgeCli(
                        (cmd, env, input, err) -> {
                            commands.add(cmd.get(1));
                            if (cmd.get(1).equals("login")) {
                                assertEquals(0, input.length);
                                assertFalse(cmd.toString().contains(TOKEN));
                                assertFalse(env.toString().contains(TOKEN));
                                err.accept("https://modal.com/login/fixture");
                                err.accept("Enter this code: ABCD");
                                return new ProcessRunner.Completed(
                                        0,
                                        "{\"status\":\"authenticated\",\"profile\":\"chosen-lab\",\"environment\":\"research\"}");
                            }
                            assertEquals(
                                    List.of(
                                            "https://modal.com/login/fixture",
                                            "Enter this code: ABCD"),
                                    progress);
                            assertEquals("chosen-lab", cmd.get(4));
                            assertEquals(TOKEN, new String(input, StandardCharsets.UTF_8));
                            err.accept(TOKEN);
                            return new ProcessRunner.Completed(
                                    0,
                                    "{\"status\":\"saved\",\"profile\":\"chosen-lab\",\"environment\":\"research\"}");
                        },
                        () -> "histoforge");
        assertEquals(
                new ModalContext("chosen-lab", "research"),
                cli.saveHuggingFaceToken(null, null, TOKEN, progress::add));
        assertEquals(List.of("login", "huggingface-token"), commands);
        assertEquals(2, progress.size());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"profile\":{}}",
                "{\"status\":\"authenticated\",\"profile\":\"other-lab\",\"environment\":\"research\"}"
            })
    void unconfirmedLoginDoesNotSubmitCredential(String response) {
        var cli =
                new HistoforgeCli(
                        (cmd, env, input, err) -> {
                            assertEquals("login", cmd.get(1));
                            assertEquals(0, input.length);
                            return new ProcessRunner.Completed(0, response);
                        },
                        () -> "histoforge");
        var failure =
                assertThrows(
                        CliException.class,
                        () ->
                                cli.saveHuggingFaceToken(
                                        new ModalContext("lab", "research"), null, TOKEN, null));
        assertEquals(CliException.Type.BAD_OUTPUT, failure.type());
    }

    @Test
    void savedBackendIsVerifiedBeforeAnyCredentialInputIsSubmitted() {
        var target =
                new qupath.ext.histoforge.client.Deployment(
                        2,
                        "backend-a",
                        "https://gateway.example",
                        "lab",
                        "research",
                        "histoforge-gateway",
                        1,
                        "old-local-token",
                        java.time.Instant.parse("2026-11-17T00:00:00Z"));
        var helper =
                new HistoforgeCli(
                        (command, environment, input, error) -> {
                            assertEquals("login", command.get(1));
                            assertEquals(0, input.length);
                            assertTrue(command.contains("--expected-gateway"));
                            assertTrue(command.contains("backend-a"));
                            assertEquals(
                                    "research", command.get(command.indexOf("--environment") + 1));
                            assertFalse(command.toString().contains(TOKEN));
                            return new ProcessRunner.Completed(
                                    1,
                                    "{\"code\":\"GATEWAY_TARGET_MISMATCH\",\"error\":\"Wrong environment\"}");
                        },
                        () -> "histoforge");
        var failure =
                assertThrows(
                        CliException.class,
                        () -> helper.saveHuggingFaceToken(null, target, TOKEN, null));
        assertEquals(CliException.Type.GATEWAY_TARGET_MISMATCH, failure.type());
    }

    @Test
    void boundTokenSaveCarriesTheSameVerifiedTargetThroughBothProcesses() throws Exception {
        var target =
                new qupath.ext.histoforge.client.Deployment(
                        1,
                        "backend-a",
                        "https://gateway.example",
                        "lab",
                        "histoforge-gateway",
                        1,
                        "old-local-token",
                        java.time.Instant.parse("2026-11-17T00:00:00Z"));
        var commands = new java.util.ArrayList<List<String>>();
        var helper =
                new HistoforgeCli(
                        (command, environment, input, error) -> {
                            commands.add(List.copyOf(command));
                            boolean login = command.get(1).equals("login");
                            assertEquals(
                                    login ? "" : TOKEN, new String(input, StandardCharsets.UTF_8));
                            assertTrue(command.contains("backend-a"));
                            assertFalse(command.toString().contains(TOKEN));
                            String status = login ? "authenticated" : "saved";
                            return new ProcessRunner.Completed(
                                    0,
                                    "{\"status\":\""
                                            + status
                                            + "\",\"profile\":\"lab\",\"environment\":\"research\","
                                            + "\"gateway_url\":\"https://gateway.example\",\"deployment_id\":\"backend-a\","
                                            + "\"app_name\":\"histoforge-gateway\"}");
                        },
                        () -> "histoforge");
        assertEquals(
                new ModalContext("lab", "research"),
                helper.saveHuggingFaceToken(null, target, TOKEN, null));
        assertEquals(2, commands.size());
        assertFalse(commands.get(0).contains("--environment"));
        assertEquals("research", commands.get(1).get(commands.get(1).indexOf("--environment") + 1));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GATEWAY_TARGET_MISMATCH",
                "IDENTITY_RECOVERY_REQUIRED",
                "RECONNECT_REQUIRED"
            })
    void tokenPhaseRetainsRecoveryTypeWithoutLeakingItsOutput(String code) {
        var helper =
                cli(
                        (command, environment, input, error) -> {
                            error.accept(TOKEN);
                            return new ProcessRunner.Completed(
                                    1, "{\"code\":\"" + code + "\",\"error\":\"" + TOKEN + "\"}");
                        });
        var failure =
                assertThrows(
                        CliException.class,
                        () -> helper.saveHuggingFaceToken(null, null, TOKEN, null));
        assertEquals(CliException.Type.valueOf(code), failure.type());
        assertFalse(failure.toString().contains(TOKEN));
        assertNull(failure.getCause());
    }
}
