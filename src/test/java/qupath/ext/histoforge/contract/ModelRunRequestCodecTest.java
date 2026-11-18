package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelRunRequestCodecTest {

    @Test
    void sharedRequestsPlanFamiliesAndAcceptancesAreExact() throws IOException {
        JsonObject fixture = fixture("model-run-request-v1.json");
        assertEquals(1, fixture.get("schemaVersion").getAsInt());
        for (JsonElement element : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            JsonObject raw = requestJson(testCase);
            JsonObject route = finiteCase(testCase).getAsJsonObject("route");
            var actualBackend =
                    InferenceIdentityCodec.parseBackendModelIdentity(raw.get("expected_backend"));
            var limits =
                    ExecutionLimitsCodec.parseTargetRasterLimits(
                            testCase.get("targetRasterLimits"));
            var runLimits =
                    ExecutionLimitsCodec.parseModelRunLimits(fixture.get("testModelRunLimits"));
            var profile = profile(testCase);
            var request =
                    ModelRunRequestCodec.validateAdmission(
                            raw,
                            route.get("moduleRef").getAsString(),
                            route.get("modelId").getAsString(),
                            actualBackend,
                            limits,
                            profile,
                            runLimits);
            byte[] requestBytes =
                    CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(request));
            assertEquals(
                    testCase.get("requestSha256").getAsString(),
                    CanonicalCodec.sha256(requestBytes),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("requestSha256").getAsString(),
                    ModelRunRequestCodec.requestDigest(request));

            JsonObject acceptedJson = acceptedJson(testCase);
            var accepted = ModelRunRequestCodec.validateAcceptance(request, acceptedJson);
            assertEquals(
                    testCase.get("acceptedSha256").getAsString(),
                    CanonicalCodec.sha256(
                            CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(accepted))));
        }
    }

    @Test
    void targetRasterFitUsesAllThreeInclusiveLimits() throws IOException {
        JsonObject testCase = caseAt(0);
        var request = ModelRunRequestCodec.parseRequest(requestJson(testCase));
        var raster = request.inputs().get(0).rasterInput().frame().transform().raster();
        JsonObject exactJson = testCase.getAsJsonObject("targetRasterLimits");
        var exact = ExecutionLimitsCodec.parseTargetRasterLimits(exactJson);
        assertTrue(ExecutionLimitsCodec.fitsTargetRaster(raster, exact));
        assertFalse(
                ExecutionLimitsCodec.fitsTargetRaster(
                        raster,
                        new ExecutionLimitsCodec.TargetRasterLimits(
                                exact.maxWidth() - 1, exact.maxHeight(), exact.maxPixels())));
        assertFalse(
                ExecutionLimitsCodec.fitsTargetRaster(
                        raster,
                        new ExecutionLimitsCodec.TargetRasterLimits(
                                exact.maxWidth(), exact.maxHeight() - 1, exact.maxPixels())));
        assertFalse(
                ExecutionLimitsCodec.fitsTargetRaster(
                        raster,
                        new ExecutionLimitsCodec.TargetRasterLimits(
                                exact.maxWidth(), exact.maxHeight(), exact.maxPixels() - 1)));
        assertEquals(exactJson, ExecutionLimitsCodec.toJson(exact));
    }

    @Test
    void requestRejectsIdentityScopeAndInputCollectionMismatch() throws IOException {
        JsonObject request = requestJson(caseAt(0));

        JsonObject invalid = request.deepCopy();
        invalid.add("inputs", new JsonArray());
        JsonObject emptyInputs = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(emptyInputs));

        JsonObject distinctInput = inputs(request).get(0).getAsJsonObject().deepCopy();
        distinctInput.addProperty("output_generation_id", "11111111-1111-4111-8111-111111111111");
        JsonObject parentScope = new JsonObject();
        parentScope.addProperty("type", "parent");
        parentScope.add(
                "region",
                distinctInput
                        .getAsJsonObject("raster_input")
                        .getAsJsonObject("frame")
                        .get("region")
                        .deepCopy());
        parentScope.addProperty("parent_id", "22222222-2222-4222-8222-222222222222");
        distinctInput.getAsJsonObject("output").add("scope", parentScope);

        invalid = request.deepCopy();
        inputs(invalid).add(distinctInput.deepCopy());
        JsonObject duplicateInputId = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(duplicateInputId));

        invalid = request.deepCopy();
        inputs(invalid).get(0).getAsJsonObject().addProperty("input_id", 11);
        distinctInput.addProperty("input_id", 3);
        inputs(invalid).add(distinctInput);
        JsonObject descendingInputIds = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(descendingInputIds));

        invalid = request.deepCopy();
        inputs(invalid)
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("output")
                .getAsJsonObject("producer")
                .addProperty("step_id", "lumen");
        JsonObject wrongOutputProducer = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(wrongOutputProducer));

        invalid = request.deepCopy();
        inputs(invalid)
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("output")
                .getAsJsonObject("scope")
                .getAsJsonObject("region")
                .addProperty("roi_id", "11111111-1111-4111-8111-111111111111");
        JsonObject wrongRegion = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(wrongRegion));

        invalid = request.deepCopy();
        invalid.getAsJsonObject("expected_backend")
                .getAsJsonObject("rasterizer")
                .addProperty("rasterization_contract_digest", "1".repeat(64));
        JsonObject wrongRasterizer = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(wrongRasterizer));

        invalid = request.deepCopy();
        JsonObject second = inputs(invalid).get(0).getAsJsonObject().deepCopy();
        second.addProperty("input_id", 1);
        inputs(invalid).add(second);
        JsonObject duplicateGeneration = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(duplicateGeneration));

        invalid = request.deepCopy();
        second = inputs(invalid).get(0).getAsJsonObject().deepCopy();
        second.addProperty("input_id", 1);
        second.addProperty("output_generation_id", "11111111-1111-4111-8111-111111111111");
        inputs(invalid).add(second);
        JsonObject duplicateOutput = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(duplicateOutput));
    }

    @Test
    void routePlanFamilyProfileAndAcceptanceMismatchFailClosed() throws IOException {
        JsonObject finiteCase = caseAt(0);
        JsonObject finite = requestJson(finiteCase);
        JsonObject route = finiteCase(finiteCase).getAsJsonObject("route");
        JsonObject oversizedCase = caseAt(1);
        JsonObject oversizedJson = requestJson(oversizedCase);
        var oversized = ModelRunRequestCodec.parseRequest(oversizedJson);
        var profile = profile(oversizedCase);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunRequestCodec.validateRouteRequest(
                                finite, "histoforge/leydig", route.get("modelId").getAsString()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunRequestCodec.validateRouteRequest(
                                finite, route.get("moduleRef").getAsString(), "lumen"));

        JsonObject wrongBackendJson = finite.getAsJsonObject("expected_backend").deepCopy();
        wrongBackendJson.getAsJsonObject("artifact_digests").addProperty("weights", "4".repeat(64));
        var wrongBackend = InferenceIdentityCodec.parseBackendModelIdentity(wrongBackendJson);
        var exactFiniteLimits =
                ExecutionLimitsCodec.parseTargetRasterLimits(finiteCase.get("targetRasterLimits"));
        var runLimits =
                ExecutionLimitsCodec.parseModelRunLimits(
                        fixture("model-run-request-v1.json").get("testModelRunLimits"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunRequestCodec.validateAdmission(
                                finite,
                                route.get("moduleRef").getAsString(),
                                route.get("modelId").getAsString(),
                                wrongBackend,
                                exactFiniteLimits,
                                Optional.empty(),
                                runLimits));

        JsonObject invalid = finite.deepCopy();
        invalid.add("expected_profile", LargeRegionProfileCodec.toJson(profile.orElseThrow()));
        var finiteWithProfile = ModelRunRequestCodec.parseRequest(invalid);
        var finiteLimits =
                ExecutionLimitsCodec.parseTargetRasterLimits(finiteCase.get("targetRasterLimits"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunRequestCodec.validatePlanFamily(
                                finiteWithProfile, finiteLimits, profile));

        invalid = oversizedJson.deepCopy();
        invalid.remove("expected_profile");
        var oversizedWithoutProfile = ModelRunRequestCodec.parseRequest(invalid);
        var oversizedLimits =
                ExecutionLimitsCodec.parseTargetRasterLimits(
                        oversizedCase.get("targetRasterLimits"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunRequestCodec.validatePlanFamily(
                                oversizedWithoutProfile, oversizedLimits, profile));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunRequestCodec.validatePlanFamily(
                                oversized, oversizedLimits, Optional.empty()));

        var wrongProfile =
                Optional.of(
                        LargeRegionProfileCodec.parse(
                                fixture("large-region-execution-v1.json")
                                        .getAsJsonArray("profiles")
                                        .get(0)
                                        .getAsJsonObject()
                                        .get("input")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunRequestCodec.validatePlanFamily(
                                oversized, oversizedLimits, wrongProfile));

        var finiteRequest = ModelRunRequestCodec.parseRequest(finite);
        for (String field : new String[] {"action_id", "model_run_id", "request_digest"}) {
            JsonObject accepted = acceptedJson(finiteCase);
            accepted.addProperty(
                    field,
                    "request_digest".equals(field)
                            ? "0".repeat(64)
                            : "11111111-1111-4111-8111-111111111111");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelRunRequestCodec.validateAcceptance(finiteRequest, accepted));
        }
    }

    @Test
    void mixedFiniteAndOversizedInputsAreRejectedAndCollectionsAreImmutable() throws IOException {
        JsonObject firstCase = caseAt(0);
        JsonObject raw = requestJson(firstCase);
        inputs(raw).get(0).getAsJsonObject().addProperty("input_id", 3);
        JsonObject secondFinite =
                JsonParser.parseString(
                                fixture("finite-inference-v3.json")
                                        .getAsJsonArray("cases")
                                        .get(1)
                                        .getAsJsonObject()
                                        .get("requestCanonical")
                                        .getAsString())
                        .getAsJsonObject();
        JsonObject second = new JsonObject();
        second.addProperty("input_id", 11);
        second.addProperty("output_generation_id", "11111111-1111-4111-8111-111111111111");
        JsonObject output = new JsonObject();
        output.add("producer", raw.get("producer").deepCopy());
        JsonObject scope = new JsonObject();
        scope.addProperty("type", "field");
        scope.add(
                "region",
                secondFinite
                        .getAsJsonObject("raster_input")
                        .getAsJsonObject("frame")
                        .get("region")
                        .deepCopy());
        output.add("scope", scope);
        second.add("output", output);
        second.add("raster_input", secondFinite.get("raster_input").deepCopy());
        inputs(raw).add(second);
        var request = ModelRunRequestCodec.parseRequest(raw);
        assertEquals(3, request.inputs().get(0).inputId());
        assertEquals(11, request.inputs().get(1).inputId());
        assertThrows(UnsupportedOperationException.class, () -> request.inputs().clear());

        var limits = new ExecutionLimitsCodec.TargetRasterLimits(512, 768, 600_000);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.validatePlanFamily(request, limits, Optional.empty()));
    }

    @Test
    void everyRequestOwnedDurableRunAdmissionLimitIsEnforced() throws IOException {
        JsonObject raw = requestJson(caseAt(0));
        inputs(raw).get(0).getAsJsonObject().addProperty("input_id", 3);
        JsonObject secondFinite =
                JsonParser.parseString(
                                fixture("finite-inference-v3.json")
                                        .getAsJsonArray("cases")
                                        .get(1)
                                        .getAsJsonObject()
                                        .get("requestCanonical")
                                        .getAsString())
                        .getAsJsonObject();
        JsonObject second = new JsonObject();
        second.addProperty("input_id", 11);
        second.addProperty("output_generation_id", "11111111-1111-4111-8111-111111111111");
        JsonObject output = new JsonObject();
        output.add("producer", raw.get("producer").deepCopy());
        JsonObject scope = new JsonObject();
        scope.addProperty("type", "field");
        scope.add(
                "region",
                secondFinite
                        .getAsJsonObject("raster_input")
                        .getAsJsonObject("frame")
                        .get("region")
                        .deepCopy());
        output.add("scope", scope);
        second.add("output", output);
        second.add("raster_input", secondFinite.get("raster_input").deepCopy());
        inputs(raw).add(second);

        var request = ModelRunRequestCodec.parseRequest(raw);
        JsonObject baseLimits =
                fixture("model-run-request-v1.json")
                        .getAsJsonObject("testModelRunLimits")
                        .deepCopy();
        assertEquals(
                request,
                ModelRunRequestCodec.validateResourceLimits(
                        request, ExecutionLimitsCodec.parseModelRunLimits(baseLimits)));

        long requestBytes =
                CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(request)).length;
        long aggregatePixels = 0;
        long aggregateMaskVertices = 0;
        for (var input : request.inputs()) {
            var raster = input.rasterInput().frame().transform().raster();
            aggregatePixels += (long) raster.width() * raster.height();
            aggregateMaskVertices +=
                    RasterInputCodec.geometryVertexCount(
                            input.rasterInput().frame().inclusionMaskRaster());
        }
        Object[][] mutations = {
            {"max_request_canonical_bytes", requestBytes - 1},
            {"max_inputs", 1},
            {"max_units", 1},
            {"max_aggregate_target_raster_pixels", aggregatePixels - 1},
            {"max_aggregate_inclusion_mask_vertices", aggregateMaskVertices - 1}
        };
        for (Object[] mutation : mutations) {
            JsonObject limits = baseLimits.deepCopy();
            limits.addProperty((String) mutation[0], (Number) mutation[1]);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ModelRunRequestCodec.validateResourceLimits(
                                    request, ExecutionLimitsCodec.parseModelRunLimits(limits)),
                    (String) mutation[0]);
        }

        JsonObject limits = baseLimits.deepCopy();
        limits.getAsJsonObject("target_raster_per_input").addProperty("max_width", 1023);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunRequestCodec.validateResourceLimits(
                                request, ExecutionLimitsCodec.parseModelRunLimits(limits)));

        Map<String, Long> requested =
                Map.of(
                        "max_transferred_bytes", request.resultBudget().maxTransferredBytes(),
                        "max_decoded_bytes", request.resultBudget().maxDecodedBytes(),
                        "max_record_bytes", request.resultBudget().maxRecordBytes(),
                        "max_features", request.resultBudget().maxFeatures(),
                        "max_vertices", request.resultBudget().maxVertices());
        for (var entry : requested.entrySet()) {
            JsonObject resultLimits = baseLimits.deepCopy();
            resultLimits
                    .getAsJsonObject("result")
                    .addProperty(entry.getKey(), entry.getValue() - 1);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ModelRunRequestCodec.validateResourceLimits(
                                    request,
                                    ExecutionLimitsCodec.parseModelRunLimits(resultLimits)),
                    entry.getKey());
        }
    }

    @Test
    void haloOverrideIsStrictAndOnlyAppliesToCoreHaloRequests() throws IOException {
        for (String invalidValue : new String[] {"null", "-1", "true", "0.5", "2147483648"}) {
            JsonObject request = requestJson(caseAt(1));
            request.add("halo_px_override", JsonParser.parseString(invalidValue));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelRunRequestCodec.parseRequest(request),
                    invalidValue);
        }
        for (int caseIndex : new int[] {0, 2}) {
            JsonObject request = requestJson(caseAt(caseIndex));
            request.addProperty("halo_px_override", 0);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelRunRequestCodec.parseRequest(request));
        }
        JsonObject request = requestJson(caseAt(1));
        request.addProperty("halo_px_override", 0);
        var zero = ModelRunRequestCodec.parseRequest(request);
        assertEquals(Optional.of(0), zero.haloPxOverride());
        assertEquals(request, ModelRunRequestCodec.toJson(zero));
    }

    @Test
    void processingRasterAdmissionUsesExactClippedCustomHaloWindows() throws IOException {
        JsonObject request = requestJson(caseAt(1));
        JsonObject profile = request.getAsJsonObject("expected_profile");
        profile.addProperty("core_width_px", 256);
        profile.addProperty("core_height_px", 192);
        profile.remove("profile_digest");
        profile.addProperty("profile_digest", CanonicalCodec.canonicalDigest(profile));
        request.addProperty("halo_px_override", 0);
        JsonObject limits =
                fixture("model-run-request-v1.json")
                        .getAsJsonObject("testModelRunLimits")
                        .deepCopy();
        limits.add(
                "processing_raster",
                ExecutionLimitsCodec.toJson(
                        new ExecutionLimitsCodec.TargetRasterLimits(256, 192, 256 * 192)));
        var zero = ModelRunRequestCodec.parseRequest(request);
        assertEquals(
                zero,
                ModelRunRequestCodec.validateResourceLimits(
                        zero, ExecutionLimitsCodec.parseModelRunLimits(limits)));
        request.addProperty("halo_px_override", 1);
        var expanded = ModelRunRequestCodec.parseRequest(request);
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        ModelRunRequestCodec.validateResourceLimits(
                                                expanded,
                                                ExecutionLimitsCodec.parseModelRunLimits(limits)))
                        .getMessage()
                        .contains("processing_raster"));

        // Admission is based on clipped allocation, not nominal core plus twice the halo.
        request.addProperty("halo_px_override", Integer.MAX_VALUE);
        var clipped = ModelRunRequestCodec.parseRequest(request);
        limits.add(
                "processing_raster",
                ExecutionLimitsCodec.toJson(
                        new ExecutionLimitsCodec.TargetRasterLimits(512, 384, 512 * 384)));
        assertEquals(
                clipped,
                ModelRunRequestCodec.validateResourceLimits(
                        clipped, ExecutionLimitsCodec.parseModelRunLimits(limits)));
        for (String field : new String[] {"max_width", "max_height", "max_pixels"}) {
            JsonObject smaller = limits.deepCopy();
            JsonObject processing = smaller.getAsJsonObject("processing_raster");
            processing.addProperty(field, processing.get(field).getAsLong() - 1);
            assertTrue(
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () ->
                                            ModelRunRequestCodec.validateResourceLimits(
                                                    clipped,
                                                    ExecutionLimitsCodec.parseModelRunLimits(
                                                            smaller)))
                            .getMessage()
                            .contains("processing_raster"));
        }
        for (int caseIndex : new int[] {0, 2}) {
            var completeRaster = ModelRunRequestCodec.parseRequest(requestJson(caseAt(caseIndex)));
            JsonObject smaller = limits.deepCopy();
            smaller.getAsJsonObject("processing_raster").addProperty("max_width", 511);
            assertTrue(
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () ->
                                            ModelRunRequestCodec.validateResourceLimits(
                                                    completeRaster,
                                                    ExecutionLimitsCodec.parseModelRunLimits(
                                                            smaller)))
                            .getMessage()
                            .contains("processing_raster"));
        }
    }

    @Test
    void oldAndFutureRequestsAndAcceptancesAreRejected() throws IOException {
        JsonObject testCase = caseAt(0);
        for (int schemaVersion : new int[] {3, 5}) {
            JsonObject request = requestJson(testCase);
            request.addProperty("schema_version", schemaVersion);
            assertEquals(
                    "unsupported model run schema_version",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> ModelRunRequestCodec.parseRequest(request))
                            .getMessage());
            JsonObject accepted = acceptedJson(testCase);
            accepted.addProperty("schema_version", schemaVersion);
            assertEquals(
                    "unsupported model run schema_version",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> ModelRunRequestCodec.parseAccepted(accepted))
                            .getMessage());
        }
    }

    @Test
    void requestSchemaAndOptionalFieldsAreClosed() throws IOException {
        JsonObject request = requestJson(caseAt(0));

        JsonObject invalid = request.deepCopy();
        invalid.addProperty("schema_version", 1);
        JsonObject wrongSchema = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(wrongSchema));

        invalid = request.deepCopy();
        invalid.addProperty("extra", true);
        JsonObject extra = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> ModelRunRequestCodec.parseRequest(extra));

        invalid = request.deepCopy();
        invalid.add("resume_from", null);
        JsonObject nullResume = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(nullResume));

        invalid = request.deepCopy();
        JsonObject selfResume = new JsonObject();
        selfResume.addProperty("previous_run_id", invalid.get("model_run_id").getAsString());
        selfResume.addProperty("previous_manifest_digest", "a".repeat(64));
        invalid.add("resume_from", selfResume);
        JsonObject sameRunResume = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunRequestCodec.parseRequest(sameRunResume));
    }

    private static JsonObject requestJson(JsonObject testCase) throws IOException {
        JsonObject finite =
                JsonParser.parseString(finiteCase(testCase).get("requestCanonical").getAsString())
                        .getAsJsonObject();
        JsonObject region =
                finite.getAsJsonObject("raster_input")
                        .getAsJsonObject("frame")
                        .getAsJsonObject("region");
        JsonObject output = new JsonObject();
        output.add("producer", finite.get("producer").deepCopy());
        JsonObject scope = new JsonObject();
        scope.addProperty("type", "field");
        scope.add("region", region.deepCopy());
        output.add("scope", scope);
        JsonObject input = new JsonObject();
        input.addProperty("input_id", testCase.get("inputId").getAsInt());
        input.addProperty("output_generation_id", testCase.get("outputGenerationId").getAsString());
        input.add("output", output);
        input.add("raster_input", finite.get("raster_input").deepCopy());

        JsonObject request = new JsonObject();
        request.addProperty("schema_version", ModelRunRequestCodec.SCHEMA_VERSION);
        request.addProperty("action_id", testCase.get("actionId").getAsString());
        request.addProperty("model_run_id", testCase.get("modelRunId").getAsString());
        request.add("producer", finite.get("producer").deepCopy());
        request.add("expected_backend", finite.get("expected_backend").deepCopy());
        if (testCase.has("largeRegionProfileCase")) {
            request.add(
                    "expected_profile",
                    fixture("large-region-execution-v1.json")
                            .getAsJsonArray("profiles")
                            .get(testCase.get("largeRegionProfileCase").getAsInt())
                            .getAsJsonObject()
                            .get("input")
                            .deepCopy());
        }
        JsonArray inputs = new JsonArray();
        inputs.add(input);
        request.add("inputs", inputs);
        request.add("result_budget", finite.get("result_budget").deepCopy());
        if (testCase.has("resumeFrom")) {
            request.add("resume_from", testCase.get("resumeFrom").deepCopy());
        }
        if (testCase.has("haloPxOverride")) {
            request.add("halo_px_override", testCase.get("haloPxOverride").deepCopy());
        }
        return request;
    }

    private static Optional<LargeRegionProfileCodec.LargeRegionProfile> profile(JsonObject testCase)
            throws IOException {
        if (!testCase.has("largeRegionProfileCase")) {
            return Optional.empty();
        }
        return Optional.of(
                LargeRegionProfileCodec.parse(
                        fixture("large-region-execution-v1.json")
                                .getAsJsonArray("profiles")
                                .get(testCase.get("largeRegionProfileCase").getAsInt())
                                .getAsJsonObject()
                                .get("input")));
    }

    private static JsonObject acceptedJson(JsonObject testCase) {
        JsonObject accepted = new JsonObject();
        accepted.addProperty("schema_version", ModelRunRequestCodec.SCHEMA_VERSION);
        accepted.addProperty("action_id", testCase.get("actionId").getAsString());
        accepted.addProperty("model_run_id", testCase.get("modelRunId").getAsString());
        accepted.addProperty("request_digest", testCase.get("requestSha256").getAsString());
        return accepted;
    }

    private static JsonArray inputs(JsonObject request) {
        return request.getAsJsonArray("inputs");
    }

    private static JsonObject caseAt(int index) throws IOException {
        return fixture("model-run-request-v1.json")
                .getAsJsonArray("cases")
                .get(index)
                .getAsJsonObject();
    }

    private static JsonObject finiteCase(JsonObject testCase) throws IOException {
        return fixture("finite-inference-v3.json")
                .getAsJsonArray("cases")
                .get(testCase.get("finiteFixtureCase").getAsInt())
                .getAsJsonObject();
    }

    private static JsonObject fixture(String name) throws IOException {
        try (InputStream input =
                ModelRunRequestCodecTest.class
                        .getClassLoader()
                        .getResourceAsStream("contracts/" + name)) {
            if (input == null) {
                throw new IOException("missing contract fixture: " + name);
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
