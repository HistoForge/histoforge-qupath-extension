package qupath.ext.histoforge.modules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec.CoreHaloProfile;
import qupath.ext.histoforge.contract.ModelOutputCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.ModuleCatalogTypes;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.Available;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModelDescriptor;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.Unavailable;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec;
import qupath.ext.histoforge.modules.ModuleContract.ScriptContract;
import qupath.ext.histoforge.modules.ModuleSnapshot.CropFraming;
import qupath.ext.histoforge.modules.ModuleSnapshot.MetricScriptSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelInputType;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ScriptSnapshot;

/** Converts one exact catalog descriptor into an action-specific executable snapshot. */
public final class ModuleActivationValidator {

    private static final Gson GSON = new Gson();

    private ModuleActivationValidator() {}

    /**
     * Revalidate exact returned content and capture only action-required models.
     *
     * <p>This method is pure. Installation is the user-owned code-execution boundary; activation
     * still recomputes every content digest before capturing executable scripts.
     */
    public static ModuleSnapshot activate(
            String deploymentId,
            InstalledModuleDescriptor descriptor,
            ActionSelectionCodec.ImageAction action) {
        return activate(deploymentId, descriptor, action, Optional.empty());
    }

    /** Revalidate content while freezing an optional action-scoped inference GPU override. */
    public static ModuleSnapshot activate(
            String deploymentId,
            InstalledModuleDescriptor descriptor,
            ActionSelectionCodec.ImageAction action,
            Optional<String> inferenceGpuOverride) {
        return activate(deploymentId, descriptor, action, inferenceGpuOverride, Map.of());
    }

    /** Freeze project choices for every required model, including downstream Redo work. */
    public static ModuleSnapshot activate(
            String deploymentId,
            InstalledModuleDescriptor descriptor,
            ActionSelectionCodec.ImageAction action,
            Optional<String> inferenceGpuOverride,
            Map<String, Integer> haloPxOverrides) {
        return activate(
                deploymentId,
                descriptor,
                action,
                inferenceGpuOverride,
                haloPxOverrides,
                InferencePrecision.DEFAULT);
    }

    /** Resolve precision once per model, after the action's GPU choice, and freeze its build. */
    public static ModuleSnapshot activate(
            String deploymentId,
            InstalledModuleDescriptor descriptor,
            ActionSelectionCodec.ImageAction action,
            Optional<String> inferenceGpuOverride,
            Map<String, Integer> haloPxOverrides,
            InferencePrecision precision) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(precision, "precision");
        inferenceGpuOverride = Objects.requireNonNull(inferenceGpuOverride, "inferenceGpuOverride");
        haloPxOverrides = Map.copyOf(haloPxOverrides);

        ModuleContract.ValidatedDefinition definition = revalidateContent(descriptor);
        JsonObject recipeTree = definition.recipe();
        requirePublishedContent(descriptor, recipeTree);

        ModuleRecipe recipe = GSON.fromJson(recipeTree, ModuleRecipe.class);
        List<String> requiredModels = RecipeDependencyGraph.from(recipe).requiredModelIds(action);
        boolean requiresDurable = requiresDurableModelRuns(action);

        Map<String, ModelSnapshot> models = new LinkedHashMap<>();
        for (ModuleRecipe.Step step : recipe.steps()) {
            if (!step.isModelStep() || !requiredModels.contains(step.model())) {
                continue;
            }
            models.put(
                    step.model(),
                    activateModel(
                            step.model(),
                            recipeTree.getAsJsonObject("models").getAsJsonObject(step.model()),
                            descriptor.models().get(step.model()),
                            requiresDurable,
                            inferenceGpuOverride,
                            Optional.ofNullable(haloPxOverrides.get(step.model())),
                            precision));
        }
        if (!models.keySet().equals(new LinkedHashSet<>(requiredModels))) {
            throw new IllegalArgumentException(
                    "action-required models do not match the recipe model steps");
        }

        CapturedScripts captured = captureScripts(recipe, descriptor.scripts(), definition);
        if (action instanceof ActionSelectionCodec.Metrics && captured.metrics().isEmpty()) {
            throw new IllegalStateException("the selected module does not define metrics.groovy");
        }

