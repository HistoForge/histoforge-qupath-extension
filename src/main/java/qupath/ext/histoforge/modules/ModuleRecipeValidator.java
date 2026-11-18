package qupath.ext.histoforge.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;

/** Strict structural and recipe-order validation for the schema defined by modules.md. */
final class ModuleRecipeValidator {

    private static final Set<String> TOP_LEVEL_FIELDS =
            Set.of(
                    "schemaVersion",
                    "name",
                    "description",
                    "upstream",
                    "trainingReportDigest",
                    "models",
                    "steps");
    private static final Set<String> MODEL_FIELDS =
            Set.of("arch", "gpu", "weights", "input", "output", "params", "backbone");
    private static final Set<String> INPUT_FIELDS =
            Set.of(
                    "type",
                    "micronsPerPixel",
                    "fieldProfile",
                    "largeRegion",
                    "framing",
                    "acceptCriteria");
    private static final Set<String> COMPOSABLE_HEAD_ARCHS = Set.of("abmil", "mlp", "linear");
    private static final Pattern MODEL_ID = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");

    private ModuleRecipeValidator() {}

    static void validate(JsonObject recipe, boolean retainedSnapshot) {
        fields(
                recipe,
                TOP_LEVEL_FIELDS,
                Set.of("schemaVersion", "name", "models", "steps"),
                "recipe");
        int version = integer(recipe.get("schemaVersion"), "recipe.schemaVersion");
        if (version != GatewayCapabilitiesCodec.MODULE_SCHEMA_VERSION
                && version != 3
                && !(retainedSnapshot && version == 2)) {
            throw new IllegalArgumentException(
                    "recipe.schemaVersion must be "
                            + "3 or "
                            + GatewayCapabilitiesCodec.MODULE_SCHEMA_VERSION
                            + (retainedSnapshot ? " (or 2 for a retained snapshot)" : ""));
        }
        nonblank(recipe.get("name"), "recipe.name");
        if (recipe.has("description")) {
            String description = nonblank(recipe.get("description"), "recipe.description");
            if (description.contains("\n") || description.contains("\r")) {
                throw new IllegalArgumentException("recipe.description must be one line");
            }
        }
        if (recipe.has("upstream")) {
            validateUpstream(recipe.get("upstream"));
        }
        if (recipe.has("trainingReportDigest")) {
            CanonicalCodec.requireSha256(
                    nonblank(recipe.get("trainingReportDigest"), "recipe.trainingReportDigest"),
                    "recipe.trainingReportDigest");
        }

        JsonObject models = object(recipe.get("models"), "recipe.models");
        if (models.isEmpty()) {
            throw new IllegalArgumentException("recipe.models must be nonempty");
        }
        for (Map.Entry<String, JsonElement> model : models.entrySet()) {
            requireModelId(model.getKey(), "model id");
            validateModel(model.getKey(), model.getValue());
            if (version < 4
                    && "point_detection"
                            .equals(
                                    model.getValue()
                                            .getAsJsonObject()
                                            .getAsJsonObject("output")
                                            .get("type")
                                            .getAsString())) {
                throw new IllegalArgumentException(
                        "point detection requires recipe schemaVersion 4");
            }
            if (version == 2
                    && model.getValue()
                            .getAsJsonObject()
                            .getAsJsonObject("input")
                            .has("largeRegion")) {
                throw new IllegalArgumentException(
                        "schema 2 recipes cannot declare input.largeRegion");
            }
        }

        JsonArray steps = array(recipe.get("steps"), "recipe.steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("recipe.steps must be nonempty");
        }
        validateSteps(steps, models);
    }

    static String requireOutputType(String value, String path) {
        return ModelOutputCodec.requireOutputType(value, path);
    }

