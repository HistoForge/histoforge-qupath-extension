package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXYM;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec.ImagePlaneIndex;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.AreaROI;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

class TargetHierarchyPopulationTest {

    private static final GeometryFactory FACTORY = new GeometryFactory();
    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    @Test
    void validOutlinesMatchCanonicalAcceptanceWithoutChangingCoordinates() throws Exception {
        for (String text :
                List.of(
                        "POLYGON ((0 0, 0 0, 20 0, 0 20, 0 0, 0 0))",
                        "POLYGON ((0 0, 20 0, 20 20, 0 20, 0 0), (2 2, 2 8, 8 8, 8 2, 2 2))",
                        "MULTIPOLYGON (((0 0, 10 0, 0 10, 0 0)), ((20 0, 30 0, 20 10, 20 0)))")) {
            Geometry geometry = new WKTReader().read(text);
            long[] before = ordinateBits(geometry);
            CanonicalGeometryCodec.canonicalCoordinates(geometry);
            PathObject object = rawDetection(geometry);
            var hierarchy = hierarchy(object);

            assertEquals(List.of(object), TargetHierarchyPopulation.select(slide(), hierarchy));
            assertArrayEquals(before, ordinateBits(geometry));
            assertSame(geometry, object.getROI().getGeometry());
        }
    }