        return new ModuleSnapshot(
                deploymentId,
                descriptor.identity(),
                recipeTree,
                definition.scriptSetDigest(),
                models,
                captured.mutations(),
                captured.metrics());
    }

    private static ModuleContract.ValidatedDefinition revalidateContent(
            InstalledModuleDescriptor descriptor) {
        ModuleContract.ValidatedDefinition definition =
                ModuleContract.parse(
                        CanonicalCodec.canonicalBytes(descriptor.recipe()), descriptor.scripts());
        if (!definition.recipeDigest().equals(descriptor.identity().recipeDigest())) {
            throw new IllegalArgumentException("catalog recipe_digest does not match recipe");
        }
        if (!definition.scriptSetDigest().equals(descriptor.scriptSetDigest())) {
            throw new IllegalArgumentException("catalog script_set_digest does not match scripts");
        }
        if (!definition.modelConfigDigests().keySet().equals(descriptor.models().keySet())) {
            throw new IllegalArgumentException(
                    "catalog model descriptors do not match recipe models");
        }

        JsonObject recipeModels = definition.recipe().getAsJsonObject("models");
        for (Map.Entry<String, String> expected : definition.modelConfigDigests().entrySet()) {
            InstalledModelDescriptor actual = descriptor.models().get(expected.getKey());
            actual.largeRegionProfile()
                    .ifPresent(
                            profile ->
                                    LargeRegionProfileCodec.requireRecipeSettings(
                                            profile,
                                            recipeModels
                                                    .getAsJsonObject(expected.getKey())
                                                    .getAsJsonObject("input")));
            if (!expected.getValue().equals(actual.modelConfigDigest())) {
                throw new IllegalArgumentException(
                        "model_config_digest does not match recipe model entry: "
                                + expected.getKey());
            }
            Set<String> expectedRoles =
                    recipeModels.getAsJsonObject(expected.getKey()).has("backbone")
                            ? Set.of("weights", "backbone.weights")
                            : Set.of("weights");
            if (!actual.artifactDigests().keySet().equals(expectedRoles)) {
                throw new IllegalArgumentException(
                        "artifact_digests do not match recipe roles: " + expected.getKey());
            }
        }
        return definition;
    }

    private static void requirePublishedContent(
            InstalledModuleDescriptor descriptor, JsonObject recipe) {
        if (descriptor.identity().resolvedCommitSha().isEmpty()
                && ModuleContract.workspaceTrainingRunId(descriptor.identity().moduleRef())
                        .isEmpty()) {
            throw new IllegalStateException(
                    "module activation requires a resolved commit or reserved training-result ref");
        }
        for (Map.Entry<String, JsonElement> entry : recipe.getAsJsonObject("models").entrySet()) {
            JsonObject model = entry.getValue().getAsJsonObject();
            if ("local".equals(model.getAsJsonObject("weights").get("type").getAsString())) {
                throw new IllegalStateException(
                        "module activation rejects local weights: " + entry.getKey());
            }
        }
    }

    private static ModelSnapshot activateModel(
            String modelId,
            JsonObject rawModel,
            InstalledModelDescriptor descriptor,
            boolean requiresDurable,
            Optional<String> inferenceGpuOverride,
            Optional<Integer> haloPxOverride,
            InferencePrecision precision) {
        if (descriptor == null) {
            throw new IllegalArgumentException("missing model descriptor: " + modelId);
        }
        if (descriptor.runnerAvailability() instanceof Unavailable unavailable) {
            throw new IllegalStateException(
                    "model "
                            + modelId
                            + " is unavailable: "
                            + unavailable.code()
                            + ": "
                            + unavailable.message());
        }
        Available available = (Available) descriptor.runnerAvailability();
        if (rawModel.has("backbone") || rawModel.getAsJsonObject("input").has("acceptCriteria")) {
            throw new IllegalStateException(
                    "model " + modelId + " declares an unsupported forward input feature");
        }
        if (requiresDurable
                && !available
                        .capabilities()
                        .modes()
                        .contains(RunnerCapabilitiesCodec.InferenceMode.DURABLE_MODEL_RUN)) {
            throw new IllegalStateException(
                    "slide model work requires durable capability: " + modelId);
        }

        ModelSpec model = GSON.fromJson(rawModel, ModelSpec.class);
        String gpu = inferenceGpuOverride.orElse(model.gpu());
        var capabilities = available.capabilities();
        var bf16 = descriptor.precisionOverrides().get(ModuleCatalogTypes.BF16_OVERRIDE);
        if (precision == InferencePrecision.BF16_IF_APPLICABLE
                && bf16 != null
                && bf16.gpus().contains(gpu)) {
            capabilities =
                    new RunnerCapabilitiesCodec.RunnerCapabilities(
                            capabilities.modes(),
                            capabilities.outputTypes(),
                            capabilities.sourceContainers(),
                            bf16.runnerBuild(),
                            capabilities.rasterizer(),
                            capabilities.preprocessingContractDigest(),
                            capabilities.finite(),
                            capabilities.durableRun());
        }
        ModelInputSnapshot input = inputSnapshot(model.input());
        ModelOutput output =
                ModelOutputCodec.parse(rawModel.get("output"), "models." + modelId + ".output");
        // A stored project choice survives catalog changes, but only an advertised core-halo
        // profile can use it. Finite-only and global-decode models retain their own contracts.
        return new ModelSnapshot(
                model.arch(),
                gpu,
                descriptor.modelConfigDigest(),
                input,
                output,
                capabilities,
                descriptor.largeRegionProfile(),
                descriptor.artifactDigests(),
                descriptor.largeRegionProfile().orElse(null) instanceof CoreHaloProfile
                        ? haloPxOverride
                        : Optional.empty());
    }

    private static ModelInputSnapshot inputSnapshot(ModelSpec.InputSpec input) {
        if ("field".equals(input.type())) {
            return new ModelInputSnapshot(
                    ModelInputType.FIELD,
                    input.micronsPerPixel(),
                    Optional.ofNullable(input.fieldProfile()),
                    Optional.empty());
        }
        if (!"crop".equals(input.type()) || input.framing() == null) {
            throw new IllegalArgumentException("validated model input has an unsupported shape");
        }
        return new ModelInputSnapshot(
                ModelInputType.CROP,
                input.micronsPerPixel(),
                Optional.ofNullable(input.fieldProfile()),
                Optional.of(new CropFraming(input.framing().paddingModelPx())));
    }

    private static CapturedScripts captureScripts(
            ModuleRecipe recipe,
            Map<String, String> sources,
            ModuleContract.ValidatedDefinition definition) {
        Map<String, ScriptSnapshot> mutations = new LinkedHashMap<>();
        Optional<MetricScriptSnapshot> metrics = Optional.empty();
        for (ModuleRecipe.Step step : recipe.steps()) {
            if (!step.isScriptStep()) {
                continue;
            }
            String filename = step.script();
            String source = sources.get(filename);
            ScriptSnapshot script =
                    new ScriptSnapshot(
                            filename,
                            source,
                            CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source)));
            ScriptContract contract = definition.scriptContracts().get(filename);
            if (contract instanceof ScriptContract.Metrics metricContract) {
                metrics =
                        Optional.of(new MetricScriptSnapshot(script, metricContract.definitions()));
            } else if (contract instanceof ScriptContract.Mutation) {
                mutations.put(filename, script);
            } else {
                throw new IllegalArgumentException("missing script contract: " + filename);
            }
        }
        return new CapturedScripts(Map.copyOf(mutations), metrics);
    }

    private static boolean requiresDurableModelRuns(ActionSelectionCodec.ImageAction action) {
        if (action instanceof ActionSelectionCodec.Metrics) {
            return false;
        }
        ActionSelectionCodec.ModelWorkSelection work =
                action instanceof ActionSelectionCodec.Fill fill
                        ? fill.work()
                        : ((ActionSelectionCodec.Redo) action).work();
        return regions(work) instanceof ActionSelectionCodec.SlideRegion;
    }

    private static ActionSelectionCodec.RegionSelection regions(
            ActionSelectionCodec.ModelWorkSelection work) {
        return work instanceof ActionSelectionCodec.AllModels all
                ? all.regions()
                : ((ActionSelectionCodec.SelectedModels) work).regions();
    }

    private record CapturedScripts(
            Map<String, ScriptSnapshot> mutations, Optional<MetricScriptSnapshot> metrics) {}
}
