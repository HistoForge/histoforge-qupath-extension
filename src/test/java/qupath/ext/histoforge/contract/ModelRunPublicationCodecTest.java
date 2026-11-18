package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Runs the shared immutable model-run publication and status contract. */
class ModelRunPublicationCodecTest {

    @Test
    void sharedPlansManifestsAndStatusesBindExactBytesAndDigests() throws IOException {
        JsonObject fixture = fixture("model-run-publication-v1.json");
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        JsonArray cases = fixture.getAsJsonArray("cases");
        for (int index = 0; index < cases.size(); index++) {
            JsonObject testCase = cases.get(index).getAsJsonObject();
            CaseValues values = caseValues(testCase);

            assertEquals(
                    testCase.get("planSha256").getAsString(),
                    digest(ModelExecutionPlanCodec.toJson(values.plan())),
                    name(testCase));
            if (testCase.has("executionIdentityDigest")) {
                assertEquals(
                        testCase.get("executionIdentityDigest").getAsString(),
                        ModelRunPublicationCodec.executionIdentityDigest(
                                values.request(), values.inputId(), 0, values.limits()),
                        name(testCase));
            }
            assertEquals(
                    testCase.get("manifestDigest").getAsString(),
                    values.manifest().get("manifest_digest").getAsString(),
                    name(testCase));
            assertEquals(
                    testCase.get("manifestArtifactSize").getAsLong(),
                    CanonicalCodec.canonicalBytes(values.manifest()).length,
                    name(testCase));
            assertEquals(
                    testCase.get("manifestArtifactSha256").getAsString(),
                    digest(values.manifest()),
                    name(testCase));

            var validatedManifest =
                    ModelRunPublicationCodec.validateManifest(
                            values.manifest(), values.request(), values.limits());
            assertEquals(
                    values.manifest(),
                    ModelRunPublicationCodec.toJson(validatedManifest),
                    name(testCase));
            var validatedStatus =
                    ModelRunStatusCodec.validateProjection(
                            values.status(),
                            values.request(),
                            values.limits(),
                            Optional.of(values.manifest()));
            assertEquals(
                    testCase.get("statusSha256").getAsString(),
                    digest(ModelRunStatusCodec.toJson(validatedStatus)),
                    name(testCase));
        }
    }

