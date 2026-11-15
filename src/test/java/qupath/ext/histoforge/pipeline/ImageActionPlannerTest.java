package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;

/** Verifies static action normalization without resolving output presence or dynamic parents. */
class ImageActionPlannerTest {

    private static final int IMAGE_WIDTH = 200;
    private static final int IMAGE_HEIGHT = 100;
    private static final String ANNOTATION_A = "66666666-6666-4666-8666-666666666666";
    private static final String ANNOTATION_B = "77777777-7777-4777-8777-777777777777";

    @Test
    void allModelsCaptureRoisAndMetricsInDeterministicRecipeOrder() {
        ModuleSnapshot module =
                PlanningTestData.module(Set.of("tubule_semantic", "lumen", "germ_cell"), true);
        ActionSelectionCodec.RoiRegions regions =
                new ActionSelectionCodec.RoiRegions(
                        Set.of(PlanningTestData.ROI_B, PlanningTestData.ROI_A));
        ActionSelectionCodec.Fill action =
                new ActionSelectionCodec.Fill(new ActionSelectionCodec.AllModels(regions), true);

        ImageExecutionPlan plan = plan(module, action, PlanningTestData.hierarchyWithRois());

        assertEquals(
                List.of("tubule_semantic", "lumen", "germ_cell"),
                plan.modelsInRecipeOrder().stream()
                        .map(ImageExecutionPlan.PlannedModelStep::stepId)
                        .toList());
        ActionSelectionCodec.TargetSelection expectedTargets =
                targets(
                        new ActionSelectionCodec.RoiTarget(PlanningTestData.ROI_A),
                        new ActionSelectionCodec.RoiTarget(PlanningTestData.ROI_B));
        plan.modelsInRecipeOrder().forEach(model -> assertEquals(expectedTargets, model.targets()));
        assertEquals(
                List.of(
                        new ExecutionScopeCodec.Roi(PlanningTestData.ROI_A),
                        new ExecutionScopeCodec.Roi(PlanningTestData.ROI_B)),
                plan.targetRegions().stream().map(ExecutionScopeCodec.TargetRegion::key).toList());
        assertEquals(Optional.of(regions), plan.metricSelection());
        assertEquals(PlanningTestData.ACTION, plan.actionId());
        assertEquals("image-1", plan.imageId());
        assertTrue(plan.action() instanceof ActionSelectionCodec.Fill);
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.modelsInRecipeOrder().add(plan.modelsInRecipeOrder().getFirst()));
    }

    @Test
    void selectedFillKeepsExactParentTargetAndDoesNotInventPrerequisiteWork() {
        ModuleSnapshot module = PlanningTestData.module(Set.of("germ_cell"), false);
        ActionSelectionCodec.SelectedModels work =
                selected(
                        Set.of(PlanningTestData.ROI_A, PlanningTestData.ROI_B),
                        step(
                                "germ_cell",
                                new ActionSelectionCodec.ParentTarget(
                                        new ExecutionScopeCodec.Roi(PlanningTestData.ROI_A),
                                        PlanningTestData.PARENT)));

        ImageExecutionPlan plan =
                plan(
                        module,
                        new ActionSelectionCodec.Fill(work, false),
                        PlanningTestData.hierarchyWithRois());

        assertEquals(List.of("germ_cell"), stepIds(plan));
        assertEquals(
                work.steps().getFirst().targets(), plan.modelsInRecipeOrder().getFirst().targets());
        assertEquals(
                List.of(new ExecutionScopeCodec.Roi(PlanningTestData.ROI_A)),
                plan.targetRegions().stream().map(ExecutionScopeCodec.TargetRegion::key).toList());
        assertEquals(Optional.empty(), plan.metricSelection());
    }

    @Test
    void selectedRedoPropagatesCoarseRegionsThroughScriptClosure() {
        ModuleSnapshot module =
                PlanningTestData.module(Set.of("tubule_semantic", "germ_cell"), true);
        ActionSelectionCodec.SelectedModels work =
                selected(
                        Set.of(PlanningTestData.ROI_A),
                        step(
                                "tubule_semantic",
                                new ActionSelectionCodec.RoiTarget(PlanningTestData.ROI_A)),
                        step(
                                "germ_cell",
                                new ActionSelectionCodec.ParentTarget(
                                        new ExecutionScopeCodec.Roi(PlanningTestData.ROI_A),
                                        PlanningTestData.PARENT)));

        ImageExecutionPlan plan =
                plan(
                        module,
                        new ActionSelectionCodec.Redo(work, true),
                        PlanningTestData.hierarchyWithRois());

        assertEquals(List.of("tubule_semantic", "germ_cell"), stepIds(plan));
        ActionSelectionCodec.TargetSelection broad =
                targets(new ActionSelectionCodec.RoiTarget(PlanningTestData.ROI_A));
        assertEquals(broad, plan.modelsInRecipeOrder().get(0).targets());
        assertEquals(
                broad,
                plan.modelsInRecipeOrder().get(1).targets(),
                "the propagated ROI subsumes the redundant exact-parent selector");
        assertEquals(
                Optional.of(new ActionSelectionCodec.RoiRegions(Set.of(PlanningTestData.ROI_A))),
                plan.metricSelection());

        ModuleSnapshot scriptDependent =
                PlanningTestData.module(Set.of("lumen", "germ_cell"), false);
        ActionSelectionCodec.Redo lumenRedo =
                new ActionSelectionCodec.Redo(
                        selected(
                                Set.of(PlanningTestData.ROI_B),
                                step(
                                        "lumen",
                                        new ActionSelectionCodec.RoiTarget(
                                                PlanningTestData.ROI_B))),
                        true);
        ImageExecutionPlan dependentPlan =
                plan(scriptDependent, lumenRedo, PlanningTestData.hierarchyWithRois());
        assertEquals(List.of("lumen", "germ_cell"), stepIds(dependentPlan));
        assertEquals(
                targets(new ActionSelectionCodec.RoiTarget(PlanningTestData.ROI_B)),
                dependentPlan.modelsInRecipeOrder().get(1).targets());
    }

    @Test
    void fullSlideAndAnnotationUnionCaptureOneImmutableSlideRegion() {
        ModuleSnapshot module = PlanningTestData.module(Set.of("tubule_semantic"), false);
        ActionSelectionCodec.Fill fullSlide =
                new ActionSelectionCodec.Fill(
                        new ActionSelectionCodec.SelectedModels(
                                new ActionSelectionCodec.SlideRegion(
                                        new ActionSelectionCodec.FullSlide()),
                                List.of(
                                        step(
                                                "tubule_semantic",
                                                new ActionSelectionCodec.SlideTarget()))),
                        true);

        ImageExecutionPlan fullPlan = plan(module, fullSlide, PlanningTestData.hierarchyWithRois());
        ExecutionScopeCodec.TargetRegion full = fullPlan.targetRegions().getFirst();
        assertInstanceOf(ExecutionScopeCodec.Slide.class, full.key());
        assertEquals(20_000.0, full.geometrySlide().getArea());
        assertEquals(new SourceIdentityCodec.ImagePlaneIndex(0, 0), full.plane());
        assertEquals(
                targets(new ActionSelectionCodec.SlideTarget()),
                fullPlan.modelsInRecipeOrder().getFirst().targets());

        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(
                List.of(
                        PlanningTestData.annotation(
                                ANNOTATION_A, 0, 0, 20, 20, ImagePlane.getPlane(2, 3)),
                        PlanningTestData.annotation(
                                ANNOTATION_B, 10, 0, 20, 20, ImagePlane.getPlane(2, 3))));
        ActionSelectionCodec.AnnotationUnion union =
                new ActionSelectionCodec.AnnotationUnion(Set.of(ANNOTATION_B, ANNOTATION_A));
        ActionSelectionCodec.Fill unionAction =
                new ActionSelectionCodec.Fill(
                        new ActionSelectionCodec.SelectedModels(
                                new ActionSelectionCodec.SlideRegion(union),
                                List.of(
                                        step(
                                                "tubule_semantic",
                                                new ActionSelectionCodec.SlideTarget()))),
                        false);

        ImageExecutionPlan unionPlan = plan(module, unionAction, hierarchy);
        ExecutionScopeCodec.TargetRegion captured = unionPlan.targetRegions().getFirst();
        assertEquals(600.0, captured.geometrySlide().getArea());
        assertEquals(new SourceIdentityCodec.ImagePlaneIndex(2, 3), captured.plane());
    }

    @Test
    void metricsPlansNoModelsAndUsesTheExactRequestedRegions() {
        ModuleSnapshot module = PlanningTestData.module(Set.of(), true);
        ActionSelectionCodec.RoiRegions regions =
                new ActionSelectionCodec.RoiRegions(Set.of(PlanningTestData.ROI_B));

        ImageExecutionPlan plan =
                plan(
                        module,
                        new ActionSelectionCodec.Metrics(regions),
                        PlanningTestData.hierarchyWithRois());

        assertEquals(List.of(), plan.modelsInRecipeOrder());
        assertEquals(Optional.of(regions), plan.metricSelection());
        assertEquals(
                List.of(new ExecutionScopeCodec.Roi(PlanningTestData.ROI_B)),
                plan.targetRegions().stream().map(ExecutionScopeCodec.TargetRegion::key).toList());

        ModuleSnapshot withoutMetrics = PlanningTestData.module(Set.of(), false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        plan(
                                withoutMetrics,
                                new ActionSelectionCodec.Metrics(regions),
                                PlanningTestData.hierarchyWithRois()));
    }

    @Test
    void invalidStaticSelectionsFailBeforeExecution() {
        ActionSelectionCodec.ParentTarget parentTarget =
                new ActionSelectionCodec.ParentTarget(
                        new ExecutionScopeCodec.Roi(PlanningTestData.ROI_A),
                        PlanningTestData.PARENT);
        ActionSelectionCodec.Fill fieldWithParent =
                new ActionSelectionCodec.Fill(
                        selected(
                                Set.of(PlanningTestData.ROI_A),
                                step("tubule_semantic", parentTarget)),
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        plan(
                                PlanningTestData.module(Set.of("tubule_semantic"), false),
                                fieldWithParent,
                                PlanningTestData.hierarchyWithRois()));

        ActionSelectionCodec.Fill missingRoi =
                new ActionSelectionCodec.Fill(
                        new ActionSelectionCodec.AllModels(
                                new ActionSelectionCodec.RoiRegions(
                                        Set.of("88888888-8888-4888-8888-888888888888"))),
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        plan(
                                PlanningTestData.module(Set.of("tubule_semantic"), false),
                                missingRoi,
                                PlanningTestData.hierarchyWithRois()));

        ActionSelectionCodec.Fill valid =
                new ActionSelectionCodec.Fill(
                        new ActionSelectionCodec.AllModels(
                                new ActionSelectionCodec.RoiRegions(
                                        Set.of(PlanningTestData.ROI_A))),
                        true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        plan(
                                PlanningTestData.module(Set.of("lumen"), false),
                                valid,
                                PlanningTestData.hierarchyWithRois()));
    }

    @Test
    void annotationUnionRejectsMissingNonAnnotationMixedPlaneAndOutsideGeometry() {
        ModuleSnapshot module = PlanningTestData.module(Set.of("tubule_semantic"), false);
        PathObject first =
                PlanningTestData.annotation(
                        ANNOTATION_A, 0, 0, 20, 20, ImagePlane.getDefaultPlane());
        PathObject otherPlane =
                PlanningTestData.annotation(ANNOTATION_B, 20, 0, 20, 20, ImagePlane.getPlane(1, 0));
        PathObjectHierarchy mixed = new PathObjectHierarchy();
        mixed.addObjects(List.of(first, otherPlane));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(module, unionAction(ANNOTATION_A, ANNOTATION_B), mixed));

        PathObjectHierarchy missing = new PathObjectHierarchy();
        missing.addObject(first);
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(module, unionAction(ANNOTATION_A, ANNOTATION_B), missing));

        PathObject detection =
                PlanningTestData.attributed(
                        ANNOTATION_B,
                        "tubule_semantic",
                        new ExecutionScopeCodec.FieldScope(
                                new ExecutionScopeCodec.Roi(PlanningTestData.ROI_A)));
        PathObjectHierarchy nonAnnotation = new PathObjectHierarchy();
        nonAnnotation.addObjects(List.of(first, detection));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(module, unionAction(ANNOTATION_A, ANNOTATION_B), nonAnnotation));

        PathObject outside =
                PlanningTestData.annotation(
                        ANNOTATION_A, 190, 0, 20, 20, ImagePlane.getDefaultPlane());
        PathObjectHierarchy outsideHierarchy = new PathObjectHierarchy();
        outsideHierarchy.addObject(outside);
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(module, unionAction(ANNOTATION_A), outsideHierarchy));
    }

    private static ImageExecutionPlan plan(
            ModuleSnapshot module,
            ActionSelectionCodec.ImageAction action,
            PathObjectHierarchy hierarchy) {
        return ImageActionPlanner.plan(
                PlanningTestData.ACTION,
                PlanningTestData.clientBuild(),
                "image-1",
                module,
                action,
                hierarchy,
                IMAGE_WIDTH,
                IMAGE_HEIGHT);
    }

    private static ActionSelectionCodec.SelectedModels selected(
            Set<String> roiIds, ActionSelectionCodec.StepTargets... steps) {
        return new ActionSelectionCodec.SelectedModels(
                new ActionSelectionCodec.RoiRegions(roiIds), List.of(steps));
    }

    private static ActionSelectionCodec.StepTargets step(
            String stepId, ActionSelectionCodec.TargetSelector... selectors) {
        return new ActionSelectionCodec.StepTargets(stepId, targets(selectors));
    }

    private static ActionSelectionCodec.TargetSelection targets(
            ActionSelectionCodec.TargetSelector... selectors) {
        return new ActionSelectionCodec.TargetSelection(List.of(selectors));
    }

    private static List<String> stepIds(ImageExecutionPlan plan) {
        return plan.modelsInRecipeOrder().stream()
                .map(ImageExecutionPlan.PlannedModelStep::stepId)
                .toList();
    }

    private static ActionSelectionCodec.Fill unionAction(String... annotationIds) {
        return new ActionSelectionCodec.Fill(
                new ActionSelectionCodec.SelectedModels(
                        new ActionSelectionCodec.SlideRegion(
                                new ActionSelectionCodec.AnnotationUnion(Set.of(annotationIds))),
                        List.of(step("tubule_semantic", new ActionSelectionCodec.SlideTarget()))),
                true);
    }
}
