package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.util.AffineTransformation;
import qupath.ext.histoforge.spatial.CandidateGeneration;

/** Cross-language image assignment, input identity and saved-evidence checks. */
class ModelRunSourceCodecTest {
    @Test
    void sharedFixtureMatchesPlansRequestsUnitIdentitiesAndCompleteResults() throws IOException {
        var fixture = fixture();
        var limits = ExecutionLimitsCodec.parseModelRunLimits(fixture.get("modelRunLimits"));
        for (var element : fixture.getAsJsonArray("cases")) {
            var item = element.getAsJsonObject();
            var request = ModelRunRequestCodec.parseRequest(wire(item, "request"));
            assertEquals(
                    item.get("requestDigest").getAsString(),
                    ModelRunRequestCodec.requestDigest(request));
            assertExact(item, "request", ModelRunRequestCodec.toJson(request));
            var delivery =
                    ModelRunSourceCodec.validateDelivery(wire(item, "delivery"), request, limits);
            assertTrue(delivery.active());
            var input = request.inputs().getFirst();
            for (int unit = 0; unit < delivery.sourceBindings().size(); unit++) {
                assertEquals(
                        item.getAsJsonArray("unitExecutionDigests").get(unit).getAsString(),
                        ModelRunPublicationCodec.executionIdentityDigest(
                                request, input.inputId(), unit, limits, delivery.sourceBindings()));
                var binding = delivery.sourceBindings().get(unit);
                var required =
                        SourceViewGeometry.requiredCapturedSourcePixelRect(
                                (SourceCaptureCodec.View) input.rasterInput().sourceView(),
                                input.rasterInput().frame().transform(),
                                ModelRunSourceCodec.processingRectangle(
                                        input.rasterInput(),
                                        delivery.executionPlans().get(input.inputId()),
                                        unit));
                assertEquals(required.orElseThrow(), binding.source().sourceRectAtLevel());
            }
            var manifest =
                    ModelRunPublicationCodec.validateManifest(
                            wire(item, "manifest"), request, limits);
            assertExact(item, "manifest", ModelRunPublicationCodec.toJson(manifest));
            assertEquals(delivery.sourceBindings(), manifest.sourceBindings().orElseThrow());
            if (item.has("finiteRequestCanonical")) {
                var finite = FiniteInferenceCodec.parseRequest(wire(item, "finiteRequest"));
                assertExact(item, "finiteRequest", FiniteInferenceCodec.toJson(finite));
                assertEquals(
                        item.get("finiteRequestDigest").getAsString(),
                        CanonicalCodec.canonicalDigest(FiniteInferenceCodec.toJson(finite)));
                assertEquals(
                        manifest.completedInputs().getFirst().inputIdentity(),
                        FiniteInferenceCodec.inputIdentity(finite));
                assertEquals(
                        delivery.sourceBindings().getFirst().source().artifact(),
                        FiniteInferenceCodec.sourceArtifact(finite));
            }
        }
    }

