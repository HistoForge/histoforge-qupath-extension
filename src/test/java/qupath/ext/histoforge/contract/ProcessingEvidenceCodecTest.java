package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProcessingEvidenceCodecTest {

    @Test
    void allPlanFamiliesProjectToClosedCompactEvidence() throws Exception {
        for (var profile :
                List.of(
                        Optional.<LargeRegionProfileCodec.LargeRegionProfile>empty(),
                        Optional.of(profile(0)),
                        Optional.of(profile(1)))) {
            var request = request(profile, Optional.empty());
            var input = request.inputs().getFirst();
            var plan = plan(request);
            var evidence = ProcessingEvidenceCodec.fromPlan(request, input, plan);
            assertEquals(evidence, ProcessingEvidenceCodec.expected(request, input, 10));
            var json = ProcessingEvidenceCodec.toJson(evidence);
            assertEquals(evidence, ProcessingEvidenceCodec.parse(json));
            assertFalse(json.has("windows"));
            assertFalse(json.has("target_mpp"));
            assertFalse(json.has("raster_transform"));
            assertEquals(profile.isPresent(), json.has("profile_digest"));
        }
    }

    @Test
    void nominalCoreDimensionsComeFromCapturedProfileAndHaloFromActualPlan() throws Exception {
        var profile = (LargeRegionProfileCodec.CoreHaloProfile) profile(1);
        var request = request(Optional.of(profile), Optional.of(0));
        var plan = (ModelExecutionPlanCodec.CoreHaloPlan) plan(request);
        assertTrue(plan.windows().getFirst().coreRect().width() < profile.coreWidthPx());
        assertTrue(plan.windows().getFirst().coreRect().height() < profile.coreHeightPx());
        var evidence =
                assertInstanceOf(
                        ProcessingEvidenceCodec.CoreHaloProcessing.class,
                        ProcessingEvidenceCodec.fromPlan(
                                request, request.inputs().getFirst(), plan));
        assertEquals(profile.coreWidthPx(), evidence.coreWidthPx());
        assertEquals(profile.coreHeightPx(), evidence.coreHeightPx());
        assertEquals(0, evidence.resolvedHaloPx());
        assertEquals(profile.profileDigest(), evidence.profileDigest());
        assertEquals(plan.planDigest(), evidence.planDigest());

        var defaultRequest = request(Optional.of(profile), Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProcessingEvidenceCodec.fromPlan(
                                defaultRequest, defaultRequest.inputs().getFirst(), plan));
        var finite = request(Optional.empty(), Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> ProcessingEvidenceCodec.fromPlan(finite, finite.inputs().getFirst(), plan));
        var otherContext =
                ModelExecutionPlanCodec.buildExpected(
                        Optional.of(profile),
                        request.inputs().getFirst().rasterInput().frame().transform().raster(),
                        "f".repeat(64),
                        10,
                        Optional.of(0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProcessingEvidenceCodec.fromPlan(
                                request, request.inputs().getFirst(), otherContext));
    }

    @Test
    void malformedCompactEvidenceFailsClosed() {
        var valid =
                ProcessingEvidenceCodec.toJson(
                        new ProcessingEvidenceCodec.CoreHaloProcessing(
                                "a".repeat(64), "b".repeat(64), 1024, 768, 0));
        for (String field : List.of("core_width_px", "core_height_px", "resolved_halo_px")) {
            for (var value :
                    List.of(
                            JsonParser.parseString("-1"),
                            JsonParser.parseString("1.5"),
                            JsonParser.parseString("2147483648"),
                            JsonParser.parseString("\"1\""))) {
                var invalid = valid.deepCopy();
                invalid.add(field, value);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ProcessingEvidenceCodec.parse(invalid));
            }
        }
        for (String field : List.of("profile_digest", "plan_digest")) {
            var invalid = valid.deepCopy();
            invalid.addProperty(field, "bad-digest");
            assertThrows(
                    IllegalArgumentException.class, () -> ProcessingEvidenceCodec.parse(invalid));
        }
        var extra = valid.deepCopy();
        extra.addProperty("target_mpp", 1.0);
        assertThrows(IllegalArgumentException.class, () -> ProcessingEvidenceCodec.parse(extra));
        var missing = valid.deepCopy();
        missing.remove("resolved_halo_px");
        assertThrows(IllegalArgumentException.class, () -> ProcessingEvidenceCodec.parse(missing));
        var finite =
                ProcessingEvidenceCodec.toJson(
                        new ProcessingEvidenceCodec.FiniteProcessing("b".repeat(64)));
        finite.addProperty("profile_digest", "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ProcessingEvidenceCodec.parse(finite));
    }

    private static ModelExecutionPlanCodec.ModelExecutionPlan plan(
            ModelRunRequestCodec.ModelRunRequest request) {
        var input = request.inputs().getFirst();
        return ModelExecutionPlanCodec.buildExpected(
                request.expectedProfile(),
                input.rasterInput().frame().transform().raster(),
                ModelRunPublicationCodec.preprocessingContextDigest(
                        input, request.expectedBackend()),
                10,
                request.haloPxOverride());
    }

    private static ModelRunRequestCodec.ModelRunRequest request(
            Optional<LargeRegionProfileCodec.LargeRegionProfile> profile, Optional<Integer> halo)
            throws IOException {
        var value =
                fixture("finite-inference-v1.json")
                        .getAsJsonArray("cases")
                        .get(0)
                        .getAsJsonObject();
        var finite =
                FiniteInferenceCodec.parseRequest(
                        JsonParser.parseString(value.get("requestCanonical").getAsString()));
        var input =
                new ModelRunRequestCodec.ModelRunInput(
                        finite.inputId(),
                        finite.outputGenerationId(),
                        new ExecutionScopeCodec.OutputKey(
                                finite.producer(),
                                new ExecutionScopeCodec.FieldScope(
                                        finite.rasterInput().frame().region())),
                        finite.rasterInput());
        return new ModelRunRequestCodec.ModelRunRequest(
                ModelRunRequestCodec.SCHEMA_VERSION,
                finite.actionId(),
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                finite.producer(),
                finite.expectedBackend(),
                profile,
                List.of(input),
                finite.resultBudget(),
                Optional.empty(),
                halo);
    }

    private static LargeRegionProfileCodec.LargeRegionProfile profile(int index)
            throws IOException {
        return LargeRegionProfileCodec.parse(
                fixture("large-region-execution-v1.json")
                        .getAsJsonArray("profiles")
                        .get(index)
                        .getAsJsonObject()
                        .get("input"));
    }

    private static JsonObject fixture(String name) throws IOException {
        try (var stream =
                ProcessingEvidenceCodecTest.class.getResourceAsStream("/contracts/" + name)) {
            if (stream == null) throw new IOException("missing fixture " + name);
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
