package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.ResumeOwnership;
import qupath.lib.projects.ProjectImageEntry;

class BatchOrchestratorTest {

    private static final String ACTION = "11111111-1111-4111-8111-111111111111";

    @Test
    void boundsConcurrencyAndReturnsRequestOrder() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstPair = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        BatchOrchestrator orchestrator =
                new BatchOrchestrator(
                        (request, stop) -> {
                            int now = running.incrementAndGet();
                            peak.accumulateAndGet(now, Math::max);
                            firstPair.countDown();
                            await(release);
                            running.decrementAndGet();
                            return success(request.entry().getID());
                        },
                        2);
        List<ImageActionExecutor.Request> requests = requests(5);
        var future = orchestrator.runBatch(requests, null);

        assertTrue(firstPair.await(5, TimeUnit.SECONDS));
        assertEquals(2, running.get());
        release.countDown();
        RunResults.BatchRunResult result = future.get(10, TimeUnit.SECONDS);

        assertEquals(2, peak.get());
        assertEquals(
                requests.stream().map(request -> request.entry().getID()).toList(),
                result.images().stream().map(RunResults.ImageRunResult::imageId).toList());
    }

    @Test
    void cancellationStopsQueuedImagesAndProducesOneTerminalResultEach() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<BatchOrchestrator.BatchProgress> progress =
                java.util.Collections.synchronizedList(new ArrayList<>());
        BatchOrchestrator orchestrator =
                new BatchOrchestrator(
                        (request, stop) -> {
                            entered.countDown();
                            await(release);
                            return stop.getAsBoolean()
                                    ? cancelled(request.entry().getID())
                                    : success(request.entry().getID());
                        },
                        1);
        var future = orchestrator.runBatch(requests(4), progress::add);
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        orchestrator.cancel();
        release.countDown();
        RunResults.BatchRunResult result = future.get(10, TimeUnit.SECONDS);

        assertEquals(4, result.images().size());
        assertTrue(
                result.images().stream()
                        .allMatch(
                                image -> image.outcome() == RunResults.ImageOutcomeType.CANCELLED));
        int runningImages = 0;
        for (int index = 0; index < 4; index++) {
            int imageIndex = index;
            List<BatchOrchestrator.BatchPhase> phases =
                    progress.stream()
                            .filter(update -> update.imageIndex() == imageIndex)
                            .map(BatchOrchestrator.BatchProgress::phase)
                            .toList();
            if (phases.contains(BatchOrchestrator.BatchPhase.RUNNING)) {
                runningImages++;
                assertEquals(
                        List.of(
                                BatchOrchestrator.BatchPhase.QUEUED,
                                BatchOrchestrator.BatchPhase.RUNNING,
                                BatchOrchestrator.BatchPhase.TERMINAL),
                        phases);
            } else {
                assertEquals(
                        List.of(
                                BatchOrchestrator.BatchPhase.QUEUED,
                                BatchOrchestrator.BatchPhase.TERMINAL),
                        phases);
            }
        }
        assertEquals(1, runningImages);
    }

    @Test
    void progressHasQueuedRunningAndExactlyOneTerminalUpdatePerImage() throws Exception {
        List<BatchOrchestrator.BatchProgress> progress =
                java.util.Collections.synchronizedList(new ArrayList<>());
        BatchOrchestrator orchestrator =
                new BatchOrchestrator((request, stop) -> success(request.entry().getID()), 2);

        orchestrator.runBatch(requests(3), progress::add).get(10, TimeUnit.SECONDS);

        for (int index = 0; index < 3; index++) {
            int current = index;
            assertEquals(
                    List.of(
                            BatchOrchestrator.BatchPhase.QUEUED,
                            BatchOrchestrator.BatchPhase.RUNNING,
                            BatchOrchestrator.BatchPhase.TERMINAL),
                    progress.stream()
                            .filter(update -> update.imageIndex() == current)
                            .map(BatchOrchestrator.BatchProgress::phase)
                            .toList());
        }
        assertEquals(
                3,
                progress.stream()
                        .mapToInt(BatchOrchestrator.BatchProgress::terminalImages)
                        .max()
                        .orElseThrow());
    }

    @Test
    void unexpectedPerImageFailureIsClosedWithoutAbortingOtherImages() throws Exception {
        BatchOrchestrator orchestrator =
                new BatchOrchestrator(
                        (request, stop) -> {
                            if (request.entry().getID().endsWith("1")) {
                                throw new IllegalStateException("boom");
                            }
                            return success(request.entry().getID());
                        },
                        2);

        RunResults.BatchRunResult result =
                orchestrator.runBatch(requests(3), null).get(10, TimeUnit.SECONDS);

        assertEquals(RunResults.ImageOutcomeType.FAILED, result.images().get(1).outcome());
        assertEquals(
                RunResults.FailureType.CORRUPT_IMAGE_STATE,
                result.images().get(1).imageFailures().getFirst().type());
        assertEquals(
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                result.images().get(2).outcome());
    }

    @Test
    void rejectsEmptyDuplicateAndMixedActionBatchesAndSecondRun() {
        BatchOrchestrator orchestrator =
                new BatchOrchestrator((request, stop) -> success(request.entry().getID()), 1);
        assertThrows(IllegalArgumentException.class, () -> orchestrator.runBatch(List.of(), null));
        ImageActionExecutor.Request first = request(0, ACTION);
        assertThrows(
                IllegalArgumentException.class,
                () -> orchestrator.runBatch(List.of(first, first), null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        orchestrator.runBatch(
                                List.of(first, request(1, "22222222-2222-4222-8222-222222222222")),
                                null));

        orchestrator.runBatch(List.of(first), null);
        assertThrows(
                IllegalStateException.class,
                () -> orchestrator.runBatch(List.of(request(1, ACTION)), null));
    }

    @Test
    void suspendedImageReleasesConcurrencySlotUntilExplicitResume() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch suspendedProgress = new CountDownLatch(1);
        AtomicReference<String> suspendedImage = new AtomicReference<>();
        Map<ImageActionAttempt.Suspended, String> imageByAttempt = new ConcurrentHashMap<>();
        BatchOrchestrator orchestrator =
                new BatchOrchestrator(
                        new BatchOrchestrator.ImageRunner() {
                            @Override
                            public ImageActionAttempt execute(
                                    ImageActionExecutor.Request request,
                                    java.util.function.BooleanSupplier shouldStop) {
                                if (starts.incrementAndGet() == 1) {
                                    ImageActionAttempt.Suspended suspended = suspended(request);
                                    imageByAttempt.put(suspended, request.entry().getID());
                                    suspendedImage.set(request.entry().getID());
                                    return suspended;
                                }
                                secondStarted.countDown();
                                return new ImageActionAttempt.Completed(
                                        success(request.entry().getID()));
                            }

                            @Override
                            public ImageActionAttempt resume(
                                    ImageActionAttempt.Suspended suspended,
                                    java.util.function.BooleanSupplier shouldStop) {
                                return new ImageActionAttempt.Completed(
                                        success(imageByAttempt.get(suspended)));
                            }

                            @Override
                            public ImageActionAttempt.Completed abandon(
                                    ImageActionAttempt.Suspended suspended) {
                                return new ImageActionAttempt.Completed(
                                        cancelled(imageByAttempt.get(suspended)));
                            }
                        },
                        1);
        var future =
                orchestrator.runBatch(
                        requests(2),
                        progress -> {
                            if (progress.phase() == BatchOrchestrator.BatchPhase.SUSPENDED) {
                                suspendedProgress.countDown();
                            }
                        });

        assertTrue(suspendedProgress.await(5, TimeUnit.SECONDS));
        assertTrue(
                secondStarted.await(5, TimeUnit.SECONDS),
                "a suspended image must not retain the only execution slot");
        assertTrue(orchestrator.resume(suspendedImage.get()));
        RunResults.BatchRunResult result = future.get(10, TimeUnit.SECONDS);

        assertEquals(2, result.images().size());
        assertTrue(
                result.images().stream()
                        .allMatch(
                                image ->
                                        image.outcome()
                                                == RunResults.ImageOutcomeType
                                                        .SUCCEEDED_NO_HIERARCHY_CHANGE));
    }

    @Test
    void batchCancellationExplicitlyAbandonsSuspendedWork() throws Exception {
        CountDownLatch suspendedProgress = new CountDownLatch(1);
        AtomicReference<ImageActionExecutor.Request> captured = new AtomicReference<>();
        AtomicInteger abandons = new AtomicInteger();
        BatchOrchestrator orchestrator =
                new BatchOrchestrator(
                        new BatchOrchestrator.ImageRunner() {
                            @Override
                            public ImageActionAttempt execute(
                                    ImageActionExecutor.Request request,
                                    java.util.function.BooleanSupplier shouldStop) {
                                captured.set(request);
                                return suspended(request);
                            }

                            @Override
                            public ImageActionAttempt resume(
                                    ImageActionAttempt.Suspended suspended,
                                    java.util.function.BooleanSupplier shouldStop) {
                                throw new AssertionError("cancel must abandon, not resume");
                            }

                            @Override
                            public ImageActionAttempt.Completed abandon(
                                    ImageActionAttempt.Suspended suspended) {
                                abandons.incrementAndGet();
                                return new ImageActionAttempt.Completed(
                                        cancelled(captured.get().entry().getID()));
                            }
                        },
                        1);
        var future =
                orchestrator.runBatch(
                        requests(1),
                        progress -> {
                            if (progress.phase() == BatchOrchestrator.BatchPhase.SUSPENDED) {
                                suspendedProgress.countDown();
                            }
                        });
        assertTrue(suspendedProgress.await(5, TimeUnit.SECONDS));

        orchestrator.cancel();
        RunResults.BatchRunResult result = future.get(10, TimeUnit.SECONDS);

        assertEquals(1, abandons.get());
        assertEquals(RunResults.ImageOutcomeType.CANCELLED, result.images().getFirst().outcome());
    }

    @Test
    void failedCancellationAbandonmentRemainsAvailableForExplicitRetry() throws Exception {
        CountDownLatch firstSuspension = new CountDownLatch(1);
        CountDownLatch failedSuspension = new CountDownLatch(1);
        AtomicReference<String> imageId = new AtomicReference<>();
        AtomicInteger abandons = new AtomicInteger();
        BatchOrchestrator orchestrator =
                new BatchOrchestrator(
                        new BatchOrchestrator.ImageRunner() {
                            @Override
                            public ImageActionAttempt execute(
                                    ImageActionExecutor.Request request,
                                    java.util.function.BooleanSupplier shouldStop) {
                                imageId.set(request.entry().getID());
                                return suspended(request);
                            }

                            @Override
                            public ImageActionAttempt resume(
                                    ImageActionAttempt.Suspended suspended,
                                    java.util.function.BooleanSupplier shouldStop) {
                                throw new AssertionError("cancelled batch must not resume");
                            }

                            @Override
                            public ImageActionAttempt.Completed abandon(
                                    ImageActionAttempt.Suspended suspended) throws IOException {
                                if (abandons.incrementAndGet() == 1) {
                                    throw new IOException("durable abandonment failed");
                                }
                                return new ImageActionAttempt.Completed(cancelled(imageId.get()));
                            }
                        },
                        1);
        var future =
                orchestrator.runBatch(
                        requests(1),
                        progress -> {
                            if (progress.phase() != BatchOrchestrator.BatchPhase.SUSPENDED) {
                                return;
                            }
                            if (progress.suspensionFailure().isPresent()) {
                                failedSuspension.countDown();
                            } else {
                                firstSuspension.countDown();
                            }
                        });
        assertTrue(firstSuspension.await(5, TimeUnit.SECONDS));

        orchestrator.cancel();
        assertTrue(failedSuspension.await(5, TimeUnit.SECONDS));
        assertFalse(orchestrator.resume(imageId.get()));
        assertTrue(orchestrator.abandon(imageId.get()));

        assertEquals(
                RunResults.ImageOutcomeType.CANCELLED,
                future.get(10, TimeUnit.SECONDS).images().getFirst().outcome());
        assertEquals(2, abandons.get());
    }

    @Test
    void failedAbandonmentKeepsSuspensionAvailableForExplicitRetry() throws Exception {
        CountDownLatch firstSuspension = new CountDownLatch(1);
        CountDownLatch failedSuspension = new CountDownLatch(1);
        AtomicReference<String> imageId = new AtomicReference<>();
        AtomicReference<String> reportedFailure = new AtomicReference<>();
        AtomicInteger abandons = new AtomicInteger();
        BatchOrchestrator orchestrator =
                new BatchOrchestrator(
                        new BatchOrchestrator.ImageRunner() {
                            @Override
                            public ImageActionAttempt execute(
                                    ImageActionExecutor.Request request,
                                    java.util.function.BooleanSupplier shouldStop) {
                                imageId.set(request.entry().getID());
                                return suspended(request);
                            }

                            @Override
                            public ImageActionAttempt resume(
                                    ImageActionAttempt.Suspended suspended,
                                    java.util.function.BooleanSupplier shouldStop) {
                                throw new AssertionError("test uses abandonment only");
                            }

                            @Override
                            public ImageActionAttempt.Completed abandon(
                                    ImageActionAttempt.Suspended suspended) throws IOException {
                                if (abandons.incrementAndGet() == 1) {
                                    throw new IOException("atomic handle publication failed");
                                }
                                return new ImageActionAttempt.Completed(cancelled(imageId.get()));
                            }
                        },
                        1);
        var future =
                orchestrator.runBatch(
                        requests(1),
                        progress -> {
                            if (progress.phase() != BatchOrchestrator.BatchPhase.SUSPENDED) {
                                return;
                            }
                            if (progress.suspensionFailure().isPresent()) {
                                reportedFailure.set(progress.suspensionFailure().orElseThrow());
                                failedSuspension.countDown();
                            } else {
                                firstSuspension.countDown();
                            }
                        });
        assertTrue(firstSuspension.await(5, TimeUnit.SECONDS));
        assertTrue(orchestrator.abandon(imageId.get()));
        assertTrue(failedSuspension.await(5, TimeUnit.SECONDS));
        assertEquals("atomic handle publication failed", reportedFailure.get());

        assertTrue(orchestrator.abandon(imageId.get()));
        assertEquals(
                RunResults.ImageOutcomeType.CANCELLED,
                future.get(10, TimeUnit.SECONDS).images().getFirst().outcome());
        assertEquals(2, abandons.get());
    }

    @Test
    void suspensionPublishesInitialAndReturnedResumeFailureCauses() throws Exception {
        var request = request(0, ACTION);
        var first = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var retries = new AtomicInteger();
        var failures = new java.util.concurrent.CopyOnWriteArrayList<String>();
        BatchOrchestrator orchestrator =
                new BatchOrchestrator(
                        new BatchOrchestrator.ImageRunner() {
                            @Override
                            public ImageActionAttempt execute(
                                    ImageActionExecutor.Request ignored,
                                    java.util.function.BooleanSupplier stop) {
                                return suspended(
                                        request,
                                        Optional.of(new IOException("initial save failed")));
                            }

                            @Override
                            public ImageActionAttempt resume(
                                    ImageActionAttempt.Suspended paused,
                                    java.util.function.BooleanSupplier stop) {
                                return retries.getAndIncrement() == 0
                                        ? suspended(
                                                request,
                                                Optional.of(
                                                        new IOException(
                                                                "retry result unavailable")))
                                        : new ImageActionAttempt.Completed(
                                                success(request.entry().getID()));
                            }

                            @Override
                            public ImageActionAttempt.Completed abandon(
                                    ImageActionAttempt.Suspended paused) {
                                return new ImageActionAttempt.Completed(
                                        cancelled(request.entry().getID()));
                            }
                        },
                        1);
        var future =
                orchestrator.runBatch(
                        List.of(request),
                        progress -> {
                            if (progress.phase() == BatchOrchestrator.BatchPhase.SUSPENDED) {
                                failures.add(progress.suspensionFailure().orElseThrow());
                                if (failures.size() == 1) first.countDown();
                                else second.countDown();
                            }
                        });
        assertTrue(first.await(5, TimeUnit.SECONDS));
        assertTrue(orchestrator.resume(request.entry().getID()));
        assertTrue(second.await(5, TimeUnit.SECONDS));
        assertTrue(orchestrator.resume(request.entry().getID()));
        assertEquals(
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                future.get(10, TimeUnit.SECONDS).images().getFirst().outcome());
        assertEquals(List.of("initial save failed", "retry result unavailable"), failures);
    }

    private static List<ImageActionExecutor.Request> requests(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> request(index, ACTION))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static ImageActionExecutor.Request request(int index, String actionId) {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        when(entry.getID()).thenReturn("image-" + index);
        when(entry.getImageName()).thenReturn("Image " + index);
        return new ImageActionExecutor.Request(
                actionId,
                clientBuild(),
                entry,
                mock(ModuleSnapshot.class),
                new ActionSelectionCodec.Metrics(
                        new ActionSelectionCodec.RoiRegions(
                                Set.of(
                                        UUID.nameUUIDFromBytes(("roi-" + index).getBytes())
                                                .toString()))));
    }

    private static BuildIdentityCodec.ClientBuildIdentity clientBuild() {
        return new BuildIdentityCodec.ClientBuildIdentity(
                new BuildIdentityCodec.SoftwareBuildIdentity(
                        "test", "1".repeat(64), "2".repeat(64)),
                "0.6.0",
                Map.of(),
                new ExecutionLimitsCodec.ClientExecutionLimits(1, 1, 1, 1, 1, 1, 1, 1));
    }

    private static RunResults.ImageRunResult success(String imageId) {
        return new RunResults.ImageRunResult(
                imageId,
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                false,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty());
    }

    private static RunResults.ImageRunResult cancelled(String imageId) {
        return new RunResults.ImageRunResult(
                imageId,
                RunResults.ImageOutcomeType.CANCELLED,
                false,
                List.of(),
                List.of(),
                List.of(
                        new RunResults.FailureDetail(
                                RunResults.FailureType.CANCELLED, "cancelled")),
                Optional.empty());
    }

    private static ImageActionAttempt.Suspended suspended(ImageActionExecutor.Request request) {
        return suspended(request, Optional.empty());
    }

    private static ImageActionAttempt.Suspended suspended(
            ImageActionExecutor.Request request, Optional<Exception> failure) {
        String imageId = request.entry().getID();
        ImageActionCoordinator owner = mock(ImageActionCoordinator.class);
        ImageExecutionPlan plan = mock(ImageExecutionPlan.class);
        LargeActionHandle handle = mock(LargeActionHandle.class);
        when(handle.imageId()).thenReturn(imageId);
        ImageActionRegistry.Reservation reservation = mock(ImageActionRegistry.Reservation.class);
        when(reservation.imageId()).thenReturn(imageId);
        ResumeOwnership pending =
                new ResumeOwnership(handle, Optional.empty(), List.of(), reservation, failure);
        return new ImageActionAttempt.Suspended(
                owner,
                request,
                plan,
                List.of(),
                List.of(pending),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                reservation);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
