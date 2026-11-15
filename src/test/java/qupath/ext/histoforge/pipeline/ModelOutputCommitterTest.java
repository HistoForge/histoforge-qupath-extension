package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.roi.RoiManager;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.PolygonOutlineFixture;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent.HierarchyEventType;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;

/** End-to-end staging and disposable-working-hierarchy tests for the sole commit boundary. */
class ModelOutputCommitterTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final String ACTION_ID = "11111111-1111-4111-8111-111111111111";
    private static final String ROI_ID = "22222222-2222-4222-8222-222222222222";
    private static final String PARENT_ID = "33333333-3333-4333-8333-333333333333";
    private static final String GENERATION_A = "44444444-4444-4444-8444-444444444444";
    private static final String GENERATION_B = "55555555-5555-4555-8555-555555555555";
    private static final String CANDIDATE_A = "66666666-6666-4666-8666-666666666666";
    private static final String CANDIDATE_B = "77777777-7777-4777-8777-777777777777";
    private static final String MANUAL_ID = "88888888-8888-4888-8888-888888888888";
    private static final Instant COMMITTED_AT = Instant.parse("2026-08-16T12:34:56.789Z");

    @Test
    void fullNativePolygonTargetRemainsIdenticalFromCaptureThroughCommit() throws Exception {
        try (var image = imageData("dense-target")) {
            var outline = PolygonOutlineFixture.dense(50, 50, 45, 45);
            var annotation = PathObjects.createAnnotationObject(outline);
            annotation.setID(uuid(ROI_ID));
            image.getHierarchy().getRootObject().addChildObject(annotation);
            var selection = new ActionSelectionCodec.RoiRegions(Set.of(ROI_ID));
            var captured =
                    ImageActionPlanner.captureRegions(selection, image.getHierarchy(), 128, 128)
                            .getFirst();
            var expected = PolygonOutlineFixture.rawPolygon(outline);
            assertEquals(
                    CanonicalGeometryCodec.canonicalDigest(expected), captured.geometryDigest());
            var client = clientBuild(1_000_000);
            var generation =
                    generation(
                            client,
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(captured.key()),
                            captured,
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_A, square(45, 45, 55, 55))));
            var committer = new ModelOutputCommitter(image, client.limits());
            assertEquals(
                    1,
                    committer
                            .apply(committer.stage(request(client, Set.of(), List.of(generation))))
                            .addedObjects());
            assertSame(
                    annotation,
                    objectsById(image.getHierarchy()).get(uuid(CANDIDATE_A)).getParent());
            assertSame(outline, annotation.getROI());
            assertEquals(
                    captured,
                    ImageActionPlanner.captureRegions(selection, image.getHierarchy(), 128, 128)
                            .getFirst());
        }
    }

    @Test
    void fieldFillStagesAndAppliesExplicitRoiChildrenWithCompleteEvidence() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("field-fill")) {
            PathObject roi = administrativeRoi();
            imageData.getHierarchy().getRootObject().addChildObject(roi);
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());
            CandidateGeneration generation =
                    generation(
                            client,
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(
                                    candidate(CANDIDATE_A, square(10, 10, 20, 20)),
                                    candidate(CANDIDATE_B, square(30, 30, 40, 40))));
            HierarchyDelta delta = committer.stage(request(client, Set.of(), List.of(generation)));

            assertTrue(delta.changes().removeObjectIds().isEmpty());
            assertEquals(2, delta.changes().addObjects().size());
            assertEquals(
                    List.of(Optional.of(uuid(ROI_ID)), Optional.of(uuid(ROI_ID))),
                    delta.changes().parentAssignments().stream()
                            .map(HierarchyDelta.ParentAssignment::parentObjectId)
                            .toList());
            GenerationEvidenceCodec.GenerationRecord stagedRecord =
                    delta.stagedGenerationStore().currentGenerationRecords().get(GENERATION_A);
            assertEquals(ACTION_ID, stagedRecord.actionId());
            assertEquals(COMMITTED_AT, stagedRecord.committedAt());
            assertEquals(generation.counts(), stagedRecord.counts());

            List<PathObjectHierarchyEvent> events = new ArrayList<>();
            imageData.getHierarchy().addListener(events::add);
            ModelOutputCommitter.ApplyResult result = committer.apply(delta);

            Map<UUID, PathObject> objects = objectsById(imageData.getHierarchy());
            PathObject first = objects.get(uuid(CANDIDATE_A));
            PathObject second = objects.get(uuid(CANDIDATE_B));
            assertSame(roi, first.getParent());
            assertSame(roi, second.getParent());
            assertEquals("Leydig cell", first.getPathClass().toString());
            assertEquals(
                    new ProvenanceCodec.ObjectProvenance(
                            ProvenanceCodec.SCHEMA_VERSION, generation.output(), GENERATION_A),
                    ProvenanceCodec.readMetadata(first.getMetadata()).orElseThrow());
            assertEquals(
                    stagedRecord,
                    ImageDataStateCodec.readGenerationStore(imageData)
                            .orElseThrow()
                            .currentGenerationRecords()
                            .get(GENERATION_A));
            assertEquals(1, result.workingEpoch());
            assertEquals(2, result.addedObjects());
            assertTrue(result.changed());
            assertEquals(1, events.size());
            assertSame(committer, events.getFirst().getSource());
            assertEquals(
                    HierarchyEventType.OTHER_STRUCTURE_CHANGE, events.getFirst().getEventType());
        }
    }

    @Test
    void deliveredImageEvidenceIsSavedExactlyWithinItsEarlierReservation() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("captured-evidence")) {
            imageData.getHierarchy().getRootObject().addChildObject(administrativeRoi());
            var client = clientBuild(1_000_000);
            var base =
                    generation(
                            client,
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));
            var old = (RasterInputCodec.SourceView) base.inputEvidence().rasterInput().sourceView();
            var capture =
                    new SourceCaptureCodec.Capture(
                            base.reservation().sourceRevision().pixelSource(),
                            old.plane(),
                            new SourceArtifactCodec.SourceLevelRequirement(0, 0, 128, 128),
                            new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0),
                            old.viewPixelsToSlide(),
                            old.pixelRect(),
                            softwareBuild("writer"));
            var input =
                    RasterInputCodec.buildModelRasterInput(
                            new SourceCaptureCodec.View(capture, old.pixelRect()),
                            base.inputEvidence().rasterInput().frame(),
                            base.backend().rasterizer());
            var acceptance = base.inputEvidence().acceptance();
            var reserved =
                    ImageDataStateCodec.GenerationStoreReservation.forInput(
                            GENERATION_A,
                            ACTION_ID,
                            true,
                            "fixture-deployment",
                            base.output(),
                            base.inputId(),
                            ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL,
                            input,
                            acceptance,
                            1,
                            client,
                            base.backend(),
                            Optional.empty());
            ImageDataStateCodec.requireGenerationStoreProjectionFits(
                    GenerationStoreCodec.emptyStore(),
                    List.of(),
                    List.of(),
                    List.of(reserved),
                    client.limits());

            var bindings =
                    List.of(
                            new SourceCaptureCodec.UnitBinding(
                                    0,
                                    new SourceCaptureCodec.Binding(
                                            capture.captureDigest(),
                                            old.artifact(),
                                            capture.sourceRectAtLevel())));
            var proof =
                    new CandidateGeneration.InputEvidence(
                            FiniteInferenceCodec.buildBackendInputIdentity(input, bindings),
                            reserved.scopeAcceptanceDigest(),
                            acceptance,
                            input);
            var completed =
                    new CandidateGeneration(
                            reserved, base.execution(), proof, base.counts(), base.candidates());
            var committer = new ModelOutputCommitter(imageData, client.limits());
            committer.apply(committer.stage(request(client, Set.of(), List.of(completed))));
            var saved =
                    ImageDataStateCodec.readGenerationStore(imageData)
                            .orElseThrow()
                            .currentGenerationRecords()
                            .get(GENERATION_A)
                            .inputEvidence();
            assertEquals(proof.committedEvidence(), saved);
            assertEquals(bindings, saved.sourceBindings());
            assertTrue(
                    reserved.maxInputEvidenceUtf8Bytes()
                            > qupath.ext.histoforge.contract.CanonicalCodec.canonicalBytes(
                                            GenerationEvidenceCodec.toJson(saved))
                                    .length);
        }
    }

    @Test
    void validEmptyFillIsASuccessfulNoOpWithoutEvidenceOrEvent() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("empty-fill")) {
            imageData.getHierarchy().getRootObject().addChildObject(administrativeRoi());
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());
            CandidateGeneration empty =
                    generation(
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of());
            List<PathObjectHierarchyEvent> events = new ArrayList<>();
            imageData.getHierarchy().addListener(events::add);

            ModelOutputCommitter.ApplyResult result =
                    committer.apply(committer.stage(request(client, Set.of(), List.of(empty))));

            assertFalse(result.changed());
            assertEquals(0, result.workingEpoch());
            assertTrue(events.isEmpty());
            assertTrue(ImageDataStateCodec.readGenerationStore(imageData).isEmpty());
        }
    }

    @Test
    void stagingRejectsGenerationContextRelabeling() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("generation-context")) {
            imageData.getHierarchy().getRootObject().addChildObject(administrativeRoi());
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());
            CandidateGeneration generation =
                    generation(
                            client,
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));

            List<CandidateGeneration> relabeled =
                    List.of(
                            withContext(
                                    generation,
                                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                    true,
                                    client,
                                    "fixture-deployment"),
                            withContext(generation, ACTION_ID, false, client, "fixture-deployment"),
                            withContext(
                                    generation,
                                    ACTION_ID,
                                    true,
                                    clientBuild(999_999),
                                    "fixture-deployment"),
                            withContext(generation, ACTION_ID, true, client, "another-deployment"));
            for (CandidateGeneration value : relabeled) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> committer.stage(request(client, Set.of(), List.of(value))));
            }
        }
    }

    @Test
    void parentAndSlideScopesUseTheExactParentAndRoot() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("parent-slide")) {
            PathObject roi = administrativeRoi();
            PathObject parent =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(5, 5, 75, 75, null),
                            PathClass.fromString("Tubule"));
            parent.setID(uuid(PARENT_ID));
            imageData.getHierarchy().getRootObject().addChildObject(roi);
            roi.addChildObject(parent);
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());

            Geometry parentGeometry = square(5, 5, 80, 80);
            CandidateGeneration parentGeneration =
                    generation(
                            GENERATION_A,
                            new ExecutionScopeCodec.ParentScope(
                                    new ExecutionScopeCodec.Roi(ROI_ID), PARENT_ID),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.of(parentGeometry),
                            List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));
            committer.apply(committer.stage(request(client, Set.of(), List.of(parentGeneration))));
            assertSame(
                    parent,
                    objectsById(imageData.getHierarchy()).get(uuid(CANDIDATE_A)).getParent());

            ExecutionScopeCodec.TargetRegion slideTarget = target(new ExecutionScopeCodec.Slide());
            CandidateGeneration slideGeneration =
                    generation(
                            GENERATION_B,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()),
                            slideTarget,
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_B, square(30, 30, 40, 40))));
            committer.apply(committer.stage(request(client, Set.of(), List.of(slideGeneration))));
            PathObject slideObject = objectsById(imageData.getHierarchy()).get(uuid(CANDIDATE_B));
            assertSame(imageData.getHierarchy().getRootObject(), slideObject.getParent());
            assertEquals(
                    1,
                    imageData.getHierarchy().getRootObject().getChildObjects().stream()
                            .filter(child -> child == slideObject)
                            .count());
            assertEquals(2, committer.workingEpoch());
        }
    }

    @Test
    void oneDeltaCanAttachDownstreamOutputToANewUpstreamParent() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("staged-parent-child")) {
            PathObject roi = administrativeRoi();
            imageData.getHierarchy().getRootObject().addChildObject(roi);
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());
            ModuleRef module = ModuleRef.parse("histoforge/spermatogenesis").orElseThrow();
            Geometry parentGeometry = square(10, 10, 60, 60);
            CandidateGeneration upstream =
                    generation(
                            client,
                            ACTION_ID,
                            true,
                            "fixture-deployment",
                            GENERATION_A,
                            new InferenceIdentityCodec.ModelStepIdentity(module, "tubule_semantic"),
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_A, parentGeometry)));
            CandidateGeneration downstream =
                    generation(
                            client,
                            ACTION_ID,
                            true,
                            "fixture-deployment",
                            GENERATION_B,
                            new InferenceIdentityCodec.ModelStepIdentity(module, "germ_cell"),
                            new ExecutionScopeCodec.ParentScope(
                                    new ExecutionScopeCodec.Roi(ROI_ID), CANDIDATE_A),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.of(parentGeometry),
                            List.of(candidate(CANDIDATE_B, square(20, 20, 30, 30))));
            List<PathObjectHierarchyEvent> events = new ArrayList<>();
            imageData.getHierarchy().addListener(events::add);

            ModelOutputCommitter.ApplyResult result =
                    committer.apply(
                            committer.stage(
                                    request(client, Set.of(), List.of(upstream, downstream))));

            Map<UUID, PathObject> objects = objectsById(imageData.getHierarchy());
            assertSame(roi, objects.get(uuid(CANDIDATE_A)).getParent());
            assertSame(objects.get(uuid(CANDIDATE_A)), objects.get(uuid(CANDIDATE_B)).getParent());
            assertEquals(
                    Set.of(GENERATION_A, GENERATION_B),
                    ImageDataStateCodec.readGenerationStore(imageData)
                            .orElseThrow()
                            .currentGenerationRecords()
                            .keySet());
            assertEquals(2, result.addedObjects());
            assertEquals(1, events.size());
        }
    }

    @Test
    void redoValidEmptyRemovesOldOutputAndLiftsPreservedChildren() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("valid-empty")) {
            PathObject roi = administrativeRoi();
            imageData.getHierarchy().getRootObject().addChildObject(roi);
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter initialCommitter =
                    new ModelOutputCommitter(imageData, client.limits());
            ExecutionScopeCodec.ExecutionScope scope =
                    new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID));
            CandidateGeneration initial =
                    generation(
                            GENERATION_A,
                            scope,
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));
            initialCommitter.apply(
                    initialCommitter.stage(request(client, Set.of(), List.of(initial))));
            PathObject oldOutput = objectsById(imageData.getHierarchy()).get(uuid(CANDIDATE_A));
            PathObject manual = object(MANUAL_ID, "Leydig cell", 12, 12);
            PathObject foreignOutput = object(CANDIDATE_B, "Spermatocyte", 14, 14);
            ProvenanceCodec.writeMetadata(
                    foreignOutput.getMetadata(),
                    new ProvenanceCodec.ObjectProvenance(
                            ProvenanceCodec.SCHEMA_VERSION,
                            new ExecutionScopeCodec.OutputKey(
                                    new InferenceIdentityCodec.ModelStepIdentity(
                                            ModuleRef.parse("histoforge/spermatogenesis")
                                                    .orElseThrow(),
                                            "germ_cell"),
                                    scope),
                            GENERATION_B));
            oldOutput.addChildObject(manual);
            oldOutput.addChildObject(foreignOutput);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());

            CandidateGeneration empty =
                    generation(
                            GENERATION_B,
                            scope,
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of());
            HierarchyDelta delta =
                    committer.stage(request(client, Set.of(initial.output()), List.of(empty)));
            assertEquals(Set.of(uuid(CANDIDATE_A)), delta.changes().removeObjectIds());
            assertTrue(delta.stagedGenerationStore().currentGenerationRecords().isEmpty());

            ModelOutputCommitter.ApplyResult result = committer.apply(delta);

            Map<UUID, PathObject> objects = objectsById(imageData.getHierarchy());
            assertFalse(objects.containsKey(uuid(CANDIDATE_A)));
            assertSame(manual, objects.get(uuid(MANUAL_ID)));
            assertSame(roi, manual.getParent());
            assertSame(foreignOutput, objects.get(uuid(CANDIDATE_B)));
            assertSame(roi, foreignOutput.getParent());
            assertTrue(
                    ImageDataStateCodec.readGenerationStore(imageData)
                            .orElseThrow()
                            .currentGenerationRecords()
                            .isEmpty());
            assertEquals(1, result.removedObjects());
            assertEquals(2, result.reparentedObjects());
            assertEquals(0, result.addedObjects());
            assertEquals(
                    List.of(
                            new ModelOutputCommitter.MissingGenerationReference(
                                    ProvenanceCodec.readMetadata(foreignOutput.getMetadata())
                                            .orElseThrow()
                                            .output(),
                                    uuid(CANDIDATE_B),
                                    GENERATION_B)),
                    result.missingGenerationReferences());
        }
    }

    @Test
    void classPreconditionsEpochsAndCandidateUuidCollisionsFailBeforeMutation() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("preconditions")) {
            PathObject roi = administrativeRoi();
            PathObject manual = object(MANUAL_ID, "Tubule", 50, 50);
            imageData.getHierarchy().getRootObject().addChildObject(roi);
            roi.addChildObject(manual);
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());
            CandidateGeneration generation =
                    generation(
                            client,
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));
            ModelOutputCommitter.StagingRequest classRequest =
                    new ModelOutputCommitter.StagingRequest(
                            ACTION_ID,
                            true,
                            client,
                            "fixture-deployment",
                            Set.of(),
                            List.of(generation),
                            List.of(
                                    new HierarchyDelta.ClassChange(
                                            uuid(MANUAL_ID), "Tubule", "Tubule (Cross Section)")),
                            COMMITTED_AT);
            HierarchyDelta first = committer.stage(classRequest);
            HierarchyDelta stale = committer.stage(classRequest);
            committer.apply(first);
            assertThrows(IllegalArgumentException.class, () -> committer.apply(stale));
            assertEquals(1, committer.workingEpoch());

            PathObject collision = object(CANDIDATE_B, "Fixture", 70, 70);
            roi.addChildObject(collision);
            CandidateGeneration collidingGeneration =
                    generation(
                            GENERATION_B,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_B, square(30, 30, 40, 40))));
            HierarchyDelta colliding =
                    committer.stage(
                            request(
                                    client,
                                    Set.of(collidingGeneration.output()),
                                    List.of(collidingGeneration)));
            int before = imageData.getHierarchy().getAllObjects(false).size();
            assertThrows(IllegalArgumentException.class, () -> committer.apply(colliding));
            assertEquals(before, imageData.getHierarchy().getAllObjects(false).size());

            manual.setPathClass(PathClass.fromString("Spermatocyte"));
            HierarchyDelta wrongClass =
                    new HierarchyDelta(
                            ACTION_ID,
                            Set.of(),
                            committer.workingEpoch(),
                            new HierarchyDelta.ChangeSet(
                                    Set.of(),
                                    List.of(
                                            new HierarchyDelta.ClassChange(
                                                    uuid(MANUAL_ID),
                                                    "Tubule (Cross Section)",
                                                    "Tubule")),
                                    List.of(),
                                    List.of()),
                            GenerationStoreCodec.emptyStore());
            assertThrows(IllegalArgumentException.class, () -> committer.apply(wrongClass));
            assertEquals("Spermatocyte", manual.getPathClass().toString());
        }
    }

    @Test
    void generationStoreLimitAndParentCycleRejectBeforeTheFirstMutation() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("bounds-cycle")) {
            PathObject roi = administrativeRoi();
            PathObject first = object(MANUAL_ID, "Tubule", 50, 50);
            PathObject second = object(CANDIDATE_B, "Tubule", 70, 70);
            imageData.getHierarchy().getRootObject().addChildObject(roi);
            roi.addChildObject(first);
            roi.addChildObject(second);
            BuildIdentityCodec.ClientBuildIdentity tinyClient = clientBuild(128);
            ModelOutputCommitter tiny = new ModelOutputCommitter(imageData, tinyClient.limits());
            CandidateGeneration generation =
                    generation(
                            tinyClient,
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));
            HierarchyDelta overLimit =
                    tiny.stage(request(tinyClient, Set.of(), List.of(generation)));
            int before = imageData.getHierarchy().getAllObjects(false).size();
            assertThrows(IllegalArgumentException.class, () -> tiny.apply(overLimit));
            assertEquals(before, imageData.getHierarchy().getAllObjects(false).size());
            assertTrue(ImageDataStateCodec.readGenerationStore(imageData).isEmpty());

            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());
            HierarchyDelta cycle =
                    new HierarchyDelta(
                            ACTION_ID,
                            Set.of(),
                            0,
                            new HierarchyDelta.ChangeSet(
                                    Set.of(),
                                    List.of(),
                                    List.of(),
                                    List.of(
                                            new HierarchyDelta.ParentAssignment(
                                                    first.getID(), Optional.of(second.getID())),
                                            new HierarchyDelta.ParentAssignment(
                                                    second.getID(), Optional.of(first.getID())))),
                            GenerationStoreCodec.emptyStore());
            assertThrows(IllegalArgumentException.class, () -> committer.apply(cycle));
            assertSame(roi, first.getParent());
            assertSame(roi, second.getParent());
        }
    }

    @Test
    void postDeltaHierarchyResourceLimitsRejectBeforeTheFirstMutation() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("hierarchy-limits")) {
            imageData.getHierarchy().getRootObject().addChildObject(administrativeRoi());
            ImageDataRevisionDigester.CaptureSize current =
                    ImageDataRevisionDigester.measureCaptureSize(imageData);
            long generousObjects = Math.addExact(current.objects(), 1_000_000);
            long generousVertices = Math.addExact(current.geometryVertices(), 1_000_000);
            long generousMeasurements = Math.addExact(current.measurements(), 1_000_000);
            long generousText = Math.addExact(current.textUtf8Bytes(), 8_000_000);
            List<BuildIdentityCodec.ClientBuildIdentity> clients =
                    List.of(
                            clientBuild(
                                    1_000_000,
                                    current.objects(),
                                    generousVertices,
                                    generousMeasurements,
                                    generousText),
                            clientBuild(
                                    1_000_000,
                                    generousObjects,
                                    current.geometryVertices(),
                                    generousMeasurements,
                                    generousText),
                            clientBuild(
                                    1_000_000,
                                    generousObjects,
                                    generousVertices,
                                    generousMeasurements,
                                    current.textUtf8Bytes()));
            List<String> expectedFields =
                    List.of(
                            "max_hierarchy_objects",
                            "max_hierarchy_geometry_vertices",
                            "max_hierarchy_text_utf8_bytes");

            for (int index = 0; index < clients.size(); index++) {
                BuildIdentityCodec.ClientBuildIdentity client = clients.get(index);
                ModelOutputCommitter committer =
                        new ModelOutputCommitter(imageData, client.limits());
                CandidateGeneration generation =
                        generation(
                                client,
                                GENERATION_A,
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Roi(ROI_ID)),
                                target(new ExecutionScopeCodec.Roi(ROI_ID)),
                                Optional.empty(),
                                List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));
                HierarchyDelta delta =
                        committer.stage(request(client, Set.of(), List.of(generation)));

                IllegalArgumentException failure =
                        assertThrows(IllegalArgumentException.class, () -> committer.apply(delta));
                assertTrue(failure.getMessage().contains(expectedFields.get(index)));
                assertFalse(objectsById(imageData.getHierarchy()).containsKey(uuid(CANDIDATE_A)));
                assertTrue(ImageDataStateCodec.readGenerationStore(imageData).isEmpty());
            }
        }
    }

    @Test
    void targetParentAndPlaneBindingsRejectStaleOrMalformedDeltasBeforeMutation() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("scope-bindings")) {
            PathObject roi = administrativeRoi();
            PathObject parent =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(5, 5, 75, 75, null),
                            PathClass.fromString("Tubule"));
            parent.setID(uuid(PARENT_ID));
            imageData.getHierarchy().getRootObject().addChildObject(roi);
            roi.addChildObject(parent);
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());

            CandidateGeneration fieldGeneration =
                    generation(
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));
            HierarchyDelta fieldDelta =
                    committer.stage(request(client, Set.of(), List.of(fieldGeneration)));
            ((PathROIObject) roi).setROI(ROIs.createRectangleROI(0, 0, 90, 90, null));
            assertThrows(IllegalArgumentException.class, () -> committer.apply(fieldDelta));
            assertFalse(objectsById(imageData.getHierarchy()).containsKey(uuid(CANDIDATE_A)));
            ((PathROIObject) roi).setROI(ROIs.createRectangleROI(0, 0, 100, 100, null));

            CandidateGeneration parentGeneration =
                    generation(
                            GENERATION_B,
                            new ExecutionScopeCodec.ParentScope(
                                    new ExecutionScopeCodec.Roi(ROI_ID), PARENT_ID),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.of(square(5, 5, 80, 80)),
                            List.of(candidate(CANDIDATE_B, square(30, 30, 40, 40))));
            HierarchyDelta parentDelta =
                    committer.stage(request(client, Set.of(), List.of(parentGeneration)));

            ((PathROIObject) roi).setROI(ROIs.createRectangleROI(0, 0, 90, 90, null));
            assertThrows(IllegalArgumentException.class, () -> committer.apply(parentDelta));
            ((PathROIObject) roi).setROI(ROIs.createRectangleROI(0, 0, 100, 100, null));

            ((PathROIObject) roi)
                    .setROI(ROIs.createRectangleROI(0, 0, 100, 100, ImagePlane.getPlane(1, 0)));
            assertThrows(IllegalArgumentException.class, () -> committer.apply(parentDelta));
            ((PathROIObject) roi).setROI(ROIs.createRectangleROI(0, 0, 100, 100, null));

            imageData.getHierarchy().getRootObject().removeChildObject(roi);
            assertThrows(IllegalArgumentException.class, () -> committer.apply(parentDelta));
            imageData.getHierarchy().getRootObject().addChildObject(roi);

            ((PathROIObject) parent).setROI(ROIs.createRectangleROI(5, 5, 70, 70, null));
            assertThrows(IllegalArgumentException.class, () -> committer.apply(parentDelta));
            assertFalse(objectsById(imageData.getHierarchy()).containsKey(uuid(CANDIDATE_B)));

            HierarchyDelta.ObjectDraft draft = fieldDelta.changes().addObjects().getFirst();
            HierarchyDelta.ObjectDraft wrongPlane =
                    new HierarchyDelta.ObjectDraft(
                            draft.objectId(),
                            GeometryTools.geometryToROI(
                                    draft.geometrySlide(), ImagePlane.getPlane(1, 0)),
                            draft.pathClass(),
                            new SourceIdentityCodec.ImagePlaneIndex(1, 0),
                            draft.provenance());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new HierarchyDelta(
                                    fieldDelta.actionId(),
                                    fieldDelta.replacementClosure(),
                                    fieldDelta.baseWorkingEpoch(),
                                    new HierarchyDelta.ChangeSet(
                                            fieldDelta.changes().removeObjectIds(),
                                            fieldDelta.changes().classChanges(),
                                            List.of(wrongPlane),
                                            fieldDelta.changes().parentAssignments()),
                                    fieldDelta.stagedGenerationStore()));
        }
    }

    @Test
    void everyChildParentIsCheckedEvenAfterItsGenerationBindingPassed() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("second-child-parent")) {
            PathObject roi = administrativeRoi();
            imageData.getHierarchy().getRootObject().addChildObject(roi);
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());
            CandidateGeneration generation =
                    generation(
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(
                                    candidate(CANDIDATE_A, square(10, 10, 20, 20)),
                                    candidate(CANDIDATE_B, square(30, 30, 40, 40))));
            HierarchyDelta good = committer.stage(request(client, Set.of(), List.of(generation)));
            HierarchyDelta bad =
                    new HierarchyDelta(
                            good.actionId(),
                            good.replacementClosure(),
                            good.baseWorkingEpoch(),
                            new HierarchyDelta.ChangeSet(
                                    good.changes().removeObjectIds(),
                                    good.changes().classChanges(),
                                    good.changes().addObjects(),
                                    List.of(
                                            new HierarchyDelta.ParentAssignment(
                                                    uuid(CANDIDATE_A), Optional.of(uuid(ROI_ID))),
                                            new HierarchyDelta.ParentAssignment(
                                                    uuid(CANDIDATE_B), Optional.empty()))),
                            good.stagedGenerationStore());
            List<PathObjectHierarchyEvent> events = new ArrayList<>();
            imageData.getHierarchy().addListener(events::add);

            assertThrows(IllegalArgumentException.class, () -> committer.apply(bad));
            assertEquals(0, committer.workingEpoch());
            assertTrue(events.isEmpty());
            assertTrue(roi.getChildObjects().isEmpty());
            assertTrue(ImageDataStateCodec.readGenerationStore(imageData).isEmpty());
            assertEquals(2, committer.apply(good).addedObjects());
        }
    }

    @Test
    void generationsSharingAParentStillValidateTheirOwnCapturedBinding() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("per-generation-binding")) {
            PathObject roi = administrativeRoi();
            PathObject parent =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(5, 5, 75, 75, null),
                            PathClass.fromString("Tubule"));
            parent.setID(uuid(PARENT_ID));
            imageData.getHierarchy().getRootObject().addChildObject(roi);
            roi.addChildObject(parent);
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());
            ExecutionScopeCodec.ExecutionScope scope =
                    new ExecutionScopeCodec.ParentScope(
                            new ExecutionScopeCodec.Roi(ROI_ID), PARENT_ID);
            ModuleRef module = ModuleRef.parse("histoforge/spermatogenesis").orElseThrow();
            CandidateGeneration first =
                    generation(
                            client,
                            ACTION_ID,
                            true,
                            "fixture-deployment",
                            GENERATION_A,
                            new InferenceIdentityCodec.ModelStepIdentity(module, "first"),
                            scope,
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.of(square(5, 5, 80, 80)),
                            List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));
            CandidateGeneration second =
                    generation(
                            client,
                            ACTION_ID,
                            true,
                            "fixture-deployment",
                            GENERATION_B,
                            new InferenceIdentityCodec.ModelStepIdentity(module, "second"),
                            scope,
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.of(square(5, 5, 70, 70)),
                            List.of(candidate(CANDIDATE_B, square(30, 30, 40, 40))));
            HierarchyDelta delta =
                    committer.stage(request(client, Set.of(), List.of(first, second)));
            List<PathObjectHierarchyEvent> events = new ArrayList<>();
            imageData.getHierarchy().addListener(events::add);

            assertThrows(IllegalArgumentException.class, () -> committer.apply(delta));
            assertEquals(0, committer.workingEpoch());
            assertTrue(events.isEmpty());
            assertTrue(parent.getChildObjects().isEmpty());
            assertSame(roi, parent.getParent());
            assertTrue(ImageDataStateCodec.readGenerationStore(imageData).isEmpty());
        }
    }

    @Test
    void failureAfterMutationPoisonsTheDisposableWorkingImage() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("poisoned-working-image")) {
            imageData.getHierarchy().getRootObject().addChildObject(administrativeRoi());
            BuildIdentityCodec.ClientBuildIdentity client = clientBuild(1_000_000);
            ModelOutputCommitter committer = new ModelOutputCommitter(imageData, client.limits());
            CandidateGeneration generation =
                    generation(
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(candidate(CANDIDATE_A, square(10, 10, 20, 20))));
            HierarchyDelta delta = committer.stage(request(client, Set.of(), List.of(generation)));
            imageData
                    .getHierarchy()
                    .addListener(
                            event -> {
                                if (event.getSource() == committer) {
                                    throw new IllegalStateException("fixture event failure");
                                }
                            });

            assertThrows(IllegalStateException.class, () -> committer.apply(delta));
            assertThrows(IllegalStateException.class, committer::workingEpoch);
            assertTrue(objectsById(imageData.getHierarchy()).containsKey(uuid(CANDIDATE_A)));
        }
    }

    @Test
    void repeatedProvenanceRetainsPerObjectMissingWarningsAndChecksDifferentOutputs()
            throws Exception {
        try (var image = imageData("provenance-reuse")) {
            var roi = administrativeRoi();
            image.getHierarchy().getRootObject().addChildObject(roi);
            var client = clientBuild(1_000_000);
            var generation =
                    generation(
                            client,
                            GENERATION_A,
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)),
                            target(new ExecutionScopeCodec.Roi(ROI_ID)),
                            Optional.empty(),
                            List.of(
                                    candidate(CANDIDATE_A, square(10, 10, 20, 20)),
                                    candidate(CANDIDATE_B, square(30, 30, 40, 40))));
            var initial = new ModelOutputCommitter(image, client.limits());
            initial.apply(initial.stage(request(client, Set.of(), List.of(generation))));
            var originalStore = ImageDataStateCodec.readGenerationStore(image).orElseThrow();
            ImageDataStateCodec.writeGenerationStore(
                    image, GenerationStoreCodec.emptyStore(), client.limits());
            var committer = new ModelOutputCommitter(image, client.limits());
            var empty = committer.stage(request(client, Set.of(), List.of()));
            var result = committer.apply(empty);
            assertFalse(result.changed());
            assertEquals(
                    List.of(uuid(CANDIDATE_A), uuid(CANDIDATE_B)),
                    result.missingGenerationReferences().stream()
                            .map(ModelOutputCommitter.MissingGenerationReference::objectId)
                            .toList());

            // Even after a successful no-op, a later apply must re-read the actual store.
            ImageDataStateCodec.writeGenerationStore(image, originalStore, client.limits());
            assertThrows(IllegalArgumentException.class, () -> committer.apply(empty));

            var withStore = new ModelOutputCommitter(image, client.limits());
            var check = withStore.stage(request(client, Set.of(), List.of()));
            var second = objectsById(image.getHierarchy()).get(uuid(CANDIDATE_B));
            var wrongOutput =
                    new ExecutionScopeCodec.OutputKey(
                            generation.output().producer(),
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            ProvenanceCodec.writeMetadata(
                    second.getMetadata(),
                    new ProvenanceCodec.ObjectProvenance(1, wrongOutput, GENERATION_A));
            assertThrows(IllegalArgumentException.class, () -> withStore.apply(check));
            assertEquals(0, withStore.workingEpoch());
            assertEquals(
                    originalStore, ImageDataStateCodec.readGenerationStore(image).orElseThrow());
        }
    }

    @Test
    void metadataChangesAfterStagingAreNotHiddenByEarlierSuccessfulDecoding() throws Exception {
        try (var image = imageData("metadata-recheck")) {
            var client = clientBuild(1_000_000);
            var object = object(MANUAL_ID, "Leydig cell", 10, 10);
            var provenance =
                    new ProvenanceCodec.ObjectProvenance(
                            1,
                            new ExecutionScopeCodec.OutputKey(
                                    new InferenceIdentityCodec.ModelStepIdentity(
                                            ModuleRef.parse("histoforge/leydig").orElseThrow(),
                                            "leydig"),
                                    new ExecutionScopeCodec.FieldScope(
                                            new ExecutionScopeCodec.Slide())),
                            GENERATION_A);
            ProvenanceCodec.writeMetadata(object.getMetadata(), provenance);
            image.getHierarchy().getRootObject().addChildObject(object);
            var committer = new ModelOutputCommitter(image, client.limits());
            var delta = committer.stage(request(client, Set.of(), List.of()));
            committer.apply(delta);
            String canonical = ProvenanceCodec.encodeMetadataValue(provenance);
            for (String invalid : new String[] {null, "not-json", " " + canonical}) {
                object.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, invalid);
                assertThrows(IllegalArgumentException.class, () -> committer.apply(delta));
                assertEquals(0, committer.workingEpoch());
            }
        }
    }

    private static ModelOutputCommitter.StagingRequest request(
            BuildIdentityCodec.ClientBuildIdentity client,
            Set<ExecutionScopeCodec.OutputKey> closure,
            List<CandidateGeneration> generations) {
        return new ModelOutputCommitter.StagingRequest(
                ACTION_ID,
                true,
                client,
                "fixture-deployment",
                closure,
                generations,
                List.of(),
                COMMITTED_AT);
    }

    private static CandidateGeneration generation(
            String generationId,
            ExecutionScopeCodec.ExecutionScope scope,
            ExecutionScopeCodec.TargetRegion target,
            Optional<Geometry> parentGeometry,
            List<CandidateGeneration.SlideCandidate> candidates) {
        return generation(
                clientBuild(1_000_000),
                ACTION_ID,
                true,
                "fixture-deployment",
                generationId,
                scope,
                target,
                parentGeometry,
                candidates);
    }

    private static CandidateGeneration generation(
            BuildIdentityCodec.ClientBuildIdentity client,
            String generationId,
            ExecutionScopeCodec.ExecutionScope scope,
            ExecutionScopeCodec.TargetRegion target,
            Optional<Geometry> parentGeometry,
            List<CandidateGeneration.SlideCandidate> candidates) {
        return generation(
                client,
                ACTION_ID,
                true,
                "fixture-deployment",
                generationId,
                scope,
                target,
                parentGeometry,
                candidates);
    }

    private static CandidateGeneration generation(
            BuildIdentityCodec.ClientBuildIdentity client,
            String actionId,
            boolean suppressOverlappingDuplicates,
            String deploymentId,
            String generationId,
            ExecutionScopeCodec.ExecutionScope scope,
            ExecutionScopeCodec.TargetRegion target,
            Optional<Geometry> parentGeometry,
            List<CandidateGeneration.SlideCandidate> candidates) {
        return generation(
                client,
                actionId,
                suppressOverlappingDuplicates,
                deploymentId,
                generationId,
                new InferenceIdentityCodec.ModelStepIdentity(
                        ModuleRef.parse("histoforge/leydig").orElseThrow(), "leydig"),
                scope,
                target,
                parentGeometry,
                candidates);
    }

    private static CandidateGeneration generation(
            BuildIdentityCodec.ClientBuildIdentity client,
            String actionId,
            boolean suppressOverlappingDuplicates,
            String deploymentId,
            String generationId,
            InferenceIdentityCodec.ModelStepIdentity producer,
            ExecutionScopeCodec.ExecutionScope scope,
            ExecutionScopeCodec.TargetRegion target,
            Optional<Geometry> parentGeometry,
            List<CandidateGeneration.SlideCandidate> candidates) {
        InferenceIdentityCodec.BackendModelIdentity backend =
                backendIdentity(producer.moduleRef(), producer.stepId());
        RasterInputCodec.ActionSourceRevision source = sourceRevision();
        SourceArtifactPublicationCodec.SourceArtifactRef artifact =
                new SourceArtifactPublicationCodec.SourceArtifactRef(
                        "99999999-9999-4999-8999-999999999999",
                        ACTION_ID,
                        hex('1'),
                        hex('2'),
                        hex('3'),
                        SourceArtifactCodec.SourceContainer.PNG);
        RasterInputCodec.SourceView sourceView =
                RasterInputCodec.buildSourceView(
                        artifact,
                        0,
                        0,
                        target.plane(),
                        new SourceIdentityCodec.IntRect(0, 0, 128, 128),
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0));
        RasterInputCodec.RasterTransform transform =
                new RasterInputCodec.RasterTransform(
                        new RasterInputCodec.RasterDescriptor(
                                128,
                                128,
                                target.plane(),
                                1.0,
                                RasterInputCodec.ResamplingType.BILINEAR,
                                RasterInputCodec.RasterPixelFormat.SRGB_UINT8),
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0));
        RasterInputCodec.RasterFrame frame =
                RasterInputCodec.buildRasterFrame(
                        source, target.key(), transform, square(0, 0, 100, 100));
        RasterInputCodec.ModelRasterInput input =
                RasterInputCodec.buildModelRasterInput(sourceView, frame, backend.rasterizer());
        ExecutionScopeCodec.ScopeAcceptance acceptance =
                new ExecutionScopeCodec.ScopeAcceptance(target, parentGeometry);
        CandidateGeneration.InputEvidence evidence =
                new CandidateGeneration.InputEvidence(
                        FiniteInferenceCodec.buildBackendInputIdentity(input),
                        ExecutionScopeCodec.scopeAcceptanceDigest(acceptance),
                        acceptance,
                        input);
        ImageDataStateCodec.GenerationStoreReservation reservation =
                ImageDataStateCodec.GenerationStoreReservation.forInput(
                        generationId,
                        actionId,
                        suppressOverlappingDuplicates,
                        deploymentId,
                        new ExecutionScopeCodec.OutputKey(producer, scope),
                        0,
                        ImageDataStateCodec.ProjectionExecutionType.FINITE_CALL,
                        input,
                        acceptance,
                        1,
                        client,
                        backend,
                        Optional.empty());
        return new CandidateGeneration(
                reservation,
                new BackendExecutionCodec.FiniteCall("fc-fixture"),
                evidence,
                new GenerationEvidenceCodec.GenerationCounts(
                        candidates.size(), candidates.size(), 0, candidates.size()),
                candidates);
    }

    private static CandidateGeneration.SlideCandidate candidate(
            String candidateId, Geometry geometry) {
        return new CandidateGeneration.SlideCandidate(
                candidateId,
                geometry,
                "Leydig cell",
                new SourceIdentityCodec.ImagePlaneIndex(0, 0));
    }

    private static CandidateGeneration withContext(
            CandidateGeneration generation,
            String actionId,
            boolean suppressOverlappingDuplicates,
            BuildIdentityCodec.ClientBuildIdentity client,
            String deploymentId) {
        ImageDataStateCodec.GenerationStoreReservation current = generation.reservation();
        ImageDataStateCodec.GenerationStoreReservation replacement =
                new ImageDataStateCodec.GenerationStoreReservation(
                        current.outputGenerationId(),
                        actionId,
                        suppressOverlappingDuplicates,
                        deploymentId,
                        current.output(),
                        current.inputId(),
                        current.executionType(),
                        current.modelRasterInputDigest(),
                        current.scopeAcceptanceDigest(),
                        current.maxInputEvidenceUtf8Bytes(),
                        current.sourceRevision(),
                        current.targetRegion(),
                        client,
                        current.backendIdentity(),
                        current.processingEvidence());
        return new CandidateGeneration(
                replacement,
                generation.execution(),
                generation.inputEvidence(),
                generation.counts(),
                generation.candidates());
    }

    private static RasterInputCodec.ActionSourceRevision sourceRevision() {
        SourceIdentityCodec.ImageSourceIdentity source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "fixture.reader",
                        hex('4'),
                        Optional.empty(),
                        128,
                        128,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(1, 1));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        "fixture-image",
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        hex('5')),
                source);
    }

    private static InferenceIdentityCodec.BackendModelIdentity backendIdentity() {
        return backendIdentity(ModuleRef.parse("histoforge/leydig").orElseThrow(), "leydig");
    }

    private static InferenceIdentityCodec.BackendModelIdentity backendIdentity(
            ModuleRef module, String modelId) {
        BuildIdentityCodec.SoftwareBuildIdentity software = softwareBuild("fixture");
        return new InferenceIdentityCodec.BackendModelIdentity(
                new InferenceIdentityCodec.ModuleContentIdentity(
                        module, Optional.of("a".repeat(40)), hex('6'), hex('7')),
                modelId,
                "cellpose-sam",
                "A10G",
                hex('8'),
                Map.of("weights", hex('9')),
                software,
                new InferenceIdentityCodec.RasterizerIdentity(software, hex('a')),
                hex('b'));
    }

    private static BuildIdentityCodec.ClientBuildIdentity clientBuild(long storeCap) {
        return clientBuild(storeCap, 1_000_000, 2_000_000, 3_000_000, 8_000_000);
    }

    private static BuildIdentityCodec.ClientBuildIdentity clientBuild(
            long storeCap,
            long maxHierarchyObjects,
            long maxHierarchyGeometryVertices,
            long maxHierarchyMeasurements,
            long maxHierarchyTextUtf8Bytes) {
        return new BuildIdentityCodec.ClientBuildIdentity(
                softwareBuild("extension"),
                "0.6.0",
                Map.of("java", "21", "jts", "1.20.0"),
                new ExecutionLimitsCodec.ClientExecutionLimits(
                        maxHierarchyObjects,
                        maxHierarchyGeometryVertices,
                        maxHierarchyMeasurements,
                        maxHierarchyTextUtf8Bytes,
                        storeCap,
                        100_000,
                        1_000_000,
                        8L * 1024 * 1024 * 1024));
    }

    private static BuildIdentityCodec.SoftwareBuildIdentity softwareBuild(String version) {
        return new BuildIdentityCodec.SoftwareBuildIdentity(version, hex('c'), hex('d'));
    }

    private static ExecutionScopeCodec.TargetRegion target(ExecutionScopeCodec.RegionKey region) {
        Geometry geometry = square(0, 0, 100, 100);
        return new ExecutionScopeCodec.TargetRegion(
                region,
                geometry,
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                CanonicalGeometryCodec.canonicalDigest(geometry));
    }

    private static ImageData<BufferedImage> imageData(String name) {
        return new ImageData<>(
                new WrappedBufferedImageServer(
                        name, new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB)),
                ImageType.UNSET);
    }

    private static PathObject administrativeRoi() {
        PathObject roi =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(0, 0, 100, 100, null),
                        RoiManager.HISTOFORGE_ROI_CLASS);
        roi.setID(uuid(ROI_ID));
        roi.setLocked(true);
        roi.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        return roi;
    }

    private static PathObject object(String id, String pathClass, int x, int y) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(x, y, 5, 5, null), PathClass.fromString(pathClass));
        object.setID(uuid(id));
        return object;
    }

    private static Map<UUID, PathObject> objectsById(PathObjectHierarchy hierarchy) {
        return hierarchy.getAllObjects(false).stream()
                .collect(Collectors.toMap(PathObject::getID, Function.identity()));
    }

    private static Geometry square(double minX, double minY, double maxX, double maxY) {
        return GEOMETRY_FACTORY.createPolygon(
                new CoordinateXY[] {
                    new CoordinateXY(minX, minY),
                    new CoordinateXY(maxX, minY),
                    new CoordinateXY(maxX, maxY),
                    new CoordinateXY(minX, maxY),
                    new CoordinateXY(minX, minY)
                });
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static String hex(char value) {
        return String.valueOf(value).repeat(64);
    }
}