    @Test
    void malformedRawOutlinesStillFailBeforeMembershipAndRemainUnchanged() throws Exception {
        Polygon valid = (Polygon) new WKTReader().read("POLYGON ((0 0, 20 0, 0 20, 0 0))");
        List<Geometry> invalid =
                List.of(
                        FACTORY.createPolygon(),
                        new WKTReader().read("POLYGON ((0 0, 0 0, 0 0, 0 0))"),
                        new WKTReader().read("POLYGON ((0 0, 1 0, 1 0, 0 0))"),
                        new WKTReader().read("POLYGON ((0 0, 1 0, 0 0, 1 0, 0 0))"),
                        new WKTReader().read("POLYGON ((0 0, 1 0, 2 0, 0 0))"),
                        new WKTReader().read("POLYGON ((0 0, 20 20, 0 20, 20 0, 0 0))"),
                        FACTORY.createMultiPolygon(new Polygon[] {valid, FACTORY.createPolygon()}),
                        FACTORY.createPolygon(
                                valid.getExteriorRing(),
                                new LinearRing[] {FACTORY.createLinearRing()}),
                        FACTORY.createPolygon(
                                new Coordinate[] {
                                    new Coordinate(0, 0), new Coordinate(20, 0),
                                    new Coordinate(0, Double.NaN), new Coordinate(0, 0)
                                }),
                        FACTORY.createPolygon(
                                new Coordinate[] {
                                    new Coordinate(0, 0, 1), new Coordinate(20, 0, 1),
                                    new Coordinate(0, 20, 1), new Coordinate(0, 0, 1)
                                }),
                        FACTORY.createPolygon(
                                new Coordinate[] {
                                    new CoordinateXYM(0, 0, 1), new CoordinateXYM(20, 0, 1),
                                    new CoordinateXYM(0, 20, 1), new CoordinateXYM(0, 0, 1)
                                }));
        for (Geometry geometry : invalid) {
            long[] before = ordinateBits(geometry);
            PathObject object = rawDetection(geometry);
            // The object would fail the later plane filter; geometry must still be checked first.
            when(object.getROI().getZ()).thenReturn(1);
            var hierarchy = hierarchy(object);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> CanonicalGeometryCodec.canonicalCoordinates(geometry));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TargetHierarchyPopulation.select(slide(), hierarchy));
            assertArrayEquals(before, ordinateBits(geometry));
        }
    }

    @Test
    void legacyAreaCacheRemainsTheSameUnmodifiedGeometry() throws Exception {
        PolygonROI polygon =
                (PolygonROI)
                        ROIs.createPolygonROI(
                                new double[] {10, 30, 25}, new double[] {10, 10, 30}, PLANE);
        var vertices = PolygonROI.class.getDeclaredField("vertices");
        vertices.setAccessible(true);
        var constructor = AreaROI.class.getDeclaredConstructor(List.class, ImagePlane.class);
        constructor.setAccessible(true);
        ROI roi = constructor.newInstance(List.of(vertices.get(polygon)), PLANE);
        Geometry cached = roi.getGeometry();
        long[] before = ordinateBits(cached);
        var object = PathObjects.createDetectionObject(roi);

        assertEquals(List.of(object), TargetHierarchyPopulation.select(slide(), hierarchy(object)));
        assertSame(cached, roi.getGeometry());
        assertArrayEquals(before, ordinateBits(cached));
    }

    @Test
    void singlePointsAreIncludedWhileLinesAndGeometryCollectionsRemainExcluded() {
        var point = PathObjects.createDetectionObject(ROIs.createPointsROI(5, 5, PLANE));
        var line = PathObjects.createDetectionObject(ROIs.createLineROI(1, 1, 9, 9, PLANE));
        var collection = rawDetection(FACTORY.createGeometryCollection());

        assertEquals(
                List.of(point),
                TargetHierarchyPopulation.select(slide(), hierarchy(point, line, collection)));
    }

    @Test
    void orderingBoundariesPlanesAndProvenanceMembershipStayUnchanged() {
        var boundary =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 100, 100, PLANE));
        boundary.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        var target = target(new ExecutionScopeCodec.Roi(boundary.getID().toString()));
        var attributedOutside = detection(200, PLANE);
        attributedOutside.setID(UUID.fromString("10000000-0000-4000-8000-000000000001"));
        ProvenanceCodec.writeMetadata(attributedOutside.getMetadata(), provenance(target.key()));
        var spatialInside = detection(10, PLANE);
        spatialInside.setID(UUID.fromString("20000000-0000-4000-8000-000000000001"));
        var spatialOutside = detection(200, PLANE);
        var otherPlane = detection(10, ImagePlane.getPlane(1, 0));
        var otherOwner = detection(10, PLANE);
        ProvenanceCodec.writeMetadata(
                otherOwner.getMetadata(), provenance(new ExecutionScopeCodec.Slide()));

        assertEquals(
                List.of(attributedOutside, spatialInside),
                TargetHierarchyPopulation.select(
                        target,
                        hierarchy(
                                boundary,
                                otherOwner,
                                spatialInside,
                                spatialOutside,
                                otherPlane,
                                attributedOutside)));
    }

    private static long[] ordinateBits(Geometry geometry) {
        // Some JTS sequences return shared coordinates; snapshot values, not references.
        Coordinate[] coordinates = geometry.getCoordinates();
        long[] result = new long[coordinates.length * 4];
        for (int index = 0; index < coordinates.length; index++) {
            Coordinate coordinate = coordinates[index];
            result[index * 4] = Double.doubleToRawLongBits(coordinate.x);
            result[index * 4 + 1] = Double.doubleToRawLongBits(coordinate.y);
            result[index * 4 + 2] = Double.doubleToRawLongBits(coordinate.getZ());
            result[index * 4 + 3] = Double.doubleToRawLongBits(coordinate.getM());
        }
        return result;
    }

    private static PathObject rawDetection(Geometry geometry) {
        ROI roi = mock(ROI.class);
        when(roi.isArea()).thenReturn(true);
        when(roi.getGeometry()).thenReturn(geometry);
        return PathObjects.createDetectionObject(roi);
    }

    private static PathObject detection(double x, ImagePlane plane) {
        return PathObjects.createDetectionObject(ROIs.createRectangleROI(x, 10, 10, 10, plane));
    }

    private static PathObjectHierarchy hierarchy(PathObject... objects) {
        var hierarchy = new PathObjectHierarchy();
        // Avoid spatial queries while assembling deliberately malformed test outlines.
        hierarchy.getRootObject().addChildObjects(List.of(objects));
        return hierarchy;
    }

    private static ExecutionScopeCodec.TargetRegion slide() {
        return target(new ExecutionScopeCodec.Slide());
    }

    private static ExecutionScopeCodec.TargetRegion target(ExecutionScopeCodec.RegionKey key) {
        Geometry geometry = ROIs.createRectangleROI(0, 0, 100, 100, PLANE).getGeometry();
        return new ExecutionScopeCodec.TargetRegion(
                key,
                geometry,
                new ImagePlaneIndex(0, 0),
                CanonicalGeometryCodec.canonicalDigest(geometry));
    }

    private static ProvenanceCodec.ObjectProvenance provenance(ExecutionScopeCodec.RegionKey key) {
        return new ProvenanceCodec.ObjectProvenance(
                ProvenanceCodec.SCHEMA_VERSION,
                new ExecutionScopeCodec.OutputKey(
                        new InferenceIdentityCodec.ModelStepIdentity(
                                new ModuleRef("histoforge", "fixture"), "cells"),
                        new ExecutionScopeCodec.FieldScope(key)),
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    }
}
