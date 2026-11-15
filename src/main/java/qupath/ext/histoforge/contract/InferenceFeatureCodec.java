package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

/** Closed validator for deterministic backend inference features. */
public final class InferenceFeatureCodec {

    private static final Set<String> COLLECTION_FIELDS = Set.of("type", "features");
    private static final Set<String> FEATURE_FIELDS =
            Set.of("type", "id", "geometry", "properties");
    private static final Set<String> PROPERTY_FIELDS = Set.of("object_type", "path_class");

    private InferenceFeatureCodec() {}

    /** A validated prediction in raster coordinates, owning its geometry and size counts. */
    public static final class Feature {
        private final String id;
        private final Geometry geometry;
        private final String pathClass;
        private final long recordBytes;
        private final long vertices;

        public Feature(String id, Geometry geometry, String pathClass) {
            this.id = CanonicalCodec.requireSha256(id, "feature.id");
            this.geometry = Objects.requireNonNull(geometry, "feature.geometry").copy();
            this.pathClass = CanonicalCodec.requirePathClassName(pathClass, "feature.path_class");
            JsonArray coordinates = CanonicalGeometryCodec.canonicalCoordinates(this.geometry);
            this.vertices = CanonicalGeometryCodec.vertexCount(this.geometry, coordinates);
            this.recordBytes =
                    recordOverhead(this.geometry.getGeometryType(), this.pathClass)
                            + CanonicalCodec.canonicalBytes(coordinates).length;
        }

        // Validated records may transfer geometry ownership without copying or revalidating it.
        private Feature(
                String id, Geometry geometry, String pathClass, long recordBytes, long vertices) {
            this.id = id;
            this.geometry = geometry;
            this.pathClass = pathClass;
            this.recordBytes = recordBytes;
            this.vertices = vertices;
        }

        public String id() {
            return id;
        }

        public Geometry geometry() {
            return geometry.copy();
        }

        public String pathClass() {
            return pathClass;
        }

        Envelope bounds() {
            return geometry.getEnvelopeInternal();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Feature feature
                    && id.equals(feature.id)
                    && Objects.equals(geometry, feature.geometry)
                    && pathClass.equals(feature.pathClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, geometry, pathClass);
        }

        @Override
        public String toString() {
            return "Feature[id=" + id + ", geometry=" + geometry + ", pathClass=" + pathClass + "]";
        }
    }

    /** A complete validated collection in deterministic feature-ID order. */
    public record FeatureCollection(List<Feature> features) {
        public FeatureCollection {
            features = List.copyOf(features);
        }
    }

    /** Validate the complete closed feature collection against model-declared PathClasses. */
    public static FeatureCollection validate(JsonObject value, Set<String> declaredClasses) {
        requireFields(value, COLLECTION_FIELDS, "feature_collection");
        if (!"FeatureCollection".equals(requiredString(value.get("type"), "type"))) {
            throw new IllegalArgumentException("feature_collection.type must be FeatureCollection");
        }
        JsonArray values = requiredArray(value.get("features"), "feature_collection.features");
        List<Feature> features = new ArrayList<>();
        RecordValidator validator = new RecordValidator(declaredClasses);
        for (int index = 0; index < values.size(); index++) {
            features.add(validator.validate(values.get(index), index));
        }
        validator.finish(() -> {});
        return new FeatureCollection(features);
    }

    /**
     * Validate records individually, retaining only hashes and occurrence counts for ID checks.
     * Callers choose whether to retain each returned geometry or release it after counting.
     */
    static final class RecordValidator {
        private final Set<String> declared;
        private final Map<String, FeatureIdOccurrences> occurrencesByFirstId = new HashMap<>();
        private final Map<RecordShape, Long> recordOverheads = new HashMap<>();
        private String priorId;

        RecordValidator(Set<String> declaredClasses) {
            declared = declaredClasses(declaredClasses);
        }

