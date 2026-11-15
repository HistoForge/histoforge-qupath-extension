package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.pipeline.BatchOrchestrator.BatchPhase;
import qupath.ext.histoforge.pipeline.BatchOrchestrator.BatchProgress;
import qupath.ext.histoforge.pipeline.BatchSuspensionDecisions.Decision;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;

/** Advances one batch image while waiting for suspended decisions outside active-work slots. */
final class BatchImageWorker {

    private static final Logger logger = LoggerFactory.getLogger(BatchImageWorker.class);

    private final int index;
    private final int total;
    private final ImageActionExecutor.Request request;
    private final AtomicInteger terminal;
    private final Consumer<BatchProgress> progress;
    private final Semaphore executionSlots;
    private final BatchOrchestrator.ImageRunner imageRunner;
    private final BooleanSupplier cancelled;
    private final BatchSuspensionDecisions suspensionDecisions;

    BatchImageWorker(
            int index,
            int total,
            ImageActionExecutor.Request request,
            AtomicInteger terminal,
            Consumer<BatchProgress> progress,
            Semaphore executionSlots,
            BatchOrchestrator.ImageRunner imageRunner,
            BooleanSupplier cancelled,
            BatchSuspensionDecisions suspensionDecisions) {
        this.index = index;
        this.total = total;
        this.request = Objects.requireNonNull(request, "request");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.executionSlots = Objects.requireNonNull(executionSlots, "executionSlots");
        this.imageRunner = Objects.requireNonNull(imageRunner, "imageRunner");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.suspensionDecisions =
                Objects.requireNonNull(suspensionDecisions, "suspensionDecisions");
    }

    RunResults.ImageRunResult run() {
        RunResults.ImageRunResult result =
                cancelled.getAsBoolean()
                        ? cancelled("batch cancelled before image execution")
                        : runAttempt();
        int completed = terminal.incrementAndGet();
        emit(progress, progress(index, total, completed, request, BatchPhase.TERMINAL, result));
        return result;
    }

    private RunResults.ImageRunResult runAttempt() {
        try {
            ImageActionAttempt attempt =
                    withExecutionSlot(
                            () -> {
                                if (cancelled.getAsBoolean()) {
                                    return new ImageActionAttempt.Completed(
                                            cancelled("batch cancelled before image execution"));
                                }
                                emitRunning();
                                return imageRunner.execute(request, cancelled);
                            });
            Optional<String> suspensionFailure = Optional.empty();
            boolean automaticallyAbandon = true;
            while (attempt instanceof ImageActionAttempt.Suspended suspended) {
                if (suspensionFailure.isEmpty()) {
                    suspended
                            .failure()
                            .ifPresent(
                                    failure ->
                                            logger.warn(
                                                    "Image {} paused during model processing",
                                                    request.entry().getID(),
                                                    failure));
                }
                Optional<String> publishedFailure =
                        cancelled.getAsBoolean() && automaticallyAbandon
                                ? Optional.empty()
                                : suspensionFailure.or(
                                        () -> suspended.failure().map(BatchImageWorker::describe));
                Decision decision =
                        suspensionDecisions.await(
                                request.entry().getID(),
                                automaticallyAbandon,
                                () -> emitSuspended(suspended, publishedFailure));
                try {
                    attempt =
                            withExecutionSlot(
                                    () -> {
                                        emitRunning();
                                        return decision == Decision.RESUME
                                                ? imageRunner.resume(suspended, cancelled)
                                                : imageRunner.abandon(suspended);
                                    });
                    suspensionFailure = Optional.empty();
                    automaticallyAbandon = true;
                } catch (IOException | RuntimeException failure) {
                    logger.warn(
                            "Could not {} suspended image {}",
                            decision == Decision.RESUME ? "resume" : "abandon",
                            request.entry().getID(),
                            failure);
                    suspensionFailure = Optional.of(describe(failure));
                    automaticallyAbandon = decision != Decision.ABANDON;
                }
            }
            RunResults.ImageRunResult result = ((ImageActionAttempt.Completed) attempt).result();
            requireResultImage(result);
            return result;
        } catch (LocalCapacityExceededException failure) {
            logger.warn("local image capacity exceeded for {}", request.entry().getID(), failure);
            return ImageResultReducer.imageFailure(
                    request.entry().getID(),
                    RunResults.ImageOutcomeType.FAILED,
                    List.of(),
                    ImageResultReducer.failure(RunResults.FailureType.LOCAL_CAPACITY, failure));
        } catch (IOException | RuntimeException failure) {
            logger.error(
                    "unexpected image execution failure for {}", request.entry().getID(), failure);
            return ImageResultReducer.imageFailure(
                    request.entry().getID(),
                    RunResults.ImageOutcomeType.FAILED,
                    List.of(),
                    ImageResultReducer.failure(
                            RunResults.FailureType.CORRUPT_IMAGE_STATE, failure));
        }
    }

    private ImageActionAttempt withExecutionSlot(AttemptOperation operation) throws IOException {
        executionSlots.acquireUninterruptibly();
        try {
            return operation.run();
        } finally {
            executionSlots.release();
        }
    }

    private void emitRunning() {
        emit(progress, progress(index, total, terminal.get(), request, BatchPhase.RUNNING, null));
    }

    private void emitSuspended(ImageActionAttempt.Suspended suspended, Optional<String> failure) {
        emit(
                progress,
                new BatchProgress(
                        index,
                        total,
                        terminal.get(),
                        request.entry().getID(),
                        request.entry().getImageName(),
                        BatchPhase.SUSPENDED,
                        Optional.empty(),
                        suspended.unresolvedTargetCount(),
                        failure));
    }

    private RunResults.ImageRunResult cancelled(String message) {
        return ImageResultReducer.imageFailure(
                request.entry().getID(),
                RunResults.ImageOutcomeType.CANCELLED,
                List.of(),
                new RunResults.FailureDetail(RunResults.FailureType.CANCELLED, message));
    }

    private void requireResultImage(RunResults.ImageRunResult result) {
        if (!request.entry().getID().equals(result.imageId())) {
            throw new IllegalStateException("image executor returned a result for another image");
        }
    }

    static BatchProgress progress(
            int index,
            int total,
            int terminal,
            ImageActionExecutor.Request request,
            BatchPhase phase,
            RunResults.ImageRunResult result) {
        return new BatchProgress(
                index,
                total,
                terminal,
                request.entry().getID(),
                request.entry().getImageName(),
                phase,
                Optional.ofNullable(result),
                0,
                Optional.empty());
    }

    static void emit(Consumer<BatchProgress> callback, BatchProgress update) {
        try {
            callback.accept(update);
        } catch (RuntimeException failure) {
            logger.warn("batch progress callback failed", failure);
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface AttemptOperation {
        ImageActionAttempt run() throws IOException;
    }
}
