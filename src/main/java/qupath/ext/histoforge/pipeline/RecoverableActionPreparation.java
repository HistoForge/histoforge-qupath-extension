package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.large.HierarchyTransactionDigester;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.ResumableAction;
import qupath.ext.histoforge.large.LargeActionTypes;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionBaseline;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.images.ImageData;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Creates the exact durable PREPARING boundary for one recoverable target transaction. */
final class RecoverableActionPreparation {

    private RecoverableActionPreparation() {}

    /** One target-specific plan paired with its atomically persisted resumable action. */
    record Prepared(ImageExecutionPlan plan, ResumableAction action) {
        Prepared {
            plan = Objects.requireNonNull(plan, "plan");
            action = Objects.requireNonNull(action, "action");
            if (!plan.actionId().equals(action.handle().actionId())
                    || !plan.clientBuild().equals(action.handle().clientBuild())
                    || !plan.imageId().equals(action.handle().imageId())
                    || !plan.module().equals(action.handle().module())
                    || !plan.action().equals(action.handle().action())
                    || !plan.targetRegions().equals(List.of(action.handle().target()))
                    || !plan.selectionRegions().equals(action.handle().selectionRegions())) {
                throw new IllegalArgumentException(
                        "prepared plan and persisted large action differ");
            }
        }
    }

    /**
     * Persist PREPARING before any source writer is allowed to materialize bytes.
     *
     * <p>The caller holds the image's mutation reservation and short image lease. Source planning
     * has already selected the recoverable protocol from the full image action; this method narrows
     * the user intent and executable module to one target, captures its exact current hierarchy
     * transaction, and atomically creates the handle.
     */
    static Prepared prepare(
            ImageExecutionPlan imagePlan,
            SourceInputPlan.TargetPlanningResult targetSource,
            RasterInputCodec.ActionSourceRevision currentSourceRevision,
            ImageData<?> image,
            LargeActionHandleStore handles)
            throws IOException {
        return prepare(
                imagePlan,
                targetSource,
                currentSourceRevision,
                image,
                handles,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString());
    }

    static Prepared prepare(
            ImageExecutionPlan imagePlan,
            SourceInputPlan.TargetPlanningResult targetSource,
            RasterInputCodec.ActionSourceRevision currentSourceRevision,
            ImageData<?> image,
            LargeActionHandleStore handles,
            Clock clock,
            Supplier<String> handleGenerationIds)
            throws IOException {
        ImageExecutionPlan full = Objects.requireNonNull(imagePlan, "imagePlan");
        SourceInputPlan.TargetPlanningResult selected =
                Objects.requireNonNull(targetSource, "targetSource");
        ImageData<?> currentImage = Objects.requireNonNull(image, "image");
        RasterInputCodec.ActionSourceRevision currentSource =
                Objects.requireNonNull(currentSourceRevision, "currentSourceRevision");
        LargeActionHandleStore store = Objects.requireNonNull(handles, "handles");
        SourceInputPlan.TargetSourcePlan source =
                selected.sourcePlan()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "recoverable target requires model source work"));
        if (source.protocol() != SourceInputPlan.ExecutionProtocol.RECOVERABLE) {
            throw new IllegalArgumentException(
                    "large-action preparation requires the recoverable protocol");
        }
        if (!source.sourceRevision().imageDataRevision().imageId().equals(full.imageId())) {
            throw new IllegalArgumentException(
                    "recoverable source belongs to another project image");
        }
        if (!source.sourceRevision()
                        .imageDataRevision()
                        .imageId()
                        .equals(currentSource.imageDataRevision().imageId())
                || !source.sourceRevision().pixelSource().equals(currentSource.pixelSource())) {
            throw new IllegalArgumentException(
                    "recoverable source differs from the current project image or pixel source");
        }

        ImageExecutionPlan targetPlan =
                planTarget(
                        full,
                        selected.target(),
                        currentImage.getHierarchy(),
                        currentImage.getServer().getWidth(),
                        currentImage.getServer().getHeight());
        if (!source.target().equals(targetPlan.targetRegions().getFirst())) {
            throw new IllegalArgumentException(
                    "recoverable source differs from the exact target plan");
        }

        OutputIndex outputIndex = OutputIndex.fromHierarchy(currentImage.getHierarchy());
        ProducerModePolicy.Decision mode = ProducerModePolicy.evaluate(targetPlan, outputIndex);
        if (mode instanceof ProducerModePolicy.CrossModeRequiresRedo conflict) {
            throw new RecipeExecutor.CrossModeRequiresRedoException(conflict);
        }
        ProducerModePolicy.Ready ready = (ProducerModePolicy.Ready) mode;
        var replacementClosure =
                RecipeScopeResolver.replacementClosure(
                        targetPlan,
                        selected.target(),
                        outputIndex,
                        ready.producerWideReplacements(),
                        RecipeScopeResolver.plannedModels(targetPlan));
        var hierarchyBaseline =
                HierarchyTransactionDigester.captureBaseline(
                        selected.target(),
                        replacementClosure,
                        targetPlan.module().metrics().isPresent(),
                        currentImage.getHierarchy(),
                        targetPlan.selectionRegions());
        LargeActionBaseline baseline =
                new LargeActionBaseline(source.sourceRevision().pixelSource(), hierarchyBaseline);

        Instant now =
                Instant.ofEpochMilli(
                        Objects.requireNonNull(clock, "clock").instant().toEpochMilli());
        String handleGenerationId =
                CanonicalCodec.requireCanonicalUuid(
                        Objects.requireNonNull(handleGenerationIds, "handleGenerationIds").get(),
                        "handle_generation_id");
        LargeActionHandle handle =
                new LargeActionHandle(
                        LargeActionTypes.SCHEMA_VERSION,
                        targetPlan.actionId(),
                        handleGenerationId,
                        targetPlan.clientBuild(),
                        targetPlan.imageId(),
                        targetPlan.module(),
                        targetPlan.action(),
                        selected.target(),
                        baseline,
                        source.artifact(),
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        LargeActionPhase.PREPARING,
                        Optional.empty(),
                        now,
                        now,
                        targetPlan.selectionRegions());
        store.create(handle);
        return new Prepared(
                targetPlan, store.requireResumable(handle.actionId(), handle.handleGenerationId()));
    }

    /** Restrict one captured model action to one exact recoverable target transaction. */
    static ImageExecutionPlan planTarget(
            ImageExecutionPlan imagePlan,
            ExecutionScopeCodec.TargetRegion target,
            PathObjectHierarchy hierarchy,
            int imageWidth,
            int imageHeight) {
        if (Objects.requireNonNull(imagePlan, "imagePlan").action()
                instanceof ActionSelectionCodec.Metrics) {
            throw new IllegalArgumentException("Metrics does not create a recoverable target");
        }
        return ImageActionSubsetPlanner.plan(
                imagePlan,
                List.of(Objects.requireNonNull(target, "target")),
                hierarchy,
                imageWidth,
                imageHeight);
    }
}