        Feature validate(JsonElement value, long index) {
            String path = "feature_collection.features[" + index + "]";
            JsonObject feature = requiredObject(value, path);
            requireFields(feature, FEATURE_FIELDS, path);
            if (!"Feature".equals(requiredString(feature.get("type"), path + ".type"))) {
                throw new IllegalArgumentException(path + ".type must be Feature");
            }

            String id =
                    CanonicalCodec.requireSha256(
                            requiredString(feature.get("id"), path + ".id"), path + ".id");
            if (id.equals(priorId)) {
                throw new IllegalArgumentException(
                        "feature_collection contains a duplicate feature id");
            }
            if (priorId != null && priorId.compareTo(id) >= 0) {
                throw new IllegalArgumentException(
                        "feature_collection.features must be in ascending id order");
            }
            priorId = id;

            JsonObject geometryValue = requiredObject(feature.get("geometry"), path + ".geometry");
            var parsed = CanonicalGeometryCodec.parseGeoJsonWithCoordinates(geometryValue);
            Geometry geometry = parsed.geometry();
            byte[] coordinates = CanonicalCodec.canonicalBytes(parsed.coordinates());
            if (!Arrays.equals(
                    CanonicalCodec.canonicalBytes(geometryValue.get("coordinates")), coordinates)) {
                throw new IllegalArgumentException(
                        path + ".geometry must use canonical coordinates");
            }

            JsonObject properties = requiredObject(feature.get("properties"), path + ".properties");
            requireFields(properties, PROPERTY_FIELDS, path + ".properties");
            if (!"DETECTION"
                    .equals(
                            requiredString(
                                    properties.get("object_type"),
                                    path + ".properties.object_type"))) {
                throw new IllegalArgumentException(
                        path + ".properties.object_type must be DETECTION");
            }
            String pathClass =
                    CanonicalCodec.requirePathClassName(
                            requiredString(
                                    properties.get("path_class"), path + ".properties.path_class"),
                            path + ".properties.path_class");
            if (!declared.contains(pathClass)) {
                throw new IllegalArgumentException(
                        path + ".properties.path_class is not declared by the model");
            }
            String encodedClass =
                    new String(
                            CanonicalCodec.canonicalBytes(new JsonPrimitive(pathClass)),
                            StandardCharsets.UTF_8);
            // Class and coordinates are already canonical JSON; only the ordinal is missing.
            String prefix =
                    "["
                            + encodedClass
                            + ","
                            + new String(coordinates, StandardCharsets.UTF_8)
                            + ",";
            String firstOccurrenceId = occurrenceId(prefix, 0);
            // The occurrence-zero ID identifies the class/shape without retaining its coordinates.
            // As with feature identities themselves, this relies on SHA-256 collision resistance.
            var occurrences =
                    occurrencesByFirstId.computeIfAbsent(
                            firstOccurrenceId, ignored -> new FeatureIdOccurrences());
            String expectedId =
                    occurrences.occurrenceCount == 0
                            ? firstOccurrenceId
                            : occurrenceId(prefix, occurrences.occurrenceCount);
            occurrences.accept(expectedId, id);
            long overhead =
                    recordOverheads.computeIfAbsent(
                            new RecordShape(geometry.getGeometryType(), pathClass),
                            shape -> recordOverhead(shape.geometryType(), shape.pathClass()));
            return new Feature(
                    id,
                    geometry,
                    pathClass,
                    overhead + coordinates.length,
                    CanonicalGeometryCodec.vertexCount(geometry, parsed.coordinates()));
        }

        void finish(Runnable checkCancellation) {
            checkCancellation.run();
            for (var occurrences : occurrencesByFirstId.values()) {
                checkCancellation.run();
                if (occurrences.unmatchedIdCounts != null) {
                    throw new IllegalArgumentException(
                            "feature_collection ids do not match their class and geometry");
                }
            }
            occurrencesByFirstId.clear();
        }
    }

