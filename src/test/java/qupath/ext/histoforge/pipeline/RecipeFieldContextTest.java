package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;

/** Saved model context reaches source preparation and the retained recovery plan. */
class RecipeFieldContextTest {

    @Test
    void savedContextExpandsOnlyItsModelAndReachesThePreparedRequest() throws Exception {
        ModuleSnapshot module = withContext(RecipeExecutorTestData.twoFieldModule(), true);
        try (var image =
                RecipeExecutorTestData.imageData("field-context", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            var plan = RecipeExecutorTestData.plan(image, module, action);
            var published = RecipeExecutorTestData.publishedSource(plan, image);
            var target = published.sourcePlan().targets().getFirst().sourcePlan().orElseThrow();
            var expanded = target.fieldInputs().get(0);
            var unchanged = target.fieldInputs().get(1);

            // The 100 x 100 ROI touches the top/left image edge. Centered expansion to 128
            // clips there, leaving 114 real pixels. It must not shift to obtain a full 128.
            assertEquals(114, expanded.frame().transform().raster().width());
            assertEquals(114, expanded.frame().transform().raster().height());
            assertEquals(100, unchanged.frame().transform().raster().width());
            assertEquals(100, unchanged.frame().transform().raster().height());
            assertEquals(
                    new SourceIdentityCodec.IntRect(0, 0, 114, 114),
                    target.sourceEnvelopeAtLevel());
            assertEquals(unchanged.acceptance(), expanded.acceptance());
            assertEquals(
                    unchanged.frame().inclusionMaskRaster(),
                    expanded.frame().inclusionMaskRaster());

            var prepared =
                    FiniteModelInputExecutor.prepare(
                            plan,
                            0,
                            "70000000-0000-4000-8000-000000000010",
                            expanded,
                            published.artifact(),
                            List.of());
            assertEquals(expanded.frame(), prepared.request().rasterInput().frame());
            assertEquals(
                    expanded.sourceView().pixelRect(),
                    prepared.request().rasterInput().sourceView().pixelRect());
            assertEquals(
                    published.sourcePlan(),
                    RecipeSourcePlanner.planAgainstPublishedSource(
                            plan,
                            RecipeExecutorTestData.capturedSourcePyramid(),
                            image.getHierarchy(),
                            published.startRequest()));
        }
    }

    @Test
    void removingContextCannotReproduceItsPublishedSourcePlan() throws Exception {
        ModuleSnapshot fixed = RecipeExecutorTestData.fieldModule();
        try (var image =
                RecipeExecutorTestData.imageData(
                        "field-context-recovery", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            var plan = RecipeExecutorTestData.plan(image, withContext(fixed, true), action);
            var published = RecipeExecutorTestData.publishedSource(plan, image);
            var withoutContext =
                    RecipeExecutorTestData.plan(image, withContext(fixed, false), action);

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RecipeSourcePlanner.planAgainstPublishedSource(
                                    withoutContext,
                                    RecipeExecutorTestData.capturedSourcePyramid(),
                                    image.getHierarchy(),
                                    published.startRequest()));
        }
    }

    @Test
    void disabledContextPreservesTheCompleteFixedSourcePlan() throws Exception {
        ModuleSnapshot fixed = RecipeExecutorTestData.fieldModule();
        try (var image =
                RecipeExecutorTestData.imageData(
                        "field-without-context", RecipeExecutorTestData.ROI_A)) {
            var action =
                    RecipeExecutorTestData.allModels(false, Set.of(RecipeExecutorTestData.ROI_A));
            var fixedPlan = RecipeExecutorTestData.plan(image, fixed, action);
            var variablePlan =
                    RecipeExecutorTestData.plan(image, withContext(fixed, false), action);

            assertEquals(
                    RecipeExecutorTestData.publishedSource(fixedPlan, image).sourcePlan(),
                    RecipeExecutorTestData.publishedSource(variablePlan, image).sourcePlan());
        }
    }

    private static ModuleSnapshot withContext(ModuleSnapshot module, boolean context) {
        ModelSnapshot parent = module.activatedModels().get("parent");
        var profile =
                new VariableFieldProfile(
                        128,
                        256,
                        192,
                        16,
                        8,
                        context,
                        0.2,
                        "test-only-aggregation",
                        "test-only-preprocessing");
        var input = parent.input();
        var contextual =
                new ModelSnapshot(
                        parent.architecture(),
                        parent.gpu(),
                        parent.modelConfigDigest(),
                        new ModelInputSnapshot(
                                input.type(),
                                input.targetMpp(),
                                Optional.of(profile),
                                input.framing()),
                        parent.output(),
                        parent.capabilities(),
                        parent.largeRegionProfile(),
                        parent.artifactDigests());
        var models = new LinkedHashMap<>(module.activatedModels());
        models.put("parent", contextual);
        return new ModuleSnapshot(
                module.deploymentId(),
                module.module(),
                module.exactRecipe(),
                module.scriptSetDigest(),
                models,
                module.scripts(),
                module.metrics());
    }
}
