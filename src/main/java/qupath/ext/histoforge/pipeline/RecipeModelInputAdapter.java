package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.CandidatePipeline;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.lib.objects.PathObject;

/** Protocol-specific model-input boundary used by the single recipe walker. */
interface RecipeModelInputAdapter {

    /** Minimum common state needed by projection and recipe-level outcome handling. */
    interface PreparedInput {
        SourceInputPlan.PlannedModelInput plannedInput();

        ImageDataStateCodec.GenerationStoreReservation reservation();
    }

    /** Resolve one field input without changing its recipe-owned output scope. */
    SourceInputPlan.PlannedModelInput planFieldInput(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            ExecutionScopeCodec.OutputKey output);

    /** Resolve one current source parent without changing recipe-owned parent eligibility. */
    SourceInputPlan.PlannedModelInput planParentInput(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            InferenceIdentityCodec.ModelStepIdentity producer,
            String parentId,
            Geometry parentGeometry);

    /** Bind a complete current step plan to either new finite calls or retained durable results. */
    List<? extends PreparedInput> prepareStep(
            ModuleRecipe.Step step,
            List<SourceInputPlan.PlannedModelInput> plannedInputs,
            List<PathObject> sourceParents,
            RecipeExecutor.AvailableSource source,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existingObjects)
            throws IOException, InterruptedException, InferenceException;

    /** Bind a selected region-gated step whose current canonical parent plan is valid-empty. */
    default void verifyEmptyStep(ModuleRecipe.Step step, List<PathObject> sourceParents)
            throws IOException {}

    /** Produce one fully validated candidate generation for a prepared logical input. */
    CandidateGeneration execute(PreparedInput input, BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    ModelGatewayExecutor.AcceptedFiniteCallFailure,
                    RecipeFailures.Categorized;

    /** Reject retained inputs or steps that the reconstructed recipe did not consume. */
    default void requireComplete() {}
}
