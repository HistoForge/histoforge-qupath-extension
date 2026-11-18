package qupath.ext.histoforge.ui.training;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAugmentationProfileChoice;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAugmentationSelection;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingOption;
import qupath.ext.histoforge.contract.TrainingClassSchema;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.training.TrainingRunDraft;
import qupath.ext.histoforge.training.TrainingRunDraft.ResultForm;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.HistoforgeTooltip;
import qupath.ext.histoforge.ui.InformationButton;
import qupath.ext.histoforge.ui.ModelDisplay;

/** Capability-backed form values. This component neither reads projects nor starts backend work. */
final class TrainingRunForm {
    private final GridPane sourceNode = grid();
    private final VBox resultNode = new VBox(6);
    private final VBox annotationNode = new VBox(8);
    private final VBox settingsNode = new VBox(10);
    private final ComboBox<ResultForm> resultForm = new ComboBox<>();
    private final TextField name = new TextField();
    private final ComboBox<String> resultInferenceGpu = new ComboBox<>();
    private final TextField minInstancePixels = new TextField();
    private final TextField resolution = new TextField();
    private final TrainingClassControls classes;
    private final VBox trainingParameters = new VBox(8);
    private final List<CheckBox> augmentationBoxes = new ArrayList<>();
    private final Label validation = new Label();
    private final TrainingFieldControls field;
    private final TrainingSettingsControls training;
    private final Runnable changed;
    private final Runnable targetChanged;
    private List<TrainingOption> options = List.of();
    private InstalledModule source;
    private String modelId;
    private ModelTrainingCapability capability;
    private TrainingAugmentationProfileChoice augmentationProfile;
    private boolean updating;

    TrainingRunForm(Runnable changed, Runnable targetChanged) {
        this.changed = changed;
        this.targetChanged = targetChanged;
        sourceNode.setVisible(false);
        sourceNode.setManaged(false);
        classes = new TrainingClassControls(this::targetEdited);
        field = new TrainingFieldControls(this::edited);
        training = new TrainingSettingsControls(this::edited);
        name.setId("trainingResultName");
        resultForm.setId("trainingResultForm");
        resultInferenceGpu.setId("resultInferenceGpu");
        minInstancePixels.setId("minimumDetectedObjectArea");
        resolution.setId("trainingResolution");
        resolution.setTooltip(
                tooltip(
                        "Micrometres represented by one model input pixel, for training and later analysis. Smaller values use more pixels per tissue area but cannot add missing slide detail. Tile pixel counts stay the same, so their physical size changes."));
        resolution.textProperty().addListener((ignored, old, value) -> targetEdited());
        name.setTooltip(tooltip("Name of the new module created by this training run."));
        resultForm.setTooltip(
                tooltip(
                        "Create a module containing only this model, or copy its complete workflow when available."));
        name.textProperty().addListener((ignored, old, value) -> edited());
        minInstancePixels.textProperty().addListener((ignored, old, value) -> edited());
        resultForm.setOnAction(event -> edited());
        resultInferenceGpu.setOnAction(event -> edited());
        validation.setId("trainingFormError");
        validation.setWrapText(true);
        HistoforgeStyles.setSemantic(validation, HistoforgeStyles.ERROR);
    }

    Node resultNode() {
        return resultNode;
    }

    Node sourceNode() {
        return sourceNode;
    }

    Node settingsNode() {
        return settingsNode;
    }

    Node annotationNode() {
        return annotationNode;
    }

    void setDisabled(boolean disabled) {
        resultNode.setDisable(disabled);
        annotationNode.setDisable(disabled);
        settingsNode.setDisable(disabled);
    }

