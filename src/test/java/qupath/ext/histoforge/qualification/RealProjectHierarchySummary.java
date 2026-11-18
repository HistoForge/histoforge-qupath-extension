package qupath.ext.histoforge.qualification;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.interfaces.ROI;

/** Aggregate structural and scientific geometry facts from persisted QuPath hierarchies. */
final class RealProjectHierarchySummary {

    private long objects;
    private long objectsWithRoi;
    private long objectsWithoutRoi;
    private long roiPoints;
    private long measurementValues;
    private long nonfiniteMeasurementValues;
    private long metadataEntries;
    private long productMetadataEntries;
    private long namedObjects;
    private long classifiedObjects;
    private long unclassifiedObjects;
    private long rootChildren;
    private long maxDepth;
    private long missingObjectIds;
    private long duplicateObjectIds;
    private long repeatedObjectReferences;
    private long parentMismatches;
    private long emptyRois;
    private long invalidGeometries;
    private long nonpositiveAreaRois;
    private long nonfiniteRoiBounds;
    private long geometryComponents;
    private long multicomponentRois;
    private long geometryHoles;
    private long roisWithHoles;
    private final Set<String> pathClasses = new HashSet<>();
    private final Set<String> productMetadataKeys = new HashSet<>();
    private final Map<String, Long> objectKinds = new TreeMap<>();
    private final Map<String, Long> roiTypes = new TreeMap<>();
    private final Map<String, Long> geometryTypes = new TreeMap<>();
    private final Map<String, Long> classificationGeometryTypes = new TreeMap<>();
    private final Map<String, Long> planes = new TreeMap<>();
    private final Map<String, Long> parentChildClassification = new TreeMap<>();

