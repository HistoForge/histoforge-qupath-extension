package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.client.FiniteRecipeGatewayStub;
import qupath.ext.histoforge.contract.*;
import qupath.ext.histoforge.data.MetricTypes.Scalar;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

/** Native recipe/save tests with real localhost finite HTTP; source service and GPU are stubbed. */
class FiniteRecipeWorkflowTest {
    @TempDir Path directory;

    @Test
    void finitePointRecipePreservesClassesScoresAndEmptyRedoThroughPublicSave() throws Exception {
        var empty = new AtomicBoolean();
        try (var server =
                        new FiniteRecipeGatewayStub(
                                directory,
                                RecipeExecutorTestData.DEPLOYMENT_ID,
                                (request, callId) -> {
                                    var features =
                                            empty.get()
                                                    ? List.<InferenceFeatureCodec.Feature>of()
                                                    : List.of(
                                                            point(
                                                                    "Mitotic figure",
                                                                    10.5,
                                                                    10.5,
                                                                    0.75),
                                                            point("Other", 12.5, 12.5, 0.875));
                                    var meta =
                                            new FiniteInferenceCodec.BackendResultMeta(
                                                    FiniteInferenceCodec.SCHEMA_VERSION,
                                                    request.actionId(),
                                                    request.outputGenerationId(),
                                                    callId,
                                                    request.inputId(),
                                                    request.producer(),
                                                    request.expectedBackend(),
                                                    FiniteInferenceCodec.inputIdentity(request),
                                                    FiniteInferenceCodec.CoordinateSpace
                                                            .RASTER_LOCAL);
                                    return FiniteInferenceCodec.toJson(
                                            new FiniteInferenceCodec.InferenceResult(
                                                    meta,
                                                    new InferenceFeatureCodec.FeatureCollection(
                                                            features.stream()
                                                                    .sorted(
                                                                            Comparator.comparing(
                                                                                    InferenceFeatureCodec
                                                                                                    .Feature
                                                                                            ::id))
                                                                    .toList())));
                                });
                var gateway = spy(server.executor())) {
            var fixture = new AnnotationRoiWorkflowFixture(directory, gateway);
            var retained = seedRetainedObjects(fixture);
            var module = pointModule();
            var initial =
                    new ImageActionExecutor.Request(
                            fixture.request.actionId(),
                            fixture.request.clientBuild(),
                            fixture.entry,
                            module,
                            fixture.request.action(),
                            fixture.request.annotationClass());
            var executor = fixture.executor();
            var result = executor.execute(initial, () -> false);
            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED, result.outcome(), result.toString());
            assertTrue(result.hierarchySaved());
            assertEquals(2, server.requests().size());
            try (var saved = fixture.entry.readImageData()) {
                assertRetainedObjects(saved, retained);
                var points =
                        saved.getHierarchy().getAllObjects(false).stream()
                                .filter(object -> object.getROI().isPoint())
                                .toList();
                assertEquals(4, points.size());
                for (var object : points) {
                    assertEquals(1, object.getROI().getNumPoints());
                    String pathClass = object.getPathClass().toString();
                    assertTrue(Set.of("Mitotic figure", "Other").contains(pathClass));
                    assertEquals(
                            pathClass.equals("Other") ? 0.875 : 0.75,
                            object.getMeasurementList()
                                    .get(InferenceFeatureCodec.DETECTION_SCORE_MEASUREMENT));
                    var provenance =
                            ProvenanceCodec.readMetadata(object.getMetadata()).orElseThrow();
                    assertEquals("parent", provenance.output().producer().stepId());
                    assertTrue(
                            Set.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B)
                                    .contains(object.getParent().getID().toString()));
                }
            }
            var filled = executor.execute(initial, () -> false);
            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE, filled.outcome());
            assertEquals(2, server.requests().size(), "Fill must retain existing point results");
            empty.set(true);
            var redo =
                    new ImageActionExecutor.Request(
                            initial.actionId(),
                            initial.clientBuild(),
                            initial.entry(),
                            module,
                            RecipeExecutorTestData.allModels(
                                    true, Set.of(RecipeExecutorTestData.ROI_A)),
                            initial.annotationClass());
            var replaced = executor.execute(redo, () -> false);
            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED, replaced.outcome(), replaced.toString());
            assertTrue(replaced.hierarchySaved());
            assertEquals(4, server.requests().size());
            try (var saved = fixture.entry.readImageData()) {
                assertRetainedObjects(saved, retained);
                assertTrue(
                        saved.getHierarchy().getAllObjects(false).stream()
                                .noneMatch(object -> object.getROI().isPoint()));
            }
            server.requireNoFailure();
        }
    }

    private static ModuleSnapshot pointModule() {
        var base =
                FiniteCapacityTestData.withCandidateLimits(
                        RecipeExecutorTestData.fieldModule(), 1.0);
        var model = base.activatedModels().get("parent");
        var output =
                new ModelOutputCodec.ModelOutput(
                        "point_detection",
                        List.of(),
                        List.of(
                                new ModelOutputCodec.ClassChannel(0, "emitted", "Mitotic figure"),
                                new ModelOutputCodec.ClassChannel(1, "emitted", "Other")));
        var capabilities = RunnerCapabilitiesCodec.toJson(model.capabilities());
        var types = new JsonArray();
        types.add("POINT_DETECTION");
        capabilities.add("output_types", types);
        var pointModel =
                new ModuleSnapshot.ModelSnapshot(
                        model.architecture(),
                        model.gpu(),
                        model.modelConfigDigest(),
                        model.input(),
                        output,
                        RunnerCapabilitiesCodec.parse(capabilities),
                        model.largeRegionProfile(),
                        model.artifactDigests());
        var recipe = base.exactRecipe();
        recipe.addProperty("schemaVersion", 4);
        recipe.getAsJsonObject("models")
                .getAsJsonObject("parent")
                .add("output", ModelOutputCodec.toJson(output));
        return new ModuleSnapshot(
                base.deploymentId(),
                base.module(),
                recipe,
                base.scriptSetDigest(),
                Map.of("parent", pointModel),
                base.scripts(),
                base.metrics());
    }

    private static InferenceFeatureCodec.Feature point(
            String pathClass, double x, double y, double score) {
        var xy = new JsonArray();
        xy.add(x);
        xy.add(y);
        var identity = new JsonArray();
        identity.add(pathClass);
        identity.add(xy);
        identity.add(score);
        identity.add(0);
        return new InferenceFeatureCodec.Feature(
                CanonicalCodec.canonicalDigest(identity),
                new org.locationtech.jts.geom.GeometryFactory()
                        .createPoint(new org.locationtech.jts.geom.CoordinateXY(x, y)),
                pathClass,
                OptionalDouble.of(score));
    }

    @Test
    void finiteHttpRunsScriptBeforeChildThenSavesMetricsAndPreservesUnrelatedObjects()
            throws Exception {
        try (var server =
                        new FiniteRecipeGatewayStub(
                                directory,
                                RecipeExecutorTestData.DEPLOYMENT_ID,
                                FiniteRecipeWorkflowTest::prediction);
                var gateway = spy(server.executor())) {
            var fixture = new AnnotationRoiWorkflowFixture(directory, gateway);
            var retained = seedRetainedObjects(fixture);
            var request = request(fixture, false);
            var executor = fixture.executor();

            var result = executor.execute(request, () -> false);
            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED, result.outcome(), result.toString());
            assertTrue(result.hierarchySaved());
            assertEquals(List.of("parent", "child", "parent", "child"), modelOrder(server));
            assertEquals(2, fixture.publications.get());
            for (var submitted : server.requests()) {
                var budget = submitted.resultBudget();
                assertEquals(
                        FiniteCapacityTestData.RESULT.maxTransferredBytes(),
                        budget.maxTransferredBytes());
                assertEquals(
                        FiniteCapacityTestData.RESULT.maxDecodedBytes(), budget.maxDecodedBytes());
                assertEquals(
                        FiniteCapacityTestData.RESULT.maxRecordBytes(), budget.maxRecordBytes());
                assertEquals(FiniteCapacityTestData.RESULT.maxFeatures(), budget.maxFeatures());
                assertEquals(FiniteCapacityTestData.RESULT.maxVertices(), budget.maxVertices());
            }
            try (var reopened = fixture.entry.readImageData()) {
                fixture.assertBoundaries(reopened, true);
                assertRetainedObjects(reopened, retained);
                assertScriptedHierarchy(reopened);
            }

            var filled = executor.execute(request, () -> false);
            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE, filled.outcome());
            assertEquals(4, server.requests().size(), "Fill must not infer again");
            verify(fixture.lifecycle, times(2)).commit(any());

            var metricsRequest =
                    new ImageActionExecutor.Request(
                            request.actionId(),
                            request.clientBuild(),
                            request.entry(),
                            RecipeExecutorTestData.withActivatedModels(request.module()),
                            new ActionSelectionCodec.Metrics(
                                    new ActionSelectionCodec.RoiRegions(
                                            Set.of(RecipeExecutorTestData.ROI_A))),
                            request.annotationClass());
            var metrics =
                    new ImageMetricsExecutor(
                                    fixture.registry, fixture.lifecycle, new MetricsEngine())
                            .execute(metricsRequest, () -> false);
            assertTrue(metrics.imageFailures().isEmpty(), metrics.toString());
            var values = metrics.metrics().orElseThrow().regionResults();
            assertEquals(2, values.size());
            assertEquals(
                    new Scalar(5),
                    values.get(new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A))
                            .values()
                            .get("object_count"));
            assertEquals(
                    new Scalar(3),
                    values.get(new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_B))
                            .values()
                            .get("object_count"));
            try (var reopened = fixture.entry.readImageData()) {
                fixture.assertBoundaries(reopened, true);
                assertRetainedObjects(reopened, retained);
                assertScriptedHierarchy(reopened);
            }
            server.requireNoFailure();
        }
    }

    @Test
    void invalidLastChildFeatureDuringRedoLeavesTheSavedImageUnchanged() throws Exception {
        var rejectChild = new AtomicBoolean();
        try (var server =
                        new FiniteRecipeGatewayStub(
                                directory,
                                RecipeExecutorTestData.DEPLOYMENT_ID,
                                (request, callId) -> {
                                    JsonObject result = prediction(request, callId);
                                    if (rejectChild.get()
                                            && request.producer().stepId().equals("child")) {
                                        var features =
                                                result.getAsJsonObject("geojson")
                                                        .getAsJsonArray("features");
                                        features.get(features.size() - 1)
                                                .getAsJsonObject()
                                                .getAsJsonObject("properties")
                                                .addProperty("object_type", "ANNOTATION");
                                    }
                                    return result;
                                });
                var gateway = spy(server.executor())) {
            var fixture = new AnnotationRoiWorkflowFixture(directory, gateway);
            var retained = seedRetainedObjects(fixture);
            var executor = fixture.executor();
            var initial = executor.execute(request(fixture, false), () -> false);
            assertEquals(
                    RunResults.ImageOutcomeType.SUCCEEDED, initial.outcome(), initial.toString());
            var before = savedRevision(fixture);

            rejectChild.set(true);
            var failed = executor.execute(request(fixture, true), () -> false);
            assertEquals(RunResults.ImageOutcomeType.FAILED, failed.outcome(), failed.toString());
            assertFalse(failed.hierarchySaved());
            assertTrue(
                    failed.scopes().stream()
                            .anyMatch(
                                    scope ->
                                            scope.execution().isPresent()
                                                    && scope.failure().orElseThrow().type()
                                                            == RunResults.FailureType
                                                                    .RESPONSE_VALIDATION),
                    failed.toString());
            assertEquals(before, savedRevision(fixture));
            assertEquals(
                    List.of(
                            "parent", "child", "parent", "child", "parent", "child", "parent",
                            "child"),
                    modelOrder(server));
            verify(fixture.lifecycle, times(2)).commit(any());
            try (var reopened = fixture.entry.readImageData()) {
                assertRetainedObjects(reopened, retained);
                assertScriptedHierarchy(reopened);
            }
            server.requireNoFailure();
        }
    }

    private static ImageActionExecutor.Request request(
            AnnotationRoiWorkflowFixture fixture, boolean redo) {
        var module =
                FiniteCapacityTestData.withCandidateLimits(
                        RecipeExecutorTestData.scriptedModule(false), 1.0);
        var recipe = module.exactRecipe();
        var metricStep = new JsonObject();
        metricStep.addProperty("script", "metrics.groovy");
        recipe.getAsJsonArray("steps").add(metricStep);
        module =
                new ModuleSnapshot(
                        module.deploymentId(),
                        module.module(),
                        recipe,
                        module.scriptSetDigest(),
                        module.activatedModels(),
                        module.scripts(),
                        RecipeExecutorTestData.fieldMetricModule().metrics());
        return new ImageActionExecutor.Request(
                fixture.request.actionId(),
                fixture.request.clientBuild(),
                fixture.entry,
                module,
                RecipeExecutorTestData.allModels(redo, Set.of(RecipeExecutorTestData.ROI_A)),
                fixture.request.annotationClass());
    }

    private static List<String> modelOrder(FiniteRecipeGatewayStub server) {
        return server.requests().stream().map(request -> request.producer().stepId()).toList();
    }

    private static JsonObject prediction(
            FiniteInferenceCodec.FiniteInferenceRequest request, String callId) {
        boolean parent = request.producer().stepId().equals("parent");
        assertTrue(parent || request.producer().stepId().equals("child"));
        var features =
                parent
                        ? List.of(feature("Parent", RecipeExecutorTestData.square(10, 10, 18, 18)))
                        : List.of(
                                feature("Child", RecipeExecutorTestData.square(1, 1, 2, 2)),
                                feature("Child", RecipeExecutorTestData.square(4, 4, 5, 5)));
        var meta =
                new FiniteInferenceCodec.BackendResultMeta(
                        FiniteInferenceCodec.SCHEMA_VERSION,
                        request.actionId(),
                        request.outputGenerationId(),
                        callId,
                        request.inputId(),
                        request.producer(),
                        request.expectedBackend(),
                        FiniteInferenceCodec.inputIdentity(request),
                        FiniteInferenceCodec.CoordinateSpace.RASTER_LOCAL);
        return FiniteInferenceCodec.toJson(
                new FiniteInferenceCodec.InferenceResult(
                        meta,
                        new InferenceFeatureCodec.FeatureCollection(
                                features.stream()
                                        .sorted(
                                                Comparator.comparing(
                                                        InferenceFeatureCodec.Feature::id))
                                        .toList())));
    }

    private static InferenceFeatureCodec.Feature feature(String pathClass, Geometry geometry) {
        var identity = new JsonArray();
        identity.add(pathClass);
        identity.add(CanonicalGeometryCodec.canonicalCoordinates(geometry));
        identity.add(0);
        return new InferenceFeatureCodec.Feature(
                CanonicalCodec.canonicalDigest(identity), geometry, pathClass);
    }

    private static Map<UUID, String> seedRetainedObjects(AnnotationRoiWorkflowFixture fixture)
            throws Exception {
        try (var image = fixture.entry.readImageData()) {
            var manual =
                    RecipeExecutorTestData.manual(
                            RecipeExecutorTestData.MANUAL_PARENT_ID,
                            "Manual",
                            RecipeExecutorTestData.square(60, 60, 65, 65));
            var unrelated =
                    RecipeExecutorTestData.attributed(
                            RecipeExecutorTestData.SLIDE_OBJECT_ID,
                            "Unrelated",
                            RecipeExecutorTestData.square(80, 70, 85, 75),
                            "unrelated",
                            new ExecutionScopeCodec.FieldScope(
                                    new ExecutionScopeCodec.Roi(RecipeExecutorTestData.ROI_A)));
            for (var object : List.of(manual, unrelated)) {
                object.setName("Retain " + object.getID());
                object.getMetadata().put("review-note", "keep this value");
                object.getMeasurementList().put("manual measurement", 7.5);
            }
            var boundary =
                    RoiRegistry.fromHierarchy(image.getHierarchy())
                            .findRoi(RecipeExecutorTestData.ROI_A)
                            .orElseThrow();
            boundary.addChildObjects(List.of(manual, unrelated));
            fixture.entry.saveImageData(image);
        }
        try (var saved = fixture.entry.readImageData()) {
            var retained = new LinkedHashMap<UUID, String>();
            for (var object : saved.getHierarchy().getAllObjects(false)) {
                if (object.getID().toString().equals(RecipeExecutorTestData.MANUAL_PARENT_ID)
                        || object.getID()
                                .toString()
                                .equals(RecipeExecutorTestData.SLIDE_OBJECT_ID)) {
                    retained.put(
                            object.getID(),
                            ImageDataRevisionDigester.objectTransactionSemanticDigest(object));
                }
            }
            assertEquals(2, retained.size());
            return retained;
        }
    }

    private static void assertRetainedObjects(ImageData<?> image, Map<UUID, String> expected) {
        var observed = new LinkedHashMap<UUID, String>();
        image.getHierarchy().getAllObjects(false).stream()
                .filter(object -> expected.containsKey(object.getID()))
                .forEach(
                        object ->
                                observed.put(
                                        object.getID(),
                                        ImageDataRevisionDigester.objectTransactionSemanticDigest(
                                                object)));
        assertEquals(expected, observed);
    }

    private static void assertScriptedHierarchy(ImageData<?> image) {
        for (String roiId : List.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B)) {
            var parentOutput =
                    new ExecutionScopeCodec.OutputKey(
                            RecipeExecutorTestData.producer("parent"),
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(roiId)));
            var parents =
                    image.getHierarchy().getAllObjects(false).stream()
                            .filter(
                                    object ->
                                            ProvenanceCodec.readMetadata(object.getMetadata())
                                                    .map(
                                                            provenance ->
                                                                    provenance
                                                                            .output()
                                                                            .equals(parentOutput))
                                                    .orElse(false))
                            .toList();
            assertEquals(1, parents.size());
            PathObject parent = parents.getFirst();
            assertEquals(
                    "Eligible",
                    parent.getPathClass().toString(),
                    "ordinary script must classify the complete parent result");
            assertEquals(2, parent.getChildObjects().size());
            for (var child : parent.getChildObjects()) {
                assertEquals("Child", child.getPathClass().toString());
                var provenance = ProvenanceCodec.readMetadata(child.getMetadata()).orElseThrow();
                assertEquals(
                        new ExecutionScopeCodec.ParentScope(
                                new ExecutionScopeCodec.Roi(roiId), parent.getID().toString()),
                        provenance.output().scope());
            }
        }
    }

    private static RasterInputCodec.ImageRevisionToken savedRevision(
            AnnotationRoiWorkflowFixture fixture) throws Exception {
        try (var image = fixture.entry.readImageData()) {
            return ImageDataRevisionDigester.capture(
                    fixture.entry.getID(),
                    SourceIdentityCodec.imageSourceIdentityDigest(
                            RecipeExecutorTestData.sourceRevision().pixelSource()),
                    image,
                    fixture.request.clientBuild().limits());
        }
    }
}
