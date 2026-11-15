package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.modules.ModuleContract.ScriptContract;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.CropFraming;
import qupath.ext.histoforge.modules.ModuleSnapshot.MetricScriptSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputType;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ScriptSnapshot;

/** Canonical persistence codec for one exact executable module snapshot. */
public final class ModuleSnapshotCodec {

    private static final Set<String> SNAPSHOT_REQUIRED_FIELDS =
            Set.of(
                    "deployment_id",
                    "module",
                    "exact_recipe",
                    "script_set_digest",
                    "activated_models",
                    "scripts");
    private static final Set<String> SNAPSHOT_OPTIONAL_FIELDS = Set.of("metrics");
    private static final Set<String> MODEL_REQUIRED_FIELDS =
            Set.of(
                    "architecture",
                    "gpu",
                    "model_config_digest",
                    "input",
                    "output",
                    "capabilities",
                    "artifact_digests");
    private static final Set<String> MODEL_OPTIONAL_FIELDS =
            Set.of("large_region_profile", "halo_px_override");
    private static final Set<String> INPUT_REQUIRED_FIELDS = Set.of("type", "target_mpp");
    private static final Set<String> INPUT_OPTIONAL_FIELDS = Set.of("field_profile", "framing");
    private static final Set<String> FRAMING_FIELDS = Set.of("padding_model_px");
    private static final Set<String> SCRIPT_FIELDS = Set.of("filename", "source", "digest");
    private static final Set<String> METRIC_SCRIPT_FIELDS = Set.of("script", "schema");
    private static final Set<String> METRIC_REQUIRED_FIELDS =
            Set.of("name", "label", "type", "decimals", "aggregation");
    private static final Set<String> METRIC_OPTIONAL_FIELDS = Set.of("unit");

    private ModuleSnapshotCodec() {}

    /** Parse, revalidate, and detach one exact persisted module snapshot. */
    public static ModuleSnapshot parse(JsonElement value) {
        JsonObject object =
                fieldsObject(
                        value,
                        SNAPSHOT_REQUIRED_FIELDS,
                        SNAPSHOT_OPTIONAL_FIELDS,
                        "module snapshot");
        JsonObject recipe = requiredObject(object.get("exact_recipe"), "exact_recipe").deepCopy();
        Map<String, ScriptSnapshot> scripts = parseScripts(object.get("scripts"));
        Optional<MetricScriptSnapshot> metrics =
                object.has("metrics")
                        ? Optional.of(parseMetrics(object.get("metrics")))
                        : Optional.empty();
        metrics.ifPresent(
                metric -> {
                    if (scripts.containsKey(metric.script().filename())) {
                        throw new IllegalArgumentException(
                                "module snapshot script roles must not overlap");
                    }
                });

        Map<String, String> allScriptSources = new LinkedHashMap<>();
        scripts.forEach((filename, script) -> allScriptSources.put(filename, script.source()));
        metrics.ifPresent(
                metric ->
                        allScriptSources.put(metric.script().filename(), metric.script().source()));
        ModuleContract.ValidatedDefinition definition =
                ModuleContract.parseRetainedSnapshot(
                        CanonicalCodec.canonicalBytes(recipe), allScriptSources);
        requireScriptPartition(definition, scripts, metrics);

        String persistedScriptSet = requiredString(object, "script_set_digest");
        if (!definition.scriptSetDigest().equals(persistedScriptSet)) {
            throw new IllegalArgumentException(
                    "module snapshot script_set_digest does not match captured scripts");
        }
        InferenceIdentityCodec.ModuleContentIdentity identity =
                InferenceIdentityCodec.parseModuleContentIdentity(object.get("module"));
        if (!definition.recipeDigest().equals(identity.recipeDigest())) {
            throw new IllegalArgumentException(
                    "module snapshot recipe does not match module recipe_digest");
        }

        JsonObject recipeModels = recipe.getAsJsonObject("models");
        requirePublishedRecipe(recipeModels);
        Map<String, ModelSnapshot> models =
                parseModels(
                        object.get("activated_models"),
                        definition.modelConfigDigests(),
                        recipeModels,
                        recipe.get("schemaVersion").getAsInt() == 2);
        return new ModuleSnapshot(
                requiredString(object, "deployment_id"),
                identity,
                recipe,
                persistedScriptSet,
                models,
                scripts,
                metrics);
    }

