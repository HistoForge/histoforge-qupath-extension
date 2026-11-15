package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes.*;
import qupath.lib.geom.Point2;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.ROIs;

/** Independent framing checks and input sensitivity for the v2 binary fingerprint. */
class MetricInputEncodingTest {

    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    @Test
    void rectangleOutlineHasSpecifiedGoldenBytes() throws Exception {
        var bytes = new ByteArrayOutputStream();
        MetricInputEncoding.writeOutline(
                new DataOutputStream(bytes), ROIs.createRectangleROI(0, 0, 2, 4, PLANE));
        // Tag, plane, eight raw binary64 statistics, reported/actual point counts, ordered XY.
        String hex =
                "02 ffffffff 00000000 00000000"
                        + " 4020000000000000 4028000000000000 3ff0000000000000 4000000000000000"
                        + " 0000000000000000 0000000000000000 4000000000000000 4010000000000000"
                        + " 00000004 00000004"
                        + " 0000000000000000 0000000000000000"
                        + " 4000000000000000 0000000000000000"
                        + " 4000000000000000 4010000000000000"
                        + " 0000000000000000 4010000000000000";
        assertArrayEquals(HexFormat.of().parseHex(hex.replace(" ", "")), bytes.toByteArray());
    }

    @Test
    void completeInputFramesMetadataUnicodeUuidTypeAndOptionalParent() throws Exception {
        var parent = snapshot(1, "細胞 😀", Optional.empty());
        var child = snapshot(2, "Cell", Optional.of(parent.objectId()));
        var region =
                new MetricRegionSnapshot(
                        MetricTestData.target(MetricTestData.ROI_A), List.of(child, parent));
        var input = input(List.of(region, MetricTestData.region(MetricTestData.ROI_B)));
        byte[] bytes = encoded(input);
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        byte[] domain = "histoforge-metric-input-v2".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(domain, in.readNBytes(domain.length));
        var metadata = com.google.gson.JsonParser.parseString(readText(in)).getAsJsonObject();
        assertEquals(
                java.util.Set.of("client_build", "calibration", "module", "script"),
                metadata.keySet());
        assertEquals(
                input.script().source(),
                metadata.getAsJsonObject("script").get("source").getAsString());
        assertEquals(2, in.readInt());
        assertEquals(
                ExecutionScopeCodec.toJson(region.target()),
                com.google.gson.JsonParser.parseString(readText(in)));
        assertEquals(2, in.readInt());
        for (var object : List.of(parent, child)) {
            assertEquals(object.objectId().getMostSignificantBits(), in.readLong());
            assertEquals(object.objectId().getLeastSignificantBits(), in.readLong());
            assertEquals(2, in.readUnsignedByte());
            assertEquals(object.pathClass(), readText(in));
            assertEquals(object.parentObjectId().isPresent(), in.readBoolean());
            if (object.parentObjectId().isPresent()) {
                assertEquals(parent.objectId().getMostSignificantBits(), in.readLong());
                assertEquals(parent.objectId().getLeastSignificantBits(), in.readLong());
            }
            assertEquals(1, in.readUnsignedByte()); // Native triangle, not its JTS representation.
            assertEquals(-1, in.readInt());
            assertEquals(0, in.readInt());
            assertEquals(0, in.readInt());
            for (int stat = 0; stat < 8; stat++) {
                assertTrue(Double.isFinite(Double.longBitsToDouble(in.readLong())));
            }
            assertEquals(4, in.readInt()); // Host nVertices includes the closing edge.
            assertEquals(3, in.readInt());
            in.skipNBytes(3 * 2 * 8);
        }
        assertEquals(
                ExecutionScopeCodec.toJson(MetricTestData.target(MetricTestData.ROI_B)),
                com.google.gson.JsonParser.parseString(readText(in)));
        assertEquals(0, in.readInt());
        assertEquals(-1, in.read());
        assertEquals(CanonicalCodec.sha256(bytes), input.metricInputDigest());
        assertEquals(
                input.metricInputDigest(),
                input(
                                List.of(
                                        MetricTestData.region(MetricTestData.ROI_B),
                                        new MetricRegionSnapshot(
                                                region.target(), List.of(parent, child))))
                        .metricInputDigest());
    }

    @Test
    void nativeMissingPerimeterDoesNotPreventCountingAnAdmittedPolygon() throws Exception {
        PolygonROI outline =
                ROIs.createPolygonROI(
                        List.of(
                                new Point2(0, 0), new Point2(8, 8),
                                new Point2(8, 0), new Point2(0, 8)),
                        PLANE);
        assertTrue(outline.getGeometry().isValid());
        assertEquals(32, outline.getGeometry().getArea());
        assertTrue(Double.isNaN(outline.getLength()));
        var input = inputWith(outline);
        assertEquals(input.metricInputDigest(), CanonicalCodec.sha256(encoded(input)));
        var runner = new MetricScriptRunner();
        runner.prepare(input.script());
        assertEquals(
                java.util.Map.of("count", 1),
                runner.run(input.script(), input.regions().getFirst(), "image", .25, .25));
        var bytes = new ByteArrayOutputStream();
        MetricInputEncoding.writeOutline(new DataOutputStream(bytes), outline);
        var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        in.skipNBytes(1 + 3 * 4 + 8);
        assertEquals(Double.doubleToRawLongBits(outline.getLength()), in.readLong());
    }

