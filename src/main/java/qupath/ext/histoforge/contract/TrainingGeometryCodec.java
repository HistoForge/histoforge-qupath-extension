package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;

/** Exact GeoJSON members stored for one training ROI and its target objects. */
public final class TrainingGeometryCodec {

    private static final Set<String> FEATURE_FIELDS = Set.of("type", "geometry", "properties");
    private static final Set<String> COLLECTION_FIELDS = Set.of("type", "features");
    private static final Set<String> OBJECT_PROPERTIES =
            Set.of("source_object_uuid", "object_type", "path_class");
    private static final Set<String> OBJECT_TYPES = Set.of("annotation", "detection");

    private TrainingGeometryCodec() {}

    /** One complete target geometry captured from a persistent QuPath object. */
    public record TrainingObject(
            String sourceObjectUuid, String objectType, String pathClass, Geometry geometry) {
        public TrainingObject {
            sourceObjectUuid =
                    CanonicalCodec.requireCanonicalUuid(sourceObjectUuid, "source_object_uuid");
            if (!OBJECT_TYPES.contains(objectType)) {
                throw new IllegalArgumentException("unsupported training object_type");
            }
            pathClass = CanonicalCodec.requirePathClassName(pathClass, "path_class");
            geometry = canonicalGeometry(geometry, "training object geometry");
        }

        @Override
        public Geometry geometry() {
            return geometry.copy();
        }
    }

    /** Encode one training ROI as a GeoJSON Feature with empty properties. */
    public static JsonObject roiFeature(Geometry geometry) {
        Geometry canonical = canonicalGeometry(geometry, "training ROI geometry");
        JsonObject feature = new JsonObject();
        feature.addProperty("type", "Feature");
        feature.add("geometry", geometryJson(canonical));
        feature.add("properties", new JsonObject());
        return feature;
    }

    /** Parse and normalize one exact training ROI feature. */
    public static Geometry parseRoiFeature(JsonElement value) {
        JsonObject feature =
                TrainingJson.exactObject(value, FEATURE_FIELDS, "training ROI feature");
        if (!"Feature".equals(TrainingJson.string(feature, "type"))) {
            throw new IllegalArgumentException("training ROI must have type Feature");
        }
        JsonObject properties =
                TrainingJson.object(feature.get("properties"), "training ROI properties");
        if (!properties.keySet().isEmpty()) {
            throw new IllegalArgumentException("training ROI properties must be empty");
        }
        return CanonicalGeometryCodec.parseGeoJson(
                TrainingJson.object(feature.get("geometry"), "training ROI geometry"));
    }

    /** Encode target objects in persistent-UUID order as one GeoJSON FeatureCollection. */
    public static JsonObject objectCollection(List<TrainingObject> objects) {
        List<TrainingObject> values = normalizedObjects(objects);
        JsonArray features = new JsonArray();
        for (TrainingObject object : values) {
            JsonObject feature = new JsonObject();
            feature.addProperty("type", "Feature");
            feature.add("geometry", geometryJson(object.geometry()));
            JsonObject properties = new JsonObject();
            properties.addProperty("source_object_uuid", object.sourceObjectUuid());
            properties.addProperty("object_type", object.objectType());
            properties.addProperty("path_class", object.pathClass());
            feature.add("properties", properties);
            features.add(feature);
        }
        JsonObject collection = new JsonObject();
        collection.addProperty("type", "FeatureCollection");
        collection.add("features", features);
        return collection;
    }

    /** Parse and normalize one exact target-object FeatureCollection. */
    public static List<TrainingObject> parseObjectCollection(JsonElement value) {
        JsonObject collection =
                TrainingJson.exactObject(value, COLLECTION_FIELDS, "training objects");
        if (!"FeatureCollection".equals(TrainingJson.string(collection, "type"))) {
            throw new IllegalArgumentException("training objects must have type FeatureCollection");
        }
        List<TrainingObject> objects = new ArrayList<>();
        for (JsonElement item : TrainingJson.array(collection.get("features"), "features")) {
            JsonObject feature =
                    TrainingJson.exactObject(item, FEATURE_FIELDS, "training object feature");
            if (!"Feature".equals(TrainingJson.string(feature, "type"))) {
                throw new IllegalArgumentException("training object must have type Feature");
            }
            JsonObject properties =
                    TrainingJson.exactObject(
                            feature.get("properties"),
                            OBJECT_PROPERTIES,
                            "training object properties");
            objects.add(
                    new TrainingObject(
                            TrainingJson.string(properties, "source_object_uuid"),
                            TrainingJson.enumValue(
                                    properties.get("object_type"), OBJECT_TYPES, "object_type"),
                            TrainingJson.string(properties, "path_class"),
                            CanonicalGeometryCodec.parseGeoJson(
                                    TrainingJson.object(
                                            feature.get("geometry"), "training object geometry"))));
        }
        return normalizedObjects(objects);
    }

    private static List<TrainingObject> normalizedObjects(List<TrainingObject> objects) {
        if (objects == null) {
            throw new IllegalArgumentException("training objects must not be null");
        }
        List<TrainingObject> values = new ArrayList<>(objects.size());
        for (TrainingObject object : objects) {
            values.add(Objects.requireNonNull(object, "training object"));
        }
        values.sort(java.util.Comparator.comparing(TrainingObject::sourceObjectUuid));
        Set<String> ids = new HashSet<>();
        for (TrainingObject object : values) {
            if (!ids.add(object.sourceObjectUuid())) {
                throw new IllegalArgumentException("training objects contain duplicate UUIDs");
            }
        }
        return List.copyOf(values);
    }

    private static JsonObject geometryJson(Geometry geometry) {
        JsonObject value = new JsonObject();
        value.addProperty("type", geometry.getGeometryType());
        value.add("coordinates", CanonicalGeometryCodec.canonicalCoordinates(geometry));
        return value;
    }

    private static Geometry canonicalGeometry(Geometry geometry, String path) {
        Geometry copy = Objects.requireNonNull(geometry, path).copy();
        JsonArray coordinates;
        try {
            coordinates = CanonicalGeometryCodec.canonicalCoordinates(copy);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    path + " must be a valid Polygon/MultiPolygon", error);
        }
        if (!(copy.getArea() > 0.0)) {
            throw new IllegalArgumentException(path + " must have positive area");
        }
        JsonObject normalized = new JsonObject();
        normalized.addProperty("type", copy.getGeometryType());
        normalized.add("coordinates", coordinates);
        return CanonicalGeometryCodec.parseGeoJson(normalized);
    }
}
