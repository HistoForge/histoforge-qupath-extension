package qupath.ext.histoforge.roi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent.HierarchyEventType;
import qupath.lib.roi.ROIs;

/** Behavioral coverage for the one explicit and generic HistoForge-ROI cleanup owner. */
class RoiDeletionCleanupTest {

    private static final String TARGET_ROI_ID = "33333333-3333-4333-8333-333333333333";
    private static final String OTHER_ROI_ID = "88888888-8888-4888-8888-888888888888";
    private static final String SLIDE_GENERATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String OTHER_GENERATION_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String PARENT_GENERATION_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    @Test
    void explicitCleanupUsesLogicalScopeAndPreservesUnownedSlideAndOtherRoiObjects()
            throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("explicit-cleanup")) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            PathObject root = hierarchy.getRootObject();
            PathObject targetRoi = administrativeRoi(TARGET_ROI_ID, 10, 20);
            PathObject otherRoi = administrativeRoi(OTHER_ROI_ID, 140, 20);
            root.addChildObject(targetRoi);
            root.addChildObject(otherRoi);

            GenerationStoreCodec.GenerationStore store = generationStoreFixture();
            GenerationEvidenceCodec.GenerationRecord record =
                    store.currentGenerationRecords().values().iterator().next();
            PathObject targetOutput =
                    attributedObject(
                            "11111111-aaaa-4111-8111-111111111111",
                            180,
                            180,
                            record.output(),
                            record.outputGenerationId());
            PathObject manualChild = object("22222222-aaaa-4222-8222-222222222222", 182, 182);
            targetOutput.addChildObject(manualChild);
            root.addChildObject(targetOutput);

            ExecutionScopeCodec.OutputKey targetParentOutput =
                    new ExecutionScopeCodec.OutputKey(
                            record.output().producer(),
                            new ExecutionScopeCodec.ParentScope(
                                    new ExecutionScopeCodec.Roi(TARGET_ROI_ID),
                                    targetOutput.getID().toString()));
            PathObject parentScopedTarget =
                    attributedObject(
                            "33333333-aaaa-4333-8333-333333333333",
                            184,
                            184,
                            targetParentOutput,
                            PARENT_GENERATION_ID);
            root.addChildObject(parentScopedTarget);

            ExecutionScopeCodec.OutputKey slideOutputKey =
                    new ExecutionScopeCodec.OutputKey(
                            record.output().producer(),
                            new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
            PathObject slideOutput =
                    attributedObject(
                            "44444444-aaaa-4444-8444-444444444444",
                            20,
                            30,
                            slideOutputKey,
                            SLIDE_GENERATION_ID);
            targetRoi.addChildObject(slideOutput);

            ExecutionScopeCodec.OutputKey otherRoiOutputKey =
                    new ExecutionScopeCodec.OutputKey(
                            record.output().producer(),
                            new ExecutionScopeCodec.FieldScope(
                                    new ExecutionScopeCodec.Roi(OTHER_ROI_ID)));
            PathObject otherRoiOutput =
                    attributedObject(
                            "55555555-aaaa-4555-8555-555555555555",
                            30,
                            40,
                            otherRoiOutputKey,
                            OTHER_GENERATION_ID);
            targetRoi.addChildObject(otherRoiOutput);
            ImageDataStateCodec.writeGenerationStore(imageData, store);

            RoiDeletionCleanup.Result result =
                    RoiDeletionCleanup.apply(imageData, Set.of(targetRoi));

            Map<UUID, PathObject> remaining = objectsById(hierarchy);
            assertFalse(remaining.containsKey(targetRoi.getID()));
            assertFalse(remaining.containsKey(targetOutput.getID()));
            assertFalse(remaining.containsKey(parentScopedTarget.getID()));
            assertSame(manualChild, remaining.get(manualChild.getID()));
            assertSame(slideOutput, remaining.get(slideOutput.getID()));
            assertSame(otherRoiOutput, remaining.get(otherRoiOutput.getID()));
            assertSame(root, manualChild.getParent());
            assertSame(root, slideOutput.getParent());
            assertSame(otherRoi, otherRoiOutput.getParent());

            assertEquals(1, result.removedRois());
            assertEquals(2, result.removedAttributedObjects());
            assertEquals(3, result.liftedPreservedObjects());
            assertEquals(
                    Set.of(SLIDE_GENERATION_ID, OTHER_GENERATION_ID),
                    result.missingGenerationRecordIds());
            assertTrue(
                    ImageDataStateCodec.readGenerationStore(imageData)
                            .orElseThrow()
                            .currentGenerationRecords()
                            .isEmpty());
        }
    }

    @Test
    void roiManagerPublishesOneCompletedHierarchyEdit() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("manager-cleanup")) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            PathObject targetRoi = administrativeRoi(TARGET_ROI_ID, 10, 20);
            hierarchy.getRootObject().addChildObject(targetRoi);
            RoiManager manager = new RoiManager();
            manager.populateFromHierarchy(hierarchy);
            var events = new ArrayList<PathObjectHierarchyEvent>();
            hierarchy.addListener(events::add);

            manager.deleteRois(imageData, Set.of(targetRoi));

            assertEquals(1, events.size());
            assertSame(manager, events.get(0).getSource());
            assertSame(hierarchy, events.get(0).getHierarchy());
            assertEquals(HierarchyEventType.OTHER_STRUCTURE_CHANGE, events.get(0).getEventType());
        }
    }

    @Test
    void deletingAnotherRoiKeepsTheCompleteReferencedGenerationClosure() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("retained-evidence")) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            PathObject retainedRoi = administrativeRoi(TARGET_ROI_ID, 10, 20);
            PathObject deletedRoi = administrativeRoi(OTHER_ROI_ID, 140, 20);
            hierarchy.getRootObject().addChildObject(retainedRoi);
            hierarchy.getRootObject().addChildObject(deletedRoi);
            GenerationStoreCodec.GenerationStore store = generationStoreFixture();
            GenerationEvidenceCodec.GenerationRecord record =
                    store.currentGenerationRecords().values().iterator().next();
            PathObject retainedOutput =
                    attributedObject(
                            "dddddddd-1111-4111-8111-dddddddddddd",
                            150,
                            30,
                            record.output(),
                            record.outputGenerationId());
            deletedRoi.addChildObject(retainedOutput);
            ImageDataStateCodec.writeGenerationStore(imageData, store);

            RoiDeletionCleanup.apply(imageData, Set.of(deletedRoi));

            assertSame(retainedRoi, retainedOutput.getParent());
            var retained = ImageDataStateCodec.readGenerationStore(imageData).orElseThrow();
            assertEquals(1, store.schemaVersion());
            assertEquals(GenerationStoreCodec.SCHEMA_VERSION, retained.schemaVersion());
            assertEquals(store.sourceRevisions(), retained.sourceRevisions());
            assertEquals(store.targetRegions(), retained.targetRegions());
            assertEquals(store.clientBuilds(), retained.clientBuilds());
            assertEquals(store.backendIdentities(), retained.backendIdentities());
            assertEquals(store.currentGenerationRecords(), retained.currentGenerationRecords());
        }
    }

    @Test
    void genericQuPathDeletionRoutesThroughTheSameCleanup() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("generic-cleanup")) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            PathObject targetRoi = administrativeRoi(TARGET_ROI_ID, 10, 20);
            PathObject otherRoi = administrativeRoi(OTHER_ROI_ID, 140, 20);
            GenerationStoreCodec.GenerationStore store = generationStoreFixture();
            GenerationEvidenceCodec.GenerationRecord record =
                    store.currentGenerationRecords().values().iterator().next();
            PathObject targetOutput =
                    attributedObject(
                            "66666666-aaaa-4666-8666-666666666666",
                            20,
                            30,
                            record.output(),
                            record.outputGenerationId());
            PathObject manualChild = object("77777777-aaaa-4777-8777-777777777777", 22, 32);
            targetOutput.addChildObject(manualChild);
            targetRoi.addChildObject(targetOutput);
            ExecutionScopeCodec.OutputKey otherRoiOutputKey =
                    new ExecutionScopeCodec.OutputKey(
                            record.output().producer(),
                            new ExecutionScopeCodec.FieldScope(
                                    new ExecutionScopeCodec.Roi(OTHER_ROI_ID)));
            PathObject otherRoiOutput =
                    attributedObject(
                            "eeeeeeee-1111-4111-8111-eeeeeeeeeeee",
                            30,
                            40,
                            otherRoiOutputKey,
                            OTHER_GENERATION_ID);
            targetRoi.addChildObject(otherRoiOutput);
            hierarchy.getRootObject().addChildObject(targetRoi);
            hierarchy.getRootObject().addChildObject(otherRoi);
            ImageDataStateCodec.writeGenerationStore(imageData, store);

            RoiManager manager = new RoiManager();
            manager.populateFromHierarchy(hierarchy);
            HierarchyListener listener = new HierarchyListener(manager, () -> imageData);
            hierarchy.addListener(listener);

            hierarchy.removeObject(targetRoi, true);

            Map<UUID, PathObject> remaining = objectsById(hierarchy);
            assertFalse(remaining.containsKey(targetRoi.getID()));
            assertFalse(remaining.containsKey(targetOutput.getID()));
            assertSame(manualChild, remaining.get(manualChild.getID()));
            assertSame(otherRoiOutput, remaining.get(otherRoiOutput.getID()));
            assertSame(hierarchy.getRootObject(), manualChild.getParent());
            assertSame(otherRoi, otherRoiOutput.getParent());
            assertEquals(Set.of(otherRoi), Set.copyOf(manager.getHistoforgeRois()));
            assertTrue(
                    ImageDataStateCodec.readGenerationStore(imageData)
                            .orElseThrow()
                            .currentGenerationRecords()
                            .isEmpty());
        }
    }

    @Test
    void genericCascadeDeletionRecoversPreservedDetachedDescendants() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("generic-cascade-cleanup")) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            PathObject targetRoi = administrativeRoi(TARGET_ROI_ID, 10, 20);
            GenerationEvidenceCodec.GenerationRecord record =
                    generationStoreFixture().currentGenerationRecords().values().iterator().next();
            PathObject targetOutput =
                    attributedObject(
                            "ffffffff-1111-4111-8111-ffffffffffff",
                            20,
                            30,
                            record.output(),
                            record.outputGenerationId());
            PathObject manualChild = object("12121212-aaaa-4121-8121-121212121212", 22, 32);
            targetOutput.addChildObject(manualChild);
            targetRoi.addChildObject(targetOutput);
            hierarchy.getRootObject().addChildObject(targetRoi);

            RoiManager manager = new RoiManager();
            manager.populateFromHierarchy(hierarchy);
            hierarchy.addListener(new HierarchyListener(manager, () -> imageData));

            hierarchy.removeObject(targetRoi, false);

            Map<UUID, PathObject> remaining = objectsById(hierarchy);
            assertFalse(remaining.containsKey(targetRoi.getID()));
            assertFalse(remaining.containsKey(targetOutput.getID()));
            assertSame(manualChild, remaining.get(manualChild.getID()));
            assertSame(hierarchy.getRootObject(), manualChild.getParent());
        }
    }

    @Test
    void genericDeletionRestoresTheRoiWhenCleanupCannotValidateCurrentState() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("failed-cleanup")) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            PathObject targetRoi = administrativeRoi(TARGET_ROI_ID, 10, 20);
            PathObject corrupt = object("99999999-aaaa-4999-8999-999999999999", 20, 30);
            corrupt.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "not-json");
            targetRoi.addChildObject(corrupt);
            hierarchy.getRootObject().addChildObject(targetRoi);

            RoiManager manager = new RoiManager();
            manager.populateFromHierarchy(hierarchy);
            HierarchyListener listener = new HierarchyListener(manager, () -> imageData);
            hierarchy.addListener(listener);

            hierarchy.removeObject(targetRoi, false);

            Map<UUID, PathObject> remaining = objectsById(hierarchy);
            assertSame(targetRoi, remaining.get(targetRoi.getID()));
            assertSame(corrupt, remaining.get(corrupt.getID()));
            assertSame(hierarchy.getRootObject(), targetRoi.getParent());
            assertSame(targetRoi, corrupt.getParent());
            assertEquals(Set.of(targetRoi), Set.copyOf(manager.getHistoforgeRois()));
        }
    }

    @Test
    void cyclicLogicalParentAssignmentsFailBeforeHierarchyMutation() throws Exception {
        try (ImageData<BufferedImage> imageData = imageData("cyclic-cleanup")) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            PathObject targetRoi = administrativeRoi(TARGET_ROI_ID, 10, 20);
            PathObject otherRoi = administrativeRoi(OTHER_ROI_ID, 140, 20);
            hierarchy.getRootObject().addChildObject(targetRoi);
            hierarchy.getRootObject().addChildObject(otherRoi);
            GenerationEvidenceCodec.GenerationRecord record =
                    generationStoreFixture().currentGenerationRecords().values().iterator().next();

            PathObject first = object("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa", 20, 30);
            PathObject second = object("bbbbbbbb-1111-4111-8111-bbbbbbbbbbbb", 30, 40);
            ProvenanceCodec.writeMetadata(
                    first.getMetadata(),
                    new ProvenanceCodec.ObjectProvenance(
                            ProvenanceCodec.SCHEMA_VERSION,
                            new ExecutionScopeCodec.OutputKey(
                                    record.output().producer(),
                                    new ExecutionScopeCodec.ParentScope(
                                            new ExecutionScopeCodec.Roi(OTHER_ROI_ID),
                                            second.getID().toString())),
                            SLIDE_GENERATION_ID));
            ProvenanceCodec.writeMetadata(
                    second.getMetadata(),
                    new ProvenanceCodec.ObjectProvenance(
                            ProvenanceCodec.SCHEMA_VERSION,
                            new ExecutionScopeCodec.OutputKey(
                                    record.output().producer(),
                                    new ExecutionScopeCodec.ParentScope(
                                            new ExecutionScopeCodec.Roi(OTHER_ROI_ID),
                                            first.getID().toString())),
                            OTHER_GENERATION_ID));
            targetRoi.addChildObject(first);
            targetRoi.addChildObject(second);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> RoiDeletionCleanup.apply(imageData, Set.of(targetRoi)));
            assertSame(hierarchy.getRootObject(), targetRoi.getParent());
            assertSame(targetRoi, first.getParent());
            assertSame(targetRoi, second.getParent());
        }
    }

    private static ImageData<BufferedImage> imageData(String name) {
        return new ImageData<>(
                new WrappedBufferedImageServer(
                        name, new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)),
                ImageType.UNSET);
    }

    private static PathObject administrativeRoi(String id, int x, int y) {
        PathObject roi =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(x, y, 100, 100, null),
                        RoiManager.HISTOFORGE_ROI_CLASS);
        roi.setID(UUID.fromString(id));
        roi.setLocked(true);
        roi.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        return roi;
    }

    private static PathObject attributedObject(
            String id, int x, int y, ExecutionScopeCodec.OutputKey output, String generationId) {
        PathObject object = object(id, x, y);
        ProvenanceCodec.writeMetadata(
                object.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION, output, generationId));
        return object;
    }

    private static PathObject object(String id, int x, int y) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(x, y, 10, 10, null),
                        PathClass.fromString("Fixture"));
        object.setID(UUID.fromString(id));
        return object;
    }

    private static GenerationStoreCodec.GenerationStore generationStoreFixture()
            throws IOException {
        try (var stream =
                RoiDeletionCleanupTest.class.getResourceAsStream(
                        "/contracts/generation-store-v1.json")) {
            if (stream == null) {
                throw new IOException("missing generation-store-v1.json");
            }
            JsonObject fixture = CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
            return GenerationStoreCodec.parseGenerationStore(
                    CanonicalCodec.parseJson(fixture.get("canonical").getAsString()));
        }
    }

    private static Map<UUID, PathObject> objectsById(PathObjectHierarchy hierarchy) {
        return hierarchy.getAllObjects(false).stream()
                .collect(Collectors.toMap(PathObject::getID, Function.identity()));
    }
}
