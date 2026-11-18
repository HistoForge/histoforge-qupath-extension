package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/** Canonical source-view and target-raster input contracts. */
public final class RasterInputCodec {

    private static final Set<String> IMAGE_REVISION_FIELDS =
            Set.of("image_id", "image_source_digest", "image_data_content_digest");
    private static final Set<String> ACTION_SOURCE_REVISION_FIELDS =
            Set.of("image_data_revision", "pixel_source");
    private static final Set<String> RASTER_DESCRIPTOR_FIELDS =
            Set.of("width", "height", "plane", "target_mpp", "resampling", "pixel_format");
    private static final Set<String> RASTER_TRANSFORM_FIELDS = Set.of("raster", "raster_to_slide");
    private static final Set<String> SOURCE_VIEW_FIELDS =
            Set.of(
                    "artifact",
                    "series_index",
                    "resolution_level",
                    "plane",
                    "pixel_rect",
                    "view_pixels_to_slide",
                    "view_digest");
    private static final Set<String> RASTER_FRAME_FIELDS =
            Set.of(
                    "source_revision",
                    "region",
                    "transform",
                    "inclusion_mask_raster",
                    "inclusion_mask_digest",
                    "frame_digest");
    private static final Set<String> MODEL_RASTER_INPUT_FIELDS =
            Set.of("source_view", "frame", "rasterizer", "input_digest");

    private RasterInputCodec() {}

    /** Complete semantic image/source identity used for conflict detection. */
    public record ImageRevisionToken(
            String imageId, String imageSourceDigest, String imageDataContentDigest) {
        public ImageRevisionToken {
            imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
            imageSourceDigest =
                    CanonicalCodec.requireSha256(imageSourceDigest, "image_source_digest");
            imageDataContentDigest =
                    CanonicalCodec.requireSha256(
                            imageDataContentDigest, "image_data_content_digest");
        }
    }

    /** Semantic ImageData revision paired with independent pixel-source identity. */
    public record ActionSourceRevision(
            ImageRevisionToken imageDataRevision,
            SourceIdentityCodec.ImageSourceIdentity pixelSource) {
        public ActionSourceRevision {
            imageDataRevision = Objects.requireNonNull(imageDataRevision, "image_data_revision");
            pixelSource = Objects.requireNonNull(pixelSource, "pixel_source");
            String expected = SourceIdentityCodec.imageSourceIdentityDigest(pixelSource);
            if (!imageDataRevision.imageSourceDigest().equals(expected)) {
                throw new IllegalArgumentException(
                        "image_data_revision.image_source_digest does not match pixel_source");
            }
        }
    }

    /** Closed target-raster interpolation algorithm. */
    public enum ResamplingType {
        BILINEAR
    }

    /** Closed target-raster sample representation. */
    public enum RasterPixelFormat {
        SRGB_UINT8
    }

    /** Exact square-pixel target-grid descriptor. */
    public record RasterDescriptor(
            int width,
            int height,
            SourceIdentityCodec.ImagePlaneIndex plane,
            double targetMpp,
            ResamplingType resampling,
            RasterPixelFormat pixelFormat) {
        public RasterDescriptor {
            width = CanonicalCodec.requirePositiveInt32(width, "width");
            height = CanonicalCodec.requirePositiveInt32(height, "height");
            plane = Objects.requireNonNull(plane, "plane");
            targetMpp = CanonicalCodec.requirePositiveFiniteDouble(targetMpp, "target_mpp");
            resampling = Objects.requireNonNull(resampling, "resampling");
            pixelFormat = Objects.requireNonNull(pixelFormat, "pixel_format");
        }
    }

    /** Target-grid descriptor paired with its exact slide mapping. */
    public record RasterTransform(
            RasterDescriptor raster, SourceIdentityCodec.ScaleTranslate2D rasterToSlide) {
        public RasterTransform {
            raster = Objects.requireNonNull(raster, "raster");
            rasterToSlide = Objects.requireNonNull(rasterToSlide, "raster_to_slide");
        }
    }

    /** Fixed model sampling geometry, independent of when its image bytes arrive. */
    public sealed interface RasterSourceView permits SourceView, SourceCaptureCodec.View {
        SourceIdentityCodec.ImagePlaneIndex plane();

