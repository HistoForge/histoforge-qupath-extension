package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.UnrecoverableRecoveryStateException;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.pipeline.ImageActionAggregation.TargetResult;
import qupath.ext.histoforge.pipeline.ImageActionCapturePlanner.CapturedAction;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.Completed;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.ResumeOwnership;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;

/**
 * Owns one image action whose logical targets may use finite and recoverable execution.
 *
 * <p>The coordinator captures one action-wide source-representation decision, retains one image
 * mutation reservation, serializes independent target commits, and aggregates terminal target truth
 * only in the live process. Durable recovery remains one handle per recoverable target; no group
 * ledger is persisted.
 */
public final class ImageActionCoordinator {

    private final ImageActionRegistry actionRegistry;
    private final LargeActionHandleStore handles;
    private final ImageActionCapturePlanner capturePlanner;
    private final ImageActionExecutor finiteExecutor;
    private final ImageMetricsExecutor metricsExecutor;
    private final RecoverableImageActionExecutor recoverableExecutor;
    private final PersistedActionRecovery persistedRecovery;

    /** Compose the finite and durable target owners around one per-image coordinator. */
    public ImageActionCoordinator(
            ImageActionRegistry actionRegistry,
            ImageLifecycle lifecycle,
            ModelGatewayExecutor gateway,
            SourceArtifactWriter sourceWriter,
            SourcePlanningLimits sourcePlanningLimits,
            LargeActionHandleStore handles,
            DurableModelRunExecutor durableRuns,
            MetricsEngine metricsEngine) {
        this(
                actionRegistry,
                lifecycle,
                gateway,
                sourceWriter,
                sourcePlanningLimits,
                handles,
                durableRuns,
                metricsEngine,
                ignored -> {});
    }

    public ImageActionCoordinator(
            ImageActionRegistry actionRegistry,
            ImageLifecycle lifecycle,
            ModelGatewayExecutor gateway,
            SourceArtifactWriter sourceWriter,
            SourcePlanningLimits sourcePlanningLimits,
            LargeActionHandleStore handles,
            DurableModelRunExecutor durableRuns,
            MetricsEngine metricsEngine,
            Consumer<ImageSourceProgress> sourceProgress) {
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        ImageLifecycle imageLifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        SourcePlanningLimits planningLimits =
                Objects.requireNonNull(sourcePlanningLimits, "sourcePlanningLimits");
        this.handles = Objects.requireNonNull(handles, "handles");
        ModelGatewayExecutor modelGateway = Objects.requireNonNull(gateway, "gateway");
        SourceArtifactWriter writer = Objects.requireNonNull(sourceWriter, "sourceWriter");
        MetricsEngine metrics = Objects.requireNonNull(metricsEngine, "metricsEngine");
        FiniteSourceProvisioner finiteSources =
                new FiniteSourceProvisioner(modelGateway, writer, sourceProgress);
        capturePlanner =
                new ImageActionCapturePlanner(imageLifecycle, planningLimits, finiteSources);
        finiteExecutor =
                new ImageActionExecutor(
                        this.actionRegistry,
                        imageLifecycle,
                        planningLimits,
                        new RecipeExecutor(modelGateway),
                        finiteSources);
        metricsExecutor = new ImageMetricsExecutor(this.actionRegistry, imageLifecycle, metrics);
        RecoverableSourceProvisioner recoverableSources =
                new RecoverableSourceProvisioner(
                        this.handles, modelGateway, writer, planningLimits, sourceProgress);
        recoverableExecutor =
                new RecoverableImageActionExecutor(
                        this.actionRegistry,
                        imageLifecycle,
                        planningLimits,
                        this.handles,
                        recoverableSources,
                        Objects.requireNonNull(durableRuns, "durableRuns"));
        persistedRecovery =
                new PersistedActionRecovery(this.handles, recoverableExecutor, metricsExecutor);
    }

    ImageActionCoordinator(
            ImageActionRegistry actionRegistry,
            ImageLifecycle lifecycle,
            SourcePlanningLimits sourcePlanningLimits,
            LargeActionHandleStore handles,
            FiniteSourceProvisioner sourceProvisioner,
            ImageActionExecutor finiteExecutor,
            RecoverableImageActionExecutor recoverableExecutor,
            ImageMetricsExecutor metricsExecutor) {
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        ImageLifecycle imageLifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        SourcePlanningLimits planningLimits =
                Objects.requireNonNull(sourcePlanningLimits, "sourcePlanningLimits");
        this.handles = Objects.requireNonNull(handles, "handles");
        FiniteSourceProvisioner finiteSources =
                Objects.requireNonNull(sourceProvisioner, "sourceProvisioner");
        this.capturePlanner =
                new ImageActionCapturePlanner(imageLifecycle, planningLimits, finiteSources);
        this.finiteExecutor = Objects.requireNonNull(finiteExecutor, "finiteExecutor");
        this.metricsExecutor = Objects.requireNonNull(metricsExecutor, "metricsExecutor");
        this.recoverableExecutor =
                Objects.requireNonNull(recoverableExecutor, "recoverableExecutor");
        persistedRecovery =
                new PersistedActionRecovery(
                        this.handles, this.recoverableExecutor, this.metricsExecutor);
    }

