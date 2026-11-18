package qupath.ext.histoforge.roi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Verifies exact administrative ROI identity, target capture, and logical/spatial membership. */
class RoiRegistryTest {

    private static final String ROI_A = "11111111-1111-4111-8111-111111111111";
    private static final String ROI_B = "22222222-2222-4222-8222-222222222222";
    private static final String ROI_C = "33333333-3333-4333-8333-333333333333";
    private static final String ROI_D = "44444444-4444-4444-8444-444444444444";
    private static final String MISSING_ROI = "99999999-9999-4999-8999-999999999999";
    private static final String GENERATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final InferenceIdentityCodec.ModelStepIdentity PRODUCER =
            new InferenceIdentityCodec.ModelStepIdentity(
                    new ModuleRef("histoforge", "test-module"), "cells");

    @Test
    void exactMetadataMarkerAloneDefinesTheCapturedRoiSet() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathObject marked = administrativeRoi(ROI_B, 0, 0, 20, 20, ImagePlane.getDefaultPlane());
        marked.setPathClass(PathClass.fromString("Displayed as anything"));
        PathObject legacyClassOnly =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(30, 0, 20, 20, ImagePlane.getDefaultPlane()),
                        RoiManager.HISTOFORGE_ROI_CLASS);
        legacyClassOnly.setID(uuid(ROI_A));
        hierarchy.addObjects(List.of(legacyClassOnly, marked));

        RoiRegistry registry = RoiRegistry.fromHierarchy(hierarchy);

        assertEquals(List.of(marked), registry.rois());
        assertSame(marked, registry.findRoi(ROI_B).orElseThrow());
        assertSame(legacyClassOnly, registry.findRoi(ROI_A).orElseThrow());
        assertFalse(RoiRegistry.isAdministrativeRoi(legacyClassOnly));
        assertTrue(RoiRegistry.isAdministrativeRoi(marked));
    }

    @Test
    void targetCaptureIsCanonicalDefensiveAndRejectsInvalidSelections() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathObject first = administrativeRoi(ROI_A, 0, 0, 50, 50, ImagePlane.getDefaultPlane());
        PathObject touching = administrativeRoi(ROI_B, 50, 0, 50, 50, ImagePlane.getDefaultPlane());
        PathObject overlapping =
                administrativeRoi(ROI_C, 25, 25, 50, 50, ImagePlane.getDefaultPlane());
        PathObject outside = administrativeRoi(ROI_D, -1, 60, 20, 20, ImagePlane.getDefaultPlane());
        hierarchy.addObjects(List.of(outside, overlapping, touching, first));
        RoiRegistry registry = RoiRegistry.fromHierarchy(hierarchy);

        var targets = registry.captureTargets(List.of(ROI_B, ROI_A), 100, 100);
        assertEquals(
                List.of(ROI_A, ROI_B),
                targets.stream()
                        .map(target -> ((ExecutionScopeCodec.Roi) target.key()).roiId())
                        .toList());
        assertEquals(2500.0, targets.get(0).geometrySlide().getArea());

        var exposedGeometry = targets.get(0).geometrySlide();
        shiftX(exposedGeometry, 1000.0);
        assertEquals(1000.0, exposedGeometry.getEnvelopeInternal().getMinX());
        assertEquals(0.0, targets.get(0).geometrySlide().getEnvelopeInternal().getMinX());
        assertEquals(
                targets.get(0).geometryDigest(),
                registry.captureTargets(List.of(ROI_A), 100, 100).get(0).geometryDigest());

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.captureTargets(List.of(ROI_A, ROI_A), 100, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.captureTargets(List.of(MISSING_ROI), 100, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.captureTargets(List.of(ROI_D), 100, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.captureTargets(List.of(ROI_A, ROI_C), 100, 100));
        assertThrows(
                IllegalArgumentException.class, () -> registry.captureTargets(List.of(), 100, 100));
    }

    @Test
    void targetCaptureAllowsTheSameFootprintOnDifferentPlanes() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(
                List.of(
                        administrativeRoi(ROI_A, 0, 0, 50, 50, ImagePlane.getDefaultPlane()),
                        administrativeRoi(ROI_B, 0, 0, 50, 50, ImagePlane.getPlane(1, 0))));

        assertEquals(
                2,
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(List.of(ROI_A, ROI_B), 100, 100)
                        .size());
    }

    @Test
    void logicalProvenanceWinsAndMissingLogicalRoiNeverFallsBack() {
        PathObjectHierarchy hierarchy = overlappingRoiHierarchy();
        RoiRegistry registry = RoiRegistry.fromHierarchy(hierarchy);

        PathObject logicalB = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        writeProvenance(
                logicalB,
                new ExecutionScopeCodec.ParentScope(new ExecutionScopeCodec.Roi(ROI_B), ROI_D));
        assertEquals(new RoiRegistry.Member(ROI_B), registry.membership(logicalB));

        PathObject missing = detection(ROI_D, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        writeProvenance(
                missing,
                new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(MISSING_ROI)));
        assertEquals(new RoiRegistry.MissingLogicalRoi(MISSING_ROI), registry.membership(missing));
    }

    @Test
    void unattributedAndSlideAttributedPolygonsUseUniqueInteriorPointCoverage() {
        PathObjectHierarchy hierarchy = overlappingRoiHierarchy();
        PathObject parentB =
                hierarchy.getAllObjects(false).stream()
                        .filter(object -> ROI_B.equals(object.getID().toString()))
                        .findFirst()
                        .orElseThrow();
        PathObject ancestryMismatch = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        hierarchy.addObjectBelowParent(parentB, ancestryMismatch, false);
        RoiRegistry registry = RoiRegistry.fromHierarchy(hierarchy);

        assertEquals(new RoiRegistry.Member(ROI_A), registry.membership(ancestryMismatch));

        PathObject slideAttributed = detection(ROI_D, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        writeProvenance(
                slideAttributed,
                new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
        assertEquals(new RoiRegistry.Member(ROI_A), registry.membership(slideAttributed));

        PathObject ambiguous =
                detection(
                        "55555555-5555-4555-8555-555555555555",
                        60,
                        10,
                        5,
                        5,
                        ImagePlane.getDefaultPlane());
        assertEquals(
                new RoiRegistry.Ambiguous(Set.of(ROI_A, ROI_B)), registry.membership(ambiguous));

        PathObject outside =
                detection(
                        "66666666-6666-4666-8666-666666666666",
                        200,
                        10,
                        5,
                        5,
                        ImagePlane.getDefaultPlane());
        assertInstanceOf(RoiRegistry.Outside.class, registry.membership(outside));

        PathObject otherPlane =
                detection(
                        "77777777-7777-4777-8777-777777777777",
                        10,
                        10,
                        5,
                        5,
                        ImagePlane.getPlane(1, 0));
        assertInstanceOf(RoiRegistry.Outside.class, registry.membership(otherPlane));
    }

    @Test
    void concaveInteriorPointAndSharedBoundaryPinTheExactJtsMembershipRule() {
        double[] concaveX = {0, 10, 10, 2, 2, 0};
        double[] concaveY = {0, 0, 2, 2, 10, 10};
        PathObject concaveObject = polygonDetection(ROI_C, concaveX, concaveY);
        var objectGeometry = concaveObject.getROI().getGeometry();
        var interiorPoint = objectGeometry.getInteriorPoint();
        PathObject rectangle =
                administrativeRoi(
                        ROI_A,
                        interiorPoint.getX() - 0.25,
                        interiorPoint.getY() - 0.25,
                        0.5,
                        0.5,
                        ImagePlane.getDefaultPlane());
        var rectangleGeometry = rectangle.getROI().getGeometry();
        assertFalse(rectangleGeometry.covers(objectGeometry.getCentroid()));
        assertTrue(rectangleGeometry.covers(interiorPoint));
        PathObjectHierarchy concaveHierarchy = new PathObjectHierarchy();
        concaveHierarchy.addObject(rectangle);
        assertEquals(
                new RoiRegistry.Member(ROI_A),
                RoiRegistry.fromHierarchy(concaveHierarchy).membership(concaveObject));

        PathObjectHierarchy touchingHierarchy = new PathObjectHierarchy();
        PathObject left = administrativeRoi(ROI_A, 0, 0, 50, 100, ImagePlane.getDefaultPlane());
        PathObject right = administrativeRoi(ROI_B, 50, 0, 50, 100, ImagePlane.getDefaultPlane());
        touchingHierarchy.addObjects(List.of(left, right));
        PathObject boundaryObject = detection(ROI_C, 45, 45, 10, 10, ImagePlane.getDefaultPlane());
        var boundaryPoint = boundaryObject.getROI().getGeometry().getInteriorPoint();
        assertEquals(50.0, boundaryPoint.getX());
        assertFalse(left.getROI().getGeometry().contains(boundaryPoint));
        assertTrue(left.getROI().getGeometry().covers(boundaryPoint));
        assertEquals(
                new RoiRegistry.Ambiguous(Set.of(ROI_A, ROI_B)),
                RoiRegistry.fromHierarchy(touchingHierarchy).membership(boundaryObject));
    }

    @Test
    void validatedGeometryMembershipMatchesProvenanceSpatialAndPlaneRules() {
        RoiRegistry registry = RoiRegistry.fromHierarchy(overlappingRoiHierarchy());
        PathObject unique = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        PathObject ambiguous = detection(ROI_C, 60, 10, 5, 5, ImagePlane.getDefaultPlane());
        PathObject outside = detection(ROI_C, 200, 10, 5, 5, ImagePlane.getDefaultPlane());
        PathObject otherPlane = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getPlane(1, 0));
        PathObject logical = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        writeProvenance(
                logical, new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_B)));
        PathObject missing = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        writeProvenance(
                missing,
                new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(MISSING_ROI)));
        PathObject slideAttributed = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        writeProvenance(
                slideAttributed,
                new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
        PathObject concave =
                polygonDetection(
                        ROI_C,
                        new double[] {0, 10, 10, 2, 2, 0},
                        new double[] {0, 0, 2, 2, 10, 10});

        for (PathObject object :
                List.of(
                        unique,
                        ambiguous,
                        outside,
                        otherPlane,
                        logical,
                        missing,
                        slideAttributed,
                        concave)) {
            Geometry geometry = object.getROI().getGeometry();
            assertEquals(
                    registry.membership(object),
                    registry.membershipUsingValidatedGeometry(object, geometry));
            Geometry before = geometry.copy();
            registry.membershipUsingValidatedGeometry(object, geometry);
            assertEquals(before, geometry);
        }
        assertEquals(
                new RoiRegistry.Outside(),
                RoiRegistry.fromHierarchy(new PathObjectHierarchy())
                        .membershipUsingValidatedGeometry(outside, outside.getROI().getGeometry()));
    }

    @Test
    void missingPlaneIndexDoesNotComputeAnUnusedInteriorPoint() {
        var registry = RoiRegistry.fromHierarchy(overlappingRoiHierarchy());
        PathObject object = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getPlane(1, 0));
        Geometry geometry = spy(object.getROI().getGeometry());

        assertEquals(
                new RoiRegistry.Outside(),
                registry.membershipUsingValidatedGeometry(object, geometry));
        writeProvenance(
                object, new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
        assertEquals(
                new RoiRegistry.Outside(),
                registry.membershipUsingValidatedGeometry(object, geometry));
        verify(geometry, never()).getInteriorPoint();
    }

    @Test
    void emptySelectionStillRejectsMalformedProvenanceGeometryAndPlane() throws Exception {
        var registry = RoiRegistry.fromHierarchy(new PathObjectHierarchy());
        PathObject object = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        var outline = spy(object.getROI());
        ((PathROIObject) object).setROI(outline);
        var validGeometry = outline.getGeometry();
        doReturn(new WKTReader().read("POLYGON ((0 0,8 8,8 0,0 8,0 0))"))
                .when(outline)
                .getGeometry();
        assertThrows(
                RoiRegistry.CorruptHierarchyException.class, () -> registry.membership(object));

        doReturn(validGeometry).when(outline).getGeometry();
        doReturn(null).when(outline).getImagePlane();
        assertThrows(
                RoiRegistry.CorruptHierarchyException.class, () -> registry.membership(object));

        object.getMetadata()
                .put(
                        qupath.ext.histoforge.contract.HistoforgePersistenceKeys.OBJECT_PROVENANCE,
                        "not-json");
        assertThrows(
                RoiRegistry.MalformedObjectProvenanceException.class,
                () -> registry.membership(object));
    }

    @Test
    void borrowedGeometryIsNotRereadRetainedOrAllowedToHideMalformedProvenance() {
        RoiRegistry registry = RoiRegistry.fromHierarchy(overlappingRoiHierarchy());
        PathObject object = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        var outline = spy(object.getROI());
        ((PathROIObject) object).setROI(outline);
        Geometry geometry = outline.getGeometry();
        clearInvocations(outline);

        assertEquals(
                new RoiRegistry.Member(ROI_A),
                registry.membershipUsingValidatedGeometry(object, geometry));
        verify(outline, never()).getGeometry();
        shiftX(geometry, 1000);
        assertEquals(new RoiRegistry.Member(ROI_A), registry.membership(object));

        object.getMetadata()
                .put(
                        qupath.ext.histoforge.contract.HistoforgePersistenceKeys.OBJECT_PROVENANCE,
                        "not-json");
        assertThrows(
                RoiRegistry.MalformedObjectProvenanceException.class,
                () -> registry.membershipUsingValidatedGeometry(object, outline.getGeometry()));
    }

    @Test
    void malformedRoiOrProvenanceAndDuplicatePersistentIdsFailClosed() {
        PathObjectHierarchy malformedRoiHierarchy = new PathObjectHierarchy();
        PathObject markedDetection = detection(ROI_A, 0, 0, 10, 10, ImagePlane.getDefaultPlane());
        markedDetection.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        malformedRoiHierarchy.addObject(markedDetection);
        assertThrows(
                IllegalArgumentException.class,
                () -> RoiRegistry.fromHierarchy(malformedRoiHierarchy));

        PathObjectHierarchy duplicateHierarchy = new PathObjectHierarchy();
        PathObject first = detection(ROI_A, 0, 0, 10, 10, ImagePlane.getDefaultPlane());
        PathObject second = detection(ROI_A, 20, 0, 10, 10, ImagePlane.getDefaultPlane());
        duplicateHierarchy.addObjects(List.of(first, second));
        assertThrows(
                IllegalArgumentException.class,
                () -> RoiRegistry.fromHierarchy(duplicateHierarchy));

        RoiRegistry registry = RoiRegistry.fromHierarchy(overlappingRoiHierarchy());
        PathObject corrupt = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        corrupt.getMetadata()
                .put(
                        qupath.ext.histoforge.contract.HistoforgePersistenceKeys.OBJECT_PROVENANCE,
                        "not-json");
        assertThrows(IllegalArgumentException.class, () -> registry.membership(corrupt));
    }

    @Test
    void flattenedPopulationPreservesRootEncounterOrderAndUuidValidation() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathObject roi = administrativeRoi(ROI_A, 0, 0, 100, 100, ImagePlane.getDefaultPlane());
        PathObject child = detection(ROI_B, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        PathObject sibling = detection(ROI_C, 200, 10, 5, 5, ImagePlane.getDefaultPlane());
        hierarchy.getRootObject().addChildObject(roi);
        roi.addChildObject(child);
        hierarchy.getRootObject().addChildObject(sibling);
        var objects = hierarchy.getFlattenedObjectList(null);

        assertEquals(new ArrayList<>(hierarchy.getAllObjects(true)), objects);
        assertSame(hierarchy.getRootObject(), objects.getFirst());
        assertEquals(
                RoiRegistry.fromHierarchy(hierarchy).rois(),
                RoiRegistry.fromHierarchyObjects(objects).rois());
        assertThrows(
                RoiRegistry.CorruptHierarchyException.class,
                () -> RoiRegistry.fromHierarchyObjects(objects.subList(1, objects.size())));

        child.setID(hierarchy.getRootObject().getID());
        assertThrows(
                RoiRegistry.CorruptHierarchyException.class,
                () -> RoiRegistry.fromHierarchyObjects(objects));
        assertThrows(
                RoiRegistry.CorruptHierarchyException.class,
                () -> RoiRegistry.fromHierarchy(hierarchy));
    }

    @Test
    void operationReaderReusesExactProvenanceButReadsCurrentMetadataAndPreservesFailures() {
        RoiRegistry registry = RoiRegistry.fromHierarchy(overlappingRoiHierarchy());
        var reader = new ProvenanceCodec.MetadataReader();
        PathObject first = detection(ROI_C, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        PathObject second = detection(ROI_D, 10, 10, 5, 5, ImagePlane.getDefaultPlane());
        var scope = new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_B));
        writeProvenance(first, scope);
        writeProvenance(second, scope);
        var evidence = registry.membershipEvidence(first, reader);
        var repeated =
                registry.membershipEvidenceUsingValidatedGeometry(
                        second, second.getROI().getGeometry(), reader);
        assertEquals(new RoiRegistry.Member(ROI_B), repeated.membership());
        assertSame(evidence.provenance().orElseThrow(), repeated.provenance().orElseThrow());

        writeProvenance(
                second, new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_A)));
        assertEquals(
                new RoiRegistry.Member(ROI_A),
                registry.membershipEvidence(second, reader).membership());
        second.getMetadata().clear();
        assertTrue(registry.membershipEvidence(second, reader).provenance().isEmpty());
        second.getMetadata()
                .put(
                        qupath.ext.histoforge.contract.HistoforgePersistenceKeys.OBJECT_PROVENANCE,
                        null);
        assertThrows(
                RoiRegistry.MalformedObjectProvenanceException.class,
                () -> registry.membershipEvidence(second, reader));
        assertThrows(
                RoiRegistry.MalformedObjectProvenanceException.class,
                () ->
                        registry.membershipEvidenceUsingValidatedGeometry(
                                second, second.getROI().getGeometry(), reader));
    }

    private static PathObjectHierarchy overlappingRoiHierarchy() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(
                List.of(
                        administrativeRoi(ROI_A, 0, 0, 100, 100, ImagePlane.getDefaultPlane()),
                        administrativeRoi(ROI_B, 50, 0, 100, 100, ImagePlane.getDefaultPlane())));
        return hierarchy;
    }

    private static PathObject administrativeRoi(
            String id, double x, double y, double width, double height, ImagePlane plane) {
        PathObject object =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(x, y, width, height, plane));
        object.setID(uuid(id));
        object.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        return object;
    }

    private static PathObject polygonDetection(String id, double[] x, double[] y) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createPolygonROI(x, y, ImagePlane.getDefaultPlane()));
        object.setID(uuid(id));
        return object;
    }

    private static PathObject detection(
            String id, double x, double y, double width, double height, ImagePlane plane) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(x, y, width, height, plane));
        object.setID(uuid(id));
        return object;
    }

    private static void writeProvenance(
            PathObject object, ExecutionScopeCodec.ExecutionScope scope) {
        ProvenanceCodec.writeMetadata(
                object.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(PRODUCER, scope),
                        GENERATION_ID));
    }

    private static void shiftX(Geometry geometry, double offset) {
        geometry.apply(
                new CoordinateSequenceFilter() {
                    @Override
                    public void filter(CoordinateSequence sequence, int index) {
                        sequence.setOrdinate(index, 0, sequence.getX(index) + offset);
                    }

                    @Override
                    public boolean isDone() {
                        return false;
                    }

                    @Override
                    public boolean isGeometryChanged() {
                        return true;
                    }
                });
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
