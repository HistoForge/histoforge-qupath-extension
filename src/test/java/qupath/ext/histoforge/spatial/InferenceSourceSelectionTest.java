package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.eligible;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.FieldModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.TargetWork;

class InferenceSourceSelectionTest {

    private static final SourcePlanningLimits LIMITS =
            new SourcePlanningLimits(32_000_000, 16L << 30, 32L << 30);
    private static final Set<SourceArtifactCodec.SourceContainer> CONTAINERS =
            Set.of(
                    SourceArtifactCodec.SourceContainer.PNG,
                    SourceArtifactCodec.SourceContainer.TIFF_FAMILY,
                    SourceArtifactCodec.SourceContainer.SLIDE_FILE);

    @ParameterizedTest
    @CsvSource({"-1, true", "0, false", "1, false"})
    void comparesAgainstHalfTheActualOriginalIncludingOddByteSizes(
            long originalSizeOffset, boolean expectOriginal) {
        List<TargetWork> targets = List.of(target(1, CONTAINERS));
        long estimate = preparedEstimate(targets);
        long originalBytes = 2 * estimate + originalSizeOffset;

        var plan = plan(originalBytes, targets, Optional.of(originalBytes), LIMITS);

        assertEquals(
                expectOriginal,
                plan.targets().getFirst().sourcePlan().orElseThrow().artifact()
                        instanceof OriginalArtifactPlan);
    }

    @Test
    void sumsIndependentTargetsBecauseTheOriginalIsUploadedOnlyOnce() {
        var left = target(1, CONTAINERS);
        var right = target(2, CONTAINERS);
        long originalBytes = 3 * preparedEstimate(List.of(left));
        var single = plan(originalBytes, List.of(left), Optional.of(originalBytes), LIMITS);
        var combined =
                plan(originalBytes, List.of(left, right), Optional.of(originalBytes), LIMITS);
        var reversed =
                plan(originalBytes, List.of(right, left), Optional.of(originalBytes), LIMITS);
        var leftPlan = combined.targets().getFirst().sourcePlan().orElseThrow();
        var rightPlan = combined.targets().getLast().sourcePlan().orElseThrow();

        assertInstanceOf(
                DerivedArtifactPlan.class,
                single.targets().getFirst().sourcePlan().orElseThrow().artifact());
        assertInstanceOf(OriginalArtifactPlan.class, leftPlan.artifact());
        assertSame(leftPlan.artifact(), rightPlan.artifact());
        assertEquals(
                leftPlan.artifact(),
                reversed.targets().getFirst().sourcePlan().orElseThrow().artifact());
        assertEquals(left.target(), leftPlan.target());
        assertEquals(right.target(), rightPlan.target());
        var preparedLeft = single.targets().getFirst().sourcePlan().orElseThrow();
        assertEquals(preparedLeft.protocol(), leftPlan.protocol());
        assertEquals(
                preparedLeft.fieldInputs().getFirst().frame(),
                leftPlan.fieldInputs().getFirst().frame());
        assertEquals(
                preparedLeft.fieldInputs().getFirst().acceptance(),
                leftPlan.fieldInputs().getFirst().acceptance());
    }

    @Test
    void sharedModelsDoNotCountTheSamePreparedSourceTwice() {
        var first = target(1, CONTAINERS);
        var otherModel = model("second", CONTAINERS);
        var targets =
                List.of(
                        new TargetWork(
                                first.target(),
                                List.of(first.selectedModels().getFirst(), otherModel)));
        long originalBytes = 2 * preparedEstimate(List.of(first));

        var plan = plan(originalBytes, targets, Optional.of(originalBytes), LIMITS);
        var prepared = plan.targets().getFirst().sourcePlan().orElseThrow();

        assertEquals(2, prepared.fieldInputs().size());
        assertInstanceOf(DerivedArtifactPlan.class, prepared.artifact());
    }

    @Test
    void preparedFileLimitStillAppliesWhenTheCropIsBelowHalfTheOriginal() {
        var targets = List.of(target(1, CONTAINERS));
        long estimate = preparedEstimate(targets);
        long originalBytes = estimate * 4;
        var tight =
                new SourcePlanningLimits(
                        LIMITS.maxCompleteDecodePixels(),
                        estimate - 1,
                        LIMITS.maxSourceArtifactBytes());

        var plan = plan(originalBytes, targets, Optional.of(originalBytes), tight);

        assertInstanceOf(
                OriginalArtifactPlan.class,
                plan.targets().getFirst().sourcePlan().orElseThrow().artifact());
        var incremental = plan(originalBytes, targets, Optional.empty(), tight);
        assertInstanceOf(
                SourceInputPlan.IncrementalSourcePlan.class,
                incremental.targets().getFirst().sourcePlan().orElseThrow().artifact());
        assertEquals(
                plan.targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .fieldInputs()
                        .getFirst()
                        .frame(),
                incremental
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .fieldInputs()
                        .getFirst()
                        .frame());
    }

    @Test
    void unusableOriginalDoesNotPreventPreparingSourcesThatFit() {
        long originalBytes = LIMITS.maxSourceArtifactBytes() + 1;
        var target = target(1, CONTAINERS);
        var oversized = plan(originalBytes, List.of(target), Optional.of(originalBytes), LIMITS);
        var absent = plan(originalBytes, List.of(target), Optional.empty(), LIMITS);
        assertEquals(absent, oversized);
        assertInstanceOf(
                DerivedArtifactPlan.class,
                oversized.targets().getFirst().sourcePlan().orElseThrow().artifact());

        // This original is small enough to be preferred, but not all models accept slide files.
        var pngOnly = target(2, Set.of(SourceArtifactCodec.SourceContainer.PNG));
        var unsupported = plan(1, List.of(target, pngOnly), Optional.of(1L), LIMITS);
        for (var result : unsupported.targets()) {
            assertInstanceOf(
                    DerivedArtifactPlan.class, result.sourcePlan().orElseThrow().artifact());
        }
    }

