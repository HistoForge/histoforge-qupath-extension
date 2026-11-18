package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.ModelExecutionPlanCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ProcessingEvidenceCodec;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.CandidatePipeline;
import qupath.ext.histoforge.spatial.SourceInputPlan;

/** Prepares and realizes one durable logical input without owning run or recipe orchestration. */
final class DurableModelInputExecutor {

    private DurableModelInputExecutor() {}

    /** Exact pre-dispatch reservation plus the detached state needed for candidate preservation. */
    record PreparedInput(
            ModelRunRequestCodec.ModelRunRequest request,
            ModelRunRequestCodec.ModelRunInput requestInput,
            SourceInputPlan.PlannedModelInput plannedInput,
            ImageDataStateCodec.GenerationStoreReservation reservation,
            List<CandidatePipeline.ExistingObjectSnapshot> existingObjects)
            implements RecipeModelInputAdapter.PreparedInput {
        PreparedInput {
            request = Objects.requireNonNull(request, "request");
            requestInput = Objects.requireNonNull(requestInput, "requestInput");
            plannedInput = Objects.requireNonNull(plannedInput, "plannedInput");
            reservation = Objects.requireNonNull(reservation, "reservation");
            existingObjects =
                    List.copyOf(Objects.requireNonNull(existingObjects, "existingObjects"));
            if (!request.inputs().contains(requestInput)
                    || !requestInput.output().equals(plannedInput.output())) {
                throw new IllegalArgumentException(
                        "durable prepared input does not match its request and recipe plan");
            }
        }
    }

    /** Build the exact generation-store reservation before any durable request is submitted. */
    static PreparedInput prepare(
            ImageExecutionPlan plan,
            ModelRunRequestCodec.ModelRunRequest request,
            ModelRunRequestCodec.ModelRunInput requestInput,
            SourceInputPlan.PlannedModelInput plannedInput,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existingObjects) {
        ImageExecutionPlan action = Objects.requireNonNull(plan, "plan");
        ModelRunRequestCodec.ModelRunInput input =
                Objects.requireNonNull(requestInput, "requestInput");
        SourceInputPlan.PlannedModelInput planned =
                Objects.requireNonNull(plannedInput, "plannedInput");
        var executionPlan =
                ModelExecutionPlanCodec.buildExpected(
                        request.expectedProfile(),
                        input.rasterInput().frame().transform().raster(),
                        ModelRunPublicationCodec.preprocessingContextDigest(
                                input, request.expectedBackend()),
                        action.module()
                                .activatedModels()
                                .get(input.output().producer().stepId())
                                .capabilities()
                                .durableRun()
                                .orElseThrow()
                                .run()
                                .maxUnits(),
                        request.haloPxOverride());
        ImageDataStateCodec.GenerationStoreReservation reservation =
                ImageDataStateCodec.GenerationStoreReservation.forInput(
                        input.outputGenerationId(),
                        action.actionId(),
                        removeOverlappingDuplicates(action),
                        action.module().deploymentId(),
                        input.output(),
                        input.inputId(),
                        ImageDataStateCodec.ProjectionExecutionType.DURABLE_RUN,
                        input.rasterInput(),
                        planned.acceptance(),
                        ModelExecutionPlanCodec.unitCount(executionPlan),
                        action.clientBuild(),
                        Objects.requireNonNull(request, "request").expectedBackend(),
                        Optional.of(
                                ProcessingEvidenceCodec.fromPlan(request, input, executionPlan)));
        return new PreparedInput(
                request, input, planned, reservation, List.copyOf(existingObjects));
    }

    /** Realize one already validated terminal input as a complete candidate generation. */
    static CandidateGeneration complete(
            PreparedInput prepared,
            ModelRunPublicationCodec.InputResult manifestInput,
            CompleteModelResults.RetainedFeatures features,
            BackendExecutionCodec.DurableRun execution,
            BooleanSupplier shouldStop)
            throws IOException {
        PreparedInput input = Objects.requireNonNull(prepared, "prepared");
        return CandidatePipeline.generateDurable(
                input.request(),
                input.requestInput(),
                Objects.requireNonNull(manifestInput, "manifestInput"),
                Objects.requireNonNull(features, "features").validation(),
                consumer -> features.read(consumer, shouldStop),
                input.plannedInput(),
                Objects.requireNonNull(execution, "execution"),
                input.reservation(),
                input.existingObjects());
    }

    private static boolean removeOverlappingDuplicates(ImageExecutionPlan plan) {
        if (plan.action()
                instanceof qupath.ext.histoforge.contract.ActionSelectionCodec.Fill fill) {
            return fill.removeOverlappingSameClassDuplicates();
        }
        return ((qupath.ext.histoforge.contract.ActionSelectionCodec.Redo) plan.action())
                .removeOverlappingSameClassDuplicates();
    }
}
