package qupath.ext.histoforge.roi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.operation.valid.IsValidOp;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.geom.Point2;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Checks annotation lookup storage without relying on garbage collection. */
class RoiRegistryRetentionTest {

    @Test
    void detectionOnlyHierarchyRetainsNoObjectLookupEntries() throws Exception {
        var hierarchy = new PathObjectHierarchy();
        var detections = new ArrayList<PathObject>();
        for (int index = 0; index < 128; index++) {
            detections.add(detection(index * 2));
        }
        hierarchy.addObjects(detections);

        var registry = RoiRegistry.fromHierarchy(hierarchy);

        assertEquals(Set.of(), retainedLookupObjects(registry));
        assertEquals(List.of(), registry.rois());
        for (var detection : detections) {
            assertTrue(registry.findRoi(detection.getID().toString()).isEmpty());
        }
    }

    @Test
    void capturedSelectionKeepsEveryAnnotationOwnerButNoDetectionOrRootEntries() throws Exception {
        var hierarchy = new PathObjectHierarchy();
        var selected = annotation(0);
        var unselected = annotation(20);
        var marked = annotation(40);
        marked.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        var line =
                PathObjects.createAnnotationObject(
                        ROIs.createLineROI(60, 0, 70, 0, ImagePlane.getDefaultPlane()));
        var detection = detection(22);
        hierarchy.addObjects(List.of(selected, unselected, marked, line, detection));
        var current = RoiRegistry.fromHierarchy(hierarchy);
        var targets = current.captureTargets(List.of(selected.getID().toString()), 100, 100);
        var captured = RoiRegistry.forTargets(hierarchy, targets);
        var annotations = Set.of(selected, unselected, marked, line);

        assertEquals(annotations, retainedLookupObjects(current));
        assertEquals(annotations, retainedLookupObjects(captured));
        assertEquals(List.of(marked), current.rois());
        assertEquals(List.of(selected), captured.rois());
        assertSame(unselected, captured.findRoi(unselected.getID().toString()).orElseThrow());
        assertSame(marked, captured.findRoi(marked.getID().toString()).orElseThrow());
        assertTrue(captured.findRoi(line.getID().toString()).isEmpty());

        ProvenanceCodec.writeMetadata(
                detection.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(
                                new InferenceIdentityCodec.ModelStepIdentity(
                                        new ModuleRef("histoforge", "test-module"), "cells"),
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Roi(
                                                unselected.getID().toString()))),
                        UUID.randomUUID().toString()));
        assertEquals(
                new RoiRegistry.Member(unselected.getID().toString()),
                captured.membership(detection));
    }

    @Test
    void generatedAnnotationsAreNotRetainedButExplicitSelectionsRemainBoundaries()
            throws Exception {
        var hierarchy = new PathObjectHierarchy();
        var ordinary = annotation(0);
        var selected = annotation(20);
        var selectedElsewhere = annotation(40);
        var generated = annotation(60);
        var marked = annotation(80);
        var targets = List.of(target(selected), target(selectedElsewhere));
        for (var object : List.of(selected, selectedElsewhere, generated, marked)) {
            object.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "malformed");
        }
        marked.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        var objects =
                List.of(
                        hierarchy.getRootObject(),
                        ordinary,
                        selected,
                        selectedElsewhere,
                        generated,
                        marked);

        var current = RoiRegistry.fromHierarchyObjects(objects);
        var captured = RoiRegistry.forTargetsFromHierarchyObjects(objects, targets);

        assertEquals(Set.of(ordinary, marked), retainedLookupObjects(current));
        assertEquals(
                Set.of(ordinary, marked, selected, selectedElsewhere),
                retainedLookupObjects(captured));
        assertEquals(Set.of(selected, selectedElsewhere), Set.copyOf(captured.rois()));
        assertTrue(captured.isBoundary(selected));
        assertTrue(captured.isBoundary(selectedElsewhere));
        assertTrue(captured.findRoi(selected.getID().toString()).isEmpty());
        assertSame(ordinary, captured.findRoi(ordinary.getID().toString()).orElseThrow());
        assertSame(marked, captured.findRoi(marked.getID().toString()).orElseThrow());
        assertThrows(
                RoiRegistry.MalformedObjectProvenanceException.class,
                () -> captured.membership(generated));
    }

    @Test
    void retentionUsesOnlyMetadataAndTreatsPresentNullAsPresent() throws Exception {
        var root = new PathObjectHierarchy().getRootObject();
        var ordinary = spy(annotation(0));
        var selected = spy(annotation(20));
        var targets = List.of(target(selected));
        doThrow(new AssertionError("retention must not read ordinary geometry"))
                .when(ordinary)
                .getROI();
        doThrow(new AssertionError("retention must not read selected geometry"))
                .when(selected)
                .getROI();
        for (String provenance : Arrays.asList("malformed", null)) {
            var generated = spy(annotation(40));
            var metadata = new HashMap<String, String>();
            metadata.put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, provenance);
            doReturn(metadata).when(generated).getMetadata();
            doReturn(true).when(generated).hasMetadata();
            doThrow(new AssertionError("retention must not read generated geometry"))
                    .when(generated)
                    .getROI();
            doReturn(metadata).when(selected).getMetadata();
            doReturn(true).when(selected).hasMetadata();
            var objects = List.of(root, ordinary, selected, generated);
            assertEquals(
                    Set.of(ordinary),
                    retainedLookupObjects(RoiRegistry.fromHierarchyObjects(objects)));
            var captured = RoiRegistry.forTargetsFromHierarchyObjects(objects, targets);
            assertEquals(Set.of(ordinary, selected), retainedLookupObjects(captured));
            assertEquals(List.of(selected), captured.rois());
            assertThrows(
                    RoiRegistry.MalformedObjectProvenanceException.class,
                    () -> captured.membership(generated));
        }
    }

    @Test
    void excludedGeneratedAnnotationStillParticipatesInUuidValidation() {
        var root = new PathObjectHierarchy().getRootObject();
        var generated = annotation(0);
        generated.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "malformed");
        generated.setID(root.getID());
        var failure =
                assertThrows(
                        RoiRegistry.CorruptHierarchyException.class,
                        () -> RoiRegistry.fromHierarchyObjects(List.of(root, generated)));
        assertTrue(failure.getCause().getMessage().contains("duplicate object UUID"));
        var withoutId = mock(PathObject.class);
        when(withoutId.isAnnotation()).thenReturn(true);
        assertInstanceOf(
                NullPointerException.class,
                assertThrows(
                                RoiRegistry.CorruptHierarchyException.class,
                                () -> RoiRegistry.fromHierarchyObjects(List.of(root, withoutId)))
                        .getCause());
    }

    @Test
    void administrativeMarkerOnDetectionStillFailsEvenWithProvenance() {
        var root = new PathObjectHierarchy().getRootObject();
        var detection = detection(0);
        detection.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        detection.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "malformed");
        assertThrows(
                RoiRegistry.CorruptHierarchyException.class,
                () -> RoiRegistry.fromHierarchyObjects(List.of(root, detection)));
    }

    @Test
    void spatialFallbackValidatesNativePolygonOnce() {
        var root = new PathObjectHierarchy().getRootObject();
        var boundary = annotation(0);
        var object =
                PathObjects.createDetectionObject(
                        ROIs.createPolygonROI(
                                List.of(new Point2(1, 1), new Point2(2, 1), new Point2(1, 2)),
                                ImagePlane.getDefaultPlane()));
        var registry =
                RoiRegistry.forTargetsFromHierarchyObjects(
                        List.of(root, boundary, object), List.of(target(boundary)));
        try (var checks = mockConstruction(IsValidOp.class)) {
            assertEquals(
                    new RoiRegistry.Member(boundary.getID().toString()),
                    registry.membership(object));
            assertEquals(1, checks.constructed().size());
            verify(checks.constructed().getFirst()).getValidationError();
            verify(checks.constructed().getFirst(), never()).isValid();
        }
    }

    private static ExecutionScopeCodec.TargetRegion target(PathObject object) {
        var roi = object.getROI();
        var geometry = roi.getGeometry();
        return new ExecutionScopeCodec.TargetRegion(
                new ExecutionScopeCodec.Roi(object.getID().toString()),
                geometry,
                new SourceIdentityCodec.ImagePlaneIndex(roi.getZ(), roi.getT()),
                CanonicalGeometryCodec.canonicalDigest(geometry));
    }

    @Test
    void detectionCannotReuseRootUuid() {
        var hierarchy = new PathObjectHierarchy();
        var detection = detection(0);
        detection.setID(hierarchy.getRootObject().getID());
        hierarchy.addObject(detection);

        assertDuplicateUuidRejected(hierarchy);
    }

    @Test
    void detectionCannotReuseAnnotationUuid() {
        var hierarchy = new PathObjectHierarchy();
        var annotation = annotation(0);
        var detection = detection(20);
        detection.setID(annotation.getID());
        hierarchy.addObjects(List.of(annotation, detection));

        assertDuplicateUuidRejected(hierarchy);
    }

    private static void assertDuplicateUuidRejected(PathObjectHierarchy hierarchy) {
        var failure =
                assertThrows(
                        RoiRegistry.CorruptHierarchyException.class,
                        () -> RoiRegistry.fromHierarchy(hierarchy));
        var cause = assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("duplicate object UUID"));
    }

    private static Set<PathObject> retainedLookupObjects(RoiRegistry registry)
            throws IllegalAccessException {
        // Lookup results cannot reveal unused entries. Inspect values without garbage collection.
        var objects = new LinkedHashSet<PathObject>();
        for (var field : RoiRegistry.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            for (var value : ((Map<?, ?>) field.get(registry)).values()) {
                if (value instanceof PathObject object) {
                    objects.add(object);
                }
            }
        }
        return objects;
    }

    private static PathObject annotation(double x) {
        return PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(x, 0, 10, 10, ImagePlane.getDefaultPlane()));
    }

    private static PathObject detection(double x) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, 2, 1, 1, ImagePlane.getDefaultPlane()));
    }
}
