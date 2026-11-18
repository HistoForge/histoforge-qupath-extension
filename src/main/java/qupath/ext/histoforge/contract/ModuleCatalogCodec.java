package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.Available;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModelDescriptor;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.ModuleCatalogPage;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.RunnerAvailability;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.RunnerUnavailableCode;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.Unavailable;
import qupath.ext.histoforge.modules.ModuleContract;

/** Exact bounded module catalog, model availability, and activation identity codec. */
public final class ModuleCatalogCodec {

    private static final Set<String> PAGE_FIELDS = Set.of("modules");
    private static final Set<String> PAGE_FIELDS_WITH_CURSOR = Set.of("modules", "next_cursor");
    private static final Set<String> MODULE_FIELDS =
            Set.of(
                    "identity",
                    "installed_at",
                    "installed_module_file_bytes",
                    "recipe",
                    "scripts",
                    "script_set_digest",
                    "models");
    private static final Set<String> MODEL_FIELDS =
            Set.of(
                    "model_config_digest",
                    "artifact_digests",
                    "runner_availability",
                    "precision_overrides",
                    "training_availability");
    private static final Set<String> MODEL_FIELDS_WITH_PROFILE =
            Set.of(
                    "model_config_digest",
                    "artifact_digests",
                    "large_region_profile",
                    "runner_availability",
                    "precision_overrides",
                    "training_availability");

    private ModuleCatalogCodec() {}

    /** A missing required execution contract that needs an updated cloud backend. */
    public static final class IncompatibleCatalogException extends IllegalArgumentException {
        private IncompatibleCatalogException(String message) {
            super(message);
        }
    }

    /** Parse and fully revalidate one catalog page. */
    public static ModuleCatalogPage parse(JsonElement value, long maxTrainingTextBytes) {
        JsonObject object = requiredObject(value, "module catalog page");
        requireFields(
                object,
                object.has("next_cursor") ? PAGE_FIELDS_WITH_CURSOR : PAGE_FIELDS,
                "module catalog page");
        if (!object.get("modules").isJsonArray()) {
            throw new IllegalArgumentException("modules must be an array");
        }
        List<InstalledModuleDescriptor> modules = new ArrayList<>();
        for (JsonElement item : object.getAsJsonArray("modules")) {
            modules.add(parseInstalledModuleDescriptor(item, maxTrainingTextBytes));
        }
        ModuleCatalogPage page =
                new ModuleCatalogPage(
                        modules,
                        object.has("next_cursor")
                                ? Optional.of(requiredString(object, "next_cursor"))
                                : Optional.empty());
        requireCursorMatchesLastRef(page);
        return page;
    }

