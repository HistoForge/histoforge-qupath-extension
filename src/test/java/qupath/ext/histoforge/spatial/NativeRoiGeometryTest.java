package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.valid.IsValidOp;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.lib.geom.Point2;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

class NativeRoiGeometryTest {
    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    @ParameterizedTest
    @ValueSource(doubles = {0, 50000, 1000000})
    void retainsEveryNativePointAndFullAreaWithoutHostPolygonization(double offset) {
        var roi = spy(PolygonOutlineFixture.dense(offset + 50000, offset + 100000, 40000, 80000));
        var expected = PolygonOutlineFixture.rawPolygon(roi);
        assertTrue(expected.isValid());
        doThrow(new AssertionError("Valid native polygon must not traverse AWT"))
                .when(roi)
                .getGeometry();
        var converted = NativeRoiGeometry.convert(roi);
        assertTrue(expected.equalsExact(converted));
        assertEquals(expected.getNumPoints(), converted.getNumPoints());
        assertEquals(expected.getEnvelopeInternal(), converted.getEnvelopeInternal());
        assertEquals(roi.getArea(), converted.getArea(), roi.getArea() * 1e-12);
        CanonicalGeometryCodec.validatePolygonalGeometry(converted);
        var validated = NativeRoiGeometry.convertValidatedPolygonal(roi);
        assertTrue(expected.equalsExact(validated));
        assertNotSame(converted, validated);
        converted.apply(AffineTransformation.translationInstance(100, 200));
        validated.apply(AffineTransformation.translationInstance(-100, -200));
        assertTrue(expected.equalsExact(NativeRoiGeometry.convert(roi)));
        assertTrue(expected.equalsExact(NativeRoiGeometry.convertValidatedPolygonal(roi)));
        verify(roi, never()).getGeometry();
    }

    @Test
    void keepsFractionalCoordinatesAndConsecutiveVerticesInsteadOfQuantizing() {
        var roi =
                ROIs.createPolygonROI(
                        List.of(
                                new Point2(.125, .007), new Point2(8.125, .007),
                                new Point2(8.125, .007), new Point2(4.125, 8.003)),
                        PLANE);
        var expected = PolygonOutlineFixture.rawPolygon(roi);
        assertTrue(expected.equalsExact(NativeRoiGeometry.convert(roi)));
        assertTrue(expected.equalsExact(NativeRoiGeometry.convertValidatedPolygonal(roi)));
    }

    @Test
    void selfCrossingOutlineKeepsTheExistingHostFilledAreaInterpretation() {
        var roi =
                ROIs.createPolygonROI(
                        List.of(
                                new Point2(0, 0), new Point2(8, 8),
                                new Point2(8, 0), new Point2(0, 8)),
                        PLANE);
        assertFalse(PolygonOutlineFixture.rawPolygon(roi).isValid());
        assertTrue(roi.getGeometry().equalsExact(NativeRoiGeometry.convert(roi)));
        assertEquals(32, NativeRoiGeometry.convert(roi).getArea());
        assertTrue(roi.getGeometry().equalsExact(NativeRoiGeometry.convertValidatedPolygonal(roi)));
        assertTrue(Double.isNaN(roi.getLength()));
    }

