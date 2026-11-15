package qupath.ext.histoforge.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.contract.CanonicalCodec;

/** Sole owner of the strict schema-1 headers embedded in module Groovy sources. */
public final class ScriptHeaderParser {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*(.*?)\\*/", Pattern.DOTALL);
    private static final String HEADER_SPACE = "[ \\t\\n\\x0B\\f\\r]";
    private static final Pattern HEADER =
            Pattern.compile(
                    "^"
                            + HEADER_SPACE
                            + "*@histoforge-(classes|metrics)"
                            + HEADER_SPACE
                            + "+(\\[.*\\])"
                            + HEADER_SPACE
                            + "*$",
                    Pattern.DOTALL);
    private static final Set<String> METRIC_FIELDS =
            Set.of("name", "label", "type", "unit", "decimals", "aggregation");
    private static final Set<String> REQUIRED_METRIC_FIELDS =
            Set.of("name", "label", "type", "decimals", "aggregation");

    private ScriptHeaderParser() {}

    /** Parse and validate the one required metrics header in {@code metrics.groovy}. */
    public static List<MetricInfo> parseMetrics(String source) {
        Header header = extractHeader(source);
        if (header == null) {
            throw new IllegalArgumentException("metrics.groovy is missing @histoforge-metrics");
        }
        if (!"metrics".equals(header.kind())) {
            throw new IllegalArgumentException("metrics.groovy cannot declare @histoforge-classes");
        }

        JsonArray values = parseArray(header.payload(), "@histoforge-metrics");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("@histoforge-metrics must be nonempty");
        }

        List<MetricInfo> metrics = new ArrayList<>(values.size());
        Set<String> names = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            JsonElement value = values.get(index);
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("metric[" + index + "] must be an object");
            }
            MetricInfo metric = parseMetric(value.getAsJsonObject(), index);
            if (!names.add(metric.name())) {
                throw new IllegalArgumentException(
                        "@histoforge-metrics contains duplicate name " + metric.name());
            }
            metrics.add(metric);
        }
        return List.copyOf(metrics);
    }

    /** Parse an ordinary script's optional nonempty class header. */
    public static List<String> parseClasses(String source) {
        Header header = extractHeader(source);
        if (header == null) {
            return List.of();
        }
        if (!"classes".equals(header.kind())) {
            throw new IllegalArgumentException("ordinary script cannot declare metrics");
        }

        JsonArray values = parseArray(header.payload(), "@histoforge-classes");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("@histoforge-classes must be nonempty");
        }
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String path = "class[" + index + "]";
            String name =
                    CanonicalCodec.requirePathClassName(
                            requiredString(values.get(index), path), path);
            if (!classes.add(name)) {
                throw new IllegalArgumentException(
                        "@histoforge-classes contains duplicate class " + name);
            }
        }
        return List.copyOf(classes);
    }

    private static MetricInfo parseMetric(JsonObject value, int index) {
        String path = "metric[" + index + "]";
        Set<String> fields = value.keySet();
        Set<String> unknown = new HashSet<>(fields);
        unknown.removeAll(METRIC_FIELDS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(path + " has unknown fields: " + unknown);
        }
        Set<String> missing = new HashSet<>(REQUIRED_METRIC_FIELDS);
        missing.removeAll(fields);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(path + " is missing fields: " + missing);
        }

        String name = requiredString(value.get("name"), path + ".name");
        String label = requiredString(value.get("label"), path + ".label");
        String type = requiredString(value.get("type"), path + ".type");
        String aggregation = requiredString(value.get("aggregation"), path + ".aggregation");
        if ("scalar".equals(type)) {
            if (!Set.of("sum", "mean").contains(aggregation)) {
                throw new IllegalArgumentException(path + ": scalar requires sum or mean");
            }
        } else if ("distribution".equals(type)) {
            if (!"pool".equals(aggregation)) {
                throw new IllegalArgumentException(path + ": distribution requires pool");
            }
        } else {
            throw new IllegalArgumentException(path + ".type must be scalar or distribution");
        }

        int decimals = nonnegativeInteger(value.get("decimals"), path + ".decimals");
        String unit = value.has("unit") ? requiredString(value.get("unit"), path + ".unit") : null;
        return new MetricInfo(name, label, type, unit, decimals, aggregation);
    }

    private static Header extractHeader(String source) {
        if (source == null) {
            throw new IllegalArgumentException("script source must not be null");
        }
        Matcher comments = BLOCK_COMMENT.matcher(source);
        Header found = null;
        int commentIndex = 0;
        while (comments.find()) {
            String body = comments.group(1);
            Matcher header = HEADER.matcher(body);
            if (header.matches()) {
                if (found != null) {
                    throw new IllegalArgumentException(
                            "script must contain at most one @histoforge header");
                }
                if (commentIndex != 0) {
                    throw new IllegalArgumentException(
                            "@histoforge header must be the first block comment");
                }
                found = new Header(header.group(1), header.group(2));
            } else if (body.contains("@histoforge-")) {
                throw new IllegalArgumentException("malformed @histoforge header block");
            }
            commentIndex++;
        }
        return found;
    }

    private static JsonArray parseArray(String payload, String headerName) {
        JsonElement value = CanonicalCodec.parseJson(payload);
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(headerName + " must contain a JSON array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException(path + " must be a nonblank string");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isString() || CanonicalCodec.isBlank(primitive.getAsString())) {
            throw new IllegalArgumentException(path + " must be a nonblank string");
        }
        return primitive.getAsString();
    }

    private static int nonnegativeInteger(JsonElement value, String path) {
        return CanonicalCodec.requireNonnegativeInt32(value, path);
    }

    private record Header(String kind, String payload) {}
}
