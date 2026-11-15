package qupath.ext.histoforge.roi;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.objects.*;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class RoiEditingContractTest {
    @TempDir Path directory;

    @Test
    void publicProjectSaveRetainsRoiIdentityAndItsScientificChildren() throws Exception {
        Path source = directory.resolve("source.png");
        assertTrue(
                ImageIO.write(
                        new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB),
                        "PNG",
                        source.toFile()));
        var project =
                qupath.lib.projects.Projects.createProject(
                        Files.createDirectory(directory.resolve("project")).toFile(),
                        BufferedImage.class);
        qupath.lib.projects.ProjectImageEntry<BufferedImage> entry;
        try (var server = qupath.lib.images.servers.ImageServers.buildServer(source.toUri())) {
            entry = project.addImage(server.getBuilder());
        }
        var region = annotation(10, 20, 1024, 1024);
        var manager = new RoiManager();
        manager.applyHistoforgeRoiSettings(region);
        var cell =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(
                                30.25, 40.5, 12.75, 18.25, ImagePlane.getDefaultPlane()),
                        qupath.lib.objects.classes.PathClass.fromString("Synthetic cell"));
        cell.getMeasurementList().put("Area", 232.6875);
        cell.getMetadata().put("synthetic-source", "retained");
        region.addChildObject(cell);
        try (var image = entry.readImageData()) {
            image.getHierarchy().getRootObject().addChildObject(region);
            entry.saveImageData(image);
        }
        project.syncChanges();
        var reopened =
                qupath.lib.projects.ProjectIO.loadProject(
                        Path.of(project.getURI()).toFile(), BufferedImage.class);
        var savedEntry = reopened.getImageList().getFirst();
        assertEquals(entry.getID(), savedEntry.getID());
        try (var image = savedEntry.readImageData()) {
            assertTrue(image.getServer().getURIs().contains(source.toUri()));
            manager.populateFromHierarchy(image.getHierarchy());
            var saved = manager.getRoiById(region.getID().toString()).orElseThrow();
            assertTrue(saved.isLocked());
            assertEquals(region.getName(), saved.getName());
            assertEquals(region.getPathClass(), saved.getPathClass());
            assertEquals(
                    java.util.Map.copyOf(region.getMetadata()),
                    java.util.Map.copyOf(saved.getMetadata()));
            assertEquals(region.getROI().getClass(), saved.getROI().getClass());
            assertTrue(region.getROI().getGeometry().equalsExact(saved.getROI().getGeometry()));
            var savedCell = saved.getChildObjects().iterator().next();
            assertEquals(cell.getID(), savedCell.getID());
            assertEquals(cell.getPathClass(), savedCell.getPathClass());
            assertSame(saved, savedCell.getParent());
            assertEquals(cell.getROI().getClass(), savedCell.getROI().getClass());
            assertEquals(cell.getROI().getImagePlane(), savedCell.getROI().getImagePlane());
            assertTrue(cell.getROI().getGeometry().equalsExact(savedCell.getROI().getGeometry()));
            assertEquals(
                    java.util.Map.copyOf(cell.getMetadata()),
                    java.util.Map.copyOf(savedCell.getMetadata()));
            assertEquals(cell.getMeasurementList().asMap(), savedCell.getMeasurementList().asMap());
        }
    }

    @Test
    void validatesBoundarySizesAndPreservesIdentityGeometryAndNumbering() {
        var manager = new RoiManager();
        var first = annotation(0, 0, 1024, 1024);
        var id = first.getID();
        var roi = first.getROI();
        assertTrue(manager.validateRoi(first).isValid());
        manager.applyHistoforgeRoiSettings(first);
        assertEquals(id, first.getID());
        assertSame(roi, first.getROI());
        assertEquals("ROI 1", first.getName());
        assertTrue(first.isLocked());
        assertEquals(RoiManager.HISTOFORGE_ROI_CLASS, first.getPathClass());
        first.setName("ROI 9");
        var next = annotation(2000, 0, 6000, 6000);
        manager.applyHistoforgeRoiSettings(next);
        assertEquals("ROI 10", next.getName());
        assertTrue(manager.validateRoi(next).isValid());
        assertFalse(manager.validateRoi(annotation(0, 0, 1023, 1024)).isValid());
        assertFalse(manager.validateRoi(annotation(0, 0, 6001, 6000)).isValid());
        assertFalse(
                manager.validateRoi(
                                PathObjects.createAnnotationObject(
                                        ROIs.createEllipseROI(
                                                0, 0, 1024, 1024, ImagePlane.getDefaultPlane())))
                        .isValid());
    }

    @Test
    void touchingEdgesAreAllowedAndAncestorLookupDoesNotReparent() {
        var manager = new RoiManager();
        var roi = annotation(0, 0, 1024, 1024);
        manager.applyHistoforgeRoiSettings(roi);
        assertFalse(
                manager.overlapsExistingRoi(
                        ROIs.createRectangleROI(
                                1024, 0, 1024, 1024, ImagePlane.getDefaultPlane())));
        assertTrue(
                manager.overlapsExistingRoi(
                        ROIs.createRectangleROI(
                                1023, 0, 1024, 1024, ImagePlane.getDefaultPlane())));
        var parent = annotation(1, 1, 10, 10);
        var child =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(2, 2, 3, 3, ImagePlane.getDefaultPlane()));
        roi.addChildObject(parent);
        parent.addChildObject(child);
        assertSame(roi, manager.findAncestorRoi(child).orElseThrow());
        assertSame(parent, child.getParent());
        assertEquals(
                ViewerContextMenuHandler.SelectionKind.SEGMENTATIONS,
                ViewerContextMenuHandler.selectionKind(manager, child, List.of(child)));
        assertEquals(
                ViewerContextMenuHandler.SelectionKind.NONE,
                ViewerContextMenuHandler.selectionKind(
                        manager, child, List.of(child, annotation(0, 0, 1, 1))));
        assertEquals(
                ViewerContextMenuHandler.SelectionKind.ROIS,
                ViewerContextMenuHandler.selectionKind(manager, roi, List.of(roi)));
    }

    @Test
    void deletingRetainsOriginalParentInsteadOfGeometricallyReinserting() {
        var hierarchy = new PathObjectHierarchy();
        var roi = annotation(0, 0, 1024, 1024);
        hierarchy.getRootObject().addChildObject(roi);
        var outer = annotation(0, 0, 100, 100);
        var nested = annotation(0, 0, 50, 50);
        var object = annotation(10, 10, 10, 10);
        var child = annotation(11, 11, 2, 2);
        roi.addChildObject(outer);
        outer.addChildObjects(List.of(nested, object));
        object.addChildObject(child);
        var id = child.getID();
        var geometry = child.getROI();
        var events = new AtomicInteger();
        hierarchy.addListener(
                event -> {
                    if (!event.isChanging()) events.incrementAndGet();
                });
        assertTrue(ViewerContextMenuHandler.stillInHierarchy(hierarchy, List.of(object)));
        ViewerContextMenuHandler.deleteSegmentations(hierarchy, List.of(object), true);
        assertSame(outer, child.getParent());
        assertSame(geometry, child.getROI());
        assertEquals(id, child.getID());
        assertEquals(1, events.get());
        assertFalse(ViewerContextMenuHandler.stillInHierarchy(hierarchy, List.of(object)));
    }

    @Test
    void clickAndDragSizeRulesStayBounded() {
        assertFalse(RoiToolHandler.isIntentionalDrag(true, annotation(10, 10, 3, 3).getROI()));
        assertTrue(RoiToolHandler.isIntentionalDrag(true, annotation(10, 10, 1024, 10).getROI()));
        assertEquals(6000, RoiToolHandler.clampDefaultSize(7000));
        assertEquals(1024, RoiToolHandler.clampDefaultSize(100));
    }

    @Test
    void centeredRectangleStaysInsideImageAtBothEdges() {
        assertEquals(0, RoiToolHandler.centeredCoordinate(100, 1024, 8000));
        assertEquals(3488, RoiToolHandler.centeredCoordinate(4000, 1024, 8000));
        assertEquals(6976, RoiToolHandler.centeredCoordinate(7900, 1024, 8000));
        assertEquals(0, RoiToolHandler.centeredCoordinate(512, 1024, 1024));
    }

    @Test
    void dragCapsEitherDirectionWithoutChangingShorterMovement() {
        assertEquals(8000, RoiToolHandler.cappedCoordinate(2000, 15000));
        assertEquals(1000, RoiToolHandler.cappedCoordinate(7000, 0));
        assertEquals(1800.5, RoiToolHandler.cappedCoordinate(2000, 1800.5));
        assertEquals(2400.25, RoiToolHandler.cappedCoordinate(2000, 2400.25));
        assertEquals(2000, RoiToolHandler.cappedCoordinate(2000, 2000));
    }

    private static PathObject annotation(double x, double y, double w, double h) {
        return PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane()));
    }
}