    static RealProjectHierarchySummary audit(PathObjectHierarchy hierarchy) {
        RealProjectHierarchySummary counts = new RealProjectHierarchySummary();
        PathObject root = hierarchy.getRootObject();
        counts.rootChildren = root.nChildObjects();
        Set<PathObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<UUID> identifiers = new HashSet<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        for (PathObject child : root.getChildObjects()) {
            pending.addLast(new Node(child, root, 1));
        }
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            PathObject object = node.object();
            if (!seen.add(object)) {
                counts.repeatedObjectReferences++;
                continue;
            }
            counts.objects++;
            counts.maxDepth = Math.max(counts.maxDepth, node.depth());
            if (object.getParent() != node.expectedParent()) {
                counts.parentMismatches++;
            }
            UUID identifier = object.getID();
            if (identifier == null) {
                counts.missingObjectIds++;
            } else if (!identifiers.add(identifier)) {
                counts.duplicateObjectIds++;
            }

            String objectClassification = classification(object);
            if (object.getPathClass() == null) {
                counts.unclassifiedObjects++;
            } else {
                counts.classifiedObjects++;
                counts.pathClasses.add(object.getPathClass().toString());
            }
            increment(counts.objectKinds, objectKind(object));
            increment(
                    counts.parentChildClassification,
                    classification(node.expectedParent()) + " -> " + objectClassification);
            if (object.getName() != null && !object.getName().isBlank()) {
                counts.namedObjects++;
            }
            counts.measurementValues =
                    Math.addExact(counts.measurementValues, object.getMeasurementList().size());
            for (double value : object.getMeasurementList().values()) {
                if (!Double.isFinite(value)) {
                    counts.nonfiniteMeasurementValues++;
                }
            }
            counts.metadataEntries =
                    Math.addExact(counts.metadataEntries, object.getMetadata().size());
            object.getMetadata().keySet().stream()
                    .filter(key -> key.startsWith("histoforge:") || key.startsWith("repropath:"))
                    .forEach(
                            key -> {
                                counts.productMetadataEntries++;
                                counts.productMetadataKeys.add(key);
                            });

            ROI roi = object.getROI();
            if (roi == null) {
                counts.objectsWithoutRoi++;
            } else {
                auditRoi(roi, objectClassification, counts);
            }
            for (PathObject child : object.getChildObjects()) {
                pending.addLast(new Node(child, object, node.depth() + 1));
            }
        }
        return counts;
    }

    void add(RealProjectHierarchySummary other) {
        objects = Math.addExact(objects, other.objects);
        objectsWithRoi = Math.addExact(objectsWithRoi, other.objectsWithRoi);
        objectsWithoutRoi = Math.addExact(objectsWithoutRoi, other.objectsWithoutRoi);
        roiPoints = Math.addExact(roiPoints, other.roiPoints);
        measurementValues = Math.addExact(measurementValues, other.measurementValues);
        nonfiniteMeasurementValues =
                Math.addExact(nonfiniteMeasurementValues, other.nonfiniteMeasurementValues);
        metadataEntries = Math.addExact(metadataEntries, other.metadataEntries);
        productMetadataEntries =
                Math.addExact(productMetadataEntries, other.productMetadataEntries);
        namedObjects = Math.addExact(namedObjects, other.namedObjects);
        classifiedObjects = Math.addExact(classifiedObjects, other.classifiedObjects);
        unclassifiedObjects = Math.addExact(unclassifiedObjects, other.unclassifiedObjects);
        rootChildren = Math.addExact(rootChildren, other.rootChildren);
        maxDepth = Math.max(maxDepth, other.maxDepth);
        missingObjectIds = Math.addExact(missingObjectIds, other.missingObjectIds);
        duplicateObjectIds = Math.addExact(duplicateObjectIds, other.duplicateObjectIds);
        repeatedObjectReferences =
                Math.addExact(repeatedObjectReferences, other.repeatedObjectReferences);
        parentMismatches = Math.addExact(parentMismatches, other.parentMismatches);
        emptyRois = Math.addExact(emptyRois, other.emptyRois);
        invalidGeometries = Math.addExact(invalidGeometries, other.invalidGeometries);
        nonpositiveAreaRois = Math.addExact(nonpositiveAreaRois, other.nonpositiveAreaRois);
        nonfiniteRoiBounds = Math.addExact(nonfiniteRoiBounds, other.nonfiniteRoiBounds);
        geometryComponents = Math.addExact(geometryComponents, other.geometryComponents);
        multicomponentRois = Math.addExact(multicomponentRois, other.multicomponentRois);
        geometryHoles = Math.addExact(geometryHoles, other.geometryHoles);
        roisWithHoles = Math.addExact(roisWithHoles, other.roisWithHoles);
        pathClasses.addAll(other.pathClasses);
        productMetadataKeys.addAll(other.productMetadataKeys);
        merge(objectKinds, other.objectKinds);
        merge(roiTypes, other.roiTypes);
        merge(geometryTypes, other.geometryTypes);
        merge(classificationGeometryTypes, other.classificationGeometryTypes);
        merge(planes, other.planes);
        merge(parentChildClassification, other.parentChildClassification);
    }

    JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.addProperty("objects", objects);
        result.addProperty("objects_with_roi", objectsWithRoi);
        result.addProperty("objects_without_roi", objectsWithoutRoi);
        result.addProperty("roi_points", roiPoints);
        result.addProperty("measurement_values", measurementValues);
        result.addProperty("nonfinite_measurement_values", nonfiniteMeasurementValues);
        result.addProperty("metadata_entries", metadataEntries);
        result.addProperty("product_metadata_entries", productMetadataEntries);
        result.addProperty("distinct_product_metadata_keys", productMetadataKeys.size());
        result.addProperty("named_objects", namedObjects);
        result.addProperty("classified_objects", classifiedObjects);
        result.addProperty("unclassified_objects", unclassifiedObjects);
        result.addProperty("distinct_path_classes", pathClasses.size());
        result.addProperty("root_children", rootChildren);
        result.addProperty("max_depth", maxDepth);
        result.addProperty("missing_object_ids", missingObjectIds);
        result.addProperty("duplicate_object_ids", duplicateObjectIds);
        result.addProperty("repeated_object_references", repeatedObjectReferences);
        result.addProperty("parent_mismatches", parentMismatches);
        result.addProperty("empty_rois", emptyRois);
        result.addProperty("invalid_geometries", invalidGeometries);
        result.addProperty("nonpositive_area_rois", nonpositiveAreaRois);
        result.addProperty("nonfinite_roi_bounds", nonfiniteRoiBounds);
        result.addProperty("geometry_components", geometryComponents);
        result.addProperty("multicomponent_rois", multicomponentRois);
        result.addProperty("geometry_holes", geometryHoles);
        result.addProperty("rois_with_holes", roisWithHoles);
        result.add("object_kinds", mapJson(objectKinds));
        result.add("roi_types", mapJson(roiTypes));
        result.add("geometry_types", mapJson(geometryTypes));
        result.add("classification_geometry_types", mapJson(classificationGeometryTypes));
        result.add("planes", mapJson(planes));
        result.add("parent_child_classification", mapJson(parentChildClassification));
        return result;
    }

    private static void auditRoi(
            ROI roi, String objectClassification, RealProjectHierarchySummary counts) {
        counts.objectsWithRoi++;
        counts.roiPoints = Math.addExact(counts.roiPoints, roi.getNumPoints());
        increment(counts.roiTypes, roi.getRoiType().name());
        increment(counts.planes, "c=" + roi.getC() + ",z=" + roi.getZ() + ",t=" + roi.getT());
        if (roi.isEmpty()) {
            counts.emptyRois++;
        }
        if (!finiteBounds(roi)) {
            counts.nonfiniteRoiBounds++;
        }
        Geometry geometry = roi.getGeometry();
        increment(counts.geometryTypes, geometry.getGeometryType());
        increment(
                counts.classificationGeometryTypes,
                objectClassification + " -> " + geometry.getGeometryType());
        int components = geometry.getNumGeometries();
        counts.geometryComponents = Math.addExact(counts.geometryComponents, components);
        if (components > 1) {
            counts.multicomponentRois++;
        }
        int holes = 0;
        for (int index = 0; index < components; index++) {
            if (geometry.getGeometryN(index) instanceof Polygon polygon) {
                holes = Math.addExact(holes, polygon.getNumInteriorRing());
            }
        }
        counts.geometryHoles = Math.addExact(counts.geometryHoles, holes);
        if (holes > 0) {
            counts.roisWithHoles++;
        }
        if (!geometry.isValid()) {
            counts.invalidGeometries++;
        }
        if (roi.isArea() && !(roi.getArea() > 0.0)) {
            counts.nonpositiveAreaRois++;
        }
    }

    private static boolean finiteBounds(ROI roi) {
        return Double.isFinite(roi.getBoundsX())
                && Double.isFinite(roi.getBoundsY())
                && Double.isFinite(roi.getBoundsWidth())
                && Double.isFinite(roi.getBoundsHeight());
    }

    private static String classification(PathObject object) {
        return object == null || object.isRootObject()
                ? "root"
                : object.getPathClass() == null ? "unclassified" : "classified";
    }

    private static String objectKind(PathObject object) {
        if (object.isTMACore()) return "tma-core";
        if (object.isCell()) return "cell";
        if (object.isTile()) return "tile";
        if (object.isDetection()) return "detection";
        if (object.isAnnotation()) return "annotation";
        return object.getClass().getSimpleName();
    }

    private static void increment(Map<String, Long> counts, String key) {
        counts.merge(key, 1L, Math::addExact);
    }

    private static JsonObject mapJson(Map<String, Long> values) {
        JsonObject result = new JsonObject();
        values.forEach(result::addProperty);
        return result;
    }

    private static void merge(Map<String, Long> target, Map<String, Long> source) {
        source.forEach((key, count) -> target.merge(key, count, Math::addExact));
    }

    private record Node(PathObject object, PathObject expectedParent, int depth) {}
}