    private static void validateModel(String modelId, JsonElement value) {
        String path = "recipe.models." + modelId;
        JsonObject model = object(value, path);
        fields(model, MODEL_FIELDS, Set.of("arch", "gpu", "weights", "input", "output"), path);
        String architecture = nonblank(model.get("arch"), path + ".arch");
        nonblank(model.get("gpu"), path + ".gpu");
        validateWeights(model.get("weights"), path + ".weights", modelId);
        validateInput(model.get("input"), path + ".input");
        validateOutput(model.get("output"), path + ".output");
        if (model.has("params")) {
            JsonObject params = object(model.get("params"), path + ".params");
            CanonicalCodec.canonicalBytes(params);
            if (model.getAsJsonObject("input").has("fieldProfile")
                    && (params.has("bsize") || params.has("tile_overlap"))) {
                throw new IllegalArgumentException(
                        path + ".params cannot declare fixed-size fields with input.fieldProfile");
            }
        }
        if (COMPOSABLE_HEAD_ARCHS.contains(architecture) && !model.has("backbone")) {
            throw new IllegalArgumentException(
                    path + ".backbone is required for a composable-head arch");
        }
        if (model.has("backbone")) {
            if (!COMPOSABLE_HEAD_ARCHS.contains(architecture)) {
                throw new IllegalArgumentException(
                        path + ".backbone is valid only for a composable-head arch");
            }
            JsonObject backbone = object(model.get("backbone"), path + ".backbone");
            fields(
                    backbone,
                    Set.of("arch", "weights"),
                    Set.of("arch", "weights"),
                    path + ".backbone");
            nonblank(backbone.get("arch"), path + ".backbone.arch");
            JsonObject weights = object(backbone.get("weights"), path + ".backbone.weights");
            if (!"referenced".equals(optionalString(weights.get("type")))) {
                throw new IllegalArgumentException(
                        path + ".backbone.weights must be referenced and revision-pinned");
            }
            validateWeights(weights, path + ".backbone.weights", null);
        }
    }

    private static void validateWeights(JsonElement value, String path, String modelId) {
        JsonObject weights = object(value, path);
        String kind = nonblank(weights.get("type"), path + ".type");
        switch (kind) {
            case "vendored" -> {
                fields(weights, Set.of("type", "file"), Set.of("type", "file"), path);
                String filename =
                        safeRelativeFile(weights.get("file"), path + ".file", ".safetensors");
                if (modelId != null && !filename.equals(modelId + ".safetensors")) {
                    throw new IllegalArgumentException(
                            path + ".file must be " + modelId + ".safetensors");
                }
            }
            case "referenced" -> {
                fields(
                        weights,
                        Set.of("type", "ref", "revision"),
                        Set.of("type", "ref", "revision"),
                        path);
                moduleRef(weights.get("ref"), path + ".ref");
                commitSha(weights.get("revision"), path + ".revision");
            }
            case "local" -> {
                fields(weights, Set.of("type", "ref"), Set.of("type", "ref"), path);
                String ref = moduleRef(weights.get("ref"), path + ".ref");
                if (!ref.startsWith("local/")) {
                    throw new IllegalArgumentException(path + ".ref must use the local/ namespace");
                }
            }
            default ->
                    throw new IllegalArgumentException(
                            path + ".type is not a supported weights source");
        }
    }

    private static void validateInput(JsonElement value, String path) {
        JsonObject input = object(value, path);
        fields(input, INPUT_FIELDS, Set.of("type", "micronsPerPixel"), path);
        String type = nonblank(input.get("type"), path + ".type");
        positiveNumber(input.get("micronsPerPixel"), path + ".micronsPerPixel");
        if (input.has("largeRegion")) {
            JsonObject settings = object(input.get("largeRegion"), path + ".largeRegion");
            Set<String> required =
                    Set.of("coreWidthModelPx", "coreHeightModelPx", "haloPerSideModelPx");
            fields(settings, required, required, path + ".largeRegion");
            for (String dimension : List.of("coreWidthModelPx", "coreHeightModelPx")) {
                CanonicalCodec.requirePositiveInt32(
                        settings.get(dimension), path + ".largeRegion." + dimension);
            }
            CanonicalCodec.requireNonnegativeInt32(
                    settings.get("haloPerSideModelPx"), path + ".largeRegion.haloPerSideModelPx");
        }
        VariableFieldProfile fieldProfile = null;
        if (input.has("fieldProfile")) {
            var parsed = TrainingTaskCodec.parseFieldProfile(input.get("fieldProfile"));
            if (!(parsed instanceof VariableFieldProfile variable)) {
                throw new IllegalArgumentException(
                        path
                                + ".fieldProfile must use "
                                + TrainingTaskCodec.VARIABLE_FIELD_PLANNER);
            }
            fieldProfile = variable;
        }
        if ("field".equals(type)) {
            if (input.has("framing") || input.has("acceptCriteria")) {
                throw new IllegalArgumentException(
                        path + ": field cannot declare crop-only fields");
            }
        } else if ("crop".equals(type)) {
            if (!input.has("framing")) {
                throw new IllegalArgumentException(path + ": crop input requires framing");
            }
            JsonObject framing = object(input.get("framing"), path + ".framing");
            fields(framing, Set.of("paddingModelPx"), Set.of("paddingModelPx"), path + ".framing");
            if (integer(framing.get("paddingModelPx"), path + ".framing.paddingModelPx") < 0) {
                throw new IllegalArgumentException(
                        path + ".framing.paddingModelPx must be nonnegative");
            }
            if (input.has("acceptCriteria")) {
                validateAcceptCriteria(input.get("acceptCriteria"), path + ".acceptCriteria");
            }
            if (fieldProfile != null && fieldProfile.smallRoiContext()) {
                throw new IllegalArgumentException(
                        path + ".fieldProfile.smallRoiContext must be false for crop input");
            }
        } else {
            throw new IllegalArgumentException(path + ".type must be field or crop");
        }
    }

