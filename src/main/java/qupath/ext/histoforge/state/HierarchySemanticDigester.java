package qupath.ext.histoforge.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.lib.measurements.Measurement;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.PathTileObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.DefaultTMAGrid;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.TMAGrid;
import qupath.lib.roi.interfaces.ROI;

/** Canonically digests the complete supported QuPath hierarchy without mutating lazy state. */
final class HierarchySemanticDigester {

    private static final byte OBJECT_FRAME = 1;
    private static final byte TMA_GRID_FRAME = 2;
    private static final byte[] DIGEST_DOMAIN =
            CanonicalCodec.utf8Bytes("histoforge-image-hierarchy-v1");
    private static final byte[] CHILD_IDS_PREFIX = CanonicalCodec.utf8Bytes("{\"child_ids\":[");
    private static final byte[] CHILD_IDS_SUFFIX = CanonicalCodec.utf8Bytes("],");
    private static final Set<Class<? extends PathObject>> SUPPORTED_OBJECT_TYPES =
            Set.of(
                    PathRootObject.class,
                    PathAnnotationObject.class,
                    PathDetectionObject.class,
                    PathCellObject.class,
                    PathTileObject.class,
                    TMACoreObject.class);
    private static final Set<String> SUPPORTED_ROI_TYPES =
            Set.of(
                    "qupath.lib.roi.RectangleROI",
                    "qupath.lib.roi.EllipseROI",
                    "qupath.lib.roi.LineROI",
                    "qupath.lib.roi.PointsROI",
                    "qupath.lib.roi.PolylineROI",
                    "qupath.lib.roi.PolygonROI",
                    "qupath.lib.roi.AreaROI",
                    "qupath.lib.roi.AWTAreaROI",
                    "qupath.lib.roi.GeometryROI");

    private HierarchySemanticDigester() {}

    /**
     * Digest and resource totals from one deterministic traversal. The identity lookup is populated
     * only when requested to validate saved object connections.
     */
    record Snapshot(String digest, Size size, Map<PathObject, String> objectIdsByIdentity) {}

    /** Resource totals required to reject an oversized capture before hierarchy mutation. */
    record Size(
            // Root plus every persisted descendant PathObject.
            long objects,
            // Main-ROI and cell-nucleus vertices across those objects.
            long geometryVertices,
            // Numeric measurement entries across those objects.
            long measurements,
            // UTF-8 bytes in classes, names, measurement names, and metadata.
            long textUtf8Bytes) {}

    static Snapshot capture(PathObjectHierarchy hierarchy, boolean retainObjectIdsByIdentity) {
        MessageDigest digest = ImageDataRevisionDigester.newSha256();
        digest.update(DIGEST_DOMAIN);
        Map<PathObject, String> objectIdsByIdentity =
                retainObjectIdsByIdentity ? new IdentityHashMap<>() : null;
        Size size = traverse(hierarchy, digest, objectIdsByIdentity);
        return new Snapshot(
                HexFormat.of().formatHex(digest.digest()),
                size,
                objectIdsByIdentity == null
                        ? Map.of()
                        : Collections.unmodifiableMap(objectIdsByIdentity));
    }

    /**
     * Count the hierarchy without constructing semantic hashes. Structural and supported-type
     * checks still apply. When non-null, the supplied identity map receives the visited objects for
     * saved-connection validation; resource-only callers otherwise allocate no such lookup.
     */
    static Size measure(
            PathObjectHierarchy hierarchy, Map<PathObject, String> objectIdsByIdentity) {
        return traverse(hierarchy, null, objectIdsByIdentity);
    }