    @Test
    void nativeHashingDoesNotAskForJtsOrReconstructAnOutline() {
        PolygonROI outline = spy(triangle(0.0));
        var object = nativeSnapshot(outline);
        clearInvocations(outline);
        doThrow(new AssertionError("JTS conversion during native hash"))
                .when(outline)
                .getGeometry();
        var input =
                input(
                        List.of(
                                new MetricRegionSnapshot(
                                        MetricTestData.target(MetricTestData.ROI_A),
                                        List.of(object))));
        assertEquals(input.metricInputDigest(), CanonicalCodec.sha256(encoded(input)));
        verify(outline, never()).getGeometry();
    }

    @Test
    void buffersBytesWithoutCollectingThePopulation() {
        var objects = new ArrayList<MetricObjectSnapshot>();
        for (int i = 1; i <= 1000; i++) objects.add(snapshot(i, "Cell", Optional.empty()));
        var input =
                input(
                        List.of(
                                new MetricRegionSnapshot(
                                        MetricTestData.target(MetricTestData.ROI_A), objects)));
        var largest = new AtomicInteger();
        var total = new AtomicInteger();
        OutputStream output =
                new OutputStream() {
                    public void write(int value) {
                        fail("unbuffered output");
                    }

                    public void write(byte[] bytes, int start, int length) {
                        largest.accumulateAndGet(length, Math::max);
                        total.addAndGet(length);
                    }
                };
        MetricInputEncoding.write(
                input.clientBuild(),
                input.calibration(),
                input.module(),
                input.script(),
                input.regions(),
                output);
        assertTrue(total.get() > 100_000);
        assertTrue(largest.get() <= 8192);
    }

