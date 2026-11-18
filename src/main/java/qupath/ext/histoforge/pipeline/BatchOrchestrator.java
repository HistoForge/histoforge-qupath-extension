package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded independent image scheduling and ordered typed batch-result aggregation. */
public final class BatchOrchestrator {

    private final ImageRunner imageRunner;
    private final int maxConcurrentImages;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final BatchSuspensionDecisions suspensionDecisions =
            new BatchSuspensionDecisions(cancelled::get);

    /** Schedule the production image coordinator with the caller-selected process-local bound. */
    public BatchOrchestrator(ImageActionCoordinator coordinator, int maxConcurrentImages) {
        ImageActionCoordinator owner = Objects.requireNonNull(coordinator, "coordinator");
        imageRunner =
                new ImageRunner() {
                    @Override
                    public ImageActionAttempt execute(
                            ImageActionExecutor.Request request, BooleanSupplier shouldStop) {
                        return owner.execute(request, shouldStop);
                    }

                    @Override
                    public ImageActionAttempt resume(
                            ImageActionAttempt.Suspended suspended, BooleanSupplier shouldStop) {
                        return owner.resume(suspended, shouldStop);
                    }

                    @Override
                    public ImageActionAttempt.Completed abandon(
                            ImageActionAttempt.Suspended suspended) throws java.io.IOException {
                        return owner.abandon(suspended);
                    }
                };
        this.maxConcurrentImages = requirePositive(maxConcurrentImages);
    }

    /** Terminal-only test seam retaining the ordinary finite scheduling behavior. */
    BatchOrchestrator(TerminalImageRunner imageRunner, int maxConcurrentImages) {
        TerminalImageRunner terminal = Objects.requireNonNull(imageRunner, "imageRunner");
        this.imageRunner =
                new ImageRunner() {
                    @Override
                    public ImageActionAttempt execute(
                            ImageActionExecutor.Request request, BooleanSupplier shouldStop) {
                        return new ImageActionAttempt.Completed(
                                terminal.execute(request, shouldStop));
                    }

                    @Override
                    public ImageActionAttempt resume(
                            ImageActionAttempt.Suspended suspended, BooleanSupplier shouldStop) {
                        throw new AssertionError("terminal test runner cannot suspend");
                    }

                    @Override
                    public ImageActionAttempt.Completed abandon(
                            ImageActionAttempt.Suspended suspended) {
                        throw new AssertionError("terminal test runner cannot suspend");
                    }
                };
        this.maxConcurrentImages = requirePositive(maxConcurrentImages);
    }

    /** Test seam for explicit suspension, resumption, and abandonment behavior. */
    BatchOrchestrator(ImageRunner imageRunner, int maxConcurrentImages) {
        this.imageRunner = Objects.requireNonNull(imageRunner, "imageRunner");
        this.maxConcurrentImages = requirePositive(maxConcurrentImages);
    }

    @FunctionalInterface
    interface TerminalImageRunner {
        RunResults.ImageRunResult execute(
                ImageActionExecutor.Request request, BooleanSupplier shouldStop);
    }

    interface ImageRunner {
        ImageActionAttempt execute(ImageActionExecutor.Request request, BooleanSupplier shouldStop);

        ImageActionAttempt resume(
                ImageActionAttempt.Suspended suspended, BooleanSupplier shouldStop);

        ImageActionAttempt.Completed abandon(ImageActionAttempt.Suspended suspended)
                throws java.io.IOException;
    }

    /** Coarse process-local progress; terminal truth is always the typed image result. */
    public enum BatchPhase {
        QUEUED,
        RUNNING,
        SUSPENDED,
        TERMINAL
    }