        SourceIdentityCodec.IntRect pixelRect();

        SourceIdentityCodec.ScaleTranslate2D viewPixelsToSlide();

        String viewDigest();
    }

    /** Exact self-digested rectangle in one sealed artifact pyramid level. */
    public record SourceView(
            SourceArtifactPublicationCodec.SourceArtifactRef artifact,
            int seriesIndex,
            int resolutionLevel,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.IntRect pixelRect,
            SourceIdentityCodec.ScaleTranslate2D viewPixelsToSlide,
            String viewDigest)
            implements RasterSourceView {
        public SourceView {
            artifact = Objects.requireNonNull(artifact, "artifact");
            seriesIndex = CanonicalCodec.requireNonnegativeInt32(seriesIndex, "series_index");
            resolutionLevel =
                    CanonicalCodec.requireNonnegativeInt32(resolutionLevel, "resolution_level");
            plane = Objects.requireNonNull(plane, "plane");
            pixelRect = Objects.requireNonNull(pixelRect, "pixel_rect");
            viewPixelsToSlide = Objects.requireNonNull(viewPixelsToSlide, "view_pixels_to_slide");
            viewDigest = CanonicalCodec.requireSha256(viewDigest, "view_digest");
        }
    }

    /** Self-digested logical region, target grid, and inclusion-mask evidence. */
    public record RasterFrame(
            ActionSourceRevision sourceRevision,
            ExecutionScopeCodec.RegionKey region,
            RasterTransform transform,
            Geometry inclusionMaskRaster,
            String inclusionMaskDigest,
            String frameDigest) {
        public RasterFrame {
            sourceRevision = Objects.requireNonNull(sourceRevision, "source_revision");
            region = Objects.requireNonNull(region, "region");
            transform = Objects.requireNonNull(transform, "transform");
            inclusionMaskRaster = copyCanonicalGeometry(inclusionMaskRaster);
            inclusionMaskDigest =
                    CanonicalCodec.requireSha256(inclusionMaskDigest, "inclusion_mask_digest");
            if (!inclusionMaskDigest.equals(
                    CanonicalGeometryCodec.canonicalDigest(inclusionMaskRaster))) {
                throw new IllegalArgumentException(
                        "inclusion_mask_digest does not match inclusion_mask_raster");
            }
            requireTransformMatchesSource(transform, sourceRevision);
            requireGeometryInsideRaster(
                    inclusionMaskRaster, transform.raster(), "inclusion_mask_raster");
            frameDigest = CanonicalCodec.requireSha256(frameDigest, "frame_digest");
        }

        @Override
        public Geometry inclusionMaskRaster() {
            return inclusionMaskRaster.copy();
        }
    }

    /** Complete immutable source view and exact backend rasterization request. */
    public record ModelRasterInput(
            RasterSourceView sourceView,
            RasterFrame frame,
            InferenceIdentityCodec.RasterizerIdentity rasterizer,
            String inputDigest) {
        public ModelRasterInput {
            sourceView = Objects.requireNonNull(sourceView, "source_view");
            frame = Objects.requireNonNull(frame, "frame");
            rasterizer = Objects.requireNonNull(rasterizer, "rasterizer");
            inputDigest = CanonicalCodec.requireSha256(inputDigest, "input_digest");
            if (!sourceView.plane().equals(frame.transform().raster().plane())) {
                throw new IllegalArgumentException(
                        "source view plane does not match target raster plane");
            }
            if (sourceView instanceof SourceCaptureCodec.View captured
                    && !captured.capture().source().equals(frame.sourceRevision().pixelSource())) {
                throw new IllegalArgumentException(
                        "source capture does not match the frame's pixel source");
            }
        }
    }

    /** Parse one exact semantic image revision. */
    public static ImageRevisionToken parseImageRevisionToken(JsonElement value) {
        JsonObject object =
                requireExactObject(value, IMAGE_REVISION_FIELDS, "image revision token");
        return new ImageRevisionToken(
                requiredString(object, "image_id"),
                requiredString(object, "image_source_digest"),
                requiredString(object, "image_data_content_digest"));
    }

