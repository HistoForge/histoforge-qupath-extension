package qupath.ext.histoforge.state;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.InvalidObjectException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.platform.LocalCapacityExceededException;
import qupath.lib.images.ImageData;
import qupath.lib.objects.DefaultPathObjectConnectionGroup;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnectionGroup;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Computes the complete semantic {@link ImageData} revision used by staged-save guards. */
public final class ImageDataRevisionDigester {

    static final int CONTENT_SCHEMA_VERSION = 1;
    private static final byte[] CONNECTION_DIGEST_DOMAIN =
            CanonicalCodec.utf8Bytes("histoforge-image-connections-v1");

    private ImageDataRevisionDigester() {}

    /** Persisted ImageData state cannot be represented by the supported semantic contract. */
    public static final class CorruptImageStateException extends IllegalArgumentException {
        public CorruptImageStateException(RuntimeException cause) {
            super("authoritative ImageData contains unsupported or inconsistent state", cause);
        }
    }

    /**
     * Capture one source-bound semantic revision after enforcing the captured client limits.
     *
     * <p>The caller must hold the image lease and invoke this on the thread that owns the supplied
     * {@code ImageData}. This method performs no IO and does not mutate the hierarchy.
     */
    public static RasterInputCodec.ImageRevisionToken capture(
            String imageId,
            String imageSourceDigest,
            ImageData<?> imageData,
            ExecutionLimitsCodec.ClientExecutionLimits limits) {
        Objects.requireNonNull(limits, "client_execution_limits");
        validateGenerationStore(imageData, limits);
        Fingerprint fingerprint = fingerprint(imageData);
        requireWithinLimits(fingerprint.captureSize(), limits);
        return new RasterInputCodec.ImageRevisionToken(
                imageId,
                imageSourceDigest,
                CanonicalCodec.canonicalDigest(fingerprint.contentPreimage()));
    }

    /** Return the semantic content digest for one authoritative ImageData materialization. */
    static String imageDataContentDigest(ImageData<?> imageData) {
        return CanonicalCodec.canonicalDigest(fingerprint(imageData).contentPreimage());
    }

    /** Return the exact small preimage used by {@link #imageDataContentDigest(ImageData)}. */
    static JsonObject canonicalContentPreimage(ImageData<?> imageData) {
        return fingerprint(imageData).contentPreimage();
    }

    /** Resource totals covered by the client hierarchy limits for one complete capture. */
    public record CaptureSize(
            // Root plus every persisted descendant PathObject.
            long objects,
            // Main-ROI and cell-nucleus vertices across those objects.
            long geometryVertices,
            // Numeric measurement entries across those objects.
            long measurements,
            // UTF-8 bytes in hierarchy text and persisted string ImageData properties.
            long textUtf8Bytes) {

        CaptureSize addText(String value, String path) {
            if (value == null) {
                return this;
            }
            return new CaptureSize(
                    objects,
                    geometryVertices,
                    measurements,
                    addExact(
                            textUtf8Bytes,
                            CanonicalCodec.utf8Bytes(value).length,
                            path + " bytes"));
        }
    }

    /**
     * Count resources without computing an unused hierarchy revision. This validates hierarchy
     * structure, supported types, and saved properties; it is not a revision token and does not
     * replace the complete semantic capture required by conflict and save guards.
     */
    public static CaptureSize measureCaptureSize(ImageData<?> imageData) {
        Objects.requireNonNull(imageData, "image_data");
        try {
            Map<String, Object> properties = persistedProperties(imageData);
            Map<PathObject, String> objectIds =
                    properties.containsKey(DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS)
                            ? new IdentityHashMap<>()
                            : null;
            HierarchySemanticDigester.Size hierarchySize =
                    HierarchySemanticDigester.measure(imageData.getHierarchy(), objectIds);
            // Reuse existing validation of stains, workflow, and arbitrary saved property graphs.
            // Only the per-object hierarchy hashes are omitted by this resource-only path.
            nonHierarchyContentPreimage(imageData, properties, objectIds);
            return captureSize(hierarchySize, properties);
        } catch (CorruptImageStateException failure) {
            throw failure;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new CorruptImageStateException(failure);
        }
    }

