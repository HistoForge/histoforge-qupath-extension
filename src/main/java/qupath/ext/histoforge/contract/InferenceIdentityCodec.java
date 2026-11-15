package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.modules.ModuleContract;

/** Exact module, producer, rasterizer, and backend execution identities. */
public final class InferenceIdentityCodec {

    private static final Set<String> MODULE_CONTENT_FIELDS =
            Set.of("module_ref", "recipe_digest", "content_digest");
    private static final Set<String> MODULE_CONTENT_FIELDS_WITH_COMMIT =
            Set.of("module_ref", "resolved_commit_sha", "recipe_digest", "content_digest");
    private static final Set<String> STEP_IDENTITY_FIELDS = Set.of("module_ref", "step_id");
    private static final Set<String> RASTERIZER_FIELDS =
            Set.of("build", "rasterization_contract_digest");
    private static final Set<String> BACKEND_MODEL_FIELDS =
            Set.of(
                    "module",
                    "model_id",
                    "architecture",
                    "gpu",
                    "model_config_digest",
                    "artifact_digests",
                    "runner_build",
                    "rasterizer",
                    "preprocessing_contract_digest");

    private InferenceIdentityCodec() {}

    /** Exact content behind one mutable module reference. */
    public record ModuleContentIdentity(
            ModuleRef moduleRef,
            Optional<String> resolvedCommitSha,
            String recipeDigest,
            String contentDigest) {
        public ModuleContentIdentity {
            moduleRef = Objects.requireNonNull(moduleRef, "module_ref");
            resolvedCommitSha =
                    Objects.requireNonNull(resolvedCommitSha, "resolved_commit_sha")
                            .map(
                                    value ->
                                            ModuleContract.requireCommitSha(
                                                    value, "resolved_commit_sha"));
            recipeDigest = CanonicalCodec.requireSha256(recipeDigest, "recipe_digest");
            contentDigest = CanonicalCodec.requireSha256(contentDigest, "content_digest");
        }
    }

    /** Module and model step that own one inference output. */
    public record ModelStepIdentity(ModuleRef moduleRef, String stepId) {
        public ModelStepIdentity {
            moduleRef = Objects.requireNonNull(moduleRef, "module_ref");
            stepId = ModuleContract.requireModelId(stepId, "step_id");
        }
    }

    /** Exact source-decoder and target-grid behavior identity. */
    public record RasterizerIdentity(
            BuildIdentityCodec.SoftwareBuildIdentity build, String rasterizationContractDigest) {
        public RasterizerIdentity {
            build = Objects.requireNonNull(build, "build");
            rasterizationContractDigest =
                    CanonicalCodec.requireSha256(
                            rasterizationContractDigest, "rasterization_contract_digest");
        }
    }

    /** Complete exact model, artifact, runner, rasterizer, and preprocessing identity. */
    public record BackendModelIdentity(
            ModuleContentIdentity module,
            String modelId,
            String architecture,
            String gpu,
            String modelConfigDigest,
            Map<String, String> artifactDigests,
            BuildIdentityCodec.SoftwareBuildIdentity runnerBuild,
            RasterizerIdentity rasterizer,
            String preprocessingContractDigest) {
        public BackendModelIdentity {
            module = Objects.requireNonNull(module, "module");
            if (module.resolvedCommitSha().isEmpty()
                    && ModuleContract.workspaceTrainingRunId(module.moduleRef()).isEmpty()) {
                throw new IllegalArgumentException(
                        "executable backend module identity requires a resolved commit or reserved training-result ref");
            }
            modelId = ModuleContract.requireModelId(modelId, "model_id");
            architecture = CanonicalCodec.requireNonblank(architecture, "architecture");
            gpu = CanonicalCodec.requireNonblank(gpu, "gpu");
            modelConfigDigest =
                    CanonicalCodec.requireSha256(modelConfigDigest, "model_config_digest");
            artifactDigests = immutableArtifactDigests(artifactDigests);
            runnerBuild = Objects.requireNonNull(runnerBuild, "runner_build");
            rasterizer = Objects.requireNonNull(rasterizer, "rasterizer");
            preprocessingContractDigest =
                    CanonicalCodec.requireSha256(
                            preprocessingContractDigest, "preprocessing_contract_digest");
        }
    }

    /** Parse one exact installed module-content identity. */
    public static ModuleContentIdentity parseModuleContentIdentity(JsonElement value) {
        JsonObject object = requiredObject(value, "module content identity");
        requireFields(
                object,
                object.has("resolved_commit_sha")
                        ? MODULE_CONTENT_FIELDS_WITH_COMMIT
                        : MODULE_CONTENT_FIELDS,
                "module content identity");
        return new ModuleContentIdentity(
                parseModuleRef(object.get("module_ref"), "module_ref"),
                object.has("resolved_commit_sha")
                        ? Optional.of(requiredString(object, "resolved_commit_sha"))
                        : Optional.empty(),
                requiredString(object, "recipe_digest"),
                requiredString(object, "content_digest"));
    }

