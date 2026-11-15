package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.localSource;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.readerSettings;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactRef;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactSealed;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.data.MetricTypes.Scalar;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Finite image-action tests across source publication, recipe work, and QuPath finalization. */
class ImageActionExecutorTest {

    @TempDir Path temporaryRoot;

    @Test
    void producedOutputPublishesOneSourceSavesOnceAndReportsPersistedTruth() throws Exception {
        try (Harness harness =
                harness(
                        (input, stop) ->
                                RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        RecipeExecutorTestData.PARENT_ID,
                                                        RecipeExecutorTestData.square(
                                                                10, 10, 30, 30),
                                                        "Parent"))),
                        RecipeExecutorTestData.ROI_A)) {
            RunResults.ImageRunResult result = harness.execute();

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED, result.outcome(), result.toString());
            assertTrue(result.hierarchySaved());
            assertEquals(1, result.scopes().size());
            assertEquals(RunResults.ScopeOutcomeType.PRODUCED, result.scopes().getFirst().type());
            assertTrue(result.scopes().getFirst().hierarchySaved());
            assertTrue(result.imageFailures().isEmpty());
            assertEquals(2, harness.savedImages.size());
            assertSame(harness.live, harness.savedImages.getFirst());
            assertSame(harness.working, harness.savedImages.getLast());
            assertSame(harness.working, harness.active.get());
            assertEquals(1, harness.materializeCalls.get());
            assertEquals(harness.publishedIds, harness.releasedIds);
            assertTrue(isEmptyDirectory(temporaryRoot));
        }
    }

    @Test
    void callerOwnedReservationRemainsActiveAfterFiniteCompletion() throws Exception {
        try (Harness harness =
                harness(
                        (input, stop) -> RecipeExecutorTestData.generation(input, List.of()),
                        RecipeExecutorTestData.ROI_A)) {
            ImageActionRegistry.Reservation reservation =
                    harness.registry.reserve(harness.request.entry().getID());

            RunResults.ImageRunResult result;
            try {
                result = harness.executor.execute(harness.request, reservation, () -> false);
                try (ImageActionRegistry.Lease ignored = reservation.acquireLease()) {
                    assertEquals(harness.request.entry().getID(), ignored.imageId());
                }
            } finally {
                reservation.close();
            }

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE, result.outcome());
        }
    }

    @Test
    void validEmptyRevalidatesWithoutASecondSaveOrViewerSwap() throws Exception {
        try (Harness harness =
                harness(
                        (input, stop) -> RecipeExecutorTestData.generation(input, List.of()),
                        RecipeExecutorTestData.ROI_A)) {
            RunResults.ImageRunResult result = harness.execute();

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE, result.outcome());
            assertFalse(result.hierarchySaved());
            assertEquals(
                    RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY, result.scopes().getFirst().type());
            assertFalse(result.scopes().getFirst().hierarchySaved());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertEquals(0, harness.viewerSwaps.get());
            assertSame(harness.live, harness.active.get());
            assertEquals(harness.publishedIds, harness.releasedIds);
            assertTrue(isEmptyDirectory(temporaryRoot));
        }
    }

    @Test
    void metricsOnlyNormalizesParentsSavesOnceAndPublishesDetachedValues() throws Exception {
        try (Harness harness =
                metricsHarness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            PathObject manual =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(10, 10, 10, 10, ImagePlane.getDefaultPlane()),
                            PathClass.fromString("Manual"));
            PathObject liveManual =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(10, 10, 10, 10, ImagePlane.getDefaultPlane()),
                            PathClass.fromString("Manual"));
            liveManual.setID(manual.getID());
            harness.working.getHierarchy().addObject(manual);
            harness.live.getHierarchy().addObject(liveManual);

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED, result.outcome(), result.toString());
            assertTrue(result.hierarchySaved());
            assertTrue(result.scopes().isEmpty());
            assertEquals(
                    new Scalar(1),
                    result.metrics()
                            .orElseThrow()
                            .regionResults()
                            .values()
                            .iterator()
                            .next()
                            .values()
                            .get("object_count"));
            assertEquals(
                    "f".repeat(64),
                    result.metrics().orElseThrow().sourceRevision().imageDataContentDigest());
            assertEquals(RecipeExecutorTestData.ROI_A, manual.getParent().getID().toString());
            assertEquals(0, harness.materializeCalls.get());
            assertEquals(2, harness.savedImages.size());
        }
    }

    @Test
    void modelExecutorSavesBeforeAnyMetricEvaluation() throws Exception {
        try (Harness harness =
                modelMetricsHarness(
                        (input, stop) ->
                                RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        RecipeExecutorTestData.PARENT_ID,
                                                        RecipeExecutorTestData.square(
                                                                10, 10, 30, 30),
                                                        "Parent"))),
                        RecipeExecutorTestData.ROI_A)) {
            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.SUCCEEDED, result.outcome());
            assertTrue(result.metrics().isEmpty());
            assertTrue(result.hierarchySaved());
            assertTrue(result.scopes().getFirst().hierarchySaved());
            assertEquals(2, harness.savedImages.size());
        }
    }

    @Test
    void cancellingMetricsDoesNotSaveNormalizationOrPublishResults() throws Exception {
        try (Harness harness =
                metricsHarness(
                        (input, stop) -> {
                            throw new AssertionError("metrics cannot execute a model");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            AtomicInteger checks = new AtomicInteger();
            RunResults.ImageRunResult result = harness.execute(() -> checks.incrementAndGet() > 1);

            assertEquals(RunResults.ImageOutcomeType.CANCELLED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertTrue(result.metrics().isEmpty());
            assertEquals(0, harness.viewerSwaps.get());
            assertEquals(0, harness.materializeCalls.get());
            assertEquals(List.of(harness.live), harness.savedImages);
        }
    }

    @Test
    void metricsNormalizationSaveFailurePublishesNothingAndLeavesLiveViewerUntouched()
            throws Exception {
        try (Harness harness =
                metricsHarness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            PathObject manual =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(10, 10, 10, 10, ImagePlane.getDefaultPlane()),
                            PathClass.fromString("Manual"));
            PathObject liveManual =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(10, 10, 10, 10, ImagePlane.getDefaultPlane()),
                            PathClass.fromString("Manual"));
            liveManual.setID(manual.getID());
            harness.working.getHierarchy().addObject(manual);
            harness.live.getHierarchy().addObject(liveManual);
            harness.saveWorkingFailure.set(new IOException("metric save failed"));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertTrue(result.metrics().isEmpty());
            assertSame(harness.live, harness.active.get());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertEquals(0, harness.viewerSwaps.get());
        }
    }

    @Test
    void missingLogicalRoiBlocksMetricsWithTheTypedImageLevelFailure() throws Exception {
        try (Harness harness =
                metricsHarness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            UUID objectId = UUID.fromString("80000000-0000-4000-8000-000000000001");
            harness.working.getHierarchy().addObject(missingLogicalRoiObject(objectId));
            harness.live.getHierarchy().addObject(missingLogicalRoiObject(objectId));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertTrue(result.metrics().isEmpty());
            assertEquals(1, result.imageFailures().size());
            assertEquals(
                    RunResults.FailureType.PROVENANCE, result.imageFailures().getFirst().type());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertSame(harness.live, harness.active.get());
        }
    }

    @Test
    void malformedPresentProvenanceIsAnImageLevelProvenanceFailure() throws Exception {
        try (Harness harness =
                metricsHarness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            UUID objectId = UUID.fromString("80000000-0000-4000-8000-000000000002");
            harness.working.getHierarchy().addObject(malformedProvenanceObject(objectId, true));
            harness.live.getHierarchy().addObject(malformedProvenanceObject(objectId, true));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertTrue(result.metrics().isEmpty());
            assertEquals(1, result.imageFailures().size());
            assertEquals(
                    RunResults.FailureType.PROVENANCE, result.imageFailures().getFirst().type());
            assertTrue(result.imageFailures().getFirst().message().contains(objectId.toString()));
        }
    }

    @Test
    void duplicateAuthoritativeObjectIdsAreCorruptImageState() throws Exception {
        try (Harness harness =
                metricsHarness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            UUID duplicateId = UUID.fromString("80000000-0000-4000-8000-000000000003");
            harness.working.getHierarchy().addObject(classifiedObject(duplicateId, 10));
            harness.working.getHierarchy().addObject(classifiedObject(duplicateId, 20));
            harness.live.getHierarchy().addObject(classifiedObject(duplicateId, 10));
            harness.live.getHierarchy().addObject(classifiedObject(duplicateId, 20));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertEquals(1, result.imageFailures().size());
            assertEquals(
                    RunResults.FailureType.CORRUPT_IMAGE_STATE,
                    result.imageFailures().getFirst().type());
        }
    }

    @Test
    void parentOnlyNormalizationIgnoresExcludedMalformedProvenance() throws Exception {
        try (Harness harness =
                metricsHarness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            UUID manualId = UUID.fromString("80000000-0000-4000-8000-000000000004");
            PathObject manual = classifiedObject(manualId, 10);
            PathObject liveManual = classifiedObject(manualId, 10);
            UUID excludedId = UUID.fromString("80000000-0000-4000-8000-000000000005");
            harness.working.getHierarchy().addObject(manual);
            harness.live.getHierarchy().addObject(liveManual);
            harness.working.getHierarchy().addObject(malformedProvenanceObject(excludedId, false));
            harness.live.getHierarchy().addObject(malformedProvenanceObject(excludedId, false));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED, result.outcome(), result.toString());
            assertTrue(result.hierarchySaved());
            assertEquals(RecipeExecutorTestData.ROI_A, manual.getParent().getID().toString());
            assertTrue(result.imageFailures().isEmpty());
        }
    }

    @Test
    void metricCompilationFailureIsGlobalAndPublishesNoMetricResult() throws Exception {
        String invalidSource =
                """
                /* @histoforge-metrics
                [{"name":"object_count","label":"Object count","type":"scalar","decimals":0,"aggregation":"sum"}]
                */
                return [
                """;
        ModuleSnapshot module =
                withMetricSource(RecipeExecutorTestData.fieldMetricOnlyModule(), invalidSource);
        try (Harness harness =
                new Harness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        module,
                        new ActionSelectionCodec.Metrics(
                                new ActionSelectionCodec.RoiRegions(
                                        Set.of(
                                                RecipeExecutorTestData.ROI_A,
                                                RecipeExecutorTestData.ROI_B))),
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertTrue(result.metrics().isEmpty());
            assertEquals(1, result.imageFailures().size());
            assertEquals(RunResults.FailureType.METRICS, result.imageFailures().getFirst().type());
            assertEquals(List.of(harness.live), harness.savedImages);
        }
    }

    @Test
    void mixedMetricRegionsSaveOnlySuccessfulNormalizationAndReportPartial() throws Exception {
        String mixedSource =
                """
                /* @histoforge-metrics
                [{"name":"object_count","label":"Object count","type":"scalar","decimals":0,"aggregation":"sum"}]
                */
                if (roi.getROI().getBoundsX() >= 100) {
                    return [wrong: 1]
                }
                return [object_count: children.size()]
                """;
        ModuleSnapshot module =
                withMetricSource(RecipeExecutorTestData.fieldMetricOnlyModule(), mixedSource);
        try (Harness harness =
                new Harness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        module,
                        new ActionSelectionCodec.Metrics(
                                new ActionSelectionCodec.RoiRegions(
                                        Set.of(
                                                RecipeExecutorTestData.ROI_A,
                                                RecipeExecutorTestData.ROI_B))),
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            UUID firstId = UUID.fromString("80000000-0000-4000-8000-000000000006");
            UUID secondId = UUID.fromString("80000000-0000-4000-8000-000000000007");
            PathObject first = classifiedObject(firstId, 10);
            PathObject second = classifiedObject(secondId, 110);
            harness.working.getHierarchy().addObjects(List.of(first, second));
            harness.live
                    .getHierarchy()
                    .addObjects(
                            List.of(
                                    classifiedObject(firstId, 10),
                                    classifiedObject(secondId, 110)));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.PARTIAL, result.outcome(), result.toString());
            assertTrue(result.hierarchySaved());
            assertEquals(RecipeExecutorTestData.ROI_A, first.getParent().getID().toString());
            assertTrue(second.getParent().isRootObject());
            assertEquals(1, result.metrics().orElseThrow().regionResults().size());
            assertEquals(1, result.metrics().orElseThrow().regionFailures().size());
            assertEquals(2, harness.savedImages.size());
        }
    }

    @Test
    void coordinatedMetricsDoNotExecuteARegionWhoseModelTransactionFailed() throws Exception {
        String source =
                """
                /* @histoforge-metrics
                [{"name":"object_count","label":"Object count","type":"scalar","decimals":0,"aggregation":"sum"}]
                */
                if (roi.getROI().getBoundsX() >= 100) {
                    throw new IllegalStateException("failed model region must not execute metrics")
                }
                return [object_count: children.size()]
                """;
        ModuleSnapshot module =
                withMetricSource(RecipeExecutorTestData.fieldMetricOnlyModule(), source);
        try (Harness harness =
                new Harness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        module,
                        new ActionSelectionCodec.Metrics(
                                new ActionSelectionCodec.RoiRegions(
                                        Set.of(
                                                RecipeExecutorTestData.ROI_A,
                                                RecipeExecutorTestData.ROI_B))),
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            RunResults.FailureDetail modelFailure =
                    new RunResults.FailureDetail(
                            RunResults.FailureType.METRICS,
                            "metrics skipped because model work failed");

            RunResults.ImageRunResult result =
                    harness.executeMetrics(
                            Map.of(
                                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_B),
                                    modelFailure));

            assertEquals(RunResults.ImageOutcomeType.PARTIAL, result.outcome());
            assertEquals(
                    Set.of(new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A)),
                    result.metrics().orElseThrow().regionResults().keySet());
            assertEquals(
                    Map.of(new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_B), modelFailure),
                    result.metrics().orElseThrow().regionFailures());
        }
    }

    @Test
    void oneRoiSourceFailureDoesNotBlockOrUnsafelyDowngradeTheLaterSave() throws Exception {
        try (Harness harness =
                harness(
                        (input, stop) ->
                                RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        RecipeExecutorTestData.PARENT_ID,
                                                        RecipeExecutorTestData.square(
                                                                140, 10, 160, 30),
                                                        "Parent"))),
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            harness.failedMaterializations.add(1);

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.PARTIAL, result.outcome());
            assertTrue(result.hierarchySaved());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.FAILED,
                            RunResults.ScopeOutcomeType.PRODUCED),
                    result.scopes().stream().map(RunResults.ScopeOutcome::type).toList());
            assertEquals(
                    RunResults.FailureType.EXTRACTION,
                    result.scopes().getFirst().failure().orElseThrow().type());
            assertFalse(result.scopes().getFirst().hierarchySaved());
            assertTrue(result.scopes().getLast().hierarchySaved());
            assertEquals(2, harness.materializeCalls.get());
            assertEquals(1, harness.publishedIds.size());
            assertEquals(harness.publishedIds, harness.releasedIds);
            assertTrue(isEmptyDirectory(temporaryRoot));
        }
    }

    @Test
    void crossModeRedoSavesSuccessfulRoiAndReportsLaterFailureAsPartial() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (Harness harness =
                new Harness(
                        (input, stop) -> {
                            if (calls.getAndIncrement() == 1) {
                                throw new IllegalStateException("injected ROI B model failure");
                            }
                            return RecipeExecutorTestData.generation(
                                    input,
                                    List.of(
                                            RecipeExecutorTestData.candidate(
                                                    RecipeExecutorTestData.PARENT_ID,
                                                    RecipeExecutorTestData.square(10, 10, 30, 30),
                                                    "Parent")));
                        },
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                true,
                                Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B)),
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            PathObject liveSlide =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            PathObject workingSlide =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            harness.live.getHierarchy().addObject(liveSlide);
            harness.working.getHierarchy().addObject(workingSlide);

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(2, calls.get());
            assertEquals(RunResults.ImageOutcomeType.PARTIAL, result.outcome(), result.toString());
            assertTrue(result.hierarchySaved());
            assertEquals(
                    List.of(
                            RunResults.ScopeOutcomeType.PRODUCED,
                            RunResults.ScopeOutcomeType.FAILED),
                    result.scopes().stream().map(RunResults.ScopeOutcome::type).toList());
            assertTrue(result.scopes().getFirst().hierarchySaved());
            assertFalse(result.scopes().getLast().hierarchySaved());
            assertFalse(
                    harness.working.getHierarchy().getAllObjects(false).stream()
                            .anyMatch(
                                    object ->
                                            RecipeExecutorTestData.SLIDE_OBJECT_ID.equals(
                                                    object.getID().toString())));
            assertTrue(
                    harness.working.getHierarchy().getAllObjects(false).stream()
                            .anyMatch(
                                    object ->
                                            RecipeExecutorTestData.PARENT_ID.equals(
                                                    object.getID().toString())));
            assertEquals(2, harness.savedImages.size());
            assertSame(harness.working, harness.active.get());
        }
    }

    @Test
    void crossModeValidEmptyReportsTheSavedSlideReplacement() throws Exception {
        try (Harness harness =
                new Harness(
                        (input, stop) -> RecipeExecutorTestData.generation(input, List.of()),
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                true, Set.of(RecipeExecutorTestData.ROI_A)),
                        RecipeExecutorTestData.ROI_A)) {
            PathObject liveSlide =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            PathObject workingSlide =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Parent",
                            RecipeExecutorTestData.square(10, 10, 30, 30),
                            "parent",
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            harness.live.getHierarchy().addObject(liveSlide);
            harness.working.getHierarchy().addObject(workingSlide);

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED, result.outcome(), result.toString());
            assertTrue(result.hierarchySaved());
            assertEquals(1, result.scopes().size());
            assertEquals(
                    RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY, result.scopes().getFirst().type());
            assertTrue(result.scopes().getFirst().hierarchySaved());
            assertFalse(
                    harness.working.getHierarchy().getAllObjects(false).stream()
                            .anyMatch(
                                    object ->
                                            RecipeExecutorTestData.SLIDE_OBJECT_ID.equals(
                                                    object.getID().toString())));
            assertEquals(2, harness.savedImages.size());
            assertSame(harness.working, harness.active.get());
        }
    }

    @Test
    void oneEligibleOriginalIsPublishedOnceAndSharedAcrossFiniteTargets() throws Exception {
        List<SourceArtifactRef> observedArtifacts = new ArrayList<>();
        try (Harness harness =
                new Harness(
                        (input, stop) -> {
                            observedArtifacts.add(
                                    FiniteInferenceCodec.sourceArtifact(input.request()));
                            return RecipeExecutorTestData.generation(input, List.of());
                        },
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                false,
                                Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B)),
                        true,
                        new SourcePlanningInputs.SourcePlanningLimits(1_000_000, 4, 4),
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            RunResults.ImageRunResult result = harness.execute();

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                    result.outcome(),
                    result.toString());
            assertEquals(2, result.scopes().size());
            assertEquals(1, harness.materializeCalls.get());
            assertEquals(1, harness.publishedIds.size());
            assertEquals(harness.publishedIds, harness.releasedIds);
            assertEquals(2, observedArtifacts.size());
            assertEquals(observedArtifacts.getFirst(), observedArtifacts.getLast());
            Path original = harness.originalSource.orElseThrow();
            assertEquals(List.of(original.toAbsolutePath()), harness.materializedPaths);
            assertEquals(List.of(1, 2, 3, 4), unsignedBytes(Files.readAllBytes(original)));
            try (var paths = Files.list(temporaryRoot)) {
                assertEquals(
                        List.of(original.getFileName()), paths.map(Path::getFileName).toList());
            }
        }
    }

    @Test
    void finiteTargetsReuseCoordinatorSealedOriginalWithoutOwningItsRelease() throws Exception {
        List<SourceArtifactRef> observedArtifacts = new ArrayList<>();
        try (Harness harness =
                new Harness(
                        (input, stop) -> {
                            observedArtifacts.add(
                                    FiniteInferenceCodec.sourceArtifact(input.request()));
                            return RecipeExecutorTestData.generation(input, List.of());
                        },
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                false,
                                Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B)),
                        true,
                        new SourcePlanningInputs.SourcePlanningLimits(1_000_000, 4, 4),
                        RecipeExecutorTestData.ROI_A,
                        RecipeExecutorTestData.ROI_B)) {
            ImageActionRegistry.Reservation reservation =
                    harness.registry.reserve(harness.request.entry().getID());
            RunResults.ImageRunResult result;
            try {
                result =
                        harness.executor.execute(
                                harness.request,
                                reservation,
                                Optional.of(harness.sharedOriginal()),
                                () -> false);
            } finally {
                reservation.close();
            }

            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                    result.outcome(),
                    result.toString());
            assertEquals(0, harness.materializeCalls.get());
            assertTrue(harness.publishedIds.isEmpty());
            assertTrue(harness.releasedIds.isEmpty());
            assertEquals(2, observedArtifacts.size());
            assertEquals(observedArtifacts.getFirst(), observedArtifacts.getLast());
            assertEquals(harness.sharedOriginal().artifact(), observedArtifacts.getFirst());
        }
    }

    @Test
    void saveFailureLeavesSuccessfulModelWorkExplicitlyUnpersisted() throws Exception {
        try (Harness harness =
                harness(
                        (input, stop) ->
                                RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        RecipeExecutorTestData.PARENT_ID,
                                                        RecipeExecutorTestData.square(
                                                                10, 10, 30, 30),
                                                        "Parent"))),
                        RecipeExecutorTestData.ROI_A)) {
            harness.saveWorkingFailure.set(new IOException("injected project save failure"));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertEquals(RunResults.ScopeOutcomeType.PRODUCED, result.scopes().getFirst().type());
            assertFalse(result.scopes().getFirst().hierarchySaved());
            assertEquals(
                    RunResults.FailureType.PERSISTENCE, result.imageFailures().getFirst().type());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertSame(harness.live, harness.active.get());
            assertEquals(harness.publishedIds, harness.releasedIds);
            assertTrue(isEmptyDirectory(temporaryRoot));
        }
    }

    @Test
    void viewerFailureAfterSaveIsPartialButDoesNotErasePersistenceTruth() throws Exception {
        try (Harness harness =
                harness(
                        (input, stop) ->
                                RecipeExecutorTestData.generation(
                                        input,
                                        List.of(
                                                RecipeExecutorTestData.candidate(
                                                        RecipeExecutorTestData.PARENT_ID,
                                                        RecipeExecutorTestData.square(
                                                                10, 10, 30, 30),
                                                        "Parent"))),
                        RecipeExecutorTestData.ROI_A)) {
            harness.viewerFailure.set(new IOException("injected viewer refresh failure"));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.PARTIAL, result.outcome());
            assertTrue(result.hierarchySaved());
            assertTrue(result.scopes().getFirst().hierarchySaved());
            assertEquals(
                    RunResults.FailureType.VIEWER_REFRESH,
                    result.imageFailures().getFirst().type());
            assertEquals(2, harness.savedImages.size());
            assertSame(harness.live, harness.active.get());
            assertEquals(harness.publishedIds, harness.releasedIds);
            assertTrue(isEmptyDirectory(temporaryRoot));
        }
    }

    @Test
    void cancellationAfterSourcePublicationReleasesTheReservationWithoutSaving() throws Exception {
        try (Harness harness =
                harness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            harness.cancelOnPublish.set(true);

            RunResults.ImageRunResult result = harness.execute(harness.cancelRequested::get);

            assertEquals(RunResults.ImageOutcomeType.CANCELLED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertEquals(RunResults.ScopeOutcomeType.CANCELLED, result.scopes().getFirst().type());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertEquals(harness.publishedIds, harness.releasedIds);
            assertTrue(isEmptyDirectory(temporaryRoot));
        }
    }

    @Test
    void ambiguousSourcePublicationFailureReleasesTheAttemptedReservation() throws Exception {
        try (Harness harness =
                harness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            harness.publicationFailure.set(new IOException("injected ambiguous publication"));

            RunResults.ImageRunResult result = harness.execute();

            assertEquals(RunResults.ImageOutcomeType.FAILED, result.outcome());
            assertFalse(result.hierarchySaved());
            assertEquals(RunResults.ScopeOutcomeType.FAILED, result.scopes().getFirst().type());
            assertEquals(
                    RunResults.FailureType.SUBMISSION,
                    result.scopes().getFirst().failure().orElseThrow().type());
            assertEquals(List.of(harness.live), harness.savedImages);
            assertTrue(harness.publishedIds.isEmpty());
            assertEquals(harness.attemptedPublicationIds, harness.releasedIds);
            assertTrue(isEmptyDirectory(temporaryRoot));
        }
    }

    @Test
    void capturedFiniteTargetGeometryChangeReturnsARegionConflictBeforeSourceWork()
            throws Exception {
        try (Harness harness =
                harness(
                        (input, stop) -> {
                            throw new AssertionError("model execution must not start");
                        },
                        RecipeExecutorTestData.ROI_A)) {
            ImageActionSubsetPlanner.Subset captured = harness.captureSubset();
            PathROIObject roi =
                    (PathROIObject)
                            harness.working.getHierarchy().getFlattenedObjectList(null).stream()
                                    .filter(
                                            object ->
                                                    RecipeExecutorTestData.ROI_A.equals(
                                                            object.getID().toString()))
                                    .findFirst()
                                    .orElseThrow();
            roi.setROI(ROIs.createRectangleROI(0, 0, 90, 100, ImagePlane.getDefaultPlane()));

            RunResults.ImageRunResult result = harness.execute(captured);

            assertEquals(RunResults.ImageOutcomeType.CONFLICT, result.outcome());
            assertEquals(1, result.scopes().size());
            assertEquals(RunResults.ScopeOutcomeType.FAILED, result.scopes().getFirst().type());
            assertEquals(
                    RunResults.FailureType.REVISION_CONFLICT,
                    result.scopes().getFirst().failure().orElseThrow().type());
            assertEquals(0, harness.materializeCalls.get());
        }
    }

    private Harness harness(RecipeExecutor.InputRunner runner, String... roiIds) throws Exception {
        return new Harness(
                runner,
                RecipeExecutorTestData.fieldModule(),
                RecipeExecutorTestData.allModels(false, Set.of(roiIds)),
                roiIds);
    }

    private Harness metricsHarness(RecipeExecutor.InputRunner runner, String... roiIds)
            throws Exception {
        return new Harness(
                runner,
                RecipeExecutorTestData.fieldMetricOnlyModule(),
                new ActionSelectionCodec.Metrics(
                        new ActionSelectionCodec.RoiRegions(Set.of(roiIds))),
                roiIds);
    }

    private Harness modelMetricsHarness(RecipeExecutor.InputRunner runner, String... roiIds)
            throws Exception {
        return new Harness(
                runner,
                RecipeExecutorTestData.fieldMetricModule(),
                RecipeExecutorTestData.allModels(false, Set.of(roiIds)),
                roiIds);
    }

    private final class Harness implements AutoCloseable {

        private final QuPathGUI qupath = mock(QuPathGUI.class);
        private final Project<BufferedImage> project = mock(Project.class);
        private final ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        private final QuPathViewer viewer = mock(QuPathViewer.class);
        private final ImageData<BufferedImage> live;
        private final ImageData<BufferedImage> working;
        private final AtomicReference<ImageData<BufferedImage>> active = new AtomicReference<>();
        private final SourceArtifactWriter writer = mock(SourceArtifactWriter.class);
        private final ModelGatewayExecutor gateway = mock(ModelGatewayExecutor.class);
        private final PreparedSourceArtifact prepared = mock(PreparedSourceArtifact.class);
        private final AtomicInteger materializeCalls = new AtomicInteger();
        private final AtomicInteger viewerSwaps = new AtomicInteger();
        private final AtomicReference<IOException> saveWorkingFailure = new AtomicReference<>();
        private final AtomicReference<Throwable> viewerFailure = new AtomicReference<>();
        private final AtomicReference<IOException> publicationFailure = new AtomicReference<>();
        private final AtomicBoolean cancelOnPublish = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final Set<Integer> failedMaterializations = new HashSet<>();
        private final List<ImageData<BufferedImage>> savedImages = new ArrayList<>();
        private final List<String> attemptedPublicationIds = new ArrayList<>();
        private final List<String> publishedIds = new ArrayList<>();
        private final List<String> releasedIds = new ArrayList<>();
        private final List<Path> materializedPaths = new ArrayList<>();
        private final Optional<Path> originalSource;
        private final ImageSourceCapture.CapturedSource capturedSource;
        private final SourcePlanningInputs.SourcePlanningLimits sourcePlanningLimits;
        private final AtomicReference<String> currentArtifactId = new AtomicReference<>();
        private final AtomicReference<SourceArtifactCodec.SourceContainer> currentContainer =
                new AtomicReference<>();
        private final ImageActionRegistry registry;
        private final ImageActionExecutor executor;
        private final ImageMetricsExecutor metricsExecutor;
        private final ImageActionExecutor.Request request;

        @SuppressWarnings("unchecked")
        private Harness(
                RecipeExecutor.InputRunner runner,
                ModuleSnapshot module,
                ActionSelectionCodec.ImageAction action,
                String... roiIds)
                throws Exception {
            this(
                    runner,
                    module,
                    action,
                    false,
                    RecipeExecutorTestData.sourcePlanningLimits(),
                    roiIds);
        }

        @SuppressWarnings("unchecked")
        private Harness(
                RecipeExecutor.InputRunner runner,
                ModuleSnapshot module,
                ActionSelectionCodec.ImageAction action,
                boolean useOriginal,
                SourcePlanningInputs.SourcePlanningLimits sourcePlanningLimits,
                String... roiIds)
                throws Exception {
            this.sourcePlanningLimits = sourcePlanningLimits;
            live = RecipeExecutorTestData.imageData("image-action", roiIds);
            working = RecipeExecutorTestData.imageData("image-action", roiIds);
            if (useOriginal) {
                Path sourcePath = temporaryRoot.resolve("unchanged-original.tif");
                Files.write(sourcePath, new byte[] {1, 2, 3, 4});
                originalSource = Optional.of(sourcePath.toRealPath());
                capturedSource = capturedSource(sourcePath);
            } else {
                originalSource = Optional.empty();
                capturedSource = capturedSource();
            }
            active.set(live);
            when(entry.getID()).thenReturn(RecipeExecutorTestData.IMAGE_ID);
            when(qupath.getProject()).thenReturn(project);
            when(viewer.getImageData()).thenAnswer(ignored -> active.get());
            when(project.getEntry(any(ImageData.class))).thenReturn(entry);
            when(entry.readImageData()).thenReturn(working);
            doAnswer(
                            invocation -> {
                                ImageData<BufferedImage> image = invocation.getArgument(0);
                                IOException failure = saveWorkingFailure.get();
                                if (image == working && failure != null) {
                                    throw failure;
                                }
                                savedImages.add(image);
                                return null;
                            })
                    .when(entry)
                    .saveImageData(any());
            doAnswer(
                            invocation -> {
                                viewerSwaps.incrementAndGet();
                                Throwable failure = viewerFailure.get();
                                if (failure != null) {
                                    throw failure;
                                }
                                active.set(invocation.getArgument(0));
                                return null;
                            })
                    .when(viewer)
                    .setImageData(any());

            int baseWorkingObjectCount = working.getHierarchy().getFlattenedObjectList(null).size();
            ImageLifecycle lifecycle =
                    new ImageLifecycle(
                            qupath,
                            () -> List.of(viewer),
                            Runnable::run,
                            (imageId, sourceDigest, image, limits) ->
                                    new RasterInputCodec.ImageRevisionToken(
                                            imageId,
                                            sourceDigest,
                                            semanticDigest(image, baseWorkingObjectCount)),
                            ignored -> capturedSource);
            AtomicInteger artifactSequence = new AtomicInteger();
            when(writer.materialize(any(), any(), any(), any(), any(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                int call = materializeCalls.incrementAndGet();
                                if (failedMaterializations.contains(call)) {
                                    throw new IOException(
                                            "injected source materialization failure");
                                }
                                SourceInputPlan.TargetSourcePlan source = invocation.getArgument(0);
                                materializedPaths.add(invocation.getArgument(2));
                                currentArtifactId.set(invocation.getArgument(5));
                                currentContainer.set(
                                        source.artifact()
                                                        instanceof
                                                        SourceInputPlan.OriginalArtifactPlan
                                                                original
                                                ? original.container()
                                                : ((SourceInputPlan.DerivedArtifactPlan)
                                                                source.artifact())
                                                        .container());
                                return prepared;
                            });
            when(gateway.publishSource(any(), any(), any()))
                    .thenAnswer(
                            ignored -> {
                                String artifactId = currentArtifactId.get();
                                attemptedPublicationIds.add(artifactId);
                                IOException failure = publicationFailure.get();
                                if (failure != null) {
                                    throw failure;
                                }
                                publishedIds.add(artifactId);
                                if (cancelOnPublish.get()) {
                                    cancelRequested.set(true);
                                }
                                return sealed(artifactId, currentContainer.get());
                            });
            doAnswer(
                            invocation -> {
                                releasedIds.add(invocation.getArgument(0));
                                return null;
                            })
                    .when(gateway)
                    .releaseSource(any());

            RecipeExecutor recipeExecutor =
                    new RecipeExecutor(
                            runner,
                            new CanonicalUuidSupplier("70000000-0000-4000-8000-"),
                            Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC));
            FiniteSourceProvisioner sourceProvisioner =
                    new FiniteSourceProvisioner(
                            gateway,
                            writer,
                            () ->
                                    "60000000-0000-4000-8000-%012d"
                                            .formatted(artifactSequence.incrementAndGet()),
                            temporaryRoot);
            registry = new ImageActionRegistry();
            executor =
                    new ImageActionExecutor(
                            registry,
                            lifecycle,
                            sourcePlanningLimits,
                            recipeExecutor,
                            sourceProvisioner);
            metricsExecutor = new ImageMetricsExecutor(registry, lifecycle, new MetricsEngine());
            request =
                    new ImageActionExecutor.Request(
                            RecipeExecutorTestData.ACTION_ID,
                            RecipeExecutorTestData.clientBuild(1_000_000),
                            entry,
                            module,
                            action);
        }

        private SealedOriginalSource sharedOriginal() {
            String artifactId = "61111111-1111-4111-8111-111111111111";
            SourceIdentityCodec.ImageSourceIdentity source = capturedSource.identity();
            SourceArtifactCodec.SourceArtifactStartRequest start =
                    new SourceArtifactCodec.SourceArtifactStartRequest(
                            SourceArtifactCodec.SCHEMA_VERSION,
                            request.actionId(),
                            artifactId,
                            SourceArtifactCodec.SourceContainer.SLIDE_FILE,
                            4,
                            4,
                            new SourceArtifactCodec.OriginalWsi(
                                    source,
                                    0,
                                    new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0),
                                    originalSource.orElseThrow().getFileName().toString(),
                                    readerSettings(source, 1),
                                    List.of()),
                            List.of(
                                    new SourceArtifactCodec.SourceLevelRequirement(
                                            0, 0, 256, 128)));
            String startDigest = SourceArtifactCodec.startRequestDigest(start);
            SourceArtifactRef artifact =
                    new SourceArtifactRef(
                            artifactId,
                            request.actionId(),
                            startDigest,
                            "2".repeat(64),
                            "3".repeat(64),
                            SourceArtifactCodec.SourceContainer.SLIDE_FILE);
            return new SealedOriginalSource(
                    request.actionId(),
                    request.entry().getID(),
                    request.clientBuild(),
                    request.module().deploymentId(),
                    request.module().module(),
                    source,
                    artifact,
                    start);
        }

        private RunResults.ImageRunResult execute() {
            return execute(() -> false);
        }

        private RunResults.ImageRunResult execute(BooleanSupplier shouldStop) {
            return request.action() instanceof ActionSelectionCodec.Metrics
                    ? metricsExecutor.execute(request, shouldStop)
                    : executor.execute(request, shouldStop);
        }

        private ImageActionSubsetPlanner.Subset captureSubset() {
            ImageExecutionPlan plan =
                    ImageActionPlanner.plan(
                            request.actionId(),
                            request.clientBuild(),
                            RecipeExecutorTestData.IMAGE_ID,
                            request.module(),
                            request.action(),
                            working.getHierarchy(),
                            working.getServer().getWidth(),
                            working.getServer().getHeight());
            RasterInputCodec.ImageRevisionToken revision =
                    new RasterInputCodec.ImageRevisionToken(
                            RecipeExecutorTestData.IMAGE_ID,
                            SourceIdentityCodec.imageSourceIdentityDigest(
                                    capturedSource.identity()),
                            "b".repeat(64));
            SourceInputPlan sourcePlan =
                    RecipeSourcePlanner.plan(
                            plan,
                            capturedSource.bind(revision),
                            working.getHierarchy(),
                            capturedSource.eligibleOriginal(),
                            sourcePlanningLimits);
            return ImageActionSubsetPlanner.subset(
                    plan,
                    sourcePlan,
                    plan.targetRegions(),
                    working.getHierarchy(),
                    working.getServer().getWidth(),
                    working.getServer().getHeight());
        }

        private RunResults.ImageRunResult execute(ImageActionSubsetPlanner.Subset captured)
                throws InterruptedException {
            try (ImageActionRegistry.Reservation reservation =
                    registry.reserve(RecipeExecutorTestData.IMAGE_ID)) {
                return executor.execute(
                        request, reservation, captured, Optional.empty(), () -> false);
            }
        }

        private RunResults.ImageRunResult executeMetrics(
                Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> prefailedRegions)
                throws InterruptedException {
            try (ImageActionRegistry.Reservation reservation =
                    registry.reserve(RecipeExecutorTestData.IMAGE_ID)) {
                return metricsExecutor.execute(request, reservation, prefailedRegions, () -> false);
            }
        }

        @Override
        public void close() {
            closeQuietly(live);
            closeQuietly(working);
        }
    }

    private static ImageSourceCapture.CapturedSource capturedSource() {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "image-action.fixture",
                        "d".repeat(64),
                        Optional.empty(),
                        256,
                        128,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(1, 1));
        var transform = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        return new ImageSourceCapture.CapturedSource(
                identity,
                0,
                transform,
                List.of(
                        new SourcePlanningInputs.SourceLevel(
                                0, new SourceViewGeometry.Dimensions(256, 128), transform)),
                expected -> {});
    }

    private static ImageSourceCapture.CapturedSource capturedSource(Path original)
            throws IOException {
        Path file = original.toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        ImageSourceCapture.OPENSLIDE_LOCAL_ADAPTER_ID,
                        "d".repeat(64),
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        attributes.size(),
                                        attributes.lastModifiedTime().toMillis(),
                                        Optional.ofNullable(attributes.fileKey())
                                                .map(Object::toString))),
                        256,
                        128,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(1, 1));
        var transform = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        return new ImageSourceCapture.CapturedSource(
                identity,
                0,
                transform,
                List.of(
                        new SourcePlanningInputs.SourceLevel(
                                0, new SourceViewGeometry.Dimensions(256, 128), transform)),
                expected -> {},
                Optional.of(localSource(file, readerSettings(identity, 1))));
    }

    private static List<Integer> unsignedBytes(byte[] bytes) {
        List<Integer> result = new ArrayList<>(bytes.length);
        for (byte value : bytes) {
            result.add(Byte.toUnsignedInt(value));
        }
        return result;
    }

    private static SourceArtifactSealed sealed(
            String artifactId, SourceArtifactCodec.SourceContainer container) {
        return new SourceArtifactSealed(
                SourceArtifactCodec.SCHEMA_VERSION,
                new SourceArtifactRef(
                        artifactId,
                        RecipeExecutorTestData.ACTION_ID,
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        container),
                Instant.parse("2026-08-16T13:00:00Z"));
    }

    private static String semanticDigest(ImageData<?> image, int baseWorkingObjectCount) {
        boolean normalizedManual =
                image.getHierarchy().getFlattenedObjectList(null).stream()
                        .anyMatch(
                                object ->
                                        object.getPathClass() != null
                                                && "Manual".equals(object.getPathClass().toString())
                                                && object.getParent() != null
                                                && !object.getParent().isRootObject());
        if (normalizedManual) {
            return "f".repeat(64);
        }
        return image.getHierarchy().getFlattenedObjectList(null).size() > baseWorkingObjectCount
                ? "e".repeat(64)
                : "b".repeat(64);
    }

    private static PathObject missingLogicalRoiObject(UUID objectId) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(10, 10, 5, 5, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Corrupt attributed object"));
        object.setID(objectId);
        ProvenanceCodec.writeMetadata(
                object.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(
                                RecipeExecutorTestData.producer("parent"),
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Roi(
                                                "99999999-9999-4999-8999-999999999999"))),
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        return object;
    }

    private static PathObject classifiedObject(UUID objectId, double x) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(x, 10, 5, 5, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Manual"));
        object.setID(objectId);
        return object;
    }

    private static PathObject malformedProvenanceObject(UUID objectId, boolean classified) {
        PathObject object =
                classified
                        ? classifiedObject(objectId, 10)
                        : PathObjects.createDetectionObject(
                                ROIs.createRectangleROI(
                                        150, 10, 5, 5, ImagePlane.getDefaultPlane()));
        object.setID(objectId);
        object.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "{}");
        return object;
    }

    private static ModuleSnapshot withMetricSource(ModuleSnapshot module, String source) {
        ModuleSnapshot.ScriptSnapshot script =
                new ModuleSnapshot.ScriptSnapshot(
                        "metrics.groovy",
                        source,
                        CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source)));
        com.google.gson.JsonObject scriptDigests = new com.google.gson.JsonObject();
        scriptDigests.addProperty(script.filename(), script.digest());
        return new ModuleSnapshot(
                module.deploymentId(),
                module.module(),
                module.exactRecipe(),
                CanonicalCodec.canonicalDigest(scriptDigests),
                module.activatedModels(),
                module.scripts(),
                Optional.of(
                        new ModuleSnapshot.MetricScriptSnapshot(
                                script, module.metrics().orElseThrow().schema())));
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.findAny().isEmpty();
        }
    }

    private static void closeQuietly(ImageData<?> image) {
        try {
            image.close();
        } catch (Exception ignored) {
            // Test cleanup only; lifecycle assertions own failures in the exercised path.
        }
    }

    private static final class CanonicalUuidSupplier
            implements java.util.function.Supplier<String> {

        private final String prefix;
        private int index;

        private CanonicalUuidSupplier(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String get() {
            return prefix + "%012d".formatted(++index);
        }
    }
}
