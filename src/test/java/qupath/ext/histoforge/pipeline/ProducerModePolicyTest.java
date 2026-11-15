package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Verifies explicit ROI/slide conversion policy without persisting another mode ledger. */
class ProducerModePolicyTest {

    private static final String OBJECT_A = "88888888-8888-4888-8888-888888888888";
    private static final String OBJECT_B = "99999999-9999-4999-8999-999999999999";
    private static final String PARENT_B = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Test
    void fillAllowsAbsentOrSameModeAndRejectsOppositeOrConflictingMode() {
        ImageExecutionPlan roiPlan = plan(false, roiAction(false));
        InferenceIdentityCodec.ModelStepIdentity producer = PlanningTestData.producer("germ_cell");

        ProducerModePolicy.Ready absent =
                assertInstanceOf(
                        ProducerModePolicy.Ready.class,
                        ProducerModePolicy.evaluate(
                                roiPlan, OutputIndex.fromHierarchy(new PathObjectHierarchy())));
        assertEquals(ProducerModePolicy.RequestedMode.ROI, absent.requestedMode());
        assertEquals(Map.of(), absent.producerWideReplacements());

        ExecutionScopeCodec.OutputKey roiOutput =
                output(
                        producer,
                        new ExecutionScopeCodec.ParentScope(
                                new ExecutionScopeCodec.Roi(PlanningTestData.ROI_A),
                                PlanningTestData.PARENT));
        ProducerModePolicy.Ready same =
                assertInstanceOf(
                        ProducerModePolicy.Ready.class,
                        ProducerModePolicy.evaluate(
                                roiPlan, index(attributed(OBJECT_A, roiOutput))));
        assertEquals(Map.of(), same.producerWideReplacements());

        ExecutionScopeCodec.OutputKey slideOutput =
                output(
                        producer,
                        new ExecutionScopeCodec.ParentScope(
                                new ExecutionScopeCodec.Slide(), PlanningTestData.PARENT));
        ProducerModePolicy.CrossModeRequiresRedo opposite =
                assertInstanceOf(
                        ProducerModePolicy.CrossModeRequiresRedo.class,
                        ProducerModePolicy.evaluate(
                                roiPlan, index(attributed(OBJECT_A, slideOutput))));
        assertEquals(Set.of(producer), opposite.conflictingProducers());

        ProducerModePolicy.CrossModeRequiresRedo conflicting =
                assertInstanceOf(
                        ProducerModePolicy.CrossModeRequiresRedo.class,
                        ProducerModePolicy.evaluate(
                                roiPlan,
                                index(
                                        attributed(OBJECT_A, roiOutput),
                                        attributed(OBJECT_B, slideOutput))));
        assertEquals(Set.of(producer), conflicting.conflictingProducers());
    }

