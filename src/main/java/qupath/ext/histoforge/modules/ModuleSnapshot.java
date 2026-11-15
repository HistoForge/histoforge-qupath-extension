package qupath.ext.histoforge.modules;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;

/** Immutable executable module values captured for one explicit action. */
public record ModuleSnapshot(
        String deploymentId,
        InferenceIdentityCodec.ModuleContentIdentity module,
        JsonObject exactRecipe,
        String scriptSetDigest,
        Map<String, ModelSnapshot> activatedModels,
        Map<String, ScriptSnapshot> scripts,
        Optional<MetricScriptSnapshot> metrics) {

    private static final Gson GSON = new Gson();

    public ModuleSnapshot {
        deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
        module = Objects.requireNonNull(module, "module");
        if (module.resolvedCommitSha().isEmpty()
                && ModuleContract.workspaceTrainingRunId(module.moduleRef()).isEmpty()) {
            throw new IllegalArgumentException(
                    "executable module snapshot requires a resolved commit or reserved training-result ref");
        }
        exactRecipe = Objects.requireNonNull(exactRecipe, "exactRecipe").deepCopy();
        scriptSetDigest = CanonicalCodec.requireSha256(scriptSetDigest, "script_set_digest");
        activatedModels = immutableModels(activatedModels);
        scripts = immutableScripts(scripts);
        metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** Return a detached copy of the exact validated schema-owned recipe tree. */
    @Override
    public JsonObject exactRecipe() {
        return exactRecipe.deepCopy();
    }

    /** Return a fresh typed projection; the exact tree remains the identity authority. */
    public ModuleRecipe recipe() {
        return GSON.fromJson(exactRecipe, ModuleRecipe.class);
    }

    /** Build the exact backend identity expected for one activated model. */
    public InferenceIdentityCodec.BackendModelIdentity backendIdentity(String modelId) {
        String id = ModuleContract.requireModelId(modelId, "model_id");
        ModelSnapshot model = activatedModels.get(id);
        if (model == null) {
            throw new IllegalArgumentException("model is not activated: " + id);
        }
        return new InferenceIdentityCodec.BackendModelIdentity(
                module,
                id,
                model.architecture(),
                model.gpu(),
                model.modelConfigDigest(),
                model.artifactDigests(),
                model.capabilities().runnerBuild(),
                model.capabilities().rasterizer(),
                model.capabilities().preprocessingContractDigest());
    }

    /** Schema-owned distinction between complete field input and parent crop input. */
    public enum ModelInputType {
        FIELD,
        CROP
    }

    /** Crop context measured in target-model pixels. */
    public record CropFraming(int paddingModelPx) {
        public CropFraming {
            if (paddingModelPx < 0) {
                throw new IllegalArgumentException("padding_model_px must be nonnegative");
            }
        }
    }

    /** Captured model input type, target resolution, variable planner, and crop framing. */
    public record ModelInputSnapshot(
            ModelInputType type,
            double targetMpp,
            Optional<VariableFieldProfile> fieldProfile,
            Optional<CropFraming> framing) {
        public ModelInputSnapshot {
            type = Objects.requireNonNull(type, "type");
            targetMpp = CanonicalCodec.requirePositiveFiniteDouble(targetMpp, "target_mpp");
            fieldProfile = Objects.requireNonNull(fieldProfile, "field_profile");
            framing = Objects.requireNonNull(framing, "framing");
            if ((type == ModelInputType.CROP) != framing.isPresent()) {
                throw new IllegalArgumentException("crop input and framing must occur together");
            }
            if (type == ModelInputType.CROP
                    && fieldProfile.isPresent()
                    && fieldProfile.orElseThrow().smallRoiContext()) {
                throw new IllegalArgumentException(
                        "crop field profile cannot own small-ROI context");
            }
        }
    }

    /** Exact executable model values retained by this action snapshot. */
    public record ModelSnapshot(
            String architecture,
            String gpu,
            String modelConfigDigest,
            ModelInputSnapshot input,
            ModelOutput output,
            RunnerCapabilitiesCodec.RunnerCapabilities capabilities,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> largeRegionProfile,
            Map<String, String> artifactDigests,
            Optional<Integer> haloPxOverride) {
        /** Capture the advertised default when no project override was selected. */
        public ModelSnapshot(
                String architecture,
                String gpu,
                String modelConfigDigest,
                ModelInputSnapshot input,
                ModelOutput output,
                RunnerCapabilitiesCodec.RunnerCapabilities capabilities,
                Optional<LargeRegionProfileCodec.LargeRegionProfile> largeRegionProfile,
                Map<String, String> artifactDigests) {
            this(
                    architecture,
                    gpu,
                    modelConfigDigest,
                    input,
                    output,
                    capabilities,
                    largeRegionProfile,
                    artifactDigests,
                    Optional.empty());
        }

        public ModelSnapshot {
            architecture = CanonicalCodec.requireNonblank(architecture, "architecture");
            gpu = CanonicalCodec.requireNonblank(gpu, "gpu");
            modelConfigDigest =
                    CanonicalCodec.requireSha256(modelConfigDigest, "model_config_digest");
            input = Objects.requireNonNull(input, "input");
            output = Objects.requireNonNull(output, "output");
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
            if (!capabilities.outputTypes().contains(ModelOutputCodec.runnerOutputType(output))) {
                throw new IllegalArgumentException(
                        "runner capabilities do not include the model output type");
            }
            largeRegionProfile = Objects.requireNonNull(largeRegionProfile, "large_region_profile");
            if (largeRegionProfile.isPresent()
                    && !capabilities
                            .modes()
                            .contains(RunnerCapabilitiesCodec.InferenceMode.DURABLE_MODEL_RUN)) {
                throw new IllegalArgumentException(
                        "large-region profile requires durable model-run capability");
            }
            artifactDigests = immutableDigests(artifactDigests);
            haloPxOverride = Objects.requireNonNull(haloPxOverride, "halo_px_override");
            if (haloPxOverride.isPresent()) {
                CanonicalCodec.requireNonnegativeInt32(
                        haloPxOverride.orElseThrow(), "halo_px_override");
                if (!(largeRegionProfile.orElse(null)
                        instanceof LargeRegionProfileCodec.CoreHaloProfile)) {
                    throw new IllegalArgumentException(
                            "halo_px_override requires a core-halo profile");
                }
            }
        }
    }

    /** Exact installed Groovy source retained without reopening a mutable module ref. */
    public record ScriptSnapshot(String filename, String source, String digest) {
        public ScriptSnapshot {
            filename = ModuleRecipeValidator.safeRelativeFile(filename, "filename", ".groovy");
            source = Objects.requireNonNull(source, "source");
            String actual = CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source));
            digest = CanonicalCodec.requireSha256(digest, "digest");
            if (!digest.equals(actual)) {
                throw new IllegalArgumentException("script digest does not match source");
            }
        }
    }

    /** Exact metric source paired with its strictly parsed result schema. */
    public record MetricScriptSnapshot(ScriptSnapshot script, List<MetricInfo> schema) {
        public MetricScriptSnapshot {
            script = Objects.requireNonNull(script, "script");
            if (!ModuleContract.METRICS_SCRIPT_NAME.equals(script.filename())) {
                throw new IllegalArgumentException("metric script must be metrics.groovy");
            }
            if (schema == null || schema.isEmpty()) {
                throw new IllegalArgumentException("metric schema must be nonempty");
            }
            schema = List.copyOf(schema);
        }
    }

    private static Map<String, ModelSnapshot> immutableModels(Map<String, ModelSnapshot> models) {
        Objects.requireNonNull(models, "activatedModels");
        Map<String, ModelSnapshot> result = new LinkedHashMap<>();
        models.forEach(
                (key, value) ->
                        result.put(
                                ModuleContract.requireModelId(key, "activated model id"),
                                Objects.requireNonNull(value, "activated model")));
        return Map.copyOf(result);
    }

    private static Map<String, ScriptSnapshot> immutableScripts(
            Map<String, ScriptSnapshot> scripts) {
        Objects.requireNonNull(scripts, "scripts");
        Map<String, ScriptSnapshot> result = new LinkedHashMap<>();
        scripts.forEach(
                (key, value) -> {
                    String filename =
                            ModuleRecipeValidator.safeRelativeFile(
                                    key, "script filename", ".groovy");
                    ScriptSnapshot snapshot = Objects.requireNonNull(value, "script");
                    if (!filename.equals(snapshot.filename())) {
                        throw new IllegalArgumentException(
                                "script map key does not match snapshot filename");
                    }
                    result.put(filename, snapshot);
                });
        return Map.copyOf(result);
    }

    private static Map<String, String> immutableDigests(Map<String, String> digests) {
        if (digests == null || digests.isEmpty()) {
            throw new IllegalArgumentException("artifact_digests must be nonempty");
        }
        Map<String, String> result = new LinkedHashMap<>();
        digests.forEach(
                (key, value) -> {
                    String role = CanonicalCodec.requireNonblank(key, "artifact role");
                    result.put(
                            role, CanonicalCodec.requireSha256(value, "artifact_digests." + role));
                });
        return Map.copyOf(result);
    }
}
