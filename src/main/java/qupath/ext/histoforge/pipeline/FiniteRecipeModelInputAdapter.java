package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.CandidatePipeline;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourceInputPlanner;
import qupath.lib.objects.PathObject;

/** Existing finite-call path adapted to the recipe walker's protocol-neutral seam. */
final class FiniteRecipeModelInputAdapter implements RecipeModelInputAdapter {

    private final ImageExecutionPlan plan;
    private final RecipeExecutor.InputRunner runner;
    private final Supplier<String> generationIds;
    private final RecipeExecutor.InputImageUpload images;
    private final Map<InferenceIdentityCodec.ModelStepIdentity, Integer> nextInputIds =
            new LinkedHashMap<>();
    private final Set<String> usedGenerationIds = new LinkedHashSet<>();

    FiniteRecipeModelInputAdapter(
            ImageExecutionPlan plan,
            RecipeExecutor.InputRunner runner,
            Supplier<String> generationIds,
            RecipeExecutor.InputImageUpload images) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.generationIds = Objects.requireNonNull(generationIds, "generationIds");
        this.images = Objects.requireNonNull(images, "images");
    }

    @Override
    public SourceInputPlan.PlannedModelInput planFieldInput(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            ExecutionScopeCodec.OutputKey output) {
        SourceInputPlan.TargetSourcePlan targetSource = requiredSource(source, target);
        return targetSource.fieldInputs().stream()
                .filter(input -> input.output().equals(output))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "source plan lacks the selected field-model input"));
    }

    @Override
    public SourceInputPlan.PlannedModelInput planParentInput(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            InferenceIdentityCodec.ModelStepIdentity producer,
            String parentId,
            Geometry parentGeometry) {
        return SourceInputPlanner.planParentInput(
                requiredSource(source, target), producer, parentId, parentGeometry);
    }

    @Override
    public List<PreparedInput> prepareStep(
            ModuleRecipe.Step step,
            List<SourceInputPlan.PlannedModelInput> plannedInputs,
            List<PathObject> sourceParents,
            RecipeExecutor.AvailableSource source,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existingObjects) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(sourceParents, "sourceParents");
        List<PreparedInput> prepared = new ArrayList<>();
        for (SourceInputPlan.PlannedModelInput planned : plannedInputs) {
            InferenceIdentityCodec.ModelStepIdentity producer = planned.output().producer();
            prepared.add(
                    FiniteModelInputExecutor.plan(
                            plan,
                            nextInputId(producer),
                            nextGenerationId(),
                            planned,
                            source,
                            existingObjects));
        }
        return List.copyOf(prepared);
    }

    @Override
    public CandidateGeneration execute(PreparedInput input, BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    ModelGatewayExecutor.AcceptedFiniteCallFailure {
        if (input instanceof FiniteModelInputExecutor.InputAwaitingUpload pending) {
            var binding = images.publish(pending.rasterInput(), pending.model(), shouldStop);
            return runner.execute(pending.bind(binding), shouldStop);
        }
        if (!(input instanceof FiniteModelInputExecutor.PreparedInput finite)) {
            throw new IllegalArgumentException("finite adapter received a durable input");
        }
        return runner.execute(finite, shouldStop);
    }

    private int nextInputId(InferenceIdentityCodec.ModelStepIdentity producer) {
        int value = nextInputIds.getOrDefault(producer, 0);
        nextInputIds.put(producer, Math.incrementExact(value));
        return value;
    }

    private String nextGenerationId() {
        String value =
                CanonicalCodec.requireCanonicalUuid(generationIds.get(), "output_generation_id");
        if (!usedGenerationIds.add(value)) {
            throw new IllegalArgumentException("output generation IDs must be unique per action");
        }
        return value;
    }

    private static SourceInputPlan.TargetSourcePlan requiredSource(
            SourceInputPlan.TargetPlanningResult source, ExecutionScopeCodec.TargetRegion target) {
        if (source == null || !source.target().equals(target) || source.sourcePlan().isEmpty()) {
            throw new RecipeExecutor.BlockedPrerequisiteException(
                    "target has no finite source plan for its resolved model input");
        }
        return source.sourcePlan().orElseThrow();
    }
}