    /** Parse one exact model producer identity. */
    public static ModelStepIdentity parseModelStepIdentity(JsonElement value) {
        JsonObject object = requireExactObject(value, STEP_IDENTITY_FIELDS, "model step identity");
        return new ModelStepIdentity(
                parseModuleRef(object.get("module_ref"), "module_ref"),
                requiredString(object, "step_id"));
    }

    /** Parse one exact source-decoder and rasterizer identity. */
    public static RasterizerIdentity parseRasterizerIdentity(JsonElement value) {
        JsonObject object = requireExactObject(value, RASTERIZER_FIELDS, "rasterizer identity");
        return new RasterizerIdentity(
                BuildIdentityCodec.parseSoftwareBuildIdentity(object.get("build")),
                requiredString(object, "rasterization_contract_digest"));
    }

    /** Parse one complete exact backend model identity. */
    public static BackendModelIdentity parseBackendModelIdentity(JsonElement value) {
        JsonObject object =
                requireExactObject(value, BACKEND_MODEL_FIELDS, "backend model identity");
        return new BackendModelIdentity(
                parseModuleContentIdentity(object.get("module")),
                requiredString(object, "model_id"),
                requiredString(object, "architecture"),
                requiredString(object, "gpu"),
                requiredString(object, "model_config_digest"),
                parseArtifactDigests(object.get("artifact_digests")),
                BuildIdentityCodec.parseSoftwareBuildIdentity(object.get("runner_build")),
                parseRasterizerIdentity(object.get("rasterizer")),
                requiredString(object, "preprocessing_contract_digest"));
    }

    /** Encode one module-content identity using exact lower-snake fields. */
    public static JsonObject toJson(ModuleContentIdentity identity) {
        JsonObject value = new JsonObject();
        value.addProperty("module_ref", identity.moduleRef().toString());
        identity.resolvedCommitSha()
                .ifPresent(commit -> value.addProperty("resolved_commit_sha", commit));
        value.addProperty("recipe_digest", identity.recipeDigest());
        value.addProperty("content_digest", identity.contentDigest());
        return value;
    }

    /** Encode one model producer identity using exact lower-snake fields. */
    public static JsonObject toJson(ModelStepIdentity identity) {
        JsonObject value = new JsonObject();
        value.addProperty("module_ref", identity.moduleRef().toString());
        value.addProperty("step_id", identity.stepId());
        return value;
    }

    /** Encode one exact rasterizer identity. */
    public static JsonObject toJson(RasterizerIdentity identity) {
        JsonObject value = new JsonObject();
        value.add("build", BuildIdentityCodec.toJson(identity.build()));
        value.addProperty("rasterization_contract_digest", identity.rasterizationContractDigest());
        return value;
    }

    /** Encode one complete backend model identity. */
    public static JsonObject toJson(BackendModelIdentity identity) {
        JsonObject value = new JsonObject();
        value.add("module", toJson(identity.module()));
        value.addProperty("model_id", identity.modelId());
        value.addProperty("architecture", identity.architecture());
        value.addProperty("gpu", identity.gpu());
        value.addProperty("model_config_digest", identity.modelConfigDigest());
        JsonObject artifacts = new JsonObject();
        identity.artifactDigests().forEach(artifacts::addProperty);
        value.add("artifact_digests", artifacts);
        value.add("runner_build", BuildIdentityCodec.toJson(identity.runnerBuild()));
        value.add("rasterizer", toJson(identity.rasterizer()));
        value.addProperty("preprocessing_contract_digest", identity.preprocessingContractDigest());
        return value;
    }

    /** Return the canonical digest retained by committed backend-identity evidence. */
    public static String backendModelIdentityDigest(BackendModelIdentity identity) {
        return CanonicalCodec.canonicalDigest(toJson(identity));
    }

    private static Map<String, String> parseArtifactDigests(JsonElement value) {
        JsonObject object = requiredObject(value, "artifact_digests");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            result.put(
                    CanonicalCodec.requireNonblank(entry.getKey(), "artifact role"),
                    requiredString(entry.getValue(), "artifact_digests." + entry.getKey()));
        }
        return immutableArtifactDigests(result);
    }

    private static Map<String, String> immutableArtifactDigests(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                    "artifact_digests must contain at least one model artifact");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String role = CanonicalCodec.requireNonblank(entry.getKey(), "artifact role");
            result.put(
                    role,
                    CanonicalCodec.requireSha256(entry.getValue(), "artifact_digests." + role));
        }
        return Map.copyOf(result);
    }

    private static ModuleRef parseModuleRef(JsonElement value, String path) {
        return ModuleRef.parse(requiredString(value, path))
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        path + " must be a canonical owner/name module ref"));
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        requireFields(object, fields, path);
        return object;
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
}
