package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.data.MetricSnapshotBuilder;
import qupath.ext.histoforge.data.MetricTypes;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion.SourceVersionChangedException;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;

/** Calculates metrics from saved model state; saves only successful parent normalization. */
final class ImageMetricsExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ImageMetricsExecutor.class);
    private final ImageActionRegistry registry;
    private final ImageLifecycle lifecycle;
    private final MetricsEngine metrics;

    ImageMetricsExecutor(
            ImageActionRegistry registry, ImageLifecycle lifecycle, MetricsEngine metrics) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    RunResults.ImageRunResult execute(ImageActionExecutor.Request request, BooleanSupplier stop) {
        return execute(request, stop, List.of());
    }

    RunResults.ImageRunResult execute(
            ImageActionExecutor.Request request,
            BooleanSupplier stop,
            List<ExecutionScopeCodec.TargetRegion> capturedSelection) {
        try (var reservation = registry.reserve(request.entry().getID())) {
            return execute(request, reservation, Map.of(), stop, capturedSelection);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failed(request, RunResults.FailureType.CANCELLED, interrupted);
        }
    }

    /** Reuses the image-action reservation after its model transactions have saved. */
    RunResults.ImageRunResult execute(
            ImageActionExecutor.Request request,
            ImageActionRegistry.Reservation reservation,
            Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> failedRegions,
            BooleanSupplier stop)
            throws InterruptedException {
        return execute(request, reservation, failedRegions, stop, List.of());
    }

    RunResults.ImageRunResult execute(
            ImageActionExecutor.Request request,
            ImageActionRegistry.Reservation reservation,
            Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> failedRegions,
            BooleanSupplier stop,
            List<ExecutionScopeCodec.TargetRegion> capturedSelection)
            throws InterruptedException {
        if (!(request.action() instanceof ActionSelectionCodec.Metrics)) {
            throw new IllegalArgumentException("metric execution requires a Metrics action");
        }
        if (!reservation.imageId().equals(request.entry().getID())) {
            throw new IllegalArgumentException("metric reservation belongs to another image");
        }
        try (var ignored = reservation.acquireLease()) {
            return executeLeased(request, failedRegions, stop, capturedSelection);
        }
    }

    private RunResults.ImageRunResult executeLeased(
            ImageActionExecutor.Request request,
            Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> failedRegions,
            BooleanSupplier stop,
            List<ExecutionScopeCodec.TargetRegion> capturedSelection) {
        ImageLifecycle.WorkingImage working = null;
        String imageId = request.entry().getID();
        try {
            requireRunning(stop);
            // This call returns values and repair IDs only. Calculation inputs are no longer
            // retained when a correction needs a separate editable image.
            var calculated = calculate(request, failedRegions, stop, capturedSelection);
            var evaluation = calculated.evaluation();
            requireRunning(stop);
            var normalization = calculated.parentAssignments();
            boolean changed = false;
            if (!normalization.isEmpty()) {
                long loadStarted = System.nanoTime();
                logger.debug("Metrics parent-repair image load started");
                working =
                        lifecycle.openMatchingWorkingImage(
                                request.entry(),
                                imageId,
                                request.clientBuild().limits(),
                                calculated.sourceRevision());
                logger.debug(
                        "Metrics parent-repair image load finished in {} ms",
                        elapsedMillis(loadStarted));
                requireRunning(stop);
                changed =
                        new ModelOutputCommitter(
                                        working.imageData(), request.clientBuild().limits())
                                .applyParentNormalization(normalization)
                                .changed();
            }
            requireRunning(stop);
            RasterInputCodec.ImageRevisionToken revision;
            Optional<RunResults.FailureDetail> viewerFailure = Optional.empty();
            long finalizationStarted = System.nanoTime();
            logger.debug(
                    "Metrics final revision verification and save started; parent changes: {}",
                    changed);
            if (changed) {
                var commit = lifecycle.commit(working);
                revision = commit.savedRevision();
                viewerFailure =
                        commit.viewerRefreshFailure()
                                .map(
                                        failure ->
                                                ImageResultReducer.failure(
                                                        RunResults.FailureType.VIEWER_REFRESH,
                                                        failure));
            } else if (working != null) {
                revision = lifecycle.finishUnchanged(working);
            } else {
                revision =
                        lifecycle.verifyUnchanged(
                                request.entry(),
                                imageId,
                                request.clientBuild().limits(),
                                calculated.sourceRevision());
            }
            logger.debug(
                    "Metrics final revision verification and save finished in {} ms",
                    elapsedMillis(finalizationStarted));
            try {
                long publicationStarted = System.nanoTime();
                logger.debug("Metrics result publication started");
                var result =
                        ImageResultReducer.completed(
                                imageId,
                                new RecipeExecutor.Result(List.of()),
                                changed,
                                viewerFailure,
                                Optional.of(metrics.publish(evaluation, revision)),
                                List.of(),
                                true);
                logger.debug(
                        "Metrics result publication finished in {} ms",
                        elapsedMillis(publicationStarted));
                return result;
            } catch (RuntimeException publicationFailure) {
                metrics.evict(imageId, request.module().module().moduleRef());
                logger.warn("Metrics publication failed for image {}", imageId, publicationFailure);
                return ImageResultReducer.completed(
                        imageId,
                        new RecipeExecutor.Result(List.of()),
                        changed,
                        viewerFailure,
                        Optional.empty(),
                        List.of(
                                ImageResultReducer.failure(
                                        RunResults.FailureType.METRICS, publicationFailure)),
                        true);
            }
        } catch (MetricCalibrationFailure failure) {
            return failed(request, RunResults.FailureType.CALIBRATION, failure);
        } catch (ImageLifecycle.RevisionConflictException | SourceVersionChangedException failure) {
            return failed(request, RunResults.FailureType.REVISION_CONFLICT, failure);
        } catch (CancellationException failure) {
            return failed(request, RunResults.FailureType.CANCELLED, failure);
        } catch (IOException failure) {
            return failed(request, RunResults.FailureType.PERSISTENCE, failure);
        } catch (LocalCapacityExceededException failure) {
            return failed(request, RunResults.FailureType.LOCAL_CAPACITY, failure);
        } catch (MetricSnapshotBuilder.MissingLogicalRoiException
                | RoiRegistry.MalformedObjectProvenanceException failure) {
            return failed(request, RunResults.FailureType.PROVENANCE, failure);
        } catch (MetricSnapshotBuilder.AmbiguousRoiMembershipException
                | RoiRegistry.CorruptHierarchyException
                | ImageDataRevisionDigester.CorruptImageStateException failure) {
            return failed(request, RunResults.FailureType.CORRUPT_IMAGE_STATE, failure);
        } catch (RuntimeException failure) {
            return failed(request, RunResults.FailureType.METRICS, failure);
        } finally {
            if (working != null) {
                try {
                    working.close();
                } catch (RuntimeException failure) {
                    logger.warn("Failed to close the metrics working image", failure);
                }
            }
        }
    }

    private CalculatedMetrics calculate(
            ImageActionExecutor.Request request,
            Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> failedRegions,
            BooleanSupplier stop,
            List<ExecutionScopeCodec.TargetRegion> capturedSelection)
            throws IOException {
        var captured = capturePreparation(request, failedRegions, stop, capturedSelection);
        requireRunning(stop);
        long evaluationStarted = System.nanoTime();
        logger.debug("Metrics script execution and result validation started");
        var preparation = captured.value();
        var evaluation = metrics.evaluate(preparation, failedRegions, true, stop);
        logger.debug(
                "Metrics script execution and result validation finished in {} ms",
                elapsedMillis(evaluationStarted));
        requireRunning(stop);
        long selectionStarted = System.nanoTime();
        logger.debug("Metrics successful parent-repair selection started");
        var assignments = MetricsEngine.successfulParentNormalization(preparation, evaluation);
        logger.debug(
                "Metrics successful parent-repair selection finished in {} ms; {} assignments",
                elapsedMillis(selectionStarted),
                assignments.size());
        return new CalculatedMetrics(captured.sourceRevision(), evaluation, assignments);
    }

    private ImageLifecycle.ReadOnlyImageCapture<MetricTypes.MetricPreparation> capturePreparation(
            ImageActionExecutor.Request request,
            Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> failedRegions,
            BooleanSupplier stop,
            List<ExecutionScopeCodec.TargetRegion> capturedSelection)
            throws IOException {
        long captureStarted = System.nanoTime();
        logger.debug("Metrics saved-image capture started");
        var captured =
                lifecycle.captureSavedImage(
                        request.entry(),
                        request.entry().getID(),
                        request.clientBuild().limits(),
                        image ->
                                MetricSnapshotBuilder.canCaptureWithoutReload(image.getHierarchy()),
                        (image, sourceRevision, source) -> {
                            long planningStarted = System.nanoTime();
                            logger.debug("Metrics action planning started");
                            var plan =
                                    ImageActionPlanner.plan(
                                            request.actionId(),
                                            request.clientBuild(),
                                            request.entry().getID(),
                                            request.module(),
                                            request.resolveAction(image.getHierarchy()),
                                            image.getHierarchy(),
                                            image.getServer().getWidth(),
                                            image.getServer().getHeight());
                            logger.debug(
                                    "Metrics action planning finished in {} ms; {} regions",
                                    elapsedMillis(planningStarted),
                                    plan.targetRegions().size());
                            var requested =
                                    plan.targetRegions().stream()
                                            .map(ExecutionScopeCodec.TargetRegion::key)
                                            .collect(
                                                    java.util.stream.Collectors
                                                            .toUnmodifiableSet());
                            if (!requested.containsAll(failedRegions.keySet())) {
                                throw new IllegalArgumentException(
                                        "prefailed metric region is absent from the captured action");
                            }
                            var calibration = sourceRevision.pixelSource().calibration();
                            try {
                                ScriptCalibrationGate.requireSupported(
                                        calibration.mppX(), calibration.mppY());
                            } catch (IllegalArgumentException failure) {
                                throw new MetricCalibrationFailure(failure);
                            }
                            return MetricSnapshotBuilder.captureForCalculation(
                                    request.clientBuild(),
                                    request.module(),
                                    plan.targetRegions(),
                                    sourceRevision,
                                    image.getHierarchy(),
                                    capturedSelection.isEmpty()
                                            ? plan.targetRegions()
                                            : capturedSelection);
                        });
        logger.debug(
                "Metrics saved-image capture finished in {} ms", elapsedMillis(captureStarted));
        requireRunning(stop);
        // No hierarchy or live objects cross this boundary. The raw field records are released
        // when this helper returns; evaluation retains only the normalized preparation.
        var preparation = captured.value().prepare().preparation();
        return new ImageLifecycle.ReadOnlyImageCapture<>(
                captured.materialization(), captured.sourceRevision(), preparation);
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private record CalculatedMetrics(
            RasterInputCodec.ActionSourceRevision sourceRevision,
            MetricsEngine.Evaluation evaluation,
            List<HierarchyDelta.ParentAssignment> parentAssignments) {}

    private static final class MetricCalibrationFailure extends IllegalArgumentException {
        private MetricCalibrationFailure(IllegalArgumentException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private RunResults.ImageRunResult failed(
            ImageActionExecutor.Request request, RunResults.FailureType type, Exception failure) {
        metrics.evict(request.entry().getID(), request.module().module().moduleRef());
        if (type != RunResults.FailureType.CANCELLED) {
            logger.warn("Metrics failed for image {}", request.entry().getID(), failure);
        }
        return ImageActionAggregation.imageFailure(
                request.entry().getID(), type, failure, List.of());
    }

    static ImageActionExecutor.Request request(
            ImageActionExecutor.Request source, ActionSelectionCodec.RegionSelection regions) {
        var action = new ActionSelectionCodec.Metrics(regions);
        return new ImageActionExecutor.Request(
                source.actionId(),
                source.clientBuild(),
                source.entry(),
                ImageActionSubsetPlanner.moduleForAction(source.module(), action),
                action);
    }

    private static void requireRunning(BooleanSupplier stop) {
        if (stop.getAsBoolean()) {
            throw new CancellationException("metrics cancelled; saved model results are unchanged");
        }
    }
}
