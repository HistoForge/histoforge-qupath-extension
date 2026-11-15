package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.large.LargeActionHandleStore.ResumableAction;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.lib.images.ImageData;

class PreparedRecipeResultTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void emptyResultUsesItsOriginalRevisionEvenWhenNoModelInputsWereAuthorized() {
        Fixture fixture = fixture();
        var original = fixture.pyramid().sourceRevision();
        var currentRevision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                original.imageDataRevision().imageId(),
                                original.imageDataRevision().imageSourceDigest(),
                                "8".repeat(64)),
                        original.pixelSource());
        var current =
                new CapturedSourcePyramid(
                        currentRevision,
                        fixture.pyramid().seriesIndex(),
                        fixture.pyramid().levelZeroPixelsToSlide(),
                        fixture.pyramid().levels());
        assertTrue(fixture.action().authorizedInputs().isEmpty());

        var delta = fixture.result().stageModelDelta(fixture.working(current), fixture.committer());

        assertTrue(delta.changes().addObjects().isEmpty());
        assertTrue(delta.changes().removeObjectIds().isEmpty());
        assertEquals(fixture.outcomes(), fixture.result().outcomes());
    }

    @Test
    void completionIsSingleUseButItsSmallOutcomeListRemainsAvailable() {
        Fixture fixture = fixture();
        fixture.result().requireAction(fixture.action());
        fixture.result().stageModelDelta(fixture.working(fixture.pyramid()), fixture.committer());

        assertThrows(
                IllegalStateException.class,
                () ->
                        fixture.result()
                                .stageModelDelta(
                                        fixture.working(fixture.pyramid()), fixture.committer()));
        assertEquals(fixture.outcomes(), fixture.result().outcomes());
    }

    @Test
    void preparedResultCannotBeUsedForAnotherPersistedAction() {
        Fixture fixture = fixture();
        assertThrows(
                IllegalStateException.class,
                () -> fixture.result().requireAction(mock(ResumableAction.class)));
    }

    @Test
    void recoveryPreparationUsesItsVerifiedPlanningPyramidNotTheCurrentSourceIdentity() {
        Fixture fixture = fixture();
        var original = fixture.pyramid();
        var source = original.sourceRevision().pixelSource();
        var currentSource =
                new SourceIdentityCodec.ImageSourceIdentity(
                        source.sourceAdapterId(),
                        source.credentialFreeDescriptorDigest(),
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        1234, 5678, Optional.of("reconnected"))),
                        source.width(),
                        source.height(),
                        source.zSlices(),
                        source.timepoints(),
                        source.calibration());
        var current =
                new CapturedSourcePyramid(
                        new RasterInputCodec.ActionSourceRevision(
                                new RasterInputCodec.ImageRevisionToken(
                                        original.sourceRevision().imageDataRevision().imageId(),
                                        SourceIdentityCodec.imageSourceIdentityDigest(
                                                currentSource),
                                        "8".repeat(64)),
                                currentSource),
                        original.seriesIndex(),
                        original.levelZeroPixelsToSlide(),
                        original.levels());
        var working = fixture.working(current);
        assertTrue(fixture.action().authorizedInputs().isEmpty());
        assertTrue(
                fixture.result()
                        .stageModelDelta(working, fixture.committer(), original)
                        .changes()
                        .addObjects()
                        .isEmpty());
        assertEquals(current, working.sourcePyramid());
    }

    enum ReaderChange {
        PIXELS,
        SERIES,
        LEVEL_ZERO_MAPPING,
        SELECTED_LEVEL_MAPPING,
        SELECTED_LEVEL_DIMENSIONS
    }

    @ParameterizedTest
    @EnumSource(ReaderChange.class)
    void changedPixelReadingPlanConflictsEvenWhenNoOutputGenerationExists(ReaderChange change) {
        Fixture fixture = fixture();
        var original = fixture.pyramid();
        var revision = original.sourceRevision();
        int series = original.seriesIndex();
        var levelZero = original.levelZeroPixelsToSlide();
        var selected = original.levels().get(1);
        switch (change) {
            case PIXELS -> {
                var pixels = revision.pixelSource();
                var changedPixels =
                        new SourceIdentityCodec.ImageSourceIdentity(
                                pixels.sourceAdapterId(),
                                "9".repeat(64),
                                pixels.contentVersion(),
                                pixels.width(),
                                pixels.height(),
                                pixels.zSlices(),
                                pixels.timepoints(),
                                pixels.calibration());
                revision =
                        new RasterInputCodec.ActionSourceRevision(
                                new RasterInputCodec.ImageRevisionToken(
                                        revision.imageDataRevision().imageId(),
                                        SourceIdentityCodec.imageSourceIdentityDigest(
                                                changedPixels),
                                        revision.imageDataRevision().imageDataContentDigest()),
                                changedPixels);
            }
            case SERIES -> series++;
            case LEVEL_ZERO_MAPPING ->
                    levelZero = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0.25, 0);
            case SELECTED_LEVEL_MAPPING ->
                    selected =
                            new SourceLevel(
                                    selected.resolutionLevel(),
                                    selected.dimensions(),
                                    new SourceIdentityCodec.ScaleTranslate2D(2, 2, 0.25, 0));
            case SELECTED_LEVEL_DIMENSIONS ->
                    selected =
                            new SourceLevel(
                                    selected.resolutionLevel(),
                                    new SourceViewGeometry.Dimensions(
                                            selected.dimensions().width() - 1,
                                            selected.dimensions().height()),
                                    selected.pixelsToLevelZero());
        }
        var changed =
                new CapturedSourcePyramid(
                        revision,
                        series,
                        levelZero,
                        List.of(original.levels().getFirst(), selected));

        assertThrows(
                LargeActionTransactionValidator.Conflict.class,
                () ->
                        fixture.result()
                                .stageModelDelta(fixture.working(changed), fixture.committer()));
        // A conflict must precede candidate consumption or any working-hierarchy mutation.
        assertTrue(
                fixture.result()
                        .stageModelDelta(fixture.working(original), fixture.committer())
                        .changes()
                        .addObjects()
                        .isEmpty());
    }

    @Test
    void addingAnUnusedCoarserLevelDoesNotChangeTheCapturedInputPlan() {
        Fixture fixture = fixture();
        var original = fixture.pyramid();
        var current =
                new CapturedSourcePyramid(
                        original.sourceRevision(),
                        original.seriesIndex(),
                        original.levelZeroPixelsToSlide(),
                        List.of(
                                original.levels().getFirst(),
                                original.levels().get(1),
                                new SourceLevel(
                                        2,
                                        new SourceViewGeometry.Dimensions(16, 8),
                                        new SourceIdentityCodec.ScaleTranslate2D(16, 16, 0, 0))));

        assertTrue(
                fixture.result()
                        .stageModelDelta(fixture.working(current), fixture.committer())
                        .changes()
                        .addObjects()
                        .isEmpty());
    }

    private static Fixture fixture() {
        ImageData<BufferedImage> image =
                RecipeExecutorTestData.imageData("prepared-recipe", RecipeExecutorTestData.ROI_A);
        var module = RecipeExecutorTestData.durableModule(RecipeExecutorTestData.fieldModule());
        ImageExecutionPlan plan =
                RecipeExecutorTestData.plan(
                        image,
                        module,
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)));
        var original = RecipeExecutorTestData.sourceRevision();
        var pixels = original.pixelSource();
        var calibrated =
                new SourceIdentityCodec.ImageSourceIdentity(
                        pixels.sourceAdapterId(),
                        pixels.credentialFreeDescriptorDigest(),
                        pixels.contentVersion(),
                        pixels.width(),
                        pixels.height(),
                        pixels.zSlices(),
                        pixels.timepoints(),
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                original.imageDataRevision().imageId(),
                                SourceIdentityCodec.imageSourceIdentityDigest(calibrated),
                                original.imageDataRevision().imageDataContentDigest()),
                        calibrated);
        var pyramid =
                new CapturedSourcePyramid(
                        revision,
                        0,
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0),
                        List.of(
                                new SourceLevel(
                                        0,
                                        new SourceViewGeometry.Dimensions(
                                                pixels.width(), pixels.height()),
                                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0)),
                                new SourceLevel(
                                        1,
                                        new SourceViewGeometry.Dimensions(
                                                pixels.width() / 2, pixels.height() / 2),
                                        new SourceIdentityCodec.ScaleTranslate2D(2, 2, 0, 0))));
        SourceInputPlan source =
                RecipeSourcePlanner.plan(
                        plan,
                        pyramid,
                        image.getHierarchy(),
                        Optional.empty(),
                        RecipeExecutorTestData.sourcePlanningLimits());
        var targetSource = source.targets().getFirst().sourcePlan().orElseThrow();
        assertEquals(1, targetSource.sourceLevel().resolutionLevel());
        LargeActionHandle handle = mock(LargeActionHandle.class);
        when(handle.actionId()).thenReturn(plan.actionId());
        when(handle.clientBuild()).thenReturn(plan.clientBuild());
        when(handle.imageId()).thenReturn(plan.imageId());
        when(handle.module()).thenReturn(plan.module());
        when(handle.action()).thenReturn(plan.action());
        when(handle.target()).thenReturn(plan.targetRegions().getFirst());
        when(handle.selectionRegions()).thenReturn(plan.selectionRegions());
        when(handle.sourcePlan()).thenReturn(targetSource.artifact());
        ResumableAction action = mock(ResumableAction.class);
        when(action.handle()).thenReturn(handle);
        var outcome =
                new RunResults.ScopeWorkResult(
                        new RunResults.ExecutionOutput(
                                targetSource.fieldInputs().getFirst().output()),
                        RunResults.ScopeOutcomeType.SKIPPED_PRESENT,
                        false,
                        Optional.empty(),
                        Optional.empty());
        var outcomes = List.of(outcome);
        var staged =
                new RecipeStaging.Target(
                        plan.targetRegions(), Set.of(), List.of(), List.of(), outcomes);
        return new Fixture(
                image,
                plan,
                pyramid,
                action,
                new PreparedRecipeResult(action, plan, source, staged, CLOCK),
                outcomes);
    }

    private record Fixture(
            ImageData<BufferedImage> image,
            ImageExecutionPlan plan,
            CapturedSourcePyramid pyramid,
            ResumableAction action,
            PreparedRecipeResult result,
            List<RunResults.ScopeWorkResult> outcomes) {

        ImageLifecycle.WorkingImage working(CapturedSourcePyramid current) {
            var working = mock(ImageLifecycle.WorkingImage.class);
            when(working.imageData()).thenReturn(image);
            when(working.sourcePyramid()).thenReturn(current);
            return working;
        }

        ModelOutputCommitter committer() {
            return new ModelOutputCommitter(image, plan.clientBuild().limits());
        }
    }
}
