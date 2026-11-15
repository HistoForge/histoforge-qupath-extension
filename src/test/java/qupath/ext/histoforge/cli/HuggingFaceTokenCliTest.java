package qupath.ext.histoforge.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HuggingFaceTokenCliTest {
    private static final String TOKEN = "hf_do_not_log_this_test_token";

    private HistoforgeCli cli(ProcessRunner runner) {
        return new HistoforgeCli(
                (cmd, env, input, err) -> {
                    if (cmd.get(1).equals("login")) {
                        assertEquals(0, input.length);
                        String profile = cmd.size() == 5 ? cmd.get(4) : "lab";
                        return new ProcessRunner.Completed(
                                0,
                                "{\"status\":\"authenticated\",\"profile\":\"" + profile + "\"}");
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
                                            "research-lab"),
                                    command);
                            assertTrue(environment.isEmpty());
                            assertEquals(TOKEN, new String(stdin, StandardCharsets.UTF_8));
                            transported.set(stdin);
                            stderr.accept(
                                    TOKEN); // It must not become progress, a message or a log.
                            return new ProcessRunner.Completed(
                                    0, "{\"status\":\"saved\",\"profile\":\"research-lab\"}");
                        });
        assertEquals("research-lab", cli.saveHuggingFaceToken("research-lab", TOKEN, null));
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
                                .saveHuggingFaceToken(null, token, null));
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
                                            .saveHuggingFaceToken(null, TOKEN, null));
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
                        "{\"status\":\"saved\",\"profile\":\"another-profile\"}")) {
            var failure =
                    assertThrows(
                            CliException.class,
                            () ->
                                    cli((cmd, env, input, err) ->
                                                    new ProcessRunner.Completed(0, output))
                                            .saveHuggingFaceToken("lab", TOKEN, null));
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
                                        .saveHuggingFaceToken(null, TOKEN, null));
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
                                            .saveHuggingFaceToken(null, TOKEN, null));
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
                                        .saveHuggingFaceToken(null, TOKEN, null));
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
                                        "{\"status\":\"authenticated\",\"profile\":\"chosen-lab\"}");
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
                                    0, "{\"status\":\"saved\",\"profile\":\"chosen-lab\"}");
                        },
                        () -> "histoforge");
        assertEquals("chosen-lab", cli.saveHuggingFaceToken(null, TOKEN, progress::add));
        assertEquals(List.of("login", "huggingface-token"), commands);
        assertEquals(2, progress.size());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"profile\":{}}",
                "{\"status\":\"authenticated\",\"profile\":\"other-lab\"}"
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
                        CliException.class, () -> cli.saveHuggingFaceToken("lab", TOKEN, null));
        assertEquals(CliException.Type.BAD_OUTPUT, failure.type());
    }
}
