package qupath.ext.histoforge.ui.training;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ModelOutputCodec.ClassChannel;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingOption;
import qupath.ext.histoforge.contract.TrainingClassSchema;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.ModelDisplay;

/** Target class channels and detached annotation mappings; never edits QuPath objects. */
final class TrainingClassControls extends VBox {
    private final Runnable changed;
    private final ComboBox<Initialization> mode = new ComboBox<>();
    private final GridPane taskRow = TrainingRunForm.grid();
    private final Label task = new Label();
    private final VBox classRows = new VBox(6);
    private final VBox mappingRows = new VBox(6);
    private final Label classError = errorLabel("trainingClassesError");
    private final Button add = new Button("Add class");
    private final List<ClassRow> classes = new ArrayList<>();
    private final Map<String, ComboBox<ClassRow>> mappings = new LinkedHashMap<>();
    private final ClassRow background = new ClassRow("background", "Background", false);
    private ModelOutput source;
    private boolean updating;

    TrainingClassControls(Runnable changed) {
        super(8);
        this.changed = changed;
        task.setId("trainingTargetTask");
        task.setWrapText(true);
        task.setMinWidth(0);
        taskRow.addRow(0, TrainingRunForm.groupLabel("Training task"), task);
        taskRow.setVisible(false);
        taskRow.setManaged(false);
        classRows.setId("trainingModelClasses");
        mappingRows.setId("trainingAnnotationMapping");
        mode.setId("trainingClassInitialization");
        var classHelp =
                TrainingRunForm.information(
                        "Use model classes: Continue training the existing classes. Renaming a class does not teach the model a new class."
                                + "\n\nAdd classes: Keep existing class recognition as a starting point and learn additional classes from your annotations."
                                + "\n\nDefine new classes: Relearn all class distinctions from your annotations, while reusing the original model's compatible image features."
                                + "\n\nChanging this choice resets the class list and annotation mapping.");
        classHelp.setId("trainingClassesInfo");
        mode.setMaxWidth(Double.MAX_VALUE);
        mode.setTooltip(classHelp.getTooltip());
        mode.setOnAction(
                event -> {
                    if (updating || source == null) return;
                    resetClasses();
                    changed.run();
                });
        add.setId("trainingAddClass");
        add.setTooltip(
                TrainingRunForm.tooltip("Append a class that the trained model will predict."));
        add.setOnAction(
                event -> {
                    classes.add(new ClassRow("emitted", "", false));
                    renderClasses();
                    changed.run();
                });
        var classesLabel = new Label("Classes");
        classesLabel.setId("trainingClassesLabel");
        classesLabel.setLabelFor(mode);
        classesLabel.setTooltip(classHelp.getTooltip());
        var modeRow = new HBox(6, mode, classHelp);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(mode, Priority.ALWAYS);
        var grid = TrainingRunForm.grid();
        grid.addRow(0, classesLabel, modeRow);
        grid.addRow(1, TrainingRunForm.groupLabel("Model classes"), classRows);
        grid.addRow(2, TrainingRunForm.groupLabel("Annotation mapping"), mappingRows);
        getChildren().addAll(taskRow, grid);
    }

    void select(ModelOutput source, List<TrainingOption> options) {
        updating = true;
        this.source = source;
        var allowed =
                options.stream()
                        .flatMap(option -> option.classInitializations().stream())
                        .distinct()
                        .sorted()
                        .toList();
        mode.getItems().setAll(allowed);
        mode.setValue(
                allowed.contains(Initialization.USE)
                        ? Initialization.USE
                        : allowed.contains(Initialization.DEFINE)
                                ? Initialization.DEFINE
                                : allowed.getFirst());
        updating = false;
        resetClasses();
    }

    Initialization initialization() {
        return mode.getValue();
    }

    String outputType() {
        return "instance_segmentation".equals(source.type())
                        && mode.getValue() != Initialization.USE
                ? "instance_segmentation_and_classification"
                : source.type();
    }

