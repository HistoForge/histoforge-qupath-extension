package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact module-owned model-output contract shared by inference and managed training. */
public final class ModelOutputCodec {

    private static final Set<String> OUTPUT_TYPES =
            Set.of(
                    "instance_segmentation",
                    "instance_segmentation_and_classification",
                    "dense_classification",
                    "point_detection",
                    "region_classification");
    private static final Set<String> CLASS_CHANNEL_ROLES =
            Set.of("background", "emitted", "training_only");
    private static final Set<String> INSTANCE_OUTPUT_FIELDS = Set.of("type", "classes");
    private static final Set<String> CLASS_OUTPUT_FIELDS = Set.of("type", "classChannels");
    private static final Set<String> BACKGROUND_CHANNEL_FIELDS = Set.of("channel", "role");
    private static final Set<String> LABELED_CHANNEL_FIELDS =
            Set.of("channel", "role", "pathClass");

    private ModelOutputCodec() {}

    /** One explicit neural class channel. Background alone has no PathClass. */
    public record ClassChannel(int channel, String role, String pathClass) {
        public ClassChannel {
            if (channel < 0) {
                throw new IllegalArgumentException("class channel must be nonnegative");
            }
            if (!CLASS_CHANNEL_ROLES.contains(role)) {
                throw new IllegalArgumentException("unsupported class-channel role");
            }
            if ("background".equals(role)) {
                if (pathClass != null) {
                    throw new IllegalArgumentException(
                            "background class channel must not declare a PathClass");
                }
            } else {
                pathClass = CanonicalCodec.requirePathClassName(pathClass, "pathClass");
            }
        }
    }

    /** Complete normalized output semantics copied from one recipe model. */
    public record ModelOutput(String type, List<String> classes, List<ClassChannel> classChannels) {
        public ModelOutput {
            type = requireOutputType(type, "output.type");
            classes = immutableClasses(classes);
            classChannels = immutableChannels(classChannels);
            if ("instance_segmentation".equals(type)) {
                if (classes.size() != 1 || !classChannels.isEmpty()) {
                    throw new IllegalArgumentException(
                            "instance_segmentation requires one class and no class channels");
                }
            } else {
                if (!classes.isEmpty() || classChannels.isEmpty()) {
                    throw new IllegalArgumentException(
                            "class-head output requires class channels and no classes field");
                }
                validateClassChannels(classChannels);
                if ("point_detection".equals(type)
                        && classChannels.stream()
                                .anyMatch(channel -> !"emitted".equals(channel.role()))) {
                    throw new IllegalArgumentException(
                            "point detection requires emitted-only class channels");
                }
            }
        }

        /** PathClasses that normal inference may add to QuPath. */
        public List<String> emittedClasses() {
            if ("instance_segmentation".equals(type)) {
                return classes;
            }
            return classChannels.stream()
                    .filter(channel -> "emitted".equals(channel.role()))
                    .map(ClassChannel::pathClass)
                    .toList();
        }

        /** Geometry expected from this pinned model, including an empty result. */
        public InferenceFeatureCodec.GeometryKind geometryKind() {
            return "point_detection".equals(type)
                    ? InferenceFeatureCodec.GeometryKind.POINT
                    : InferenceFeatureCodec.GeometryKind.POLYGONAL;
        }

        /** Every supervised non-background class in neural-channel order. */
        public List<ClassChannel> targetChannels() {
            return classChannels.stream()
                    .filter(channel -> !"background".equals(channel.role()))
                    .toList();
        }

        /** Every supervised PathClass in neural-channel order. */
        public List<String> targetClasses() {
            if ("instance_segmentation".equals(type)) {
                return classes;
            }
            return targetChannels().stream().map(ClassChannel::pathClass).toList();
        }
    }

    /** Parse, strictly validate, and normalize one recipe-style model-output object. */
    public static ModelOutput parse(JsonElement value) {
        return parse(value, "model output");
    }

    /** Parse with a caller-owned field path for actionable validation errors. */
    public static ModelOutput parse(JsonElement value, String path) {
        JsonObject object = requiredObject(value, path);
        String type = requireOutputType(requiredString(object.get("type"), path + ".type"), path);
        if ("instance_segmentation".equals(type)) {
            requireExactFields(object, INSTANCE_OUTPUT_FIELDS, path);
            JsonArray values = requiredArray(object.get("classes"), path + ".classes");
            List<String> classes = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                classes.add(
                        CanonicalCodec.requirePathClassName(
                                requiredString(values.get(index), path + ".classes[" + index + "]"),
                                path + ".classes[" + index + "]"));
            }
            return new ModelOutput(type, classes, List.of());
        }

