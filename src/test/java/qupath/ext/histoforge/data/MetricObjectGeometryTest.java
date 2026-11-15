package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.geom.Path2D;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.util.GeometricShapeFactory;
import qupath.ext.histoforge.data.MetricTypes.*;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.spatial.PolygonOutlineFixture;
import qupath.lib.geom.Point2;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryROI;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/** Native-outline authority, defensive fallback and separate editable script objects. */
class MetricObjectGeometryTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    @ParameterizedTest
    @ValueSource(doubles = {0, 120000, 240000})
    void ordinaryNativeShapesKeepTheirOwnPointsAndMeasurements(double offset) {
        var triangle =
                ROIs.createPolygonROI(
                        List.of(
                                new Point2(offset, offset),
                                new Point2(offset + 1.125, offset + 8.25),
                                new Point2(offset + 8, offset + 1)),
                        PLANE);
        for (ROI roi :
                List.of(
                        triangle,
                        ROIs.createRectangleROI(
                                offset + 0.123456789, offset + 1.25, 7.123456789, 9.75, PLANE),
                        ROIs.createEllipseROI(offset, offset, 18, 18, PLANE))) {
            Geometry validated =
                    roi.getClass() == PolygonROI.class
                            ? PolygonOutlineFixture.rawPolygon(roi)
                            : roi.getGeometry();
            MetricObjectGeometry captured;
            try (var conversions = mockStatic(GeometryTools.class)) {
                captured = MetricObjectGeometry.takeValidatedGeometry(validated, PLANE, roi);
                assertSame(roi, captured.scriptRoi(PLANE));
                conversions.verifyNoInteractions();
            }
            assertEquals(roi.getAllPoints(), captured.scriptRoi(PLANE).getAllPoints());
            assertEquals(
                    Double.doubleToRawLongBits(roi.getArea()),
                    Double.doubleToRawLongBits(captured.scriptRoi(PLANE).getArea()));
            assertEquals(validated, captured.copy());
            translateInPlace(captured.copy());
            assertEquals(validated, captured.copy());
        }
    }

    @Test
    void aNativePolygonRemainsAuthoritativeWhenItsJtsShapeIsDifferent() throws Exception {
        ROI roi =
                ROIs.createPolygonROI(
                        List.of(
                                new Point2(0, 0),
                                new Point2(1, .01),
                                new Point2(1, 1),
                                new Point2(.75, .01),
                                new Point2(0, 1)),
                        PLANE);
        Geometry jts = roi.getGeometry();
        assertNotEquals(roi.getArea(), jts.getArea());
        var captured = MetricObjectGeometry.takeValidatedGeometry(jts, PLANE, roi);
        assertSame(roi, captured.scriptRoi(PLANE));
        assertTrue(captured.scriptRoi(PLANE).contains(.076, .001));
        assertEquals(PolygonOutlineFixture.rawPolygon(roi), captured.copy());
        assertEquals(roi.getArea(), captured.copy().getArea(), 1e-12);
    }

    @Test
    void nativePublicAccessorsCannotMutateCapturedCoordinates() {
        PolygonROI original =
                ROIs.createPolygonROI(
                        List.of(new Point2(0, 0), new Point2(0, 8), new Point2(8, 1)), PLANE);
        Geometry expected = PolygonOutlineFixture.rawPolygon(original);
        var captured = MetricObjectGeometry.takeValidatedGeometry(expected, PLANE, original);
        String identity = MetricInputEncoding.outlineDigest(captured, PLANE);
        translateInPlace(captured.copy());
        translateInPlace(original.getGeometry());
        ((Path2D) original.getShape()).reset();
        original.getAllPoints().clear();
        assertSame(original, captured.scriptRoi(PLANE));
        assertEquals(expected, captured.copy());
        assertEquals(identity, MetricInputEncoding.outlineDigest(captured, PLANE));
    }

    @Test
    void planeNormalizationDoesNotChangeTheOriginal() {
        ROI original = ROIs.createEllipseROI(0, 0, 18, 18, ImagePlane.getPlaneWithChannel(2, 0, 0));
        var captured =
                MetricObjectGeometry.takeValidatedGeometry(original.getGeometry(), PLANE, original);
        assertEquals(PLANE, captured.scriptRoi(PLANE).getImagePlane());
        assertEquals(ImagePlane.getPlaneWithChannel(2, 0, 0), original.getImagePlane());
        assertEquals(
                ImagePlane.getPlane(2, 3),
                captured.scriptRoi(ImagePlane.getPlane(2, 3)).getImagePlane());
        assertEquals(PLANE, captured.scriptRoi(PLANE).getImagePlane());
        assertEquals(original.getArea(), captured.scriptRoi(PLANE).getArea());
    }

    @Test
    void largeGeometryRoiCannotRetainItsCreatorsMutableContainmentIndex() {
        var factory = GeometryTools.getDefaultFactory();
        var shapes = new GeometricShapeFactory(factory);
        shapes.setNumPoints(1004);
        shapes.setCentre(new org.locationtech.jts.geom.Coordinate(20, 20));
        shapes.setSize(20);
        Polygon shell = shapes.createCircle();
        Polygon hole = (Polygon) MetricTestData.square(19, 19, 21, 21);
        Polygon supplied =
                factory.createPolygon(
                        shell.getExteriorRing(),
                        new org.locationtech.jts.geom.LinearRing[] {hole.getExteriorRing()});
        ROI original = GeometryTools.geometryToROI(supplied, PLANE);
        assertInstanceOf(GeometryROI.class, original);
        var captured =
                MetricObjectGeometry.takeValidatedGeometry(original.getGeometry(), PLANE, original);
        translateInPlace(supplied);
        assertNotSame(original, captured.scriptRoi(PLANE));
        assertTrue(captured.scriptRoi(PLANE).contains(15, 20));
        assertFalse(captured.scriptRoi(PLANE).contains(115, 20));
        assertFalse(captured.scriptRoi(PLANE).contains(20, 20)); // Hole is preserved.
        translateInPlace(captured.scriptRoi(PLANE).getGeometry());
        assertTrue(captured.scriptRoi(PLANE).contains(15, 20));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON ((0.123456789 0,0 8,8 1,0.123456789 0))",
                "POLYGON ((0 0,0 8,8 8,8 0,0 0),(2 2,6 2,6 6,2 6,2 2))",
                "MULTIPOLYGON (((0 0,0 2,2 2,2 0,0 0)),((4 4,4 6,6 6,6 4,4 4)))"
            })
    void privateFallbackPreservesValidatedGeometryAndOwnsItsCoordinates(String shape)
            throws Exception {
        Geometry source =
                new WKTReader(new GeometryFactory(new PrecisionModel(), 4326)).read(shape);
        Geometry expected = source.copy();
        var captured = capture(source);
        translateInPlace(source);
        translateInPlace(captured.geometrySlide());
        assertEquals(expected, captured.geometrySlide());
        assertEquals(4326, captured.geometrySlide().getSRID());
        assertSame(expected.getFactory(), captured.geometrySlide().getFactory());
        assertNotSame(captured.scriptRoi(PLANE), captured.scriptRoi(PLANE));
    }

    @Test
    void scriptInvocationsHaveFreshObjectsClassesAndParentLinks() throws Exception {
        MetricObjectSnapshot captured = capture(polygon());
        MetricRegionSnapshot region =
                new MetricRegionSnapshot(
                        MetricTestData.target(MetricTestData.ROI_A), List.of(captured));
        var source =
                MetricTestData.script(
                        """
                def object = children[0]
                def before = [object.pathClass.toString(), object.ROI.area,
                              object.parent.ID.toString(), roi.childObjects.size()]
                object.setPathClass(qupath.lib.objects.classes.PathClass.fromString('Changed'))
                object.setROI(object.ROI.translate(100, 100))
                roi.addChildObject(qupath.lib.objects.PathObjects.createDetectionObject(object.ROI))
                return before
                """,
                        MetricTypesTest.scalar("count", AggregationType.SUM));
        MetricScriptRunner runner = new MetricScriptRunner();
        runner.prepare(source);
        Object first = runner.run(source, region, "image", 0.25, 0.25);
        Object second = runner.run(source, region, "image", 0.25, 0.25);

        assertEquals(first, second);
        assertEquals("Cell", captured.pathClass());
        assertEquals(Optional.empty(), captured.parentObjectId());
        assertGeometryUnchanged(polygon(), captured.geometrySlide());
    }

    @Test
    void optionalConversionFailureKeepsPerRegionEvaluationFailure() throws Exception {
        Geometry source = polygon();
        GeometryFactory factory = GeometryTools.getDefaultFactory();
        try (var conversions = mockStatic(GeometryTools.class)) {
            conversions.when(GeometryTools::getDefaultFactory).thenReturn(factory);
            conversions
                    .when(
                            () ->
                                    GeometryTools.geometryToROI(
                                            any(Geometry.class), any(ImagePlane.class)))
                    .thenThrow(new IllegalStateException("conversion failed"));
            MetricObjectSnapshot captured = capture(source);
            assertGeometryUnchanged(source, captured.geometrySlide());
            var script =
                    MetricTestData.script(
                            "return [count: 1]",
                            MetricTypesTest.scalar("count", AggregationType.SUM));
            var preparation =
                    MetricTestData.preparation(
                            MetricTestData.clientBuild(10, 20),
                            script,
                            MetricTestData.revision('1'),
                            new MetricRegionSnapshot(
                                    MetricTestData.target(MetricTestData.ROI_A),
                                    List.of(captured)));
            var evaluation =
                    new MetricsEngine().evaluate(preparation, Map.of(), false, () -> false);

            assertTrue(evaluation.regionResults().isEmpty());
            assertEquals(
                    RunResults.FailureType.METRICS,
                    evaluation.regionFailures().values().iterator().next().type());
            assertEquals(
                    "conversion failed",
                    evaluation.regionFailures().values().iterator().next().message());
        }
    }

    private static MetricObjectSnapshot capture(Geometry geometry) {
        return new MetricObjectSnapshot(
                MetricTestData.objectId(1),
                MetricObjectType.DETECTION,
                "Cell",
                geometry,
                Optional.empty());
    }

    private static Geometry polygon() throws Exception {
        return new WKTReader(GeometryTools.getDefaultFactory())
                .read("POLYGON ((0.25 0.5,1.75 8.25,8.125 1.5,0.25 0.5))")
                .norm();
    }

    private static void assertGeometryUnchanged(Geometry expected, Geometry actual) {
        assertEquals(expected, actual);
        assertEquals(expected.getSRID(), actual.getSRID());
        assertSame(expected.getFactory(), actual.getFactory());
    }

    private static void translateInPlace(Geometry geometry) {
        double before = geometry.getEnvelopeInternal().getMinX();
        geometry.apply(AffineTransformation.translationInstance(100, 0));
        assertEquals(before + 100, geometry.getEnvelopeInternal().getMinX(), 0.0001);
    }
}
