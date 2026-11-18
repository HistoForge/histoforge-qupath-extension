package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.client.ModuleRef;

/** Runs the shared finite-inference identity and envelope contract. */
class FiniteInferenceCodecTest {

    @Test
    void sharedFixtureBindsSpatialIdentityAndAllFiniteEnvelopes() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        for (JsonElement item : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = item.getAsJsonObject();
            JsonObject route = testCase.getAsJsonObject("route");
            var request =
                    FiniteInferenceCodec.validateRouteRequest(
                            wire(testCase, "request"),
                            moduleRef(route.get("moduleRef").getAsString()),
                            route.get("modelId").getAsString());
            assertExact(testCase, "request", FiniteInferenceCodec.toJson(request));

            var modelInput = request.rasterInput();
            var view = (RasterInputCodec.SourceView) modelInput.sourceView();
            var frame = modelInput.frame();
            JsonObject digests = testCase.getAsJsonObject("expectedDigests");
            assertEquals(
                    digests.get("imageSourceDigest").getAsString(),
                    frame.sourceRevision().imageDataRevision().imageSourceDigest());
            assertEquals(digests.get("sourceViewDigest").getAsString(), view.viewDigest());
            assertEquals(
                    digests.get("inclusionMaskDigest").getAsString(), frame.inclusionMaskDigest());
            assertEquals(digests.get("frameDigest").getAsString(), frame.frameDigest());
            assertEquals(
                    digests.get("modelRasterInputDigest").getAsString(), modelInput.inputDigest());
            assertEquals(
                    digests.get("backendModelIdentityDigest").getAsString(),
                    InferenceIdentityCodec.backendModelIdentityDigest(request.expectedBackend()));

            assertEquals(
                    view,
                    RasterInputCodec.buildSourceView(
                            view.artifact(),
                            view.seriesIndex(),
                            view.resolutionLevel(),
                            view.plane(),
                            view.pixelRect(),
                            view.viewPixelsToSlide()));
            var artifact = view.artifact();
            var changedCreation =
                    new SourceArtifactPublicationCodec.SourceArtifactRef(
                            artifact.artifactId(),
                            "99999999-9999-4999-8999-999999999999",
                            artifact.startRequestDigest(),
                            artifact.bytesDigest(),
                            artifact.manifestDigest(),
                            artifact.container());
            assertNotEquals(
                    view.viewDigest(),
                    RasterInputCodec.buildSourceView(
                                    changedCreation,
                                    view.seriesIndex(),
                                    view.resolutionLevel(),
                                    view.plane(),
                                    view.pixelRect(),
                                    view.viewPixelsToSlide())
                            .viewDigest());
            assertEquals(
                    frame,
                    RasterInputCodec.buildRasterFrame(
                            frame.sourceRevision(),
                            frame.region(),
                            frame.transform(),
                            frame.inclusionMaskRaster()));
            assertEquals(
                    modelInput,
                    RasterInputCodec.buildModelRasterInput(view, frame, modelInput.rasterizer()));

            var accepted =
                    FiniteInferenceCodec.validateAcceptance(request, wire(testCase, "accepted"));
            var pending =
                    FiniteInferenceCodec.validatePending(
                            wire(testCase, "pending"), accepted.backendCallId());
            var cancellation =
                    FiniteInferenceCodec.validateCancellationAcceptance(
                            wire(testCase, "cancellation"), accepted.backendCallId());
            assertExact(testCase, "accepted", FiniteInferenceCodec.toJson(accepted));
            assertExact(testCase, "pending", FiniteInferenceCodec.toJson(pending));
            assertExact(testCase, "cancellation", FiniteInferenceCodec.toJson(cancellation));

            var result =
                    FiniteInferenceCodec.validateResult(
                            wire(testCase, "result"),
                            request,
                            accepted.backendCallId(),
                            strings(testCase, "declaredClasses"));
            assertExact(testCase, "result", FiniteInferenceCodec.toJson(result));
            assertEquals(
                    FiniteInferenceCodec.buildBackendInputIdentity(modelInput),
                    result.meta().inputIdentity());
        }

