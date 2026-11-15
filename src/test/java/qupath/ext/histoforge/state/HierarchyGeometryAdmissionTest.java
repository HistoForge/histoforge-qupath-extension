package qupath.ext.histoforge.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class HierarchyGeometryAdmissionTest {

    @Test
    void prospectiveTotalCountsSavedAndNewOutlinesOnceWithoutMutatingTheImage() throws Exception {
        var plane = ImagePlane.getDefaultPlane();
        try (var image =
                new ImageData<>(
                        new WrappedBufferedImageServer(
                                "geometry-admission",
                                new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB)))) {
            var firstMask =
                    PathObjects.createAnnotationObject(
                            ROIs.createRectangleROI(0, 0, 32, 32, plane));
            var secondMask =
                    PathObjects.createAnnotationObject(
                            ROIs.createRectangleROI(32, 0, 32, 32, plane));
            var savedNucleus =
                    PathObjects.createDetectionObject(
                            ROIs.createPolygonROI(
                                    new double[] {2, 6, 2}, new double[] {2, 2, 6}, plane));
            firstMask.addChildObject(savedNucleus);
            image.getHierarchy().getRootObject().addChildObjects(List.of(firstMask, secondMask));

            // Both masks already exist. Their 8 points and the saved nucleus's 3 total 11.
            var before = ImageDataRevisionDigester.measureCaptureSize(image);
            assertEquals(4, before.objects());
            assertEquals(11, before.geometryVertices());
            String digest = ImageDataRevisionDigester.imageDataContentDigest(image);
            var pendingCell =
                    PathObjects.createCellObject(
                            ROIs.createRectangleROI(34, 2, 8, 8, plane),
                            ROIs.createPolygonROI(
                                    new double[] {36, 40, 36}, new double[] {4, 4, 8}, plane),
                            null,
                            null);

            // A cell contributes its distinct cell and nucleus outlines: 4 + 3, not 7 cells.
            var failure =
                    assertThrows(
                            LocalCapacityExceededException.class,
                            () ->
                                    ImageDataRevisionDigester.requireProspectiveHierarchyFits(
                                            image,
                                            List.of(),
                                            Map.of(),
                                            List.of(pendingCell),
                                            Optional.empty(),
                                            limits(17)));
            assertEquals("max_hierarchy_geometry_vertices", failure.resource());
            assertEquals(18, failure.observed());
            assertEquals(17, failure.limit());

            ImageDataRevisionDigester.requireProspectiveHierarchyFits(
                    image, List.of(), Map.of(), List.of(pendingCell), Optional.empty(), limits(18));
            assertEquals(before, ImageDataRevisionDigester.measureCaptureSize(image));
            assertEquals(digest, ImageDataRevisionDigester.imageDataContentDigest(image));
        }
    }

    private static ClientExecutionLimits limits(long vertices) {
        return new ClientExecutionLimits(
                100, vertices, 100, 1_000_000, 1_000_000, 100, 100, 1_000_000);
    }
}
