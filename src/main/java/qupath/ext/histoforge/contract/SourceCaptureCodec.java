package qupath.ext.histoforge.contract;

import static qupath.ext.histoforge.contract.StrictJson.requireExactObject;
import static qupath.ext.histoforge.contract.StrictJson.requiredString;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Image geometry captured before its lossless source files are uploaded. */
public final class SourceCaptureCodec {

    private static final Set<String> CAPTURE_FIELDS =
            Set.of(
                    "source",
                    "plane",
                    "source_level",
                    "source_level_pixels_to_level_zero",
                    "level_zero_pixels_to_slide",
                    "source_rect_at_level",
                    "writer_build",
                    "capture_digest");
    private static final Set<String> VIEW_FIELDS = Set.of("capture", "pixel_rect", "view_digest");
    private static final Set<String> BINDING_FIELDS =
            Set.of("capture_digest", "artifact", "source_rect_at_level");

    private SourceCaptureCodec() {}

    /**
     * One complete planned envelope at a fixed source-pyramid level. Reader mappings remain
     * separate to preserve their floating-point composition order; level dimensions must never be
     * inferred from the size of a smaller uploaded file.
     */
    public record Capture(
            SourceIdentityCodec.ImageSourceIdentity source,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceArtifactCodec.SourceLevelRequirement sourceLevel,
            SourceIdentityCodec.ScaleTranslate2D sourceLevelPixelsToLevelZero,
            SourceIdentityCodec.ScaleTranslate2D levelZeroPixelsToSlide,
            SourceIdentityCodec.IntRect sourceRectAtLevel,
            BuildIdentityCodec.SoftwareBuildIdentity writerBuild) {
        public Capture {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(plane, "plane");
            Objects.requireNonNull(sourceLevel, "source_level");
            Objects.requireNonNull(
                    sourceLevelPixelsToLevelZero, "source_level_pixels_to_level_zero");
            Objects.requireNonNull(levelZeroPixelsToSlide, "level_zero_pixels_to_slide");
            Objects.requireNonNull(writerBuild, "writer_build");
            if (plane.z() != 0 || plane.t() != 0) {
                throw new IllegalArgumentException("source capture supports only plane z=0, t=0");
            }
            requireContained(
                    sourceRectAtLevel, fullRect(sourceLevel.width(), sourceLevel.height()));
            if (sourceLevel.resolutionLevel() == 0
                    && (sourceLevel.width() != source.width()
                            || sourceLevel.height() != source.height()
                            || !sourceLevelPixelsToLevelZero.equals(
                                    new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0)))) {
                throw new IllegalArgumentException(
                        "source level zero must match the original image grid");
            }
        }

