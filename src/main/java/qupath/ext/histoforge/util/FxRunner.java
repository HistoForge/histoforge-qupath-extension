package qupath.ext.histoforge.util;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javafx.application.Platform;

/**
 * Synchronous FX-thread execution. The caller retains ownership until the submitted work finishes.
 */
public final class FxRunner {
    private FxRunner() {}

    public static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        var task = new FutureTask<Void>(action, null);
        Platform.runLater(task);
        boolean interrupted = false;
        try {
            for (; ; ) {
                try {
                    task.get();
                    return;
                } catch (InterruptedException interruption) {
                    // Releasing a hierarchy lease before the FX action ends would permit concurrent
                    // mutation.
                    interrupted = true;
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                    if (failure.getCause() instanceof Error error) throw error;
                    throw new RuntimeException(failure.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
