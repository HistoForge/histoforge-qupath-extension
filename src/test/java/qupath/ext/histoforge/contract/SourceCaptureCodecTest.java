package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Canonical fixture shared with the backend; Java does not duplicate pixel resampling. */
class SourceCaptureCodecTest {

    @Test
    void sharedFixtureFreezesCaptureViewBindingAndWriterOrigin() throws IOException {
        JsonObject fixture = fixture();
        var capture = SourceCaptureCodec.parseCapture(fixture.get("capture"));
        var view = SourceCaptureCodec.parseView(fixture.get("view"));
        var binding = SourceCaptureCodec.parseBinding(fixture.get("binding"), capture);
        assertEquals(fixture.get("capture"), SourceCaptureCodec.toJson(capture));
        assertEquals(fixture.get("view"), SourceCaptureCodec.toJson(view));
        assertEquals(fixture.get("binding"), SourceCaptureCodec.toJson(binding));
        assertEquals(
                fixture.get("captureCanonical").getAsString(),
                new String(
                        CanonicalCodec.canonicalBytes(SourceCaptureCodec.toJson(capture)),
                        StandardCharsets.UTF_8));
        assertEquals(
                fixture.get("viewCanonical").getAsString(),
                new String(
                        CanonicalCodec.canonicalBytes(SourceCaptureCodec.toJson(view)),
                        StandardCharsets.UTF_8));
        assertEquals(
                fixture.get("bindingSha256").getAsString(),
                CanonicalCodec.canonicalDigest(SourceCaptureCodec.toJson(binding)));
        var geometry = SourceCaptureCodec.viewGeometry(view);
        assertEquals(
                SourceIdentityCodec.parseIntRect(fixture.get("sourceRectAtLevel")),
                geometry.sourceRectAtLevel());
        assertEquals(
                SourceIdentityCodec.parseScaleTranslate2D(fixture.get("viewPixelsToSlide")),
                geometry.viewPixelsToSlide());
        assertEquals(
                SourceArtifactCodec.parseOrigin(fixture.get("imageOrigin")),
                SourceCaptureCodec.imageOrigin(capture, binding.sourceRectAtLevel()));
    }

    @Test
    void viewTransformRetainsTheCompleteCaptureAnchor() throws IOException {
        var original = SourceCaptureCodec.parseCapture(fixture().get("capture"));
        var capture =
                new SourceCaptureCodec.Capture(
                        original.source(),
                        original.plane(),
                        original.sourceLevel(),
                        new SourceIdentityCodec.ScaleTranslate2D(
                                24.532112507341665, 1, -731.2715117751975, 0),
                        original.levelZeroPixelsToSlide(),
                        original.sourceRectAtLevel(),
                        original.writerBuild());
        var view =
                new SourceCaptureCodec.View(
                        capture, new SourceIdentityCodec.IntRect(33432, 20, 30, 40));
        var actual = SourceCaptureCodec.viewGeometry(view).viewPixelsToSlide();
        double directlyCombined = -731.2715117751975 + (33086 + 33432) * 24.532112507341665;
        assertEquals(1631095.7882515779, actual.translateX());
        assertNotEquals(directlyCombined, actual.translateX());
    }

