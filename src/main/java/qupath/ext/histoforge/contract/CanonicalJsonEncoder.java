package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.erdtman.jcs.NumberToJSON;

/** Encode a parsed Gson tree using the JSON Canonicalization Scheme (RFC 8785). */
final class CanonicalJsonEncoder {

    // Preserve the container-depth limit of the pinned Gson parser used for raw JSON.
    private static final int MAX_CONTAINER_DEPTH = 255;
    private static final Pattern JSON_NUMBER =
            Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private static final String HEX_DIGITS = "0123456789abcdef";

    private CanonicalJsonEncoder() {}

    /** Return canonical JSON text without serializing and reparsing the input tree. */
    static String encode(JsonElement value) {
        StringBuilder output = new StringBuilder();
        append(value, output, 0);
        return output.toString();
    }

    private static void append(JsonElement value, StringBuilder output, int containerDepth) {
        if (value.isJsonArray() || value.isJsonObject()) {
            containerDepth++;
            if (containerDepth > MAX_CONTAINER_DEPTH) {
                throw new IllegalArgumentException("JSON nesting exceeds 255 containers");
            }
        }
        if (value.isJsonNull()) {
            output.append("null");
        } else if (value.isJsonArray()) {
            output.append('[');
            boolean first = true;
            for (JsonElement child : value.getAsJsonArray()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                append(child, output, containerDepth);
            }
            output.append(']');
        } else if (value.isJsonObject()) {
            output.append('{');
            boolean first = true;
            // JCS orders member names by UTF-16 code units, as String.compareTo does.
            for (var entry : new TreeMap<>(value.getAsJsonObject().asMap()).entrySet()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendString(entry.getKey(), output);
                output.append(':');
                append(entry.getValue(), output, containerDepth);
            }
            output.append('}');
        } else {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                output.append(primitive.getAsBoolean());
            } else if (primitive.isString()) {
                appendString(primitive.getAsString(), output);
            } else {
                appendNumber(primitive, output);
            }
        }
    }

    private static void appendNumber(JsonPrimitive primitive, StringBuilder output) {
        try {
            if (primitive.getAsNumber() instanceof Double value) {
                // Doubles already have the exact value that a JSON text round-trip would recover.
                output.append(NumberToJSON.serializeNumber(value));
            } else {
                // Other Number types follow their JSON text; widening a Float changes its value.
                String token = primitive.getAsString();
                if (!JSON_NUMBER.matcher(token).matches()) {
                    throw new IllegalArgumentException("invalid RFC 8785 JSON number");
                }
                output.append(NumberToJSON.serializeNumber(Double.parseDouble(token)));
            }
        } catch (IOException | NumberFormatException e) {
            throw new IllegalArgumentException("invalid RFC 8785 JSON number", e);
        }
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append("\\u00");
                        output.append(HEX_DIGITS.charAt(character >>> 4));
                        output.append(HEX_DIGITS.charAt(character & 0xf));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }
}
