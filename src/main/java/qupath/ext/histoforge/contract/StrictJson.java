package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;

/** Shared strict JSON shape and string primitives for closed wire contracts. */
public final class StrictJson {

    private StrictJson() {}

    public static JsonObject requireObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    public static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requireObject(value, path);
        requireFields(object, fields, path);
        return object;
    }

    public static void requireFields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    public static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }

    public static String requiredString(JsonObject value, String field) {
        return requiredString(value.get(field), field);
    }

    public static String requiredString(JsonObject value, String field, String parentPath) {
        return requiredString(value.get(field), parentPath + "." + field);
    }
}
