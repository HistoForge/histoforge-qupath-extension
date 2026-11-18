package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import qupath.ext.histoforge.modules.ModuleContract;

/** Package-private scalar and structural helpers shared by the closed training codecs. */
final class TrainingJson {

    private static final Pattern STABLE_ID = Pattern.compile("[!-~]{1,128}");

    private TrainingJson() {}

    static JsonObject exactObject(JsonElement value, Set<String> fields, String path) {
        JsonObject object = object(value, path);
        requireFields(object, fields, path);
        return object;
    }

    static JsonObject objectWithOptional(
            JsonElement value, Set<String> required, Set<String> optional, String path) {
        JsonObject object = object(value, path);
        if (!object.keySet().containsAll(required)
                || object.keySet().stream()
                        .anyMatch(key -> !required.contains(key) && !optional.contains(key))) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    static void requireFields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    static JsonArray array(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        return value.getAsJsonArray();
    }

    static JsonArray nonemptyArray(JsonElement value, String path) {
        JsonArray array = array(value, path);
        if (array.isEmpty()) {
            throw new IllegalArgumentException(path + " must be nonempty");
        }
        return array;
    }

    static String string(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }

    static String string(JsonObject value, String field) {
        return string(value.get(field), field);
    }

    static String stableId(JsonElement value, String path) {
        String result = string(value, path);
        if (!STABLE_ID.matcher(result).matches()) {
            throw new IllegalArgumentException(path + " must be 1 to 128 visible ASCII characters");
        }
        return result;
    }

    static String modelId(JsonElement value, String path) {
        return ModuleContract.requireModelId(stableId(value, path), path);
    }

    static String text(JsonElement value, String path, long maxTextBytes) {
        String result = textSyntax(value, path);
        if (result.getBytes(StandardCharsets.UTF_8).length > maxTextBytes) {
            throw new IllegalArgumentException(path + " exceeds max_text_bytes");
        }
        return result;
    }

    static String textSyntax(JsonElement value, String path) {
        String result = CanonicalCodec.requireNonblank(string(value, path), path);
        if (result.codePoints().anyMatch(TrainingJson::isControlCharacter)) {
            throw new IllegalArgumentException(path + " must not contain control characters");
        }
        return result;
    }

    static boolean bool(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(path + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    static String enumValue(JsonElement value, Set<String> choices, String path) {
        String result = string(value, path);
        if (!choices.contains(result)) {
            throw new IllegalArgumentException(path + " is unsupported");
        }
        return result;
    }

    static List<String> stringArray(JsonElement value, String path) {
        JsonArray array = array(value, path);
        List<String> result = new ArrayList<>();
        for (JsonElement item : array) {
            result.add(string(item, path + " item"));
        }
        return List.copyOf(result);
    }

    static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static boolean isControlCharacter(int codePoint) {
        return codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f);
    }
}