    private static void validateAcceptCriteria(JsonElement value, String path) {
        JsonObject criteria = object(value, path);
        fields(criteria, Set.of("sizeUmRange", "maxAspectRatio"), Set.of(), path);
        if (criteria.isEmpty()) {
            throw new IllegalArgumentException(path + " must declare at least one criterion");
        }
        if (criteria.has("sizeUmRange")) {
            JsonArray range = array(criteria.get("sizeUmRange"), path + ".sizeUmRange");
            if (range.size() != 2) {
                throw new IllegalArgumentException(path + ".sizeUmRange must contain [min, max]");
            }
            double minimum = positiveNumber(range.get(0), path + ".sizeUmRange[0]");
            double maximum = positiveNumber(range.get(1), path + ".sizeUmRange[1]");
            if (minimum > maximum) {
                throw new IllegalArgumentException(path + ".sizeUmRange minimum exceeds maximum");
            }
        }
        if (criteria.has("maxAspectRatio")
                && positiveNumber(criteria.get("maxAspectRatio"), path + ".maxAspectRatio") < 1) {
            throw new IllegalArgumentException(path + ".maxAspectRatio must be at least 1");
        }
    }

    private static void validateOutput(JsonElement value, String path) {
        JsonObject output = object(value, path);
        JsonObject normalized = ModelOutputCodec.toJson(ModelOutputCodec.parse(output, path));
        for (String field : new ArrayList<>(output.keySet())) {
            output.remove(field);
        }
        normalized.entrySet().forEach(entry -> output.add(entry.getKey(), entry.getValue()));
    }

    private static void validateSteps(JsonArray steps, JsonObject models) {
        Set<String> seenIds = new HashSet<>();
        Set<String> earlierPlainModels = new HashSet<>();
        boolean metricsSeen = false;
        for (int index = 0; index < steps.size(); index++) {
            String path = "recipe.steps[" + index + "]";
            JsonObject step = object(steps.get(index), path);
            Set<String> stepFields = step.keySet();
            String id;
            if (stepFields.equals(Set.of("model"))) {
                String modelId = knownModel(step.get("model"), models, path + ".model");
                if (!"field".equals(inputType(models, modelId))) {
                    throw new IllegalArgumentException(path + ": plain model must use field input");
                }
                earlierPlainModels.add(modelId);
                id = modelId;
            } else if (stepFields.equals(Set.of("model", "parentModel", "parentClass"))) {
                String modelId = knownModel(step.get("model"), models, path + ".model");
                String parentModel =
                        knownModel(step.get("parentModel"), models, path + ".parentModel");
                if (!earlierPlainModels.contains(parentModel)) {
                    throw new IllegalArgumentException(
                            path + ".parentModel must name an earlier plain model");
                }
                if (!"crop".equals(inputType(models, modelId))) {
                    throw new IllegalArgumentException(
                            path + ": region-gated model must use crop input");
                }
                requireUniquePathClasses(
                        array(step.get("parentClass"), path + ".parentClass"),
                        path + ".parentClass");
                id = modelId;
            } else if (stepFields.equals(Set.of("script"))) {
                String filename = safeRelativeFile(step.get("script"), path + ".script", ".groovy");
                if (ModuleContract.METRICS_SCRIPT_NAME.equals(filename)) {
                    if (metricsSeen || index != steps.size() - 1) {
                        throw new IllegalArgumentException(
                                "metrics.groovy must occur once and be the final step");
                    }
                    metricsSeen = true;
                }
                id = filename;
            } else {
                throw new IllegalArgumentException(
                        path + " is not one of the three supported step shapes");
            }
            if (!seenIds.add(id)) {
                throw new IllegalArgumentException("duplicate stable step id: " + id);
            }
        }
    }

