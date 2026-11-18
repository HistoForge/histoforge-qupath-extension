package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.LocalFileSourceVersion.SourceVersionChangedException;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Saves the model and ordinary-script results of one finite Fill or Redo action. Metrics run
 * separately, after this transaction has finished.
 *
 * <p>Planning, source publication, recipe staging, and QuPath persistence remain in their existing
 * owners. This class composes them and emits a typed result only after lifecycle finalization.
 */
public final class ImageActionExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ImageActionExecutor.class);

    private final ImageActionRegistry actionRegistry;
    private final ImageLifecycle lifecycle;
    private final SourcePlanningLimits sourcePlanningLimits;
    private final RecipeExecutor recipeExecutor;
    private final FiniteSourceProvisioner sourceProvisioner;

    /** Compose the production finite-action owners without introducing an IO forwarding layer. */
    public ImageActionExecutor(
            ImageActionRegistry actionRegistry,
            ImageLifecycle lifecycle,
            ModelGatewayExecutor gateway,
            SourceArtifactWriter sourceWriter,
            SourcePlanningLimits sourcePlanningLimits) {
        this(
                actionRegistry,
                lifecycle,
                sourcePlanningLimits,
                new RecipeExecutor(gateway),
                new FiniteSourceProvisioner(gateway, sourceWriter));
    }

    /** Test seam for deterministic model execution and source publication. */
    ImageActionExecutor(
            ImageActionRegistry actionRegistry,
            ImageLifecycle lifecycle,
            SourcePlanningLimits sourcePlanningLimits,
            RecipeExecutor recipeExecutor,
            FiniteSourceProvisioner sourceProvisioner) {
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.sourcePlanningLimits =
                Objects.requireNonNull(sourcePlanningLimits, "sourcePlanningLimits");
        this.recipeExecutor = Objects.requireNonNull(recipeExecutor, "recipeExecutor");
        this.sourceProvisioner = Objects.requireNonNull(sourceProvisioner, "sourceProvisioner");
    }

    /** Exact user intent and activated executable snapshot for one project image. */
    public record Request(
            String actionId,
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            ProjectImageEntry<BufferedImage> entry,
            ModuleSnapshot module,
            ActionSelectionCodec.ImageAction action,
            Optional<String> annotationClass) {
        public Request(
                String actionId,
                BuildIdentityCodec.ClientBuildIdentity clientBuild,
                ProjectImageEntry<BufferedImage> entry,
                ModuleSnapshot module,
                ActionSelectionCodec.ImageAction action) {
            this(actionId, clientBuild, entry, module, action, Optional.empty());
        }

        public Request {
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            clientBuild = Objects.requireNonNull(clientBuild, "clientBuild");
            entry = Objects.requireNonNull(entry, "entry");
            module = Objects.requireNonNull(module, "module");
            action = Objects.requireNonNull(action, "action");
            annotationClass =
                    Objects.requireNonNull(annotationClass, "annotationClass")
                            .map(
                                    value ->
                                            CanonicalCodec.requirePathClassName(
                                                    value, "Annotation class"));
        }

        ActionSelectionCodec.ImageAction resolveAction(
                qupath.lib.objects.hierarchy.PathObjectHierarchy hierarchy) {
            return annotationClass
                    .map(
                            value ->
                                    qupath.ext.histoforge.roi.AnnotationRoiSelection.resolve(
                                            action, value, hierarchy))
                    .orElse(action);
        }
    }

    /** Execute one bounded action and reduce every terminal path into persisted-state truth. */
    public RunResults.ImageRunResult execute(Request request, BooleanSupplier shouldStop) {
        Request value = Objects.requireNonNull(request, "request");
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        String imageId = CanonicalCodec.requireNonblank(value.entry().getID(), "image_id");
        ImageActionRegistry.Reservation reservation = null;
        try {
            reservation = actionRegistry.reserve(imageId);
            return execute(value, reservation, stop);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ImageResultReducer.imageFailure(
                    imageId,
                    RunResults.ImageOutcomeType.CANCELLED,
                    List.of(),
                    ImageResultReducer.failure(RunResults.FailureType.CANCELLED, interrupted));
        } finally {
            if (reservation != null) {
                reservation.close();
            }
        }
    }

    /**
     * Execute one finite action while the process-local image coordinator retains mutation
     * ownership across later target transactions.
     */
    RunResults.ImageRunResult execute(
            Request request,
            ImageActionRegistry.Reservation reservation,
            BooleanSupplier shouldStop)
            throws InterruptedException {
        return execute(request, reservation, Optional.empty(), shouldStop);
    }

    /** Execute a finite subset against an already sealed action-wide original. */
    RunResults.ImageRunResult execute(
            Request request,
            ImageActionRegistry.Reservation reservation,
            Optional<SealedOriginalSource> sharedOriginal,
            BooleanSupplier shouldStop)
            throws InterruptedException {
        return execute(request, reservation, Optional.empty(), sharedOriginal, shouldStop);
    }

    /** Execute an exact action-wide captured subset without replanning its source work. */
    RunResults.ImageRunResult execute(
            Request request,
            ImageActionRegistry.Reservation reservation,
            ImageActionSubsetPlanner.Subset captured,
            Optional<SealedOriginalSource> sharedOriginal,
            BooleanSupplier shouldStop)
            throws InterruptedException {
        return execute(
                request,
                reservation,
                Optional.of(Objects.requireNonNull(captured, "captured")),
                sharedOriginal,
                shouldStop);
    }

    private RunResults.ImageRunResult execute(
            Request request,
            ImageActionRegistry.Reservation reservation,
            Optional<ImageActionSubsetPlanner.Subset> captured,
            Optional<SealedOriginalSource> sharedOriginal,
            BooleanSupplier shouldStop)
            throws InterruptedException {
        Request value = Objects.requireNonNull(request, "request");
        if (value.action() instanceof ActionSelectionCodec.Metrics) {
            throw new IllegalArgumentException("model execution does not run metrics");
        }
        ImageActionRegistry.Reservation owner = Objects.requireNonNull(reservation, "reservation");
        Optional<ImageActionSubsetPlanner.Subset> subset =
                Objects.requireNonNull(captured, "captured");
        Optional<SealedOriginalSource> shared =
                Objects.requireNonNull(sharedOriginal, "sharedOriginal");
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        String imageId = CanonicalCodec.requireNonblank(value.entry().getID(), "image_id");
        if (!owner.imageId().equals(imageId)) {
            throw new IllegalArgumentException(
                    "image reservation belongs to another finite action");
        }
        try (ImageActionRegistry.Lease ignored = owner.acquireLease()) {
            return executeLeased(value, imageId, subset, shared, stop);
        }
    }

    private RunResults.ImageRunResult executeLeased(
            Request request,
            String imageId,
            Optional<ImageActionSubsetPlanner.Subset> captured,
            Optional<SealedOriginalSource> sharedOriginal,
            BooleanSupplier shouldStop) {
        ImageLifecycle.WorkingImage working = null;
        ImageExecutionPlan plan = null;
        List<RunResults.ScopeWorkResult> observedWork = List.of();
        try {
            requireRunning(shouldStop);
            if (captured.isEmpty() && request.annotationClass().isPresent()) {
                var prepared = AnnotationRoiPreparation.open(lifecycle, request, shouldStop);
                working = prepared.working();
                plan = prepared.plan();
            } else {
                working =
                        lifecycle.openWorkingImage(
                                request.entry(), imageId, request.clientBuild().limits());
            }
            requireRunning(shouldStop);
            ImageData<BufferedImage> image = working.imageData();
            if (captured.isPresent()) {
                ImageActionSubsetPlanner.Subset subset = captured.orElseThrow();
                requireCapturedRequest(request, subset.execution());
                plan = subset.execution();
                ImageActionSubsetPlanner.requireCurrent(
                        subset,
                        image.getHierarchy(),
                        image.getServer().getWidth(),
                        image.getServer().getHeight());
            } else if (plan == null) {
                plan =
                        ImageActionPlanner.plan(
                                request.actionId(),
                                request.clientBuild(),
                                imageId,
                                request.module(),
                                request.action(),
                                image.getHierarchy(),
                                image.getServer().getWidth(),
                                image.getServer().getHeight());
            }

            RecipeExecutor.Result recipeResult;
            SourceInputPlan sourcePlan =
                    captured.isPresent()
                            ? captured.orElseThrow().sources()
                            : sharedOriginal.isPresent()
                                    ? RecipeSourcePlanner.planAgainstPublishedSource(
                                            plan,
                                            working.sourcePyramid(),
                                            image.getHierarchy(),
                                            sharedOriginal.orElseThrow().startRequest())
                                    : RecipeSourcePlanner.plan(
                                            plan,
                                            working.sourcePyramid(),
                                            image.getHierarchy(),
                                            working.eligibleOriginalSource(),
                                            sourcePlanningLimits);
            FiniteSourceProvisioner.Session sourceSession;
            try {
                sourceSession = sourceProvisioner.open();
            } catch (IOException extractionFailure) {
                return ImageResultReducer.imageFailure(
                        imageId,
                        RunResults.ImageOutcomeType.FAILED,
                        List.of(),
                        ImageResultReducer.failure(
                                RunResults.FailureType.EXTRACTION, extractionFailure));
            }
            try (FiniteSourceProvisioner.Session sources = sourceSession) {
                var provisions =
                        sources.provision(
                                plan,
                                sourcePlan,
                                image,
                                working,
                                sharedOriginal,
                                sourcePlanningLimits,
                                shouldStop);
                requireRunning(shouldStop);
                recipeResult =
                        recipeExecutor.execute(
                                plan,
                                new RecipeExecutor.ExecutionInputs(
                                        sourcePlan,
                                        provisions,
                                        working.sourceRevision(),
                                        sources.imageUploads()),
                                image,
                                shouldStop);
            }
            observedWork = recipeResult.scopeResults();
            requireRunning(shouldStop);

            boolean modelChanged =
                    recipeResult.appliedTransactions().stream()
                            .anyMatch(value -> value.applyResult().changed());
            Optional<RunResults.FailureDetail> viewerFailure = Optional.empty();
            if (modelChanged) {
                ImageLifecycle.CommitResult commit = lifecycle.commit(working);
                viewerFailure =
                        commit.viewerRefreshFailure()
                                .map(
                                        error ->
                                                ImageResultReducer.failure(
                                                        RunResults.FailureType.VIEWER_REFRESH,
                                                        error));
            } else {
                lifecycle.finishUnchanged(working);
            }

            return ImageResultReducer.completed(
                    imageId,
                    recipeResult,
                    modelChanged,
                    viewerFailure,
                    Optional.empty(),
                    List.of(),
                    false);
        } catch (RecipeExecutor.ExecutionCancelledException cancelled) {
            return ImageResultReducer.work(
                    imageId,
                    RunResults.ImageOutcomeType.CANCELLED,
                    cancelled.scopeResults(),
                    List.of(),
                    false);
        } catch (RecipeExecutor.ExecutionFailedException failed) {
            return ImageResultReducer.work(
                    imageId,
                    RunResults.ImageOutcomeType.FAILED,
                    failed.scopeResults(),
                    List.of(),
                    false);
        } catch (ImageLifecycle.RevisionConflictException
                | SourceVersionChangedException conflict) {
            return ImageResultReducer.imageFailure(
                    imageId,
                    RunResults.ImageOutcomeType.CONFLICT,
                    observedWork,
                    ImageResultReducer.failure(RunResults.FailureType.REVISION_CONFLICT, conflict));
        } catch (ImageActionSubsetPlanner.CapturedPlanConflict conflict) {
            return ImageResultReducer.work(
                    imageId,
                    RunResults.ImageOutcomeType.CONFLICT,
                    plan == null
                            ? List.of()
                            : RecipeOutcomes.failedTransaction(
                                    plan,
                                    plan.targetRegions(),
                                    List.of(),
                                    ImageResultReducer.failure(
                                            RunResults.FailureType.REVISION_CONFLICT, conflict)),
                    List.of(),
                    false);
        } catch (CancellationException cancelled) {
            return cancelledResult(imageId, plan, observedWork, cancelled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return cancelledResult(imageId, plan, observedWork, interrupted);
        } catch (IOException persistenceFailure) {
            return ImageResultReducer.imageFailure(
                    imageId,
                    RunResults.ImageOutcomeType.FAILED,
                    observedWork,
                    ImageResultReducer.failure(
                            RunResults.FailureType.PERSISTENCE, persistenceFailure));
        } catch (RecipeExecutor.CrossModeRequiresRedoException modeFailure) {
            RunResults.FailureDetail detail = RecipeFailures.detail(modeFailure);
            List<RunResults.ScopeWorkResult> work =
                    plan == null
                            ? List.of()
                            : RecipeOutcomes.failedTransaction(
                                    plan, plan.targetRegions(), List.of(), detail);
            return ImageResultReducer.work(
                    imageId, RunResults.ImageOutcomeType.FAILED, work, List.of(), false);
        } catch (LocalCapacityExceededException capacityFailure) {
            return ImageResultReducer.imageFailure(
                    imageId,
                    RunResults.ImageOutcomeType.FAILED,
                    observedWork,
                    ImageResultReducer.failure(
                            RunResults.FailureType.LOCAL_CAPACITY, capacityFailure));
        } catch (RoiRegistry.MalformedObjectProvenanceException provenanceFailure) {
            return ImageResultReducer.imageFailure(
                    imageId,
                    RunResults.ImageOutcomeType.FAILED,
                    observedWork,
                    ImageResultReducer.failure(
                            RunResults.FailureType.PROVENANCE, provenanceFailure));
        } catch (RoiRegistry.CorruptHierarchyException
                | ImageDataRevisionDigester.CorruptImageStateException stateFailure) {
            return ImageResultReducer.imageFailure(
                    imageId,
                    RunResults.ImageOutcomeType.FAILED,
                    observedWork,
                    ImageResultReducer.failure(
                            RunResults.FailureType.CORRUPT_IMAGE_STATE, stateFailure));
        } catch (RuntimeException failure) {
            RunResults.FailureType type =
                    failure instanceof IllegalArgumentException
                            ? RunResults.FailureType.INVALID_REQUEST
                            : RunResults.FailureType.CORRUPT_IMAGE_STATE;
            return ImageResultReducer.imageFailure(
                    imageId,
                    RunResults.ImageOutcomeType.FAILED,
                    observedWork,
                    ImageResultReducer.failure(type, failure));
        } finally {
            closeWorking(working);
        }
    }

    private static RunResults.ImageRunResult cancelledResult(
            String imageId,
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            Throwable cancellation) {
        RunResults.FailureDetail detail =
                ImageResultReducer.failure(RunResults.FailureType.CANCELLED, cancellation);
        List<RunResults.ScopeWorkResult> work =
                observedWork.isEmpty() && plan != null
                        ? RecipeOutcomes.cancelledTransaction(
                                plan, plan.targetRegions(), List.of(), detail)
                        : observedWork;
        return ImageResultReducer.work(
                imageId,
                RunResults.ImageOutcomeType.CANCELLED,
                work,
                observedWork.isEmpty() && plan == null ? List.of(detail) : List.of(),
                false);
    }

    private static void closeWorking(ImageLifecycle.WorkingImage working) {
        if (working == null) {
            return;
        }
        try {
            working.close();
        } catch (RuntimeException failure) {
            logger.warn("Failed to close a disposable working image", failure);
        }
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("image action cancelled");
        }
    }

    private static void requireCapturedRequest(Request request, ImageExecutionPlan plan) {
        if (!request.actionId().equals(plan.actionId())
                || !request.clientBuild().equals(plan.clientBuild())
                || !request.entry().getID().equals(plan.imageId())
                || !request.module().equals(plan.module())
                || !request.action().equals(plan.action())) {
            throw new IllegalArgumentException(
                    "captured finite subset belongs to another image action");
        }
    }
}