    /** Parse one descriptor and recompute every content-owned digest. */
    public static InstalledModuleDescriptor parseInstalledModuleDescriptor(
            JsonElement value, long maxTrainingTextBytes) {
        JsonObject object = requiredObject(value, "installed module descriptor");
        Set<String> fields = new HashSet<>(MODULE_FIELDS);
        if (object.has("readme_markdown")) {
            fields.add("readme_markdown");
        }
        requireFields(object, fields, "installed module descriptor");
        var identity = InferenceIdentityCodec.parseModuleContentIdentity(object.get("identity"));
        Map<String, String> scripts = parseStringMap(object.get("scripts"), "scripts");
        JsonObject recipe = requiredObject(object.get("recipe"), "recipe").deepCopy();
        ModuleContract.ValidatedDefinition definition =
                ModuleContract.parse(CanonicalCodec.canonicalBytes(recipe), scripts);
        if (!definition.recipeDigest().equals(identity.recipeDigest())) {
            throw new IllegalArgumentException("catalog recipe_digest does not match recipe");
        }
        String scriptDigest = requiredString(object, "script_set_digest");
        if (!definition.scriptSetDigest().equals(scriptDigest)) {
            throw new IllegalArgumentException("catalog script_set_digest does not match scripts");
        }

        JsonObject rawModels = requiredObject(object.get("models"), "models");
        if (!rawModels.keySet().equals(definition.modelConfigDigests().keySet())) {
            throw new IllegalArgumentException(
                    "catalog model descriptors do not match recipe models");
        }
        Map<String, InstalledModelDescriptor> models = new LinkedHashMap<>();
        JsonObject recipeModels = definition.recipe().getAsJsonObject("models");
        for (Map.Entry<String, JsonElement> entry : rawModels.entrySet()) {
            String modelId = ModuleContract.requireModelId(entry.getKey(), "model_id");
            JsonObject recipeModel = recipeModels.getAsJsonObject(modelId);
            Set<String> expectedArtifactRoles =
                    recipeModel.has("backbone")
                            ? Set.of("weights", "backbone.weights")
                            : Set.of("weights");
            models.put(
                    modelId,
                    parseInstalledModelDescriptor(
                            entry.getValue(),
                            definition.modelConfigDigests().get(modelId),
                            expectedArtifactRoles,
                            recipeModel.getAsJsonObject("input"),
                            maxTrainingTextBytes));
        }
        if (identity.resolvedCommitSha().isEmpty()
                && ModuleContract.workspaceTrainingRunId(identity.moduleRef()).isEmpty()
                && models.values().stream()
                        .anyMatch(
                                descriptor ->
                                        descriptor.runnerAvailability() instanceof Available)) {
            throw new IllegalArgumentException(
                    "available catalog model requires a commit or reserved training-result ref");
        }
        return new InstalledModuleDescriptor(
                identity,
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "installed_at"), "installed_at"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("installed_module_file_bytes"), "installed_module_file_bytes"),
                definition.recipe(),
                scripts,
                scriptDigest,
                models,
                object.has("readme_markdown")
                        ? Optional.of(requiredString(object, "readme_markdown"))
                        : Optional.empty());
    }

    /** Encode one catalog page using exact lower-snake wire fields. */
    public static JsonObject toJson(ModuleCatalogPage page, long maxTrainingTextBytes) {
        requireCursorMatchesLastRef(page);
        JsonObject value = new JsonObject();
        var modules = new com.google.gson.JsonArray();
        page.modules().forEach(item -> modules.add(toJson(item, maxTrainingTextBytes)));
        value.add("modules", modules);
        page.nextCursor().ifPresent(cursor -> value.addProperty("next_cursor", cursor));
        return value;
    }

    private static InstalledModelDescriptor parseInstalledModelDescriptor(
            JsonElement value,
            String expectedDigest,
            Set<String> expectedArtifactRoles,
            JsonObject modelInput,
            long maxTrainingTextBytes) {
        JsonObject object = requiredObject(value, "installed model descriptor");
        if (!object.has("precision_overrides")) {
            throw new IncompatibleCatalogException(
                    "Cloud backend does not advertise inference precision. Update cloud backend in Settings before starting new work.");
        }
        requireFields(
                object,
                object.has("large_region_profile") ? MODEL_FIELDS_WITH_PROFILE : MODEL_FIELDS,
                "installed model descriptor");
        String digest = requiredString(object, "model_config_digest");
        if (!expectedDigest.equals(digest)) {
            throw new IllegalArgumentException(
                    "model_config_digest does not match recipe model entry");
        }
        Map<String, String> artifactDigests = parseDigestMap(object.get("artifact_digests"));
        if (!artifactDigests.keySet().equals(expectedArtifactRoles)) {
            throw new IllegalArgumentException(
                    "artifact_digests do not match recipe artifact roles");
        }
        var profile =
                object.has("large_region_profile")
                        ? Optional.of(
                                LargeRegionProfileCodec.parse(object.get("large_region_profile")))
                        : Optional.<LargeRegionProfileCodec.LargeRegionProfile>empty();
        profile.ifPresent(
                valueProfile ->
                        LargeRegionProfileCodec.requireRecipeSettings(valueProfile, modelInput));
        return new InstalledModelDescriptor(
                digest,
                artifactDigests,
                profile,
                parseRunnerAvailability(object.get("runner_availability")),
                parsePrecisionOverrides(object.get("precision_overrides")),
                TrainingCapabilityCodec.parseAvailability(
                        object.get("training_availability"), maxTrainingTextBytes));
    }

    private static Map<String, ModuleCatalogTypes.PrecisionOverride> parsePrecisionOverrides(
            JsonElement value) {
        JsonObject object = requiredObject(value, "precision_overrides");
        Map<String, ModuleCatalogTypes.PrecisionOverride> result = new LinkedHashMap<>();
        for (var entry : object.entrySet()) {
            if (!ModuleCatalogTypes.BF16_OVERRIDE.equals(entry.getKey())) {
                throw new IllegalArgumentException(
                        "unsupported precision override: " + entry.getKey());
            }
            JsonObject override = requiredObject(entry.getValue(), "precision override");
            requireFields(override, Set.of("runner_build", "gpus"), "precision override");
            if (!override.get("gpus").isJsonArray()) {
                throw new IllegalArgumentException("precision override GPUs must be an array");
            }
            List<String> gpus = new ArrayList<>();
            for (JsonElement gpu : override.getAsJsonArray("gpus")) {
                gpus.add(requiredString(gpu, "precision override GPU"));
            }
            result.put(
                    entry.getKey(),
                    new ModuleCatalogTypes.PrecisionOverride(
                            BuildIdentityCodec.parseSoftwareBuildIdentity(
                                    override.get("runner_build")),
                            gpus));
        }
        return result;
    }

    private static RunnerAvailability parseRunnerAvailability(JsonElement value) {
        JsonObject object = requiredObject(value, "runner_availability");
        return switch (requiredString(object, "type")) {
            case "available" -> {
                requireFields(object, Set.of("type", "capabilities"), "runner_availability");
                yield new Available(RunnerCapabilitiesCodec.parse(object.get("capabilities")));
            }
            case "unavailable" -> {
                requireFields(object, Set.of("type", "code", "message"), "runner_availability");
                try {
                    yield new Unavailable(
                            RunnerUnavailableCode.valueOf(requiredString(object, "code")),
                            requiredString(object, "message"));
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException(
                            "runner_availability has an unsupported code", error);
                }
            }
            default ->
                    throw new IllegalArgumentException(
                            "runner_availability has an unsupported type");
        };
    }

    private static JsonObject toJson(
            InstalledModuleDescriptor descriptor, long maxTrainingTextBytes) {
        JsonObject value = new JsonObject();
        value.add("identity", InferenceIdentityCodec.toJson(descriptor.identity()));
        value.addProperty(
                "installed_at",
                CanonicalCodec.rfc3339Millis(descriptor.installedAt(), "installed_at"));
        value.addProperty("installed_module_file_bytes", descriptor.installedModuleFileBytes());
        value.add("recipe", descriptor.recipe().deepCopy());
        JsonObject scripts = new JsonObject();
        descriptor.scripts().forEach(scripts::addProperty);
        value.add("scripts", scripts);
        value.addProperty("script_set_digest", descriptor.scriptSetDigest());
        JsonObject models = new JsonObject();
        descriptor
                .models()
                .forEach((key, item) -> models.add(key, toJson(item, maxTrainingTextBytes)));
        value.add("models", models);
        descriptor.readmeMarkdown().ifPresent(text -> value.addProperty("readme_markdown", text));
        return value;
    }

    private static JsonObject toJson(
            InstalledModelDescriptor descriptor, long maxTrainingTextBytes) {
        JsonObject value = new JsonObject();
        value.addProperty("model_config_digest", descriptor.modelConfigDigest());
        JsonObject artifacts = new JsonObject();
        descriptor.artifactDigests().forEach(artifacts::addProperty);
        value.add("artifact_digests", artifacts);
        descriptor
                .largeRegionProfile()
                .ifPresent(
                        profile ->
                                value.add(
                                        "large_region_profile",
                                        LargeRegionProfileCodec.toJson(profile)));
        value.add("runner_availability", toJson(descriptor.runnerAvailability()));
        JsonObject overrides = new JsonObject();
        descriptor
                .precisionOverrides()
                .forEach(
                        (mode, override) -> {
                            JsonObject encoded = new JsonObject();
                            encoded.add(
                                    "runner_build",
                                    BuildIdentityCodec.toJson(override.runnerBuild()));
                            var gpus = new com.google.gson.JsonArray();
                            override.gpus().forEach(gpus::add);
                            encoded.add("gpus", gpus);
                            overrides.add(mode, encoded);
                        });
        value.add("precision_overrides", overrides);
        value.add(
                "training_availability",
                TrainingCapabilityCodec.toJson(
                        descriptor.trainingAvailability(), maxTrainingTextBytes));
        return value;
    }

    private static JsonObject toJson(RunnerAvailability availability) {
        JsonObject value = new JsonObject();
        if (availability instanceof Available available) {
            value.addProperty("type", "available");
            value.add("capabilities", RunnerCapabilitiesCodec.toJson(available.capabilities()));
        } else if (availability instanceof Unavailable unavailable) {
            value.addProperty("type", "unavailable");
            value.addProperty("code", unavailable.code().name());
            value.addProperty("message", unavailable.message());
        } else {
            throw new IllegalArgumentException("unsupported runner availability");
        }
        return value;
    }

    private static Map<String, String> parseStringMap(JsonElement value, String path) {
        JsonObject object = requiredObject(value, path);
        Map<String, String> result = new LinkedHashMap<>();
        object.entrySet()
                .forEach(
                        entry ->
                                result.put(
                                        CanonicalCodec.requireNonblank(
                                                entry.getKey(), path + " key"),
                                        requiredString(
                                                entry.getValue(), path + "." + entry.getKey())));
        return ModuleCatalogTypes.immutableStringMap(result, path);
    }

    private static Map<String, String> parseDigestMap(JsonElement value) {
        JsonObject object = requiredObject(value, "artifact_digests");
        Map<String, String> result = new LinkedHashMap<>();
        object.entrySet()
                .forEach(
                        entry ->
                                result.put(
                                        entry.getKey(),
                                        requiredString(
                                                entry.getValue(),
                                                "artifact_digests." + entry.getKey())));
        return ModuleCatalogTypes.immutableDigestMap(result);
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void requireFields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    private static String requiredString(JsonObject value, String field) {
        return requiredString(value.get(field), field);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }

    private static void requireCursorMatchesLastRef(ModuleCatalogPage page) {
        if (page.nextCursor().isEmpty()) {
            return;
        }
        String cursorRef = decodeCursor(page.nextCursor().orElseThrow());
        String lastRef = page.modules().getLast().identity().moduleRef().toString();
        if (!cursorRef.equals(lastRef)) {
            throw new IllegalArgumentException(
                    "next_cursor must encode the page's exclusive last ref");
        }
    }

    private static String decodeCursor(String value) {
        final byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("next_cursor is malformed", error);
        }
        for (byte item : decoded) {
            if (Byte.toUnsignedInt(item) > 0x7f) {
                throw new IllegalArgumentException("next_cursor is not ASCII");
            }
        }
        String ref = new String(decoded, StandardCharsets.US_ASCII);
        ModuleRef.parse(ref)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "next_cursor has an invalid module ref"));
        String canonical =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(ref.getBytes(StandardCharsets.US_ASCII));
        if (!canonical.equals(value)) {
            throw new IllegalArgumentException("next_cursor is not canonically encoded");
        }
        return ref;
    }
}