    ModelOutput output() {
        classError.setText("");
        String problem = validateNames();
        if (!problem.isEmpty()) {
            throw new IllegalArgumentException("Model classes: " + problem);
        }
        if (classes.stream().noneMatch(row -> "emitted".equals(row.role))) {
            classError.setText("Add at least one predicted class.");
            throw new IllegalArgumentException(classError.getText());
        }
        ModelOutput target;
        if ("instance_segmentation".equals(outputType())) {
            target =
                    new ModelOutput(
                            outputType(),
                            List.of(classes.getFirst().name.getText().strip()),
                            List.of());
        } else {
            var channels = new ArrayList<ClassChannel>();
            for (int index = 0; index < classes.size(); index++) {
                var row = classes.get(index);
                channels.add(
                        new ClassChannel(
                                index,
                                row.role,
                                "background".equals(row.role) ? null : row.name.getText().strip()));
            }
            target = new ModelOutput(outputType(), List.of(), channels);
        }
        try {
            TrainingClassSchema.requireTransition(source, target, mode.getValue());
        } catch (IllegalArgumentException error) {
            classError.setText(error.getMessage());
            throw error;
        }
        return target;
    }

    boolean hasValidationError() {
        return !classError.getText().isEmpty()
                || classes.stream().anyMatch(row -> !row.error.getText().isEmpty());
    }

    private String validateNames() {
        var counts = new LinkedHashMap<String, Integer>();
        classes.stream()
                .filter(row -> !"background".equals(row.role))
                .forEach(row -> counts.merge(row.name.getText().strip(), 1, Integer::sum));
        String first = "";
        for (var row : classes) {
            String problem = "";
            if (!"background".equals(row.role)) {
                String value = row.name.getText().strip();
                if (CanonicalCodec.isBlank(value)) {
                    problem =
                            row.removable()
                                    ? "Enter a class name or remove this row."
                                    : "Enter a class name.";
                } else {
                    try {
                        CanonicalCodec.requirePathClassName(value, "Class name");
                        if (counts.get(value) > 1) {
                            problem = "Use a different name; this class name is already used.";
                        }
                    } catch (IllegalArgumentException error) {
                        problem =
                                "Enter a valid QuPath class name (for subclasses, use Parent: Child).";
                    }
                }
            }
            row.error.setText(problem);
            if (first.isEmpty()) first = problem;
        }
        return first;
    }

    Map<String, String> annotationClassMap() {
        var result = new LinkedHashMap<String, String>();
        mappings.forEach(
                (name, box) -> {
                    var row = box.getValue();
                    if (row != background) result.put(name, row.name.getText().strip());
                });
        return Map.copyOf(result);
    }

    /** Discovery adds visible background defaults without changing the frozen target task. */
    void showAnnotationClasses(Set<String> names) {
        names.stream()
                .sorted()
                .forEach(
                        name -> {
                            if (!mappings.containsKey(name)) addMapping(name, background);
                        });
        renderMappings();
    }

    void resetAnnotationClasses() {
        mappings.clear();
        for (String name : source.targetClasses()) {
            var row =
                    classes.stream()
                            .filter(value -> value.name.getText().equals(name))
                            .findFirst()
                            .orElse(background);
            addMapping(name, row);
        }
        renderMappings();
    }

    private void resetClasses() {
        updating = true;
        task.setText(ModelDisplay.task(outputType()));
        taskRow.setVisible(!outputType().equals(source.type()));
        taskRow.setManaged(taskRow.isVisible());
        classes.clear();
        boolean retained = mode.getValue() != Initialization.DEFINE;
        if ("instance_segmentation".equals(source.type())) {
            if (mode.getValue() != Initialization.USE) {
                classes.add(new ClassRow("background", "Background", false));
            }
            classes.add(new ClassRow("emitted", source.classes().getFirst(), false));
        } else {
            source.classChannels()
                    .forEach(
                            channel ->
                                    classes.add(
                                            new ClassRow(
                                                    channel.role(),
                                                    channel.pathClass() == null
                                                            ? "Background"
                                                            : channel.pathClass(),
                                                    retained)));
        }
        if (mode.getValue() == Initialization.ADD
                && !"instance_segmentation".equals(source.type())) {
            classes.add(new ClassRow("emitted", "", false));
        }
        resetAnnotationClasses();
        renderClasses();
        updating = false;
    }