    private static Size traverse(
            PathObjectHierarchy hierarchy,
            MessageDigest digest,
            Map<PathObject, String> objectIdsByIdentity) {
        Set<UUID> objectIds = new HashSet<>();
        ArrayDeque<PendingChildren> pending = new ArrayDeque<>();
        pending.push(new PendingChildren(null, List.of(hierarchy.getRootObject()).iterator()));
        Map<PathClass, ClassSerialization> serializedClasses =
                digest == null ? null : new IdentityHashMap<>();

        long objects = 0;
        long vertices = 0;
        long measurements = 0;
        long textBytes = 0;
        while (!pending.isEmpty()) {
            PendingChildren next = pending.peek();
            PathObject object = next.children().next();
            if (!next.children().hasNext()) pending.pop();
            requireSupportedObjectType(object);
            if (object.getParent() != next.parent()) {
                throw new IllegalArgumentException(
                        "hierarchy contains an inconsistent parent link");
            }

            UUID id = object.getID();
            if (!objectIds.add(id)) {
                throw new IllegalArgumentException(
                        "hierarchy contains duplicate PathObject UUID " + id);
            }
            String objectId = id.toString();
            if (objectIdsByIdentity != null) {
                objectIdsByIdentity.put(object, objectId);
            }
            PathObject[] children = object.getChildObjectsAsArray();
            if (digest != null) {
                byte[] fields =
                        CanonicalCodec.canonicalBytes(
                                objectState(object, objectId, true, serializedClasses));
                updateObjectFrame(digest, fields, children);
            }

            Size objectSize = measureObject(object);
            objects = addExact(objects, objectSize.objects(), "hierarchy objects");
            vertices = addExact(vertices, objectSize.geometryVertices(), "hierarchy vertices");
            measurements =
                    addExact(measurements, objectSize.measurements(), "hierarchy measurements");
            textBytes = addExact(textBytes, objectSize.textUtf8Bytes(), "hierarchy text bytes");

            if (children.length != 0) {
                // Preserve depth-first child order with one frame per level, not per child.
                pending.push(new PendingChildren(object, Arrays.asList(children).iterator()));
            }
        }

        JsonObject gridState = tmaGridState(hierarchy.getTMAGrid(), objectIds);
        if (digest != null) {
            updateFrame(digest, TMA_GRID_FRAME, CanonicalCodec.canonicalBytes(gridState));
        }
        return new Size(objects, vertices, measurements, textBytes);
    }

    /** Measure one supported object's exact contribution to the hierarchy resource totals. */
    static Size measureObject(PathObject object) {
        requireSupportedObjectType(object);
        long vertices = roiVertices(object.getROI());
        if (object instanceof PathCellObject cell) {
            vertices = addExact(vertices, roiVertices(cell.getNucleusROI()), "hierarchy vertices");
        }

        long measurements = 0;
        long textBytes = 0;
        if (object.hasMeasurements()) {
            for (Measurement measurement : object.getMeasurementList().getMeasurements()) {
                measurements = addExact(measurements, 1, "hierarchy measurements");
                textBytes = addUtf8(textBytes, measurement.getName(), "measurement name");
            }
        }
        if (object.getPathClass() != null && !object.isRootObject()) {
            textBytes = addUtf8(textBytes, object.getPathClass().toString(), "PathClass name");
        }
        textBytes = addUtf8(textBytes, object.getName(), "object name");
        if (object.hasMetadata()) {
            for (Map.Entry<String, String> entry : object.getMetadata().entrySet()) {
                textBytes =
                        addUtf8(
                                textBytes,
                                Objects.requireNonNull(entry.getKey(), "metadata key"),
                                "metadata key");
                textBytes = addUtf8(textBytes, entry.getValue(), "metadata value");
            }
        }
        return new Size(1, vertices, measurements, textBytes);
    }

    /** Return one supported object's complete semantic digest outside a hierarchy traversal. */
    static String objectSemanticDigest(PathObject object) {
        String objectId =
                Objects.requireNonNull(object.getID(), "persistent object UUID").toString();
        requireSupportedObjectType(object);
        byte[] fields = CanonicalCodec.canonicalBytes(objectState(object, objectId, true, null));
        PathObject[] children = object.getChildObjectsAsArray();
        return CanonicalCodec.sha256Incrementally(
                digest -> updateObjectState(digest, fields, children));
    }

    /**
     * Return one supported object's semantic digest with classification fields omitted.
     *
     * <p>This is the exact class-only script barrier: object type, UUID, geometry, name, color,
     * lock state, measurements, metadata, parent, and child ordering remain covered, while the
     * PathClass and its coupled probability may change.
     */
    static String objectNonClassSemanticDigest(PathObject object) {
        String objectId =
                Objects.requireNonNull(object.getID(), "persistent object UUID").toString();
        requireSupportedObjectType(object);
        byte[] fields = CanonicalCodec.canonicalBytes(objectState(object, objectId, false, null));
        PathObject[] children = object.getChildObjectsAsArray();
        return CanonicalCodec.sha256Incrementally(
                digest -> updateObjectState(digest, fields, children));
    }

    /**
     * Return one object's transaction-local state without its complete child list.
     *
     * <p>The object's own parent remains covered. Child membership is represented by each selected
     * child's parent field, so an out-of-domain child does not make an otherwise unrelated ROI edit
     * conflict.
     */
    static String objectTransactionSemanticDigest(PathObject object) {
        String objectId =
                Objects.requireNonNull(object.getID(), "persistent object UUID").toString();
        requireSupportedObjectType(object);
        JsonObject state = objectState(object, objectId, true, null);
        // Child order is omitted here, but malformed child identities still invalidate capture.
        for (PathObject child : object.getChildObjectsAsArray()) {
            Objects.requireNonNull(child.getID(), "persistent child UUID");
        }
        if (object.getParent() == null || object.getParent().isRootObject()) {
            state.add("parent_id", JsonNull.INSTANCE);
        }
        return CanonicalCodec.canonicalDigest(state);
    }