    @Test
    void rejectsOriginalWhoseSizeNoLongerMatchesTheCapturedSource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(2, List.of(target(1, CONTAINERS)), Optional.of(1L), LIMITS));
    }

    @Test
    void recoversOneTargetsCapturedOriginalWithoutRepeatingTheCombinedSizeDecision() {
        var left = target(1, CONTAINERS);
        var right = target(2, CONTAINERS);
        long originalBytes = 3 * preparedEstimate(List.of(left));
        var combined =
                plan(originalBytes, List.of(left, right), Optional.of(originalBytes), LIMITS);
        var captured = combined.targets().getFirst().sourcePlan().orElseThrow();
        var retainedArtifact =
                SourceArtifactPlanCodec.parse(SourceArtifactPlanCodec.toJson(captured.artifact()));
        var recovered =
                SourceInputPlanner.planAgainstCapturedSource(
                        source(originalBytes), List.of(left), retainedArtifact);

        assertInstanceOf(OriginalArtifactPlan.class, retainedArtifact);
        assertEquals(combined.targets().getFirst(), recovered.targets().getFirst());
        assertInstanceOf(
                DerivedArtifactPlan.class,
                plan(originalBytes, List.of(left), Optional.of(originalBytes), LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact());
    }

    @Test
    void recoversPreparedSourceAndRejectsChangedGeometry() {
        var target = target(1, CONTAINERS);
        var prepared = plan(1, List.of(target), Optional.empty(), LIMITS);
        var captured = prepared.targets().getFirst().sourcePlan().orElseThrow().artifact();
        var retainedArtifact =
                SourceArtifactPlanCodec.parse(SourceArtifactPlanCodec.toJson(captured));

        assertEquals(
                prepared,
                SourceInputPlanner.planAgainstCapturedSource(
                        source(1), List.of(target), retainedArtifact));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceInputPlanner.planAgainstCapturedSource(
                                source(1), List.of(target(2, CONTAINERS)), retainedArtifact));
    }

    @Test
    void incrementalSelectionRoundTripsWithoutReapplyingTheSizeRule() {
        var target = target(1, CONTAINERS);
        var tight = new SourcePlanningLimits(32_000_000, 1, 32L << 30);
        var incremental = plan(1, List.of(target), Optional.empty(), tight);
        var sourcePlan = incremental.targets().getFirst().sourcePlan().orElseThrow();
        var retained =
                SourceArtifactPlanCodec.parse(
                        SourceArtifactPlanCodec.toJson(sourcePlan.artifact()));
        assertInstanceOf(SourceInputPlan.IncrementalSourcePlan.class, retained);
        assertEquals(SourceInputPlan.ExecutionProtocol.FINITE, sourcePlan.protocol());
        assertEquals(
                incremental,
                SourceInputPlanner.planAgainstCapturedSource(source(1), List.of(target), retained));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceInputPlanner.planAgainstCapturedSource(
                                source(1), List.of(target(2, CONTAINERS)), retained));
        var encoded = SourceArtifactPlanCodec.toJson(retained);
        encoded.addProperty("unexpected", true);
        assertThrows(IllegalArgumentException.class, () -> SourceArtifactPlanCodec.parse(encoded));
    }

    private static long preparedEstimate(List<TargetWork> targets) {
        return plan(1, targets, Optional.empty(), LIMITS).targets().stream()
                .map(result -> (DerivedArtifactPlan) result.sourcePlan().orElseThrow().artifact())
                .mapToLong(DerivedArtifactPlan::conservativeArtifactBytes)
                .sum();
    }

    private static SourceInputPlan plan(
            long originalBytes,
            List<TargetWork> targets,
            Optional<Long> candidateBytes,
            SourcePlanningLimits limits) {
        var source = source(originalBytes);
        return SourceInputPlanner.plan(
                source, targets, candidateBytes.map(bytes -> eligible(bytes, source)), limits);
    }

    private static CapturedSourcePyramid source(long originalBytes) {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "qupath.bioformats.local",
                        "1".repeat(64),
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        originalBytes, 1_700_000_000_000L, Optional.of("source"))),
                        1_000,
                        1_000,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        var mapping = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        return new CapturedSourcePyramid(
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                "image",
                                SourceIdentityCodec.imageSourceIdentityDigest(identity),
                                "2".repeat(64)),
                        identity),
                0,
                mapping,
                List.of(
                        new SourceLevel(
                                0, new SourceViewGeometry.Dimensions(1_000, 1_000), mapping)));
    }

    private static TargetWork target(
            int index, Set<SourceArtifactCodec.SourceContainer> containers) {
        int x = index * 300;
        var geometry =
                new GeometryFactory()
                        .createPolygon(
                                new Coordinate[] {
                                    new Coordinate(x, 100),
                                    new Coordinate(x + 100, 100),
                                    new Coordinate(x + 100, 200),
                                    new Coordinate(x, 200),
                                    new Coordinate(x, 100)
                                });
        return new TargetWork(
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Roi("11111111-1111-4111-8111-11111111111" + index),
                        geometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(geometry)),
                List.of(model("model", containers)));
    }

    private static FieldModel model(
            String id, Set<SourceArtifactCodec.SourceContainer> containers) {
        return new FieldModel(
                new InferenceIdentityCodec.ModelStepIdentity(
                        ModuleRef.parse("owner/module").orElseThrow(), id),
                0.25,
                Optional.empty(),
                new ExecutionLimitsCodec.TargetRasterLimits(2_048, 2_048, 4_194_304),
                true,
                containers);
    }
}
