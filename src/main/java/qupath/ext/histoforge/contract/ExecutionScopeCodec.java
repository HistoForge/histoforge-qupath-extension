package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;

/** Canonical logical region, execution-scope, and output ownership contracts. */
public final class ExecutionScopeCodec {

    private static final Set<String> ROI_REGION_FIELDS = Set.of("type", "roi_id");
    private static final Set<String> SLIDE_REGION_FIELDS = Set.of("type");
    private static final Set<String> FIELD_SCOPE_FIELDS = Set.of("type", "region");
    private static final Set<String> PARENT_SCOPE_FIELDS = Set.of("type", "region", "parent_id");
    private static final Set<String> OUTPUT_KEY_FIELDS = Set.of("producer", "scope");
    private static final Set<String> TARGET_REGION_FIELDS =
            Set.of("key", "geometry_slide", "plane", "geometry_digest");

    private ExecutionScopeCodec() {}

    /** Stable logical ROI or slide-region identity. */
    public sealed interface RegionKey permits Roi, Slide {}

    /** One persistent HistoForge ROI. */
    public record Roi(String roiId) implements RegionKey {
        public Roi {
            roiId = CanonicalCodec.requireCanonicalUuid(roiId, "roi_id");
        }
    }

    /** The one slide-mode logical region in an image. */
    public record Slide() implements RegionKey {}

    /** Logical model-output ownership independent of pixel framing. */
    public sealed interface ExecutionScope permits FieldScope, ParentScope {}

    /** One plain-model output owned by one logical region. */
    public record FieldScope(RegionKey region) implements ExecutionScope {
        public FieldScope {
            region = Objects.requireNonNull(region, "region");
        }
    }

    /** One region-gated output owned by one logical region and source parent. */
    public record ParentScope(RegionKey region, String parentId) implements ExecutionScope {
        public ParentScope {
            region = Objects.requireNonNull(region, "region");
            parentId = CanonicalCodec.requireCanonicalUuid(parentId, "parent_id");
        }
    }

    /** Sole logical key for Fill presence and Redo replacement. */
    public record OutputKey(
            InferenceIdentityCodec.ModelStepIdentity producer, ExecutionScope scope) {
        public OutputKey {
            producer = Objects.requireNonNull(producer, "producer");
            scope = Objects.requireNonNull(scope, "scope");
        }
    }

    /** Return the logical region owned by either execution-scope variant. */
    public static RegionKey regionOf(ExecutionScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (scope instanceof FieldScope field) {
            return field.region();
        }
        return ((ParentScope) scope).region();
    }

    /** Captured geometry and plane for one logical region in an action. */
    public record TargetRegion(
            // Stable logical identity of the captured region.
            RegionKey key,
            // Exact captured target geometry in slide coordinates.
            Geometry geometrySlide,
            // Captured image plane containing the target geometry.
            SourceIdentityCodec.ImagePlaneIndex plane,
            // Digest of the canonical captured target geometry coordinates.
            String geometryDigest) {
        public TargetRegion {
            key = Objects.requireNonNull(key, "key");
            geometrySlide = copyCanonicalGeometry(geometrySlide, "geometry_slide");
            plane = Objects.requireNonNull(plane, "plane");
            geometryDigest = CanonicalCodec.requireSha256(geometryDigest, "geometry_digest");
            if (!geometryDigest.equals(CanonicalGeometryCodec.canonicalDigest(geometrySlide))) {
                throw new IllegalArgumentException(
                        "geometry_digest does not match target region geometry");
            }
        }

        @Override
        public Geometry geometrySlide() {
            return geometrySlide.copy();
        }
    }

    /** Complete target and optional source-parent geometry used for scope acceptance. */
    public record ScopeAcceptance(
            // Captured logical target that accepts candidate interior points.
            TargetRegion target,
            // Exact source-parent geometry, present only for a parent scope.
            Optional<Geometry> parentGeometrySlide) {
        public ScopeAcceptance {
            target = Objects.requireNonNull(target, "target");
            parentGeometrySlide =
                    Objects.requireNonNull(parentGeometrySlide, "parent_geometry_slide")
                            .map(
                                    geometry ->
                                            copyCanonicalGeometry(
                                                    geometry, "parent_geometry_slide"));
        }

        @Override
        public Optional<Geometry> parentGeometrySlide() {
            return parentGeometrySlide.map(Geometry::copy);
        }
    }

    /** Parse one exact logical region key. */
    public static RegionKey parseRegionKey(JsonElement value) {
        JsonObject object = requiredObject(value, "region key");
        String type = requiredString(object, "type");
        return switch (type) {
            case "roi" -> {
                requireFields(object, ROI_REGION_FIELDS, "ROI region key");
                yield new Roi(requiredString(object, "roi_id"));
            }
            case "slide" -> {
                requireFields(object, SLIDE_REGION_FIELDS, "slide region key");
                yield new Slide();
            }
            default -> throw new IllegalArgumentException("unsupported region key type: " + type);
        };
    }

