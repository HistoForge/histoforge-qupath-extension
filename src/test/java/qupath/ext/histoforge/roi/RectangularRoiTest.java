package qupath.ext.histoforge.roi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.spatial.PolygonOutlineFixture;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.PolygonROI;

class RectangularRoiTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON ((0 0, 2048 0, 0 2048, 0 0))",
                "POLYGON ((1024 0, 2048 1024, 1024 2048, 0 1024, 1024 0))",
                "POLYGON ((0 0, 2048 0, 2048 1024, 1024 1024, 1024 2048, 0 2048, 0 0))",
                "POLYGON ((0 0, 2048 0, 2048 2048, 0 2048, 0 0),"
                        + " (100 100, 100 200, 200 200, 200 100, 100 100))",
                "MULTIPOLYGON (((0 0, 1024 0, 1024 1024, 0 1024, 0 0)),"
                        + " ((1100 1100, 2048 1100, 2048 2048, 1100 2048, 1100 1100)))"
            })
    void rejectsNonrectangularAdministrativeBoundaries(String wkt) throws Exception {
        PathObject annotation = annotation(wkt);
        assertFalse(new RoiManager().validateRoi(annotation).isValid());
        var hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(annotation);
        var failure =
                assertThrows(
                        RoiRegistry.CorruptHierarchyException.class,
                        () -> RoiRegistry.fromHierarchy(hierarchy));
        assertTrue(failure.getCause().getMessage().contains("axis-aligned rectangle"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON ((0.5 0, 2048 0, 2048 2048, 0.5 2048, 0.5 0))",
                "POLYGON ((0 0, 1024 0, 2048 0, 2048 2048, 0 2048, 0 0))",
                "MULTIPOLYGON (((0 0, 2048 0, 2048 2048, 0 2048, 0 0)))"
            })
    void acceptsRectangularAreaWithoutDependingOnQuPathRoiType(String wkt) throws Exception {
        PathObject annotation = annotation(wkt);
        var originalPoints = annotation.getROI().getAllPoints();
        var publicGeometry = annotation.getROI().getGeometry().copy();
        var originalGeometry =
                annotation.getROI().getClass() == PolygonROI.class
                        ? PolygonOutlineFixture.rawPolygon(annotation.getROI())
                        : publicGeometry;
        assertTrue(new RoiManager().validateRoi(annotation).isValid());
        var hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(annotation);
        var target =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(List.of(annotation.getID().toString()), 3000, 3000)
                        .getFirst();
        // Canonical capture may reverse the ring without changing the region.
        assertTrue(originalGeometry.equalsTopo(target.geometrySlide()));
        assertEquals(
                CanonicalGeometryCodec.canonicalDigest(originalGeometry), target.geometryDigest());
        assertEquals(originalPoints, annotation.getROI().getAllPoints());
        assertTrue(publicGeometry.equalsExact(annotation.getROI().getGeometry()));
    }

    private static PathObject annotation(String wkt) throws Exception {
        var geometry = new WKTReader().read(wkt);
        var annotation =
                PathObjects.createAnnotationObject(
                        GeometryTools.geometryToROI(geometry, ImagePlane.getDefaultPlane()));
        annotation.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        return annotation;
    }
}
