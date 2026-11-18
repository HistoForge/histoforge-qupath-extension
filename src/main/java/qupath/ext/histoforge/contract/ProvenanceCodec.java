package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Sole canonical codec for logical ownership stored on one attributed PathObject. */
public final class ProvenanceCodec {

    public static final int SCHEMA_VERSION = 1;

    private static final Set<String> OBJECT_PROVENANCE_FIELDS =
            Set.of("schema_version", "output", "output_generation_id");

    private ProvenanceCodec() {}

    /** Logical replacement ownership plus the image-owned generation record it references. */
    public record ObjectProvenance(
            int schemaVersion, ExecutionScopeCodec.OutputKey output, String outputGenerationId) {
        public ObjectProvenance {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported object provenance schema_version");
            }
            output = Objects.requireNonNull(output, "output");
            outputGenerationId =
                    CanonicalCodec.requireCanonicalUuid(outputGenerationId, "output_generation_id");
        }
    }

    /** Parse one exact schema-1 object-provenance value. */
    public static ObjectProvenance parse(JsonElement value) {
        JsonObject object =
                requireExactObject(value, OBJECT_PROVENANCE_FIELDS, "object provenance");
        return new ObjectProvenance(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                ExecutionScopeCodec.parseOutputKey(object.get("output")),
                requiredString(object, "output_generation_id"));
    }

    /** Encode one provenance value as the exact String stored in PathObject metadata. */
    public static String encodeMetadataValue(ObjectProvenance provenance) {
        return new String(canonicalBytes(provenance), StandardCharsets.UTF_8);
    }

    /** Read PathObject metadata; only an absent key means the object is unattributed. */
    public static Optional<ObjectProvenance> readMetadata(Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (!metadata.containsKey(HistoforgePersistenceKeys.OBJECT_PROVENANCE)) {
            return Optional.empty();
        }
        return Optional.of(
                decodeMetadataValue(metadata.get(HistoforgePersistenceKeys.OBJECT_PROVENANCE)));
    }

    /**
     * Reads current metadata while reusing successful decodes of identical canonical text.
     *
     * <p>Own one reader per synchronous operation; never retain it on an image or across a
     * mutation/callback boundary. The bounds limit retained entries and UTF-16 characters, not
     * accepted input. When full, decoding continues without caching. Failed decodes are never
     * cached, and each read still distinguishes an absent key from a present null value.
     */
    public static final class MetadataReader {
        private final int maxEntries;
        private final int maxCharacters;
        private final Map<String, ObjectProvenance> decoded = new HashMap<>();
        private int cachedCharacters;

        public MetadataReader() {
            this(4096, 1_048_576);
        }

        MetadataReader(int maxEntries, int maxCharacters) {
            if (maxEntries < 0 || maxCharacters < 0) {
                throw new IllegalArgumentException("provenance cache bounds must be nonnegative");
            }
            this.maxEntries = maxEntries;
            this.maxCharacters = maxCharacters;
        }

        public Optional<ObjectProvenance> read(Map<String, String> metadata) {
            Objects.requireNonNull(metadata, "metadata");
            if (!metadata.containsKey(HistoforgePersistenceKeys.OBJECT_PROVENANCE)) {
                return Optional.empty();
            }
            String text = metadata.get(HistoforgePersistenceKeys.OBJECT_PROVENANCE);
            ObjectProvenance provenance = decoded.get(text);
            if (provenance == null) {
                provenance = decodeMetadataValue(text);
                if (decoded.size() < maxEntries
                        && text.length() <= maxCharacters - cachedCharacters) {
                    decoded.put(text, provenance);
                    cachedCharacters += text.length();
                }
            }
            return Optional.of(provenance);
        }
    }

    /** Write one canonical provenance string through the sole raw metadata-key owner. */
    public static void writeMetadata(Map<String, String> metadata, ObjectProvenance provenance) {
        Objects.requireNonNull(metadata, "metadata");
        metadata.put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, encodeMetadataValue(provenance));
    }

    /** Decode one present PathObject metadata value and reject null or noncanonical text. */
    public static ObjectProvenance decodeMetadataValue(String text) {
        if (text == null) {
            throw new IllegalArgumentException("object provenance metadata must be a string");
        }
        ObjectProvenance provenance = parse(CanonicalCodec.parseJson(text));
        if (!encodeMetadataValue(provenance).equals(text)) {
            throw new IllegalArgumentException("object provenance metadata must be canonical JSON");
        }
        return provenance;
    }

    /** Return exact canonical JCS bytes for one validated provenance value. */
    public static byte[] canonicalBytes(ObjectProvenance provenance) {
        return CanonicalCodec.canonicalBytes(toJson(provenance));
    }

    /** Serialize one provenance value with its frozen lower-snake field names. */
    public static JsonObject toJson(ObjectProvenance provenance) {
        if (provenance == null) {
            throw new IllegalArgumentException("object provenance must not be null");
        }
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", provenance.schemaVersion());
        value.add("output", ExecutionScopeCodec.toJson(provenance.output()));
        value.addProperty("output_generation_id", provenance.outputGenerationId());
        return value;
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.getAsString();
    }
}