    /** Recreate one feature from the exact bytes already checked by {@link RecordValidator}. */
    static Feature readValidatedRecord(JsonObject feature, long recordBytes) {
        JsonObject geometryValue = feature.getAsJsonObject("geometry");
        Geometry geometry = CanonicalGeometryCodec.parsePolygonCoordinates(geometryValue);
        return new Feature(
                feature.get("id").getAsString(),
                geometry,
                feature.getAsJsonObject("properties").get("path_class").getAsString(),
                recordBytes,
                CanonicalGeometryCodec.vertexCount(
                        geometry, geometryValue.getAsJsonArray("coordinates")));
    }

    /** Encode one validated collection using the exact fixed GeoJSON feature shape. */
    public static JsonObject toJson(FeatureCollection collection) {
        JsonArray features = new JsonArray();
        for (Feature feature : collection.features()) {
            features.add(toJson(feature));
        }
        JsonObject result = new JsonObject();
        result.addProperty("type", "FeatureCollection");
        result.add("features", features);
        return result;
    }

    /** Size of the complete canonical feature, calculated when its geometry is validated. */
    static long canonicalRecordBytes(Feature feature) {
        return feature.recordBytes;
    }

    /** Count polygon vertices without each ring's repeated closing coordinate. */
    static long vertexCount(Feature feature) {
        return feature.vertices;
    }

    private static JsonObject toJson(Feature feature) {
        return featureJson(
                feature.id,
                feature.geometry.getGeometryType(),
                CanonicalGeometryCodec.canonicalCoordinates(feature.geometry),
                feature.pathClass);
    }

    private static JsonObject featureJson(
            String id, String geometryType, JsonArray coordinates, String pathClass) {
        JsonObject geometry = new JsonObject();
        geometry.addProperty("type", geometryType);
        geometry.add("coordinates", coordinates);

        JsonObject properties = new JsonObject();
        properties.addProperty("object_type", "DETECTION");
        properties.addProperty("path_class", pathClass);

        JsonObject value = new JsonObject();
        value.addProperty("type", "Feature");
        value.addProperty("id", id);
        value.add("geometry", geometry);
        value.add("properties", properties);
        return value;
    }

    private static long recordOverhead(String geometryType, String pathClass) {
        // IDs are always 64 ASCII characters; replace only the empty coordinate array's two bytes.
        return CanonicalCodec.canonicalBytes(
                                featureJson(
                                        "0".repeat(64), geometryType, new JsonArray(), pathClass))
                        .length
                - 2L;
    }

    private static String occurrenceId(String canonicalPrefix, long occurrence) {
        return CanonicalCodec.sha256(
                (canonicalPrefix + occurrence + "]").getBytes(StandardCharsets.UTF_8));
    }

    /** Reconcile one class/shape's IDs when hash order differs from occurrence order. */
    private static final class FeatureIdOccurrences {
        private long occurrenceCount;
        private Map<String, Long> unmatchedIdCounts;

        void accept(String expectedId, String observedId) {
            occurrenceCount = Math.addExact(occurrenceCount, 1L);
            if (expectedId.equals(observedId)) return;
            if (unmatchedIdCounts == null) unmatchedIdCounts = new HashMap<>();
            // Expected IDs add one; observed IDs subtract one. Remove only matched counts.
            adjust(expectedId, 1L);
            adjust(observedId, -1L);
            // Keep the count even when a prefix balances: later duplicates need the next ordinal.
            if (unmatchedIdCounts.isEmpty()) unmatchedIdCounts = null;
        }

        private void adjust(String id, long change) {
            long balance = Math.addExact(unmatchedIdCounts.getOrDefault(id, 0L), change);
            if (balance == 0) {
                unmatchedIdCounts.remove(id);
            } else {
                unmatchedIdCounts.put(id, balance);
            }
        }
    }

    private static Set<String> declaredClasses(Set<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("declared_classes must not be null");
        }
        Set<String> result = new HashSet<>();
        for (String value : values) {
            result.add(CanonicalCodec.requirePathClassName(value, "declared_classes"));
        }
        return Set.copyOf(result);
    }

    private static void requireFields(JsonObject value, Set<String> expected, String path) {
        if (value == null || !value.keySet().equals(expected)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }

    private record RecordShape(String geometryType, String pathClass) {}
}