    private static JsonObject objectState(
            PathObject object,
            String objectId,
            boolean includeClassification,
            Map<PathClass, ClassSerialization> serializedClasses) {
        JsonObject state = new JsonObject();
        state.addProperty("id", objectId);
        state.addProperty("type", objectType(object));
        addNullableString(state, "name", object.getName());
        addNullableInteger(state, "color", object.getColor());

        if (object.isRootObject()) {
            state.add("locked", JsonNull.INSTANCE);
            if (includeClassification) {
                state.add("path_class_serialization_sha256", JsonNull.INSTANCE);
                state.add("class_probability_bits", JsonNull.INSTANCE);
            }
            state.add("roi_serialization_sha256", JsonNull.INSTANCE);
        } else {
            state.addProperty("locked", object.isLocked());
            if (includeClassification) {
                addNullableString(
                        state,
                        "path_class_serialization_sha256",
                        classDigest(object.getPathClass(), serializedClasses));
                state.addProperty(
                        "class_probability_bits", doubleBits(object.getClassProbability()));
            }
            addNullableRoiDigest(state, "roi_serialization_sha256", object.getROI(), "ROI");
        }
        if (object instanceof PathCellObject cell) {
            addNullableRoiDigest(
                    state, "nucleus_roi_serialization_sha256", cell.getNucleusROI(), "nucleus ROI");
        } else {
            state.add("nucleus_roi_serialization_sha256", JsonNull.INSTANCE);
        }
        if (object instanceof TMACoreObject core) {
            state.addProperty("tma_missing", core.isMissing());
        } else {
            state.add("tma_missing", JsonNull.INSTANCE);
        }

        JsonArray measurementValues = new JsonArray();
        if (object.hasMeasurements()) {
            for (Measurement measurement : object.getMeasurementList().getMeasurements()) {
                JsonObject value = new JsonObject();
                value.addProperty("name", measurement.getName());
                value.addProperty("value_bits", doubleBits(measurement.getValue()));
                measurementValues.add(value);
            }
        }
        state.add("measurements", measurementValues);

        JsonObject metadata = new JsonObject();
        if (object.hasMetadata()) {
            new TreeMap<>(object.getMetadata()).forEach(metadata::addProperty);
        }
        state.add("metadata", metadata);

        PathObject parent = object.getParent();
        addNullableString(state, "parent_id", parent == null ? null : parent.getID().toString());
        return state;
    }

