package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.CropFraming;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;
import qupath.ext.histoforge.roi.RoiManager;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourceInputPlanner;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Recipe/action-to-source planning tests through the real hierarchy boundary. */
class RecipeSourcePlannerTest {

    private static final String DISTANT_PARENT_ID = "30000000-0000-4000-8000-000000000007";
    private static final String FOREIGN_PARENT_ID = "30000000-0000-4000-8000-000000000008";

    @Test
    void projectsSelectedFieldModelAndCapturedSourceIdentity() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("source-field", RecipeExecutorTestData.ROI_A)) {
            ImageExecutionPlan plan =
                    RecipeExecutorTestData.plan(
                            image,
                            module,
                            RecipeExecutorTestData.allModels(
                                    false, Set.of(RecipeExecutorTestData.ROI_A)));

            SourceInputPlan.TargetSourcePlan source = plan(plan, image).sourcePlan().orElseThrow();

            assertEquals(SourceInputPlan.ExecutionProtocol.FINITE, source.protocol());
            assertEquals(1, source.fieldInputs().size());
            assertTrue(source.cropInputs().isEmpty());
            assertEquals(
                    RecipeExecutorTestData.producer("parent"),
                    source.fieldInputs().getFirst().output().producer());
            assertEquals(
                    new ExecutionScopeCodec.FieldScope(
                            new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A)),
                    source.fieldInputs().getFirst().output().scope());

            CapturedSourcePyramid captured = RecipeExecutorTestData.capturedSourcePyramid();
            RasterInputCodec.ImageRevisionToken revision =
                    captured.sourceRevision().imageDataRevision();
            CapturedSourcePyramid wrongImage =
                    new CapturedSourcePyramid(
                            new RasterInputCodec.ActionSourceRevision(
                                    new RasterInputCodec.ImageRevisionToken(
                                            "another-image",
                                            revision.imageSourceDigest(),
                                            revision.imageDataContentDigest()),
                                    captured.sourceRevision().pixelSource()),
                            captured.seriesIndex(),
                            captured.levelZeroPixelsToSlide(),
                            captured.levels());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RecipeSourcePlanner.plan(
                                    plan,
                                    wrongImage,
                                    image.getHierarchy(),
                                    Optional.empty(),
                                    RecipeExecutorTestData.sourcePlanningLimits()));
        }
    }

    @Test
    void exactParentSelectionBoundsOnlyThatEligibleCurrentParent() throws Exception {
        ModuleSnapshot module = childOnly(RecipeExecutorTestData.scriptedModule(false));
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData("source-parent", RecipeExecutorTestData.ROI_A)) {
            PathObject selected =
                    RecipeExecutorTestData.manual(
                            RecipeExecutorTestData.MANUAL_PARENT_ID,
                            "Eligible",
                            RecipeExecutorTestData.square(10, 10, 20, 20));
            PathObject distant =
                    RecipeExecutorTestData.manual(
                            DISTANT_PARENT_ID,
                            "Eligible",
                            RecipeExecutorTestData.square(70, 70, 80, 80));
            image.getHierarchy().getRootObject().addChildObjects(List.of(selected, distant));
            ImageExecutionPlan plan =
                    RecipeExecutorTestData.plan(
                            image,
                            module,
                            RecipeExecutorTestData.selectedChildRedo(
                                    RecipeExecutorTestData.ROI_A,
                                    RecipeExecutorTestData.MANUAL_PARENT_ID));

            SourceInputPlan.TargetSourcePlan source = plan(plan, image).sourcePlan().orElseThrow();

            assertTrue(source.fieldInputs().isEmpty());
            assertEquals(1, source.cropInputs().size());
            SourceInputPlanner.planParentInput(
                    source,
                    RecipeExecutorTestData.producer("child"),
                    RecipeExecutorTestData.MANUAL_PARENT_ID,
                    selected.getROI().getGeometry());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            SourceInputPlanner.planParentInput(
                                    source,
                                    RecipeExecutorTestData.producer("child"),
                                    DISTANT_PARENT_ID,
                                    distant.getROI().getGeometry()));
        }
    }

    @Test
    void broadChildUsesOnlyReachableCurrentParentDomain() throws Exception {
        ModuleSnapshot module = childOnly(RecipeExecutorTestData.scriptedModule(false));
        ActionSelectionCodec.ImageAction action = selectedChildRedoBroad();
        try (ImageData<?> empty =
                        RecipeExecutorTestData.imageData(
                                "source-empty", RecipeExecutorTestData.ROI_A);
                ImageData<?> manual =
                        RecipeExecutorTestData.imageData(
                                "source-manual", RecipeExecutorTestData.ROI_A);
                ImageData<?> modelOutput =
                        RecipeExecutorTestData.imageData(
                                "source-model-output", RecipeExecutorTestData.ROI_A);
                ImageData<?> foreign =
                        RecipeExecutorTestData.imageData(
                                "source-foreign", RecipeExecutorTestData.ROI_A)) {
            ImageExecutionPlan emptyPlan = RecipeExecutorTestData.plan(empty, module, action);
            assertTrue(plan(emptyPlan, empty).sourcePlan().isEmpty());

            manual.getHierarchy()
                    .getRootObject()
                    .addChildObject(
                            RecipeExecutorTestData.manual(
                                    RecipeExecutorTestData.MANUAL_PARENT_ID,
                                    "Parent",
                                    RecipeExecutorTestData.square(10, 10, 20, 20)));
            ImageExecutionPlan manualPlan = RecipeExecutorTestData.plan(manual, module, action);
            assertTrue(plan(manualPlan, manual).sourcePlan().isEmpty());

            modelOutput
                    .getHierarchy()
                    .getRootObject()
                    .addChildObject(
                            RecipeExecutorTestData.attributed(
                                    RecipeExecutorTestData.OLD_PARENT_ID,
                                    "Edited",
                                    RecipeExecutorTestData.square(10, 10, 20, 20),
                                    "parent",
                                    new ExecutionScopeCodec.FieldScope(
                                            new ExecutionScopeCodec.Roi(
                                                    RecipeExecutorTestData.ROI_A))));
            ImageExecutionPlan modelOutputPlan =
                    RecipeExecutorTestData.plan(modelOutput, module, action);
            assertTrue(plan(modelOutputPlan, modelOutput).sourcePlan().isPresent());

            PathObject attributed =
                    RecipeExecutorTestData.manual(
                            FOREIGN_PARENT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 20, 20));
            ProvenanceCodec.writeMetadata(
                    attributed.getMetadata(),
                    new ProvenanceCodec.ObjectProvenance(
                            ProvenanceCodec.SCHEMA_VERSION,
                            new ExecutionScopeCodec.OutputKey(
                                    new InferenceIdentityCodec.ModelStepIdentity(
                                            new ModuleRef("another", "module"), "parent"),
                                    new ExecutionScopeCodec.FieldScope(
                                            new ExecutionScopeCodec.Roi(
                                                    RecipeExecutorTestData.ROI_A))),
                            "50000000-0000-4000-8000-000000000009"));
            foreign.getHierarchy().getRootObject().addChildObject(attributed);
            ImageExecutionPlan foreignPlan = RecipeExecutorTestData.plan(foreign, module, action);
            assertTrue(plan(foreignPlan, foreign).sourcePlan().isEmpty());
        }
    }

    @Test
    void exactChildDoesNotInheritBroadUpstreamDiscovery() throws Exception {
        ModuleSnapshot module = withChildPadding(RecipeExecutorTestData.scriptedModule(false), 16);
        try (ImageData<?> image =
                RecipeExecutorTestData.imageData(
                        "source-exact-child", RecipeExecutorTestData.ROI_A)) {
            PathObject parent =
                    RecipeExecutorTestData.manual(
                            RecipeExecutorTestData.MANUAL_PARENT_ID,
                            "Eligible",
                            RecipeExecutorTestData.square(10, 10, 20, 20));
            image.getHierarchy().getRootObject().addChildObject(parent);
            ExecutionScopeCodec.Roi region =
                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A);
            ActionSelectionCodec.SelectedModels work =
                    new ActionSelectionCodec.SelectedModels(
                            new ActionSelectionCodec.RoiRegions(Set.of(region.roiId())),
                            List.of(
                                    new ActionSelectionCodec.StepTargets(
                                            "parent",
                                            new ActionSelectionCodec.TargetSelection(
                                                    List.of(
                                                            new ActionSelectionCodec.RoiTarget(
                                                                    region.roiId())))),
                                    new ActionSelectionCodec.StepTargets(
                                            "child",
                                            new ActionSelectionCodec.TargetSelection(
                                                    List.of(
                                                            new ActionSelectionCodec.ParentTarget(
                                                                    region,
                                                                    RecipeExecutorTestData
                                                                            .MANUAL_PARENT_ID))))));
            ImageExecutionPlan imagePlan =
                    RecipeExecutorTestData.plan(
                            image, module, new ActionSelectionCodec.Fill(work, true));

            SourceInputPlan.TargetSourcePlan source =
                    plan(imagePlan, image).sourcePlan().orElseThrow();

            assertEquals(
                    new SourceIdentityCodec.IntRect(0, 0, 100, 100),
                    source.sourceEnvelopeAtLevel());
            assertEquals(SourceInputPlan.ExecutionProtocol.FINITE, source.protocol());
        }
    }

    @Test
    void slidePlanningProjectsTheRunnerDurabilityRequirement() throws Exception {
        ModuleSnapshot module = RecipeExecutorTestData.fieldModule();
        try (ImageData<?> image = RecipeExecutorTestData.imageData("source-slide")) {
            ActionSelectionCodec.ImageAction action =
                    new ActionSelectionCodec.Fill(
                            new ActionSelectionCodec.AllModels(
                                    new ActionSelectionCodec.SlideRegion(
                                            new ActionSelectionCodec.FullSlide())),
                            true);
            ImageExecutionPlan plan = RecipeExecutorTestData.plan(image, module, action);

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RecipeSourcePlanner.plan(
                                    plan,
                                    RecipeExecutorTestData.capturedSourcePyramid(),
                                    image.getHierarchy(),
                                    Optional.empty(),
                                    RecipeExecutorTestData.sourcePlanningLimits()));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "6000,6000,false,FINITE",
        "6001,6000,false,RECOVERABLE",
        "6000,6001,false,RECOVERABLE",
        "6001,5999,false,RECOVERABLE",
        "5999,6001,false,RECOVERABLE",
        "6000,6000,true,RECOVERABLE"
    })
    void candidateFiniteRoutingUsesModelPixelsAndKeepsSlideRecoverable(
            int modelWidth,
            int modelHeight,
            boolean slide,
            SourceInputPlan.ExecutionProtocol expected)
            throws Exception {
        // Quarter-pixel coordinates are exact in both native ROI and JTS precision models.
        // Only source metadata and hierarchy geometry are created; there is no pixel array.
        double modelMpp = 0.25;
        ModuleSnapshot module =
                FiniteCapacityTestData.withCandidateLimits(
                        RecipeExecutorTestData.forceRecoverableProtocol(
                                RecipeExecutorTestData.durableModule(
                                        RecipeExecutorTestData.fieldModule())),
                        modelMpp);
        var hierarchy = new PathObjectHierarchy();
        var outline =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(
                                0,
                                0,
                                modelWidth * modelMpp,
                                modelHeight * modelMpp,
                                ImagePlane.getDefaultPlane()),
                        RoiManager.HISTOFORGE_ROI_CLASS);
        outline.setID(UUID.fromString(RecipeExecutorTestData.ROI_A));
        outline.setLocked(true);
        outline.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        hierarchy.addObject(outline);
        ActionSelectionCodec.ImageAction action =
                slide
                        ? new ActionSelectionCodec.Fill(
                                new ActionSelectionCodec.AllModels(
                                        new ActionSelectionCodec.SlideRegion(
                                                new ActionSelectionCodec.AnnotationUnion(
                                                        Set.of(RecipeExecutorTestData.ROI_A)))),
                                true)
                        : RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A));
        var imagePlan =
                ImageActionPlanner.plan(
                        RecipeExecutorTestData.ACTION_ID,
                        RecipeExecutorTestData.clientBuild(1_000_000),
                        RecipeExecutorTestData.IMAGE_ID,
                        module,
                        action,
                        hierarchy,
                        2000,
                        2000);
        var pixelSource =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "recipe.fixture.reader",
                        "d".repeat(64),
                        Optional.empty(),
                        2000,
                        2000,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(1, 1));
        var transform = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        var pyramid =
                new CapturedSourcePyramid(
                        new RasterInputCodec.ActionSourceRevision(
                                new RasterInputCodec.ImageRevisionToken(
                                        RecipeExecutorTestData.IMAGE_ID,
                                        SourceIdentityCodec.imageSourceIdentityDigest(pixelSource),
                                        "e".repeat(64)),
                                pixelSource),
                        0,
                        transform,
                        List.of(
                                new SourceLevel(
                                        0,
                                        new SourceViewGeometry.Dimensions(2000, 2000),
                                        transform)));
        var source =
                RecipeSourcePlanner.plan(
                                imagePlan,
                                pyramid,
                                hierarchy,
                                Optional.empty(),
                                RecipeExecutorTestData.sourcePlanningLimits())
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        var raster = source.fieldInputs().getFirst().frame().transform().raster();

        assertEquals(expected, source.protocol());
        assertEquals(modelWidth, raster.width());
        assertEquals(modelHeight, raster.height());
        assertEquals(modelMpp, raster.targetMpp());
        assertEquals(0, source.sourceLevel().resolutionLevel());
        assertTrue(source.sourceEnvelopeAtLevel().width() < modelWidth);
        assertTrue(source.sourceEnvelopeAtLevel().height() < modelHeight);
    }

    private static SourceInputPlan.TargetPlanningResult plan(
            ImageExecutionPlan plan, ImageData<?> image) {
        return RecipeSourcePlanner.plan(
                        plan,
                        RecipeExecutorTestData.capturedSourcePyramid(),
                        image.getHierarchy(),
                        Optional.empty(),
                        RecipeExecutorTestData.sourcePlanningLimits())
                .targets()
                .getFirst();
    }

    private static ModuleSnapshot childOnly(ModuleSnapshot complete) {
        return new ModuleSnapshot(
                complete.deploymentId(),
                complete.module(),
                complete.exactRecipe(),
                complete.scriptSetDigest(),
                Map.of("child", complete.activatedModels().get("child")),
                complete.scripts(),
                complete.metrics());
    }

    private static ActionSelectionCodec.ImageAction selectedChildRedoBroad() {
        ActionSelectionCodec.RoiRegions regions =
                new ActionSelectionCodec.RoiRegions(Set.of(RecipeExecutorTestData.ROI_A));
        ActionSelectionCodec.TargetSelection targets =
                new ActionSelectionCodec.TargetSelection(
                        List.of(new ActionSelectionCodec.RoiTarget(RecipeExecutorTestData.ROI_A)));
        return new ActionSelectionCodec.Redo(
                new ActionSelectionCodec.SelectedModels(
                        regions, List.of(new ActionSelectionCodec.StepTargets("child", targets))),
                true);
    }

    private static ModuleSnapshot withChildPadding(ModuleSnapshot module, int paddingModelPx) {
        ModelSnapshot child = module.activatedModels().get("child");
        ModelInputSnapshot input = child.input();
        ModelSnapshot padded =
                new ModelSnapshot(
                        child.architecture(),
                        child.gpu(),
                        child.modelConfigDigest(),
                        new ModelInputSnapshot(
                                input.type(),
                                input.targetMpp(),
                                input.fieldProfile(),
                                Optional.of(new CropFraming(paddingModelPx))),
                        child.output(),
                        child.capabilities(),
                        child.largeRegionProfile(),
                        child.artifactDigests());
        Map<String, ModelSnapshot> models = new LinkedHashMap<>(module.activatedModels());
        models.put("child", padded);
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