    /** Parse and bind a semantic image revision to its independent pixel source. */
    public static ActionSourceRevision parseActionSourceRevision(JsonElement value) {
        JsonObject object =
                requireExactObject(value, ACTION_SOURCE_REVISION_FIELDS, "action source revision");
        return new ActionSourceRevision(
                parseImageRevisionToken(object.get("image_data_revision")),
                SourceIdentityCodec.parseImageSourceIdentity(object.get("pixel_source")));
    }

    /** Parse one exact target-raster descriptor. */
    public static RasterDescriptor parseRasterDescriptor(JsonElement value) {
        JsonObject object =
                requireExactObject(value, RASTER_DESCRIPTOR_FIELDS, "raster descriptor");
        return new RasterDescriptor(
                CanonicalCodec.requirePositiveInt32(object.get("width"), "width"),
                CanonicalCodec.requirePositiveInt32(object.get("height"), "height"),
                SourceIdentityCodec.parseImagePlaneIndex(object.get("plane")),
                CanonicalCodec.requirePositiveFiniteDouble(object.get("target_mpp"), "target_mpp"),
                exactEnum(object.get("resampling"), ResamplingType.class, "resampling"),
                exactEnum(object.get("pixel_format"), RasterPixelFormat.class, "pixel_format"));
    }

    /** Parse one target grid and exact slide mapping. */
    public static RasterTransform parseRasterTransform(JsonElement value) {
        JsonObject object = requireExactObject(value, RASTER_TRANSFORM_FIELDS, "raster transform");
        return new RasterTransform(
                parseRasterDescriptor(object.get("raster")),
                SourceIdentityCodec.parseScaleTranslate2D(object.get("raster_to_slide")));
    }

    /** Parse and verify one self-digested source view. */
    public static SourceView parseSourceView(JsonElement value) {
        JsonObject object = requireExactObject(value, SOURCE_VIEW_FIELDS, "source view");
        SourceView view =
                new SourceView(
                        SourceArtifactPublicationCodec.parseArtifactRef(object.get("artifact")),
                        CanonicalCodec.requireNonnegativeInt32(
                                object.get("series_index"), "series_index"),
                        CanonicalCodec.requireNonnegativeInt32(
                                object.get("resolution_level"), "resolution_level"),
                        SourceIdentityCodec.parseImagePlaneIndex(object.get("plane")),
                        SourceIdentityCodec.parseIntRect(object.get("pixel_rect")),
                        SourceIdentityCodec.parseScaleTranslate2D(
                                object.get("view_pixels_to_slide")),
                        requiredString(object, "view_digest"));
        if (!view.viewDigest().equals(sourceViewDigest(view))) {
            throw new IllegalArgumentException("view_digest does not match source view fields");
        }
        return view;
    }

    /** Parse the closed choice of sealed-file or captured source geometry. */
    public static RasterSourceView parseRasterSourceView(JsonElement value) {
        JsonObject object = requiredObject(value, "source view");
        return object.has("capture")
                ? SourceCaptureCodec.parseView(object)
                : parseSourceView(object);
    }

    /** Parse and verify one self-digested target raster frame. */
    public static RasterFrame parseRasterFrame(JsonElement value) {
        JsonObject object = requireExactObject(value, RASTER_FRAME_FIELDS, "raster frame");
        RasterFrame frame =
                new RasterFrame(
                        parseActionSourceRevision(object.get("source_revision")),
                        ExecutionScopeCodec.parseRegionKey(object.get("region")),
                        parseRasterTransform(object.get("transform")),
                        parseGeometry(object.get("inclusion_mask_raster")),
                        requiredString(object, "inclusion_mask_digest"),
                        requiredString(object, "frame_digest"));
        if (!frame.frameDigest()
                .equals(CanonicalCodec.canonicalDigest(rasterFramePreimage(frame)))) {
            throw new IllegalArgumentException("frame_digest does not match raster frame fields");
        }
        return frame;
    }

