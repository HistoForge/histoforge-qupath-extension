package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.eligible;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.AutomaticWarmupPolicy;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAugmentationProfileChoice;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingGpuChoice;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingSettings;
import qupath.ext.histoforge.contract.TrainingTaskCodec.ClosedIntegerRange;
import qupath.ext.histoforge.contract.TrainingTaskCodec.FixedFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.EligibleOriginalSource;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingRoiGeometry;

class TrainingSourcePlannerTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final String ROI_A = "11111111-1111-4111-8111-111111111111";
    private static final String ROI_B = "22222222-2222-4222-8222-222222222222";
    private static final SourcePlanningLimits NORMAL_LIMITS =
            new SourcePlanningLimits(32_000_000, 1L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024);

    @Test
    void smallRoiContextExpandsRealPixelsAndUsesTheSharedLevelRule() {
        CapturedSourcePyramid source = source(1_000, 800, 0.5, Optional.empty());
        var roi = new TrainingRoiGeometry(ROI_A, rectangle(300, 200, 400, 300));
        var task = task("field", 1.0);
        var withContext =
                capability(
                        "field",
                        new VariableFieldProfile(
                                256,
                                512,
                                384,
                                16,
                                8,
                                false,
                                0.25,
                                "mean-logits-v1",
                                "brightfield-rgb-v1"),
                        256,
                        512,
                        true);
        var withoutContext = capability("field", fixed(false), 384, 384, false);

        var expanded =
                TrainingSourcePlanner.plan(
                                source,
                                List.of(roi),
                                task,
                                withContext,
                                Optional.empty(),
                                NORMAL_LIMITS)
                        .rois()
                        .getFirst();
        var natural =
                TrainingSourcePlanner.plan(
                                source,
                                List.of(roi),
                                task,
                                withoutContext,
                                Optional.empty(),
                                NORMAL_LIMITS)
                        .rois()
                        .getFirst();

        assertEquals(1, expanded.sourceLevel().resolutionLevel());
        assertTrue(
                expanded.sourceEnvelopeAtLevel().width() > natural.sourceEnvelopeAtLevel().width());
        assertTrue(
                expanded.sourceEnvelopeAtLevel().height()
                        > natural.sourceEnvelopeAtLevel().height());
        assertInstanceOf(DerivedArtifactPlan.class, expanded.artifact());
        assertEquals(
                SourceArtifactCodec.SourceContainer.PNG,
                ((DerivedArtifactPlan) expanded.artifact()).container());
    }

    @Test
    void cropPaddingComesFromTheSelectedCapability() {
        CapturedSourcePyramid source = source(1_000, 800, 0.5, Optional.empty());
        var roi = new TrainingRoiGeometry(ROI_A, rectangle(300, 200, 400, 300));
        var crop =
                TrainingSourcePlanner.plan(
                                source,
                                List.of(roi),
                                task("crop", 1.0),
                                capability("crop", 16, fixed(false), 384, 384, false),
                                Optional.empty(),
                                NORMAL_LIMITS)
                        .rois()
                        .getFirst();
        var field =
                TrainingSourcePlanner.plan(
                                source,
                                List.of(roi),
                                task("field", 1.0),
                                capability("field", fixed(false), 384, 384, false),
                                Optional.empty(),
                                NORMAL_LIMITS)
                        .rois()
                        .getFirst();
        assertTrue(crop.sourceEnvelopeAtLevel().width() > field.sourceEnvelopeAtLevel().width());
    }

    @Test
    void fractionalRoiEnvelopeContainsItsEdgesNotOnlyTheSampledPixelCenters() {
        CapturedSourcePyramid pyramid = source(100, 80, 0.5, Optional.empty());
        var onlyLevelZero =
                new CapturedSourcePyramid(
                        pyramid.sourceRevision(),
                        pyramid.seriesIndex(),
                        pyramid.levelZeroPixelsToSlide(),
                        List.of(pyramid.levels().getFirst()));
        var planned =
                TrainingSourcePlanner.plan(
                                onlyLevelZero,
                                List.of(
                                        new TrainingRoiGeometry(
                                                ROI_A, rectangle(10.6, 10.6, 30.6, 30.6))),
                                task("field", 1.0),
                                capability("field", fixed(false), 384, 384, false),
                                Optional.empty(),
                                NORMAL_LIMITS)
                        .rois()
                        .getFirst();

        assertEquals(
                new SourceIdentityCodec.IntRect(10, 10, 21, 21), planned.sourceEnvelopeAtLevel());
        var artifact = assertInstanceOf(DerivedArtifactPlan.class, planned.artifact());
        assertEquals(planned.sourceEnvelopeAtLevel(), artifact.sourceRectAtLevel());
    }

    @Test
    void smallRoiContextEnvelopeIsClampedToThePhysicalImage() {
        CapturedSourcePyramid pyramid = source(100, 80, 0.5, Optional.empty());
        var planned =
                TrainingSourcePlanner.plan(
                                pyramid,
                                List.of(
                                        new TrainingRoiGeometry(
                                                ROI_A, rectangle(0.1, 0.1, 10.1, 10.1))),
                                task("field", 1.0),
                                capability("field", fixed(true), 384, 384, false),
                                Optional.empty(),
                                NORMAL_LIMITS)
                        .rois()
                        .getFirst();

        assertEquals(1, planned.sourceLevel().resolutionLevel());
        assertEquals(
                new SourceIdentityCodec.IntRect(0, 0, 50, 40), planned.sourceEnvelopeAtLevel());
    }

    @Test
    void fittingDerivedArtifactsRemainPreparedEvenWhenAnOriginalIsSmaller() {
        long originalBytes = 1_000;
        var source =
                source(
                        1_000,
                        800,
                        0.5,
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        originalBytes, 1_700_000_000_000L, Optional.of("key"))));
        var plan =
                TrainingSourcePlanner.plan(
                        source,
                        List.of(new TrainingRoiGeometry(ROI_A, rectangle(100, 100, 900, 700))),
                        task("field", 0.5),
                        capability("field", fixed(false), 384, 384, false),
                        Optional.of(eligible(originalBytes, source)),
                        NORMAL_LIMITS);

        assertFalse(plan.usesOriginal());
        assertInstanceOf(DerivedArtifactPlan.class, plan.rois().getFirst().artifact());
    }

    @Test
    void oneOversizeDerivedRoiSelectsOneEligibleOriginalForTheImage() {
        long originalBytes = 1_000_000;
        CapturedSourcePyramid source =
                source(
                        1_000,
                        800,
                        0.5,
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        originalBytes, 1_700_000_000_000L, Optional.of("key"))));
        var rois =
                List.of(
                        new TrainingRoiGeometry(ROI_A, rectangle(10, 10, 50, 50)),
                        new TrainingRoiGeometry(ROI_B, rectangle(100, 100, 900, 700)));
        var limits = new SourcePlanningLimits(32_000_000, 200_000, 2_000_000);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingSourcePlanner.plan(
                                source,
                                rois,
                                task("field", 0.5),
                                capability("field", fixed(false), 384, 384, false),
                                Optional.empty(),
                                limits));
        var plan =
                TrainingSourcePlanner.plan(
                        source,
                        rois,
                        task("field", 0.5),
                        capability("field", fixed(false), 384, 384, false),
                        Optional.of(eligible(originalBytes, source)),
                        limits);
        assertTrue(plan.usesOriginal());
        assertEquals(
                assertInstanceOf(OriginalArtifactPlan.class, plan.rois().get(0).artifact()),
                assertInstanceOf(OriginalArtifactPlan.class, plan.rois().get(1).artifact()));
        assertFalse(
                plan.rois()
                        .get(0)
                        .sourceEnvelopeAtLevel()
                        .equals(plan.rois().get(1).sourceEnvelopeAtLevel()));

        var singleFile = eligible(originalBytes, source);
        var companions =
                new EligibleOriginalSource(
                        originalBytes,
                        SourceArtifactCodec.SourceContainer.SLIDE_FILE_SET,
                        singleFile.entryPath(),
                        singleFile.readerSettings());
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                TrainingSourcePlanner.plan(
                                        source,
                                        rois,
                                        task("field", 0.5),
                                        capability("field", fixed(false), 384, 384, false),
                                        Optional.of(companions),
                                        limits));
        assertTrue(error.getMessage().contains("original container"));
    }

    private static FixedFieldProfile fixed(boolean context) {
        return new FixedFieldProfile(
                384, 16, context, 0.25, "mean-logits-v1", "brightfield-rgb-v1");
    }

    private static TrainingTask task(String inputKind, double modelMpp) {
        return new TrainingTask(
                "test-capability",
                inputKind,
                new ModelOutput("instance_segmentation", List.of("Leydig cell"), List.of()),
                true,
                modelMpp,
                java.util.Map.of("Leydig cell", "Leydig cell"));
    }

    private static ModelTrainingCapability capability(
            String inputKind,
            TrainingFieldProfile field,
            long minimumSide,
            long maximumSide,
            boolean contextEditable) {
        return capability(inputKind, 0, field, minimumSide, maximumSide, contextEditable);
    }

    private static ModelTrainingCapability capability(
            String inputKind,
            int cropPaddingModelPx,
            TrainingFieldProfile field,
            long minimumSide,
            long maximumSide,
            boolean contextEditable) {
        var integerRange = new ClosedIntegerRange(1, 8);
        return new ModelTrainingCapability(
                "test-capability",
                "Test task",
                inputKind,
                "instance_segmentation",
                true,
                cropPaddingModelPx,
                field,
                new ClosedIntegerRange(minimumSide, maximumSide),
                contextEditable,
                List.of(
                        new TrainingAugmentationProfileChoice(
                                "h_e", "H&E", "Test profile", List.of())),
                "h_e",
                List.of(new TrainingGpuChoice("l4", "L4")),
                new ClosedIntegerRange(1, 1000),
                new AutomaticWarmupPolicy(8, 10),
                integerRange,
                new ClosedIntegerRange(0, 8),
                integerRange,
                List.of(),
                new TrainingSettings("l4", 1, 0, 1, 80, "auto", 10, null, null));
    }

    private static CapturedSourcePyramid source(
            int width,
            int height,
            double mpp,
            Optional<SourceIdentityCodec.SourceContentVersion> version) {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "qupath.openslide.local",
                        "1".repeat(64),
                        version,
                        width,
                        height,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(mpp, mpp));
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                "image",
                                SourceIdentityCodec.imageSourceIdentityDigest(identity),
                                "2".repeat(64)),
                        identity);
        return new CapturedSourcePyramid(
                revision,
                0,
                new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0),
                List.of(
                        new SourceLevel(
                                0,
                                new SourceViewGeometry.Dimensions(width, height),
                                new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0)),
                        new SourceLevel(
                                1,
                                new SourceViewGeometry.Dimensions(
                                        (width + 1) / 2, (height + 1) / 2),
                                new SourceIdentityCodec.ScaleTranslate2D(2, 2, 0, 0))));
    }

    private static Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.createPolygon(
                new Coordinate[] {
                    new Coordinate(minX, minY),
                    new Coordinate(maxX, minY),
                    new Coordinate(maxX, maxY),
                    new Coordinate(minX, maxY),
                    new Coordinate(minX, minY)
                });
    }
}