    @Test
    void sourceFootprintKeepsIntegerOffsetsBeyondFloat32Precision() throws IOException {
        // Match test_large_source_offset_does_not_round_away_the_final_pixels in Python.
        // Sampling is view-local float32; adding the integer source origin must not round again.
        int extent = 33_554_435;
        int origin = extent - 8;
        var fixture = fixture();
        var original = SourceCaptureCodec.parseCapture(fixture.get("capture"));
        var sourceJson = fixture.getAsJsonObject("capture").getAsJsonObject("source").deepCopy();
        sourceJson.addProperty("width", extent);
        sourceJson.addProperty("height", 1);
        var identity = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        var rectangle = new SourceIdentityCodec.IntRect(origin, 0, 8, 1);
        var capture =
                new SourceCaptureCodec.Capture(
                        SourceIdentityCodec.parseImageSourceIdentity(sourceJson),
                        original.plane(),
                        new SourceArtifactCodec.SourceLevelRequirement(0, 0, extent, 1),
                        identity,
                        identity,
                        rectangle,
                        original.writerBuild());
        var view =
                new SourceCaptureCodec.View(capture, new SourceIdentityCodec.IntRect(0, 0, 8, 1));
        var target =
                new RasterInputCodec.RasterTransform(
                        new RasterInputCodec.RasterDescriptor(
                                8,
                                1,
                                original.plane(),
                                0.25,
                                RasterInputCodec.ResamplingType.BILINEAR,
                                RasterInputCodec.RasterPixelFormat.SRGB_UINT8),
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, origin, 0));
        assertEquals(
                rectangle,
                SourceViewGeometry.requiredCapturedSourcePixelRect(
                                view, target, new SourceIdentityCodec.IntRect(0, 0, 8, 1))
                        .orElseThrow());
        assertEquals(
                new SourceIdentityCodec.IntRect(extent - 2, 0, 2, 1),
                SourceViewGeometry.requiredCapturedSourcePixelRect(
                                view, target, new SourceIdentityCodec.IntRect(6, 0, 2, 1))
                        .orElseThrow());
    }

    @Test
    void mutationsCannotRedefineARecordedCaptureViewOrBinding() throws IOException {
        JsonObject fixture = fixture();
        var capture = SourceCaptureCodec.parseCapture(fixture.get("capture"));
        JsonObject changed = fixture.getAsJsonObject("capture").deepCopy();
        changed.getAsJsonObject("source_level").addProperty("width", 70001);
        assertThrows(
                IllegalArgumentException.class, () -> SourceCaptureCodec.parseCapture(changed));
        JsonObject view = fixture.getAsJsonObject("view").deepCopy();
        view.getAsJsonObject("pixel_rect").addProperty("x", 33433);
        assertThrows(IllegalArgumentException.class, () -> SourceCaptureCodec.parseView(view));
        JsonObject binding = fixture.getAsJsonObject("binding").deepCopy();
        binding.addProperty("capture_digest", "0".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceCaptureCodec.parseBinding(binding, capture));
    }

    @Test
    void rectanglesCannotEscapeTheirDeclaredSource() throws IOException {
        JsonObject fixture = fixture();
        var capture = SourceCaptureCodec.parseCapture(fixture.get("capture"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SourceCaptureCodec.View(
                                capture, new SourceIdentityCodec.IntRect(0, 0, 100000, 1)));
        JsonObject binding = fixture.getAsJsonObject("binding").deepCopy();
        binding.getAsJsonObject("source_rect_at_level").addProperty("x", 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceCaptureCodec.parseBinding(binding, capture));
        JsonObject invalid = fixture.getAsJsonObject("capture").deepCopy();
        invalid.getAsJsonObject("source_level").addProperty("resolution_level", 0);
        assertThrows(
                IllegalArgumentException.class, () -> SourceCaptureCodec.parseCapture(invalid));
        invalid.getAsJsonObject("plane").addProperty("z", 1);
        assertThrows(
                IllegalArgumentException.class, () -> SourceCaptureCodec.parseCapture(invalid));
    }

    @Test
    void reservationBoundsEveryContainerAndMaximumIntegerEncoding() {
        for (var container : SourceArtifactCodec.SourceContainer.values()) {
            var artifact =
                    new SourceArtifactPublicationCodec.SourceArtifactRef(
                            "ffffffff-ffff-4fff-bfff-ffffffffffff",
                            "ffffffff-ffff-4fff-bfff-ffffffffffff",
                            "f".repeat(64),
                            "f".repeat(64),
                            "f".repeat(64),
                            container);
            var binding =
                    new SourceCaptureCodec.UnitBinding(
                            Integer.MAX_VALUE,
                            new SourceCaptureCodec.Binding(
                                    "f".repeat(64),
                                    artifact,
                                    new SourceIdentityCodec.IntRect(
                                            Integer.MAX_VALUE / 2,
                                            Integer.MAX_VALUE / 2,
                                            Integer.MAX_VALUE / 2,
                                            Integer.MAX_VALUE / 2)));
            long actual =
                    CanonicalCodec.canonicalBytes(SourceCaptureCodec.bindingsJson(List.of(binding)))
                            .length;
            assertTrue(
                    actual <= SourceCaptureCodec.maximumBindingsUtf8Size(1), container.toString());
            long repeated =
                    CanonicalCodec.canonicalBytes(
                                    SourceCaptureCodec.bindingsJson(List.of(binding, binding)))
                            .length;
            assertTrue(
                    repeated <= SourceCaptureCodec.maximumBindingsUtf8Size(2),
                    container.toString());
        }
        assertTrue(
                SourceCaptureCodec.maximumBindingsUtf8Size(Integer.MAX_VALUE) > Integer.MAX_VALUE);
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceCaptureCodec.maximumBindingsUtf8Size(0));
    }

    @Test
    void unknownFieldsAreNotSilentlyDropped() throws IOException {
        JsonObject capture = fixture().getAsJsonObject("capture");
        capture.addProperty("unknown", 1);
        assertThrows(
                IllegalArgumentException.class, () -> SourceCaptureCodec.parseCapture(capture));
    }

    private static JsonObject fixture() throws IOException {
        try (var stream =
                SourceCaptureCodecTest.class.getResourceAsStream(
                        "/contracts/source-capture-v1.json")) {
            if (stream == null) {
                throw new IOException("source capture fixture is missing");
            }
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