    /** One image transition; {@code imageIndex} is zero-based request order. */
    public record BatchProgress(
            int imageIndex,
            int totalImages,
            int terminalImages,
            String imageId,
            String imageName,
            BatchPhase phase,
            Optional<RunResults.ImageRunResult> result,
            int unresolvedTargets,
            Optional<String> suspensionFailure) {
        public BatchProgress {
            if (imageIndex < 0 || totalImages <= 0 || imageIndex >= totalImages) {
                throw new IllegalArgumentException("invalid batch image index");
            }
            if (terminalImages < 0 || terminalImages > totalImages) {
                throw new IllegalArgumentException("invalid terminal image count");
            }
            imageId = requireText(imageId, "imageId");
            imageName = requireText(imageName, "imageName");
            phase = Objects.requireNonNull(phase, "phase");
            result = Objects.requireNonNull(result, "result");
            suspensionFailure = Objects.requireNonNull(suspensionFailure, "suspensionFailure");
            if ((phase == BatchPhase.TERMINAL) != result.isPresent()) {
                throw new IllegalArgumentException(
                        "a batch result is present exactly for terminal progress");
            }
            if ((phase == BatchPhase.SUSPENDED) != (unresolvedTargets > 0)) {
                throw new IllegalArgumentException(
                        "unresolved targets are present exactly for suspended progress");
            }
            if (phase != BatchPhase.SUSPENDED && suspensionFailure.isPresent()) {
                throw new IllegalArgumentException(
                        "a suspension failure belongs only to suspended progress");
            }
        }
    }

    /** Execute all requested images with bounded active work and request-ordered results. */
    public CompletableFuture<RunResults.BatchRunResult> runBatch(
            List<ImageActionExecutor.Request> requests, Consumer<BatchProgress> progressCallback) {
        List<ImageActionExecutor.Request> batch = validateRequests(requests);
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("BatchOrchestrator executes exactly one batch");
        }
        String actionId = batch.getFirst().actionId();
        Consumer<BatchProgress> progress =
                progressCallback == null ? ignored -> {} : progressCallback;
        return CompletableFuture.supplyAsync(() -> execute(actionId, batch, progress));
    }

    /** Cancel running/queued images and durably abandon currently suspended work. */
    public void cancel() {
        cancelled.set(true);
        suspensionDecisions.cancelAll();
    }

    /** Explicitly retry one currently suspended image. */
    public boolean resume(String imageId) {
        return !cancelled.get() && suspensionDecisions.resume(imageId);
    }

    /** Explicitly abandon one suspended image while preserving earlier saved targets. */
    public boolean abandon(String imageId) {
        return suspensionDecisions.abandon(imageId);
    }

    private RunResults.BatchRunResult execute(
            String actionId,
            List<ImageActionExecutor.Request> requests,
            Consumer<BatchProgress> progress) {
        int total = requests.size();
        AtomicInteger terminal = new AtomicInteger();
        for (int index = 0; index < total; index++) {
            BatchImageWorker.emit(
                    progress,
                    BatchImageWorker.progress(
                            index, total, 0, requests.get(index), BatchPhase.QUEUED, null));
        }

        Semaphore executionSlots = new Semaphore(Math.min(maxConcurrentImages, total), true);
        try (ExecutorService pool =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("histoforge-image-", 0).factory())) {
            List<CompletableFuture<RunResults.ImageRunResult>> futures = new ArrayList<>(total);
            for (int index = 0; index < total; index++) {
                BatchImageWorker worker =
                        new BatchImageWorker(
                                index,
                                total,
                                requests.get(index),
                                terminal,
                                progress,
                                executionSlots,
                                imageRunner,
                                cancelled::get,
                                suspensionDecisions);
                futures.add(CompletableFuture.supplyAsync(worker::run, pool));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            return new RunResults.BatchRunResult(
                    actionId, futures.stream().map(CompletableFuture::join).toList());
        }
    }

    private static List<ImageActionExecutor.Request> validateRequests(
            List<ImageActionExecutor.Request> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("requests must be nonempty");
        }
        List<ImageActionExecutor.Request> copy = List.copyOf(requests);
        String actionId = copy.getFirst().actionId();
        Set<String> images = new HashSet<>();
        for (ImageActionExecutor.Request request : copy) {
            Objects.requireNonNull(request, "request");
            if (!actionId.equals(request.actionId())) {
                throw new IllegalArgumentException("batch requests must share one action_id");
            }
            if (!images.add(request.entry().getID())) {
                throw new IllegalArgumentException("batch image IDs must be unique");
            }
        }
        return copy;
    }

    static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must be nonblank");
        }
        return text;
    }

    private static int requirePositive(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("maxConcurrentImages must be positive");
        }
        return value;
    }
}
