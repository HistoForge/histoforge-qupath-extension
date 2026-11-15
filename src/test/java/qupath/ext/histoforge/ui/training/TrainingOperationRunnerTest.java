package qupath.ext.histoforge.ui.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TrainingOperationRunnerTest {

    @Test
    @Timeout(5)
    void cancellationKeepsOwnershipUntilTheWorkerActuallyStops() throws Exception {
        var callbacks = new ConcurrentLinkedQueue<Runnable>();
        var runningChanges = new ArrayList<Boolean>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var progressCalls = new AtomicInteger();
        var successCalls = new AtomicInteger();
        var failureCalls = new AtomicInteger();
        var replacementStarted = new AtomicBoolean();
        var runner = new TrainingOperationRunner(runningChanges::add, callbacks::add);

        runner.start(
                (cancelled, progress) -> {
                    progress.accept(
                            new qupath.ext.histoforge.training.TrainingDatasetWorkflow.Progress(
                                    qupath.ext.histoforge.training.TrainingDatasetWorkflow.Phase
                                            .ENCODING,
                                    1,
                                    2,
                                    "first"));
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try {
                            release.await(10, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // Simulate a library write that does not stop on thread interruption.
                        }
                    }
                    return "finished";
                },
                ignored -> progressCalls.incrementAndGet(),
                ignored -> successCalls.incrementAndGet(),
                ignored -> failureCalls.incrementAndGet());

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        var stopped = runner.cancelAndAwait();
        drain(callbacks);
        assertTrue(runner.isRunning());
        assertFalse(stopped.isDone(), "Cancellation must await the library write");
        assertEquals(0, progressCalls.get());

        runner.start(
                (cancelled, progress) -> {
                    replacementStarted.set(true);
                    return "replacement";
                },
                ignored -> {},
                ignored -> {},
                ignored -> {});
        assertFalse(replacementStarted.get());

        release.countDown();
        awaitCallback(callbacks);
        drain(callbacks);

        assertFalse(runner.isRunning());
        assertTrue(stopped.isDone());
        assertFalse(stopped.isCompletedExceptionally());
        assertEquals(List.of(true, false), runningChanges);
        assertEquals(0, successCalls.get());
        assertEquals(0, failureCalls.get());
    }

    private static void awaitCallback(ConcurrentLinkedQueue<Runnable> callbacks)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (callbacks.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(callbacks.isEmpty());
    }

    private static void drain(ConcurrentLinkedQueue<Runnable> callbacks) {
        Runnable callback;
        while ((callback = callbacks.poll()) != null) {
            callback.run();
        }
    }
}
