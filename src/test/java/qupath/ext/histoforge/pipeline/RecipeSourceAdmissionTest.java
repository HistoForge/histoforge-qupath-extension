package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.lib.images.ImageData;

/** Known-input capability checks during planning, without materializing source pixels. */
class RecipeSourceAdmissionTest {

    @ParameterizedTest
    @CsvSource({
        "256, 128, 32768, true",
        "255, 128, 32768, false",
        "256, 127, 32768, false",
        "256, 128, 32767, false"
    })
    void checksAdvertisedLargeInputLimitsBeforeUpload(
            int maxWidth, int maxHeight, long maxPixels, boolean supported) throws Exception {
        var module =
                RecipeExecutorTestData.forceRecoverableProtocol(
                        RecipeExecutorTestData.durableModule(RecipeExecutorTestData.fieldModule()));
        var model = module.activatedModels().get("parent");
        var capabilities = RunnerCapabilitiesCodec.toJson(model.capabilities());
        var rasterLimit =
                capabilities
                        .getAsJsonObject("durable_run")
                        .getAsJsonObject("run")
                        .getAsJsonObject("target_raster_per_input");
        rasterLimit.addProperty("max_width", maxWidth);
        rasterLimit.addProperty("max_height", maxHeight);
        rasterLimit.addProperty("max_pixels", maxPixels);
        module =
                withModel(
                        module,
                        "parent",
                        new ModelSnapshot(
                                model.architecture(),
                                model.gpu(),
                                model.modelConfigDigest(),
                                model.input(),
                                model.output(),
                                RunnerCapabilitiesCodec.parse(capabilities),
                                model.largeRegionProfile(),
                                model.artifactDigests()));
        var work =
                new ActionSelectionCodec.AllModels(
                        new ActionSelectionCodec.SlideRegion(new ActionSelectionCodec.FullSlide()));
        var source = RecipeExecutorTestData.capturedSourcePyramid();
        try (ImageData<?> image = RecipeExecutorTestData.imageData("bounded-large-field")) {
            var fill = new ActionSelectionCodec.Fill(work, true);
            var action = RecipeExecutorTestData.plan(image, module, fill);
            if (supported) {
                assertEquals(
                        SourceInputPlan.ExecutionProtocol.RECOVERABLE,
                        plan(action, image, source).sourcePlan().orElseThrow().protocol());
            } else {
                var failure =
                        assertThrows(
                                IllegalArgumentException.class, () -> plan(action, image, source));
                assertTrue(failure.getMessage().contains("256 × 128 model-pixel input"));
                assertTrue(failure.getMessage().contains("No image was uploaded"));
            }
            image.getHierarchy()
                    .getRootObject()
                    .addChildObject(
                            RecipeExecutorTestData.attributed(
                                    RecipeExecutorTestData.OLD_PARENT_ID,
                                    "Parent",
                                    RecipeExecutorTestData.square(10, 10, 20, 20),
                                    "parent",
                                    new ExecutionScopeCodec.FieldScope(
                                            new ExecutionScopeCodec.Slide())));
            // Existing results remain usable even if a newer backend has a smaller limit.
            plan(RecipeExecutorTestData.plan(image, module, fill), image, source);
        }
    }

    @Test
    void rejectsKnownLargeFieldWithoutProfileButKeepsFillsExistingOutput() throws Exception {
        ModuleSnapshot supported =
                RecipeExecutorTestData.forceRecoverableProtocol(
                        RecipeExecutorTestData.durableModule(RecipeExecutorTestData.fieldModule()));
        ModuleSnapshot unsupported = withoutLargeRegionProfile(supported, "parent");
        var work =
                new ActionSelectionCodec.AllModels(
                        new ActionSelectionCodec.SlideRegion(new ActionSelectionCodec.FullSlide()));
        var source = RecipeExecutorTestData.capturedSourcePyramid();
        try (ImageData<?> image = RecipeExecutorTestData.imageData("large-field")) {
            var fill = new ActionSelectionCodec.Fill(work, true);
            var plan = RecipeExecutorTestData.plan(image, unsupported, fill);
            var failure =
                    assertThrows(IllegalArgumentException.class, () -> plan(plan, image, source));
            assertTrue(failure.getMessage().contains("256 × 128 model-pixel input"));
            assertTrue(failure.getMessage().contains("No image was uploaded"));

            var supportedPlan = RecipeExecutorTestData.plan(image, supported, fill);
            assertEquals(
                    SourceInputPlan.ExecutionProtocol.RECOVERABLE,
                    plan(supportedPlan, image, source).sourcePlan().orElseThrow().protocol());

            image.getHierarchy()
                    .getRootObject()
                    .addChildObject(
                            RecipeExecutorTestData.attributed(
                                    RecipeExecutorTestData.OLD_PARENT_ID,
                                    "Parent",
                                    RecipeExecutorTestData.square(10, 10, 20, 20),
                                    "parent",
                                    new ExecutionScopeCodec.FieldScope(
                                            new ExecutionScopeCodec.Slide())));
            // Fill keeps this existing output. Redo would actually invoke the unsupported model.
            plan(RecipeExecutorTestData.plan(image, unsupported, fill), image, source);
            var redo =
                    RecipeExecutorTestData.plan(
                            image, unsupported, new ActionSelectionCodec.Redo(work, true));
            assertThrows(IllegalArgumentException.class, () -> plan(redo, image, source));
        }
    }

