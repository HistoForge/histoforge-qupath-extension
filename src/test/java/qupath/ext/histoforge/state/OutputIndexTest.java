package qupath.ext.histoforge.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.ROIs;

/** Verifies the derived current-output lookup without introducing persisted presence state. */
class OutputIndexTest {

    private static final String ROI_A = "11111111-1111-4111-8111-111111111111";
    private static final String ROI_B = "22222222-2222-4222-8222-222222222222";
    private static final InferenceIdentityCodec.ModelStepIdentity PRODUCER_A =
            producer("module-a", "cells");
    private static final InferenceIdentityCodec.ModelStepIdentity PRODUCER_B =
            producer("module-b", "structures");
    private static final InferenceIdentityCodec.ModelStepIdentity PRODUCER_C =
            producer("module-c", "other");
    private static final ExecutionScopeCodec.OutputKey FIELD_A =
            new ExecutionScopeCodec.OutputKey(
                    PRODUCER_A,
                    new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_A)));
    private static final ExecutionScopeCodec.OutputKey PARENT_A =
            new ExecutionScopeCodec.OutputKey(
                    PRODUCER_A,
                    new ExecutionScopeCodec.ParentScope(
                            new ExecutionScopeCodec.Roi(ROI_A),
                            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    private static final ExecutionScopeCodec.OutputKey SLIDE_A =
            new ExecutionScopeCodec.OutputKey(
                    PRODUCER_A,
                    new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
    private static final ExecutionScopeCodec.OutputKey FIELD_B =
            new ExecutionScopeCodec.OutputKey(
                    PRODUCER_B,
                    new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_B)));

    @Test
    void hierarchyCaptureUsesOnlyCanonicalProvenanceAndSortsByPersistentId() {
        PathObject first = attributed("10000000-0000-4000-8000-000000000001", FIELD_A, 1);
        PathObject second = attributed("10000000-0000-4000-8000-000000000002", PARENT_A, 2);
        PathObject third = attributed("10000000-0000-4000-8000-000000000003", FIELD_A, 3);
        PathObject fourth = attributed("10000000-0000-4000-8000-000000000004", SLIDE_A, 4);
        PathObject fifth = attributed("10000000-0000-4000-8000-000000000005", FIELD_B, 5);
        PathObject legacy = detection("10000000-0000-4000-8000-000000000006");
        legacy.getMetadata().put("histoforge:owner_model", "histoforge/module-a/cells");
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(List.of(legacy, fifth, fourth, third, second, first));

        OutputIndex index = OutputIndex.fromHierarchy(hierarchy);

        assertTrue(index.contains(FIELD_A));
        assertEquals(List.of(first, third), index.objects(FIELD_A));
        assertEquals(List.of(first, second, third), index.objectsScopedToRoi(ROI_A));
        assertEquals(
                List.of(),
                index.objects(
                        new ExecutionScopeCodec.OutputKey(
                                PRODUCER_C,
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Slide()))));
        assertEquals(
                List.of(
                        provenance(FIELD_A, 1),
                        provenance(PARENT_A, 2),
                        provenance(FIELD_A, 3),
                        provenance(SLIDE_A, 4),
                        provenance(FIELD_B, 5)),
                index.currentProvenance());

        assertEquals(
                new OutputIndex.ProducerMaterialization(
                        PRODUCER_A,
                        OutputIndex.ProducerMaterializationMode.CONFLICTING,
                        Set.of(FIELD_A, PARENT_A, SLIDE_A)),
                index.materialization(PRODUCER_A));
        assertEquals(
                OutputIndex.ProducerMaterializationMode.ROI,
                index.materialization(PRODUCER_B).mode());
        assertEquals(
                OutputIndex.ProducerMaterializationMode.NONE,
                index.materialization(PRODUCER_C).mode());
        assertFalse(
                index.contains(
                        new ExecutionScopeCodec.OutputKey(
                                PRODUCER_C,
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Slide()))));
    }

    @Test
    void addAndRemoveMaintainPresenceAndMaterializationWithoutMutableViews() {
        OutputIndex index = OutputIndex.fromHierarchy(new PathObjectHierarchy());
        PathObject roi = attributed("20000000-0000-4000-8000-000000000001", FIELD_A, 1);
        PathObject slide = attributed("20000000-0000-4000-8000-000000000002", SLIDE_A, 2);
        PathObject secondRoi = attributed("20000000-0000-4000-8000-000000000003", FIELD_A, 3);

        index.add(roi);
        index.add(secondRoi);
        assertTrue(index.contains(FIELD_A));
        assertEquals(List.of(roi, secondRoi), index.objects(FIELD_A));
        assertEquals(
                OutputIndex.ProducerMaterializationMode.ROI,
                index.materialization(PRODUCER_A).mode());
        assertThrows(UnsupportedOperationException.class, () -> index.objects(FIELD_A).add(roi));
        assertThrows(
                UnsupportedOperationException.class,
                () -> index.materialization(PRODUCER_A).currentOutputs().add(SLIDE_A));

        index.add(slide);
        assertEquals(
                OutputIndex.ProducerMaterializationMode.CONFLICTING,
                index.materialization(PRODUCER_A).mode());
        PathObject duplicate = attributed(roi.getID().toString(), PARENT_A, 3);
        assertThrows(IllegalArgumentException.class, () -> index.add(duplicate));

        index.remove(roi.getID());
        assertTrue(index.contains(FIELD_A));
        assertEquals(List.of(secondRoi), index.objects(FIELD_A));
        assertEquals(
                OutputIndex.ProducerMaterializationMode.CONFLICTING,
                index.materialization(PRODUCER_A).mode());
        index.remove(secondRoi.getID());
        assertFalse(index.contains(FIELD_A));
        assertEquals(
                OutputIndex.ProducerMaterializationMode.SLIDE,
                index.materialization(PRODUCER_A).mode());
        index.remove(roi.getID());
        index.remove(slide.getID());
        assertEquals(
                OutputIndex.ProducerMaterializationMode.NONE,
                index.materialization(PRODUCER_A).mode());
    }

    @Test
    void malformedOrDuplicateAttributedStateFailsClosedWhileUnattributedObjectsAreIgnored() {
        PathObject corrupt = detection("30000000-0000-4000-8000-000000000001");
        corrupt.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "not-json");
        PathObjectHierarchy corruptHierarchy = new PathObjectHierarchy();
        corruptHierarchy.addObject(corrupt);
        assertThrows(
                IllegalArgumentException.class, () -> OutputIndex.fromHierarchy(corruptHierarchy));

        PathObject first = attributed("30000000-0000-4000-8000-000000000002", FIELD_A, 1);
        PathObject second = attributed(first.getID().toString(), SLIDE_A, 2);
        PathObjectHierarchy duplicateHierarchy = new PathObjectHierarchy();
        duplicateHierarchy.addObjects(List.of(first, second));
        assertThrows(
                IllegalArgumentException.class,
                () -> OutputIndex.fromHierarchy(duplicateHierarchy));

        PathObject unattributed = detection("30000000-0000-4000-8000-000000000003");
        PathObjectHierarchy unattributedHierarchy = new PathObjectHierarchy();
        unattributedHierarchy.addObject(unattributed);
        OutputIndex index = OutputIndex.fromHierarchy(unattributedHierarchy);
        assertEquals(List.of(), index.currentProvenance());
        assertThrows(IllegalArgumentException.class, () -> index.objectsScopedToRoi("not-a-uuid"));
    }

    @Test
    void batchChecksEveryObjectAndDoesNotRetainDecodedMetadataAcrossCalls() {
        var index = OutputIndex.fromHierarchy(new PathObjectHierarchy());
        var first = attributed("30000000-0000-4000-8000-000000000001", FIELD_A, 1);
        var second = attributed("30000000-0000-4000-8000-000000000002", FIELD_A, 1);
        index.addAll(List.of(second, first));
        assertEquals(List.of(first, second), index.objects(FIELD_A));
        var duplicate = attributed(first.getID().toString(), FIELD_A, 1);
        assertThrows(IllegalArgumentException.class, () -> index.addAll(List.of(duplicate)));

        index.remove(second.getID());
        second.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, null);
        assertThrows(IllegalArgumentException.class, () -> index.addAll(List.of(second)));
        ProvenanceCodec.writeMetadata(second.getMetadata(), provenance(SLIDE_A, 1));
        index.addAll(List.of(second));
        assertEquals(List.of(first), index.objects(FIELD_A));
        assertEquals(List.of(second), index.objects(SLIDE_A));
    }

    private static PathObject attributed(
            String objectId, ExecutionScopeCodec.OutputKey output, int generationSuffix) {
        PathObject object = detection(objectId);
        ProvenanceCodec.writeMetadata(object.getMetadata(), provenance(output, generationSuffix));
        return object;
    }

    private static ProvenanceCodec.ObjectProvenance provenance(
            ExecutionScopeCodec.OutputKey output, int generationSuffix) {
        return new ProvenanceCodec.ObjectProvenance(
                ProvenanceCodec.SCHEMA_VERSION,
                output,
                "40000000-0000-4000-8000-" + String.format("%012d", generationSuffix));
    }

    private static PathObject detection(String objectId) {
        PathObject object =
                PathObjects.createDetectionObject(ROIs.createRectangleROI(0, 0, 10, 10, null));
        object.setID(UUID.fromString(objectId));
        return object;
    }

    private static InferenceIdentityCodec.ModelStepIdentity producer(
            String moduleName, String stepId) {
        return new InferenceIdentityCodec.ModelStepIdentity(
                new ModuleRef("histoforge", moduleName), stepId);
    }
}
