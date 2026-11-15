package qupath.ext.histoforge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Process-level coverage for production subprocess error mapping. */
class ProcessRunnerTest {

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void stdinIsWrittenAndClosedWithoutAnArgumentOrEnvironmentCopy() throws Exception {
        var command =
                List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        Path.of(
                                        InputProbe.class
                                                .getProtectionDomain()
                                                .getCodeSource()
                                                .getLocation()
                                                .toURI())
                                .toString(),
                        InputProbe.class.getName());
        var result = ProcessRunner.SYSTEM.run(command, Map.of(), new byte[256 * 1024], line -> {});
        assertEquals(0, result.exitCode());
        assertEquals("262144", result.stdout().strip());
    }

    public static final class InputProbe {
        public static void main(String[] args) throws Exception {
            if (args.length > 0 && args[0].equals("child")) {
                Thread.sleep(60_000);
                return;
            }
            if (args.length > 0 && args[0].equals("parent")) {
                var child = new ProcessBuilder(javaProbe("child")).start();
                System.err.println(child.pid());
                child.waitFor();
                return;
            }
            if (args.length > 0 && args[0].equals("utf8")) {
                if (!"utf-8".equals(System.getenv("PYTHONIOENCODING"))) {
                    throw new AssertionError(
                            "Python pipes would use the inherited Windows encoding");
                }
                System.out.write(
                        "{\"message\":\"Verifying… ✓\"}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                System.err.write(
                        "Deploying backend… 🔨\n"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            System.out.println(System.in.readAllBytes().length);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void helperPipesUseUtf8EvenWhenAnOlderEncodingIsRequested() throws Exception {
        var progress = new java.util.ArrayList<String>();
        var result =
                ProcessRunner.SYSTEM.run(
                        javaProbe("utf8"), Map.of("PYTHONIOENCODING", "cp1252"), progress::add);
        assertEquals(0, result.exitCode());
        assertEquals("{\"message\":\"Verifying… ✓\"}", result.stdout());
        assertEquals(List.of("Deploying backend… 🔨"), progress);
    }

    private static List<String> javaProbe(String mode) throws Exception {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                Path.of(
                                InputProbe.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toString(),
                InputProbe.class.getName(),
                mode);
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void cancellingHelperAlsoStopsItsBrowserLoginChild() throws Exception {
        var command = javaProbe("parent");
        var child = new AtomicReference<ProcessHandle>();
        var started = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        ProcessRunner.SYSTEM.run(
                                                command,
                                                line -> {
                                                    child.set(
                                                            ProcessHandle.of(Long.parseLong(line))
                                                                    .orElseThrow());
                                                    started.countDown();
                                                });
                                    } catch (Exception error) {
                                        failure.set(error);
                                    }
                                });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(5_000);
            assertTrue(failure.get() instanceof InterruptedException);
            child.get().onExit().get(5, TimeUnit.SECONDS);
        } finally {
            worker.interrupt();
            if (child.get() != null) child.get().destroyForcibly();
        }
    }

    @Test
    void systemRunnerMapsMissingExecutableToNotFound() {
        assertThrows(
                ProcessRunner.NotFoundException.class,
                () ->
                        ProcessRunner.SYSTEM.run(
                                List.of("histoforge-command-that-must-not-exist-4dd8c81f"),
                                line -> {}));
    }

    @Test
    void systemRunnerAddsRequestedEnvironment() throws Exception {
        String key = "HISTOFORGE_PROCESS_RUNNER_TEST";
        String expected = "exact-value";
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> command =
                windows
                        ? List.of("cmd", "/c", "echo %" + key + "%")
                        : List.of("sh", "-c", "printf %s \"$" + key + "\"");

        var result = ProcessRunner.SYSTEM.run(command, Map.of(key, expected), line -> {});

        assertEquals(0, result.exitCode());
        assertEquals(expected, result.stdout().strip());
    }
}