    /** Explicit persisted-handle inspection/recovery; merely obtaining it dispatches nothing. */
    public PersistedActionRecovery persistedRecovery() {
        return persistedRecovery;
    }

    /** Execute the complete captured image action once. */
    public ImageActionAttempt execute(
            ImageActionExecutor.Request request, BooleanSupplier shouldStop) {
        ImageActionExecutor.Request value = Objects.requireNonNull(request, "request");
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        String imageId = CanonicalCodec.requireNonblank(value.entry().getID(), "image_id");
        ImageActionRegistry.Reservation reservation = null;
        boolean transferred = false;
        try {
            reservation = actionRegistry.reserve(imageId);
            if (value.action() instanceof ActionSelectionCodec.Metrics) {
                return new ImageActionAttempt.Completed(
                        metricsExecutor.execute(value, reservation, java.util.Map.of(), stop));
            }
            CapturedAction captured = capturePlanner.capture(value, reservation, stop);
            if (captured.finitePlan().isEmpty() && captured.recoverablePlans().isEmpty()) {
                var modelResult = finiteExecutor.execute(value, reservation, stop);
                var terminal =
                        List.of(new TargetResult(captured.fullPlan().targetRegions(), modelResult));
                return new ImageActionAttempt.Completed(
                        ImageActionAggregation.reduce(
                                imageId,
                                terminal,
                                finalMetrics(
                                        value, captured.fullPlan(), reservation, terminal, stop)));
            }
            ImageActionAttempt result = executeCaptured(value, captured, reservation, stop);
            transferred = result instanceof ImageActionAttempt.Suspended;
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ImageActionAttempt.Completed(
                    ImageActionAggregation.imageFailure(
                            imageId, RunResults.FailureType.CANCELLED, interrupted, List.of()));
        } catch (CancellationException cancelled) {
            return new ImageActionAttempt.Completed(
                    ImageActionAggregation.imageFailure(
                            imageId, RunResults.FailureType.CANCELLED, cancelled, List.of()));
        } catch (RecipeFailures.Categorized failure) {
            return new ImageActionAttempt.Completed(
                    ImageResultReducer.imageFailure(
                            imageId,
                            RunResults.ImageOutcomeType.FAILED,
                            List.of(),
                            failure.failure()));
        } catch (InferenceException failure) {
            return new ImageActionAttempt.Completed(
                    ImageActionAggregation.imageFailure(
                            imageId, RecipeFailures.detail(failure).type(), failure, List.of()));
        } catch (LocalCapacityExceededException failure) {
            return new ImageActionAttempt.Completed(
                    ImageActionAggregation.imageFailure(
                            imageId, RunResults.FailureType.LOCAL_CAPACITY, failure, List.of()));
        } catch (RoiRegistry.MalformedObjectProvenanceException failure) {
            return new ImageActionAttempt.Completed(
                    ImageActionAggregation.imageFailure(
                            imageId, RunResults.FailureType.PROVENANCE, failure, List.of()));
        } catch (RoiRegistry.CorruptHierarchyException
                | ImageDataRevisionDigester.CorruptImageStateException failure) {
            return new ImageActionAttempt.Completed(
                    ImageActionAggregation.imageFailure(
                            imageId,
                            RunResults.FailureType.CORRUPT_IMAGE_STATE,
                            failure,
                            List.of()));
        } catch (RuntimeException failure) {
            return new ImageActionAttempt.Completed(
                    ImageActionAggregation.imageFailure(
                            imageId,
                            failure instanceof IllegalArgumentException
                                    ? RunResults.FailureType.INVALID_REQUEST
                                    : RunResults.FailureType.CORRUPT_IMAGE_STATE,
                            failure,
                            List.of()));
        } finally {
            if (reservation != null && !transferred) {
                reservation.close();
            }
        }
    }

