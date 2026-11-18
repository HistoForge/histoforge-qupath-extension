package qupath.ext.histoforge.roi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.large.HierarchyTransactionDigester;
import qupath.ext.histoforge.spatial.TargetHierarchyPopulation;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;

class AnnotationRoiSelectionTest {
    @Test
    void exactClassIncludesLockedAndUnlockedButExcludesOutputsMarkersAndDetections()
            throws Exception {
        var hierarchy = new PathObjectHierarchy();
        var unlocked = rectangle(0, "Tissue");
        var locked = rectangle(20, "Tissue");
        locked.setLocked(true);
        var other = rectangle(40, "Tissue: Subclass");
        var marked = rectangle(60, "Tissue");
        marked.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        var output = rectangle(80, "Tissue");
        output.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "malformed output");
        var detection =
                PathObjects.createDetectionObject(unlocked.getROI(), unlocked.getPathClass());
        var empty =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(0, 0, 0, 0, ImagePlane.getDefaultPlane()),
                        unlocked.getPathClass());
        hierarchy.addObjects(List.of(unlocked, locked, other, marked, output, detection, empty));

        assertEquals(
                Set.of(unlocked.getID().toString(), locked.getID().toString()),
                AnnotationRoiSelection.ids(hierarchy, "Tissue"));
        assertFalse(unlocked.isLocked());
        assertTrue(locked.isLocked());
        assertThrows(
                IllegalArgumentException.class,
                () -> AnnotationRoiSelection.regions(hierarchy, "Absent"));
    }

    @Test
    void holesMultipartAndOverlappingBoundingBoxesRemainIndependentTargets() throws Exception {
        var hierarchy = new PathObjectHierarchy();
        var ring = polygon("POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (2 2, 2 8, 8 8, 8 2, 2 2))");
        var island = polygon("POLYGON ((3 3, 7 3, 7 7, 3 7, 3 3))");
        var multipart =
                polygon(
                        "MULTIPOLYGON (((20 0, 25 0, 25 5, 20 5, 20 0)), ((30 0, 35 0, 35 5, 30 5, 30 0)))");
        hierarchy.addObjects(List.of(ring, island, multipart));
        var targets =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(AnnotationRoiSelection.ids(hierarchy, "Tissue"), 100, 100);
        assertEquals(3, targets.size());
        assertEquals(64, target(targets, ring).geometrySlide().getArea());
        assertEquals(2, target(targets, multipart).geometrySlide().getNumGeometries());
        assertTrue(
                target(targets, ring)
                        .geometrySlide()
                        .getEnvelope()
                        .intersects(target(targets, island).geometrySlide().getEnvelope()));
        assertEquals(
                0,
                target(targets, ring)
                        .geometrySlide()
                        .intersection(target(targets, island).geometrySlide())
                        .getArea());
    }

    @Test
    void trueOverlapRejectedButTouchingOutlinesAllowed() {
        var hierarchy = new PathObjectHierarchy();
        var first = rectangle(0, "Tissue");
        var touching = rectangle(10, "Tissue");
        hierarchy.addObjects(List.of(first, touching));
        assertEquals(
                2,
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(AnnotationRoiSelection.ids(hierarchy, "Tissue"), 100, 100)
                        .size());
        hierarchy.addObject(rectangle(5, "Tissue"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RoiRegistry.fromHierarchy(hierarchy)
                                .captureTargets(
                                        AnnotationRoiSelection.ids(hierarchy, "Tissue"), 100, 100));
    }

    @Test
    void fullSelectionExcludesBoundariesAndDoesNotMixMarkedRoisIntoMembership() {
        var hierarchy = new PathObjectHierarchy();
        var left = rectangle(0, "Tissue");
        var right = rectangle(10, "Tissue");
        var marked = rectangle(0, "Marker");
        marked.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        var inside =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(2, 2, 2, 2, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Cell"));
        var seam =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(9, 2, 2, 2, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Cell"));
        hierarchy.addObjects(List.of(left, right, marked, inside, seam));
        var selection =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(AnnotationRoiSelection.ids(hierarchy, "Tissue"), 100, 100);
        var target = target(selection, left);
        assertEquals(
                List.of(inside), TargetHierarchyPopulation.select(target, hierarchy, selection));
        var baseline =
                HierarchyTransactionDigester.captureBaseline(
                        target, Set.of(), false, hierarchy, selection);
        ((qupath.lib.objects.PathROIObject) right)
                .setROI(ROIs.createRectangleROI(10, 0, 0, 10, ImagePlane.getDefaultPlane()));
        assertEquals(
                baseline,
                HierarchyTransactionDigester.captureForRebase(
                        target, baseline, false, hierarchy, selection));
        hierarchy.removeObject(right, true);
        assertEquals(
                baseline,
                HierarchyTransactionDigester.captureForRebase(
                        target, baseline, false, hierarchy, selection));
        left.setLocked(true);
        assertNotEquals(
                baseline,
                HierarchyTransactionDigester.captureForRebase(
                        target, baseline, false, hierarchy, selection));
        hierarchy.removeObject(left, true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HierarchyTransactionDigester.captureForRebase(
                                target, baseline, false, hierarchy, selection));
    }

    private static ExecutionScopeCodec.TargetRegion target(
            List<ExecutionScopeCodec.TargetRegion> targets, PathObject object) {
        return targets.stream()
                .filter(
                        target ->
                                target.key()
                                        .equals(
                                                new ExecutionScopeCodec.Roi(
                                                        object.getID().toString())))
                .findFirst()
                .orElseThrow();
    }

    private static PathObject rectangle(double x, String pathClass) {
        return PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(x, 0, 10, 10, ImagePlane.getDefaultPlane()),
                PathClass.fromString(pathClass));
    }

    private static PathObject polygon(String text) throws Exception {
        return PathObjects.createAnnotationObject(
                GeometryTools.geometryToROI(
                        new WKTReader().read(text), ImagePlane.getDefaultPlane()),
                PathClass.fromString("Tissue"));
    }
}
