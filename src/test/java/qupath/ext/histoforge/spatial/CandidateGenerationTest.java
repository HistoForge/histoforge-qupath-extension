package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.ArtifactRefCodec;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FeatureStreamDecoder;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.ModelExecutionPlanCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ObjectIdentityCodec;
import qupath.ext.histoforge.contract.ProcessingEvidenceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.spatial.CandidateGeneration.SlideCandidate;
import qupath.ext.histoforge.spatial.SourceInputPlan.PlannedModelInput;
import qupath.ext.histoforge.spatial.SourceInputPlan.SourceViewPlan;

/** Verifies complete finite-result transformation, scope acceptance, and generation evidence. */
class CandidateGenerationTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final String PARENT_ID = "99999999-9999-4999-8999-999999999999";

    @Test
    void candidateConversionRejectsDuplicateAndOutOfOrderFeatureIds() throws IOException {
        FixtureCase fixture = fixtureCase(0);
        var features = fixture.result().geojson().features();
        Scenario valid =
                fieldScenario(
                        fixture,
                        fixture.request().rasterInput().frame().inclusionMaskRaster(),
                        fixture.result().geojson());
        for (var invalid :
                List.of(
                        List.of(features.getFirst(), features.getFirst()),
                        List.of(features.getLast(), features.getFirst()))) {
            Scenario scenario =
                    new Scenario(
                            valid.request(),
                            new FiniteInferenceCodec.InferenceResult(
                                    valid.result().meta(),
                                    new InferenceFeatureCodec.FeatureCollection(invalid)),
                            valid.planned(),
                            valid.execution());
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> generate(scenario, List.of(), false));
            assertTrue(error.getMessage().contains("failed during feature order"));
            assertTrue(error.getMessage().contains(invalid.getLast().id()));
            assertEquals(
                    "validated result features must have unique ascending IDs",
                    error.getCause().getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativePrecisionEmptyRemainsDistinctFromEmptyBackendOutput(boolean durable)
            throws IOException {
        FixtureCase fixture = fixtureCase(0);
        var features =
                featureCollection(
                        List.of(new FeatureSpec(rectangle(0, 0, 0.001, 0.001), "Tubule", 0)));
        Scenario scenario = fieldScenario(fixture, rectangle(0, 0, 10, 10), features);
        byte[] original =
                CanonicalCodec.canonicalBytes(FiniteInferenceCodec.toJson(scenario.result()));

        var generation = generate(scenario, List.of(), false, durable);
        assertEquals(1, generation.counts().backendFeatures());
        assertEquals(0, generation.counts().scopeAccepted());
        assertEquals(0, generation.counts().candidatesSuppressedByUnattributedOverlap());
        assertEquals(0, generation.counts().committedObjects());
        assertTrue(generation.candidates().isEmpty());
        assertEquals(durable, generation.processingEvidence().isPresent());
        assertArrayEquals(
                original,
                CanonicalCodec.canonicalBytes(FiniteInferenceCodec.toJson(scenario.result())));
    }

    @Test
    void rasterFailureIsDistinguishedFromNativeMaterialization() throws IOException {
        FixtureCase fixture = fixtureCase(0);
        Scenario valid =
                fieldScenario(
                        fixture,
                        fixture.request().rasterInput().frame().inclusionMaskRaster(),
                        fixture.result().geojson());
        var outside =
                featureCollection(List.of(new FeatureSpec(rectangle(-10, 0, 10, 10), "Tubule", 0)));
        Scenario invalid =
                new Scenario(
                        valid.request(),
                        new FiniteInferenceCodec.InferenceResult(valid.result().meta(), outside),
                        valid.planned(),
                        valid.execution());

        var error =
                assertThrows(
                        IllegalArgumentException.class, () -> generate(invalid, List.of(), false));
        assertTrue(error.getMessage().contains("failed during raster validation"));
        assertTrue(error.getMessage().contains(outside.features().getFirst().id()));
        assertTrue(error.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void finiteFixtureProducesBoundSlideCandidatesAndEvidence() throws IOException {
        FixtureCase fixture = fixtureCase(0);
        Scenario scenario =
                fieldScenario(
                        fixture,
                        fixture.request().rasterInput().frame().inclusionMaskRaster(),
                        fixture.result().geojson());

        CandidateGeneration generation = generate(scenario, List.of(), true);

        assertEquals(fixture.request().outputGenerationId(), generation.outputGenerationId());
        assertEquals(fixture.request().expectedBackend(), generation.backend());
        assertEquals(scenario.execution(), generation.execution());
        assertEquals(fixture.request().inputId(), generation.inputId());
        assertEquals(2, generation.counts().backendFeatures());
        assertEquals(2, generation.counts().scopeAccepted());
        assertEquals(0, generation.counts().candidatesSuppressedByUnattributedOverlap());
        assertEquals(2, generation.counts().committedObjects());
        assertEquals(
                List.of(
                        "01aac26f-031a-5ce7-b049-255678d74a58",
                        "e5a0b724-3b94-53d5-914b-24409909a5e5"),
                generation.candidates().stream().map(SlideCandidate::candidateId).toList());
        assertEquals(
                persistentSlideGeometry(
                                fixture.result().geojson().features().getFirst().geometry(),
                                fixture.request().rasterInput().frame().transform())
                        .toText(),
                generation.candidates().getFirst().geometrySlide().toText());
        assertEquals(
                FiniteInferenceCodec.buildBackendInputIdentity(scenario.request().rasterInput()),
                generation.inputEvidence().backendInputIdentity());
        assertEquals(
                ExecutionScopeCodec.scopeAcceptanceDigest(scenario.planned().acceptance()),
                generation.inputEvidence().scopeAcceptanceDigest());
        assertEquals(scenario.request().rasterInput(), generation.inputEvidence().rasterInput());
    }

    @Test
    void fieldAndParentScopesDiscardExpectedPaddingSpillover() throws IOException {
        FixtureCase fixture = fixtureCase(0);
        Geometry firstFeatureRegion = rectangle(40, 50, 160, 180);
        Scenario field = fieldScenario(fixture, firstFeatureRegion, fixture.result().geojson());
        CandidateGeneration fieldGeneration = generate(field, List.of(), false);

        assertEquals(2, fieldGeneration.counts().backendFeatures());
        assertEquals(1, fieldGeneration.counts().scopeAccepted());
        assertEquals(1, fieldGeneration.candidates().size());
        assertEquals(
                "01aac26f-031a-5ce7-b049-255678d74a58",
                fieldGeneration.candidates().getFirst().candidateId());

        Geometry target = fixture.request().rasterInput().frame().inclusionMaskRaster();
        Scenario parent =
                parentScenario(fixture, target, firstFeatureRegion, fixture.result().geojson());
        CandidateGeneration parentGeneration = generate(parent, List.of(), false);

        assertTrue(parentGeneration.output().scope() instanceof ExecutionScopeCodec.ParentScope);
        assertTrue(parentGeneration.inputEvidence().acceptance().parentGeometrySlide().isPresent());
        assertEquals(2, parentGeneration.counts().backendFeatures());
        assertEquals(1, parentGeneration.counts().scopeAccepted());
        assertEquals(
                fieldGeneration.candidates().getFirst().candidateId(),
                parentGeneration.candidates().getFirst().candidateId());
    }

    @Test
    void parentJtsInteriorPointHandlesConcavityHolesAndCoveredBoundary() throws Exception {
        FixtureCase fixture = fixtureCase(0);
        List<Geometry> centroidOutside =
                List.of(
                        wkt(
                                "POLYGON ((20 20, 120 20, 120 40, 40 40, 40 100, "
                                        + "120 100, 120 120, 20 120, 20 20))"),
                        wkt(
                                "POLYGON ((20 20, 120 20, 120 120, 20 120, 20 20), "
                                        + "(40 40, 40 100, 100 100, 100 40, 40 40))"));
        for (Geometry feature : centroidOutside) {
            assertFalse(feature.covers(feature.getCentroid()));
            Geometry featureSlide =
                    SourceInputGeometry.geometryToSlide(
                            feature, fixture.request().rasterInput().frame().transform());
            Coordinate slidePoint = featureSlide.getInteriorPoint().getCoordinate();
            var mapping = fixture.request().rasterInput().frame().transform().rasterToSlide();
            double pointX = (slidePoint.x - mapping.translateX()) / mapping.scaleX();
            double pointY = (slidePoint.y - mapping.translateY()) / mapping.scaleY();
            Geometry target = rectangle(pointX - 2, pointY - 2, pointX + 2, pointY + 2);
            assertFalse(target.covers(feature.getCentroid()));
            Geometry targetSlide =
                    SourceInputGeometry.geometryToSlide(
                            target, fixture.request().rasterInput().frame().transform());
            assertTrue(
                    targetSlide.covers(featureSlide.getInteriorPoint()),
                    "transformed acceptance must cover the transformed JTS interior point: "
                            + feature);
            CandidateGeneration generation =
                    generate(
                            parentScenario(
                                    fixture,
                                    fixture.request().rasterInput().frame().inclusionMaskRaster(),
                                    target,
                                    featureCollection(
                                            List.of(new FeatureSpec(feature, "Tubule", 0)))),
                            List.of(),
                            false);
            assertEquals(1, generation.candidates().size(), feature.toText());
            assertEquals(
                    persistentSlideGeometry(
                                    feature, fixture.request().rasterInput().frame().transform())
                            .toText(),
                    generation.candidates().getFirst().geometrySlide().toText());
        }

        Geometry feature = rectangle(20, 20, 60, 60);
        Geometry interior = feature.getInteriorPoint();
        Geometry boundaryTarget =
                rectangle(
                        interior.getCoordinate().x,
                        interior.getCoordinate().y - 4,
                        interior.getCoordinate().x + 20,
                        interior.getCoordinate().y + 4);
        assertTrue(boundaryTarget.covers(interior));
        assertFalse(boundaryTarget.contains(interior));
        CandidateGeneration boundary =
                generate(
                        parentScenario(
                                fixture,
                                fixture.request().rasterInput().frame().inclusionMaskRaster(),
                                boundaryTarget,
                                featureCollection(List.of(new FeatureSpec(feature, "Tubule", 0)))),
                        List.of(),
                        false);
        assertEquals(1, boundary.candidates().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void targetClipKeepsCrossingObjectsWithoutChangingBackendEvidence(boolean durable)
            throws Exception {
        FixtureCase fixture = fixtureCase(0);
        Geometry prediction = rectangle(20, 20, 120, 100);
        Geometry target = rectangle(100, 10, 140, 110);
        assertFalse(target.covers(prediction.getInteriorPoint()));
        var features =
                featureCollection(
                        List.of(
                                new FeatureSpec(prediction, "Tubule", 0),
                                new FeatureSpec(rectangle(140, 20, 160, 100), "Tubule", 0),
                                new FeatureSpec(rectangle(180, 20, 200, 100), "Tubule", 0)));
        Scenario scenario = fieldScenario(fixture, target, features);
        byte[] originalResult =
                CanonicalCodec.canonicalBytes(FiniteInferenceCodec.toJson(scenario.result()));
        CandidateGeneration generation = generate(scenario, List.of(), false, durable);

        assertEquals(3, generation.counts().backendFeatures());
        assertEquals(1, generation.counts().scopeAccepted());
        assertEquals(1, generation.counts().committedObjects());
        assertGeometry(scenario, rectangle(100, 20, 120, 100), generation);
        String featureId =
                features.features().stream()
                        .filter(feature -> feature.geometry().equalsTopo(prediction))
                        .findFirst()
                        .orElseThrow()
                        .id();
        assertEquals(
                ObjectIdentityCodec.candidateId(
                        scenario.request().outputGenerationId(),
                        scenario.request().inputId(),
                        featureId),
                generation.candidates().getFirst().candidateId());
        assertArrayEquals(
                originalResult,
                CanonicalCodec.canonicalBytes(FiniteInferenceCodec.toJson(scenario.result())));
        assertEquals(scenario.planned().acceptance(), generation.inputEvidence().acceptance());

        var outside = existing(scenario, rectangle(20, 20, 99, 100));
        assertEquals(1, generate(scenario, List.of(outside), true, durable).candidates().size());
        var inside = existing(scenario, rectangle(105, 30, 115, 90));
        CandidateGeneration suppressed = generate(scenario, List.of(inside), true, durable);
        assertEquals(1, suppressed.counts().scopeAccepted());
        assertEquals(1, suppressed.counts().candidatesSuppressedByUnattributedOverlap());
        assertTrue(suppressed.candidates().isEmpty());

        Scenario outsideOnly =
                fieldScenario(
                        fixture,
                        target,
                        featureCollection(
                                List.of(
                                        new FeatureSpec(
                                                rectangle(140, 20, 160, 100), "Tubule", 0))));
        CandidateGeneration empty = generate(outsideOnly, List.of(), false, durable);
        assertEquals(1, empty.counts().backendFeatures());
        assertEquals(0, empty.counts().scopeAccepted());
        assertTrue(empty.candidates().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parentAdmissionUsesOriginalPredictionBeforeOuterTargetClip(boolean durable)
            throws Exception {
        FixtureCase fixture = fixtureCase(0);
        Geometry prediction = rectangle(20, 20, 120, 100);
        Geometry target = rectangle(100, 10, 140, 110);
        Geometry parent = rectangle(60, 10, 105, 110);
        Geometry clipped = rectangle(100, 20, 120, 100);
        assertTrue(parent.covers(prediction.getInteriorPoint()));
        assertFalse(parent.covers(clipped.getInteriorPoint()));
        var features = featureCollection(List.of(new FeatureSpec(prediction, "Tubule", 0)));
        Scenario accepted = parentScenario(fixture, target, parent, features);
        assertGeometry(accepted, clipped, generate(accepted, List.of(), false, durable));

        Geometry otherParent = rectangle(100, 10, 120, 110);
        assertFalse(otherParent.covers(prediction.getInteriorPoint()));
        assertTrue(otherParent.covers(clipped.getInteriorPoint()));
        CandidateGeneration rejected =
                generate(
                        parentScenario(fixture, target, otherParent, features),
                        List.of(),
                        false,
                        durable);
        assertEquals(1, rejected.counts().backendFeatures());
        assertEquals(0, rejected.counts().scopeAccepted());
        assertTrue(rejected.candidates().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void targetClipKeepsAllPolygonalPartsAndHolesInOneCandidate(boolean durable) throws Exception {
        FixtureCase fixture = fixtureCase(0);
        Geometry prediction = rectangle(20, 20, 200, 180);
        Geometry target =
                wkt(
                        "MULTIPOLYGON (((10 30, 50 30, 50 70, 10 70, 10 30)), "
                                + "((100 40, 180 40, 180 140, 100 140, 100 40), "
                                + "(120 60, 120 120, 160 120, 160 60, 120 60)), "
                                + "((200 150, 220 150, 220 170, 200 170, 200 150)))");
        Geometry expected =
                wkt(
                        "MULTIPOLYGON (((20 30, 50 30, 50 70, 20 70, 20 30)), "
                                + "((100 40, 180 40, 180 140, 100 140, 100 40), "
                                + "(120 60, 120 120, 160 120, 160 60, 120 60)))");
        Scenario scenario =
                fieldScenario(
                        fixture,
                        target,
                        featureCollection(List.of(new FeatureSpec(prediction, "Tubule", 0))));
        CandidateGeneration generation = generate(scenario, List.of(), false, durable);
        assertGeometry(scenario, expected, generation);
        assertEquals(1, generation.counts().scopeAccepted());
        assertEquals(2, generation.candidates().getFirst().geometrySlide().getNumGeometries());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validEmptyIsACompleteZeroCountGeneration(boolean durable) throws IOException {
        FixtureCase fixture = fixtureCase(1);
        CandidateGeneration generation =
                generate(
                        fieldScenario(
                                fixture,
                                fixture.request().rasterInput().frame().inclusionMaskRaster(),
                                fixture.result().geojson()),
                        List.of(),
                        true,
                        durable);

        assertTrue(generation.candidates().isEmpty());
        assertEquals(0, generation.counts().backendFeatures());
        assertEquals(0, generation.counts().scopeAccepted());
        assertEquals(0, generation.counts().committedObjects());
        assertEquals(durable, generation.processingEvidence().isPresent());
    }

    @Test
    void durableCoreHaloCarriesCapturedNominalDimensionsAndResolvedHalo() throws IOException {
        FixtureCase fixture = fixtureCase(0);
        var profileJson = new JsonObject();
        profileJson.addProperty("type", "core-halo");
        profileJson.addProperty("core_width_px", 1024);
        profileJson.addProperty("core_height_px", 768);
        profileJson.addProperty("halo_px", 96);
        profileJson.addProperty("validation_evidence_digest", hex('a'));
        profileJson.addProperty("profile_digest", CanonicalCodec.canonicalDigest(profileJson));
        var profile = LargeRegionProfileCodec.parse(profileJson);
        var scenario =
                fieldScenario(
                        fixture,
                        fixture.request().rasterInput().frame().inclusionMaskRaster(),
                        fixture.result().geojson());
        var generation =
                generateDurable(scenario, List.of(), false, Optional.of(profile), Optional.of(0));
        var processing =
                (ProcessingEvidenceCodec.CoreHaloProcessing)
                        generation.processingEvidence().orElseThrow();
        assertEquals(1024, processing.coreWidthPx());
        assertEquals(768, processing.coreHeightPx());
        assertEquals(0, processing.resolvedHaloPx());
        assertEquals(
                generation.reservation().processingEvidence(), generation.processingEvidence());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CandidateGeneration(
                                generation.reservation(),
                                generation.execution(),
                                generation.inputEvidence(),
                                generation.counts(),
                                generation.candidates(),
                                Optional.of(
                                        new ProcessingEvidenceCodec.CoreHaloProcessing(
                                                processing.profileDigest(),
                                                processing.planDigest(),
                                                512,
                                                768,
                                                0))));
    }

    @Test
    void overlapSuppressionUpdatesOnlyCountsAndCandidateOmissions() throws IOException {
        FixtureCase fixture = fixtureCase(0);
        Scenario scenario =
                fieldScenario(
                        fixture,
                        fixture.request().rasterInput().frame().inclusionMaskRaster(),
                        fixture.result().geojson());
        Geometry existingGeometry =
                SourceInputGeometry.geometryToSlide(
                        fixture.result().geojson().features().getFirst().geometry(),
                        fixture.request().rasterInput().frame().transform());
        var existing =
                new CandidatePipeline.ExistingObjectSnapshot(
                        existingGeometry,
                        Optional.of("Tubule"),
                        fixture.request().rasterInput().frame().transform().raster().plane(),
                        CandidatePipeline.ExistingObjectType.ANNOTATION,
                        false,
                        false);
        String before = existing.geometrySlide().toText();

        CandidateGeneration enabled = generate(scenario, List.of(existing), true);
        CandidateGeneration disabled = generate(scenario, List.of(existing), false);

        assertEquals(2, enabled.counts().scopeAccepted());
        assertEquals(1, enabled.counts().candidatesSuppressedByUnattributedOverlap());
        assertEquals(1, enabled.counts().committedObjects());
        assertEquals("Tubule (Not Intact)", enabled.candidates().getFirst().pathClass());
        assertEquals(2, disabled.candidates().size());
        assertEquals(0, disabled.counts().candidatesSuppressedByUnattributedOverlap());
        assertEquals(before, existing.geometrySlide().toText());
    }

    @Test
    void planResultAndExecutionMismatchesFailTheWholeInput() throws IOException {
        FixtureCase fixture = fixtureCase(0);
        Scenario scenario =
                fieldScenario(
                        fixture,
                        fixture.request().rasterInput().frame().inclusionMaskRaster(),
                        fixture.result().geojson());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CandidatePipeline.generateFinite(
                                scenario.request(),
                                scenario.result(),
                                scenario.planned(),
                                new BackendExecutionCodec.FiniteCall("another-call"),
                                reservation(scenario, false),
                                List.of()));

        var wrongProducer =
                new InferenceIdentityCodec.ModelStepIdentity(
                        scenario.request().producer().moduleRef(), "lumen");
        PlannedModelInput wrongPlan =
                new PlannedModelInput(
                        new ExecutionScopeCodec.OutputKey(
                                wrongProducer, scenario.planned().output().scope()),
                        scenario.planned().acceptance(),
                        scenario.planned().frame(),
                        scenario.planned().sourceView());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CandidatePipeline.generateFinite(
                                scenario.request(),
                                scenario.result(),
                                wrongPlan,
                                scenario.execution(),
                                reservation(scenario, false),
                                List.of()));

        FiniteInferenceCodec.BackendResultMeta meta = scenario.result().meta();
        var wrongResult =
                new FiniteInferenceCodec.InferenceResult(
                        new FiniteInferenceCodec.BackendResultMeta(
                                meta.schemaVersion(),
                                meta.actionId(),
                                meta.outputGenerationId(),
                                meta.backendCallId(),
                                meta.inputId() + 1,
                                meta.producer(),
                                meta.backend(),
                                meta.inputIdentity(),
                                meta.coordinateSpace()),
                        scenario.result().geojson());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CandidatePipeline.generateFinite(
                                scenario.request(),
                                wrongResult,
                                scenario.planned(),
                                scenario.execution(),
                                reservation(scenario, false),
                                List.of()));

        PlannedModelInput missingParent =
                new PlannedModelInput(
                        new ExecutionScopeCodec.OutputKey(
                                scenario.request().producer(),
                                new ExecutionScopeCodec.ParentScope(
                                        scenario.request().rasterInput().frame().region(),
                                        PARENT_ID)),
                        scenario.planned().acceptance(),
                        scenario.planned().frame(),
                        scenario.planned().sourceView());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CandidatePipeline.generateFinite(
                                scenario.request(),
                                scenario.result(),
                                missingParent,
                                scenario.execution(),
                                reservation(scenario, false),
                                List.of()));

        ImageDataStateCodec.GenerationStoreReservation wrongAction =
                withActionId(reservation(scenario, false), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CandidatePipeline.generateFinite(
                                scenario.request(),
                                scenario.result(),
                                scenario.planned(),
                                scenario.execution(),
                                wrongAction,
                                List.of()));
    }

    @Test
    void candidateUuidV5HasIndependentGoldenAndGenerationIsDetached() throws IOException {
        String featureId = "6b76775b3e08f17c088606ff7f9dece9982401141a236beb8f9517639dc8f959";
        assertEquals(
                "01aac26f-031a-5ce7-b049-255678d74a58",
                ObjectIdentityCodec.candidateId(
                        "55555555-5555-4555-8555-555555555555", 3, featureId));
        assertNotEquals(
                ObjectIdentityCodec.candidateId(
                        "55555555-5555-4555-8555-555555555555", 3, featureId),
                ObjectIdentityCodec.candidateId(
                        "55555555-5555-4555-8555-555555555555", 4, featureId));

        FixtureCase fixture = fixtureCase(0);
        CandidateGeneration generation =
                generate(
                        fieldScenario(
                                fixture,
                                fixture.request().rasterInput().frame().inclusionMaskRaster(),
                                fixture.result().geojson()),
                        List.of(),
                        false);
        String before = generation.candidates().getFirst().geometrySlide().toText();
        Geometry exposed = generation.candidates().getFirst().geometrySlide();
        exposed.apply((CoordinateFilter) coordinate -> coordinate.x += 10_000);
        exposed.geometryChanged();
        assertEquals(before, generation.candidates().getFirst().geometrySlide().toText());
        assertThrows(
                UnsupportedOperationException.class,
                () -> generation.candidates().add(generation.candidates().getFirst()));
    }

    private static CandidateGeneration generate(
            Scenario scenario,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existing,
            boolean suppress) {
        return CandidatePipeline.generateFinite(
                scenario.request(),
                scenario.result(),
                scenario.planned(),
                scenario.execution(),
                reservation(scenario, suppress),
                existing);
    }

    private static CandidateGeneration generate(
            Scenario scenario,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existing,
            boolean suppress,
            boolean durable)
            throws IOException {
        if (!durable) {
            return generate(scenario, existing, suppress);
        }
        return generateDurable(scenario, existing, suppress, Optional.empty(), Optional.empty());
    }

    private static CandidateGeneration generateDurable(
            Scenario scenario,
            Collection<CandidatePipeline.ExistingObjectSnapshot> existing,
            boolean suppress,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> profile,
            Optional<Integer> halo)
            throws IOException {
        var finite = scenario.request();
        var input =
                new ModelRunRequestCodec.ModelRunInput(
                        finite.inputId(),
                        finite.outputGenerationId(),
                        scenario.planned().output(),
                        finite.rasterInput());
        String runId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        var run =
                new ModelRunRequestCodec.ModelRunRequest(
                        ModelRunRequestCodec.SCHEMA_VERSION,
                        finite.actionId(),
                        runId,
                        finite.producer(),
                        finite.expectedBackend(),
                        profile,
                        List.of(input),
                        finite.resultBudget(),
                        Optional.empty(),
                        halo);
        var features = scenario.result().geojson();
        long vertices =
                features.features().stream()
                        .mapToLong(
                                feature -> CanonicalGeometryCodec.vertexCount(feature.geometry()))
                        .sum();
        byte[] encoded;
        try (var bytes = new ByteArrayOutputStream()) {
            try (var gzip = new GZIPOutputStream(bytes)) {
                for (var feature :
                        InferenceFeatureCodec.toJson(features).getAsJsonArray("features")) {
                    gzip.write(0x1e);
                    gzip.write(CanonicalCodec.canonicalBytes(feature));
                    gzip.write('\n');
                }
            }
            encoded = bytes.toByteArray();
        }
        var validated =
                FeatureStreamDecoder.validate(
                        new ByteArrayInputStream(encoded),
                        finite.resultBudget(),
                        features.features().stream()
                                .map(InferenceFeatureCodec.Feature::pathClass)
                                .collect(Collectors.toSet()),
                        features.features().size(),
                        vertices);
        var manifestInput =
                new ModelRunPublicationCodec.InputResult(
                        input.inputId(),
                        FiniteInferenceCodec.buildBackendInputIdentity(input.rasterInput()),
                        ModelExecutionPlanCodec.buildExpected(
                                profile,
                                input.rasterInput().frame().transform().raster(),
                                ModelRunPublicationCodec.preprocessingContextDigest(
                                        input, finite.expectedBackend()),
                                1,
                                halo),
                        new ArtifactRefCodec.ArtifactRef(
                                runId,
                                ModelRunPublicationCodec.FEATURE_STREAM_MEDIA_TYPE,
                                Optional.of(
                                        ModelRunPublicationCodec.FEATURE_STREAM_CONTENT_ENCODING),
                                encoded.length,
                                CanonicalCodec.sha256(encoded)),
                        features.features().size(),
                        vertices);
        var base = reservation(scenario, suppress);
        var reserved =
                ImageDataStateCodec.GenerationStoreReservation.forInput(
                        base.outputGenerationId(),
                        base.actionId(),
                        base.removeOverlappingSameClassDuplicates(),
                        base.deploymentId(),
                        base.output(),
                        base.inputId(),
                        ImageDataStateCodec.ProjectionExecutionType.DURABLE_RUN,
                        input.rasterInput(),
                        scenario.planned().acceptance(),
                        ModelExecutionPlanCodec.unitCount(manifestInput.executionPlan()),
                        base.clientBuild(),
                        base.backendIdentity(),
                        Optional.of(
                                ProcessingEvidenceCodec.fromPlan(
                                        run, input, manifestInput.executionPlan())));
        return CandidatePipeline.generateDurable(
                run,
                input,
                manifestInput,
                validated,
                consumer ->
                        FeatureStreamDecoder.readValidated(
                                new ByteArrayInputStream(encoded), validated, consumer),
                scenario.planned(),
                new BackendExecutionCodec.DurableRun(runId, hex('b')),
                reserved,
                existing);
    }

    private static void assertGeometry(
            Scenario scenario, Geometry expectedRaster, CandidateGeneration generation) {
        assertEquals(1, generation.candidates().size());
        assertTrue(
                persistentSlideGeometry(
                                expectedRaster,
                                scenario.request().rasterInput().frame().transform())
                        .equalsTopo(generation.candidates().getFirst().geometrySlide()));
    }

    private static CandidatePipeline.ExistingObjectSnapshot existing(
            Scenario scenario, Geometry raster) {
        return new CandidatePipeline.ExistingObjectSnapshot(
                persistentSlideGeometry(
                        raster, scenario.request().rasterInput().frame().transform()),
                Optional.of("Tubule"),
                scenario.planned().acceptance().target().plane(),
                CandidatePipeline.ExistingObjectType.ANNOTATION,
                false,
                false);
    }

    private static ImageDataStateCodec.GenerationStoreReservation reservation(
            Scenario scenario, boolean suppress) {
        return ImageDataStateCodec.GenerationStoreReservation.forInput(
                scenario.request().outputGenerationId(),
                scenario.request().actionId(),
                suppress,
                "fixture-deployment",
                scenario.planned().output(),
                scenario.request().inputId(),
                ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL,
                scenario.request().rasterInput(),
                scenario.planned().acceptance(),
                1,
                clientBuild(),
                scenario.request().expectedBackend(),
                Optional.empty());
    }

    private static ImageDataStateCodec.GenerationStoreReservation withActionId(
            ImageDataStateCodec.GenerationStoreReservation reservation, String actionId) {
        return new ImageDataStateCodec.GenerationStoreReservation(
                reservation.outputGenerationId(),
                actionId,
                reservation.removeOverlappingSameClassDuplicates(),
                reservation.deploymentId(),
                reservation.output(),
                reservation.inputId(),
                reservation.executionType(),
                reservation.modelRasterInputDigest(),
                reservation.scopeAcceptanceDigest(),
                reservation.maxInputEvidenceUtf8Bytes(),
                reservation.sourceRevision(),
                reservation.targetRegion(),
                reservation.clientBuild(),
                reservation.backendIdentity(),
                reservation.processingEvidence());
    }

    private static BuildIdentityCodec.ClientBuildIdentity clientBuild() {
        return new BuildIdentityCodec.ClientBuildIdentity(
                new BuildIdentityCodec.SoftwareBuildIdentity("candidate-test", hex('c'), hex('d')),
                "0.6.0",
                Map.of("java", "21", "jts", "1.20.0"),
                new ExecutionLimitsCodec.ClientExecutionLimits(
                        1_000_000,
                        2_000_000,
                        3_000_000,
                        8_000_000,
                        32L * 1024 * 1024,
                        100_000,
                        1_000_000,
                        8L * 1024 * 1024 * 1024));
    }

    private static Scenario fieldScenario(
            FixtureCase fixture,
            Geometry targetRaster,
            InferenceFeatureCodec.FeatureCollection features) {
        return scenario(
                fixture,
                targetRaster,
                Optional.empty(),
                targetRaster,
                features,
                scope -> new ExecutionScopeCodec.FieldScope(scope));
    }

    private static Scenario parentScenario(
            FixtureCase fixture,
            Geometry targetRaster,
            Geometry parentRaster,
            InferenceFeatureCodec.FeatureCollection features) {
        return scenario(
                fixture,
                targetRaster,
                Optional.of(parentRaster),
                SourceInputGeometry.intersection(targetRaster, parentRaster),
                features,
                scope -> new ExecutionScopeCodec.ParentScope(scope, PARENT_ID));
    }

    private static Scenario scenario(
            FixtureCase fixture,
            Geometry targetRaster,
            Optional<Geometry> parentRaster,
            Geometry inclusionMaskRaster,
            InferenceFeatureCodec.FeatureCollection features,
            ScopeFactory scopeFactory) {
        FiniteInferenceCodec.FiniteInferenceRequest base = fixture.request();
        RasterInputCodec.ModelRasterInput baseInput = base.rasterInput();
        RasterInputCodec.RasterFrame frame =
                RasterInputCodec.buildRasterFrame(
                        baseInput.frame().sourceRevision(),
                        baseInput.frame().region(),
                        baseInput.frame().transform(),
                        inclusionMaskRaster);
        RasterInputCodec.ModelRasterInput rasterInput =
                RasterInputCodec.buildModelRasterInput(
                        baseInput.sourceView(), frame, baseInput.rasterizer());
        var request =
                new FiniteInferenceCodec.FiniteInferenceRequest(
                        base.schemaVersion(),
                        base.actionId(),
                        base.outputGenerationId(),
                        base.inputId(),
                        base.producer(),
                        base.expectedBackend(),
                        rasterInput,
                        base.resultBudget());
        Geometry targetSlide = SourceInputGeometry.geometryToSlide(targetRaster, frame.transform());
        var target =
                new ExecutionScopeCodec.TargetRegion(
                        frame.region(),
                        targetSlide,
                        frame.transform().raster().plane(),
                        CanonicalGeometryCodec.canonicalDigest(targetSlide));
        var acceptance =
                new ExecutionScopeCodec.ScopeAcceptance(
                        target,
                        parentRaster.map(
                                geometry ->
                                        SourceInputGeometry.geometryToSlide(
                                                geometry, frame.transform())));
        ExecutionScopeCodec.ExecutionScope scope = scopeFactory.create(frame.region());
        var planned =
                new PlannedModelInput(
                        new ExecutionScopeCodec.OutputKey(base.producer(), scope),
                        acceptance,
                        frame,
                        sourceViewPlan((RasterInputCodec.SourceView) rasterInput.sourceView()));
        var rawResult =
                new FiniteInferenceCodec.InferenceResult(
                        new FiniteInferenceCodec.BackendResultMeta(
                                FiniteInferenceCodec.SCHEMA_VERSION,
                                request.actionId(),
                                request.outputGenerationId(),
                                fixture.callId(),
                                request.inputId(),
                                request.producer(),
                                request.expectedBackend(),
                                FiniteInferenceCodec.buildBackendInputIdentity(rasterInput),
                                FiniteInferenceCodec.CoordinateSpace.RASTER_LOCAL),
                        features);
        FiniteInferenceCodec.InferenceResult result =
                FiniteInferenceCodec.validateResult(
                        FiniteInferenceCodec.toJson(rawResult),
                        request,
                        fixture.callId(),
                        fixture.declaredClasses());
        return new Scenario(
                request, result, planned, new BackendExecutionCodec.FiniteCall(fixture.callId()));
    }

    private static SourceViewPlan sourceViewPlan(RasterInputCodec.SourceView sourceView) {
        return new SourceViewPlan(
                sourceView.seriesIndex(),
                sourceView.resolutionLevel(),
                sourceView.plane(),
                sourceView.pixelRect(),
                sourceView.viewPixelsToSlide());
    }

    private static InferenceFeatureCodec.FeatureCollection featureCollection(
            List<FeatureSpec> specifications) {
        List<InferenceFeatureCodec.Feature> features = new ArrayList<>();
        for (FeatureSpec specification : specifications) {
            JsonArray preimage = new JsonArray();
            preimage.add(specification.pathClass());
            preimage.add(CanonicalGeometryCodec.canonicalCoordinates(specification.geometry()));
            preimage.add(specification.occurrence());
            features.add(
                    new InferenceFeatureCodec.Feature(
                            CanonicalCodec.sha256(CanonicalCodec.canonicalBytes(preimage)),
                            specification.geometry(),
                            specification.pathClass()));
        }
        features.sort(Comparator.comparing(InferenceFeatureCodec.Feature::id));
        return new InferenceFeatureCodec.FeatureCollection(features);
    }

    private static Geometry rectangle(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.createPolygon(
                new Coordinate[] {
                    new Coordinate(minX, minY),
                    new Coordinate(maxX, minY),
                    new Coordinate(maxX, maxY),
                    new Coordinate(minX, maxY),
                    new Coordinate(minX, minY)
                });
    }

    private static Geometry persistentSlideGeometry(
            Geometry rasterGeometry, RasterInputCodec.RasterTransform transform) {
        return SourceInputGeometry.geometryForQuPathPersistence(
                SourceInputGeometry.geometryToSlide(rasterGeometry, transform),
                transform.raster().plane());
    }

    private static Geometry wkt(String value) throws ParseException {
        return new WKTReader(GEOMETRY_FACTORY).read(value);
    }

    private static String hex(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static FixtureCase fixtureCase(int index) throws IOException {
        JsonObject value = fixture().getAsJsonArray("cases").get(index).getAsJsonObject();
        FiniteInferenceCodec.FiniteInferenceRequest request =
                FiniteInferenceCodec.parseRequest(wire(value, "request"));
        String callId = FiniteInferenceCodec.parseAccepted(wire(value, "accepted")).backendCallId();
        Set<String> declaredClasses = strings(value.getAsJsonArray("declaredClasses"));
        FiniteInferenceCodec.InferenceResult result =
                FiniteInferenceCodec.validateResult(
                        wire(value, "result"), request, callId, declaredClasses);
        return new FixtureCase(request, result, callId, declaredClasses);
    }

    private static JsonObject wire(JsonObject value, String name) {
        return JsonParser.parseString(value.get(name + "Canonical").getAsString())
                .getAsJsonObject();
    }

    private static Set<String> strings(JsonArray values) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (JsonElement value : values) {
            result.add(value.getAsString());
        }
        return Set.copyOf(result);
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream input =
                CandidateGenerationTest.class
                        .getClassLoader()
                        .getResourceAsStream("contracts/finite-inference-v3.json")) {
            if (input == null) {
                throw new IOException("missing finite-inference fixture");
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private record FixtureCase(
            FiniteInferenceCodec.FiniteInferenceRequest request,
            FiniteInferenceCodec.InferenceResult result,
            String callId,
            Set<String> declaredClasses) {}

    private record Scenario(
            FiniteInferenceCodec.FiniteInferenceRequest request,
            FiniteInferenceCodec.InferenceResult result,
            PlannedModelInput planned,
            BackendExecutionCodec.FiniteCall execution) {}

    private record FeatureSpec(Geometry geometry, String pathClass, int occurrence) {}

    @FunctionalInterface
    private interface ScopeFactory {
        ExecutionScopeCodec.ExecutionScope create(ExecutionScopeCodec.RegionKey region);
    }
}