    /** Explicitly advance the unresolved target, then start deferred targets in captured order. */
    public ImageActionAttempt resume(
            ImageActionAttempt.Suspended suspended, BooleanSupplier shouldStop) {
        ImageActionAttempt.Suspended source = Objects.requireNonNull(suspended, "suspended");
        ImageActionAttempt.Suspended.State state = source.claim(this);
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        List<TargetResult> terminal = new ArrayList<>(state.terminalResults());
        boolean transferred = false;
        try {
            ResumeOwnership ownership = state.pending().getFirst();
            ImageExecutionPlan plan = requireObservedPlan(ownership, "recovery");
            if (stop.getAsBoolean()) {
                transferred = true;
                ImageActionAttempt.Suspended next =
                        suspended(state, terminal, ownership, state.deferredTargets());
                source.resolveClaim(this);
                return next;
            }

            try {
                RecoverableImageActionExecutor.CoordinatedAttempt result =
                        recoverableExecutor.resume(ownership, state.request().entry(), stop);
                if (result.attempt() instanceof Completed completed) {
                    terminal.add(
                            new TargetResult(
                                    plan.targetRegions(),
                                    completed.result(),
                                    completed.automaticContinuationAllowed()));
                } else {
                    ResumeOwnership resumed =
                            ((RecoverableActionAttempt.Suspended) result.attempt())
                                    .claimForResume();
                    transferred = true;
                    ImageActionAttempt.Suspended next =
                            suspended(state, terminal, resumed, state.deferredTargets());
                    source.resolveClaim(this);
                    return next;
                }
            } catch (UnrecoverableRecoveryStateException stateLoss) {
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.failedTarget(
                                        plan,
                                        ownership.observedWork(),
                                        ImageResultReducer.failure(
                                                RunResults.FailureType.CORRUPT_IMAGE_STATE,
                                                stateLoss))));
            } catch (IOException | InferenceException failure) {
                transferred = true;
                ImageActionAttempt.Suspended next =
                        suspended(
                                state,
                                terminal,
                                ownership.withFailure(failure),
                                state.deferredTargets());
                source.resolveClaim(this);
                return next;
            }

            Optional<PendingTarget> pending =
                    executeRecoverableTargets(
                            state.request(),
                            state.deferredTargets(),
                            state.reservation(),
                            state.sharedOriginal(),
                            stop,
                            terminal);
            if (pending.isPresent()) {
                transferred = true;
                PendingTarget target = pending.orElseThrow();
                ImageActionAttempt.Suspended next =
                        suspended(state, terminal, target.ownership(), target.deferredTargets());
                source.resolveClaim(this);
                return next;
            }

