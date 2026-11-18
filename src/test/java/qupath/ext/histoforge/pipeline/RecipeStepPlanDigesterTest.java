package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.CropFraming;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputSnapshot;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.lib.images.ImageData;

class RecipeStepPlanDigesterTest {

    @Test
    void parentDigestCanonicalizesOrderAndBindsParentSemantics() {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData("parent-plan", RecipeExecutorTestData.ROI_A);
        ModuleSnapshot module =
                RecipeExecutorTestData.durableModule(RecipeExecutorTestData.scriptedModule(false));
        ImageExecutionPlan plan =
                RecipeExecutorTestData.plan(
                        image,
                        module,
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        ModuleSnapshot.ModelInputSnapshot child =
                plan.module().activatedModels().get("child").input();
        ExecutionScopeCodec.TargetRegion target = plan.targetRegions().getFirst();
        InferenceIdentityCodec.ModelStepIdentity parentProducer =
                RecipeExecutorTestData.producer("parent");
        RecipeStepPlanDigester.ParentEvidence first =
                new RecipeStepPlanDigester.ParentEvidence(
                        target.key(),
                        target.plane(),
                        "30000000-0000-4000-8000-000000000011",
                        "Eligible",
                        RecipeExecutorTestData.square(10, 10, 30, 30),
                        Optional.of(
                                new ExecutionScopeCodec.OutputKey(
                                        parentProducer,
                                        new ExecutionScopeCodec.FieldScope(target.key()))));
        RecipeStepPlanDigester.ParentEvidence second =
                new RecipeStepPlanDigester.ParentEvidence(
                        target.key(),
                        target.plane(),
                        "30000000-0000-4000-8000-000000000012",
                        "Eligible",
                        RecipeExecutorTestData.square(40, 10, 60, 30),
                        Optional.empty());

        String digest = RecipeStepPlanDigester.parentPlanDigest(child, List.of(second, first));
        assertEquals(
                digest, RecipeStepPlanDigester.parentPlanDigest(child, List.of(first, second)));
        assertNotEquals(
                digest,
                RecipeStepPlanDigester.parentPlanDigest(
                        child,
                        List.of(
                                first,
                                new RecipeStepPlanDigester.ParentEvidence(
                                        second.region(),
                                        second.plane(),
                                        second.parentId(),
                                        "Changed",
                                        second.geometrySlide(),
                                        second.logicalParentOutput()))));
        assertNotEquals(
                digest,
                RecipeStepPlanDigester.parentPlanDigest(
                        child,
                        List.of(
                                first,
                                new RecipeStepPlanDigester.ParentEvidence(
                                        second.region(),
                                        second.plane(),
                                        second.parentId(),
                                        second.pathClass(),
                                        RecipeExecutorTestData.square(40, 10, 61, 30),
                                        second.logicalParentOutput()))));
        assertNotEquals(
                digest,
                RecipeStepPlanDigester.parentPlanDigest(
                        new ModelInputSnapshot(
                                child.type(),
                                child.targetMpp() + 0.25,
                                child.fieldProfile(),
                                child.framing()),
                        List.of(first, second)));
        assertNotEquals(
                digest,
                RecipeStepPlanDigester.parentPlanDigest(
                        new ModelInputSnapshot(
                                child.type(),
                                child.targetMpp(),
                                Optional.of(
                                        new VariableFieldProfile(
                                                256,
                                                1024,
                                                768,
                                                16,
                                                8,
                                                false,
                                                0.25,
                                                "mean-logits-v1",
                                                "brightfield-rgb-v1")),
                                child.framing()),
                        List.of(first, second)));
        assertNotEquals(
                digest,
                RecipeStepPlanDigester.parentPlanDigest(
                        new ModelInputSnapshot(
                                child.type(),
                                child.targetMpp(),
                                child.fieldProfile(),
                                Optional.of(
                                        new CropFraming(
                                                child.framing().orElseThrow().paddingModelPx()
                                                        + 1))),
                        List.of(first, second)));
    }

    @Test
    void inputDigestBindsAssignmentsAndRetainsEmptyStep() {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData("input-plan", RecipeExecutorTestData.ROI_A);
        ModuleSnapshot module =
                RecipeExecutorTestData.durableModule(RecipeExecutorTestData.fieldModule());
        ImageExecutionPlan plan =
                RecipeExecutorTestData.plan(
                        image,
                        module,
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        RecipeExecutorTestData.PublishedSourceFixture published =
                RecipeExecutorTestData.publishedSource(plan, image);
        SourceInputPlan.PlannedModelInput planned =
                published
                        .sourcePlan()
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .fieldInputs()
                        .getFirst();
        ModuleSnapshot.ModelSnapshot model = module.activatedModels().get("parent");
        RasterInputCodec.SourceView sourceView =
                RasterInputCodec.buildSourceView(
                        published.artifact(),
                        planned.sourceView().seriesIndex(),
                        planned.sourceView().resolutionLevel(),
                        planned.sourceView().plane(),
                        planned.sourceView().pixelRect(),
                        planned.sourceView().viewPixelsToSlide());
        RasterInputCodec.ModelRasterInput rasterInput =
                RasterInputCodec.buildModelRasterInput(
                        sourceView, planned.frame(), model.capabilities().rasterizer());
        ModelRunRequestCodec.ModelRunRequest request = request(plan, model, planned, rasterInput);
        List<RecipeStepPlanDigester.RequestShard> shards =
                List.of(new RecipeStepPlanDigester.RequestShard(0, request));

        String retainedDigest = RecipeStepPlanDigester.inputPlanDigest(shards);
        assertNotEquals(
                retainedDigest,
                RecipeStepPlanDigester.inputPlanDigest(
                        List.of(
                                new RecipeStepPlanDigester.RequestShard(
                                        0,
                                        request(
                                                plan,
                                                model,
                                                planned,
                                                rasterInput,
                                                "70000000-0000-4000-8000-000000000099")))));

        String empty = RecipeStepPlanDigester.inputPlanDigest(List.of());
        assertEquals("c5f2fcebd4615ffcb2c6402e575a10b762b533a595c1303a60e766de25730320", empty);
    }

    @Test
    void inputDigestRetainsMixedFiniteAndProfileRequestShards() {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData("mixed-plan", RecipeExecutorTestData.ROI_A);
        ModuleSnapshot module =
                RecipeExecutorTestData.durableModule(RecipeExecutorTestData.fieldModule());
        ImageExecutionPlan plan =
                RecipeExecutorTestData.plan(
                        image,
                        module,
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        RecipeExecutorTestData.PublishedSourceFixture published =
                RecipeExecutorTestData.publishedSource(plan, image);
        SourceInputPlan.PlannedModelInput planned =
                published
                        .sourcePlan()
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .fieldInputs()
                        .getFirst();
        ModuleSnapshot.ModelSnapshot model = module.activatedModels().get("parent");
        RasterInputCodec.SourceView sourceView =
                RasterInputCodec.buildSourceView(
                        published.artifact(),
                        planned.sourceView().seriesIndex(),
                        planned.sourceView().resolutionLevel(),
                        planned.sourceView().plane(),
                        planned.sourceView().pixelRect(),
                        planned.sourceView().viewPixelsToSlide());
        RasterInputCodec.ModelRasterInput rasterInput =
                RasterInputCodec.buildModelRasterInput(
                        sourceView, planned.frame(), model.capabilities().rasterizer());
        ModelRunRequestCodec.ModelRunRequest base = request(plan, model, planned, rasterInput);
        ModelRunRequestCodec.ModelRunInput first = base.inputs().getFirst();
        ExecutionScopeCodec.OutputKey secondOutput =
                new ExecutionScopeCodec.OutputKey(
                        first.output().producer(),
                        new ExecutionScopeCodec.ParentScope(
                                ExecutionScopeCodec.regionOf(first.output().scope()),
                                "30000000-0000-4000-8000-000000000099"));
        ExecutionScopeCodec.OutputKey thirdOutput =
                new ExecutionScopeCodec.OutputKey(
                        first.output().producer(),
                        new ExecutionScopeCodec.ParentScope(
                                ExecutionScopeCodec.regionOf(first.output().scope()),
                                "30000000-0000-4000-8000-000000000098"));
        ModelRunRequestCodec.ModelRunInput second =
                new ModelRunRequestCodec.ModelRunInput(
                        1,
                        "70000000-0000-4000-8000-000000000099",
                        secondOutput,
                        first.rasterInput());
        ModelRunRequestCodec.ModelRunInput third =
                new ModelRunRequestCodec.ModelRunInput(
                        2,
                        "70000000-0000-4000-8000-000000000098",
                        thirdOutput,
                        first.rasterInput());
        ModelRunRequestCodec.ModelRunRequest finite =
                new ModelRunRequestCodec.ModelRunRequest(
                        base.schemaVersion(),
                        base.actionId(),
                        base.modelRunId(),
                        base.producer(),
                        base.expectedBackend(),
                        Optional.empty(),
                        List.of(first, third),
                        base.resultBudget(),
                        Optional.empty(),
                        java.util.Optional.empty());
        String validationEvidenceDigest = "e".repeat(64);
        JsonObject profilePreimage = new JsonObject();
        profilePreimage.addProperty("type", "global-decode");
        profilePreimage.addProperty("validation_evidence_digest", validationEvidenceDigest);
        LargeRegionProfileCodec.GlobalDecodeProfile profile =
                new LargeRegionProfileCodec.GlobalDecodeProfile(
                        CanonicalCodec.canonicalDigest(profilePreimage), validationEvidenceDigest);
        ModelRunRequestCodec.ModelRunRequest profiled =
                new ModelRunRequestCodec.ModelRunRequest(
                        base.schemaVersion(),
                        base.actionId(),
                        "60000000-0000-4000-8000-000000000099",
                        base.producer(),
                        base.expectedBackend(),
                        Optional.of(profile),
                        List.of(second),
                        base.resultBudget(),
                        Optional.empty(),
                        java.util.Optional.empty());

        List<RecipeStepPlanDigester.RequestShard> shards =
                List.of(
                        new RecipeStepPlanDigester.RequestShard(0, finite),
                        new RecipeStepPlanDigester.RequestShard(1, profiled));
        String mixed = RecipeStepPlanDigester.inputPlanDigest(shards);
        assertNotEquals(
                RecipeStepPlanDigester.inputPlanDigest(
                        List.of(new RecipeStepPlanDigester.RequestShard(0, finite))),
                mixed);
    }

    private static ModelRunRequestCodec.ModelRunRequest request(
            ImageExecutionPlan plan,
            ModuleSnapshot.ModelSnapshot model,
            SourceInputPlan.PlannedModelInput planned,
            RasterInputCodec.ModelRasterInput rasterInput) {
        return request(plan, model, planned, rasterInput, "70000000-0000-4000-8000-000000000001");
    }

    private static ModelRunRequestCodec.ModelRunRequest request(
            ImageExecutionPlan plan,
            ModuleSnapshot.ModelSnapshot model,
            SourceInputPlan.PlannedModelInput planned,
            RasterInputCodec.ModelRasterInput rasterInput,
            String generationId) {
        var result = model.capabilities().durableRun().orElseThrow().run().result();
        return new ModelRunRequestCodec.ModelRunRequest(
                ModelRunRequestCodec.SCHEMA_VERSION,
                plan.actionId(),
                "60000000-0000-4000-8000-000000000001",
                planned.output().producer(),
                plan.module().backendIdentity("parent"),
                Optional.empty(),
                List.of(
                        new ModelRunRequestCodec.ModelRunInput(
                                0, generationId, planned.output(), rasterInput)),
                new FiniteInferenceCodec.ClientResultBudget(
                        result.maxTransferredBytes(),
                        result.maxDecodedBytes(),
                        result.maxRecordBytes(),
                        result.maxFeatures(),
                        result.maxVertices()),
                Optional.empty(),
                java.util.Optional.empty());
    }
}
