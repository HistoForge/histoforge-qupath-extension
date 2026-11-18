package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.lib.images.ImageData;

class RecipeSourcePlannerRecoveryTest {

    @Test
    void publishedDerivedSourceReproducesTheFreshRecipeSourcePlan() {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData("source-replan", RecipeExecutorTestData.ROI_A);
        ModuleSnapshot module =
                RecipeExecutorTestData.durableModule(RecipeExecutorTestData.scriptedModule(false));
        ImageExecutionPlan plan =
                RecipeExecutorTestData.plan(
                        image,
                        module,
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        RecipeExecutorTestData.PublishedSourceFixture published =
                RecipeExecutorTestData.publishedSource(plan, image);

        SourceInputPlan replanned =
                RecipeSourcePlanner.planAgainstPublishedSource(
                        plan,
                        RecipeExecutorTestData.capturedSourcePyramid(),
                        image.getHierarchy(),
                        published.startRequest());

        assertEquals(published.sourcePlan(), replanned);
    }

    @Test
    void publishedSourceWhoseEnvelopeNoLongerMatchesFailsClosed() {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData(
                        "source-replan-mismatch", RecipeExecutorTestData.ROI_A);
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
        SourceArtifactCodec.SourceArtifactStartRequest current = published.startRequest();
        SourceArtifactCodec.DerivedRegion origin =
                (SourceArtifactCodec.DerivedRegion) current.origin();
        var rect = origin.sourceRectAtLevel();
        SourceArtifactCodec.SourceArtifactStartRequest altered =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        current.schemaVersion(),
                        current.creationActionId(),
                        current.artifactId(),
                        current.container(),
                        current.totalBytes(),
                        current.chunkSizeBytes(),
                        new SourceArtifactCodec.DerivedRegion(
                                origin.source(),
                                origin.sourceResolutionLevel(),
                                origin.plane(),
                                new qupath.ext.histoforge.contract.SourceIdentityCodec.IntRect(
                                        rect.x(), rect.y(), rect.width() - 1, rect.height()),
                                origin.artifactPixelsToSlide(),
                                origin.writerBuild()),
                        List.of(
                                new SourceArtifactCodec.SourceLevelRequirement(
                                        0, 0, rect.width() - 1, rect.height())));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RecipeSourcePlanner.planAgainstPublishedSource(
                                plan,
                                RecipeExecutorTestData.capturedSourcePyramid(),
                                image.getHierarchy(),
                                altered));
    }

    @Test
    void recoveryUsesRetainedSemanticRevisionAndFreshReaderPyramid() {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData(
                        "source-replan-revision", RecipeExecutorTestData.ROI_A);
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
        String generationId = "70000000-0000-4000-8000-000000000001";
        FiniteModelInputExecutor.PreparedInput finite =
                FiniteModelInputExecutor.prepare(
                        plan, 0, generationId, planned, published.artifact(), List.of());
        ModelRunRequestCodec.ModelRunInput retainedInput =
                new ModelRunRequestCodec.ModelRunInput(
                        finite.request().inputId(),
                        finite.request().outputGenerationId(),
                        planned.output(),
                        finite.request().rasterInput());
        Map<String, LargeActionHandleStore.AuthorizedModelInput> authorized =
                Map.of(
                        generationId,
                        new LargeActionHandleStore.AuthorizedModelInput(
                                retainedInput,
                                finite.request().expectedBackend(),
                                Optional.empty()));

        CapturedSourcePyramid original = RecipeExecutorTestData.capturedSourcePyramid();
        RasterInputCodec.ActionSourceRevision editedRevision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                original.sourceRevision().imageDataRevision().imageId(),
                                original.sourceRevision().imageDataRevision().imageSourceDigest(),
                                "9".repeat(64)),
                        original.sourceRevision().pixelSource());
        CapturedSourcePyramid current =
                new CapturedSourcePyramid(
                        editedRevision,
                        original.seriesIndex(),
                        original.levelZeroPixelsToSlide(),
                        original.levels());

        CapturedSourcePyramid planning =
                LargeActionRecipePlan.planningSourcePyramid(current, authorized);

        assertEquals(RecipeExecutorTestData.sourceRevision(), planning.sourceRevision());
        assertEquals(current.seriesIndex(), planning.seriesIndex());
        assertEquals(current.levelZeroPixelsToSlide(), planning.levelZeroPixelsToSlide());
        assertEquals(current.levels(), planning.levels());
    }

    @Test
    void recoveryRejectsAChangedPixelSourceBeforeReplanning() {
        CapturedSourcePyramid current = RecipeExecutorTestData.capturedSourcePyramid();
        SourceIdentityCodec.ImageSourceIdentity source = current.sourceRevision().pixelSource();
        SourceIdentityCodec.ImageSourceIdentity changedSource =
                new SourceIdentityCodec.ImageSourceIdentity(
                        source.sourceAdapterId(),
                        "9".repeat(64),
                        source.contentVersion(),
                        source.width(),
                        source.height(),
                        source.zSlices(),
                        source.timepoints(),
                        source.calibration());
        RasterInputCodec.ActionSourceRevision changedRevision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                current.sourceRevision().imageDataRevision().imageId(),
                                SourceIdentityCodec.imageSourceIdentityDigest(changedSource),
                                current.sourceRevision()
                                        .imageDataRevision()
                                        .imageDataContentDigest()),
                        changedSource);
        CapturedSourcePyramid changed =
                new CapturedSourcePyramid(
                        changedRevision,
                        current.seriesIndex(),
                        current.levelZeroPixelsToSlide(),
                        current.levels());
        ModelRunRequestCodec.ModelRunInput retained = retainedFieldInput();
        Map<String, LargeActionHandleStore.AuthorizedModelInput> authorized =
                Map.of(
                        retained.outputGenerationId(),
                        new LargeActionHandleStore.AuthorizedModelInput(
                                retained,
                                RecipeExecutorTestData.durableModule(
                                                RecipeExecutorTestData.fieldModule())
                                        .backendIdentity("parent"),
                                Optional.empty()));

        assertThrows(
                LargeActionTransactionValidator.Conflict.class,
                () -> LargeActionRecipePlan.planningSourcePyramid(changed, authorized));
    }

    @Test
    void recoveryRejectsRetainedInputsFromDifferentSemanticRevisions() {
        ModelRunRequestCodec.ModelRunInput first = retainedFieldInput();
        RasterInputCodec.RasterFrame frame = first.rasterInput().frame();
        RasterInputCodec.ActionSourceRevision changedRevision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                frame.sourceRevision().imageDataRevision().imageId(),
                                frame.sourceRevision().imageDataRevision().imageSourceDigest(),
                                "8".repeat(64)),
                        frame.sourceRevision().pixelSource());
        RasterInputCodec.RasterFrame changedFrame =
                RasterInputCodec.buildRasterFrame(
                        changedRevision,
                        frame.region(),
                        frame.transform(),
                        frame.inclusionMaskRaster());
        RasterInputCodec.ModelRasterInput changedRaster =
                RasterInputCodec.buildModelRasterInput(
                        first.rasterInput().sourceView(),
                        changedFrame,
                        first.rasterInput().rasterizer());
        ModelRunRequestCodec.ModelRunInput second =
                new ModelRunRequestCodec.ModelRunInput(
                        1, "70000000-0000-4000-8000-000000000003", first.output(), changedRaster);
        var backend =
                RecipeExecutorTestData.durableModule(RecipeExecutorTestData.fieldModule())
                        .backendIdentity("parent");
        Map<String, LargeActionHandleStore.AuthorizedModelInput> authorized =
                Map.of(
                        first.outputGenerationId(),
                        new LargeActionHandleStore.AuthorizedModelInput(
                                first, backend, Optional.empty()),
                        second.outputGenerationId(),
                        new LargeActionHandleStore.AuthorizedModelInput(
                                second, backend, Optional.empty()));

        assertThrows(
                LargeActionTransactionValidator.Conflict.class,
                () ->
                        LargeActionRecipePlan.planningSourcePyramid(
                                RecipeExecutorTestData.capturedSourcePyramid(), authorized));
    }

    private static ModelRunRequestCodec.ModelRunInput retainedFieldInput() {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData(
                        "source-replan-retained", RecipeExecutorTestData.ROI_A);
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
        FiniteModelInputExecutor.PreparedInput finite =
                FiniteModelInputExecutor.prepare(
                        plan,
                        0,
                        "70000000-0000-4000-8000-000000000002",
                        planned,
                        published.artifact(),
                        List.of());
        return new ModelRunRequestCodec.ModelRunInput(
                finite.request().inputId(),
                finite.request().outputGenerationId(),
                planned.output(),
                finite.request().rasterInput());
    }
}