    /** Reset defaults only for a genuinely changed selected model/catalog descriptor. */
    void select(InstalledModule source, String modelId) {
        updating = true;
        this.source = source;
        this.modelId = modelId;
        sourceNode.getChildren().clear();
        sourceNode.setVisible(source != null && modelId != null);
        sourceNode.setManaged(sourceNode.isVisible());
        resultNode.getChildren().clear();
        annotationNode.getChildren().clear();
        settingsNode.getChildren().clear();
        if (source == null || modelId == null) {
            capability = null;
            updating = false;
            changed.run();
            return;
        }
        var model = source.recipe().models().get(modelId);
        var architecture = new Label(ModelDisplay.architecture(model.arch()));
        architecture.setId("trainingSourceArchitecture");
        architecture.setWrapText(true);
        architecture.setMinWidth(0);
        var architectureRow = withInfo(architecture, ModelDisplay.architectureHelp(model.arch()));
        architectureRow.getChildren().getLast().setId("trainingSourceArchitectureInfo");
        HBox.setHgrow(architecture, Priority.ALWAYS);
        var task = new Label(ModelDisplay.task(model.output().type()));
        task.setId("trainingSourceTask");
        task.setWrapText(true);
        task.setMinWidth(0);
        sourceNode.addRow(0, groupLabel("Architecture"), architectureRow);
        sourceNode.addRow(1, groupLabel("Task"), task);
        options = TrainingRunDraft.options(source, modelId);
        classes.select(source.recipe().models().get(modelId).output(), options);
        resolution.setText(
                Double.toString(source.recipe().models().get(modelId).input().micronsPerPixel()));
        capability = selectedCapability();
        var forms = TrainingRunDraft.resultForms(source, modelId);
        resultForm.getItems().setAll(forms);
        resultForm.getSelectionModel().selectFirst();
        name.setText(modelId + " (fine-tuned)");
        var resultGrid = grid();
        if (forms.size() > 1) {
            resultForm.setMaxWidth(Double.MAX_VALUE);
            resultGrid.addRow(0, new Label("Type"), resultForm);
        } else {
            var formLabel =
                    new Label(
                            forms.isEmpty()
                                    ? "The copied workflow has a model that cannot run on this backend."
                                    : forms.getFirst().toString());
            formLabel.setWrapText(true);
            resultGrid.addRow(0, new Label("Type"), formLabel);
        }
        resultGrid.addRow(1, new Label("Name"), name);
        resultNode.getChildren().add(resultGrid);
        if ("crop".equals(capability.inputKind())) {
            var step =
                    source.recipe().steps().stream()
                            .filter(value -> modelId.equals(value.model()))
                            .findFirst()
                            .orElseThrow();
            var framing =
                    new Label(
                            "Parent: "
                                    + step.parentModel()
                                    + "; classes: "
                                    + String.join(", ", step.parentClass())
                                    + "; crop padding: "
                                    + capability.cropPaddingModelPx()
                                    + " model px. These settings stay with the copied workflow.");
            framing.setWrapText(true);
            framing.getStyleClass().add(HistoforgeStyles.MUTED);
            resultNode.getChildren().add(framing);
        }

        String sourceInferenceGpu = source.recipe().models().get(modelId).gpu();
        var resultInferenceGpus = new LinkedHashSet<String>();
        resultInferenceGpus.add(sourceInferenceGpu);
        resultInferenceGpus.addAll(source.gateway().inferenceGpus());
        resultInferenceGpu.getItems().setAll(resultInferenceGpus);
        resultInferenceGpu.setValue(sourceInferenceGpu);
        var inferenceGrid = grid();
        inferenceGrid.addRow(
                0,
                new Label("Inference GPU"),
                withInfo(
                        resultInferenceGpu,
                        "GPU saved with the trained model for later analysis. Settings → Cloud can override it "
                                + "for new Project actions. Other models copied with the workflow keep their own defaults."));
        minInstancePixels.clear();
        if (!"dense_classification".equals(capability.outputType())) {
            minInstancePixels.setText(
                    Integer.toString(TrainingRunDraft.sourceMinInstancePixels(source, modelId)));
            minInstancePixels.setPrefColumnCount(6);
            minInstancePixels.setMaxWidth(Region.USE_PREF_SIZE);
            inferenceGrid.addRow(
                    1,
                    new Label("Minimum object area"),
                    withInfo(
                            minInstancePixels,
                            "Discard predicted objects smaller than this many pixels at the model's resolution. "
                                    + "Use it to exclude small detections; 0 disables this size filter. It affects validation "
                                    + "and later analysis, but never removes training annotations."));
        }
        field.select(capability, resolution());
        selectTrainingParameters();
        var resolutionGrid = grid();
        resolutionGrid.addRow(0, new Label("Resolution (µm/px)"), resolution);
        annotationNode.getChildren().setAll(heading("Training classes"), classes);
        settingsNode
                .getChildren()
                .setAll(
                        heading("Parameters"),
                        resolutionGrid,
                        field,
                        inferenceGrid,
                        trainingParameters,
                        validation);
        updating = false;
        edited();
    }

    TrainingRunDraft draft() {
        return new TrainingRunDraft(
                source,
                modelId,
                targetTask(),
                classInitialization(),
                resultForm.getValue(),
                name.getText(),
                resultInferenceGpu.getValue(),
                field.value(),
                minimumDetectedObjectArea(),
                new TrainingAugmentationSelection(
                        augmentationProfile.profileId(),
                        augmentationBoxes.stream()
                                .filter(CheckBox::isSelected)
                                .map(CheckBox::getId)
                                .toList()),
                training.value());
    }

    TrainingTask targetTask() {
        if (capability == null)
            throw new IllegalArgumentException("Select a model available for training.");
        return TrainingCapabilityCodec.taskFromCapability(
                capability,
                classes.output(),
                resolution(),
                classes.annotationClassMap(),
                source.gateway().trainingApiLimits().report().maxTextBytes());
    }

    Initialization classInitialization() {
        return classes.initialization();
    }

    boolean isTargetValid() {
        return targetValidationMessage().isEmpty();
    }