    /** Parse and verify one complete model-raster input. */
    public static ModelRasterInput parseModelRasterInput(JsonElement value) {
        JsonObject object =
                requireExactObject(value, MODEL_RASTER_INPUT_FIELDS, "model raster input");
        ModelRasterInput input =
                new ModelRasterInput(
                        parseRasterSourceView(object.get("source_view")),
                        parseRasterFrame(object.get("frame")),
                        InferenceIdentityCodec.parseRasterizerIdentity(object.get("rasterizer")),
                        requiredString(object, "input_digest"));
        if (!input.inputDigest().equals(modelRasterInputDigest(input))) {
            throw new IllegalArgumentException(
                    "input_digest does not match model raster input identity");
        }
        return input;
    }

    /** Build a source view and derive its self-digest. */
    public static SourceView buildSourceView(
            SourceArtifactPublicationCodec.SourceArtifactRef artifact,
            int seriesIndex,
            int resolutionLevel,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.IntRect pixelRect,
            SourceIdentityCodec.ScaleTranslate2D viewPixelsToSlide) {
        SourceView provisional =
                new SourceView(
                        artifact,
                        seriesIndex,
                        resolutionLevel,
                        plane,
                        pixelRect,
                        viewPixelsToSlide,
                        "0".repeat(64));
        return new SourceView(
                artifact,
                seriesIndex,
                resolutionLevel,
                plane,
                pixelRect,
                viewPixelsToSlide,
                sourceViewDigest(provisional));
    }

    /** Build a raster frame and derive its geometry and frame digests. */
    public static RasterFrame buildRasterFrame(
            ActionSourceRevision sourceRevision,
            ExecutionScopeCodec.RegionKey region,
            RasterTransform transform,
            Geometry inclusionMaskRaster) {
        Geometry geometry = copyCanonicalGeometry(inclusionMaskRaster);
        String maskDigest = CanonicalGeometryCodec.canonicalDigest(geometry);
        RasterFrame provisional =
                new RasterFrame(
                        sourceRevision, region, transform, geometry, maskDigest, "0".repeat(64));
        return new RasterFrame(
                sourceRevision,
                region,
                transform,
                geometry,
                maskDigest,
                CanonicalCodec.canonicalDigest(rasterFramePreimage(provisional)));
    }

    /** Build a complete model input and derive its compact self-digest. */
    public static ModelRasterInput buildModelRasterInput(
            RasterSourceView sourceView,
            RasterFrame frame,
            InferenceIdentityCodec.RasterizerIdentity rasterizer) {
        ModelRasterInput provisional =
                new ModelRasterInput(sourceView, frame, rasterizer, "0".repeat(64));
        return new ModelRasterInput(
                sourceView, frame, rasterizer, modelRasterInputDigest(provisional));
    }

    /** Encode one complete model input using exact lower-snake fields. */
    public static JsonObject toJson(ModelRasterInput input) {
        JsonObject value = new JsonObject();
        value.add("source_view", toJson(input.sourceView()));
        value.add("frame", toJson(input.frame()));
        value.add("rasterizer", InferenceIdentityCodec.toJson(input.rasterizer()));
        value.addProperty("input_digest", input.inputDigest());
        return value;
    }

    /** Return the compact canonical digest of one model-raster input. */
    public static String modelRasterInputDigest(ModelRasterInput input) {
        return modelRasterInputDigest(
                input.sourceView().viewDigest(), input.frame().frameDigest(), input.rasterizer());
    }

    /** Return the canonical digest of every source-view field except its self-digest. */
    public static String sourceViewDigest(SourceView view) {
        return CanonicalCodec.canonicalDigest(sourceViewPreimage(view));
    }

    /** Recompute a compact model-input digest from the evidence retained after commit. */
    public static String modelRasterInputDigest(
            String sourceViewDigest,
            String frameDigest,
            InferenceIdentityCodec.RasterizerIdentity rasterizer) {
        JsonObject preimage = new JsonObject();
        preimage.addProperty(
                "source_view_digest",
                CanonicalCodec.requireSha256(sourceViewDigest, "source_view_digest"));
        preimage.addProperty(
                "frame_digest", CanonicalCodec.requireSha256(frameDigest, "frame_digest"));
        preimage.add(
                "rasterizer",
                InferenceIdentityCodec.toJson(Objects.requireNonNull(rasterizer, "rasterizer")));
        return CanonicalCodec.canonicalDigest(preimage);
    }

