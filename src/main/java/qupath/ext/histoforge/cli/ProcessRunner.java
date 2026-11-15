package qupath.ext.histoforge.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs an external command, returning its exit code + captured stdout while streaming each stderr
 * line to a callback. The shared seam that makes the CLI engines ({@link HistoforgeCli}, {@link
 * UvInstaller}) unit-testable: production uses {@link #SYSTEM}; tests pass a lambda returning
 * canned output without spawning a process.
 */
interface ProcessRunner {

    Completed run(
            List<String> command,
            Map<String, String> environment,
            byte[] stdin,
            Consumer<String> onStderrLine)
            throws IOException, InterruptedException;

    default Completed run(
            List<String> command, Map<String, String> environment, Consumer<String> onStderrLine)
            throws IOException, InterruptedException {
        return run(command, environment, new byte[0], onStderrLine);
    }

    default Completed run(List<String> command, Consumer<String> onStderrLine)
            throws IOException, InterruptedException {
        return run(command, Map.of(), onStderrLine);
    }

    /** A finished process: its exit code + the full stdout text. */
    record Completed(int exitCode, String stdout) {}

    /**
     * Thrown when an executable can't be found — the OS reports ENOENT from {@code
     * ProcessBuilder.start()}, distinct from the command running and failing.
     */
    final class NotFoundException extends IOException {
        NotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The production runner used outside tests. */
    ProcessRunner SYSTEM = new SystemRunner();

    /**
     * Spawns the command with {@link ProcessBuilder}: stdout and stderr are drained on separate
     * virtual threads while the caller blocks on {@link Process#waitFor()} — draining both
     * concurrently avoids a pipe-buffer deadlock when the child is chatty on both (Modal's deploy
     * logs are). A missing executable becomes {@link NotFoundException}; an interrupt at {@code
     * waitFor()} destroys the process so a UI Cancel terminates the child promptly.
     */
    final class SystemRunner implements ProcessRunner {

        @Override
        public Completed run(
                List<String> command,
                Map<String, String> environment,
                byte[] stdin,
                Consumer<String> onStderrLine)
                throws IOException, InterruptedException {
            Process process;
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.environment().putAll(environment);
                // Both pipe readers decode UTF-8. Python otherwise uses a Windows code page,
                // escaping emoji and producing invalid UTF-8 for characters such as an ellipsis.
                builder.environment().put("PYTHONIOENCODING", "utf-8");
                process = builder.start();
            } catch (IOException e) {
                // ProcessBuilder reports ENOENT with different punctuation across JDK/OS
                // combinations: "error=2" on Windows and older Unix JDKs, but "error: 2"
                // on current macOS. Both mean the command could not be started.
                String message = e.getMessage();
                if (message != null
                        && (message.contains("error=2") || message.contains("error: 2"))) {
                    throw new NotFoundException(command.get(0) + " not found", e);
                }
                throw e;
            }

            StringBuilder stdout = new StringBuilder();
            Thread outReader =
                    Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout));
            Thread errReader =
                    Thread.ofVirtual()
                            .start(() -> readLines(process.getErrorStream(), onStderrLine));
            AtomicReference<IOException> inputFailure = new AtomicReference<>();
            Thread inputWriter =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (var stream = process.getOutputStream()) {
                                            stream.write(stdin);
                                        } catch (IOException failure) {
                                            inputFailure.set(failure);
                                        }
                                    });

            try {
                int exit = process.waitFor();
                outReader.join();
                errReader.join();
                inputWriter.join();
                if (exit == 0 && inputFailure.get() != null) {
                    throw inputFailure.get();
                }
                return new Completed(exit, stdout.toString());
            } catch (InterruptedException e) {
                // Browser login is a child of the helper; stop it as well.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw e;
            }
        }

        /** Read the whole stream into {@code sink}; sole writer, safe to read after join. */
        private static void drain(InputStream in, StringBuilder sink) {
            try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) {
                    sink.append(buf, 0, n);
                }
            } catch (IOException ignored) {
                // stream closed on process exit / destroy — keep whatever we read
            }
        }

        private static void readLines(InputStream in, Consumer<String> onLine) {
            try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (IOException ignored) {
                // stream closed on process exit / destroy
            }
        }
    }
}