    @Test
    void nativeOrderSignedZeroPlaneAndCachedStatisticsAffectIdentity() {
        PolygonROI base = triangle(0.0);
        String expected = inputWith(base).metricInputDigest();
        assertNotEquals(expected, inputWith(triangle(-0.0)).metricInputDigest());
        var reverse = new ArrayList<>(base.getAllPoints());
        java.util.Collections.reverse(reverse);
        assertNotEquals(
                expected, inputWith(ROIs.createPolygonROI(reverse, PLANE)).metricInputDigest());
        for (int statistic = 0; statistic < 9; statistic++) {
            PolygonROI changed = spy(triangle(0.0));
            switch (statistic) {
                case 0 -> doReturn(changed.nVertices() + 1).when(changed).nVertices();
                case 1 -> doReturn(Math.nextUp(changed.getArea())).when(changed).getArea();
                case 2 -> doReturn(Math.nextUp(changed.getLength())).when(changed).getLength();
                case 3 ->
                        doReturn(Math.nextUp(changed.getCentroidX())).when(changed).getCentroidX();
                case 4 ->
                        doReturn(Math.nextUp(changed.getCentroidY())).when(changed).getCentroidY();
                case 5 -> doReturn(Math.nextUp(changed.getBoundsX())).when(changed).getBoundsX();
                case 6 -> doReturn(Math.nextUp(changed.getBoundsY())).when(changed).getBoundsY();
                case 7 ->
                        doReturn(Math.nextUp(changed.getBoundsWidth()))
                                .when(changed)
                                .getBoundsWidth();
                case 8 ->
                        doReturn(Math.nextUp(changed.getBoundsHeight()))
                                .when(changed)
                                .getBoundsHeight();
                default -> throw new AssertionError();
            }
            assertNotEquals(expected, inputWith(changed).metricInputDigest());
            assertNotEquals(nativeSnapshot(base), nativeSnapshot(changed));
        }
        assertNotEquals(
                MetricInputEncoding.outlineDigest(
                        MetricObjectGeometry.takeValidatedGeometry(base.getGeometry(), PLANE, base),
                        PLANE),
                MetricInputEncoding.outlineDigest(
                        MetricObjectGeometry.takeValidatedGeometry(base.getGeometry(), PLANE, base),
                        ImagePlane.getPlane(2, 3)));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POLYGON ((1 1,1 8,8 2,1 1))",
                "POLYGON ((1 1,1 9,9 9,9 1,1 1),(3 3,7 3,7 7,3 7,3 3))",
                "MULTIPOLYGON (((1 1,1 3,3 3,3 1,1 1)),((5 5,5 8,8 8,8 5,5 5)))"
            })
    void privatePolygonalGeometryPreservesStructureAndResistsMutation(String text)
            throws Exception {
        Geometry geometry = new WKTReader().read(text);
        var object =
                new MetricObjectSnapshot(
                        MetricTestData.objectId(1),
                        MetricObjectType.DETECTION,
                        "Cell",
                        geometry,
                        Optional.empty());
        var region =
                new MetricRegionSnapshot(
                        MetricTestData.target(MetricTestData.ROI_A), List.of(object));
        var input = input(List.of(region));
        byte[] expected = encoded(input);
        for (Geometry exposed :
                List.of(geometry, object.geometrySlide(), object.scriptRoi(PLANE).getGeometry())) {
            double before = exposed.getEnvelopeInternal().getMinX();
            exposed.apply(AffineTransformation.translationInstance(100, 0));
            assertEquals(before + 100, exposed.getEnvelopeInternal().getMinX(), 0.0001);
            assertArrayEquals(expected, encoded(input(List.of(region))));
        }
        var different =
                new MetricObjectSnapshot(
                        MetricTestData.objectId(1),
                        MetricObjectType.DETECTION,
                        "Cell",
                        geometry,
                        Optional.empty());
        assertNotEquals(
                input.metricInputDigest(),
                input(List.of(new MetricRegionSnapshot(region.target(), List.of(different))))
                        .metricInputDigest());
    }

    @Test
    void fallbackFramesComponentsHolesAndPolygonTypeIndependently() throws Exception {
        Polygon polygon =
                (Polygon) new WKTReader().read("POLYGON ((0 0,8 0,0 8,0 0),(1 1,1 2,2 1,1 1))");
        var multi = polygon.getFactory().createMultiPolygon(new Polygon[] {polygon});
        byte[][] encodings = new byte[2][];
        int position = 0;
        for (Geometry geometry : List.of(polygon, multi)) {
            var bytes = new ByteArrayOutputStream();
            MetricInputEncoding.writeGeometry(
                    new DataOutputStream(bytes), geometry, ImagePlane.getPlane(2, 3));
            encodings[position++] = bytes.toByteArray();
            var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(0, in.readUnsignedByte());
            assertEquals(-1, in.readInt());
            assertEquals(2, in.readInt());
            assertEquals(3, in.readInt());
            assertEquals(geometry instanceof Polygon ? 1 : 2, in.readUnsignedByte());
            assertEquals(1, in.readInt());
            assertEquals(2, in.readInt());
            for (double[][] ring :
                    List.of(
                            new double[][] {{0, 0}, {8, 0}, {0, 8}, {0, 0}},
                            new double[][] {{1, 1}, {1, 2}, {2, 1}, {1, 1}})) {
                assertEquals(4, in.readInt());
                for (double[] point : ring) {
                    assertEquals(Double.doubleToRawLongBits(point[0]), in.readLong());
                    assertEquals(Double.doubleToRawLongBits(point[1]), in.readLong());
                }
            }
            assertEquals(-1, in.read());
        }
        assertFalse(java.util.Arrays.equals(encodings[0], encodings[1]));
    }

    static byte[] encoded(MetricInputDraft input) {
        var bytes = new ByteArrayOutputStream();
        MetricInputEncoding.write(
                input.clientBuild(),
                input.calibration(),
                input.module(),
                input.script(),
                input.regions(),
                bytes);
        return bytes.toByteArray();
    }

    private static String readText(DataInputStream in) throws Exception {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }

    private static PolygonROI triangle(double left) {
        return ROIs.createPolygonROI(
                List.of(new Point2(left, 0), new Point2(0, 8), new Point2(8, 1)), PLANE);
    }

    private static MetricObjectSnapshot nativeSnapshot(PolygonROI roi) {
        return MetricObjectSnapshot.fromCapturedGeometry(
                MetricTestData.objectId(1),
                MetricObjectType.DETECTION,
                "Cell",
                MetricObjectGeometry.takeValidatedGeometry(roi.getGeometry(), PLANE, roi),
                Optional.empty());
    }

    private static MetricObjectSnapshot snapshot(
            int id, String pathClass, Optional<java.util.UUID> parent) {
        PolygonROI roi = triangle(0.0);
        return MetricObjectSnapshot.fromCapturedGeometry(
                MetricTestData.objectId(id),
                MetricObjectType.DETECTION,
                pathClass,
                MetricObjectGeometry.takeValidatedGeometry(roi.getGeometry(), PLANE, roi),
                parent);
    }

    private static MetricInputDraft inputWith(PolygonROI roi) {
        return input(
                List.of(
                        new MetricRegionSnapshot(
                                MetricTestData.target(MetricTestData.ROI_A),
                                List.of(nativeSnapshot(roi)))));
    }

    private static MetricInputDraft input(List<MetricRegionSnapshot> regions) {
        return MetricInputDraft.build(
                MetricTestData.clientBuild(10, 20),
                new SourceIdentityCodec.PixelCalibration(0.25, 0.25),
                MetricTestData.MODULE,
                MetricTestData.script(
                        "return [count: 1]", MetricTypesTest.scalar("count", AggregationType.SUM)),
                regions);
    }
}