    /** Return the canonical digest used as the normalized generation-store map key. */
    public static String actionSourceRevisionDigest(ActionSourceRevision revision) {
        return CanonicalCodec.canonicalDigest(toJson(revision));
    }

    /** Revalidate a compact persisted raster transform against its normalized source revision. */
    static void requireTransformMatchesSource(
            RasterTransform transform, ActionSourceRevision sourceRevision) {
        Objects.requireNonNull(transform, "raster_transform");
        Objects.requireNonNull(sourceRevision, "source_revision");
        requireTargetMppScale(transform, sourceRevision.pixelSource());
        requirePlaneInsideSource(transform.raster().plane(), sourceRevision.pixelSource());
    }

    /** Encode one semantic image revision for another canonical persisted contract. */
    public static JsonObject toJson(ImageRevisionToken revision) {
        JsonObject value = new JsonObject();
        value.addProperty("image_id", revision.imageId());
        value.addProperty("image_source_digest", revision.imageSourceDigest());
        value.addProperty("image_data_content_digest", revision.imageDataContentDigest());
        return value;
    }

    /** Encode one complete source revision for normalized generation or export evidence. */
    public static JsonObject toJson(ActionSourceRevision revision) {
        JsonObject value = new JsonObject();
        value.add("image_data_revision", toJson(revision.imageDataRevision()));
        value.add("pixel_source", SourceIdentityCodec.toJson(revision.pixelSource()));
        return value;
    }

    static JsonObject toJson(RasterDescriptor raster) {
        JsonObject value = new JsonObject();
        value.addProperty("width", raster.width());
        value.addProperty("height", raster.height());
        value.add("plane", SourceIdentityCodec.toJson(raster.plane()));
        value.addProperty("target_mpp", raster.targetMpp());
        value.addProperty("resampling", raster.resampling().name());
        value.addProperty("pixel_format", raster.pixelFormat().name());
        return value;
    }

    static JsonObject toJson(RasterTransform transform) {
        JsonObject value = new JsonObject();
        value.add("raster", toJson(transform.raster()));
        value.add("raster_to_slide", SourceIdentityCodec.toJson(transform.rasterToSlide()));
        return value;
    }

    static JsonObject toJson(SourceView view) {
        JsonObject value = sourceViewPreimage(view);
        value.addProperty("view_digest", view.viewDigest());
        return value;
    }

    public static JsonObject toJson(RasterSourceView view) {
        return switch (view) {
            case SourceView sealed -> toJson(sealed);
            case SourceCaptureCodec.View captured -> SourceCaptureCodec.toJson(captured);
        };
    }

    static JsonObject toJson(RasterFrame frame) {
        JsonObject value = rasterFramePreimage(frame);
        value.addProperty("frame_digest", frame.frameDigest());
        return value;
    }

    private static JsonObject sourceViewPreimage(SourceView view) {
        JsonObject value = new JsonObject();
        value.add("artifact", SourceArtifactPublicationCodec.toJson(view.artifact()));
        value.addProperty("series_index", view.seriesIndex());
        value.addProperty("resolution_level", view.resolutionLevel());
        value.add("plane", SourceIdentityCodec.toJson(view.plane()));
        value.add("pixel_rect", SourceIdentityCodec.toJson(view.pixelRect()));
        value.add("view_pixels_to_slide", SourceIdentityCodec.toJson(view.viewPixelsToSlide()));
        return value;
    }

    private static JsonObject rasterFramePreimage(RasterFrame frame) {
        JsonObject value = new JsonObject();
        value.add("source_revision", toJson(frame.sourceRevision()));
        value.add("region", ExecutionScopeCodec.toJson(frame.region()));
        value.add("transform", toJson(frame.transform()));
        value.add("inclusion_mask_raster", geometryJson(frame.inclusionMaskRaster()));
        value.addProperty("inclusion_mask_digest", frame.inclusionMaskDigest());
        return value;
    }