        JsonObject emptyCase = fixture.getAsJsonArray("cases").get(1).getAsJsonObject();
        assertEquals(
                0,
                wire(emptyCase, "result")
                        .getAsJsonObject("geojson")
                        .getAsJsonArray("features")
                        .size());
    }

    @Test
    void oldAndFutureVersionsAreRejectedByEveryFiniteEnvelope() throws IOException {
        JsonObject testCase = firstCase();
        for (int schemaVersion : new int[] {1, 2, 4}) {
            for (String envelope :
                    List.of("request", "accepted", "pending", "cancellation", "result")) {
                JsonObject value = wire(testCase, envelope);
                if (envelope.equals("result")) value = value.getAsJsonObject("meta");
                value.addProperty("schema_version", schemaVersion);
                JsonObject unsupported = value;
                var failure =
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> {
                                    switch (envelope) {
                                        case "request" ->
                                                FiniteInferenceCodec.parseRequest(unsupported);
                                        case "accepted" ->
                                                FiniteInferenceCodec.parseAccepted(unsupported);
                                        case "pending" ->
                                                FiniteInferenceCodec.parsePending(unsupported);
                                        case "cancellation" ->
                                                FiniteInferenceCodec.parseCancellationAccepted(
                                                        unsupported);
                                        case "result" ->
                                                FiniteInferenceCodec.parseResultMeta(unsupported);
                                        default -> throw new AssertionError(envelope);
                                    }
                                });
                assertEquals("unsupported finite inference schema_version", failure.getMessage());
            }
        }
    }

    @Test
    void recordsDetachMutableMapsAndGeometries() throws IOException {
        JsonObject testCase = firstCase();
        var request = FiniteInferenceCodec.parseRequest(wire(testCase, "request"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> request.expectedBackend().artifactDigests().put("other", "a".repeat(64)));

        Geometry exposed = request.rasterInput().frame().inclusionMaskRaster();
        exposed.apply((CoordinateFilter) coordinate -> coordinate.x += 10_000.0);
        exposed.geometryChanged();
        assertFalse(
                request.rasterInput().frame().inclusionMaskRaster().getEnvelopeInternal().getMinX()
                        >= 10_000.0);

        var result =
                FiniteInferenceCodec.validateResult(
                        wire(testCase, "result"),
                        request,
                        wire(testCase, "accepted").get("backend_call_id").getAsString(),
                        strings(testCase, "declaredClasses"));
        Geometry feature = result.geojson().features().getFirst().geometry();
        feature.apply((CoordinateFilter) coordinate -> coordinate.x += 10_000.0);
        feature.geometryChanged();
        assertFalse(
                result.geojson().features().getFirst().geometry().getEnvelopeInternal().getMinX()
                        >= 10_000.0);
    }

    @Test
    void sharedBackendCallIdByteBoundaryCoversEveryFiniteEnvelope() throws IOException {
        JsonObject fixture = fixture();
        JsonObject base = fixture.getAsJsonArray("cases").get(0).getAsJsonObject();
        JsonObject accepted = wire(base, "accepted");
        JsonObject pending = wire(base, "pending");
        JsonObject cancellation = wire(base, "cancellation");
        JsonObject meta = wire(base, "result").getAsJsonObject("meta");

        for (JsonElement item : fixture.getAsJsonArray("backendCallIdCases")) {
            JsonObject testCase = item.getAsJsonObject();
            String callId =
                    testCase.get("unit")
                            .getAsString()
                            .repeat(testCase.get("repetitions").getAsInt());
            assertEquals(
                    testCase.get("utf8Bytes").getAsInt(),
                    CanonicalCodec.utf8Bytes(callId).length,
                    testCase.get("name").getAsString());
            if (testCase.has("canonicalStringBytes")) {
                assertEquals(
                        testCase.get("canonicalStringBytes").getAsInt(),
                        CanonicalCodec.canonicalBytes(new com.google.gson.JsonPrimitive(callId))
                                .length);
            }

            JsonObject acceptedValue = accepted.deepCopy();
            acceptedValue.addProperty("backend_call_id", callId);
            JsonObject pendingValue = pending.deepCopy();
            pendingValue.addProperty("backend_call_id", callId);
            JsonObject cancellationValue = cancellation.deepCopy();
            cancellationValue.addProperty("backend_call_id", callId);
            JsonObject metaValue = meta.deepCopy();
            metaValue.addProperty("backend_call_id", callId);

            List<Runnable> validators =
                    List.of(
                            () ->
                                    BackendExecutionCodec.requireBackendCallId(
                                            callId, "backend_call_id"),
                            () -> BackendExecutionCodec.encodeBackendCallQuery(callId),
                            () -> new BackendExecutionCodec.FiniteCall(callId),
                            () -> FiniteInferenceCodec.parseAccepted(acceptedValue),
                            () -> FiniteInferenceCodec.parsePending(pendingValue),
                            () -> FiniteInferenceCodec.parseCancellationAccepted(cancellationValue),
                            () -> FiniteInferenceCodec.parseResultMeta(metaValue));
            if (testCase.get("valid").getAsBoolean()) {
                validators.forEach(Runnable::run);
            } else {
                validators.forEach(
                        validate -> assertThrows(IllegalArgumentException.class, validate::run));
            }
        }
    }

    @Test
    void sharedBackendCallQueryEncodingRoundTripsThroughJavaUri() throws IOException {
        JsonObject fixture = fixture();
        String parameter = fixture.get("backendCallQueryParameter").getAsString();
        assertEquals(BackendExecutionCodec.BACKEND_CALL_QUERY_PARAMETER, parameter);
        for (JsonElement item : fixture.getAsJsonArray("backendCallAddressCases")) {
            JsonObject testCase = item.getAsJsonObject();
            int repetitions = testCase.get("repetitions").getAsInt();
            String callId = testCase.get("unit").getAsString().repeat(repetitions);
            String prefix = parameter + "=";
            String expected =
                    prefix + testCase.get("encodedUnit").getAsString().repeat(repetitions);
            String query = BackendExecutionCodec.encodeBackendCallQuery(callId);

            assertEquals(expected, query, testCase.get("name").getAsString());
            URI uri = URI.create("https://example.test/v1/inference/calls?" + query);
            assertEquals(expected, uri.getRawQuery());
            assertEquals(
                    callId,
                    URLDecoder.decode(
                            uri.getRawQuery().substring(prefix.length()), StandardCharsets.UTF_8));
        }
    }

    @Test
    void requestIdentityAndSpatialMutationsFailClosed() throws IOException {
        JsonObject testCase = firstCase();
        List<Consumer<JsonObject>> mutations =
                List.of(
                        value -> value.addProperty("extra", true),
                        value -> value.addProperty("schema_version", 1),
                        value ->
                                value.addProperty(
                                        "action_id", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
                        value -> value.getAsJsonObject("producer").addProperty("step_id", "lumen"),
                        value ->
                                value.getAsJsonObject("expected_backend")
                                        .getAsJsonObject("rasterizer")
                                        .addProperty(
                                                "rasterization_contract_digest", "a".repeat(64)),
                        value ->
                                rasterInput(value)
                                        .getAsJsonObject("source_view")
                                        .addProperty("view_digest", "a".repeat(64)),
                        value ->
                                rasterInput(value)
                                        .getAsJsonObject("frame")
                                        .addProperty("inclusion_mask_digest", "a".repeat(64)),
                        value ->
                                rasterInput(value)
                                        .getAsJsonObject("frame")
                                        .addProperty("frame_digest", "a".repeat(64)),
                        value -> rasterInput(value).addProperty("input_digest", "a".repeat(64)),
                        value ->
                                rasterInput(value)
                                        .getAsJsonObject("frame")
                                        .getAsJsonObject("source_revision")
                                        .getAsJsonObject("image_data_revision")
                                        .addProperty("image_source_digest", "a".repeat(64)));

        for (Consumer<JsonObject> mutation : mutations) {
            JsonObject value = wire(testCase, "request");
            mutation.accept(value);
            assertThrows(
                    IllegalArgumentException.class, () -> FiniteInferenceCodec.parseRequest(value));
        }

        JsonObject route = testCase.getAsJsonObject("route");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FiniteInferenceCodec.validateRouteRequest(
                                wire(testCase, "request"),
                                moduleRef("histoforge/lumen"),
                                route.get("modelId").getAsString()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FiniteInferenceCodec.validateRouteRequest(
                                wire(testCase, "request"),
                                moduleRef(route.get("moduleRef").getAsString()),
                                "lumen"));

        var frame =
                FiniteInferenceCodec.parseRequest(wire(testCase, "request")).rasterInput().frame();
        Geometry outside = frame.inclusionMaskRaster();
        outside.apply((CoordinateFilter) coordinate -> coordinate.x += 513.0);
        outside.geometryChanged();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RasterInputCodec.buildRasterFrame(
                                frame.sourceRevision(),
                                frame.region(),
                                frame.transform(),
                                outside));

        var transform = frame.transform();
        var rasterToSlide = transform.rasterToSlide();
        var wrongTransform =
                new RasterInputCodec.RasterTransform(
                        transform.raster(),
                        new SourceIdentityCodec.ScaleTranslate2D(
                                Math.nextUp(rasterToSlide.scaleX()),
                                rasterToSlide.scaleY(),
                                rasterToSlide.translateX(),
                                rasterToSlide.translateY()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RasterInputCodec.buildRasterFrame(
                                frame.sourceRevision(),
                                frame.region(),
                                wrongTransform,
                                frame.inclusionMaskRaster()));

        var request = FiniteInferenceCodec.parseRequest(wire(testCase, "request"));
        var slideFrame =
                RasterInputCodec.buildRasterFrame(
                        frame.sourceRevision(),
                        new ExecutionScopeCodec.Slide(),
                        frame.transform(),
                        frame.inclusionMaskRaster());
        var slideInput =
                RasterInputCodec.buildModelRasterInput(
                        request.rasterInput().sourceView(),
                        slideFrame,
                        request.rasterInput().rasterizer());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FiniteInferenceCodec.FiniteInferenceRequest(
                                request.schemaVersion(),
                                request.actionId(),
                                request.outputGenerationId(),
                                request.inputId(),
                                request.producer(),
                                request.expectedBackend(),
                                slideInput,
                                request.resultBudget()));
    }

    @Test
    void acceptanceResultEchoesAndResultLimitsFailClosed() throws IOException {
        JsonObject testCase = firstCase();
        JsonObject requestValue = wire(testCase, "request");
        var request = FiniteInferenceCodec.parseRequest(requestValue);
        JsonObject acceptedValue = wire(testCase, "accepted");
        String callId = acceptedValue.get("backend_call_id").getAsString();
        JsonObject result = wire(testCase, "result");
        Set<String> classes = strings(testCase, "declaredClasses");

        for (String field : Set.of("action_id", "output_generation_id")) {
            JsonObject wrongAccepted = acceptedValue.deepCopy();
            wrongAccepted.addProperty(field, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> FiniteInferenceCodec.validateAcceptance(request, wrongAccepted));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FiniteInferenceCodec.validatePending(
                                wire(testCase, "pending"), "other-call"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FiniteInferenceCodec.validateCancellationAcceptance(
                                wire(testCase, "cancellation"), "other-call"));

        assertMetaMutationFails(
                result,
                request,
                callId,
                classes,
                value -> value.addProperty("action_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        assertMetaMutationFails(
                result,
                request,
                callId,
                classes,
                value -> value.addProperty("backend_call_id", "other-call"));
        assertMetaMutationFails(
                result, request, callId, classes, value -> value.addProperty("input_id", 99));
        assertMetaMutationFails(
                result,
                request,
                callId,
                classes,
                value -> value.getAsJsonObject("producer").addProperty("step_id", "lumen"));
        assertMetaMutationFails(
                result,
                request,
                callId,
                classes,
                value -> value.getAsJsonObject("backend").addProperty("gpu", "L4"));
        assertMetaMutationFails(
                result,
                request,
                callId,
                classes,
                value ->
                        value.getAsJsonObject("input_identity")
                                .addProperty("source_manifest_digest", "a".repeat(64)));

        JsonObject wrongCoordinate = result.deepCopy();
        wrongCoordinate.getAsJsonObject("meta").addProperty("coordinate_space", "SLIDE");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        FiniteInferenceCodec.validateResult(
                                wrongCoordinate, request, callId, classes));

        JsonObject wrongClass = result.deepCopy();
        wrongClass
                .getAsJsonObject("geojson")
                .getAsJsonArray("features")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("properties")
                .addProperty("path_class", "Lumen");
        assertThrows(
                IllegalArgumentException.class,
                () -> FiniteInferenceCodec.validateResult(wrongClass, request, callId, classes));

        JsonObject wrongBounds = result.deepCopy();
        JsonObject outOfBounds = wire(testCase, "outOfBoundsGeojson");
        assertExact(testCase, "outOfBoundsGeojson", outOfBounds);
        wrongBounds.add("geojson", outOfBounds);
        assertThrows(
                IllegalArgumentException.class,
                () -> FiniteInferenceCodec.validateResult(wrongBounds, request, callId, classes));

        int featureBytes =
                CanonicalCodec.canonicalBytes(
                                result.getAsJsonObject("geojson").getAsJsonArray("features").get(0))
                        .length;
        assertBudgetLimitFails(requestValue, result, callId, classes, "max_features", 1);
        assertBudgetLimitFails(requestValue, result, callId, classes, "max_vertices", 7);
        assertBudgetLimitFails(
                requestValue, result, callId, classes, "max_record_bytes", featureBytes - 1L);
    }

    @Test
    void exactFeatureRecordAndVertexBudgetsAreAccepted() throws IOException {
        JsonObject testCase = firstCase();
        JsonObject request = wire(testCase, "request");
        JsonObject result = wire(testCase, "result");
        long maximumRecord = 0;
        long vertices = 0;
        var features = result.getAsJsonObject("geojson").getAsJsonArray("features");
        for (var feature : features) {
            maximumRecord = Math.max(maximumRecord, CanonicalCodec.canonicalBytes(feature).length);
            vertices +=
                    CanonicalGeometryCodec.vertexCount(
                            CanonicalGeometryCodec.parseGeoJson(
                                    feature.getAsJsonObject().getAsJsonObject("geometry")));
        }
        request.getAsJsonObject("result_budget").addProperty("max_record_bytes", maximumRecord);
        request.getAsJsonObject("result_budget").addProperty("max_vertices", vertices);
        request.getAsJsonObject("result_budget").addProperty("max_features", features.size());
        var validated =
                FiniteInferenceCodec.validateResult(
                        result,
                        FiniteInferenceCodec.parseRequest(request),
                        result.getAsJsonObject("meta").get("backend_call_id").getAsString(),
                        strings(testCase, "declaredClasses"));
        assertEquals(features.size(), validated.geojson().features().size());
    }

    @Test
    void resultBudgetFieldsArePositiveSafeIntegers() throws IOException {
        JsonObject request = wire(firstCase(), "request");
        for (String field : request.getAsJsonObject("result_budget").keySet()) {
            JsonObject zero = request.deepCopy();
            zero.getAsJsonObject("result_budget").addProperty(field, 0);
            assertThrows(
                    IllegalArgumentException.class, () -> FiniteInferenceCodec.parseRequest(zero));

            JsonObject unsafe = request.deepCopy();
            unsafe.getAsJsonObject("result_budget").addProperty(field, 9_007_199_254_740_992L);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> FiniteInferenceCodec.parseRequest(unsafe));
        }
    }

    private static void assertMetaMutationFails(
            JsonObject result,
            FiniteInferenceCodec.FiniteInferenceRequest request,
            String callId,
            Set<String> classes,
            Consumer<JsonObject> mutation) {
        JsonObject wrong = result.deepCopy();
        mutation.accept(wrong.getAsJsonObject("meta"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FiniteInferenceCodec.validateResult(wrong, request, callId, classes));
    }

    private static void assertBudgetLimitFails(
            JsonObject request,
            JsonObject result,
            String callId,
            Set<String> classes,
            String field,
            long limit) {
        JsonObject bounded = request.deepCopy();
        bounded.getAsJsonObject("result_budget").addProperty(field, limit);
        var parsed = FiniteInferenceCodec.parseRequest(bounded);
        assertThrows(
                IllegalArgumentException.class,
                () -> FiniteInferenceCodec.validateResult(result, parsed, callId, classes));
    }

    private static JsonObject rasterInput(JsonObject request) {
        return request.getAsJsonObject("raster_input");
    }

    private static void assertExact(JsonObject testCase, String name, JsonObject value) {
        byte[] encoded = CanonicalCodec.canonicalBytes(value);
        assertEquals(
                testCase.get(name + "Canonical").getAsString(),
                new String(encoded, StandardCharsets.UTF_8),
                testCase.get("name").getAsString());
        assertEquals(
                testCase.get(name + "Sha256").getAsString(),
                CanonicalCodec.sha256(encoded),
                testCase.get("name").getAsString());
    }

    private static Set<String> strings(JsonObject value, String field) {
        Set<String> result = new HashSet<>();
        for (JsonElement item : value.getAsJsonArray(field)) {
            result.add(item.getAsString());
        }
        return Set.copyOf(result);
    }

    private static JsonObject wire(JsonObject testCase, String name) {
        return CanonicalCodec.parseJson(testCase.get(name + "Canonical").getAsString())
                .getAsJsonObject();
    }

    private static ModuleRef moduleRef(String value) {
        return ModuleRef.parse(value).orElseThrow();
    }

    private static JsonObject firstCase() throws IOException {
        return fixture().getAsJsonArray("cases").get(0).getAsJsonObject();
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                FiniteInferenceCodecTest.class.getResourceAsStream(
                        "/contracts/finite-inference-v3.json")) {
            if (stream == null) {
                throw new IOException("missing finite-inference fixture");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