    private static void updateObjectFrame(
            MessageDigest digest, byte[] fields, PathObject[] children) {
        // Each canonical UUID is 36 ASCII bytes, plus quotes and a separating comma except last.
        long childBytes = children.length == 0 ? 0 : 39L * children.length - 1;
        long length =
                addExact(
                        childBytes,
                        CHILD_IDS_PREFIX.length
                                + CHILD_IDS_SUFFIX.length
                                + (long) fields.length
                                - 1,
                        "hierarchy object frame bytes");
        digest.update(OBJECT_FRAME);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(length).array());
        updateObjectState(digest, fields, children);
    }

    /**
     * Stream the child UUID list without building a JSON tree or complete JSON text for that list.
     * In this fixed object schema, child_ids is the first JCS key. All other fields still use the
     * canonical encoder; skip only their opening brace when appending them after the child array.
     */
    private static void updateObjectState(
            MessageDigest digest, byte[] fields, PathObject[] children) {
        digest.update(CHILD_IDS_PREFIX);
        boolean first = true;
        for (PathObject child : children) {
            UUID id = Objects.requireNonNull(child.getID(), "persistent child UUID");
            if (!first) {
                digest.update((byte) ',');
            }
            first = false;
            digest.update((byte) '"');
            digest.update(CanonicalCodec.utf8Bytes(id.toString()));
            digest.update((byte) '"');
        }
        digest.update(CHILD_IDS_SUFFIX);
        digest.update(fields, 1, fields.length - 1);
    }

    private static JsonObject tmaGridState(TMAGrid grid, Set<UUID> objectIds) {
        JsonObject state = new JsonObject();
        if (grid == null) {
            state.addProperty("present", false);
            return state;
        }
        if (grid.getClass() != DefaultTMAGrid.class) {
            throw new IllegalArgumentException(
                    "unsupported persisted TMAGrid type " + grid.getClass().getName());
        }
        state.addProperty("present", true);
        state.addProperty("grid_width", grid.getGridWidth());
        state.addProperty("grid_height", grid.getGridHeight());
        JsonArray coreIds = new JsonArray();
        for (TMACoreObject core : grid.getTMACoreList()) {
            UUID id = core.getID();
            if (!objectIds.contains(id)) {
                throw new IllegalArgumentException(
                        "TMA grid references a core outside the hierarchy");
            }
            coreIds.add(id.toString());
        }
        state.add("core_ids", coreIds);
        return state;
    }

    private static String objectType(PathObject object) {
        return switch (object) {
            case PathRootObject ignored -> "root";
            case PathAnnotationObject ignored -> "annotation";
            case PathCellObject ignored -> "cell";
            case PathTileObject ignored -> "tile";
            case TMACoreObject ignored -> "tma-core";
            case PathDetectionObject ignored -> "detection";
            default ->
                    throw new IllegalArgumentException(
                            "unsupported persisted PathObject type " + object.getClass().getName());
        };
    }

    private static void requireSupportedObjectType(PathObject object) {
        if (!SUPPORTED_OBJECT_TYPES.contains(object.getClass())) {
            throw new IllegalArgumentException(
                    "unsupported persisted PathObject type " + object.getClass().getName());
        }
    }

    private static long roiVertices(ROI roi) {
        requireSupportedRoiType(roi);
        return roi == null ? 0 : roi.getAllPoints().size();
    }

    private static void requireSupportedRoiType(ROI roi) {
        if (roi != null && !SUPPORTED_ROI_TYPES.contains(roi.getClass().getName())) {
            throw new IllegalArgumentException(
                    "unsupported persisted ROI type " + roi.getClass().getName());
        }
    }

    private static long addUtf8(long current, String value, String path) {
        return value == null
                ? current
                : addExact(current, CanonicalCodec.utf8Bytes(value).length, path + " bytes");
    }

    private static long addExact(long left, long right, String path) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(path + " exceed signed 64-bit range", e);
        }
    }

    private static void addNullableString(JsonObject target, String key, String value) {
        if (value == null) {
            target.add(key, JsonNull.INSTANCE);
        } else {
            target.addProperty(key, value);
        }
    }

    private static void addNullableInteger(JsonObject target, String key, Integer value) {
        if (value == null) {
            target.add(key, JsonNull.INSTANCE);
        } else {
            target.addProperty(key, value);
        }
    }

    private static String classDigest(
            PathClass value, Map<PathClass, ClassSerialization> serializedClasses) {
        if (value == null) return null;
        if (serializedClasses == null)
            return ImageDataRevisionDigester.serializedComponentDigest(value, "PathClass");
        return classSerialization(value, serializedClasses).digest();
    }

    /**
     * PathClass names and parents are immutable; colors are not. Check the entire parent chain
     * before reusing its exact serialized digest. The map lives only for this traversal, including
     * stored-image captures whose shared classes can also be used by the live viewer.
     */
    private static ClassSerialization classSerialization(
            PathClass value, Map<PathClass, ClassSerialization> serializedClasses) {
        var parent =
                value.getParentClass() == null
                        ? null
                        : classSerialization(value.getParentClass(), serializedClasses);
        Integer color = value.getColor();
        var previous = serializedClasses.get(value);
        if (previous != null
                && previous.parent() == parent
                && Objects.equals(previous.color(), color)) return previous;
        var current =
                new ClassSerialization(
                        color,
                        parent,
                        ImageDataRevisionDigester.serializedComponentDigest(value, "PathClass"));
        serializedClasses.put(value, current);
        return current;
    }

    @SuppressWarnings("deprecation")
    private static void addNullableRoiDigest(
            JsonObject target, String key, ROI roi, String component) {
        if (roi == null) {
            target.add(key, JsonNull.INSTANCE);
            return;
        }
        requireSupportedRoiType(roi);
        ROI cacheFreeCopy = roi.duplicate();
        if (cacheFreeCopy == null || cacheFreeCopy.getClass() != roi.getClass()) {
            throw new IllegalArgumentException(
                    "persisted ROI did not produce an exact-type semantic copy");
        }
        target.addProperty(
                key, ImageDataRevisionDigester.serializedComponentDigest(cacheFreeCopy, component));
    }

    private static String doubleBits(double value) {
        return HexFormat.of().toHexDigits(Double.doubleToLongBits(value));
    }

    private static void updateFrame(MessageDigest digest, byte type, byte[] value) {
        digest.update(type);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value.length).array());
        digest.update(value);
    }

    private record PendingChildren(PathObject parent, Iterator<PathObject> children) {}

    private record ClassSerialization(Integer color, ClassSerialization parent, String digest) {}
}