    @Test
    void redoReturnsEveryCurrentOutputForProducerWideConversion() {
        ImageExecutionPlan roiRedo = plan(false, roiAction(true));
        InferenceIdentityCodec.ModelStepIdentity producer = PlanningTestData.producer("germ_cell");
        ExecutionScopeCodec.OutputKey first =
                output(
                        producer,
                        new ExecutionScopeCodec.ParentScope(
                                new ExecutionScopeCodec.Slide(), PlanningTestData.PARENT));
        ExecutionScopeCodec.OutputKey second =
                output(
                        producer,
                        new ExecutionScopeCodec.ParentScope(
                                new ExecutionScopeCodec.Slide(), PARENT_B));

        ProducerModePolicy.Ready decision =
                assertInstanceOf(
                        ProducerModePolicy.Ready.class,
                        ProducerModePolicy.evaluate(
                                roiRedo,
                                index(attributed(OBJECT_A, first), attributed(OBJECT_B, second))));

        assertEquals(ProducerModePolicy.RequestedMode.ROI, decision.requestedMode());
        assertEquals(Map.of(producer, Set.of(first, second)), decision.producerWideReplacements());
        assertThrows(
                UnsupportedOperationException.class,
                () -> decision.producerWideReplacements().put(producer, Set.of()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> decision.producerWideReplacements().get(producer).add(first));
    }

    @Test
    void slideModeUsesTheSameDerivedPolicyAndMetricsHaveNoProducerMode() {
        ImageExecutionPlan slideFill = plan(true, slideAction(false));
        InferenceIdentityCodec.ModelStepIdentity producer = PlanningTestData.producer("germ_cell");
        ExecutionScopeCodec.OutputKey slideOutput =
                output(
                        producer,
                        new ExecutionScopeCodec.ParentScope(
                                new ExecutionScopeCodec.Slide(), PlanningTestData.PARENT));
        ProducerModePolicy.Ready same =
                assertInstanceOf(
                        ProducerModePolicy.Ready.class,
                        ProducerModePolicy.evaluate(
                                slideFill, index(attributed(OBJECT_A, slideOutput))));
        assertEquals(ProducerModePolicy.RequestedMode.SLIDE, same.requestedMode());

        ModuleSnapshot metricsModule = PlanningTestData.module(Set.of(), true);
        ImageExecutionPlan metrics =
                ImageActionPlanner.plan(
                        PlanningTestData.ACTION,
                        PlanningTestData.clientBuild(),
                        "image-1",
                        metricsModule,
                        new ActionSelectionCodec.Metrics(
                                new ActionSelectionCodec.RoiRegions(
                                        Set.of(PlanningTestData.ROI_A))),
                        PlanningTestData.hierarchyWithRois(),
                        200,
                        100);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProducerModePolicy.evaluate(
                                metrics, OutputIndex.fromHierarchy(new PathObjectHierarchy())));
    }

    private static ImageExecutionPlan plan(boolean slide, ActionSelectionCodec.ImageAction action) {
        return ImageActionPlanner.plan(
                PlanningTestData.ACTION,
                PlanningTestData.clientBuild(),
                "image-1",
                PlanningTestData.module(Set.of("germ_cell"), false),
                action,
                slide ? new PathObjectHierarchy() : PlanningTestData.hierarchyWithRois(),
                200,
                100);
    }

    private static ActionSelectionCodec.ImageAction roiAction(boolean redo) {
        ActionSelectionCodec.SelectedModels work =
                new ActionSelectionCodec.SelectedModels(
                        new ActionSelectionCodec.RoiRegions(Set.of(PlanningTestData.ROI_A)),
                        List.of(
                                new ActionSelectionCodec.StepTargets(
                                        "germ_cell",
                                        new ActionSelectionCodec.TargetSelection(
                                                List.of(
                                                        new ActionSelectionCodec.RoiTarget(
                                                                PlanningTestData.ROI_A))))));
        return redo
                ? new ActionSelectionCodec.Redo(work, true)
                : new ActionSelectionCodec.Fill(work, true);
    }

    private static ActionSelectionCodec.ImageAction slideAction(boolean redo) {
        ActionSelectionCodec.SelectedModels work =
                new ActionSelectionCodec.SelectedModels(
                        new ActionSelectionCodec.SlideRegion(new ActionSelectionCodec.FullSlide()),
                        List.of(
                                new ActionSelectionCodec.StepTargets(
                                        "germ_cell",
                                        new ActionSelectionCodec.TargetSelection(
                                                List.of(new ActionSelectionCodec.SlideTarget())))));
        return redo
                ? new ActionSelectionCodec.Redo(work, true)
                : new ActionSelectionCodec.Fill(work, true);
    }

    private static OutputIndex index(PathObject... objects) {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(List.of(objects));
        return OutputIndex.fromHierarchy(hierarchy);
    }

    private static PathObject attributed(String objectId, ExecutionScopeCodec.OutputKey output) {
        return PlanningTestData.attributed(objectId, output.producer().stepId(), output.scope());
    }

    private static ExecutionScopeCodec.OutputKey output(
            InferenceIdentityCodec.ModelStepIdentity producer,
            ExecutionScopeCodec.ExecutionScope scope) {
        return new ExecutionScopeCodec.OutputKey(producer, scope);
    }
}
