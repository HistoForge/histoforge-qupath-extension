package qupath.ext.histoforge.pipeline;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.modules.ModelSpec;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot.ScriptSnapshot;
import qupath.ext.histoforge.modules.ScriptHeaderParser;

/** Pure recipe-order policy shared by script execution and conservative source planning. */
final class RecipePopulationPolicy {

    private RecipePopulationPolicy() {}

    /** Exact population and result-class policy at one ordinary script barrier. */
    record OrdinaryScriptPlan(
            ScriptSnapshot script,
            Set<String> earlierModels,
            Set<String> visibleManualClasses,
            Set<String> allowedResultClasses) {
        OrdinaryScriptPlan {
            script = Objects.requireNonNull(script, "script");
            earlierModels = Set.copyOf(Objects.requireNonNull(earlierModels, "earlierModels"));
            visibleManualClasses =
                    Set.copyOf(
                            Objects.requireNonNull(visibleManualClasses, "visibleManualClasses"));
            allowedResultClasses =
                    Set.copyOf(
                            Objects.requireNonNull(allowedResultClasses, "allowedResultClasses"));
        }
    }

    /** Analyze one ordinary script at its exact recipe position. */
    static OrdinaryScriptPlan ordinaryScriptPlan(ModuleSnapshot module, String scriptFilename) {
        Objects.requireNonNull(module, "module");
        String filename = CanonicalCodec.requireNonblank(scriptFilename, "script_filename");
        if (ModuleContract.METRICS_SCRIPT_NAME.equals(filename)) {
            throw new IllegalArgumentException("metrics.groovy is not an ordinary script");
        }

        ModuleRecipe recipe = module.recipe();
        Set<String> earlierModels = new LinkedHashSet<>();
        Set<String> visibleManualClasses = new LinkedHashSet<>();
        Set<String> earlierModelClasses = new LinkedHashSet<>();
        for (ModuleRecipe.Step step : recipe.steps()) {
            if (step.isModelStep()) {
                earlierModels.add(step.model());
                for (String pathClass : modelClasses(recipe, step.model())) {
                    visibleManualClasses.add(pathClass);
                    earlierModelClasses.add(pathClass);
                }
                continue;
            }
            requireScriptStep(step);
            if (step.script().equals(filename)) {
                ScriptSnapshot script = requiredScript(module, filename);
                List<String> declaredClasses = ScriptHeaderParser.parseClasses(script.source());
                visibleManualClasses.addAll(declaredClasses);
                Set<String> allowed = new LinkedHashSet<>(earlierModelClasses);
                allowed.addAll(declaredClasses);
                return new OrdinaryScriptPlan(script, earlierModels, visibleManualClasses, allowed);
            }
            if (!ModuleContract.METRICS_SCRIPT_NAME.equals(step.script())) {
                visibleManualClasses.addAll(
                        ScriptHeaderParser.parseClasses(
                                requiredScript(module, step.script()).source()));
            }
        }
        throw new IllegalArgumentException("ordinary script is not a recipe step: " + filename);
    }

    /**
     * Return manual classes visible to a reachable ordinary script before {@code modelId}.
     *
     * <p>A script can run only after selected model work for this target could have changed staged
     * state. This remains conservative about whether that work actually changes anything; the
     * transaction resolves that later from model results and Fill presence.
     */
    static Set<String> reachableScriptManualClassesBeforeModel(
            ModuleSnapshot module, String modelId, Set<String> selectedModelIds) {
        Objects.requireNonNull(module, "module");
        String target = ModuleContract.requireModelId(modelId, "model_id");
        Set<String> selectedModels =
                Set.copyOf(Objects.requireNonNull(selectedModelIds, "selectedModelIds"));
        ModuleRecipe recipe = module.recipe();
        Set<String> accumulatedVisible = new LinkedHashSet<>();
        Set<String> visibleToAnyScript = new LinkedHashSet<>();
        boolean selectedModelMayHaveChanged = false;
        for (ModuleRecipe.Step step : recipe.steps()) {
            if (step.isModelStep()) {
                if (step.model().equals(target)) {
                    return Set.copyOf(visibleToAnyScript);
                }
                accumulatedVisible.addAll(modelClasses(recipe, step.model()));
                selectedModelMayHaveChanged |= selectedModels.contains(step.model());
                continue;
            }
            requireScriptStep(step);
            if (!ModuleContract.METRICS_SCRIPT_NAME.equals(step.script())) {
                accumulatedVisible.addAll(
                        ScriptHeaderParser.parseClasses(
                                requiredScript(module, step.script()).source()));
                if (selectedModelMayHaveChanged) {
                    visibleToAnyScript.addAll(accumulatedVisible);
                }
            }
        }
        throw new IllegalArgumentException("model is not a recipe step: " + target);
    }

    private static List<String> modelClasses(ModuleRecipe recipe, String modelId) {
        ModelSpec model = recipe.models().get(modelId);
        if (model == null || model.output() == null) {
            throw new IllegalArgumentException(
                    "captured recipe lacks model output classes: " + modelId);
        }
        List<String> classes = model.output().emittedClasses();
        if (classes.isEmpty()) {
            throw new IllegalArgumentException(
                    "captured recipe model output classes must be nonempty: " + modelId);
        }
        return classes.stream()
                .map(value -> CanonicalCodec.requirePathClassName(value, "model output class"))
                .toList();
    }

    private static ScriptSnapshot requiredScript(ModuleSnapshot module, String filename) {
        ScriptSnapshot script = module.scripts().get(filename);
        if (script == null) {
            throw new IllegalArgumentException(
                    "captured module lacks ordinary script: " + filename);
        }
        return script;
    }

    private static void requireScriptStep(ModuleRecipe.Step step) {
        if (!step.isScriptStep()) {
            throw new IllegalArgumentException(
                    "captured recipe step has no model or script implementation");
        }
    }
}