    private static void requireScriptPartition(
            ModuleContract.ValidatedDefinition definition,
            Map<String, ScriptSnapshot> scripts,
            Optional<MetricScriptSnapshot> metrics) {
        Set<String> expectedOrdinary =
                definition.scriptContracts().entrySet().stream()
                        .filter(entry -> entry.getValue() instanceof ScriptContract.Mutation)
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!scripts.keySet().equals(expectedOrdinary)) {
            throw new IllegalArgumentException(
                    "module snapshot ordinary scripts do not match captured recipe roles");
        }
        Optional<ScriptContract.Metrics> expectedMetrics =
                definition.scriptContracts().entrySet().stream()
                        .filter(entry -> ModuleContract.METRICS_SCRIPT_NAME.equals(entry.getKey()))
                        .map(Map.Entry::getValue)
                        .map(ScriptContract.Metrics.class::cast)
                        .findFirst();
        if (expectedMetrics.isPresent() != metrics.isPresent()) {
            throw new IllegalArgumentException(
                    "module snapshot metric script does not match captured recipe role");
        }
        if (metrics.isPresent()
                && !metrics.orElseThrow()
                        .schema()
                        .equals(expectedMetrics.orElseThrow().definitions())) {
            throw new IllegalArgumentException(
                    "module snapshot metric schema does not match captured recipe contract");
        }
    }

    /** Encode one snapshot without reopening mutable module content. */
    public static JsonObject toJson(ModuleSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("module snapshot must not be null");
        }
        JsonObject value = new JsonObject();
        value.addProperty("deployment_id", snapshot.deploymentId());
        value.add("module", InferenceIdentityCodec.toJson(snapshot.module()));
        value.add("exact_recipe", snapshot.exactRecipe());
        value.addProperty("script_set_digest", snapshot.scriptSetDigest());

        JsonObject models = new JsonObject();
        snapshot.activatedModels().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> models.add(entry.getKey(), toJson(entry.getValue())));
        value.add("activated_models", models);

        JsonObject scripts = new JsonObject();
        snapshot.scripts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> scripts.add(entry.getKey(), toJson(entry.getValue())));
        value.add("scripts", scripts);
        snapshot.metrics().ifPresent(metrics -> value.add("metrics", toJson(metrics)));
        return value;
    }

    private static Map<String, ModelSnapshot> parseModels(
            JsonElement value,
            Map<String, String> expectedDigests,
            JsonObject recipeModels,
            boolean runnerOwnedDefaults) {
        JsonObject object = requiredObject(value, "activated_models");
        Map<String, ModelSnapshot> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String modelId = ModuleContract.requireModelId(entry.getKey(), "activated model id");
            ModelSnapshot model = parseModel(entry.getValue());
            String expected = expectedDigests.get(modelId);
            if (expected == null || !expected.equals(model.modelConfigDigest())) {
                throw new IllegalArgumentException(
                        "activated model config digest does not match captured recipe: " + modelId);
            }
            requireRecipeProjection(modelId, model, recipeModels.getAsJsonObject(modelId));
            // Schema 2 snapshots predate recipe-owned cores and halo. Preserve their exact
            // captured profile for inspection; never invent missing historical settings.
            if (!runnerOwnedDefaults) {
                model.largeRegionProfile()
                        .ifPresent(
                                profile ->
                                        LargeRegionProfileCodec.requireRecipeSettings(
                                                profile,
                                                recipeModels
                                                        .getAsJsonObject(modelId)
                                                        .getAsJsonObject("input")));
            }
            result.put(modelId, model);
        }
        return Map.copyOf(result);
    }

    private static void requirePublishedRecipe(JsonObject recipeModels) {
        for (Map.Entry<String, JsonElement> entry : recipeModels.entrySet()) {
            JsonObject weights = entry.getValue().getAsJsonObject().getAsJsonObject("weights");
            if ("local".equals(requiredString(weights, "type"))) {
                throw new IllegalArgumentException(
                        "executable module snapshot cannot use local weights: " + entry.getKey());
            }
        }
    }

    private static void requireRecipeProjection(
            String modelId, ModelSnapshot model, JsonObject recipeModel) {
        if (recipeModel == null) {
            throw new IllegalArgumentException(
                    "activated model is absent from captured recipe: " + modelId);
        }
        JsonObject recipeInput = recipeModel.getAsJsonObject("input");
        JsonObject recipeOutput = recipeModel.getAsJsonObject("output");
        if (recipeModel.has("backbone") || recipeInput.has("acceptCriteria")) {
            throw new IllegalArgumentException(
                    "activated model contains an unsupported forward feature: " + modelId);
        }
        String inputType = requiredString(recipeInput, "type");
        ModelInputType expectedInputType =
                switch (inputType) {
                    case "field" -> ModelInputType.FIELD;
                    case "crop" -> ModelInputType.CROP;
                    default -> throw new IllegalArgumentException("unsupported recipe input type");
                };
        Optional<CropFraming> expectedFraming =
                recipeInput.has("framing")
                        ? Optional.of(
                                new CropFraming(
                                        CanonicalCodec.requireNonnegativeInt32(
                                                recipeInput
                                                        .getAsJsonObject("framing")
                                                        .get("paddingModelPx"),
                                                "paddingModelPx")))
                        : Optional.empty();
        Optional<VariableFieldProfile> expectedFieldProfile =
                recipeInput.has("fieldProfile")
                        ? Optional.of(parseVariableFieldProfile(recipeInput.get("fieldProfile")))
                        : Optional.empty();
        ModelInputSnapshot expectedInput =
                new ModelInputSnapshot(
                        expectedInputType,
                        CanonicalCodec.requirePositiveFiniteDouble(
                                recipeInput.get("micronsPerPixel"), "micronsPerPixel"),
                        expectedFieldProfile,
                        expectedFraming);

        ModelOutput expectedOutput = ModelOutputCodec.parse(recipeOutput, "recipe model output");
        Set<String> expectedArtifactRoles = Set.of("weights");
        if (!requiredString(recipeModel, "arch").equals(model.architecture())
                || !expectedInput.equals(model.input())
                || !expectedOutput.equals(model.output())
                || !expectedArtifactRoles.equals(model.artifactDigests().keySet())) {
            throw new IllegalArgumentException(
                    "activated model projection does not match captured recipe: " + modelId);
        }
    }

    private static ModelSnapshot parseModel(JsonElement value) {
        JsonObject object =
                fieldsObject(value, MODEL_REQUIRED_FIELDS, MODEL_OPTIONAL_FIELDS, "model snapshot");
        JsonObject artifacts = requiredObject(object.get("artifact_digests"), "artifact_digests");
        Map<String, String> artifactDigests = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : artifacts.entrySet()) {
            String role = CanonicalCodec.requireNonblank(entry.getKey(), "artifact role");
            artifactDigests.put(role, requiredString(entry.getValue(), "artifact_digests." + role));
        }
        return new ModelSnapshot(
                requiredString(object, "architecture"),
                requiredString(object, "gpu"),
                requiredString(object, "model_config_digest"),
                parseInput(object.get("input")),
                parseOutput(object.get("output")),
                RunnerCapabilitiesCodec.parse(object.get("capabilities")),
                object.has("large_region_profile")
                        ? Optional.of(
                                LargeRegionProfileCodec.parse(object.get("large_region_profile")))
                        : Optional.empty(),
                artifactDigests,
                object.has("halo_px_override")
                        ? Optional.of(
                                CanonicalCodec.requireNonnegativeInt32(
                                        object.get("halo_px_override"), "halo_px_override"))
                        : Optional.empty());
    }

    private static JsonObject toJson(ModelSnapshot model) {
        JsonObject value = new JsonObject();
        value.addProperty("architecture", model.architecture());
        value.addProperty("gpu", model.gpu());
        value.addProperty("model_config_digest", model.modelConfigDigest());
        value.add("input", toJson(model.input()));
        value.add("output", toJson(model.output()));
        value.add("capabilities", RunnerCapabilitiesCodec.toJson(model.capabilities()));
        model.largeRegionProfile()
                .ifPresent(
                        profile ->
                                value.add(
                                        "large_region_profile",
                                        LargeRegionProfileCodec.toJson(profile)));
        JsonObject artifacts = new JsonObject();
        model.artifactDigests().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> artifacts.addProperty(entry.getKey(), entry.getValue()));
        value.add("artifact_digests", artifacts);
        model.haloPxOverride().ifPresent(halo -> value.addProperty("halo_px_override", halo));
        return value;
    }

    private static ModelInputSnapshot parseInput(JsonElement value) {
        JsonObject object =
                fieldsObject(
                        value,
                        INPUT_REQUIRED_FIELDS,
                        INPUT_OPTIONAL_FIELDS,
                        "model input snapshot");
        ModelInputType type =
                switch (requiredString(object, "type")) {
                    case "field" -> ModelInputType.FIELD;
                    case "crop" -> ModelInputType.CROP;
                    default -> throw new IllegalArgumentException("unsupported model input type");
                };
        Optional<CropFraming> framing =
                object.has("framing")
                        ? Optional.of(parseFraming(object.get("framing")))
                        : Optional.empty();
        Optional<VariableFieldProfile> fieldProfile =
                object.has("field_profile")
                        ? Optional.of(parseVariableFieldProfile(object.get("field_profile")))
                        : Optional.empty();
        return new ModelInputSnapshot(
                type,
                CanonicalCodec.requirePositiveFiniteDouble(object.get("target_mpp"), "target_mpp"),
                fieldProfile,
                framing);
    }

    private static JsonObject toJson(ModelInputSnapshot input) {
        JsonObject value = new JsonObject();
        value.addProperty("type", input.type() == ModelInputType.FIELD ? "field" : "crop");
        value.addProperty("target_mpp", input.targetMpp());
        input.fieldProfile()
                .ifPresent(
                        profile -> value.add("field_profile", TrainingTaskCodec.toJson(profile)));
        input.framing().ifPresent(framing -> value.add("framing", toJson(framing)));
        return value;
    }

    private static VariableFieldProfile parseVariableFieldProfile(JsonElement value) {
        var profile = TrainingTaskCodec.parseFieldProfile(value);
        if (!(profile instanceof VariableFieldProfile variable)) {
            throw new IllegalArgumentException(
                    "model input snapshot field_profile must use "
                            + TrainingTaskCodec.VARIABLE_FIELD_PLANNER);
        }
        return variable;
    }

    private static CropFraming parseFraming(JsonElement value) {
        JsonObject object = exactObject(value, FRAMING_FIELDS, "crop framing");
        return new CropFraming(
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("padding_model_px"), "padding_model_px"));
    }

    private static JsonObject toJson(CropFraming framing) {
        JsonObject value = new JsonObject();
        value.addProperty("padding_model_px", framing.paddingModelPx());
        return value;
    }

    private static ModelOutput parseOutput(JsonElement value) {
        return ModelOutputCodec.parse(value, "model output snapshot");
    }

    private static JsonObject toJson(ModelOutput output) {
        return ModelOutputCodec.toJson(output);
    }

    private static Map<String, ScriptSnapshot> parseScripts(JsonElement value) {
        JsonObject object = requiredObject(value, "scripts");
        Map<String, ScriptSnapshot> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            ScriptSnapshot script = parseScript(entry.getValue());
            if (!entry.getKey().equals(script.filename())) {
                throw new IllegalArgumentException("script key does not match filename");
            }
            result.put(entry.getKey(), script);
        }
        return Map.copyOf(result);
    }

    private static ScriptSnapshot parseScript(JsonElement value) {
        JsonObject object = exactObject(value, SCRIPT_FIELDS, "script snapshot");
        return new ScriptSnapshot(
                requiredString(object, "filename"),
                requiredString(object, "source"),
                requiredString(object, "digest"));
    }

    private static JsonObject toJson(ScriptSnapshot script) {
        JsonObject value = new JsonObject();
        value.addProperty("filename", script.filename());
        value.addProperty("source", script.source());
        value.addProperty("digest", script.digest());
        return value;
    }

    private static MetricScriptSnapshot parseMetrics(JsonElement value) {
        JsonObject object = exactObject(value, METRIC_SCRIPT_FIELDS, "metric script snapshot");
        ScriptSnapshot script = parseScript(object.get("script"));
        List<MetricInfo> schema = parseMetricSchema(object.get("schema"));
        List<MetricInfo> derived =
                qupath.ext.histoforge.modules.ScriptHeaderParser.parseMetrics(script.source());
        if (!schema.equals(derived)) {
            throw new IllegalArgumentException(
                    "persisted metric schema does not match captured script source");
        }
        return new MetricScriptSnapshot(script, schema);
    }

    private static JsonObject toJson(MetricScriptSnapshot metrics) {
        JsonObject value = new JsonObject();
        value.add("script", toJson(metrics.script()));
        JsonArray schema = new JsonArray();
        metrics.schema().forEach(metric -> schema.add(toJson(metric)));
        value.add("schema", schema);
        return value;
    }

    private static List<MetricInfo> parseMetricSchema(JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("metric schema must be a nonempty array");
        }
        List<MetricInfo> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject metric =
                    fieldsObject(
                            item,
                            METRIC_REQUIRED_FIELDS,
                            METRIC_OPTIONAL_FIELDS,
                            "metric definition");
            result.add(
                    new MetricInfo(
                            requiredString(metric, "name"),
                            requiredString(metric, "label"),
                            requiredString(metric, "type"),
                            metric.has("unit") ? requiredString(metric, "unit") : null,
                            CanonicalCodec.requireNonnegativeInt32(
                                    metric.get("decimals"), "decimals"),
                            requiredString(metric, "aggregation")));
        }
        return List.copyOf(result);
    }

    private static JsonObject toJson(MetricInfo metric) {
        JsonObject value = new JsonObject();
        value.addProperty("name", metric.name());
        value.addProperty("label", metric.label());
        value.addProperty("type", metric.type());
        if (metric.unit() != null) {
            value.addProperty("unit", metric.unit());
        }
        value.addProperty("decimals", metric.decimals());
        value.addProperty("aggregation", metric.aggregation());
        return value;
    }

    private static List<String> stringArray(JsonElement value, String path) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException(path + " must be a nonempty array");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            result.add(requiredString(item, path));
        }
        return List.copyOf(result);
    }

    private static JsonObject exactObject(JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static JsonObject fieldsObject(
            JsonElement value, Set<String> required, Set<String> optional, String path) {
        JsonObject object = requiredObject(value, path);
        Set<String> allowed = new java.util.HashSet<>(required);
        allowed.addAll(optional);
        if (!object.keySet().containsAll(required) || !allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String field) {
        return requiredString(object.get(field), field);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }
}
