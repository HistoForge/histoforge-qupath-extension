package qupath.ext.histoforge.ui.training;

import java.util.List;
import java.util.Objects;
import javafx.collections.ListChangeListener;
import javafx.scene.control.ComboBox;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAvailable;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.ui.ModulesRegistry;

/** Owns the Train tab's exact installed-module and trainable-model selection. */
final class TrainingModelPicker {

    private final ComboBox<String> moduleComboBox;
    private final ComboBox<String> modelComboBox;
    private final ModulesRegistry registry;
    private final Runnable selectionChanged;
    private boolean updating;
    private Selection lastSelection;

    TrainingModelPicker(
            ComboBox<String> moduleComboBox,
            ComboBox<String> modelComboBox,
            ModulesRegistry registry,
            Runnable selectionChanged) {
        this.moduleComboBox = Objects.requireNonNull(moduleComboBox, "moduleComboBox");
        this.modelComboBox = Objects.requireNonNull(modelComboBox, "modelComboBox");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.selectionChanged = Objects.requireNonNull(selectionChanged, "selectionChanged");
        moduleComboBox.setOnAction(event -> moduleChanged());
        modelComboBox.setOnAction(
                event -> {
                    if (!updating) {
                        notifySelectionChanged();
                    }
                });
        registry.asObservable()
                .addListener((ListChangeListener<InstalledModule>) ignored -> refresh());
    }

    void refresh() {
        updating = true;
        String previousModule = moduleComboBox.getValue();
        List<String> refs =
                registry.asObservable().stream()
                        .filter(module -> !trainableModelIds(module).isEmpty())
                        .map(InstalledModule::ref)
                        .sorted()
                        .toList();
        moduleComboBox.getItems().setAll(refs);
        if (previousModule != null && refs.contains(previousModule)) {
            moduleComboBox.setValue(previousModule);
        } else if (refs.isEmpty()) {
            moduleComboBox.setValue(null);
        } else {
            moduleComboBox.getSelectionModel().selectFirst();
        }
        updateModels();
        updating = false;
        notifySelectionChanged();
    }

    InstalledModule selectedModule() {
        String ref = moduleComboBox.getValue();
        return ref == null ? null : registry.get(ref).orElse(null);
    }

    String selectedModelId() {
        return modelComboBox.getValue();
    }

    boolean hasTrainableModules() {
        return !moduleComboBox.getItems().isEmpty();
    }

    void setDisabled(boolean disabled) {
        moduleComboBox.setDisable(disabled || moduleComboBox.getItems().isEmpty());
        modelComboBox.setDisable(disabled || modelComboBox.getItems().isEmpty());
    }

    private void moduleChanged() {
        if (updating) {
            return;
        }
        updating = true;
        updateModels();
        updating = false;
        notifySelectionChanged();
    }

    private void updateModels() {
        InstalledModule module = selectedModule();
        String previousModel = modelComboBox.getValue();
        List<String> models = module == null ? List.of() : trainableModelIds(module);
        modelComboBox.getItems().setAll(models);
        if (previousModel != null && models.contains(previousModel)) {
            modelComboBox.setValue(previousModel);
        } else if (models.isEmpty()) {
            modelComboBox.setValue(null);
        } else {
            modelComboBox.getSelectionModel().selectFirst();
        }
    }

    private static List<String> trainableModelIds(InstalledModule module) {
        return module.recipe().steps().stream()
                .filter(step -> step.isModelStep())
                .map(step -> step.model())
                .distinct()
                .filter(
                        id ->
                                module.descriptor().models().get(id).trainingAvailability()
                                        instanceof TrainingAvailable)
                .sorted()
                .toList();
    }

    private void notifySelectionChanged() {
        InstalledModule source = selectedModule();
        Selection selection =
                new Selection(
                        source == null ? null : source.descriptor(),
                        source == null ? null : source.gateway(),
                        selectedModelId());
        if (!selection.equals(lastSelection)) {
            lastSelection = selection;
            selectionChanged.run();
        }
    }

    private record Selection(
            InstalledModuleDescriptor module, GatewayCapabilities gateway, String modelId) {}
}
