package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.*;
import qupath.ext.histoforge.data.MetricImageResolver;
import qupath.ext.histoforge.data.MetricTypes.Scalar;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.export.ExportTypes;
import qupath.ext.histoforge.export.MetricsExporter;
import qupath.ext.histoforge.roi.AnnotationRoiSelection;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.CandidatePipeline;

/** Real QuPath files and source crops; only network/model responses are substituted. */
class AnnotationRoiWorkflowTest {
    @TempDir Path directory;

    @Test
    void locksSaveBeforeUploadThenIndependentClippedOutputsSurviveReopen() throws Exception {
        var harness = new AnnotationRoiWorkflowFixture(directory);
        AtomicInteger modelCalls = new AtomicInteger();
        var executor =
                harness.executor(
                        (input, stop) -> {
                            modelCalls.incrementAndGet();
                            assertTrue(
                                    input.existingObjects().isEmpty(),
                                    "The selected outline is not a biological object");
                            return rectangularPrediction(input);
                        });
        var result = executor.execute(harness.request, () -> false);
        assertEquals(RunResults.ImageOutcomeType.SUCCEEDED, result.outcome(), result.toString());
        assertTrue(result.hierarchySaved());
        assertEquals(2, modelCalls.get());
        assertEquals(2, harness.publications.get());
        verify(harness.lifecycle, times(2)).commit(any()); // lock preparation, then model results

        try (var reopened = harness.entry.readImageData()) {
            harness.assertBoundaries(reopened, true);
            var predictions =
                    reopened.getHierarchy().getAllObjects(false).stream()
                            .filter(
                                    object ->
                                            object.getMetadata()
                                                    .containsKey(
                                                            HistoforgePersistenceKeys
                                                                    .OBJECT_PROVENANCE))
                            .toList();
            assertEquals(2, predictions.size());
            for (var object : predictions) {
                var provenance = ProvenanceCodec.readMetadata(object.getMetadata()).orElseThrow();
                var owner =
                        (ExecutionScopeCodec.Roi)
                                ExecutionScopeCodec.regionOf(provenance.output().scope());
                var boundary =
                        RoiRegistry.fromHierarchy(reopened.getHierarchy())
                                .findRoi(owner.roiId())
                                .orElseThrow();
                assertSame(boundary, object.getParent());
                assertTrue(
                        object.getROI().getGeometry().equalsTopo(boundary.getROI().getGeometry()));
            }
            assertEquals(
                    Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B),
                    AnnotationRoiSelection.ids(reopened.getHierarchy(), "Parent"));
        }

        var second = executor.execute(harness.request, () -> false);
        assertEquals(
                RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE,
                second.outcome(),
                second.toString());
        assertEquals(2, modelCalls.get(), "Fill is presence based");
        verify(harness.lifecycle, times(2)).commit(any());

        // Native Unlock persists normally. Metrics must neither relock nor count either outline.
        try (var image = harness.entry.readImageData()) {
            image.getHierarchy().getAnnotationObjects().forEach(object -> object.setLocked(false));
            harness.entry.saveImageData(image);
        }
        var metricsRequest =
                new ImageActionExecutor.Request(
                        harness.request.actionId(),
                        harness.request.clientBuild(),
                        harness.entry,
                        RecipeExecutorTestData.fieldMetricOnlyModule(),
                        new ActionSelectionCodec.Metrics(
                                new ActionSelectionCodec.RoiRegions(
                                        Set.of(RecipeExecutorTestData.ROI_A))),
                        Optional.of("Parent"));
        var metrics =
                new ImageMetricsExecutor(harness.registry, harness.lifecycle, new MetricsEngine())
                        .execute(metricsRequest, () -> false);
        assertTrue(metrics.imageFailures().isEmpty(), metrics.toString());
        assertEquals(2, metrics.metrics().orElseThrow().regionResults().size());
        for (var region : metrics.metrics().orElseThrow().regionResults().values()) {
            assertEquals(new Scalar(1), region.values().get("object_count"));
        }
        try (var image = harness.entry.readImageData()) {
            harness.assertBoundaries(image, false);
        }

        // Export must resolve the same current class, not the deliberately stale preview IDs.
        var export =
                new ExportTypes.ExportRequest(
                        UUID.randomUUID(),
                        directory.resolve("metrics.xlsx"),
                        ExportTypes.ExportOverwritePolicy.FAIL_IF_EXISTS,
                        List.of(
                                new ExportTypes.ExportImageSelection(
                                        harness.entry.getID(),
                                        List.of(
                                                new ExportTypes.ExportModuleSelection(
                                                        metricsRequest.module(),
                                                        ((ActionSelectionCodec.Metrics)
                                                                        metricsRequest.action())
                                                                .targets(),
                                                        Optional.of("Parent"))),
                                        Map.of())));
        var artifact =
                new MetricsExporter(
                                new MetricImageResolver(
                                        harness.registry, harness.lifecycle, new MetricsEngine()))
                        .export(
                                harness.project,
                                harness.request.clientBuild(),
                                export,
                                () -> false);
        assertTrue(Files.size(artifact.destination()) > 0);
        assertEquals(
                2, artifact.manifest().images().getFirst().modules().getFirst().regions().size());
        try (var image = harness.entry.readImageData()) {
            harness.assertBoundaries(image, false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inferenceFailureOrCancellationDoesNotUndoSavedLocks(boolean cancel) throws Exception {
        var harness = new AnnotationRoiWorkflowFixture(directory);
        var result =
                harness.executor(
                                (input, stop) -> {
                                    if (cancel)
                                        throw new CancellationException("test cancellation");
                                    throw new IOException("test model failure");
                                })
                        .execute(harness.request, () -> false);
        assertEquals(
                cancel ? RunResults.ImageOutcomeType.CANCELLED : RunResults.ImageOutcomeType.FAILED,
                result.outcome());
        try (var image = harness.entry.readImageData()) {
            harness.assertBoundaries(image, true);
            assertEquals(2, image.getHierarchy().getAnnotationObjects().size());
        }
    }

    private static CandidateGeneration rectangularPrediction(
            FiniteModelInputExecutor.PreparedInput input) {
        var request = input.request();
        var raster = request.rasterInput().frame().transform().raster();
        var prediction =
                new InferenceFeatureCodec.FeatureCollection(
                        List.of(
                                new InferenceFeatureCodec.Feature(
                                        "1".repeat(64),
                                        RecipeExecutorTestData.square(
                                                0, 0, raster.width(), raster.height()),
                                        "Parent")));
        var meta =
                new FiniteInferenceCodec.BackendResultMeta(
                        FiniteInferenceCodec.SCHEMA_VERSION,
                        request.actionId(),
                        request.outputGenerationId(),
                        "call-fixture",
                        request.inputId(),
                        request.producer(),
                        request.expectedBackend(),
                        FiniteInferenceCodec.buildBackendInputIdentity(request.rasterInput()),
                        FiniteInferenceCodec.CoordinateSpace.RASTER_LOCAL);
        return CandidatePipeline.generateFinite(
                request,
                new FiniteInferenceCodec.InferenceResult(meta, prediction),
                input.plannedInput(),
                new BackendExecutionCodec.FiniteCall("call-fixture"),
                input.reservation(),
                input.existingObjects());
    }
}