    private static String inputType(JsonObject models, String modelId) {
        return models.getAsJsonObject(modelId).getAsJsonObject("input").get("type").getAsString();
    }

    private static void requireUniquePathClasses(JsonArray values, String path) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(path + " must be nonempty");
        }
        Set<String> names = new HashSet<>();
        for (JsonElement value : values) {
            String name = CanonicalCodec.requirePathClassName(nonblank(value, path), path);
            if (!names.add(name)) {
                throw new IllegalArgumentException(path + " contains duplicates");
            }
        }
    }

    private static String knownModel(JsonElement value, JsonObject models, String path) {
        String modelId = nonblank(value, path);
        if (!models.has(modelId)) {
            throw new IllegalArgumentException(path + " names unknown model " + modelId);
        }
        return modelId;
    }

    private static void validateUpstream(JsonElement value) {
        String upstream = nonblank(value, "recipe.upstream");
        int separator = upstream.indexOf('@');
        if (separator <= 0 || separator != upstream.lastIndexOf('@')) {
            throw new IllegalArgumentException("recipe.upstream must be owner/name@revision");
        }
        moduleRef(new JsonPrimitive(upstream.substring(0, separator)), "recipe.upstream");
        commitSha(new JsonPrimitive(upstream.substring(separator + 1)), "recipe.upstream revision");
    }

    private static String moduleRef(JsonElement value, String path) {
        String ref = nonblank(value, path);
        if (ModuleRef.parse(ref).isEmpty()) {
            throw new IllegalArgumentException(path + " must be a canonical owner/name module ref");
        }
        return ref;
    }

    private static String commitSha(JsonElement value, String path) {
        return requireCommitSha(nonblank(value, path), path);
    }

    static String requireModelId(String value, String path) {
        if (value == null || !MODEL_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(path + " must be a canonical model id");
        }
        return value;
    }

    static String requireCommitSha(String value, String path) {
        if (value == null || !COMMIT_SHA.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    path + " must be a full lowercase 40-character commit SHA");
        }
        return value;
    }

    private static String safeRelativeFile(JsonElement value, String path, String suffix) {
        return safeRelativeFile(nonblank(value, path), path, suffix);
    }

    static String safeRelativeFile(String filename, String path, String suffix) {
        if (filename == null) {
            throw new IllegalArgumentException(path + " must be a safe POSIX-relative path");
        }
        String[] parts = filename.split("/", -1);
        if (filename.startsWith("/")
                || filename.contains("\\")
                || filename.indexOf('\0') >= 0
                || !filename.endsWith(suffix)) {
            throw new IllegalArgumentException(path + " must be a safe POSIX-relative path");
        }
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException(path + " must be a safe POSIX-relative path");
            }
        }
        return filename;
    }

    private static void fields(
            JsonObject value, Set<String> allowed, Set<String> required, String path) {
        Set<String> unknown = new HashSet<>(value.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(path + " has unknown fields: " + unknown);
        }
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(value.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(path + " is missing fields: " + missing);
        }
    }

    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be a JSON object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(path + " must be a JSON array");
        }
        return value.getAsJsonArray();
    }

    private static String nonblank(JsonElement value, String path) {
        String text = optionalString(value);
        if (text == null || CanonicalCodec.isBlank(text)) {
            throw new IllegalArgumentException(path + " must be a nonblank string");
        }
        return text;
    }

    private static String optionalString(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    private static int integer(JsonElement value, String path) {
        return CanonicalCodec.requireInt32(value, path);
    }

    private static double positiveNumber(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + " must be numeric");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number <= 0) {
            throw new IllegalArgumentException(path + " must be finite and positive");
        }
        return number;
    }
}