    @Test
    void normalizedUnitsDependOnTheCompleteTargetSourceSet() throws IOException {
        var fixture = fixture();
        var item = fixture.getAsJsonArray("cases").get(1).getAsJsonObject();
        var limits = ExecutionLimitsCodec.parseModelRunLimits(fixture.get("modelRunLimits"));
        for (String architecture :
                List.of(
                        "dinov3-adapter-cellpose",
                        "dinov3-adapter-hovernet",
                        "dinov3-adapter-semantic")) {
            var value = wire(item, "request");
            value.getAsJsonObject("expected_backend").addProperty("architecture", architecture);
            var request = ModelRunRequestCodec.parseRequest(value);
            var bindings =
                    ModelRunSourceCodec.parseBindings(
                            wire(item, "delivery").get("source_bindings"));
            int input = request.inputs().getFirst().inputId();
            String expected =
                    ModelRunPublicationCodec.executionIdentityDigest(
                            request, input, 0, limits, bindings);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ModelRunPublicationCodec.executionIdentityDigest(
                                    request, input, 0, limits, bindings.subList(0, 1)));
            for (int changedUnit = 0; changedUnit < 2; changedUnit++) {
                var changed = ModelRunSourceCodec.toJson(bindings);
                changed.get(changedUnit)
                        .getAsJsonObject()
                        .getAsJsonObject("source")
                        .getAsJsonObject("artifact")
                        .addProperty("bytes_digest", "f".repeat(64));
                assertNotEquals(
                        expected,
                        ModelRunPublicationCodec.executionIdentityDigest(
                                request,
                                input,
                                0,
                                limits,
                                ModelRunSourceCodec.parseBindings(changed)));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ModelRunPublicationCodec.executionIdentityDigest(
                                    request, input, 0, limits, List.of()));
        }
    }

    @Test
    void readbackRejectsWrongRequestWrongPlanDuplicateAssignmentsAndMissingPixels()
            throws IOException {
        var fixture = fixture();
        var item = fixture.getAsJsonArray("cases").get(1).getAsJsonObject();
        var request = ModelRunRequestCodec.parseRequest(wire(item, "request"));
        var limits = ExecutionLimitsCodec.parseModelRunLimits(fixture.get("modelRunLimits"));
        var wrongRequest = wire(item, "delivery");
        wrongRequest.addProperty("request_digest", "f".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunSourceCodec.validateDelivery(wrongRequest, request, limits));
        var wrongPlan = wire(item, "delivery");
        wrongPlan.getAsJsonArray("execution_plans").remove(0);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunSourceCodec.validateDelivery(wrongPlan, request, limits));
        var duplicate = wire(item, "delivery");
        duplicate
                .getAsJsonArray("source_bindings")
                .add(duplicate.getAsJsonArray("source_bindings").get(0).deepCopy());
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunSourceCodec.validateDelivery(duplicate, request, limits));
        var missingPixels = wire(item, "delivery");
        var rectangle =
                missingPixels
                        .getAsJsonArray("source_bindings")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("source")
                        .getAsJsonObject("source_rect_at_level");
        rectangle.addProperty("width", rectangle.get("width").getAsInt() - 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelRunSourceCodec.validateDelivery(missingPixels, request, limits));
    }

    @Test
    void capturedEvidenceRetainsAllSourcesAndOldSealedEvidenceAddsNoFields() throws IOException {
        var fixture = fixture();
        var item = fixture.getAsJsonArray("cases").get(1).getAsJsonObject();
        var request = ModelRunRequestCodec.parseRequest(wire(item, "request"));
        var limits = ExecutionLimitsCodec.parseModelRunLimits(fixture.get("modelRunLimits"));
        var manifest =
                ModelRunPublicationCodec.validateManifest(wire(item, "manifest"), request, limits);
        var raster = request.inputs().getFirst().rasterInput();
        var frame = raster.frame();
        var mapping = frame.transform().rasterToSlide();
        var geometry =
                new AffineTransformation(
                                mapping.scaleX(),
                                0,
                                mapping.translateX(),
                                0,
                                mapping.scaleY(),
                                mapping.translateY())
                        .transform(frame.inclusionMaskRaster());
        var target =
                new ExecutionScopeCodec.TargetRegion(
                        frame.region(),
                        geometry,
                        frame.transform().raster().plane(),
                        CanonicalGeometryCodec.canonicalDigest(geometry));
        var acceptance = new ExecutionScopeCodec.ScopeAcceptance(target, Optional.empty());
        var evidence =
                new CandidateGeneration.InputEvidence(
                                manifest.completedInputs().getFirst().inputIdentity(),
                                ExecutionScopeCodec.scopeAcceptanceDigest(acceptance),
                                acceptance,
                                raster)
                        .committedEvidence();
        assertEquals(2, evidence.sourceBindings().size());
        var encoded = GenerationEvidenceCodec.toJson(evidence);
        assertEquals(evidence, GenerationEvidenceCodec.parseCommittedInputEvidence(encoded));
        encoded.remove("source_bindings");
        assertThrows(
                IllegalArgumentException.class,
                () -> GenerationEvidenceCodec.parseCommittedInputEvidence(encoded));
        var old = GenerationStoreTestData.generationRecord().inputEvidence();
        var oldJson = GenerationEvidenceCodec.toJson(old);
        assertFalse(oldJson.has("source_bindings"));
        assertEquals(old, GenerationEvidenceCodec.parseCommittedInputEvidence(oldJson));
        oldJson.add("source_bindings", SourceCaptureCodec.bindingsJson(evidence.sourceBindings()));
        assertThrows(
                IllegalArgumentException.class,
                () -> GenerationEvidenceCodec.parseCommittedInputEvidence(oldJson));
    }

    private static JsonObject fixture() throws IOException {
        try (var stream =
                ModelRunSourceCodecTest.class.getResourceAsStream(
                        "/contracts/model-source-delivery-v2.json")) {
            if (stream == null) throw new IOException("missing source delivery fixture");
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static JsonObject wire(JsonObject item, String name) {
        return JsonParser.parseString(item.get(name + "Canonical").getAsString()).getAsJsonObject();
    }

    private static void assertExact(JsonObject item, String name, JsonObject actual) {
        assertEquals(
                item.get(name + "Canonical").getAsString(),
                new String(CanonicalCodec.canonicalBytes(actual), StandardCharsets.UTF_8));
    }
}
