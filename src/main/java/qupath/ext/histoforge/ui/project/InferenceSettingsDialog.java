package qupath.ext.histoforge.ui.project;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.contract.LargeRegionProfileCodec.CoreHaloProfile;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.Available;
import qupath.ext.histoforge.data.ProjectInferenceSettings;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.InformationButton;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/** Edits project-owned halo choices without activating models or starting work. */
final class InferenceSettingsDialog {

    private static final String HELP =
            "A context halo is extra tissue shown around each processed section of a large region. "
                    + "Use Default for the module's recommended amount. Custom changes this surrounding "
                    + "context, not the section size. Values are pixels at the model's resolution; the "
                    + "corresponding micrometres are shown below. More context uses more memory and "
                    + "computation and may change predictions. Cloud resource limits still apply. New Fill "
                    + "and Redo actions use the saved setting; unfinished actions keep their original setting. "
                    + "Saving does not start analysis.";

    private InferenceSettingsDialog() {}

    static void show(QuPathGUI qupath, ModulesRegistry registry) {
        Project<?> project = qupath.getProject();
        if (project == null) {
            Dialogs.showWarningNotification("Inference settings", "Open a QuPath project first.");
            return;
        }
        List<ModuleChoice> modules = assignedModules(project, registry::get);
        if (modules.isEmpty()) {
            Dialogs.showWarningNotification(
                    "Inference settings", "Assign a module to an image first.");
            return;
        }
        Map<String, Map<String, Integer>> stored = ProjectInferenceSettings.read(project);
        Map<ModuleChoice, List<ModelControls>> controls = new LinkedHashMap<>();
        for (ModuleChoice module : modules) {
            controls.put(
                    module,
                    supportedModels(module.installed()).stream()
                            .map(
                                    model ->
                                            new ModelControls(
                                                    model,
                                                    Optional.ofNullable(
                                                            stored.getOrDefault(
                                                                            module.ref(), Map.of())
                                                                    .get(model.id()))))
                            .toList());
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(qupath.getStage());
        dialog.setTitle("Inference settings");
        dialog.setHeaderText("Settings for this project");
        dialog.setResizable(true);
        dialog.setOnShowing(event -> HistoforgeStyles.apply(dialog.getDialogPane().getScene()));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        ((javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.OK))
                .setText("Save");

        ComboBox<ModuleChoice> modulePicker = new ComboBox<>();
        modulePicker.getItems().setAll(modules);
        modulePicker.setMaxWidth(Double.MAX_VALUE);
        VBox modelRows = new VBox(14);
        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add(HistoforgeStyles.ERROR);
        modulePicker
                .valueProperty()
                .addListener(
                        (ignored, previous, current) -> {
                            modelRows.getChildren().clear();
                            List<ModelControls> rows = controls.get(current);
                            if (rows.isEmpty()) {
                                Label empty =
                                        new Label(
                                                current.installed().isEmpty()
                                                        ? "Install this assigned module to view its inference settings."
                                                        : "This module has no models with available large-region processing settings.");
                                empty.setWrapText(true);
                                modelRows.getChildren().add(empty);
                            } else {
                                rows.forEach(row -> modelRows.getChildren().add(row.view));
                            }
                        });
        modulePicker.getSelectionModel().selectFirst();

        HBox heading = new HBox(6, new Label("Context halo"), new InformationButton(HELP));
        heading.setAlignment(Pos.CENTER_LEFT);
        ScrollPane scroll = new ScrollPane(modelRows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(260);
        VBox content =
                new VBox(
                        12,
                        new Label("Module assigned in this project:"),
                        modulePicker,
                        heading,
                        scroll,
                        error);
        content.setPadding(new Insets(8));
        content.setPrefWidth(600);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane()
                .lookupButton(ButtonType.OK)
                .addEventFilter(
                        ActionEvent.ACTION,
                        event -> {
                            try {
                                Map<String, Map<String, Integer>> updated =
                                        new LinkedHashMap<>(stored);
                                for (var module : controls.entrySet()) {
                                    Map<String, Integer> custom =
                                            new LinkedHashMap<>(
                                                    stored.getOrDefault(
                                                            module.getKey().ref(), Map.of()));
                                    for (ModelControls row : module.getValue()) {
                                        try {
                                            Optional<Integer> override = row.override();
                                            if (override.isPresent()) {
                                                custom.put(row.model.id(), override.orElseThrow());
                                            } else {
                                                custom.remove(row.model.id());
                                            }
                                        } catch (IllegalArgumentException failure) {
                                            modulePicker.setValue(module.getKey());
                                            row.customPixels.requestFocus();
                                            throw failure;
                                        }
                                    }
                                    if (custom.isEmpty()) {
                                        updated.remove(module.getKey().ref());
                                    } else {
                                        updated.put(module.getKey().ref(), Map.copyOf(custom));
                                    }
                                }
                                ProjectInferenceSettings.save(project, qupath.getProject(), updated)
                                        .ifPresent(
                                                failure ->
                                                        Dialogs.showErrorMessage(
                                                                "Project Save Incomplete",
                                                                "Inference settings remain in this project session, but QuPath did not confirm the project save. "
                                                                        + "Use Save Project & Refresh to retry: "
                                                                        + failure.getMessage()));
                            } catch (RuntimeException failure) {
                                event.consume();
                                error.setText(failure.getMessage());
                            }
                        });
        dialog.showAndWait();
    }

    /** Assignment scope is the entire project, independent of checked images. */
    static List<ModuleChoice> assignedModules(
            Project<?> project, Function<String, Optional<InstalledModule>> lookup) {
        return project.getImageList().stream()
                .map(entry -> entry.getMetadata().get(HistoforgeKeys.MODULE))
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .map(ref -> new ModuleChoice(ref, lookup.apply(ref)))
                .toList();
    }

    /** Display only advertised CoreHalo models, in recipe order; never invent a profile. */
    static List<ModelSetting> supportedModels(Optional<InstalledModule> installed) {
        if (installed.isEmpty()) {
            return List.of();
        }
        InstalledModule module = installed.orElseThrow();
        List<ModelSetting> result = new ArrayList<>();
        for (var step : module.recipe().steps()) {
            if (!step.isModelStep()) {
                continue;
            }
            var descriptor = module.descriptor().models().get(step.model());
            if (descriptor.runnerAvailability() instanceof Available
                    && descriptor.largeRegionProfile().orElse(null)
                            instanceof CoreHaloProfile profile) {
                result.add(
                        new ModelSetting(
                                step.model(),
                                module.recipe()
                                        .models()
                                        .get(step.model())
                                        .input()
                                        .micronsPerPixel(),
                                profile));
            }
        }
        return List.copyOf(result);
    }

    static int parseCustomHalo(String text) {
        if (text == null || !text.matches("[0-9]+")) {
            throw new IllegalArgumentException(
                    "Custom halo must be a nonnegative whole number of pixels.");
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "Custom halo is outside the supported integer range.", failure);
        }
    }

    record ModuleChoice(String ref, Optional<InstalledModule> installed) {
        @Override
        public String toString() {
            return installed
                    .map(module -> module.name() + " — " + ref)
                    .orElse(ref + " — not installed");
        }
    }

    record ModelSetting(String id, double targetMpp, CoreHaloProfile profile) {
        String physicalHalo(int haloPx) {
            return BigDecimal.valueOf(targetMpp)
                            .multiply(BigDecimal.valueOf(haloPx))
                            .stripTrailingZeros()
                            .toPlainString()
                    + " µm per side";
        }
    }

    private static final class ModelControls {
        private final ModelSetting model;
        private final VBox view;
        private final ComboBox<String> mode = new ComboBox<>();
        private final TextField customPixels = new TextField();
        private final Label physical = new Label();

        private ModelControls(ModelSetting model, Optional<Integer> override) {
            this.model = model;
            mode.getItems().setAll("Default (" + model.profile().haloPx() + " px)", "Custom");
            mode.getSelectionModel().select(override.isPresent() ? 1 : 0);
            customPixels.setText(Integer.toString(override.orElse(model.profile().haloPx())));
            customPixels.setPrefColumnCount(8);
            customPixels.setAccessibleText(model.id() + " custom halo in model pixels per side");
            HBox halo = new HBox(8, mode, customPixels, new Label("px per side"), physical);
            halo.setAlignment(Pos.CENTER_LEFT);
            view =
                    new VBox(
                            4,
                            new Label(model.id()),
                            new Label(
                                    "Core: "
                                            + model.profile().coreWidthPx()
                                            + " × "
                                            + model.profile().coreHeightPx()
                                            + " model px"),
                            halo);
            mode.valueProperty().addListener((ignored, previous, current) -> refresh());
            customPixels.textProperty().addListener((ignored, previous, current) -> refresh());
            refresh();
        }

        private Optional<Integer> override() {
            return mode.getSelectionModel().getSelectedIndex() == 0
                    ? Optional.empty()
                    : Optional.of(parseCustomHalo(customPixels.getText()));
        }

        private void refresh() {
            customPixels.setDisable(mode.getSelectionModel().getSelectedIndex() == 0);
            try {
                physical.setText(model.physicalHalo(override().orElse(model.profile().haloPx())));
            } catch (IllegalArgumentException invalid) {
                physical.setText("Enter a nonnegative integer");
            }
        }
    }
}