        requireExactFields(object, CLASS_OUTPUT_FIELDS, path);
        JsonArray values = requiredArray(object.get("classChannels"), path + ".classChannels");
        List<ClassChannel> channels = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            channels.add(
                    parseClassChannel(values.get(index), path + ".classChannels[" + index + "]"));
        }
        return new ModelOutput(type, List.of(), channels);
    }

    /** Encode a normalized model output using the exact recipe field names. */
    public static JsonObject toJson(ModelOutput output) {
        Objects.requireNonNull(output, "model output");
        JsonObject result = new JsonObject();
        result.addProperty("type", output.type());
        if ("instance_segmentation".equals(output.type())) {
            JsonArray classes = new JsonArray();
            output.classes().forEach(classes::add);
            result.add("classes", classes);
        } else {
            JsonArray channels = new JsonArray();
            output.classChannels().forEach(channel -> channels.add(toJson(channel)));
            result.add("classChannels", channels);
        }
        return result;
    }

    /** Convert the module string into the existing runner-capability enum. */
    public static RunnerCapabilitiesCodec.ModelOutputType runnerOutputType(ModelOutput output) {
        Objects.requireNonNull(output, "model output");
        return RunnerCapabilitiesCodec.ModelOutputType.valueOf(
                output.type().toUpperCase(java.util.Locale.ROOT));
    }

    public static String requireOutputType(String value, String path) {
        if (!OUTPUT_TYPES.contains(value)) {
            throw new IllegalArgumentException(path + " is unsupported");
        }
        return value;
    }

    private static ClassChannel parseClassChannel(JsonElement value, String path) {
        JsonObject object = requiredObject(value, path);
        String role = requiredString(object.get("role"), path + ".role");
        Set<String> expected =
                "background".equals(role) ? BACKGROUND_CHANNEL_FIELDS : LABELED_CHANNEL_FIELDS;
        requireExactFields(object, expected, path);
        String pathClass =
                "background".equals(role)
                        ? null
                        : requiredString(object.get("pathClass"), path + ".pathClass");
        return new ClassChannel(
                CanonicalCodec.requireNonnegativeInt32(object.get("channel"), path + ".channel"),
                role,
                pathClass);
    }

    private static JsonObject toJson(ClassChannel channel) {
        JsonObject result = new JsonObject();
        result.addProperty("channel", channel.channel());
        result.addProperty("role", channel.role());
        if (channel.pathClass() != null) {
            result.addProperty("pathClass", channel.pathClass());
        }
        return result;
    }

    private static List<String> immutableClasses(List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            result.add(
                    CanonicalCodec.requirePathClassName(
                            values.get(index), "classes[" + index + "]"));
        }
        return List.copyOf(result);
    }

    private static List<ClassChannel> immutableChannels(List<ClassChannel> values) {
        if (values == null) {
            return List.of();
        }
        List<ClassChannel> result = new ArrayList<>();
        for (ClassChannel value : values) {
            result.add(Objects.requireNonNull(value, "class channel"));
        }
        result.sort(Comparator.comparingInt(ClassChannel::channel));
        return List.copyOf(result);
    }

    private static void validateClassChannels(List<ClassChannel> channels) {
        int backgroundCount = 0;
        boolean emitted = false;
        Set<String> pathClasses = new HashSet<>();
        for (int index = 0; index < channels.size(); index++) {
            ClassChannel channel = channels.get(index);
            if (channel.channel() != index) {
                throw new IllegalArgumentException(
                        "class channels must be contiguous from channel zero");
            }
            if ("background".equals(channel.role())) {
                backgroundCount++;
            } else {
                emitted |= "emitted".equals(channel.role());
                if (!pathClasses.add(channel.pathClass())) {
                    throw new IllegalArgumentException(
                            "class channels contain duplicate PathClasses");
                }
            }
        }
        if (backgroundCount > 1) {
            throw new IllegalArgumentException("class channels permit at most one background");
        }
        if (!emitted) {
            throw new IllegalArgumentException("class channels must contain an emitted channel");
        }
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be a JSON object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(path + " must be a JSON array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }

    private static void requireExactFields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }
}