    @Test
    void doesNotRejectHypotheticalLargeParentsBeforeTheParentModelRuns() throws Exception {
        var module =
                withoutLargeRegionProfile(
                        RecipeExecutorTestData.mixedFamilyDurableModule(), "child");
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("unknown-parents", RecipeExecutorTestData.ROI_A)) {
            var plan =
                    RecipeExecutorTestData.plan(
                            image,
                            module,
                            RecipeExecutorTestData.allModels(
                                    false, Set.of(RecipeExecutorTestData.ROI_A)));
            assertEquals(
                    1,
                    plan(plan, image, RecipeExecutorTestData.capturedSourcePyramid())
                            .sourcePlan()
                            .orElseThrow()
                            .cropInputs()
                            .size());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "true, 0.25, 0.25, 0.5, 128, 64, true",
        "false, 0.5, 0.5, 0.25, 200, 200, false",
        "true, 0.25, 0.5, 0.5, 128, 128, true",
        "false, 0.25, 0.5, 0.25, 100, 200, false"
    })
    void checksModelResolutionInsteadOfSlidePixelDimensions(
            boolean wholeSlide,
            double sourceMppX,
            double sourceMppY,
            double targetMpp,
            int expectedWidth,
            int expectedHeight,
            boolean fitsFinite)
            throws Exception {
        ModuleSnapshot module =
                withoutLargeRegionProfile(
                        RecipeExecutorTestData.forceRecoverableProtocol(
                                RecipeExecutorTestData.durableModule(
                                        RecipeExecutorTestData.fieldModule())),
                        "parent");
        module = withTargetMpp(module, targetMpp);
        var source = sourceAtMpp(sourceMppX, sourceMppY);
        // The whole image is 256 × 128 slide pixels; ROI_A is 100 × 100.
        // The finite model limit is 128 × 128, independently of the slide's calibration.
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("scaled-field", RecipeExecutorTestData.ROI_A)) {
            ActionSelectionCodec.ImageAction action =
                    wholeSlide
                            ? new ActionSelectionCodec.Fill(
                                    new ActionSelectionCodec.AllModels(
                                            new ActionSelectionCodec.SlideRegion(
                                                    new ActionSelectionCodec.FullSlide())),
                                    true)
                            : RecipeExecutorTestData.allModels(
                                    false, Set.of(RecipeExecutorTestData.ROI_A));
            var plan = RecipeExecutorTestData.plan(image, module, action);
            if (fitsFinite) {
                var raster =
                        plan(plan, image, source)
                                .sourcePlan()
                                .orElseThrow()
                                .fieldInputs()
                                .getFirst()
                                .frame()
                                .transform()
                                .raster();
                assertEquals(expectedWidth, raster.width());
                assertEquals(expectedHeight, raster.height());
                assertEquals(targetMpp, raster.targetMpp());
            } else {
                var failure =
                        assertThrows(
                                IllegalArgumentException.class, () -> plan(plan, image, source));
                assertTrue(
                        failure.getMessage()
                                .contains(
                                        expectedWidth
                                                + " × "
                                                + expectedHeight
                                                + " model-pixel input"));
            }
        }
    }

    private static SourceInputPlan.TargetPlanningResult plan(
            ImageExecutionPlan plan, ImageData<?> image, CapturedSourcePyramid source) {
        return RecipeSourcePlanner.plan(
                        plan,
                        source,
                        image.getHierarchy(),
                        Optional.empty(),
                        RecipeExecutorTestData.sourcePlanningLimits())
                .targets()
                .getFirst();
    }

    private static ModuleSnapshot withoutLargeRegionProfile(ModuleSnapshot module, String modelId) {
        var model = module.activatedModels().get(modelId);
        return withModel(
                module,
                modelId,
                new ModelSnapshot(
                        model.architecture(),
                        model.gpu(),
                        model.modelConfigDigest(),
                        model.input(),
                        model.output(),
                        model.capabilities(),
                        Optional.empty(),
                        model.artifactDigests()));
    }

    private static ModuleSnapshot withTargetMpp(ModuleSnapshot module, double targetMpp) {
        var model = module.activatedModels().get("parent");
        var input = model.input();
        return withModel(
                module,
                "parent",
                new ModelSnapshot(
                        model.architecture(),
                        model.gpu(),
                        model.modelConfigDigest(),
                        new ModelInputSnapshot(
                                input.type(), targetMpp, input.fieldProfile(), input.framing()),
                        model.output(),
                        model.capabilities(),
                        model.largeRegionProfile(),
                        model.artifactDigests()));
    }

    private static ModuleSnapshot withModel(
            ModuleSnapshot module, String modelId, ModelSnapshot model) {
        var models = new LinkedHashMap<>(module.activatedModels());
        models.put(modelId, model);
        return new ModuleSnapshot(
                module.deploymentId(),
                module.module(),
                module.exactRecipe(),
                module.scriptSetDigest(),
                models,
                module.scripts(),
                module.metrics());
    }

    private static CapturedSourcePyramid sourceAtMpp(double mppX, double mppY) {
        var captured = RecipeExecutorTestData.capturedSourcePyramid();
        var revision = captured.sourceRevision();
        var original = revision.pixelSource();
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        original.sourceAdapterId(),
                        original.credentialFreeDescriptorDigest(),
                        original.contentVersion(),
                        original.width(),
                        original.height(),
                        original.zSlices(),
                        original.timepoints(),
                        new SourceIdentityCodec.PixelCalibration(mppX, mppY));
        return new CapturedSourcePyramid(
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                revision.imageDataRevision().imageId(),
                                SourceIdentityCodec.imageSourceIdentityDigest(source),
                                revision.imageDataRevision().imageDataContentDigest()),
                        source),
                captured.seriesIndex(),
                captured.levelZeroPixelsToSlide(),
                captured.levels());
    }
}
