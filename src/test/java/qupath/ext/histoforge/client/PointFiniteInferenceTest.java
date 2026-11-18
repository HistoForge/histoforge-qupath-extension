package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.InferenceFeatureCodec.GeometryKind;
import qupath.ext.histoforge.contract.ModelOutputCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.modules.ModuleContract;

/** Exact shared wire bytes and authenticated local HTTP calls, without a cloud job. */
class PointFiniteInferenceTest {

    @Test
    void upperEdgePointsFailBeforeImportEvenWithValidIdentity(@TempDir Path directory)
            throws Exception {
        var testCase = fixture().getAsJsonArray("cases").get(0).getAsJsonObject();
        var request = FiniteInferenceCodec.parseRequest(wire(testCase, "request"));
        var raster = request.rasterInput().frame().transform().raster();
        for (double[] xy :
                new double[][] {
                    {raster.width(), 0.5},
                    {0.5, raster.height()},
                    {-0.5, 0.5},
                    {raster.width() - 0.5, raster.height() - 0.5}
                }) {
            JsonObject result = wire(testCase, "result");
            var feature =
                    result.getAsJsonObject("geojson")
                            .getAsJsonArray("features")
                            .get(0)
                            .getAsJsonObject();
            var coordinates = new com.google.gson.JsonArray();
            coordinates.add(xy[0]);
            coordinates.add(xy[1]);
            feature.getAsJsonObject("geometry").add("coordinates", coordinates);
            var preimage = new com.google.gson.JsonArray();
            preimage.add("Object");
            preimage.add(coordinates);
            preimage.add(feature.getAsJsonObject("properties").get("score"));
            preimage.add(0);
            feature.addProperty("id", CanonicalCodec.canonicalDigest(preimage));
            var features = new com.google.gson.JsonArray();
            features.add(feature);
            result.getAsJsonObject("geojson").add("features", features);
            result.getAsJsonObject("meta")
                    .addProperty("backend_call_id", FiniteInferenceGatewayStub.CALL_ID);
            boolean inside =
                    xy[0] >= 0 && xy[0] < raster.width() && xy[1] >= 0 && xy[1] < raster.height();
            if (inside) {
                assertEquals(
                        1,
                        FiniteInferenceCodec.validateResult(
                                        result,
                                        request,
                                        FiniteInferenceGatewayStub.CALL_ID,
                                        Set.of("Object"),
                                        GeometryKind.POINT)
                                .geojson()
                                .features()
                                .size());
            } else {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                FiniteInferenceCodec.validateResult(
                                        result,
                                        request,
                                        FiniteInferenceGatewayStub.CALL_ID,
                                        Set.of("Object"),
                                        GeometryKind.POINT));
            }
            try (var gateway = new FiniteInferenceGatewayStub(request);
                    var executor =
                            new ModelGatewayExecutor(
                                    new GatewayTransport(
                                            ModelGatewayExecutorTestData.storeFor(
                                                    gateway.server(), directory)),
                                    ModelGatewayExecutorTestData.capabilities())) {
                JsonObject capabilities =
                        RunnerCapabilitiesCodec.toJson(gateway.runnerCapabilities());
                var types = new com.google.gson.JsonArray();
                types.add("POINT_DETECTION");
                capabilities.add("output_types", types);
                var output =
                        new ModelOutputCodec.ModelOutput(
                                "point_detection",
                                java.util.List.of(),
                                java.util.List.of(
                                        new ModelOutputCodec.ClassChannel(0, "emitted", "Object")));
                gateway.replyToPoll(200, CanonicalCodec.canonicalBytes(result));
                var handle =
                        executor.submitFinite(
                                request, RunnerCapabilitiesCodec.parse(capabilities), output);
                if (inside) assertTrue(executor.pollFinite(handle).isPresent());
                else assertThrows(InferenceException.class, () -> executor.pollFinite(handle));
                gateway.requireNoFailure();
            }
        }
        var polygon =
                new org.locationtech.jts.geom.GeometryFactory()
                        .toGeometry(
                                new org.locationtech.jts.geom.Envelope(
                                        0, raster.width(), 0, raster.height()));
        assertDoesNotThrow(
                () ->
                        RasterInputCodec.requireGeometryInsideRaster(
                                polygon, raster, "polygon edge"));
    }

    @Test
    void sharedRecipeAndResultsPreserveExactPointScores() throws Exception {
        JsonObject fixture = fixture();
        byte[] recipeBytes =
                fixture.get("recipeCanonical")
                        .getAsString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(fixture.get("recipeSha256").getAsString(), CanonicalCodec.sha256(recipeBytes));
        ModuleContract.parse(recipeBytes, Map.of());
        var recipe = CanonicalCodec.parseJson(recipeBytes).getAsJsonObject();
        var output =
                ModelOutputCodec.parse(
                        recipe.getAsJsonObject("models").getAsJsonObject("centers").get("output"));
        assertEquals(GeometryKind.POINT, output.geometryKind());
        for (var element : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            var request = FiniteInferenceCodec.parseRequest(wire(testCase, "request"));
            String callId = wire(testCase, "accepted").get("backend_call_id").getAsString();
            var result =
                    FiniteInferenceCodec.validateResult(
                            wire(testCase, "result"),
                            request,
                            callId,
                            Set.copyOf(output.emittedClasses()),
                            output.geometryKind());
            byte[] encoded = CanonicalCodec.canonicalBytes(FiniteInferenceCodec.toJson(result));
            assertEquals(
                    testCase.get("resultSha256").getAsString(), CanonicalCodec.sha256(encoded));
            if (!result.geojson().features().isEmpty()) {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                FiniteInferenceCodec.validateResult(
                                        wire(testCase, "result"),
                                        request,
                                        callId,
                                        Set.of("Object")));
                for (var feature : result.geojson().features()) {
                    assertTrue(feature.score().isPresent());
                }
            }
        }
        recipe.addProperty("schemaVersion", 3);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleContract.parse(CanonicalCodec.canonicalBytes(recipe), Map.of()));
        var channel =
                recipe.getAsJsonObject("models")
                        .getAsJsonObject("centers")
                        .getAsJsonObject("output")
                        .getAsJsonArray("classChannels")
                        .get(0)
                        .getAsJsonObject();
        channel.addProperty("role", "training_only");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelOutputCodec.parse(
                                recipe.getAsJsonObject("models")
                                        .getAsJsonObject("centers")
                                        .get("output")));
    }

    @Test
    void finiteHandleKeepsThePinnedPointKindThroughHttpPolling(@TempDir Path directory)
            throws Exception {
        for (var element : fixture().getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            var request = FiniteInferenceCodec.parseRequest(wire(testCase, "request"));
            var output =
                    ModelOutputCodec.parse(
                            CanonicalCodec.parseJson(fixture().get("recipeCanonical").getAsString())
                                    .getAsJsonObject()
                                    .getAsJsonObject("models")
                                    .getAsJsonObject("centers")
                                    .get("output"));
            try (var gateway = new FiniteInferenceGatewayStub(request);
                    var executor =
                            new ModelGatewayExecutor(
                                    new GatewayTransport(
                                            ModelGatewayExecutorTestData.storeFor(
                                                    gateway.server(), directory)),
                                    ModelGatewayExecutorTestData.capabilities())) {
                JsonObject capabilities =
                        RunnerCapabilitiesCodec.toJson(gateway.runnerCapabilities());
                var outputs = new com.google.gson.JsonArray();
                outputs.add("POINT_DETECTION");
                capabilities.add("output_types", outputs);
                var runner = RunnerCapabilitiesCodec.parse(capabilities);
                JsonObject result = wire(testCase, "result");
                result.getAsJsonObject("meta")
                        .addProperty("backend_call_id", FiniteInferenceGatewayStub.CALL_ID);
                gateway.replyToPoll(200, CanonicalCodec.canonicalBytes(result));
                var handle = executor.submitFinite(request, runner, output);
                assertEquals(GeometryKind.POINT, handle.geometryKind());
                var received = executor.pollFinite(handle).orElseThrow();
                assertEquals(
                        result.getAsJsonObject("geojson"),
                        FiniteInferenceCodec.toJson(received).getAsJsonObject("geojson"));
                assertEquals(1, gateway.postCalls());
                assertEquals(0, gateway.cancelCalls());
                gateway.requireNoFailure();
            }
        }
    }

    private static JsonObject fixture() throws Exception {
        try (var input =
                PointFiniteInferenceTest.class.getResourceAsStream(
                        "/contracts/point-finite-inference-v1.json")) {
            assertNotNull(input);
            return CanonicalCodec.parseJson(input.readAllBytes()).getAsJsonObject();
        }
    }

    private static JsonObject wire(JsonObject testCase, String name) {
        return CanonicalCodec.parseJson(testCase.get(name + "Canonical").getAsString())
                .getAsJsonObject();
    }
}
