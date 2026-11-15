package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.util.AffineTransformation;
import qupath.ext.histoforge.spatial.CandidateGeneration;

/** File identities arrive after reservation; exact completed proof must still fit and match. */
class CapturedInputReservationTest {
    @Test
    void reservationIsStableAcrossDeliveryAndRejectsInsufficientCapacity() throws IOException {
        var fixture = fixture();
        var reserved = reserve(fixture);
        var completed = evidence(fixture, fixture.bindings());
        reserved.requireCompletedEvidence(completed);
        assertEquals(reserved, reserve(fixture));
        assertTrue(
                reserved.maxInputEvidenceUtf8Bytes()
                        >= CanonicalCodec.canonicalBytes(GenerationEvidenceCodec.toJson(completed))
                                .length);

        long size =
                ImageDataStateCodec.requireGenerationStoreProjectionFits(
                        GenerationStoreCodec.emptyStore(),
                        List.of(),
                        List.of(),
                        List.of(reserved),
                        reserved.clientBuild().limits());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ImageDataStateCodec.requireGenerationStoreProjectionFits(
                                GenerationStoreCodec.emptyStore(),
                                List.of(),
                                List.of(),
                                List.of(reserved),
                                GenerationStoreTestData.limitsWithStoreCap(size - 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> resized(reserved, 1).requireCompletedEvidence(completed));
    }

    @Test
    void repeatedFilesStillReserveAndRetainEveryProcessingUnit() throws IOException {
        var fixture = fixture();
        var view = (SourceCaptureCodec.View) fixture.input().rasterInput().sourceView();
        var shared =
                new SourceCaptureCodec.Binding(
                        view.capture().captureDigest(),
                        fixture.bindings().getFirst().source().artifact(),
                        view.capture().sourceRectAtLevel());
        var completed =
                evidence(
                        fixture,
                        List.of(
                                new SourceCaptureCodec.UnitBinding(0, shared),
                                new SourceCaptureCodec.UnitBinding(1, shared)));
        reserve(fixture).requireCompletedEvidence(completed);
        assertEquals(2, completed.sourceBindings().size());
        assertEquals(
                completed,
                GenerationEvidenceCodec.parseCommittedInputEvidence(
                        GenerationEvidenceCodec.toJson(completed)));
    }

    @Test
    void alteredRasterOrAcceptanceCannotUseTheReservedRecord() throws IOException {
        var fixture = fixture();
        var reservation = reserve(fixture);
        for (String field : List.of("model_raster_input_digest", "scope_acceptance_digest")) {
            var changed = GenerationEvidenceCodec.toJson(evidence(fixture, fixture.bindings()));
            changed.addProperty(field, "f".repeat(64));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            reservation.requireCompletedEvidence(
                                    GenerationEvidenceCodec.parseCommittedInputEvidence(changed)));
        }
        var input = fixture.input().rasterInput();
        var altered =
                new RasterInputCodec.ModelRasterInput(
                        input.sourceView(), input.frame(), input.rasterizer(), "f".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GenerationStoreCodec.requirePlannedInputContext(
                                fixture.input().output(),
                                altered,
                                fixture.acceptance(),
                                fixture.request().expectedBackend()));
    }

    private static ImageDataStateCodec.GenerationStoreReservation reserve(Fixture fixture) {
        return ImageDataStateCodec.GenerationStoreReservation.forInput(
                fixture.input().outputGenerationId(),
                fixture.request().actionId(),
                false,
                "fixture-deployment",
                fixture.input().output(),
                fixture.input().inputId(),
                ImageDataStateCodec.ProjectionExecutionType.DURABLE_RUN,
                fixture.input().rasterInput(),
                fixture.acceptance(),
                ModelExecutionPlanCodec.unitCount(fixture.plan()),
                GenerationStoreTestData.clientBuild(),
                fixture.request().expectedBackend(),
                Optional.of(
                        ProcessingEvidenceCodec.fromPlan(
                                fixture.request(), fixture.input(), fixture.plan())));
    }

    private static ImageDataStateCodec.GenerationStoreReservation resized(
            ImageDataStateCodec.GenerationStoreReservation value, long bytes) {
        return new ImageDataStateCodec.GenerationStoreReservation(
                value.outputGenerationId(),
                value.actionId(),
                value.removeOverlappingSameClassDuplicates(),
                value.deploymentId(),
                value.output(),
                value.inputId(),
                value.executionType(),
                value.modelRasterInputDigest(),
                value.scopeAcceptanceDigest(),
                bytes,
                value.sourceRevision(),
                value.targetRegion(),
                value.clientBuild(),
                value.backendIdentity(),
                value.processingEvidence());
    }

    private static GenerationEvidenceCodec.CommittedInputEvidence evidence(
            Fixture fixture, List<SourceCaptureCodec.UnitBinding> bindings) {
        return new CandidateGeneration.InputEvidence(
                        FiniteInferenceCodec.buildBackendInputIdentity(
                                fixture.input().rasterInput(), bindings),
                        ExecutionScopeCodec.scopeAcceptanceDigest(fixture.acceptance()),
                        fixture.acceptance(),
                        fixture.input().rasterInput())
                .committedEvidence();
    }

    private record Fixture(
            ModelRunRequestCodec.ModelRunRequest request,
            ModelRunRequestCodec.ModelRunInput input,
            ExecutionScopeCodec.ScopeAcceptance acceptance,
            ModelExecutionPlanCodec.ModelExecutionPlan plan,
            List<SourceCaptureCodec.UnitBinding> bindings) {}

    private static Fixture fixture() throws IOException {
        try (var stream =
                CapturedInputReservationTest.class.getResourceAsStream(
                        "/contracts/model-source-delivery-v1.json")) {
            if (stream == null) throw new IOException("missing source delivery fixture");
            var fixture =
                    JsonParser.parseString(
                                    new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
            var item = fixture.getAsJsonArray("cases").get(1).getAsJsonObject();
            var request =
                    ModelRunRequestCodec.parseRequest(
                            JsonParser.parseString(item.get("requestCanonical").getAsString()));
            var manifest =
                    ModelRunPublicationCodec.validateManifest(
                            JsonParser.parseString(item.get("manifestCanonical").getAsString()),
                            request,
                            ExecutionLimitsCodec.parseModelRunLimits(
                                    fixture.get("modelRunLimits")));
            var input = request.inputs().getFirst();
            var frame = input.rasterInput().frame();
            var transform = frame.transform().rasterToSlide();
            var geometry =
                    new AffineTransformation(
                                    transform.scaleX(),
                                    0,
                                    transform.translateX(),
                                    0,
                                    transform.scaleY(),
                                    transform.translateY())
                            .transform(frame.inclusionMaskRaster());
            var target =
                    new ExecutionScopeCodec.TargetRegion(
                            frame.region(),
                            geometry,
                            frame.transform().raster().plane(),
                            CanonicalGeometryCodec.canonicalDigest(geometry));
            var result = manifest.completedInputs().getFirst();
            return new Fixture(
                    request,
                    input,
                    new ExecutionScopeCodec.ScopeAcceptance(target, Optional.empty()),
                    result.executionPlan(),
                    result.inputIdentity().sourceBindings());
        }
    }
}
