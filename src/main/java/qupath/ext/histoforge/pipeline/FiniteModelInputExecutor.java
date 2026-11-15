package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.CandidatePipeline;
import qupath.ext.histoforge.spatial.SourceInputPlan;

/** Builds and executes one exact finite logical input outside recipe orchestration. */
final class FiniteModelInputExecutor {

    private final ModelGatewayExecutor gateway;

    FiniteModelInputExecutor(ModelGatewayExecutor gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** Complete pre-dispatch values needed for projection, execution, and candidate validation. */
    record PreparedInput(
            FiniteInferenceCodec.FiniteInferenceRequest request,
            SourceInputPlan.PlannedModelInput plannedInput,
            ImageDataStateCodec.GenerationStoreReservation reservation,
            ModuleSnapshot.ModelSnapshot model,
            List<CandidatePipeline.ExistingObjectSnapshot> existingObjects)
            implements RecipeModelInputAdapter.PreparedInput {
        PreparedInput {
            request = Objects.requireNonNull(request, "request");
            plannedInput = Objects.requireNonNull(plannedInput, "plannedInput");
            reservation = Objects.requireNonNull(reservation, "reservation");
            model = Objects.requireNonNull(model, "model");
            existingObjects =
                    List.copyOf(Objects.requireNonNull(existingObjects, "existingObjects"));
            if (!request.actionId().equals(reservation.actionId())
                    || !request.outputGenerationId().equals(reservation.outputGenerationId())
                    || request.inputId() != reservation.inputId()
                    || !request.producer().equals(reservation.output().producer())
                    || !request.expectedBackend().equals(reservation.backendIdentity())
                    || !request.rasterInput()
                            .inputDigest()
                            .equals(reservation.modelRasterInputDigest())
                    || !plannedInput.output().equals(reservation.output())) {
                throw new IllegalArgumentException(
                        "prepared finite request, plan, and reservation do not match");
            }
        }
    }

    /** Planned input whose exact uploaded image is supplied only after output-space admission. */
    record InputAwaitingUpload(
            RasterInputCodec.ModelRasterInput rasterInput,
            SourceInputPlan.PlannedModelInput plannedInput,
            ImageDataStateCodec.GenerationStoreReservation reservation,
            ModuleSnapshot.ModelSnapshot model,
            List<CandidatePipeline.ExistingObjectSnapshot> existingObjects)
            implements RecipeModelInputAdapter.PreparedInput {
        InputAwaitingUpload {
            Objects.requireNonNull(rasterInput, "rasterInput");
            Objects.requireNonNull(plannedInput, "plannedInput");
            Objects.requireNonNull(reservation, "reservation");
            Objects.requireNonNull(model, "model");
            existingObjects = List.copyOf(existingObjects);
            if (!(rasterInput.sourceView() instanceof SourceCaptureCodec.View)
                    || !rasterInput.inputDigest().equals(reservation.modelRasterInputDigest())
                    || !plannedInput.output().equals(reservation.output())) {
                throw new IllegalArgumentException("pending image does not match its reservation");
            }
        }

        PreparedInput bind(SourceCaptureCodec.Binding binding) {
            return prepared(
                    rasterInput,
                    plannedInput,
                    reservation,
                    model,
                    existingObjects,
                    Optional.of(binding));
        }
    }

    /** Build a request from an already published complete source, without network IO. */
    static PreparedInput prepare(
            ImageExecutionPlan plan,
            int inputId,
            String outputGenerationId,
            SourceInputPlan.PlannedModelInput plannedInput,
            SourceArtifactPublicationCodec.SourceArtifactRef artifact,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existingObjects) {
        return (PreparedInput)
                plan(
                        plan,
                        inputId,
                        outputGenerationId,
                        plannedInput,
                        new RecipeExecutor.PublishedSource(artifact),
                        existingObjects);
    }

    /** Reserve one input without IO; the recipe admits all step reservations before execution. */
    static RecipeModelInputAdapter.PreparedInput plan(
            ImageExecutionPlan plan,
            int inputId,
            String outputGenerationId,
            SourceInputPlan.PlannedModelInput plannedInput,
            RecipeExecutor.AvailableSource source,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existingObjects) {
        Objects.requireNonNull(plan, "plan");
        SourceInputPlan.PlannedModelInput input =
                Objects.requireNonNull(plannedInput, "plannedInput");
        Objects.requireNonNull(source, "source");
        InferenceIdentityCodec.ModelStepIdentity producer = input.output().producer();
        if (!producer.moduleRef().equals(plan.module().module().moduleRef())) {
            throw new IllegalArgumentException(
                    "planned input producer does not belong to the captured module");
        }
        ModuleSnapshot.ModelSnapshot model = plan.module().activatedModels().get(producer.stepId());
        if (model == null) {
            throw new IllegalArgumentException(
                    "planned input model is absent from the captured activation");
        }
        InferenceIdentityCodec.BackendModelIdentity backend =
                plan.module().backendIdentity(producer.stepId());
        RasterInputCodec.ModelRasterInput rasterInput =
                RasterInputCodec.buildModelRasterInput(
                        source.viewFor(input.sourceView()), input.frame(), backend.rasterizer());
        ImageDataStateCodec.GenerationStoreReservation reservation =
                ImageDataStateCodec.GenerationStoreReservation.forInput(
                        outputGenerationId,
                        plan.actionId(),
                        removeOverlappingDuplicates(plan),
                        plan.module().deploymentId(),
                        input.output(),
                        inputId,
                        ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL,
                        rasterInput,
                        input.acceptance(),
                        1,
                        plan.clientBuild(),
                        backend,
                        Optional.empty());
        List<CandidatePipeline.ExistingObjectSnapshot> existing = List.copyOf(existingObjects);
        if (source instanceof RecipeExecutor.CapturedSource) {
            return new InputAwaitingUpload(rasterInput, input, reservation, model, existing);
        }
        return prepared(rasterInput, input, reservation, model, existing, Optional.empty());
    }

    private static PreparedInput prepared(
            RasterInputCodec.ModelRasterInput rasterInput,
            SourceInputPlan.PlannedModelInput input,
            ImageDataStateCodec.GenerationStoreReservation reservation,
            ModuleSnapshot.ModelSnapshot model,
            List<CandidatePipeline.ExistingObjectSnapshot> existingObjects,
            Optional<SourceCaptureCodec.Binding> binding) {
        var request =
                new FiniteInferenceCodec.FiniteInferenceRequest(
                        FiniteInferenceCodec.SCHEMA_VERSION,
                        reservation.actionId(),
                        reservation.outputGenerationId(),
                        reservation.inputId(),
                        input.output().producer(),
                        reservation.backendIdentity(),
                        rasterInput,
                        resultBudget(model.capabilities().finite().result()),
                        binding);
        return new PreparedInput(request, input, reservation, model, existingObjects);
    }

    /** Execute the prepared input and return only fully validated slide-coordinate candidates. */
    CandidateGeneration execute(PreparedInput prepared, BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    ModelGatewayExecutor.AcceptedFiniteCallFailure {
        PreparedInput input = Objects.requireNonNull(prepared, "prepared");
        ModelGatewayExecutor.CompletedFiniteCall completed =
                gateway.executeFinite(
                        input.request(),
                        input.model().capabilities(),
                        input.model().output().emittedClasses(),
                        shouldStop);
        try {
            return CandidatePipeline.generateFinite(
                    input.request(),
                    completed.result(),
                    input.plannedInput(),
                    completed.execution(),
                    input.reservation(),
                    input.existingObjects());
        } catch (RuntimeException failure) {
            throw new ModelGatewayExecutor.AcceptedFiniteCallFailure(
                    completed.execution(), failure);
        }
    }

    private static FiniteInferenceCodec.ClientResultBudget resultBudget(
            ExecutionLimitsCodec.ResultLimits limits) {
        return new FiniteInferenceCodec.ClientResultBudget(
                limits.maxTransferredBytes(),
                limits.maxDecodedBytes(),
                limits.maxRecordBytes(),
                limits.maxFeatures(),
                limits.maxVertices());
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