    /** Return the complete supported semantic state of one detached PathObject. */
    public static String objectSemanticDigest(PathObject object) {
        return HierarchySemanticDigester.objectSemanticDigest(
                Objects.requireNonNull(object, "object"));
    }

    /** Return one detached PathObject's semantic state except its classification. */
    public static String objectNonClassSemanticDigest(PathObject object) {
        return HierarchySemanticDigester.objectNonClassSemanticDigest(
                Objects.requireNonNull(object, "object"));
    }

    /**
     * Return one object's complete target-transaction state except its unselected child list.
     *
     * <p>This retains the object's parent edge and every intrinsic persisted field. A transaction
     * owner combines these digests over its exact selected population.
     */
    public static String objectTransactionSemanticDigest(PathObject object) {
        return HierarchySemanticDigester.objectTransactionSemanticDigest(
                Objects.requireNonNull(object, "object"));
    }

    /** Return the exact complete hierarchy digest used for whole-slide transactions. */
    public static String hierarchySemanticDigest(PathObjectHierarchy hierarchy) {
        return HierarchySemanticDigester.capture(
                        Objects.requireNonNull(hierarchy, "hierarchy"), false)
                .digest();
    }

    /**
     * Require exact prospective hierarchy and generation-property totals before mutation.
     *
     * <p>The supplied objects are the already re-resolved current removals, constructed additions,
     * and validated current-object class replacements. Parent-only changes do not affect these
     * totals. A present generation-store value means apply will replace or create that persisted
     * string; an empty optional means the property is unchanged.
     */
    public static void requireProspectiveHierarchyFits(
            ImageData<?> imageData,
            Collection<PathObject> removals,
            Map<PathObject, String> classChanges,
            Collection<PathObject> additions,
            Optional<String> prospectiveGenerationStore,
            ExecutionLimitsCodec.ClientExecutionLimits limits) {
        Objects.requireNonNull(imageData, "image_data");
        Objects.requireNonNull(limits, "client_execution_limits");
        CaptureSize projected = measureCaptureSize(imageData);

        Set<PathObject> uniqueRemovals = identitySet(removals, "removals");
        Set<PathObject> uniqueAdditions = identitySet(additions, "additions");
        for (PathObject removal : uniqueRemovals) {
            projected =
                    adjustHierarchySize(
                            projected,
                            HierarchySemanticDigester.measureObject(removal),
                            false,
                            "removed object");
        }
        for (PathObject addition : uniqueAdditions) {
            projected =
                    adjustHierarchySize(
                            projected,
                            HierarchySemanticDigester.measureObject(addition),
                            true,
                            "added object");
        }

        Map<PathObject, String> changed =
                Map.copyOf(Objects.requireNonNull(classChanges, "class_changes"));
        for (Map.Entry<PathObject, String> entry : changed.entrySet()) {
            PathObject object = Objects.requireNonNull(entry.getKey(), "class-change object");
            if (object.isRootObject() || object.getPathClass() == null) {
                throw new IllegalArgumentException(
                        "class-change sizing requires a current non-root PathClass");
            }
            String replacement =
                    PathClass.fromString(
                                    CanonicalCodec.requirePathClassName(
                                            entry.getValue(), "new_class"))
                            .toString();
            long text =
                    subtractExact(
                            projected.textUtf8Bytes(),
                            CanonicalCodec.utf8Bytes(object.getPathClass().toString()).length,
                            "replaced PathClass text");
            text =
                    addExact(
                            text,
                            CanonicalCodec.utf8Bytes(replacement).length,
                            "replacement PathClass text");
            projected =
                    new CaptureSize(
                            projected.objects(),
                            projected.geometryVertices(),
                            projected.measurements(),
                            text);
        }

        Optional<String> storeReplacement =
                Objects.requireNonNull(prospectiveGenerationStore, "prospective_generation_store");
        if (storeReplacement.isPresent()) {
            String replacement = storeReplacement.orElseThrow();
            long text = projected.textUtf8Bytes();
            if (imageData.getProperties().containsKey(HistoforgePersistenceKeys.GENERATION_STORE)) {
                Object current = imageData.getProperty(HistoforgePersistenceKeys.GENERATION_STORE);
                if (!(current instanceof String currentText)) {
                    throw new IllegalArgumentException(
                            "generation store property must be a string");
                }
                text =
                        subtractExact(
                                text,
                                CanonicalCodec.utf8Bytes(currentText).length,
                                "current generation-store text");
            } else {
                text =
                        addExact(
                                text,
                                CanonicalCodec.utf8Bytes(HistoforgePersistenceKeys.GENERATION_STORE)
                                        .length,
                                "generation-store property key");
            }
            text =
                    addExact(
                            text,
                            CanonicalCodec.utf8Bytes(replacement).length,
                            "prospective generation-store text");
            projected =
                    new CaptureSize(
                            projected.objects(),
                            projected.geometryVertices(),
                            projected.measurements(),
                            text);
        }
        requireWithinLimits(projected, limits);
    }

