package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathROIObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class ImageActionSubsetPlannerTest {

    @Test
    void capturedSubsetRetainsExactSourcePlanAndRejectsLaterTargetGeometryChange()
            throws Exception {
        try (ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData(
                        "captured-subset",
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            ActionSelectionCodec.ImageAction action =
                    RecipeExecutorTestData.allModels(
                            false,
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B));
            ImageExecutionPlan full =
                    ImageActionPlanner.plan(
                            RecipeExecutorTestData.ACTION_ID,
                            RecipeExecutorTestData.clientBuild(1_000_000),
                            RecipeExecutorTestData.IMAGE_ID,
                            RecipeExecutorTestData.fieldModule(),
                            action,
                            image.getHierarchy(),
                            image.getServer().getWidth(),
                            image.getServer().getHeight());
            SourceInputPlan sources =
                    RecipeSourcePlanner.plan(
                            full,
                            RecipeExecutorTestData.capturedSourcePyramid(),
                            image.getHierarchy(),
                            Optional.empty(),
                            RecipeExecutorTestData.sourcePlanningLimits());
            ExecutionScopeCodec.TargetRegion target =
                    full.targetRegions().stream()
                            .filter(
                                    candidate ->
                                            candidate
                                                    .key()
                                                    .equals(
                                                            new ExecutionScopeCodec.Roi(
                                                                    RecipeExecutorTestData.ROI_A)))
                            .findFirst()
                            .orElseThrow();
            ImageActionSubsetPlanner.Subset subset =
                    ImageActionSubsetPlanner.subset(
                            full,
                            sources,
                            List.of(target),
                            image.getHierarchy(),
                            image.getServer().getWidth(),
                            image.getServer().getHeight());

            assertEquals(
                    sources.targets().stream()
                            .filter(candidate -> candidate.target().equals(target))
                            .findFirst()
                            .orElseThrow(),
                    subset.sources().targets().getFirst());
            assertDoesNotThrow(
                    () ->
                            ImageActionSubsetPlanner.requireCurrent(
                                    subset,
                                    image.getHierarchy(),
                                    image.getServer().getWidth(),
                                    image.getServer().getHeight()));

            PathROIObject roi =
                    (PathROIObject)
                            image.getHierarchy().getFlattenedObjectList(null).stream()
                                    .filter(
                                            object ->
                                                    object.getID()
                                                            .toString()
                                                            .equals(RecipeExecutorTestData.ROI_A))
                                    .findFirst()
                                    .orElseThrow();
            roi.setROI(ROIs.createRectangleROI(0, 0, 90, 100, ImagePlane.getDefaultPlane()));

            assertThrows(
                    ImageActionSubsetPlanner.CapturedPlanConflict.class,
                    () ->
                            ImageActionSubsetPlanner.requireCurrent(
                                    subset,
                                    image.getHierarchy(),
                                    image.getServer().getWidth(),
                                    image.getServer().getHeight()));
        }
    }
}