    @Test
    void otherNativeTypesKeepTheirPublicConversionIncludingHolesAndParts() throws Exception {
        var reader = new WKTReader();
        for (var roi :
                List.of(
                        ROIs.createRectangleROI(2.125, 3.25, 10, 20, PLANE),
                        ROIs.createEllipseROI(2.125, 3.25, 10, 20, PLANE),
                        ROIs.createLineROI(1, 2, 3, 4, PLANE),
                        GeometryTools.geometryToROI(
                                reader.read(
                                        "POLYGON ((0 0,20 0,20 20,0 20,0 0),(5 5,5 10,10 10,10 5,5 5))"),
                                PLANE),
                        GeometryTools.geometryToROI(
                                reader.read(
                                        "MULTIPOLYGON (((0 0,5 0,5 5,0 5,0 0)),((10 0,15 0,15 5,10 5,10 0)))"),
                                PLANE))) {
            assertTrue(roi.getGeometry().equalsExact(NativeRoiGeometry.convert(roi)));
            if (roi.isArea()) {
                assertTrue(
                        roi.getGeometry()
                                .equalsExact(NativeRoiGeometry.convertValidatedPolygonal(roi)));
            } else {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NativeRoiGeometry.convertValidatedPolygonal(roi));
            }
        }
    }

    @Test
    void strictNativeConversionRunsOneCompleteTopologyCheck() {
        var roi = spy(ROIs.createPolygonROI(new double[] {0, 8, 0}, new double[] {0, 0, 8}, PLANE));
        doThrow(new AssertionError("Valid native polygon must not use host conversion"))
                .when(roi)
                .getGeometry();
        // Other tests exercise real topology. Here, count calls at the shared JTS boundary.
        try (var checks = mockConstruction(IsValidOp.class)) {
            NativeRoiGeometry.convertValidatedPolygonal(roi);
            assertEquals(1, checks.constructed().size());
            verify(checks.constructed().getFirst()).getValidationError();
            verify(checks.constructed().getFirst(), never()).isValid();
        }
    }

    @Test
    void shortDegenerateAndNonfiniteNativeOutlinesUseAndValidateHostConversion() {
        var host = ROIs.createRectangleROI(0, 0, 8, 8, PLANE).getGeometry();
        for (var points :
                List.of(
                        List.of(new Point2(0, 0), new Point2(1, 0)),
                        List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 0)),
                        List.of(new Point2(0, 0), new Point2(1, 0), new Point2(2, 0)),
                        List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, Double.NaN)),
                        List.of(
                                new Point2(0, 0),
                                new Point2(1, 0),
                                new Point2(0, Double.POSITIVE_INFINITY)))) {
            var roi =
                    spy(
                            ROIs.createPolygonROI(
                                    List.of(new Point2(0, 0), new Point2(8, 0), new Point2(0, 8)),
                                    PLANE));
            doReturn(points).when(roi).getAllPoints();
            doReturn(host).when(roi).getGeometry();
            var result = NativeRoiGeometry.convertValidatedPolygonal(roi);
            assertTrue(host.equalsExact(result));
            assertNotSame(host, result);
            verify(roi).getGeometry();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON EMPTY",
                "LINESTRING (0 0, 8 8)",
                "POLYGON ((0 0,8 8,8 0,0 8,0 0))",
                "POLYGON ((0 0,8 0,0 NaN,0 0))",
                "POLYGON Z ((0 0 1,8 0 1,0 8 1,0 0 1))",
                "POLYGON M ((0 0 1,8 0 1,0 8 1,0 0 1))"
            })
    void invalidHostGeometryIsNotAdmitted(String shape) throws Exception {
        Geometry host = new WKTReader().read(shape);
        var other = mock(ROI.class);
        when(other.getGeometry()).thenReturn(host);
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeRoiGeometry.convertValidatedPolygonal(other));

        var nativeOutline =
                spy(
                        ROIs.createPolygonROI(
                                List.of(
                                        new Point2(0, 0),
                                        new Point2(8, 8),
                                        new Point2(8, 0),
                                        new Point2(0, 8)),
                                PLANE));
        doReturn(host).when(nativeOutline).getGeometry();
        assertThrows(
                IllegalArgumentException.class,
                () -> NativeRoiGeometry.convertValidatedPolygonal(nativeOutline));
    }

    @Test
    void strictConversionDoesNotExposeOtherRoiMutableStorage() {
        Geometry host = ROIs.createRectangleROI(.125, .25, 8, 8, PLANE).getGeometry();
        Geometry expected = host.copy();
        ROI roi = mock(ROI.class);
        when(roi.getGeometry()).thenReturn(host);
        var first = NativeRoiGeometry.convertValidatedPolygonal(roi);
        var second = NativeRoiGeometry.convertValidatedPolygonal(roi);
        first.apply(AffineTransformation.translationInstance(100, 200));
        assertTrue(expected.equalsExact(host));
        host.apply(AffineTransformation.translationInstance(300, 400));
        assertTrue(expected.equalsExact(second));
    }

    @Test
    void unexpectedNativeAndHostFailuresPropagateWithoutAlternativeConversion() {
        var roi =
                spy(
                        ROIs.createPolygonROI(
                                List.of(new Point2(0, 0), new Point2(8, 0), new Point2(0, 8)),
                                PLANE));
        var failure = new IllegalArgumentException("native read failed");
        doThrow(failure).when(roi).getAllPoints();
        assertSame(
                failure,
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NativeRoiGeometry.convertValidatedPolygonal(roi)));
        verify(roi, never()).getGeometry();

        ROI other = mock(ROI.class);
        when(other.getGeometry()).thenThrow(failure);
        assertSame(
                failure,
                assertThrows(
                        IllegalArgumentException.class,
                        () -> NativeRoiGeometry.convertValidatedPolygonal(other)));
        verify(other).getGeometry();
    }
}
