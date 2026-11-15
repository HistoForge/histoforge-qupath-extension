package qupath.ext.histoforge.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;

/** Owns recipe-order model selection and conservative downstream dependency closure. */
public final class RecipeDependencyGraph {

    private final List<String> modelOrder;
    private final Set<String> modelIds;
    private final Map<String, Set<String>> edges;

    private RecipeDependencyGraph(
            List<String> modelOrder, Set<String> modelIds, Map<String, Set<String>> edges) {
        this.modelOrder = List.copyOf(modelOrder);
        this.modelIds = Set.copyOf(modelIds);
        Map<String, Set<String>> copied = new LinkedHashMap<>();
        edges.forEach((key, value) -> copied.put(key, Set.copyOf(value)));
        this.edges = Map.copyOf(copied);
    }

    /** Build the exact dependency graph implied by validated recipe order and parent links. */
    public static RecipeDependencyGraph from(ModuleRecipe recipe) {
        Objects.requireNonNull(recipe, "recipe");
        List<ModuleRecipe.Step> steps = recipe.steps();
        List<String> modelOrder = new ArrayList<>();
        Set<String> modelIds = new LinkedHashSet<>();
        Map<String, Integer> modelPositions = new LinkedHashMap<>();
        Map<String, Set<String>> edges = new LinkedHashMap<>();

        for (int index = 0; index < steps.size(); index++) {
            ModuleRecipe.Step step = Objects.requireNonNull(steps.get(index), "recipe step");
            String id = stepId(step);
            if (edges.putIfAbsent(id, new LinkedHashSet<>()) != null) {
                throw new IllegalArgumentException("recipe step identifiers must be unique: " + id);
            }
            if (step.isModelStep()) {
                if (!modelIds.add(step.model())) {
                    throw new IllegalArgumentException(
                            "recipe model steps must be unique: " + step.model());
                }
                modelOrder.add(step.model());
                modelPositions.put(step.model(), index);
            }
        }
        if (modelOrder.isEmpty()) {
            throw new IllegalArgumentException("recipe must contain at least one model step");
        }

        for (ModuleRecipe.Step step : steps) {
            if (!step.isRegionGated()) {
                continue;
            }
            if (!modelIds.contains(step.parentModel())) {
                throw new IllegalArgumentException(
                        "region-gated parent_model is not a recipe model: " + step.parentModel());
            }
            edges.get(step.parentModel()).add(step.model());
        }

        for (int scriptIndex = 0; scriptIndex < steps.size(); scriptIndex++) {
            ModuleRecipe.Step script = steps.get(scriptIndex);
            if (!isMutationScript(script)) {
                continue;
            }
            for (int earlier = 0; earlier < scriptIndex; earlier++) {
                ModuleRecipe.Step source = steps.get(earlier);
                if (source.isModelStep() || isMutationScript(source)) {
                    edges.get(stepId(source)).add(script.script());
                }
            }
            for (int later = scriptIndex + 1; later < steps.size(); later++) {
                ModuleRecipe.Step target = steps.get(later);
                if (!target.isRegionGated()) {
                    continue;
                }
                Integer parentPosition = modelPositions.get(target.parentModel());
                if (parentPosition == null) {
                    throw new IllegalArgumentException(
                            "region-gated parent_model is not a recipe model: "
                                    + target.parentModel());
                }
                if (parentPosition < scriptIndex) {
                    edges.get(script.script()).add(target.model());
                }
            }
        }
        return new RecipeDependencyGraph(modelOrder, modelIds, edges);
    }

    /** Return every model required by this action in deterministic recipe order. */
    public List<String> requiredModelIds(ActionSelectionCodec.ImageAction action) {
        Objects.requireNonNull(action, "action");
        if (action instanceof ActionSelectionCodec.Metrics) {
            return List.of();
        }
        ActionSelectionCodec.ModelWorkSelection work = modelWork(action);
        if (work instanceof ActionSelectionCodec.AllModels) {
            return modelOrder;
        }

        Set<String> selected = new LinkedHashSet<>();
        for (ActionSelectionCodec.StepTargets step :
                ((ActionSelectionCodec.SelectedModels) work).steps()) {
            if (!modelIds.contains(step.stepId())) {
                throw new IllegalArgumentException(
                        "selected step is not a recipe model: " + step.stepId());
            }
            selected.add(step.stepId());
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("model action selects no recipe models");
        }
        Set<String> required =
                action instanceof ActionSelectionCodec.Redo
                        ? Set.copyOf(downstreamModelClosure(selected))
                        : selected;
        return modelOrder.stream().filter(required::contains).toList();
    }

    /** Return selected models plus every downstream model, in deterministic recipe order. */
    public List<String> downstreamModelClosure(Collection<String> selectedModels) {
        if (selectedModels == null || selectedModels.isEmpty()) {
            throw new IllegalArgumentException("selectedModels must be nonempty");
        }
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (String selected : selectedModels) {
            if (!modelIds.contains(selected)) {
                throw new IllegalArgumentException(
                        "selected step is not a recipe model: " + selected);
            }
            pending.addLast(selected);
        }

        Set<String> visited = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            edges.getOrDefault(current, Set.of()).forEach(pending::addLast);
        }
        return modelOrder.stream().filter(visited::contains).toList();
    }

    /** Return the validated recipe model order. */
    public List<String> modelOrder() {
        return modelOrder;
    }

    private static ActionSelectionCodec.ModelWorkSelection modelWork(
            ActionSelectionCodec.ImageAction action) {
        return action instanceof ActionSelectionCodec.Fill fill
                ? fill.work()
                : ((ActionSelectionCodec.Redo) action).work();
    }

    private static boolean isMutationScript(ModuleRecipe.Step step) {
        return step.isScriptStep() && !ModuleContract.METRICS_SCRIPT_NAME.equals(step.script());
    }

    private static String stepId(ModuleRecipe.Step step) {
        if (step.isModelStep() == step.isScriptStep()) {
            throw new IllegalArgumentException(
                    "recipe step must contain exactly one implementation");
        }
        return step.isModelStep() ? step.model() : step.script();
    }
}
