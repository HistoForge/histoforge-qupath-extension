package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;

class AnnotationSubsetCaptureTest {
    @Test
    void deferredTargetRejectsUnlockButAllowsCompletedSiblingOutput() throws Exception {
        try (var image =
                RecipeExecutorTestData.imageData(
                        "annotation-subset",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            var hierarchy = image.getHierarchy();
            hierarchy
                    .getAnnotationObjects()
                    .forEach(
                            boundary -> {
                                boundary.getMetadata().remove(HistoforgeKeys.ROI_TYPE);
                                boundary.setPathClass(PathClass.fromString("Tissue"));
                                boundary.setLocked(true);
                            });
            var plan =
                    ImageActionPlanner.plan(
                            RecipeExecutorTestData.ACTION_ID,
                            RecipeExecutorTestData.clientBuild(1_000_000),
                            RecipeExecutorTestData.IMAGE_ID,
                            RecipeExecutorTestData.fieldModule(),
                            RecipeExecutorTestData.allModels(
                                    false,
                                    Set.of(
                                            RecipeExecutorTestData.ROI_A,
                                            RecipeExecutorTestData.ROI_B)),
                            hierarchy,
                            256,
                            128);
            var source =
                    RecipeSourcePlanner.plan(
                            plan,
                            RecipeExecutorTestData.capturedSourcePyramid(),
                            hierarchy,
                            Optional.empty(),
                            RecipeExecutorTestData.sourcePlanningLimits());
            var target =
                    plan.targetRegions().stream()
                            .filter(
                                    region ->
                                            region.key()
                                                    .equals(
                                                            new ExecutionScopeCodec.Roi(
                                                                    RecipeExecutorTestData.ROI_B)))
                            .findFirst()
                            .orElseThrow();
            var subset =
                    ImageActionSubsetPlanner.subset(
                            plan, source, List.of(target), hierarchy, 256, 128);
            var registry = RoiRegistry.fromHierarchy(hierarchy);
            var sibling = registry.findRoi(RecipeExecutorTestData.ROI_A).orElseThrow();
            sibling.addChildObject(
                    PathObjects.createDetectionObject(ROIs.createRectangleROI(5, 5, 10, 10, null)));
            assertDoesNotThrow(
                    () -> ImageActionSubsetPlanner.requireCurrent(subset, hierarchy, 256, 128));
            hierarchy.removeObject(sibling, true);
            assertDoesNotThrow(
                    () -> ImageActionSubsetPlanner.requireCurrent(subset, hierarchy, 256, 128));
            var selected = registry.findRoi(RecipeExecutorTestData.ROI_B).orElseThrow();
            selected.setLocked(false);
            assertThrows(
                    ImageActionSubsetPlanner.CapturedPlanConflict.class,
                    () -> ImageActionSubsetPlanner.requireCurrent(subset, hierarchy, 256, 128));
            selected.setLocked(true);
            selected.setName("edited after capture");
            assertThrows(
                    ImageActionSubsetPlanner.CapturedPlanConflict.class,
                    () -> ImageActionSubsetPlanner.requireCurrent(subset, hierarchy, 256, 128));
        }
    }
}