    /** Parse one exact field or parent execution scope. */
    public static ExecutionScope parseExecutionScope(JsonElement value) {
        JsonObject object = requiredObject(value, "execution scope");
        String type = requiredString(object, "type");
        return switch (type) {
            case "field" -> {
                requireFields(object, FIELD_SCOPE_FIELDS, "field scope");
                yield new FieldScope(parseRegionKey(object.get("region")));
            }
            case "parent" -> {
                requireFields(object, PARENT_SCOPE_FIELDS, "parent scope");
                yield new ParentScope(
                        parseRegionKey(object.get("region")), requiredString(object, "parent_id"));
            }
            default ->
                    throw new IllegalArgumentException("unsupported execution scope type: " + type);
        };
    }

    /** Parse the sole logical Fill/Redo output key. */
    public static OutputKey parseOutputKey(JsonElement value) {
        JsonObject object = requireExactObject(value, OUTPUT_KEY_FIELDS, "output key");
        return new OutputKey(
                InferenceIdentityCodec.parseModelStepIdentity(object.get("producer")),
                parseExecutionScope(object.get("scope")));
    }

    /** Parse one complete captured target region and verify its geometry digest. */
    public static TargetRegion parseTargetRegion(JsonElement value) {
        JsonObject object = requireExactObject(value, TARGET_REGION_FIELDS, "target region");
        JsonElement geometry = object.get("geometry_slide");
        if (geometry == null || !geometry.isJsonObject()) {
            throw new IllegalArgumentException("geometry_slide must be a GeoJSON object");
        }
        return new TargetRegion(
                parseRegionKey(object.get("key")),
                CanonicalGeometryCodec.parseGeoJson(geometry.getAsJsonObject()),
                SourceIdentityCodec.parseImagePlaneIndex(object.get("plane")),
                requiredString(object, "geometry_digest"));
    }

    /** Encode one logical region key using exact lower-snake fields and lower-kebab tags. */
    public static JsonObject toJson(RegionKey region) {
        JsonObject value = new JsonObject();
        if (region instanceof Roi roi) {
            value.addProperty("type", "roi");
            value.addProperty("roi_id", roi.roiId());
        } else if (region instanceof Slide) {
            value.addProperty("type", "slide");
        } else {
            throw new IllegalArgumentException("unsupported region key");
        }
        return value;
    }

    /** Encode one execution scope using its closed variant shape. */
    public static JsonObject toJson(ExecutionScope scope) {
        JsonObject value = new JsonObject();
        if (scope instanceof FieldScope field) {
            value.addProperty("type", "field");
            value.add("region", toJson(field.region()));
        } else if (scope instanceof ParentScope parent) {
            value.addProperty("type", "parent");
            value.add("region", toJson(parent.region()));
            value.addProperty("parent_id", parent.parentId());
        } else {
            throw new IllegalArgumentException("unsupported execution scope");
        }
        return value;
    }

    /** Encode one exact logical output key. */
    public static JsonObject toJson(OutputKey output) {
        JsonObject value = new JsonObject();
        value.add("producer", InferenceIdentityCodec.toJson(output.producer()));
        value.add("scope", toJson(output.scope()));
        return value;
    }

    /** Encode one complete captured target region. */
    public static JsonObject toJson(TargetRegion target) {
        JsonObject value = new JsonObject();
        value.add("key", toJson(target.key()));
        value.add("geometry_slide", geometryJson(target.geometrySlide()));
        value.add("plane", SourceIdentityCodec.toJson(target.plane()));
        value.addProperty("geometry_digest", target.geometryDigest());
        return value;
    }

    /** Return the canonical digest used as the normalized generation-store map key. */
    public static String targetRegionDigest(TargetRegion target) {
        return CanonicalCodec.canonicalDigest(toJson(target));
    }

    /** Encode one complete target and optional source-parent acceptance value. */
    public static JsonObject toJson(ScopeAcceptance acceptance) {
        JsonObject value = new JsonObject();
        value.add("target", toJson(acceptance.target()));
        acceptance
                .parentGeometrySlide()
                .ifPresent(geometry -> value.add("parent_geometry_slide", geometryJson(geometry)));
        return value;
    }

    /** Return the canonical binding of one complete scope-acceptance value. */
    public static String scopeAcceptanceDigest(ScopeAcceptance acceptance) {
        return CanonicalCodec.canonicalDigest(toJson(acceptance));
    }

    /** Return the canonical bytes used to order or persist one output key. */
    public static byte[] canonicalBytes(OutputKey output) {
        return CanonicalCodec.canonicalBytes(toJson(output));
    }

    private static Geometry copyCanonicalGeometry(Geometry geometry, String path) {
        Geometry copy = Objects.requireNonNull(geometry, path).copy();
        JsonObject normalized = new JsonObject();
        normalized.addProperty("type", copy.getGeometryType());
        normalized.add("coordinates", CanonicalGeometryCodec.canonicalCoordinates(copy));
        return CanonicalGeometryCodec.parseGeoJson(normalized);
    }

    private static JsonObject geometryJson(Geometry geometry) {
        JsonObject value = new JsonObject();
        value.addProperty("type", geometry.getGeometryType());
        value.add("coordinates", CanonicalGeometryCodec.canonicalCoordinates(geometry));
        return value;
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
        JsonElement element = value.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }
}
