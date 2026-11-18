package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.lib.images.ImageData;

/** Captures one immutable action-wide execution/source decision before any target starts. */
final class ImageActionCapturePlanner {

    private final ImageLifecycle lifecycle;
    private final SourcePlanningLimits sourcePlanningLimits;
    private final FiniteSourceProvisioner sourceProvisioner;

    ImageActionCapturePlanner(
            ImageLifecycle lifecycle,
            SourcePlanningLimits sourcePlanningLimits,
            FiniteSourceProvisioner sourceProvisioner) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.sourcePlanningLimits =
                Objects.requireNonNull(sourcePlanningLimits, "sourcePlanningLimits");
        this.sourceProvisioner = Objects.requireNonNull(sourceProvisioner, "sourceProvisioner");
    }

    CapturedAction capture(
            ImageActionExecutor.Request request,
            ImageActionRegistry.Reservation reservation,
            BooleanSupplier shouldStop)
            throws InterruptedException, RecipeFailures.Categorized {
        ImageActionExecutor.Request value = Objects.requireNonNull(request, "request");
        ImageActionRegistry.Reservation owner = Objects.requireNonNull(reservation, "reservation");
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        FiniteSourceProvisioner.Session session = null;
        try (ImageActionRegistry.Lease ignored = owner.acquireLease()) {
            requireRunning(stop);
            final AnnotationRoiPreparation.Prepared prepared;
            try {
                prepared = AnnotationRoiPreparation.open(lifecycle, value, stop);
            } catch (ImageLifecycle.RevisionConflictException failure) {
                throw RecipeFailures.categorized(
                        RunResults.FailureType.REVISION_CONFLICT,
                        "Selected annotations changed before they could be locked and saved",
                        failure);
            } catch (IOException failure) {
                throw RecipeFailures.categorized(
                        RunResults.FailureType.PERSISTENCE,
                        "Could not prepare the saved image for inference: " + failure.getMessage(),
                        failure);
            }
            try (prepared) {
                ImageLifecycle.WorkingImage working = prepared.working();
                ImageData<BufferedImage> image = working.imageData();
                ImageExecutionPlan fullPlan = prepared.plan();
                SourceInputPlan sourcePlan =
                        RecipeSourcePlanner.plan(
                                fullPlan,
                                working.sourcePyramid(),
                                image.getHierarchy(),
                                working.eligibleOriginalSource(),
                                sourcePlanningLimits);
                List<ExecutionScopeCodec.TargetRegion> finiteTargets = new ArrayList<>();
                List<ExecutionScopeCodec.TargetRegion> recoverableTargets = new ArrayList<>();
                for (SourceInputPlan.TargetPlanningResult target : sourcePlan.targets()) {
                    if (target.sourcePlan().isPresent()
                            && target.sourcePlan().orElseThrow().protocol()
                                    == SourceInputPlan.ExecutionProtocol.RECOVERABLE) {
                        recoverableTargets.add(target.target());
                    } else {
                        finiteTargets.add(target.target());
                    }
                }

                Optional<ImageActionSubsetPlanner.Subset> finitePlan =
                        finiteTargets.isEmpty()
                                ? Optional.empty()
                                : Optional.of(
                                        ImageActionSubsetPlanner.subset(
                                                fullPlan,
                                                sourcePlan,
                                                finiteTargets,
                                                image.getHierarchy(),
                                                image.getServer().getWidth(),
                                                image.getServer().getHeight()));
                List<ImageActionSubsetPlanner.Subset> recoverablePlans =
                        recoverableTargets.stream()
                                .map(
                                        target ->
                                                ImageActionSubsetPlanner.subset(
                                                        fullPlan,
                                                        sourcePlan,
                                                        List.of(target),
                                                        image.getHierarchy(),
                                                        image.getServer().getWidth(),
                                                        image.getServer().getHeight()))
                                .toList();
                boolean usesOriginal =
                        sourcePlan.targets().stream()
                                .flatMap(target -> target.sourcePlan().stream())
                                .map(SourceInputPlan.TargetSourcePlan::artifact)
                                .anyMatch(SourceInputPlan.OriginalArtifactPlan.class::isInstance);
                int executionGroups = (finitePlan.isPresent() ? 1 : 0) + recoverablePlans.size();
                Optional<FiniteSourceProvisioner.PreparedOriginal> preparedOriginal =
                        Optional.empty();
                if (usesOriginal && executionGroups > 1) {
                    try {
                        session = sourceProvisioner.open();
                        preparedOriginal =
                                Optional.of(
                                        session.prepareOriginal(
                                                fullPlan, sourcePlan, image, working, stop));
                    } catch (CancellationException failure) {
                        throw failure;
                    } catch (IOException | RuntimeException failure) {
                        throw RecipeFailures.categorized(
                                RunResults.FailureType.EXTRACTION,
                                "failed to prepare the shared source artifact",
                                failure);
                    }
                }
                return new CapturedAction(
                        fullPlan,
                        finitePlan,
                        recoverablePlans,
                        working.sourceRevision(),
                        Optional.ofNullable(session),
                        preparedOriginal);
            }
        } catch (InterruptedException
                | RecipeFailures.Categorized
                | RuntimeException
                | Error failure) {
            if (session != null) {
                session.close();
            }
            throw failure;
        }
    }

    record CapturedAction(
            ImageExecutionPlan fullPlan,
            Optional<ImageActionSubsetPlanner.Subset> finitePlan,
            List<ImageActionSubsetPlanner.Subset> recoverablePlans,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            Optional<FiniteSourceProvisioner.Session> sourceSession,
            Optional<FiniteSourceProvisioner.PreparedOriginal> preparedOriginal) {
        CapturedAction {
            fullPlan = Objects.requireNonNull(fullPlan, "fullPlan");
            finitePlan = Objects.requireNonNull(finitePlan, "finitePlan");
            recoverablePlans = List.copyOf(recoverablePlans);
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            sourceSession = Objects.requireNonNull(sourceSession, "sourceSession");
            preparedOriginal = Objects.requireNonNull(preparedOriginal, "preparedOriginal");
            if (sourceSession.isPresent() != preparedOriginal.isPresent()) {
                throw new IllegalArgumentException(
                        "shared source session and preparation must be present together");
            }
        }
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("image action cancelled");
        }
    }
}