    private static Fingerprint fingerprint(ImageData<?> imageData) {
        Objects.requireNonNull(imageData, "image_data");
        try {
            return captureFingerprint(imageData);
        } catch (CorruptImageStateException failure) {
            throw failure;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new CorruptImageStateException(failure);
        }
    }

    private static Fingerprint captureFingerprint(ImageData<?> imageData) {
        Map<String, Object> properties = persistedProperties(imageData);
        HierarchySemanticDigester.Snapshot hierarchy =
                HierarchySemanticDigester.capture(
                        imageData.getHierarchy(),
                        properties.containsKey(
                                DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS));

        JsonObject preimage =
                nonHierarchyContentPreimage(imageData, properties, hierarchy.objectIdsByIdentity());
        preimage.addProperty("hierarchy_semantic_sha256", hierarchy.digest());
        return new Fingerprint(preimage, captureSize(hierarchy.size(), properties));
    }

    private static CaptureSize captureSize(
            HierarchySemanticDigester.Size hierarchySize, Map<String, Object> properties) {
        CaptureSize size =
                new CaptureSize(
                        hierarchySize.objects(),
                        hierarchySize.geometryVertices(),
                        hierarchySize.measurements(),
                        hierarchySize.textUtf8Bytes());
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            size = size.addText(entry.getKey(), "ImageData property key");
            if (entry.getValue() instanceof String text) {
                size = size.addText(text, "ImageData property value");
            }
        }
        return size;
    }

    private static JsonObject nonHierarchyContentPreimage(
            ImageData<?> imageData,
            Map<String, Object> properties,
            Map<PathObject, String> objectIdsByIdentity) {
        JsonObject preimage = new JsonObject();
        preimage.addProperty("schema_version", CONTENT_SCHEMA_VERSION);
        preimage.addProperty("image_type", imageData.getImageType().name());
        preimage.addProperty(
                "stains_semantic_sha256",
                StainWorkflowSemanticDigester.stainsSemanticDigest(
                        imageData.getColorDeconvolutionStains()));
        preimage.addProperty(
                "workflow_semantic_sha256",
                StainWorkflowSemanticDigester.workflowSemanticDigest(imageData));

        JsonObject propertyDigests = new JsonObject();
        properties.forEach(
                (key, value) -> {
                    if (HistoforgePersistenceKeys.ACTION_COMMIT_WITNESS.equals(key)) {
                        if (!(value instanceof String)) {
                            throw new IllegalArgumentException(
                                    "action commit witness property must be a string");
                        }
                        return;
                    }
                    propertyDigests.addProperty(
                            key,
                            semanticPropertyDigest(
                                    key, value, objectIdsByIdentity, "property[" + key + "]"));
                });
        preimage.add("property_semantic_sha256", propertyDigests);
        return preimage;
    }

    private static void requireWithinLimits(
            CaptureSize size, ExecutionLimitsCodec.ClientExecutionLimits limits) {
        requireAtMost(size.objects(), limits.maxHierarchyObjects(), "max_hierarchy_objects");
        requireAtMost(
                size.geometryVertices(),
                limits.maxHierarchyGeometryVertices(),
                "max_hierarchy_geometry_vertices");
        requireAtMost(
                size.measurements(),
                limits.maxHierarchyMeasurements(),
                "max_hierarchy_measurements");
        requireAtMost(
                size.textUtf8Bytes(),
                limits.maxHierarchyTextUtf8Bytes(),
                "max_hierarchy_text_utf8_bytes");
    }

    private static void validateGenerationStore(
            ImageData<?> imageData, ExecutionLimitsCodec.ClientExecutionLimits limits) {
        if (imageData.getProperties().containsKey(HistoforgePersistenceKeys.GENERATION_STORE)) {
            ImageDataStateCodec.decodeGenerationStoreProperty(
                    imageData.getProperty(HistoforgePersistenceKeys.GENERATION_STORE), limits);
        }
    }

    private static void requireAtMost(long actual, long limit, String field) {
        if (actual > limit) {
            throw new LocalCapacityExceededException(field, actual, limit);
        }
    }

    private static long addExact(long left, long right, String path) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(path + " exceed signed 64-bit range", e);
        }
    }

    private static long subtractExact(long left, long right, String path) {
        try {
            long result = Math.subtractExact(left, right);
            if (result < 0) {
                throw new ArithmeticException("negative total");
            }
            return result;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(path + " produced an invalid resource total", e);
        }
    }

    private static CaptureSize adjustHierarchySize(
            CaptureSize current, HierarchySemanticDigester.Size object, boolean add, String path) {
        return new CaptureSize(
                adjust(current.objects(), object.objects(), add, path + " objects"),
                adjust(
                        current.geometryVertices(),
                        object.geometryVertices(),
                        add,
                        path + " geometry vertices"),
                adjust(current.measurements(), object.measurements(), add, path + " measurements"),
                adjust(current.textUtf8Bytes(), object.textUtf8Bytes(), add, path + " text bytes"));
    }

    private static long adjust(long left, long right, boolean add, String path) {
        return add ? addExact(left, right, path) : subtractExact(left, right, path);
    }

    private static Set<PathObject> identitySet(Collection<PathObject> objects, String path) {
        if (objects == null) {
            throw new IllegalArgumentException(path + " must not be null");
        }
        Set<PathObject> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PathObject object : objects) {
            if (!result.add(Objects.requireNonNull(object, path + " item"))) {
                throw new IllegalArgumentException(path + " contains the same object twice");
            }
        }
        return result;
    }

    /** Mirror QuPath 0.6 PathIO's top-level public-save property admission. */
    private static Map<String, Object> persistedProperties(ImageData<?> imageData) {
        Map<String, Object> result = new TreeMap<>();
        for (Map.Entry<String, Object> entry : imageData.getProperties().entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("ImageData property key must not be null");
            }
            if (isQuPathPersistedProperty(entry.getValue())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static boolean isQuPathPersistedProperty(Object value) {
        if (value == null) {
            return true;
        }
        return value instanceof Serializable && isQuPathSerializableType(value.getClass());
    }

    private static boolean isQuPathSerializableType(Class<?> type) {
        if (!Serializable.class.isAssignableFrom(type)) {
            return false;
        }
        ClassLoader loader = type.getClassLoader();
        if (loader != null
                && loader != ClassLoader.getPlatformClassLoader()
                && loader != ClassLoader.getSystemClassLoader()) {
            return false;
        }
        Module module = type.getModule();
        if (module != null && "java.base".equals(module.getName())) {
            return true;
        }
        return type.getPackageName().startsWith("qupath.lib");
    }

    private static String semanticPropertyDigest(
            String key,
            Object value,
            Map<PathObject, String> authoritativeObjectIds,
            String component) {
        if (DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS.equals(key)) {
            return connectionDigest(value, authoritativeObjectIds);
        }
        return serializedComponentDigest(value, component);
    }

    private static String connectionDigest(
            Object value, Map<PathObject, String> authoritativeObjectIds) {
        if (!(value instanceof PathObjectConnections connections)) {
            throw new IllegalArgumentException(
                    DefaultPathObjectConnectionGroup.KEY_OBJECT_CONNECTIONS
                            + " must contain PathObjectConnections");
        }
        MessageDigest digest = newSha256();
        digest.update(CONNECTION_DIGEST_DOMAIN);
        List<PathObjectConnectionGroup> groups = connections.getConnectionGroups();
        updateLong(digest, groups.size());
        for (PathObjectConnectionGroup group : groups) {
            if (group == null || group.getClass() != DefaultPathObjectConnectionGroup.class) {
                throw new IllegalArgumentException(
                        "unsupported persisted PathObjectConnectionGroup type");
            }
            Collection<PathObject> sources = group.getPathObjects();
            updateLong(digest, sources.size());
            for (PathObject source : sources) {
                updateText(digest, authoritativeObjectId(source, authoritativeObjectIds));
                List<PathObject> targets = group.getConnectedObjects(source);
                if (targets == null) {
                    throw new IllegalArgumentException(
                            "PathObject connection list must not be null");
                }
                updateLong(digest, targets.size());
                for (PathObject target : targets) {
                    updateText(digest, authoritativeObjectId(target, authoritativeObjectIds));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String authoritativeObjectId(
            PathObject object, Map<PathObject, String> authoritativeObjectIds) {
        if (object == null || !authoritativeObjectIds.containsKey(object)) {
            throw new IllegalArgumentException(
                    "PathObject connection endpoint is not an authoritative hierarchy instance");
        }
        return authoritativeObjectIds.get(object);
    }

    private static void updateText(MessageDigest digest, String value) {
        byte[] bytes = CanonicalCodec.utf8Bytes(value);
        updateLong(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    static String serializedComponentDigest(Object value, String component) {
        MessageDigest digest = newSha256();
        try (var digestStream = new DigestOutputStream(OutputStream.nullOutputStream(), digest);
                var objectStream = new QuPathCompatibleObjectOutputStream(digestStream)) {
            objectStream.writeObject(value);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "QuPath " + component + " cannot be serialized for revision capture", e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class QuPathCompatibleObjectOutputStream extends ObjectOutputStream {

        QuPathCompatibleObjectOutputStream(OutputStream output) throws IOException {
            super(output);
            enableReplaceObject(true);
        }

        @Override
        protected Object replaceObject(Object value) throws IOException {
            if (value == null) {
                return null;
            }
            if (value instanceof PathObject || value instanceof PathObjectHierarchy) {
                throw new InvalidObjectException(
                        "generic ImageData properties must not reference hierarchy objects");
            }
            Class<?> type = value.getClass();
            requireQuPathSerializableType(type);
            return value;
        }

        @Override
        protected void annotateClass(Class<?> type) throws IOException {
            requireQuPathSerializableType(type);
        }

        @Override
        protected void annotateProxyClass(Class<?> type) throws IOException {
            requireQuPathSerializableType(type);
        }

        private static void requireQuPathSerializableType(Class<?> type)
                throws InvalidClassException {
            if (!isQuPathSerializableType(type)) {
                throw new InvalidClassException(
                        type.getName(), "QuPath 0.6 read filter rejects this nested class");
            }
        }
    }

    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record Fingerprint(JsonObject contentPreimage, CaptureSize captureSize) {}
}