    private void renderClasses() {
        classRows.getChildren().clear();
        for (int index = 0; index < classes.size(); index++) {
            var row = classes.get(index);
            String role =
                    switch (row.role) {
                        case "background" -> "Background";
                        case "training_only" -> "Training only";
                        default -> "Predicted";
                    };
            var view = new HBox(6, new Label(Integer.toString(index)), row.name, new Label(role));
            view.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(row.name, Priority.ALWAYS);
            row.name.setEditable(!"background".equals(row.role));
            row.name.setId("trainingClassName" + index);
            row.error.setId("trainingClassError" + index);
            if (row.removable()) {
                var remove = new Button("Remove");
                remove.setTooltip(
                        TrainingRunForm.tooltip(
                                "Remove this new class. Its mapped annotations become background."));
                remove.setOnAction(
                        event -> {
                            classes.remove(row);
                            renderClasses();
                            changed.run();
                        });
                view.getChildren().add(remove);
            }
            classRows.getChildren().add(new VBox(2, view, row.error));
        }
        if (mode.getValue() != Initialization.USE) classRows.getChildren().add(add);
        classRows.getChildren().add(classError);
        mappings.values()
                .forEach(
                        box -> {
                            var selected = box.getValue();
                            boolean wasUpdating = updating;
                            updating = true;
                            box.getItems().setAll(mappingChoices());
                            box.setValue(classes.contains(selected) ? selected : background);
                            updating = wasUpdating;
                        });
    }

    private List<ClassRow> mappingChoices() {
        var choices = new ArrayList<ClassRow>();
        choices.add(background);
        classes.stream().filter(row -> !"background".equals(row.role)).forEach(choices::add);
        return choices;
    }

    private void addMapping(String name, ClassRow target) {
        var box = new ComboBox<ClassRow>();
        box.getItems().setAll(mappingChoices());
        box.setCellFactory(ignored -> mappingCell());
        box.setButtonCell(mappingCell());
        box.setValue(target);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setTooltip(
                TrainingRunForm.tooltip(
                        "Use annotations named “"
                                + name
                                + "” to teach the selected model class. Background keeps their pixels in training as "
                                + "background unless another mapped annotation covers them."));
        box.setOnAction(
                event -> {
                    if (!updating) changed.run();
                });
        mappings.put(name, box);
    }

    private void renderMappings() {
        mappingRows.getChildren().clear();
        mappings.forEach(
                (name, box) -> {
                    var label = new Label(name);
                    label.setWrapText(true);
                    label.setMinWidth(110);
                    var row = new HBox(8, label, box);
                    row.setAlignment(Pos.CENTER_LEFT);
                    HBox.setHgrow(box, Priority.ALWAYS);
                    mappingRows.getChildren().add(row);
                });
        var help = new Label("Unmapped and unclassified annotations are background.");
        help.setWrapText(true);
        mappingRows.getChildren().add(help);
    }

    private static ListCell<ClassRow> mappingCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ClassRow row, boolean empty) {
                super.updateItem(row, empty);
                textProperty().unbind();
                if (empty || row == null) setText("");
                else textProperty().bind(row.name.textProperty());
            }
        };
    }

    private final class ClassRow {
        private final String role;
        private final TextField name;
        private final boolean retained;
        private final Label error = errorLabel(null);

        private boolean removable() {
            return mode.getValue() != Initialization.USE && !retained && !"background".equals(role);
        }

        private ClassRow(String role, String label, boolean retained) {
            this.role = role;
            this.retained = retained;
            name = new TextField(label);
            name.setPromptText("Class name");
            name.setTooltip(
                    TrainingRunForm.tooltip(
                            "Class name saved in the trained model. Existing annotation mappings follow this row if you rename it."));
            name.textProperty()
                    .addListener(
                            (ignored, old, value) -> {
                                if (!updating) changed.run();
                            });
        }
    }

    private static Label errorLabel(String id) {
        var label = new Label("");
        label.setId(id);
        label.setWrapText(true);
        label.getStyleClass().add(HistoforgeStyles.ERROR);
        label.visibleProperty().bind(label.textProperty().isNotEmpty());
        label.managedProperty().bind(label.visibleProperty());
        return label;
    }
}
