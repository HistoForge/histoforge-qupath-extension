package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.eligible;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.IncrementalSourcePlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.PinnedSourceLevel;
import qupath.ext.histoforge.spatial.SourceInputPlan.TargetSourcePlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.FieldModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.TargetWork;

class InferencePreparedImageRoutingTest {
    private static final long GIB = 1L << 30;
    private static final SourcePlanningLimits LIMITS =
            new SourcePlanningLimits(32_000_000, 16 * GIB, 32 * GIB);
    private static final Set<SourceArtifactCodec.SourceContainer> CONTAINERS =
            Set.of(
                    SourceArtifactCodec.SourceContainer.PNG,
                    SourceArtifactCodec.SourceContainer.TIFF_FAMILY,
                    SourceArtifactCodec.SourceContainer.SLIDE_FILE);
    private static final SourceIdentityCodec.ScaleTranslate2D IDENTITY =
            new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);

    @ParameterizedTest
    @CsvSource({"39879, true", "39880, true", "39881, false"})
    void routesBelowAtAndAboveOneGibWithoutAllocatingPixels(int height, boolean complete) {
        var candidate = candidate(8_960, height);
        // This rectangle's RGB-plus-tiled-file estimate is exactly 1 GiB at height 39,880.
        assertEquals(GIB + (height - 39_880L) * 8_960 * 3, candidate.conservativeArtifactBytes());

        var artifact = SourceArtifactPlanning.inferencePlan(candidate, IDENTITY, LIMITS);

        assertEquals(complete, artifact instanceof DerivedArtifactPlan);
        if (!complete) assertInstanceOf(IncrementalSourcePlan.class, artifact);
    }

    @Test
    void smallerHardFileCeilingStillRoutesToOnDemandPreparation() {
        var tight = new SourcePlanningLimits(32_000_000, GIB - 1, 32 * GIB);
        assertInstanceOf(
                IncrementalSourcePlan.class,
                SourceArtifactPlanning.inferencePlan(candidate(8_960, 39_880), IDENTITY, tight));
    }

    @Test
    void twoGibPreparedTargetAgainstTwentyGibOriginalIsIncrementalNotOriginal() {
        var source = source(26_600, 26_880);
        var work = work(26_600, 26_880, 0.25, false);
        var planned =
                SourceInputPlanner.plan(
                        source, List.of(work), Optional.of(eligible(20 * GIB, source)), LIMITS);
        var target = planned.targets().getFirst().sourcePlan().orElseThrow();

        assertEquals(2 * GIB, candidate(26_600, 26_880).conservativeArtifactBytes());
        assertInstanceOf(IncrementalSourcePlan.class, target.artifact());
        assertEquals(SourceInputPlan.ExecutionProtocol.RECOVERABLE, target.protocol());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparationRoutePreservesTheModelGridAndRoiOrSlideProtocol(boolean slide) {
        var source = source(26_600, 26_880);
        var work = work(26_600, 26_880, 8.0, slide);
        var planned = SourceInputPlanner.plan(source, List.of(work), Optional.empty(), LIMITS);
        var incremental = planned.targets().getFirst().sourcePlan().orElseThrow();
        assertInstanceOf(IncrementalSourcePlan.class, incremental.artifact());
        assertEquals(
                slide
                        ? SourceInputPlan.ExecutionProtocol.RECOVERABLE
                        : SourceInputPlan.ExecutionProtocol.FINITE,
                incremental.protocol());
        assertTrue(
                incremental.fieldInputs().getFirst().frame().transform().raster().width() < 6_000);
        assertTrue(
                incremental.fieldInputs().getFirst().frame().transform().raster().height() < 6_000);

        var complete = completeImage(source, work, incremental);
        var original =
                SourceInputPlanner.plan(
                                source,
                                List.of(work),
                                Optional.of(eligible(20 * GIB, source)),
                                new SourcePlanningLimits(32_000_000, GIB / 2, 32 * GIB))
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        assertInstanceOf(OriginalArtifactPlan.class, original.artifact());
        assertSameProcessing(incremental, complete);
        assertSameProcessing(incremental, original);

        for (var artifact :
                List.of(incremental.artifact(), complete.artifact(), original.artifact())) {
            var retained = SourceArtifactPlanCodec.parse(SourceArtifactPlanCodec.toJson(artifact));
            var recovered =
                    SourceInputPlanner.planAgainstCapturedSource(source, List.of(work), retained)
                            .targets()
                            .getFirst()
                            .sourcePlan()
                            .orElseThrow();
            assertEquals(retained, recovered.artifact());
            assertSameProcessing(incremental, recovered);
            // Unlike a whole original, a prepared source plan captures this exact envelope.
            if (!(artifact instanceof OriginalArtifactPlan)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                SourceInputPlanner.planAgainstCapturedSource(
                                        source,
                                        List.of(work(26_500, 26_880, 8.0, slide)),
                                        retained));
            }
        }
    }

    @Test
    void requiredProcessingImageMayExceedRoutingCutoffButNotHardFileCeiling() {
        var source = source(26_600, 26_880);
        var target =
                SourceInputPlanner.plan(
                                source,
                                List.of(work(26_600, 26_880, 0.25, false)),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        var capture =
                assertInstanceOf(IncrementalSourcePlan.class, target.artifact())
                        .capture(
                                source.sourceRevision().pixelSource(),
                                new BuildIdentityCodec.SoftwareBuildIdentity(
                                        "test", "3".repeat(64), "4".repeat(64)));
        var image =
                SourceArtifactPlanning.capturedImage(
                        capture, target.sourceEnvelopeAtLevel(), CONTAINERS, LIMITS);
        assertEquals(2 * GIB, image.conservativeArtifactBytes());
        assertEquals(SourceArtifactCodec.SourceContainer.TIFF_FAMILY, image.container());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceArtifactPlanning.capturedImage(
                                capture,
                                target.sourceEnvelopeAtLevel(),
                                CONTAINERS,
                                new SourcePlanningLimits(32_000_000, 2 * GIB - 1, 32 * GIB)));
    }

    private static void assertSameProcessing(TargetSourcePlan expected, TargetSourcePlan actual) {
        assertEquals(expected.target(), actual.target());
        assertEquals(expected.sourceRevision(), actual.sourceRevision());
        assertEquals(expected.sourceLevel(), actual.sourceLevel());
        assertEquals(expected.sourceEnvelopeAtLevel(), actual.sourceEnvelopeAtLevel());
        assertEquals(expected.protocol(), actual.protocol());
        assertEquals(expected.fieldInputs().size(), actual.fieldInputs().size());
        for (int index = 0; index < expected.fieldInputs().size(); index++) {
            var left = expected.fieldInputs().get(index);
            var right = actual.fieldInputs().get(index);
            assertEquals(left.output(), right.output());
            assertEquals(left.acceptance(), right.acceptance());
            assertEquals(left.frame(), right.frame());
        }
    }

    private static TargetSourcePlan completeImage(
            CapturedSourcePyramid source, TargetWork work, TargetSourcePlan target) {
        var artifact =
                SourceArtifactPlanning.derivedPlan(
                        SourceArtifactPlanning.candidate(
                                target.target(),
                                target.sourceLevel(),
                                target.sourceEnvelopeAtLevel(),
                                CONTAINERS),
                        IDENTITY,
                        LIMITS);
        return SourceInputPlanner.planAgainstCapturedSource(source, List.of(work), artifact)
                .targets()
                .getFirst()
                .sourcePlan()
                .orElseThrow();
    }

    private static SourceArtifactPlanning.Candidate candidate(int width, int height) {
        return SourceArtifactPlanning.candidate(
                work(width, height, 0.25, false).target(),
                new PinnedSourceLevel(
                        0, 0, new SourceViewGeometry.Dimensions(width, height), IDENTITY),
                new SourceIdentityCodec.IntRect(0, 0, width, height),
                CONTAINERS);
    }

    private static CapturedSourcePyramid source(int width, int height) {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "qupath.bioformats.local",
                        "1".repeat(64),
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        20 * GIB, 1_700_000_000_000L, Optional.empty())),
                        width,
                        height,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        return new CapturedSourcePyramid(
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                "image",
                                SourceIdentityCodec.imageSourceIdentityDigest(identity),
                                "2".repeat(64)),
                        identity),
                0,
                IDENTITY,
                List.of(
                        new SourceLevel(
                                0, new SourceViewGeometry.Dimensions(width, height), IDENTITY)));
    }

    private static TargetWork work(int width, int height, double targetMpp, boolean slide) {
        var geometry =
                new GeometryFactory()
                        .createPolygon(
                                new Coordinate[] {
                                    new Coordinate(0, 0),
                                    new Coordinate(width, 0),
                                    new Coordinate(width, height),
                                    new Coordinate(0, height),
                                    new Coordinate(0, 0)
                                });
        return new TargetWork(
                new ExecutionScopeCodec.TargetRegion(
                        slide
                                ? new ExecutionScopeCodec.Slide()
                                : new ExecutionScopeCodec.Roi(
                                        "11111111-1111-4111-8111-111111111111"),
                        geometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(geometry)),
                List.of(
                        new FieldModel(
                                new InferenceIdentityCodec.ModelStepIdentity(
                                        ModuleRef.parse("owner/module").orElseThrow(), "model"),
                                targetMpp,
                                Optional.empty(),
                                new ExecutionLimitsCodec.TargetRasterLimits(
                                        6_000, 6_000, 36_000_000),
                                true,
                                CONTAINERS)));
    }
}
