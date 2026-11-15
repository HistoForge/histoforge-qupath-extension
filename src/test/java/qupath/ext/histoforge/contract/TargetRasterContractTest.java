package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Client-side proof of the shared target-raster contract.
 *
 * <p>The extension verifies source-view geometry, target-grid geometry, partition coverage, and
 * fixture identity. It deliberately does not reproduce the OpenCV pixel kernel: rasterization has
 * one runtime owner in the backend.
 */
class TargetRasterContractTest {

    @Test
    void sharedFixtureFreezesViewGeometryAndBackendGoldenIdentity() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());
        var selectedDimensions =
                dimensions(fixture.getAsJsonObject("sourceLevel").getAsJsonObject("dimensions"));
        var levelZeroDimensions = dimensions(fixture.getAsJsonObject("levelZeroDimensions"));
        var selectedTransform =
                transform(fixture.getAsJsonObject("selectedLevelPixelsToLevelZero"));
        assertTrue(
                selectedTransform.scaleX()
                        != (double) levelZeroDimensions.width() / selectedDimensions.width());
        assertTrue(
                selectedTransform.scaleY()
                        != (double) levelZeroDimensions.height() / selectedDimensions.height());
        JsonObject digestPreimage = fixture.deepCopy();
        String digest = digestPreimage.remove("rasterizationContractDigest").getAsString();
        assertEquals(digest, CanonicalCodec.canonicalDigest(digestPreimage));
        sourcePixels(fixture);

        for (var element : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            CaseInputs inputs = caseInputs(fixture, testCase);
            assertEquals(
                    inputs.expectedTransform(), inputs.originalTransform(), testCase.toString());
            assertEquals(
                    inputs.originalTransform(), inputs.derivedTransform(), testCase.toString());
            assertTargetGrid(testCase, fixture.getAsJsonObject("sourceCalibration"));
            assertEquals(
                    SourceIdentityCodec.parseIntRect(testCase.get("sourcePixelRect")),
                    SourceViewGeometry.requiredSourcePixelRect(
                            SourceViewGeometry.selectedLevelPixelsToSlide(
                                    transform(fixture.getAsJsonObject("levelZeroPixelsToSlide")),
                                    selectedTransform),
                            selectedDimensions,
                            RasterInputCodec.parseRasterTransform(testCase.get("targetTransform"))),
                    testCase.toString());

            byte[] expected =
                    Base64.getDecoder().decode(testCase.get("expectedRgbBase64").getAsString());
            var raster =
                    RasterInputCodec.parseRasterTransform(testCase.get("targetTransform")).raster();
            assertEquals(raster.width() * raster.height() * 3, expected.length);
            assertEquals(
                    testCase.get("expectedRgbSha256").getAsString(),
                    CanonicalCodec.sha256(expected));
        }
    }

    @Test
    void absoluteOutputRectsExactlyCoverTheTargetRaster() throws IOException {
        JsonObject fixture = fixture();
        for (var element : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            var raster =
                    RasterInputCodec.parseRasterTransform(testCase.get("targetTransform")).raster();
            boolean[] covered = new boolean[Math.multiplyExact(raster.width(), raster.height())];
            for (var partitionElement : testCase.getAsJsonArray("outputPartitions")) {
                var rectangle = SourceIdentityCodec.parseIntRect(partitionElement);
                markPartition(rectangle, covered, raster.width(), raster.height());
            }
            for (boolean pixel : covered) {
                assertTrue(pixel, testCase.get("name").getAsString());
            }
        }
    }

    @Test
    void fixtureCrossesDeclaredTilesAndUsesOpaqueWhite() throws IOException {
        JsonObject fixture = fixture();
        JsonObject sourceLevel = fixture.getAsJsonObject("sourceLevel");
        JsonArray cases = fixture.getAsJsonArray("cases");
        var rectangle =
                SourceIdentityCodec.parseIntRect(
                        cases.get(0).getAsJsonObject().get("sourcePixelRect"));
        int tileWidth = sourceLevel.get("tileWidth").getAsInt();
        int tileHeight = sourceLevel.get("tileHeight").getAsInt();
        assertTrue(
                rectangle.x() / tileWidth != (rectangle.x() + rectangle.width() - 1) / tileWidth);
        assertTrue(
                rectangle.y() / tileHeight
                        != (rectangle.y() + rectangle.height() - 1) / tileHeight);

        byte[] output =
                Base64.getDecoder()
                        .decode(
                                cases.get(1)
                                        .getAsJsonObject()
                                        .get("expectedRgbBase64")
                                        .getAsString());
        assertEquals(255, Byte.toUnsignedInt(output[output.length - 1]));
        assertEquals(255, Byte.toUnsignedInt(output[output.length - 2]));
        assertEquals(255, Byte.toUnsignedInt(output[output.length - 3]));
        boolean hasNonWhiteChannel = false;
        for (byte channel : output) {
            hasNonWhiteChannel |= Byte.toUnsignedInt(channel) != 255;
        }
        assertTrue(hasNonWhiteChannel);
    }

    @Test
    void sourceViewDerivationRejectsRectanglesOutsideTheSelectedLevel() throws IOException {
        JsonObject fixture = fixture();
        JsonObject first = fixture.getAsJsonArray("cases").get(0).getAsJsonObject();
        var rectangle = SourceIdentityCodec.parseIntRect(first.get("sourcePixelRect"));
        var outside =
                new SourceIdentityCodec.IntRect(
                        rectangle.x(),
                        rectangle.y(),
                        fixture.getAsJsonObject("sourceLevel")
                                .getAsJsonObject("dimensions")
                                .get("width")
                                .getAsInt(),
                        rectangle.height());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceViewGeometry.deriveOriginalViewTransform(
                                transform(fixture.getAsJsonObject("levelZeroPixelsToSlide")),
                                transform(
                                        fixture.getAsJsonObject("selectedLevelPixelsToLevelZero")),
                                dimensions(
                                        fixture.getAsJsonObject("sourceLevel")
                                                .getAsJsonObject("dimensions")),
                                outside));
    }

    @Test
    void viewTransformsComposeNonzeroLevelAndSubviewOffsets() {
        var original =
                SourceViewGeometry.deriveOriginalViewTransform(
                        new SourceIdentityCodec.ScaleTranslate2D(0.75, 1.25, -1.5, 2.25),
                        new SourceIdentityCodec.ScaleTranslate2D(2.0, 3.0, 0.25, -0.5),
                        new SourceViewGeometry.Dimensions(12, 10),
                        new SourceIdentityCodec.IntRect(2, 1, 4, 3));
        assertEquals(new SourceIdentityCodec.ScaleTranslate2D(1.5, 3.75, 1.6875, 5.375), original);

        var derived =
                SourceViewGeometry.deriveDerivedViewTransform(
                        new SourceIdentityCodec.ScaleTranslate2D(1.5, 3.75, 1.5, 6.0),
                        new SourceViewGeometry.Dimensions(8, 8),
                        new SourceIdentityCodec.IntRect(1, 2, 4, 3));
        assertEquals(new SourceIdentityCodec.ScaleTranslate2D(1.5, 3.75, 3.0, 13.5), derived);
    }

    @Test
    void viewTransformsPreserveTheBackendBinary64OperationOrder() {
        var levelZero =
                new SourceIdentityCodec.ScaleTranslate2D(
                        0.9402327520733241, 1.0, 9.120685437784989, 0.0);
        var selectedLevel =
                new SourceIdentityCodec.ScaleTranslate2D(
                        0.659858540495406, 1.0, 8.956549741186986, 0.0);
        var dimensions = new SourceViewGeometry.Dimensions(4_000, 1);
        var rectangle = new SourceIdentityCodec.IntRect(2_770, 0, 1, 1);
        var original =
                SourceViewGeometry.deriveOriginalViewTransform(
                        levelZero, selectedLevel, dimensions, rectangle);
        double composeFirst =
                SourceViewGeometry.selectedLevelPixelsToSlide(levelZero, selectedLevel).translateX()
                        + rectangle.x() * original.scaleX();

        assertEquals(1736.1070207301818, original.translateX());
        assertNotEquals(composeFirst, original.translateX());

        double baseTranslate = -731.2715117751975;
        double scale = 24.532112507341665;
        int artifactOffset = 33_086;
        int viewOffset = 33_432;
        var artifact =
                new SourceIdentityCodec.ScaleTranslate2D(
                        scale, 1.0, baseTranslate + artifactOffset * scale, 0.0);
        var derived =
                SourceViewGeometry.deriveDerivedViewTransform(
                        artifact,
                        new SourceViewGeometry.Dimensions(70_000, 1),
                        new SourceIdentityCodec.IntRect(viewOffset, 0, 1, 1));
        double combineOffsetsFirst = baseTranslate + (artifactOffset + viewOffset) * scale;

        assertEquals(1631095.7882515779, derived.translateX());
        assertNotEquals(combineOffsetsFirst, derived.translateX());
    }

    @Test
    void minimalViewUsesExactFloat32NeighborWeightsAndPhysicalBorders() {
        var sourceToSlide = new SourceIdentityCodec.ScaleTranslate2D(1.0, 1.0, 0.0, 0.0);
        var dimensions = new SourceViewGeometry.Dimensions(2, 1);

        assertEquals(
                new SourceIdentityCodec.IntRect(0, 0, 1, 1),
                SourceViewGeometry.requiredSourcePixelRect(
                        sourceToSlide, dimensions, onePixelTarget(0.0)));
        assertEquals(
                new SourceIdentityCodec.IntRect(0, 0, 2, 1),
                SourceViewGeometry.requiredSourcePixelRect(
                        sourceToSlide, dimensions, onePixelTarget(0.01)));
        assertEquals(
                new SourceIdentityCodec.IntRect(0, 0, 1, 1),
                SourceViewGeometry.requiredSourcePixelRect(
                        sourceToSlide, dimensions, onePixelTarget(-0.75)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SourceViewGeometry.requiredSourcePixelRect(
                                sourceToSlide, dimensions, onePixelTarget(3.0)));
    }

    @Test
    void minimalViewUsesTheSameViewLocalFloat32CoordinatesAsTheBackend() {
        var sourceToSlide =
                new SourceIdentityCodec.ScaleTranslate2D(2.0, 1.0, -63.15894033791618, 0.0);
        var dimensions = new SourceViewGeometry.Dimensions(1_000, 1);
        var target =
                new RasterInputCodec.RasterTransform(
                        new RasterInputCodec.RasterDescriptor(
                                2,
                                1,
                                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                                1.0,
                                RasterInputCodec.ResamplingType.BILINEAR,
                                RasterInputCodec.RasterPixelFormat.SRGB_UINT8),
                        new SourceIdentityCodec.ScaleTranslate2D(
                                6.0, 1.0, 1236.8410532038954, 0.0));

        assertEquals(
                new SourceIdentityCodec.IntRect(650, 0, 5, 1),
                SourceViewGeometry.requiredSourcePixelRect(sourceToSlide, dimensions, target));
    }

    private static RasterInputCodec.RasterTransform onePixelTarget(double translateX) {
        return new RasterInputCodec.RasterTransform(
                new RasterInputCodec.RasterDescriptor(
                        1,
                        1,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        1.0,
                        RasterInputCodec.ResamplingType.BILINEAR,
                        RasterInputCodec.RasterPixelFormat.SRGB_UINT8),
                new SourceIdentityCodec.ScaleTranslate2D(1.0, 1.0, translateX, 0.0));
    }

    private static CaseInputs caseInputs(JsonObject fixture, JsonObject testCase) {
        JsonObject sourceLevel = fixture.getAsJsonObject("sourceLevel");
        var selectedDimensions = dimensions(sourceLevel.getAsJsonObject("dimensions"));
        var rectangle = SourceIdentityCodec.parseIntRect(testCase.get("sourcePixelRect"));
        var original =
                SourceViewGeometry.deriveOriginalViewTransform(
                        transform(fixture.getAsJsonObject("levelZeroPixelsToSlide")),
                        transform(fixture.getAsJsonObject("selectedLevelPixelsToLevelZero")),
                        selectedDimensions,
                        rectangle);
        var expected = transform(testCase.getAsJsonObject("expectedViewPixelsToSlide"));
        var derived =
                SourceViewGeometry.deriveDerivedViewTransform(
                        expected,
                        new SourceViewGeometry.Dimensions(rectangle.width(), rectangle.height()),
                        new SourceIdentityCodec.IntRect(
                                0, 0, rectangle.width(), rectangle.height()));
        return new CaseInputs(original, derived, expected);
    }

    private static void markPartition(
            SourceIdentityCodec.IntRect rectangle,
            boolean[] covered,
            int targetWidth,
            int targetHeight) {
        assertTrue(rectangle.x() + rectangle.width() <= targetWidth);
        assertTrue(rectangle.y() + rectangle.height() <= targetHeight);
        for (int y = 0; y < rectangle.height(); y++) {
            for (int x = 0; x < rectangle.width(); x++) {
                int targetPixel = (rectangle.y() + y) * targetWidth + rectangle.x() + x;
                assertTrue(!covered[targetPixel]);
                covered[targetPixel] = true;
            }
        }
    }

    private static void assertTargetGrid(JsonObject testCase, JsonObject calibration) {
        JsonObject frame = testCase.getAsJsonObject("frameBoundsSlide");
        var target = RasterInputCodec.parseRasterTransform(testCase.get("targetTransform"));
        double stepX = target.raster().targetMpp() / calibration.get("mpp_x").getAsDouble();
        double stepY = target.raster().targetMpp() / calibration.get("mpp_y").getAsDouble();
        assertEquals(stepX, target.rasterToSlide().scaleX());
        assertEquals(stepY, target.rasterToSlide().scaleY());
        assertEquals(frame.get("min_x").getAsDouble(), target.rasterToSlide().translateX());
        assertEquals(frame.get("min_y").getAsDouble(), target.rasterToSlide().translateY());
        assertEquals(
                (int)
                        Math.ceil(
                                (frame.get("max_x").getAsDouble()
                                                - frame.get("min_x").getAsDouble())
                                        / stepX),
                target.raster().width());
        assertEquals(
                (int)
                        Math.ceil(
                                (frame.get("max_y").getAsDouble()
                                                - frame.get("min_y").getAsDouble())
                                        / stepY),
                target.raster().height());
    }

    private static byte[] sourcePixels(JsonObject fixture) {
        JsonObject source = fixture.getAsJsonObject("sourceLevel");
        byte[] pixels = Base64.getDecoder().decode(source.get("rgbBase64").getAsString());
        assertEquals(source.get("rgbSha256").getAsString(), CanonicalCodec.sha256(pixels));
        var dimensions = dimensions(source.getAsJsonObject("dimensions"));
        assertEquals(dimensions.width() * dimensions.height() * 3, pixels.length);
        return pixels;
    }

    private static SourceViewGeometry.Dimensions dimensions(JsonObject value) {
        return new SourceViewGeometry.Dimensions(
                value.get("width").getAsInt(), value.get("height").getAsInt());
    }

    private static SourceIdentityCodec.ScaleTranslate2D transform(JsonObject value) {
        return SourceIdentityCodec.parseScaleTranslate2D(value);
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                TargetRasterContractTest.class
                        .getClassLoader()
                        .getResourceAsStream("contracts/target-raster-v1.json")) {
            if (stream == null) {
                throw new IOException("missing target-raster-v1.json");
            }
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private record CaseInputs(
            SourceIdentityCodec.ScaleTranslate2D originalTransform,
            SourceIdentityCodec.ScaleTranslate2D derivedTransform,
            SourceIdentityCodec.ScaleTranslate2D expectedTransform) {}
}
