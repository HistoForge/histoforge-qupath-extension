package qupath.ext.histoforge.ui.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class CatalogJobsRefreshSmokeTest {
    @Test
    void timeoutDetailsIncludeLastStepAndUncaughtFailureWithoutChangingThreads() {
        var thread = new Thread(() -> {}, "JavaFX Application Thread");
        var frame = new StackTraceElement("example.Header", "show", "Header.java", 42);
        var stack = new StackTraceElement[] {frame};
        var error = new IllegalStateException("Synthetic FX failure");
        error.setStackTrace(stack);

        String details =
                CatalogJobsRefreshSmoke.fxTimeoutDetails(
                        "Stage.show started", error, Map.of(thread, stack));

        assertTrue(details.contains("Last connection-header step: Stage.show started"));
        assertTrue(details.contains("Uncaught FX error: java.lang.IllegalStateException"));
        assertTrue(details.contains("Thread JavaFX Application Thread [NEW]"));
        assertTrue(details.contains("example.Header.show(Header.java:42)"));
        assertEquals(Thread.State.NEW, thread.getState());
        assertFalse(thread.isInterrupted());
        assertEquals(frame, stack[0]);
    }

    @Test
    void threadSnapshotIsBoundedAndIncludesNativeUiThreadsFirst() {
        var stacks = new LinkedHashMap<Thread, StackTraceElement[]>();
        var frames = new StackTraceElement[40];
        Arrays.fill(frames, new StackTraceElement("example.Render", "snapshot", "Render.java", 7));
        for (int i = 0; i < 100; i++) stacks.put(new Thread(() -> {}, "background-" + i), frames);
        stacks.put(new Thread(() -> {}, "QuantumRenderer-0"), frames);

        String details = CatalogJobsRefreshSmoke.fxTimeoutDetails("snapshot started", null, stacks);

        assertTrue(details.length() <= 16_384);
        assertTrue(
                details.indexOf("Thread QuantumRenderer-0")
                        < details.indexOf("Thread background-"));
        assertTrue(details.contains("Additional frames omitted."));
        assertTrue(details.contains("[Thread snapshot truncated]"));
        assertEquals(101, stacks.size());
        assertEquals(40, frames.length);
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeoutRethrowsOriginalExceptionWithoutCancellingTheFxTask() throws Exception {
        FutureTask<String> task = mock(FutureTask.class);
        var original = new TimeoutException("Synthetic native deadline");
        when(task.get(10, TimeUnit.SECONDS)).thenThrow(original);
        boolean interruptedBefore = Thread.currentThread().isInterrupted();
        var captured = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var output = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(output);
            assertSame(
                    original,
                    assertThrows(
                            TimeoutException.class,
                            () -> CatalogJobsRefreshSmoke.awaitFxTask(task)));
        } finally {
            System.setErr(previous);
        }

        verify(task).get(10, TimeUnit.SECONDS);
        verify(task, never()).cancel(anyBoolean());
        assertEquals(interruptedBefore, Thread.currentThread().isInterrupted());
        assertTrue(
                captured.toString(StandardCharsets.UTF_8).contains("Last connection-header step:"));
    }
}