        public String captureDigest() {
            return CanonicalCodec.canonicalDigest(capturePreimage(this));
        }
    }

    /** Fixed model view relative to the complete capture, not to an individual uploaded file. */
    public record View(Capture capture, SourceIdentityCodec.IntRect pixelRect)
            implements RasterInputCodec.RasterSourceView {
        public View {
            Objects.requireNonNull(capture, "capture");
            var envelope = capture.sourceRectAtLevel();
            requireContained(pixelRect, fullRect(envelope.width(), envelope.height()));
        }

        public String viewDigest() {
            return CanonicalCodec.canonicalDigest(viewPreimage(this));
        }

        public SourceIdentityCodec.ImagePlaneIndex plane() {
            return capture.plane();
        }

        public SourceIdentityCodec.ScaleTranslate2D viewPixelsToSlide() {
            return viewGeometry(this).viewPixelsToSlide();
        }
    }

    /**
     * Exact sealed file and the source-level pixels it supplies. One file may supply several
     * processing units; unit assignment belongs to the accepted run plan.
     */
    public record Binding(
            String captureDigest,
            SourceArtifactPublicationCodec.SourceArtifactRef artifact,
            SourceIdentityCodec.IntRect sourceRectAtLevel) {
        public Binding {
            captureDigest = CanonicalCodec.requireSha256(captureDigest, "capture_digest");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(sourceRectAtLevel, "source_rect_at_level");
        }

        public void requireCapture(Capture capture) {
            if (!captureDigest.equals(capture.captureDigest())) {
                throw new IllegalArgumentException("source binding names a different capture");
            }
            requireContained(sourceRectAtLevel, capture.sourceRectAtLevel());
        }
    }

    /** One input's exact image contribution, indexed by its processing unit. */
    public record UnitBinding(int unitId, Binding source) {
        public UnitBinding {
            CanonicalCodec.requireNonnegativeInt32(unitId, "unit_id");
            Objects.requireNonNull(source, "source");
        }
    }

    /** Complete results must retain every unit, including units with no predictions. */
    public static List<UnitBinding> requireCompleteBindings(List<UnitBinding> values) {
        var bindings = List.copyOf(values);
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("completed input source_bindings must be nonempty");
        }
        for (int unit = 0; unit < bindings.size(); unit++) {
            if (bindings.get(unit).unitId() != unit) {
                throw new IllegalArgumentException(
                        "input source bindings must cover consecutive units from zero");
            }
        }
        return bindings;
    }

    public static List<UnitBinding> parseCompleteBindings(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("source_bindings must be an array");
        }
        List<UnitBinding> result = new ArrayList<>();
        for (var entry : value.getAsJsonArray()) {
            var item =
                    requireExactObject(entry, Set.of("unit_id", "source"), "input source binding");
            result.add(
                    new UnitBinding(
                            CanonicalCodec.requireNonnegativeInt32(item.get("unit_id"), "unit_id"),
                            parseBinding(item.get("source"))));
        }
        return requireCompleteBindings(result);
    }

    public static JsonArray bindingsJson(List<UnitBinding> bindings) {
        JsonArray result = new JsonArray();
        for (var binding : bindings) {
            JsonObject item = new JsonObject();
            item.addProperty("unit_id", binding.unitId());
            item.add("source", toJson(binding.source()));
            result.add(item);
        }
        return result;
    }

    /**
     * Reserve evidence space before file identities exist. Each entry contains two UUIDs, four
     * SHA-256 digests, five nonnegative int32 values, a container name, and fixed JSON keys. Their
     * maximum encoding fits in 1 KiB; the contract test checks every supported container. Count
     * processing units, even when several units use the same file.
     */
    static long maximumBindingsUtf8Size(int unitCount) {
        CanonicalCodec.requirePositiveInt32(unitCount, "source binding unit count");
        return 2L + unitCount * 1024L + (unitCount - 1L);
    }

    /** Rectangle in source-level pixels and mapping from view-local pixels into slide space. */
    public record ViewGeometry(
            SourceIdentityCodec.IntRect sourceRectAtLevel,
            SourceIdentityCodec.ScaleTranslate2D viewPixelsToSlide) {}

    public static Capture parseCapture(JsonElement value) {
        JsonObject item = requireExactObject(value, CAPTURE_FIELDS, "source capture");
        Capture capture =
                new Capture(
                        SourceIdentityCodec.parseImageSourceIdentity(item.get("source")),
                        SourceIdentityCodec.parseImagePlaneIndex(item.get("plane")),
                        SourceArtifactCodec.parseLevelRequirement(item.get("source_level")),
                        SourceIdentityCodec.parseScaleTranslate2D(
                                item.get("source_level_pixels_to_level_zero")),
                        SourceIdentityCodec.parseScaleTranslate2D(
                                item.get("level_zero_pixels_to_slide")),
                        SourceIdentityCodec.parseIntRect(item.get("source_rect_at_level")),
                        BuildIdentityCodec.parseSoftwareBuildIdentity(item.get("writer_build")));
        requireDigest(item, "capture_digest", capture.captureDigest());
        return capture;
    }

    public static View parseView(JsonElement value) {
        JsonObject item = requireExactObject(value, VIEW_FIELDS, "captured source view");
        View view =
                new View(
                        parseCapture(item.get("capture")),
                        SourceIdentityCodec.parseIntRect(item.get("pixel_rect")));
        requireDigest(item, "view_digest", view.viewDigest());
        return view;
    }

    public static Binding parseBinding(JsonElement value, Capture capture) {
        Binding binding = parseBinding(value);
        binding.requireCapture(capture);
        return binding;
    }

    public static Binding parseBinding(JsonElement value) {
        JsonObject item = requireExactObject(value, BINDING_FIELDS, "captured source binding");
        return new Binding(
                requiredString(item, "capture_digest"),
                SourceArtifactPublicationCodec.parseArtifactRef(item.get("artifact")),
                SourceIdentityCodec.parseIntRect(item.get("source_rect_at_level")));
    }

    public static JsonObject toJson(Capture capture) {
        JsonObject value = capturePreimage(capture);
        value.addProperty("capture_digest", capture.captureDigest());
        return value;
    }

    public static JsonObject toJson(View view) {
        JsonObject value = viewPreimage(view);
        value.addProperty("view_digest", view.viewDigest());
        return value;
    }

    public static JsonObject toJson(Binding binding) {
        JsonObject value = new JsonObject();
        value.addProperty("capture_digest", binding.captureDigest());
        value.add("artifact", SourceArtifactPublicationCodec.toJson(binding.artifact()));
        value.add("source_rect_at_level", SourceIdentityCodec.toJson(binding.sourceRectAtLevel()));
        return value;
    }

    /**
     * Preserve the complete prepared-image mapping: first position the capture, then its model
     * view. Never recompute the view from the origin of a smaller uploaded file.
     */
    public static ViewGeometry viewGeometry(View view) {
        Capture capture = view.capture();
        var envelope = capture.sourceRectAtLevel();
        var rectangle = view.pixelRect();
        return new ViewGeometry(
                new SourceIdentityCodec.IntRect(
                        Math.addExact(envelope.x(), rectangle.x()),
                        Math.addExact(envelope.y(), rectangle.y()),
                        rectangle.width(),
                        rectangle.height()),
                SourceViewGeometry.deriveDerivedViewTransform(
                        sourceRectangleMapping(capture, envelope),
                        new SourceViewGeometry.Dimensions(envelope.width(), envelope.height()),
                        rectangle));
    }

    /** Origin understood by the existing lossless QuPath source writer and backend reader. */
    public static SourceArtifactCodec.DerivedRegion imageOrigin(
            Capture capture, SourceIdentityCodec.IntRect sourceRectAtLevel) {
        requireContained(sourceRectAtLevel, capture.sourceRectAtLevel());
        return new SourceArtifactCodec.DerivedRegion(
                capture.source(),
                capture.sourceLevel().resolutionLevel(),
                capture.plane(),
                sourceRectAtLevel,
                sourceRectangleMapping(capture, sourceRectAtLevel),
                capture.writerBuild());
    }

    private static SourceIdentityCodec.ScaleTranslate2D sourceRectangleMapping(
            Capture capture, SourceIdentityCodec.IntRect rectangle) {
        var level = capture.sourceLevel();
        return SourceViewGeometry.deriveOriginalViewTransform(
                capture.levelZeroPixelsToSlide(),
                capture.sourceLevelPixelsToLevelZero(),
                new SourceViewGeometry.Dimensions(level.width(), level.height()),
                rectangle);
    }

    private static JsonObject capturePreimage(Capture capture) {
        JsonObject value = new JsonObject();
        value.add("source", SourceIdentityCodec.toJson(capture.source()));
        value.add("plane", SourceIdentityCodec.toJson(capture.plane()));
        value.add("source_level", SourceArtifactCodec.toJson(capture.sourceLevel()));
        value.add(
                "source_level_pixels_to_level_zero",
                SourceIdentityCodec.toJson(capture.sourceLevelPixelsToLevelZero()));
        value.add(
                "level_zero_pixels_to_slide",
                SourceIdentityCodec.toJson(capture.levelZeroPixelsToSlide()));
        value.add("source_rect_at_level", SourceIdentityCodec.toJson(capture.sourceRectAtLevel()));
        value.add("writer_build", BuildIdentityCodec.toJson(capture.writerBuild()));
        return value;
    }

    private static JsonObject viewPreimage(View view) {
        JsonObject value = new JsonObject();
        value.add("capture", toJson(view.capture()));
        value.add("pixel_rect", SourceIdentityCodec.toJson(view.pixelRect()));
        return value;
    }

    private static SourceIdentityCodec.IntRect fullRect(int width, int height) {
        return new SourceIdentityCodec.IntRect(0, 0, width, height);
    }

    /** Reject a file or view rectangle that extends beyond its captured source bounds. */
    public static void requireContained(
            SourceIdentityCodec.IntRect inner, SourceIdentityCodec.IntRect outer) {
        Objects.requireNonNull(inner, "source rectangle");
        if (inner.x() < outer.x()
                || inner.y() < outer.y()
                || (long) inner.x() + inner.width() > (long) outer.x() + outer.width()
                || (long) inner.y() + inner.height() > (long) outer.y() + outer.height()) {
            throw new IllegalArgumentException(
                    "source rectangle lies outside the captured image bounds");
        }
    }

    private static void requireDigest(JsonObject item, String field, String expected) {
        if (!CanonicalCodec.requireSha256(requiredString(item, field), field).equals(expected)) {
            throw new IllegalArgumentException(field + " does not match its fields");
        }
    }
}