    String targetValidationMessage() {
        if (capability == null) return "Select a model available for training.";
        try {
            TrainingRunDraft.capability(source, modelId, targetTask(), classInitialization());
            return "";
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
    }

    void showAnnotationClasses(Set<String> names) {
        classes.showAnnotationClasses(names);
    }

    /** Forget the listed classes and every choice made for them, as after a project switch. */
    void forgetAnnotationClasses() {
        classes.forgetAnnotationClasses();
    }

    /** List no classes after a read that found no training ROI; the choices made are kept. */
    void hideAnnotationClasses() {
        classes.hideAnnotationClasses();
    }

    /**
     * The target model's classes that start without learned weights. Each can be learned only from
     * objects in the Training rows.
     *
     * @throws IllegalArgumentException when the class setup cannot be built yet
     */
    List<String> classesWithoutLearnedWeights() {
        return TrainingClassSchema.classesWithoutLearnedWeights(
                source.recipe().models().get(modelId).output(),
                classes.output(),
                classes.initialization());
    }

    private double resolution() {
        try {
            double value = Double.parseDouble(resolution.getText().strip());
            if (Double.isFinite(value) && value > 0) return value;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("Enter a positive resolution in µm/px.");
    }

    private ModelTrainingCapability selectedCapability() {
        return options.stream()
                .filter(
                        option ->
                                option.capability().outputType().equals(classes.outputType())
                                        && option.classInitializations()
                                                .contains(classes.initialization()))
                .map(TrainingOption::capability)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "This training target is not available on the backend."));
    }

    private void selectTrainingParameters() {
        training.select(capability);
        augmentationProfile =
                capability.augmentationProfiles().stream()
                        .filter(
                                value ->
                                        value.profileId()
                                                .equals(capability.defaultAugmentationProfileId()))
                        .findFirst()
                        .orElseThrow();
        trainingParameters.getChildren().setAll(training.node(), augmentationControls());
    }

    private void targetEdited() {
        if (updating || source == null) return;
        updating = true;
        try {
            var selected = selectedCapability();
            double targetResolution = resolution();
            if (!selected.equals(capability)) {
                capability = selected;
                field.select(capability, targetResolution);
                selectTrainingParameters();
            } else {
                field.updateResolution(targetResolution);
            }
        } catch (IllegalArgumentException ignored) {
            // Split and draft validation display incomplete edits.
        } finally {
            updating = false;
        }
        edited();
        targetChanged.run();
    }

    /** Whether the class setup lets training start. */
    boolean isClassSetupTrainable() {
        return capability != null && classes.trainingProblem().isEmpty();
    }

    boolean isValid() {
        if (!isClassSetupTrainable()) return false;
        try {
            draft();
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private Node augmentationControls() {
        augmentationBoxes.clear();
        var options = new VBox(6);
        for (var operator : augmentationProfile.operators()) {
            var box = new CheckBox(operator.displayName());
            box.setId(operator.operatorId());
            box.setSelected(operator.defaultEnabled());
            box.selectedProperty().addListener((ignored, old, value) -> edited());
            augmentationBoxes.add(box);
            options.getChildren().add(withInfo(box, operator.description()));
        }
        var restore = new Button("Restore defaults");
        restore.setId("augmentationDefaults");
        restore.setTooltip(tooltip("Restore this model's default augmentation choices."));
        restore.setOnAction(
                event -> {
                    updating = true;
                    for (int index = 0; index < augmentationBoxes.size(); index++) {
                        augmentationBoxes
                                .get(index)
                                .setSelected(
                                        augmentationProfile
                                                .operators()
                                                .get(index)
                                                .defaultEnabled());
                    }
                    updating = false;
                    edited();
                });
        options.getChildren().add(restore);
        var grid = grid();
        var label = groupLabel("Augmentations");
        label.setTooltip(tooltip(augmentationProfile.description()));
        grid.addRow(0, label, options);
        return grid;
    }

    private Integer minimumDetectedObjectArea() {
        if ("dense_classification".equals(capability.outputType())) return null;
        try {
            return Integer.parseInt(minInstancePixels.getText().trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Enter a whole number for minimum object area.", error);
        }
    }

    private void edited() {
        if (updating || capability == null) return;
        try {
            draft();
            validation.setText("");
        } catch (IllegalArgumentException error) {
            validation.setText(classes.hasTargetProblem() ? "" : error.getMessage());
        }
        validation.setVisible(!validation.getText().isEmpty());
        validation.setManaged(validation.isVisible());
        changed.run();
    }

    static GridPane grid() {
        GridPane grid = new GridPane(8, 8);
        ColumnConstraints labels = new ColumnConstraints(160);
        ColumnConstraints controls = new ColumnConstraints();
        controls.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, controls);
        return grid;
    }

    static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add(HistoforgeStyles.HEADING);
        return label;
    }

    static Label groupLabel(String text) {
        Label label = new Label(text);
        GridPane.setValignment(label, VPos.TOP);
        return label;
    }

    static InformationButton information(String text) {
        return new InformationButton(text);
    }

    static HBox withInfo(Node control, String text) {
        if (control instanceof Control input) input.setTooltip(tooltip(text));
        var row = new HBox(6, control, information(text));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static HistoforgeTooltip tooltip(String text) {
        HistoforgeTooltip tooltip = new HistoforgeTooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        return tooltip;
    }
}