    @Test
    void oldAndFutureManifestsAndStatusesAreRejected() throws IOException {
        CaseValues values = caseValues(caseAt(0));
        for (int schemaVersion : new int[] {3, 5}) {
            JsonObject manifest = values.manifest().deepCopy();
            manifest.addProperty("schema_version", schemaVersion);
            redigestManifest(manifest);
            assertEquals(
                    "unsupported model run publication schema_version",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> ModelRunPublicationCodec.parseManifest(manifest))
                            .getMessage());
            JsonObject status = values.status().deepCopy();
            status.addProperty("schema_version", schemaVersion);
            assertEquals(
                    "unsupported model run status schema_version",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> ModelRunStatusCodec.parse(status))
                            .getMessage());
        }
    }

    @Test
    void preprocessingAndExecutionIdentitiesBindTheSettledInputs() throws IOException {
        CaseValues values = caseValues(caseAt(1));
        var input = values.request().inputs().getFirst();
        String preprocessing =
                ModelRunPublicationCodec.preprocessingContextDigest(
                        input, values.request().expectedBackend());
        assertEquals(
                ((ModelExecutionPlanCodec.CoreHaloPlan) values.plan()).preprocessingContextDigest(),
                preprocessing);

        JsonObject changedBackend =
                InferenceIdentityCodec.toJson(values.request().expectedBackend()).deepCopy();
        changedBackend.addProperty("preprocessing_contract_digest", "5".repeat(64));
        assertNotEquals(
                preprocessing,
                ModelRunPublicationCodec.preprocessingContextDigest(
                        input, InferenceIdentityCodec.parseBackendModelIdentity(changedBackend)));

        JsonObject changedRequestJson = ModelRunRequestCodec.toJson(values.request()).deepCopy();
        changedRequestJson.addProperty("action_id", "51515151-5151-4151-8151-515151515151");
        var changedRequest = ModelRunRequestCodec.parseRequest(changedRequestJson);
        assertNotEquals(
                ModelRunPublicationCodec.executionIdentityDigest(
                        values.request(), values.inputId(), 0, values.limits()),
                ModelRunPublicationCodec.executionIdentityDigest(
                        changedRequest, values.inputId(), 0, values.limits()));
    }

    @ParameterizedTest
    @EnumSource(
            value = GatewayErrorCodec.Code.class,
            names = {"INSUFFICIENT_CONTEXT", "RESULT_LIMIT_EXCEEDED"})
    void partialFailureIsDigestBoundAndMustMatchStatus(GatewayErrorCodec.Code code)
            throws IOException {
        JsonObject testCase = caseAt(1);
        CaseValues values = caseValues(testCase);
        var failure =
                new GatewayErrorCodec.Error(
                        1, code, "actual backend failure", Optional.empty(), Optional.empty());
        JsonObject manifest = values.manifest().deepCopy();
        manifest.add("failure", GatewayErrorCodec.toJson(failure));
        redigestManifest(manifest);
        assertNotEquals(values.manifest().get("manifest_digest"), manifest.get("manifest_digest"));
        var parsed =
                ModelRunPublicationCodec.validateManifest(
                        manifest, values.request(), values.limits());
        assertEquals(Optional.of(failure), parsed.failure());
        assertEquals(manifest, ModelRunPublicationCodec.toJson(parsed));

        JsonObject status = status(testCase, values.request(), manifest);
        status.add("failure", GatewayErrorCodec.toJson(failure));
        var projection =
                ModelRunStatusCodec.validateProjection(
                        status, values.request(), values.limits(), Optional.of(manifest));
        assertEquals(Optional.of(failure), projection.failure());
        assertEquals(status, ModelRunStatusCodec.toJson(projection));

        for (boolean remove : new boolean[] {false, true}) {
            JsonObject alteredManifest = manifest.deepCopy();
            JsonObject alteredStatus = status.deepCopy();
            if (remove) {
                alteredManifest.remove("failure");
                alteredStatus.remove("failure");
            } else {
                alteredManifest.getAsJsonObject("failure").addProperty("message", "wrong cause");
                alteredStatus.getAsJsonObject("failure").addProperty("message", "wrong cause");
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ModelRunPublicationCodec.parseManifest(alteredManifest));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ModelRunStatusCodec.validateProjection(
                                    alteredStatus,
                                    values.request(),
                                    values.limits(),
                                    Optional.of(manifest)));
        }
        JsonObject unexpectedFailure = values.status().deepCopy();
        unexpectedFailure.add("failure", GatewayErrorCodec.toJson(failure));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunStatusCodec.validateProjection(
                                unexpectedFailure,
                                values.request(),
                                values.limits(),
                                Optional.of(values.manifest())));
    }

    @Test
    void completeCannotCarryFailureAndPartialFailureCannotBeNull() throws IOException {
        CaseValues complete = caseValues(caseAt(0));
        JsonObject failure =
                GatewayErrorCodec.toJson(
                        new GatewayErrorCodec.Error(
                                1,
                                GatewayErrorCodec.Code.RUNNER_FAILED,
                                "actual failure",
                                Optional.empty(),
                                Optional.empty()));
        JsonObject invalidManifest = complete.manifest().deepCopy();
        invalidManifest.add("failure", failure);
        redigestManifest(invalidManifest);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunPublicationCodec.parseManifest(invalidManifest));
        JsonObject invalidStatus = complete.status().deepCopy();
        invalidStatus.add("failure", failure);
        assertThrows(
                IllegalArgumentException.class, () -> ModelRunStatusCodec.parse(invalidStatus));

        CaseValues partial = caseValues(caseAt(1));
        JsonObject nullManifest = partial.manifest().deepCopy();
        nullManifest.add("failure", JsonNull.INSTANCE);
        redigestManifest(nullManifest);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunPublicationCodec.parseManifest(nullManifest));
        JsonObject nullStatus = partial.status().deepCopy();
        nullStatus.add("failure", JsonNull.INSTANCE);
        assertThrows(IllegalArgumentException.class, () -> ModelRunStatusCodec.parse(nullStatus));
    }

    @Test
    void manifestRejectsCrossRecordPlanAndCoverageMismatches() throws IOException {
        CaseValues values = caseValues(caseAt(1));

        JsonObject wrongAction = values.manifest().deepCopy();
        wrongAction.addProperty("action_id", "51515151-5151-4151-8151-515151515151");
        redigestManifest(wrongAction);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                wrongAction, values.request(), values.limits()));

        JsonObject wrongIdentity = values.manifest().deepCopy();
        wrongIdentity
                .getAsJsonArray("completed_units")
                .get(0)
                .getAsJsonObject()
                .addProperty("execution_identity_digest", "0".repeat(64));
        redigestManifest(wrongIdentity);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                wrongIdentity, values.request(), values.limits()));

        JsonObject falseComplete = values.manifest().deepCopy();
        falseComplete.addProperty("complete", true);
        redigestManifest(falseComplete);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                falseComplete, values.request(), values.limits()));
    }

    @Test
    void planSpecificPublicationsAndFeatureStreamsFailClosed() throws IOException {
        CaseValues global = caseValues(caseAt(2));
        JsonObject wrongGlobal = global.manifest().deepCopy();
        JsonObject checkpoint =
                wrongGlobal.getAsJsonArray("checkpoints").remove(0).getAsJsonObject();
        JsonObject completedUnit = new JsonObject();
        completedUnit.addProperty("input_id", checkpoint.get("input_id").getAsInt());
        completedUnit.addProperty("unit_id", checkpoint.get("unit_id").getAsInt());
        completedUnit.add("reusable_state", checkpoint.get("state").deepCopy());
        completedUnit.addProperty(
                "execution_identity_digest",
                checkpoint.get("execution_identity_digest").getAsString());
        wrongGlobal.getAsJsonArray("completed_units").add(completedUnit);
        redigestManifest(wrongGlobal);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                wrongGlobal, global.request(), global.limits()));

        CaseValues finite = caseValues(caseAt(0));
        JsonObject wrongFinite = finite.manifest().deepCopy();
        JsonObject completedInput =
                wrongFinite.getAsJsonArray("completed_inputs").remove(0).getAsJsonObject();
        JsonObject finiteUnit = new JsonObject();
        finiteUnit.addProperty("input_id", completedInput.get("input_id").getAsInt());
        finiteUnit.addProperty("unit_id", 0);
        finiteUnit.add(
                "reusable_state",
                completedInput.getAsJsonObject("feature_stream_artifact").deepCopy());
        finiteUnit.addProperty(
                "execution_identity_digest",
                ModelRunPublicationCodec.executionIdentityDigest(
                        finite.request(), finite.inputId(), 0, finite.limits()));
        wrongFinite.getAsJsonArray("completed_units").add(finiteUnit);
        wrongFinite.addProperty("complete", false);
        redigestManifest(wrongFinite);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                wrongFinite, finite.request(), finite.limits()));

        JsonObject wrongStream = finite.manifest().deepCopy();
        wrongStream
                .getAsJsonArray("completed_inputs")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("feature_stream_artifact")
                .addProperty("media_type", "application/json");
        redigestManifest(wrongStream);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                wrongStream, finite.request(), finite.limits()));

        JsonObject inconsistentCounts = finite.manifest().deepCopy();
        inconsistentCounts
                .getAsJsonArray("completed_inputs")
                .get(0)
                .getAsJsonObject()
                .addProperty("vertex_count", 3);
        redigestManifest(inconsistentCounts);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                inconsistentCounts, finite.request(), finite.limits()));
    }

    @Test
    void resultAndRecoveryArtifactBudgetsAreEnforced() throws IOException {
        CaseValues finite = caseValues(caseAt(0));
        JsonObject oversizedStream = finite.manifest().deepCopy();
        oversizedStream
                .getAsJsonArray("completed_inputs")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("feature_stream_artifact")
                .addProperty(
                        "size_bytes", finite.request().resultBudget().maxTransferredBytes() + 1);
        redigestManifest(oversizedStream);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                oversizedStream, finite.request(), finite.limits()));

        CaseValues core = caseValues(caseAt(1));
        long stateBytes =
                core.manifest()
                        .getAsJsonArray("completed_units")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("reusable_state")
                        .get("size_bytes")
                        .getAsLong();
        JsonObject perUnitLimits =
                fixture("model-run-request-v1.json")
                        .getAsJsonObject("testModelRunLimits")
                        .deepCopy();
        perUnitLimits.addProperty("max_recovery_state_bytes_per_unit", stateBytes - 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                core.manifest(),
                                core.request(),
                                ExecutionLimitsCodec.parseModelRunLimits(perUnitLimits)));

        JsonObject perRunLimits =
                fixture("model-run-request-v1.json")
                        .getAsJsonObject("testModelRunLimits")
                        .deepCopy();
        perRunLimits.addProperty("max_recovery_state_bytes_per_run", stateBytes - 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunPublicationCodec.validateManifest(
                                core.manifest(),
                                core.request(),
                                ExecutionLimitsCodec.parseModelRunLimits(perRunLimits)));
    }

    @Test
    void statusPhaseCountsAndArtifactBindingsFailClosed() throws IOException {
        CaseValues values = caseValues(caseAt(0));

        JsonObject failedWithManifest = values.status().deepCopy();
        failedWithManifest.addProperty("phase", "FAILED");
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunStatusCodec.parse(failedWithManifest));

        JsonObject wrongRequestArtifact = values.status().deepCopy();
        wrongRequestArtifact
                .getAsJsonObject("request_artifact")
                .addProperty("sha256", "0".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunStatusCodec.validateProjection(
                                wrongRequestArtifact,
                                values.request(),
                                values.limits(),
                                Optional.of(values.manifest())));

        JsonObject wrongTotal = values.status().deepCopy();
        wrongTotal.addProperty("total_unit_count", 2);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunStatusCodec.validateProjection(
                                wrongTotal,
                                values.request(),
                                values.limits(),
                                Optional.of(values.manifest())));

        JsonObject wrongPhase = values.status().deepCopy();
        wrongPhase.addProperty("phase", "PARTIAL");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunStatusCodec.validateProjection(
                                wrongPhase,
                                values.request(),
                                values.limits(),
                                Optional.of(values.manifest())));

        JsonObject queued = values.status().deepCopy();
        queued.addProperty("phase", "QUEUED");
        queued.remove("terminal_manifest");
        queued.addProperty("completed_unit_count", 0);
        assertEquals(
                ModelRunStatusCodec.Phase.QUEUED,
                ModelRunStatusCodec.validateProjection(
                                queued, values.request(), values.limits(), Optional.empty())
                        .phase());
        queued.addProperty("completed_unit_count", 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelRunStatusCodec.validateProjection(
                                queued, values.request(), values.limits(), Optional.empty()));
    }

    private static CaseValues caseValues(JsonObject testCase) throws IOException {
        JsonObject requestCase = requestCase(testCase.get("requestFixtureCase").getAsInt());
        var request = ModelRunRequestCodec.parseRequest(requestJson(requestCase));
        var limits =
                ExecutionLimitsCodec.parseModelRunLimits(
                        fixture("model-run-request-v1.json").get("testModelRunLimits"));
        Map<Integer, ModelExecutionPlanCodec.ModelExecutionPlan> plans =
                ModelRunPublicationCodec.buildExpectedPlans(request, limits);
        int inputId = request.inputs().getFirst().inputId();
        var plan = plans.get(inputId);
        JsonObject manifest = manifest(testCase, request, plan);
        JsonObject status = status(testCase, request, manifest);
        return new CaseValues(request, limits, inputId, plan, manifest, status);
    }

    private static JsonObject manifest(
            JsonObject testCase,
            ModelRunRequestCodec.ModelRunRequest request,
            ModelExecutionPlanCodec.ModelExecutionPlan plan) {
        int inputId = request.inputs().getFirst().inputId();
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", ModelRunRequestCodec.SCHEMA_VERSION);
        value.addProperty("model_run_id", request.modelRunId());
        value.addProperty("action_id", request.actionId());
        value.add("backend", InferenceIdentityCodec.toJson(request.expectedBackend()));
        value.addProperty("request_digest", ModelRunRequestCodec.requestDigest(request));
        value.add("completed_units", new JsonArray());
        value.add("checkpoints", new JsonArray());
        value.add("failed_units", new JsonArray());
        value.add("completed_inputs", new JsonArray());
        value.addProperty("complete", testCase.get("phase").getAsString().equals("COMPLETE"));

        switch (name(testCase)) {
            case "complete-finite" -> {
                JsonObject result = new JsonObject();
                result.addProperty("input_id", inputId);
                result.add(
                        "input_identity",
                        FiniteInferenceCodec.toJson(
                                FiniteInferenceCodec.buildBackendInputIdentity(
                                        request.inputs().getFirst().rasterInput())));
                result.add("execution_plan", ModelExecutionPlanCodec.toJson(plan));
                result.add(
                        "feature_stream_artifact",
                        testCase.get("featureStreamArtifact").deepCopy());
                result.addProperty("feature_count", 0);
                result.addProperty("vertex_count", 0);
                value.getAsJsonArray("completed_inputs").add(result);
            }
            case "partial-core-halo" -> {
                JsonObject unit = new JsonObject();
                unit.addProperty("input_id", inputId);
                unit.addProperty("unit_id", 0);
                unit.add("reusable_state", testCase.get("reusableStateArtifact").deepCopy());
                unit.addProperty(
                        "execution_identity_digest",
                        testCase.get("executionIdentityDigest").getAsString());
                value.getAsJsonArray("completed_units").add(unit);
            }
            case "partial-global-checkpoint" -> {
                JsonObject checkpoint = new JsonObject();
                checkpoint.addProperty("input_id", inputId);
                checkpoint.addProperty("unit_id", 0);
                checkpoint.addProperty("sequence", testCase.get("checkpointSequence").getAsLong());
                checkpoint.add("state", testCase.get("checkpointStateArtifact").deepCopy());
                checkpoint.addProperty(
                        "execution_identity_digest",
                        testCase.get("executionIdentityDigest").getAsString());
                value.getAsJsonArray("checkpoints").add(checkpoint);

                JsonObject failure = new JsonObject();
                failure.addProperty("input_id", inputId);
                failure.addProperty("unit_id", 0);
                JsonObject error = new JsonObject();
                error.addProperty("schema_version", 1);
                error.addProperty("code", "RUNNER_FAILED");
                error.addProperty("message", "model tile failed");
                failure.add("failure", error);
                value.getAsJsonArray("failed_units").add(failure);
            }
            default -> throw new IllegalArgumentException("unsupported fixture case");
        }
        redigestManifest(value);
        return value;
    }

    private static JsonObject status(
            JsonObject testCase,
            ModelRunRequestCodec.ModelRunRequest request,
            JsonObject manifest) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", ModelRunRequestCodec.SCHEMA_VERSION);
        value.addProperty("model_run_id", request.modelRunId());
        value.addProperty("phase", testCase.get("phase").getAsString());
        value.add(
                "request_artifact",
                jsonArtifact(
                        testCase.get("requestArtifactId").getAsString(),
                        ModelRunRequestCodec.toJson(request)));
        value.addProperty(
                "completed_unit_count", name(testCase).equals("partial-global-checkpoint") ? 0 : 1);
        value.addProperty("total_unit_count", 1);
        value.add(
                "terminal_manifest",
                jsonArtifact(testCase.get("terminalManifestArtifactId").getAsString(), manifest));
        value.addProperty("phase_deadline", testCase.get("phaseDeadline").getAsString());
        return value;
    }

    private static JsonObject jsonArtifact(String artifactId, JsonElement value) {
        byte[] bytes = CanonicalCodec.canonicalBytes(value);
        JsonObject artifact = new JsonObject();
        artifact.addProperty("artifact_id", artifactId);
        artifact.addProperty("media_type", "application/json");
        artifact.addProperty("size_bytes", bytes.length);
        artifact.addProperty("sha256", CanonicalCodec.sha256(bytes));
        return artifact;
    }

    private static void redigestManifest(JsonObject manifest) {
        manifest.remove("manifest_digest");
        manifest.addProperty("manifest_digest", CanonicalCodec.canonicalDigest(manifest));
    }

    private static String digest(JsonElement value) {
        return CanonicalCodec.sha256(CanonicalCodec.canonicalBytes(value));
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
        return request;
    }

    private static JsonObject caseAt(int index) throws IOException {
        return fixture("model-run-publication-v1.json")
                .getAsJsonArray("cases")
                .get(index)
                .getAsJsonObject();
    }

    private static JsonObject requestCase(int index) throws IOException {
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
                ModelRunPublicationCodecTest.class
                        .getClassLoader()
                        .getResourceAsStream("contracts/" + name)) {
            if (input == null) {
                throw new IOException("missing contract fixture: " + name);
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static String name(JsonObject testCase) {
        return testCase.get("name").getAsString();
    }

    private record CaseValues(
            ModelRunRequestCodec.ModelRunRequest request,
            ExecutionLimitsCodec.ModelRunLimits limits,
            int inputId,
            ModelExecutionPlanCodec.ModelExecutionPlan plan,
            JsonObject manifest,
            JsonObject status) {}
}