    private static Geometry parseGeometry(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("inclusion_mask_raster must be an object");
        }
        return CanonicalGeometryCodec.parseGeoJson(value.getAsJsonObject());
    }

    private static Geometry copyCanonicalGeometry(Geometry geometry) {
        Geometry copy = Objects.requireNonNull(geometry, "inclusion_mask_raster").copy();
        CanonicalGeometryCodec.canonicalCoordinates(copy);
        return copy;
    }

    private static JsonObject geometryJson(Geometry geometry) {
        JsonObject value = new JsonObject();
        value.addProperty("type", geometry.getGeometryType());
        value.add("coordinates", CanonicalGeometryCodec.canonicalCoordinates(geometry));
        return value;
    }

    /** Require complete geometry coordinates to lie within one submitted target raster. */
    public static void requireGeometryInsideRaster(
            Geometry geometry, RasterDescriptor raster, String path) {
        if (geometry instanceof Point) {
            requirePointBoundsInsideRaster(geometry.getEnvelopeInternal(), raster, path);
            return;
        }
        requireBoundsInsideRaster(geometry.getEnvelopeInternal(), raster, path);
    }

    /** Points occupy the half-open pixel domain; polygon edges may reach width or height. */
    static void requirePointBoundsInsideRaster(
            Envelope envelope, RasterDescriptor raster, String path) {
        requireBoundsInsideRaster(envelope, raster, path);
        if (!envelope.isNull()
                && (envelope.getMaxX() == raster.width()
                        || envelope.getMaxY() == raster.height())) {
            throw new IllegalArgumentException(path + " lies outside the target raster");
        }
    }

    /** Apply the same coordinate bounds to a whole stream without retaining its polygons. */
    static void requireBoundsInsideRaster(Envelope envelope, RasterDescriptor raster, String path) {
        if (envelope.isNull()) return;
        if (envelope.getMinX() < 0.0
                || envelope.getMaxX() > raster.width()
                || envelope.getMinY() < 0.0
                || envelope.getMaxY() > raster.height()) {
            throw new IllegalArgumentException(path + " lies outside the target raster");
        }
    }

    /** Count polygon vertices without each ring's repeated closing coordinate. */
    public static long geometryVertexCount(Geometry geometry) {
        if (geometry instanceof Polygon polygon) {
            long count = polygon.getExteriorRing().getNumPoints() - 1L;
            for (int index = 0; index < polygon.getNumInteriorRing(); index++) {
                count = Math.addExact(count, polygon.getInteriorRingN(index).getNumPoints() - 1L);
            }
            return count;
        }
        if (geometry instanceof MultiPolygon multiPolygon) {
            long count = 0;
            for (int index = 0; index < multiPolygon.getNumGeometries(); index++) {
                count = Math.addExact(count, geometryVertexCount(multiPolygon.getGeometryN(index)));
            }
            return count;
        }
        throw new IllegalArgumentException("geometry must be Polygon or MultiPolygon");
    }

    private static void requireTargetMppScale(
            RasterTransform transform, SourceIdentityCodec.ImageSourceIdentity source) {
        double targetMpp = transform.raster().targetMpp();
        double expectedX = targetMpp / source.calibration().mppX();
        double expectedY = targetMpp / source.calibration().mppY();
        if (Double.compare(transform.rasterToSlide().scaleX(), expectedX) != 0
                || Double.compare(transform.rasterToSlide().scaleY(), expectedY) != 0) {
            throw new IllegalArgumentException(
                    "raster_to_slide scale does not match target_mpp and source calibration");
        }
    }

    private static void requirePlaneInsideSource(
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.ImageSourceIdentity source) {
        if (plane.z() >= source.zSlices() || plane.t() >= source.timepoints()) {
            throw new IllegalArgumentException("target raster plane lies outside the image source");
        }
    }

    private static <T extends Enum<T>> T exactEnum(JsonElement value, Class<T> type, String path) {
        String text = requiredString(value, path);
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + " has an unsupported value", e);
        }
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        requireFields(object, fields, path);
        return object;
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void requireFields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    private static String requiredString(JsonObject value, String field) {
        return requiredString(value.get(field), field);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }
}
