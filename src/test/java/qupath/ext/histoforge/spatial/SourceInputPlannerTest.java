package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.eligible;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
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
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.spatial.SourceInputPlan.DerivedArtifactPlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.IncrementalSourcePlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CropModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.FieldModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.ParentCandidate;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.TargetWork;

class SourceInputPlannerTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final ModuleRef MODULE = ModuleRef.parse("owner/module").orElseThrow();
    private static final ExecutionLimitsCodec.TargetRasterLimits LARGE_LIMITS =
            new ExecutionLimitsCodec.TargetRasterLimits(100_000, 100_000, 10_000_000_000L);
    private static final Set<SourceArtifactCodec.SourceContainer> SOURCE_CONTAINERS =
            Set.of(
                    SourceArtifactCodec.SourceContainer.PNG,
                    SourceArtifactCodec.SourceContainer.TIFF_FAMILY,
                    SourceArtifactCodec.SourceContainer.SLIDE_FILE);
    private static final SourcePlanningLimits LIMITS =
            new SourcePlanningLimits(32_000_000, 1L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024);

    @Test
    void derivedCeilingCannotExceedTheBackendSourceCeiling() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SourcePlanningLimits(32_000_000, 8_000_001, 8_000_000));
    }

    @Test
    void contextParticipatesInProtocolAndSourceArtifactLimits() {
        var source = source(1_000, 800, 0.25, 0.25, Optional.empty());
        var target = target("11111111-1111-4111-8111-111111111111", 300, 200, 320, 220);
        var finite = new ExecutionLimitsCodec.TargetRasterLimits(64, 64, 4_096);
        var context =
                Optional.of(
                        new VariableFieldProfile(
                                128,
                                256,
                                192,
                                16,
                                8,
                                true,
                                0.2,
                                "test-only-aggregation",
                                "test-only-preprocessing"));
        var plain =
                new FieldModel(
                        producer("field"), 0.5, Optional.empty(), finite, true, SOURCE_CONTAINERS);
        var expanded =
                new FieldModel(producer("field"), 0.5, context, finite, true, SOURCE_CONTAINERS);
        var plainPlan =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(plain))),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        var expandedPlan =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(expanded))),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        assertEquals(SourceInputPlan.ExecutionProtocol.FINITE, plainPlan.protocol());
        assertEquals(SourceInputPlan.ExecutionProtocol.RECOVERABLE, expandedPlan.protocol());
        assertEquals(1, expandedPlan.sourceLevel().resolutionLevel());
        assertEquals(
                128, expandedPlan.fieldInputs().getFirst().frame().transform().raster().width());
        assertEquals(
                128, expandedPlan.fieldInputs().getFirst().frame().transform().raster().height());

        var finiteOnly =
                new FieldModel(producer("field"), 0.5, context, finite, false, SOURCE_CONTAINERS);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(finiteOnly))),
                                Optional.empty(),
                                LIMITS));

        long plainBytes = ((DerivedArtifactPlan) plainPlan.artifact()).conservativeArtifactBytes();
        var sourceLimit =
                new SourcePlanningLimits(32_000_000, plainBytes, LIMITS.maxSourceArtifactBytes());
        assertEquals(
                plainPlan,
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(plain))),
                                Optional.empty(),
                                sourceLimit)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow());
        assertInstanceOf(
                IncrementalSourcePlan.class,
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(expanded))),
                                Optional.empty(),
                                sourceLimit)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact());
    }

    @Test
    void plansOnePinnedLevelExactFieldViewAndLaterParentInput() {
        CapturedSourcePyramid source = source(1_000, 800, 0.25, 0.5, Optional.empty());
        var target = target("11111111-1111-4111-8111-111111111111", 100, 100, 300, 300);
        var parentProducer = producer("parent");
        var childProducer = producer("child");
        Geometry currentParent = rectangle(150, 150, 200, 200);
        var models =
                List.of(
                        new FieldModel(
                                parentProducer,
                                1.5,
                                Optional.empty(),
                                LARGE_LIMITS,
                                true,
                                SOURCE_CONTAINERS),
                        new CropModel(
                                childProducer,
                                0.75,
                                LARGE_LIMITS,
                                true,
                                SOURCE_CONTAINERS,
                                16,
                                parentProducer,
                                Set.of("Tubule"),
                                Set.of("Tubule (Cross Section)"),
                                true,
                                List.of(
                                        new ParentCandidate(
                                                Optional.of("Edited"),
                                                Optional.of(parentProducer),
                                                currentParent))));

        SourceInputPlan.TargetSourcePlan plan =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, models)),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();

        // The child model is finer than level 1 along Y, so the complete target pins level zero.
        assertEquals(0, plan.sourceLevel().resolutionLevel());
        assertEquals(SourceInputPlan.ExecutionProtocol.FINITE, plan.protocol());
        assertEquals(1, plan.fieldInputs().size());
        assertEquals(1, plan.cropInputs().size());
        DerivedArtifactPlan artifact = assertInstanceOf(DerivedArtifactPlan.class, plan.artifact());
        assertEquals(SourceArtifactCodec.SourceContainer.PNG, artifact.container());
        assertEquals(plan.sourceEnvelopeAtLevel(), artifact.sourceRectAtLevel());

        var field = plan.fieldInputs().getFirst();
        assertEquals(parentProducer, field.output().producer());
        assertEquals(new ExecutionScopeCodec.FieldScope(target.key()), field.output().scope());
        assertEquals(1.5, field.frame().transform().raster().targetMpp());
        assertEquals(
                artifact.artifactPixelsToSlide().translateX()
                        + field.sourceView().pixelRect().x()
                                * artifact.artifactPixelsToSlide().scaleX(),
                field.sourceView().viewPixelsToSlide().translateX());

        Geometry resolvedParent = rectangle(220, 180, 260, 240);
        var parentInput =
                SourceInputPlanner.planParentInput(
                        plan,
                        childProducer,
                        "22222222-2222-4222-8222-222222222222",
                        resolvedParent);
        assertEquals(
                new ExecutionScopeCodec.ParentScope(
                        target.key(), "22222222-2222-4222-8222-222222222222"),
                parentInput.output().scope());
        assertEquals(resolvedParent, parentInput.acceptance().parentGeometrySlide().orElseThrow());
        assertEquals(0, parentInput.sourceView().seriesIndex());
        assertEquals(0, parentInput.sourceView().resolutionLevel());
        assertTrue(parentInput.sourceView().pixelRect().x() >= 0);
        assertTrue(parentInput.sourceView().pixelRect().y() >= 0);
    }

    @Test
    void usesOneEligibleOriginalForEveryTargetWhenOneDerivedTargetExceedsTheCeiling() {
        long originalBytes = 1_000_000;
        CapturedSourcePyramid source =
                source(
                        1_000,
                        800,
                        0.25,
                        0.25,
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        originalBytes, 1_700_000_000_000L, Optional.of("file"))));
        FieldModel coarseModel =
                new FieldModel(
                        producer("field"),
                        0.5,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS);
        FieldModel fineModel =
                new FieldModel(
                        producer("field"),
                        0.25,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS);
        var left = target("11111111-1111-4111-8111-111111111111", 20, 20, 120, 120);
        var right = target("22222222-2222-4222-8222-222222222222", 500, 20, 900, 420);
        var tightLimits = new SourcePlanningLimits(32_000_000, 1_100_000, 8_000_000);

        assertInstanceOf(
                DerivedArtifactPlan.class,
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(left, List.of(coarseModel))),
                                Optional.empty(),
                                tightLimits)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact());
        assertInstanceOf(
                IncrementalSourcePlan.class,
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(right, List.of(fineModel))),
                                Optional.empty(),
                                tightLimits)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact());

        SourceInputPlan plan =
                SourceInputPlanner.plan(
                        source,
                        List.of(
                                new TargetWork(left, List.of(coarseModel)),
                                new TargetWork(right, List.of(fineModel))),
                        Optional.of(eligible(originalBytes, source)),
                        tightLimits);

        OriginalArtifactPlan first =
                assertInstanceOf(
                        OriginalArtifactPlan.class,
                        plan.targets().get(0).sourcePlan().orElseThrow().artifact());
        OriginalArtifactPlan second =
                assertInstanceOf(
                        OriginalArtifactPlan.class,
                        plan.targets().get(1).sourcePlan().orElseThrow().artifact());
        assertEquals(first, second);
        assertEquals(
                List.of(0, 1),
                first.requiredLevels().stream()
                        .map(SourceArtifactCodec.SourceLevelRequirement::resolutionLevel)
                        .toList());
        assertEquals(
                1,
                plan.targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .fieldInputs()
                        .getFirst()
                        .sourceView()
                        .resolutionLevel());

        SourceArtifactCodec.SourceArtifactStartRequest start =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        "33333333-3333-4333-8333-333333333333",
                        "44444444-4444-4444-8444-444444444444",
                        SourceArtifactCodec.SourceContainer.SLIDE_FILE,
                        originalBytes,
                        1024,
                        new SourceArtifactCodec.OriginalWsi(
                                source.sourceRevision().pixelSource(),
                                first.pyramidSeriesIndex(),
                                first.levelZeroPixelsToSlide(),
                                first.entryPath(),
                                first.readerSettings(),
                                List.of()),
                        first.requiredLevels());
        SourceInputPlan.TargetSourcePlan recoveredFinePlan =
                SourceInputPlanner.planAgainstPublishedSource(
                                source, List.of(new TargetWork(right, List.of(fineModel))), start)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        OriginalArtifactPlan recoveredFine =
                assertInstanceOf(OriginalArtifactPlan.class, recoveredFinePlan.artifact());
        assertEquals(0, recoveredFinePlan.sourceLevel().resolutionLevel());
        assertEquals(first.requiredLevels(), recoveredFine.requiredLevels());

        List<SourceArtifactCodec.SourceLevelRequirement> wrongRetainedLevels =
                List.of(
                        first.requiredLevels().getFirst(),
                        new SourceArtifactCodec.SourceLevelRequirement(0, 1, 499, 400));
        SourceArtifactCodec.SourceArtifactStartRequest wrongRetainedStart =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        start.creationActionId(),
                        start.artifactId(),
                        start.container(),
                        start.totalBytes(),
                        start.chunkSizeBytes(),
                        start.origin(),
                        wrongRetainedLevels);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceInputPlanner.planAgainstPublishedSource(
                                source,
                                List.of(new TargetWork(right, List.of(fineModel))),
                                wrongRetainedStart));
    }

    @Test
    void prefersBoundedDerivedArtifactsWhenTheyAreBelowHalfTheOriginalSize() {
        long originalBytes = 4_000_000;
        CapturedSourcePyramid source =
                source(
                        1_000,
                        800,
                        0.25,
                        0.25,
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        originalBytes, 1_700_000_000_000L, Optional.of("file"))));
        var target = target("11111111-1111-4111-8111-111111111111", 20, 20, 420, 420);
        FieldModel model =
                new FieldModel(
                        producer("field"),
                        0.5,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS);

        var artifact =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(model))),
                                Optional.of(eligible(originalBytes, source)),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact();

        assertInstanceOf(DerivedArtifactPlan.class, artifact);
    }

    @Test
    void oversizedDerivedArtifactUsesIncrementalDeliveryWithoutAnAdmissibleOriginal() {
        long sourceCeiling = 1_500_000;
        long eligibleBytes = sourceCeiling;
        long oversizedOriginalBytes = sourceCeiling + 1;
        var tightLimits = new SourcePlanningLimits(32_000_000, 1_100_000, sourceCeiling);
        var target = target("11111111-1111-4111-8111-111111111111", 20, 20, 420, 420);

        FieldModel both =
                new FieldModel(
                        producer("field"),
                        0.5,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS);
        CapturedSourcePyramid eligibleSource =
                source(
                        1_000,
                        800,
                        0.25,
                        0.25,
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        eligibleBytes, 1_700_000_000_000L, Optional.of("file"))));
        assertInstanceOf(
                IncrementalSourcePlan.class,
                SourceInputPlanner.plan(
                                eligibleSource,
                                List.of(new TargetWork(target, List.of(both))),
                                Optional.empty(),
                                tightLimits)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact());

        CapturedSourcePyramid oversizedOriginalSource =
                source(
                        1_000,
                        800,
                        0.25,
                        0.25,
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        oversizedOriginalBytes,
                                        1_700_000_000_000L,
                                        Optional.of("file"))));
        assertInstanceOf(
                IncrementalSourcePlan.class,
                SourceInputPlanner.plan(
                                oversizedOriginalSource,
                                List.of(new TargetWork(target, List.of(both))),
                                Optional.of(
                                        eligible(oversizedOriginalBytes, oversizedOriginalSource)),
                                tightLimits)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact());

        FieldModel pngOnly =
                new FieldModel(
                        producer("field"),
                        0.5,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        Set.of(SourceArtifactCodec.SourceContainer.PNG));
        assertInstanceOf(
                IncrementalSourcePlan.class,
                SourceInputPlanner.plan(
                                eligibleSource,
                                List.of(new TargetWork(target, List.of(pngOnly))),
                                Optional.of(eligible(eligibleBytes, eligibleSource)),
                                tightLimits)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact());
    }

    @Test
    void publishedOriginalReproducesItsPlanAndRetainedByteIdentity() {
        long originalBytes = 1_000_000;
        CapturedSourcePyramid source =
                source(
                        1_000,
                        800,
                        0.25,
                        0.25,
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        originalBytes, 1_700_000_000_000L, Optional.of("file"))));
        FieldModel model =
                new FieldModel(
                        producer("field"),
                        0.5,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS);
        TargetWork work =
                new TargetWork(
                        target("11111111-1111-4111-8111-111111111111", 20, 20, 420, 420),
                        List.of(model));
        SourceInputPlan initial =
                SourceInputPlanner.plan(
                        source,
                        List.of(work),
                        Optional.of(eligible(originalBytes, source)),
                        new SourcePlanningLimits(32_000_000, 1_100_000, 8_000_000));
        OriginalArtifactPlan artifact =
                assertInstanceOf(
                        OriginalArtifactPlan.class,
                        initial.targets().getFirst().sourcePlan().orElseThrow().artifact());
        SourceArtifactCodec.SourceArtifactStartRequest start =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        "33333333-3333-4333-8333-333333333333",
                        "44444444-4444-4444-8444-444444444444",
                        SourceArtifactCodec.SourceContainer.SLIDE_FILE,
                        originalBytes,
                        1024,
                        new SourceArtifactCodec.OriginalWsi(
                                source.sourceRevision().pixelSource(),
                                artifact.pyramidSeriesIndex(),
                                artifact.levelZeroPixelsToSlide(),
                                artifact.entryPath(),
                                artifact.readerSettings(),
                                List.of()),
                        artifact.requiredLevels());

        assertEquals(
                initial,
                SourceInputPlanner.planAgainstPublishedSource(source, List.of(work), start));
    }

    @Test
    void publishedDerivedRecomputesItsConservativeBound() {
        CapturedSourcePyramid source = source(1_000, 800, 0.25, 0.25, Optional.empty());
        FieldModel model =
                new FieldModel(
                        producer("field"),
                        0.5,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS);
        TargetWork work =
                new TargetWork(
                        target("11111111-1111-4111-8111-111111111111", 20, 20, 420, 420),
                        List.of(model));
        SourceInputPlan initial =
                SourceInputPlanner.plan(source, List.of(work), Optional.empty(), LIMITS);
        DerivedArtifactPlan artifact =
                assertInstanceOf(
                        DerivedArtifactPlan.class,
                        initial.targets().getFirst().sourcePlan().orElseThrow().artifact());
        long encodedBytes = 123_456;
        assertNotEquals(encodedBytes, artifact.conservativeArtifactBytes());
        SourceArtifactCodec.SourceArtifactStartRequest start =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        SourceArtifactCodec.SCHEMA_VERSION,
                        "33333333-3333-4333-8333-333333333333",
                        "44444444-4444-4444-8444-444444444444",
                        artifact.container(),
                        encodedBytes,
                        1024,
                        new SourceArtifactCodec.DerivedRegion(
                                source.sourceRevision().pixelSource(),
                                artifact.sourceResolutionLevel(),
                                artifact.plane(),
                                artifact.sourceRectAtLevel(),
                                artifact.artifactPixelsToSlide(),
                                new BuildIdentityCodec.SoftwareBuildIdentity(
                                        "test-writer", "a".repeat(64), "b".repeat(64))),
                        List.of(
                                new SourceArtifactCodec.SourceLevelRequirement(
                                        0,
                                        0,
                                        artifact.sourceRectAtLevel().width(),
                                        artifact.sourceRectAtLevel().height())));

        assertEquals(
                initial,
                SourceInputPlanner.planAgainstPublishedSource(source, List.of(work), start));
    }

    @Test
    void wsiScaleTargetPrefersAnEligibleOriginalOtherwisePlansBoundedImages() {
        long originalBytes = 3_830_628_072L;
        CapturedSourcePyramid source =
                source(
                        126_976,
                        253_952,
                        0.22220247089147632,
                        0.22220247089147632,
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        originalBytes, 1_700_000_000_000L, Optional.of("wsi"))));
        var target = target("11111111-1111-4111-8111-111111111111", 0, 0, 126_976, 126_976);
        var wsiRasterLimits =
                new ExecutionLimitsCodec.TargetRasterLimits(500_000, 500_000, 250_000_000_000L);
        FieldModel model =
                new FieldModel(
                        producer("field"),
                        0.22,
                        Optional.empty(),
                        wsiRasterLimits,
                        true,
                        SOURCE_CONTAINERS);
        TargetWork work = new TargetWork(target, List.of(model));

        var captured =
                SourceInputPlanner.plan(
                                source,
                                List.of(work),
                                Optional.empty(),
                                new SourcePlanningLimits(
                                        32_000_000,
                                        64L * 1024 * 1024 * 1024,
                                        64L * 1024 * 1024 * 1024))
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        assertInstanceOf(IncrementalSourcePlan.class, captured.artifact());
        long estimate =
                SourceArtifactPlanning.candidate(
                                captured.target(),
                                captured.sourceLevel(),
                                captured.sourceEnvelopeAtLevel(),
                                SOURCE_CONTAINERS)
                        .conservativeArtifactBytes();
        // The target spans the full source width, so X clips at the image edge. Its Y edge is
        // internal to the slide and bilinear resampling therefore requires one neighboring row.
        assertEquals(48_401_760_256L, estimate);
        assertTrue(estimate > LIMITS.maxDerivedSourceArtifactBytes());
        assertTrue(originalBytes <= LIMITS.maxSourceArtifactBytes());

        assertInstanceOf(
                IncrementalSourcePlan.class,
                SourceInputPlanner.plan(source, List.of(work), Optional.empty(), LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact());

        var artifact =
                SourceInputPlanner.plan(
                                source,
                                List.of(work),
                                Optional.of(eligible(originalBytes, source)),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact();

        assertEquals(
                originalBytes, assertInstanceOf(OriginalArtifactPlan.class, artifact).totalBytes());
    }

    @Test
    void largeDerivedTargetUsesTiledTiffAndRequiresDurableExecution() {
        CapturedSourcePyramid source = source(7_000, 6_000, 0.25, 0.25, Optional.empty());
        var target = target("11111111-1111-4111-8111-111111111111", 0, 0, 7_000, 6_000);
        var finiteLimits = new ExecutionLimitsCodec.TargetRasterLimits(2_048, 2_048, 4_194_304);
        FieldModel durable =
                new FieldModel(
                        producer("field"),
                        0.25,
                        Optional.empty(),
                        finiteLimits,
                        true,
                        SOURCE_CONTAINERS);

        var planned =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(durable))),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        assertEquals(SourceInputPlan.ExecutionProtocol.RECOVERABLE, planned.protocol());
        assertEquals(
                SourceArtifactCodec.SourceContainer.TIFF_FAMILY,
                assertInstanceOf(DerivedArtifactPlan.class, planned.artifact()).container());

        FieldModel finiteOnly =
                new FieldModel(
                        producer("field"),
                        0.25,
                        Optional.empty(),
                        finiteLimits,
                        false,
                        SOURCE_CONTAINERS);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(finiteOnly))),
                                Optional.empty(),
                                LIMITS));
    }

    @Test
    void cropOnlyTargetWithNoEligibleParentNeedsNoSourceArtifact() {
        CapturedSourcePyramid source = source(500, 500, 0.25, 0.25, Optional.empty());
        var target = target("11111111-1111-4111-8111-111111111111", 50, 50, 450, 450);
        CropModel crop =
                new CropModel(
                        producer("child"),
                        0.25,
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS,
                        0,
                        producer("parent"),
                        Set.of("Tubule"),
                        Set.of(),
                        false,
                        List.of());

        assertTrue(
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(crop))),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .isEmpty());
    }

    @Test
    void explicitParentPlanCannotBeReusedForAnUnplannedDistantParent() {
        CapturedSourcePyramid source = source(1_000, 1_000, 0.25, 0.25, Optional.empty());
        var target = target("11111111-1111-4111-8111-111111111111", 50, 50, 950, 950);
        var parentProducer = producer("parent");
        var childProducer = producer("child");
        Geometry selectedParent = rectangle(100, 100, 140, 140);
        CropModel crop =
                new CropModel(
                        childProducer,
                        0.25,
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS,
                        0,
                        parentProducer,
                        Set.of("Tubule"),
                        Set.of(),
                        false,
                        List.of(
                                new ParentCandidate(
                                        Optional.of("Tubule"), Optional.empty(), selectedParent)));
        var plan =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(crop))),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceInputPlanner.planParentInput(
                                plan,
                                childProducer,
                                "22222222-2222-4222-8222-222222222222",
                                rectangle(800, 800, 840, 840)));
    }

    @Test
    void sourceLevelSelectionUsesReaderMappingInsteadOfRoundedDimensionRatios() {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "rounded-pyramid",
                        "3".repeat(64),
                        Optional.empty(),
                        103,
                        79,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                "rounded-pyramid",
                                SourceIdentityCodec.imageSourceIdentityDigest(identity),
                                "4".repeat(64)),
                        identity);
        var source =
                new CapturedSourcePyramid(
                        revision,
                        0,
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0),
                        List.of(
                                new SourceLevel(
                                        0,
                                        new SourceViewGeometry.Dimensions(103, 79),
                                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0)),
                                new SourceLevel(
                                        1,
                                        new SourceViewGeometry.Dimensions(52, 20),
                                        new SourceIdentityCodec.ScaleTranslate2D(2, 4, 0, 0))));
        FieldModel model =
                new FieldModel(
                        producer("field"),
                        0.99,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS);

        var plan =
                SourceInputPlanner.plan(
                                source,
                                List.of(
                                        new TargetWork(
                                                target(
                                                        "11111111-1111-4111-8111-111111111111",
                                                        10,
                                                        10,
                                                        90,
                                                        70),
                                                List.of(model))),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();

        // The actual Y downsample is 4, so level 1 is coarser than 0.99 MPP.
        // The rounded dimension ratio 79/20 would incorrectly admit it.
        assertEquals(0, plan.sourceLevel().resolutionLevel());
    }

    @Test
    void derivedContainerUsesTheIntersectionAcceptedByEverySelectedModel() {
        CapturedSourcePyramid source = source(500, 500, 0.25, 0.25, Optional.empty());
        var target = target("11111111-1111-4111-8111-111111111111", 50, 50, 450, 450);
        var parentProducer = producer("parent");
        FieldModel parent =
                new FieldModel(
                        parentProducer,
                        0.5,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS);
        CropModel tiffOnlyChild =
                new CropModel(
                        producer("child"),
                        0.5,
                        LARGE_LIMITS,
                        true,
                        Set.of(SourceArtifactCodec.SourceContainer.TIFF_FAMILY),
                        0,
                        parentProducer,
                        Set.of("Tubule"),
                        Set.of(),
                        true,
                        List.of());

        var artifact =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(parent, tiffOnlyChild))),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow()
                        .artifact();

        assertEquals(
                SourceArtifactCodec.SourceContainer.TIFF_FAMILY,
                assertInstanceOf(DerivedArtifactPlan.class, artifact).container());
    }

    @Test
    void publicPlannerPreservesOriginalViewBinary64OperationOrder() {
        double levelZeroTranslate = 60.090470299161694;
        double levelZeroScale = 1.0001;
        double levelScale = 4.328922635354765;
        var levelZero =
                new SourceIdentityCodec.ScaleTranslate2D(
                        levelZeroScale, levelZeroScale, levelZeroTranslate, 0.0);
        var selectedLevel =
                new SourceIdentityCodec.ScaleTranslate2D(levelScale, levelScale, 0.0, 0.0);
        CapturedSourcePyramid source =
                mappedSource(
                        "qupath.bioformats.local",
                        500_000,
                        500_000,
                        new SourceIdentityCodec.PixelCalibration(1.0, 1.0),
                        levelZero,
                        new SourceViewGeometry.Dimensions(100_000, 100_000),
                        selectedLevel,
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        1, 1_700_000_000_000L, Optional.of("source"))));
        int expectedViewX = 70_343;
        double minX = levelZeroTranslate + (expectedViewX * levelScale) * levelZeroScale;
        var target =
                target("11111111-1111-4111-8111-111111111111", minX, 400.0, minX + 11.0, 411.0);
        FieldModel model =
                new FieldModel(
                        producer("field"),
                        4.4,
                        Optional.empty(),
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS);

        var plan =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(model))),
                                Optional.of(eligible(1, source)),
                                new SourcePlanningLimits(32_000_000, 1, 1))
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        assertInstanceOf(OriginalArtifactPlan.class, plan.artifact());
        var view = plan.fieldInputs().getFirst().sourceView();
        double composeFirst =
                SourceViewGeometry.selectedLevelPixelsToSlide(levelZero, selectedLevel).translateX()
                        + view.pixelRect().x() * (levelZeroScale * levelScale);

        assertEquals(expectedViewX, view.pixelRect().x());
        assertEquals(304599.9463495533, view.viewPixelsToSlide().translateX());
        assertNotEquals(composeFirst, view.viewPixelsToSlide().translateX());
    }

    @Test
    void publicPlannerPreservesDerivedViewBinary64OperationOrder() {
        double baseTranslate = -731.2715117751975;
        double levelScale = 24.532112507341665;
        var levelZero = new SourceIdentityCodec.ScaleTranslate2D(1.0, 1.0, baseTranslate, 0.0);
        var selectedLevel = new SourceIdentityCodec.ScaleTranslate2D(levelScale, 1.0, 0.0, 0.0);
        CapturedSourcePyramid source =
                mappedSource(
                        "derived-operation-order",
                        2_500_000,
                        2_000,
                        new SourceIdentityCodec.PixelCalibration(1.0, levelScale),
                        levelZero,
                        new SourceViewGeometry.Dimensions(100_000, 1_000),
                        selectedLevel,
                        Optional.empty());
        double leftMin = baseTranslate + 33_086 * levelScale;
        double rightMin = baseTranslate + 66_482 * levelScale + 0.1;
        Geometry leftParent = rectangle(leftMin, 100.0, leftMin + 2.5 * levelScale, 102.5);
        Geometry rightParent = rectangle(rightMin, 100.0, rightMin + 2.5 * levelScale, 102.5);
        var target =
                target(
                        "11111111-1111-4111-8111-111111111111",
                        leftMin - 1.0,
                        99.0,
                        rightMin + 2.5 * levelScale + 1.0,
                        104.0);
        var childProducer = producer("child");
        CropModel crop =
                new CropModel(
                        childProducer,
                        levelScale,
                        LARGE_LIMITS,
                        true,
                        SOURCE_CONTAINERS,
                        0,
                        producer("parent"),
                        Set.of("Tubule"),
                        Set.of(),
                        false,
                        List.of(
                                new ParentCandidate(
                                        Optional.of("Tubule"), Optional.empty(), leftParent),
                                new ParentCandidate(
                                        Optional.of("Tubule"), Optional.empty(), rightParent)));

        var plan =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(crop))),
                                Optional.empty(),
                                LIMITS)
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        DerivedArtifactPlan artifact = assertInstanceOf(DerivedArtifactPlan.class, plan.artifact());
        var input =
                SourceInputPlanner.planParentInput(
                        plan, childProducer, "22222222-2222-4222-8222-222222222222", rightParent);
        var view = input.sourceView();
        double combineOffsetsFirst =
                baseTranslate
                        + (artifact.sourceRectAtLevel().x() + view.pixelRect().x()) * levelScale;

        assertEquals(33_086, artifact.sourceRectAtLevel().x());
        assertEquals(33_396, view.pixelRect().x());
        assertEquals(1630212.6322013135, view.viewPixelsToSlide().translateX());
        assertNotEquals(combineOffsetsFirst, view.viewPixelsToSlide().translateX());

        var incremental =
                SourceInputPlanner.plan(
                                source,
                                List.of(new TargetWork(target, List.of(crop))),
                                Optional.empty(),
                                new SourcePlanningLimits(
                                        32_000_000, 1, LIMITS.maxSourceArtifactBytes()))
                        .targets()
                        .getFirst()
                        .sourcePlan()
                        .orElseThrow();
        assertInstanceOf(IncrementalSourcePlan.class, incremental.artifact());
        assertEquals(SourceInputPlan.ExecutionProtocol.FINITE, incremental.protocol());
        var boundedInput =
                SourceInputPlanner.planParentInput(
                        incremental,
                        childProducer,
                        "22222222-2222-4222-8222-222222222222",
                        rightParent);
        assertEquals(input, boundedInput);
        var capture =
                ((IncrementalSourcePlan) incremental.artifact())
                        .capture(
                                source.sourceRevision().pixelSource(),
                                new BuildIdentityCodec.SoftwareBuildIdentity(
                                        "writer", "a".repeat(64), "b".repeat(64)));
        var capturedView =
                new qupath.ext.histoforge.contract.SourceCaptureCodec.View(
                        capture, boundedInput.sourceView().pixelRect());
        assertEquals(view.viewPixelsToSlide(), capturedView.viewPixelsToSlide());
    }

    private static CapturedSourcePyramid source(
            int width,
            int height,
            double mppX,
            double mppY,
            Optional<SourceIdentityCodec.SourceContentVersion> version) {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "qupath.bioformats.local",
                        "1".repeat(64),
                        version,
                        width,
                        height,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(mppX, mppY));
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

    private static CapturedSourcePyramid mappedSource(
            String sourceId,
            int width,
            int height,
            SourceIdentityCodec.PixelCalibration calibration,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            SourceViewGeometry.Dimensions selectedDimensions,
            SourceIdentityCodec.ScaleTranslate2D selectedPixelsToLevelZero,
            Optional<SourceIdentityCodec.SourceContentVersion> version) {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        sourceId, "5".repeat(64), version, width, height, 1, 1, calibration);
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                sourceId,
                                SourceIdentityCodec.imageSourceIdentityDigest(identity),
                                "6".repeat(64)),
                        identity);
        return new CapturedSourcePyramid(
                revision,
                0,
                levelZeroPixelsToSlide,
                List.of(
                        new SourceLevel(
                                0,
                                new SourceViewGeometry.Dimensions(width, height),
                                new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0)),
                        new SourceLevel(1, selectedDimensions, selectedPixelsToLevelZero)));
    }

    private static ExecutionScopeCodec.TargetRegion target(
            String roiId, double minX, double minY, double maxX, double maxY) {
        Geometry geometry = rectangle(minX, minY, maxX, maxY);
        return new ExecutionScopeCodec.TargetRegion(
                new ExecutionScopeCodec.Roi(roiId),
                geometry,
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                CanonicalGeometryCodec.canonicalDigest(geometry));
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

    private static InferenceIdentityCodec.ModelStepIdentity producer(String stepId) {
        return new InferenceIdentityCodec.ModelStepIdentity(MODULE, stepId);
    }
}
