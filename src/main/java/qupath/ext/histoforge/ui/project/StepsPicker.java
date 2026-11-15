package qupath.ext.histoforge.ui.project;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModuleRecipe.Step;

/**
 * The model picker beside Fill / Redo. It exposes model steps only; ordinary and metric scripts
 * remain recipe-owned consequences of the explicit action.
 *
 * <p>The coordinator applies the same model selection to every image in the batch. The picker is
 * available only when all checked images share an installed module. Changing that module or its
 * recipe clears the selection.
 */
final class StepsPicker {

    private static final String DEFAULT_LABEL = "All models";

    private final MenuButton button;
    private final Set<String> selected = new LinkedHashSet<>();
    private List<String> modelIds = List.of();
    private String moduleRef; // whose steps currently populate the menu; null = disabled
    private String recipeDigest; // exact recipe version whose model IDs populate the menu

    StepsPicker(MenuButton button) {
        this.button = button;
        button.setText(DEFAULT_LABEL);
        button.setDisable(true);
    }

    /**
     * Repopulate the menu for the given exact recipe. The same ref and recipe digest preserve the
     * selection; a replacement recipe, different ref, or null clears and rebuilds it.
     *
     * @param module the module every checked row shares, or {@code null} if the picker should be
     *     disabled (no rows checked, mixed modules, or the module isn't installed)
     */
    void update(InstalledModule module, boolean blocked) {
        String ref = module == null ? null : module.ref();
        String nextRecipeDigest =
                module == null ? null : module.descriptor().identity().recipeDigest();
        if (Objects.equals(ref, moduleRef) && Objects.equals(nextRecipeDigest, recipeDigest)) {
            button.setDisable(shouldDisable(blocked, module != null, button.getItems().size()));
            return;
        }
        moduleRef = ref;
        recipeDigest = nextRecipeDigest;
        selected.clear();
        modelIds = List.of();
        button.getItems().clear();
        if (module == null) {
            button.setDisable(true);
            button.setText(DEFAULT_LABEL);
            return;
        }
        modelIds =
                module.recipe().steps().stream()
                        .filter(Step::isModelStep)
                        .map(Step::model)
                        .toList();
        for (String id : modelIds) {
            CheckMenuItem item = new CheckMenuItem(id);
            item.setOnAction(
                    e -> {
                        if (item.isSelected()) {
                            selected.add(id);
                        } else {
                            selected.remove(id);
                        }
                        refreshLabel();
                    });
            button.getItems().add(item);
        }
        button.setDisable(shouldDisable(blocked, true, button.getItems().size()));
        refreshLabel();
    }

    static boolean shouldDisable(boolean blocked, boolean hasModule, int modelCount) {
        return blocked || !hasModule || modelCount == 0;
    }

    /** Selected model IDs in recipe order; an empty list means every model. */
    List<String> selectedModelIds() {
        return modelIds.stream().filter(selected::contains).toList();
    }

    private void refreshLabel() {
        if (selected.isEmpty()) {
            button.setText(DEFAULT_LABEL);
        } else if (selected.size() == 1) {
            button.setText(selected.iterator().next());
        } else {
            button.setText(selected.size() + " models");
        }
    }
}