            Optional<RunResults.ImageRunResult> metrics =
                    finalMetrics(
                            state.request(), state.fullPlan(), state.reservation(), terminal, stop);
            ImageActionAttempt.Completed completed =
                    new ImageActionAttempt.Completed(
                            ImageActionAggregation.reduce(
                                    state.request().entry().getID(), terminal, metrics));
            source.resolveClaim(this);
            return completed;
        } catch (RuntimeException | Error failure) {
            source.restoreClaim(this);
            transferred = true;
            throw failure;
        } finally {
            if (!transferred) {
                close(state);
            }
        }
    }

    /** Durably abandon the unresolved handle and cancel every target that was not started. */
    public ImageActionAttempt.Completed abandon(ImageActionAttempt.Suspended suspended)
            throws IOException {
        ImageActionAttempt.Suspended source = Objects.requireNonNull(suspended, "suspended");
        ImageActionAttempt.Suspended.State state = source.claim(this);
        List<TargetResult> terminal = new ArrayList<>(state.terminalResults());
        boolean complete = false;
        try {
            CancellationException cancellation =
                    new CancellationException("suspended image action abandoned by user");
            ResumeOwnership ownership = state.pending().getFirst();
            ImageExecutionPlan plan = requireObservedPlan(ownership, "abandonment");
            try {
                LargeActionHandle handle = ownership.lastObservedHandle();
                LargeActionHandle current =
                        handles.load(handle.actionId(), handle.handleGenerationId());
                if (!current.phase().terminal()) {
                    handles.abandon(current.actionId(), current.handleGenerationId());
                }
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.cancelled(ownership, cancellation)));
            } catch (UnrecoverableRecoveryStateException stateLoss) {
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.failedTarget(
                                        plan,
                                        ownership.observedWork(),
                                        ImageResultReducer.failure(
                                                RunResults.FailureType.CORRUPT_IMAGE_STATE,
                                                stateLoss))));
            }
            for (ImageActionSubsetPlanner.Subset deferred : state.deferredTargets()) {
                ImageExecutionPlan deferredPlan = deferred.execution();
                terminal.add(
                        new TargetResult(
                                deferredPlan.targetRegions(),
                                ImageActionAggregation.cancelled(
                                        deferredPlan,
                                        "image action abandoned before target start")));
            }
            Optional<RunResults.ImageRunResult> metrics =
                    finalMetrics(
                            state.request(),
                            state.fullPlan(),
                            state.reservation(),
                            terminal,
                            () -> false);
            ImageActionAttempt.Completed result =
                    new ImageActionAttempt.Completed(
                            ImageActionAggregation.reduce(
                                    state.request().entry().getID(), terminal, metrics));
            complete = true;
            source.resolveClaim(this);
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            source.restoreClaim(this);
            throw failure;
        } finally {
            if (complete) {
                close(state);
            }
        }
    }

    private ImageActionAttempt executeCaptured(
            ImageActionExecutor.Request request,
            CapturedAction captured,
            ImageActionRegistry.Reservation reservation,
            BooleanSupplier shouldStop)
            throws InterruptedException, InferenceException, RecipeFailures.Categorized {
        Optional<SealedOriginalSource> sharedOriginal = Optional.empty();
        List<TargetResult> terminal = new ArrayList<>();
        boolean sourceTransferred = false;
        try {
            if (captured.preparedOriginal().isPresent()) {
                try {
                    sharedOriginal =
                            Optional.of(
                                    captured.sourceSession()
                                            .orElseThrow()
                                            .publishOriginal(
                                                    captured.fullPlan(),
                                                    captured.sourceRevision(),
                                                    captured.preparedOriginal().orElseThrow(),
                                                    shouldStop));
                } catch (CancellationException failure) {
                    throw failure;
                } catch (IOException | RuntimeException failure) {
                    throw RecipeFailures.categorized(
                            RunResults.FailureType.SUBMISSION,
                            "failed to publish the shared source artifact",
                            failure);
                }
            }

            if (captured.finitePlan().isPresent()) {
                ImageActionSubsetPlanner.Subset finiteSubset = captured.finitePlan().orElseThrow();
                ImageActionExecutor.Request finiteRequest =
                        subsetRequest(request, finiteSubset.execution());
                terminal.add(
                        new TargetResult(
                                finiteSubset.execution().targetRegions(),
                                finiteExecutor.execute(
                                        finiteRequest,
                                        reservation,
                                        finiteSubset,
                                        sharedOriginal,
                                        shouldStop)));
            }

            Optional<PendingTarget> pending =
                    executeRecoverableTargets(
                            request,
                            captured.recoverablePlans(),
                            reservation,
                            sharedOriginal,
                            shouldStop,
                            terminal);
            if (pending.isPresent()) {
                PendingTarget target = pending.orElseThrow();
                ImageActionAttempt.Suspended suspended =
                        new ImageActionAttempt.Suspended(
                                this,
                                request,
                                captured.fullPlan(),
                                terminal,
                                List.of(target.ownership()),
                                target.deferredTargets(),
                                captured.sourceSession(),
                                sharedOriginal,
                                reservation);
                sourceTransferred = true;
                return suspended;
            }

            Optional<RunResults.ImageRunResult> metrics =
                    finalMetrics(request, captured.fullPlan(), reservation, terminal, shouldStop);
            return new ImageActionAttempt.Completed(
                    ImageActionAggregation.reduce(request.entry().getID(), terminal, metrics));
        } finally {
            if (!sourceTransferred) {
                captured.sourceSession().ifPresent(FiniteSourceProvisioner.Session::close);
            }
        }
    }

    private Optional<RunResults.ImageRunResult> finalMetrics(
            ImageActionExecutor.Request request,
            ImageExecutionPlan fullPlan,
            ImageActionRegistry.Reservation reservation,
            List<TargetResult> terminal,
            BooleanSupplier shouldStop) {
        if (fullPlan.metricSelection().isEmpty()
                || !ImageActionAggregation.allowsAutomaticContinuation(terminal)) {
            return Optional.empty();
        }
        ImageActionExecutor.Request metricsRequest =
                ImageMetricsExecutor.request(request, fullPlan.metricSelection().orElseThrow());
        try {
            return Optional.of(
                    metricsExecutor.execute(
                            metricsRequest,
                            reservation,
                            ImageActionAggregation.failedMetricRegions(terminal),
                            shouldStop));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.of(
                    ImageActionAggregation.imageFailure(
                            request.entry().getID(),
                            RunResults.FailureType.CANCELLED,
                            interrupted,
                            List.of()));
        }
    }

    private Optional<PendingTarget> executeRecoverableTargets(
            ImageActionExecutor.Request request,
            List<ImageActionSubsetPlanner.Subset> targets,
            ImageActionRegistry.Reservation reservation,
            Optional<SealedOriginalSource> sharedOriginal,
            BooleanSupplier shouldStop,
            List<TargetResult> terminal) {
        List<ImageActionSubsetPlanner.Subset> plans = List.copyOf(targets);
        for (int index = 0; index < plans.size(); index++) {
            ImageActionSubsetPlanner.Subset subset = plans.get(index);
            ImageExecutionPlan plan = subset.execution();
            if (!ImageActionAggregation.allowsAutomaticContinuation(terminal)) {
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.failedTarget(
                                        plan,
                                        new RunResults.FailureDetail(
                                                RunResults.FailureType.REVISION_CONFLICT,
                                                "Earlier results are saved, but the current viewer is not confirmed up to date. "
                                                        + "Reload this image before retrying the remaining targets."))));
                continue;
            }
            if (shouldStop.getAsBoolean()) {
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.cancelled(
                                        plan, "image action cancelled before target start")));
                continue;
            }
            try {
                RecoverableImageActionExecutor.CoordinatedAttempt result =
                        recoverableExecutor.execute(
                                subsetRequest(request, plan),
                                reservation,
                                subset,
                                sharedOriginal,
                                shouldStop);
                if (result.attempt() instanceof Completed completed) {
                    terminal.add(
                            new TargetResult(
                                    plan.targetRegions(),
                                    completed.result(),
                                    completed.automaticContinuationAllowed()));
                } else {
                    ResumeOwnership ownership =
                            ((RecoverableActionAttempt.Suspended) result.attempt())
                                    .claimForResume();
                    return Optional.of(
                            new PendingTarget(ownership, plans.subList(index + 1, plans.size())));
                }
            } catch (InferenceException | IOException failure) {
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.failedTarget(
                                        plan, RecipeFailures.detail(failure))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.cancelled(
                                        plan, "image action interrupted before target terminal")));
                for (int later = index + 1; later < plans.size(); later++) {
                    ImageExecutionPlan laterPlan = plans.get(later).execution();
                    terminal.add(
                            new TargetResult(
                                    laterPlan.targetRegions(),
                                    ImageActionAggregation.cancelled(
                                            laterPlan,
                                            "image action interrupted before target start")));
                }
                return Optional.empty();
            } catch (TargetRecipeTransaction.TargetTransactionFailure failure) {
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.imageFailure(
                                        request.entry().getID(),
                                        failure.failure().type(),
                                        failure,
                                        failure.outcomes())));
            } catch (LocalCapacityExceededException failure) {
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.failedTarget(
                                        plan,
                                        ImageResultReducer.failure(
                                                RunResults.FailureType.LOCAL_CAPACITY, failure))));
            } catch (RuntimeException failure) {
                terminal.add(
                        new TargetResult(
                                plan.targetRegions(),
                                ImageActionAggregation.failedTarget(
                                        plan,
                                        ImageResultReducer.failure(
                                                RunResults.FailureType.CORRUPT_IMAGE_STATE,
                                                failure))));
            }
        }
        return Optional.empty();
    }

    private ImageActionAttempt.Suspended suspended(
            ImageActionAttempt.Suspended.State state,
            List<TargetResult> terminal,
            ResumeOwnership pending,
            List<ImageActionSubsetPlanner.Subset> deferredTargets) {
        return new ImageActionAttempt.Suspended(
                this,
                state.request(),
                state.fullPlan(),
                terminal,
                List.of(pending),
                deferredTargets,
                state.sourceSession(),
                state.sharedOriginal(),
                state.reservation());
    }

    private static ImageExecutionPlan requireObservedPlan(
            ResumeOwnership ownership, String operation) {
        return ownership
                .lastObservedPlan()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "same-process image "
                                                + operation
                                                + " lost its target plan"));
    }

    private static void close(ImageActionAttempt.Suspended.State state) {
        state.sourceSession().ifPresent(FiniteSourceProvisioner.Session::close);
        state.reservation().close();
    }

    private record PendingTarget(
            ResumeOwnership ownership, List<ImageActionSubsetPlanner.Subset> deferredTargets) {
        private PendingTarget {
            ownership = Objects.requireNonNull(ownership, "ownership");
            deferredTargets = List.copyOf(deferredTargets);
        }
    }

    private static ImageActionExecutor.Request subsetRequest(
            ImageActionExecutor.Request request, ImageExecutionPlan plan) {
        return new ImageActionExecutor.Request(
                request.actionId(),
                request.clientBuild(),
                request.entry(),
                plan.module(),
                plan.action());
    }
}
