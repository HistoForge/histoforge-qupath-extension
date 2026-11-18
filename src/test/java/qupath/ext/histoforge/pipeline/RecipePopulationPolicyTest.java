package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Shared recipe-position population policy tests. */
class RecipePopulationPolicyTest {

    @Test
    void derivesOrdinaryScriptAndLaterSourceVisibilityFromRecipeOrder() {
        var module = RecipeExecutorTestData.scriptedModule(false);

        RecipePopulationPolicy.OrdinaryScriptPlan script =
                RecipePopulationPolicy.ordinaryScriptPlan(module, "classify.groovy");

        assertEquals(Set.of("parent"), script.earlierModels());
        assertEquals(Set.of("Parent", "Eligible"), script.visibleManualClasses());
        assertEquals(Set.of("Parent", "Eligible"), script.allowedResultClasses());
        assertEquals(
                Set.of("Parent", "Eligible"),
                RecipePopulationPolicy.reachableScriptManualClassesBeforeModel(
                        module, "child", Set.of("parent", "child")));
        assertEquals(
                Set.of(),
                RecipePopulationPolicy.reachableScriptManualClassesBeforeModel(
                        module, "child", Set.of("child")));
        assertEquals(
                Set.of(),
                RecipePopulationPolicy.reachableScriptManualClassesBeforeModel(
                        module, "parent", Set.of("parent", "child")));
    }
}
