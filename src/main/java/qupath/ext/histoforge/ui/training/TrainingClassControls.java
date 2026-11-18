package qupath.ext.histoforge.ui.training;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.StringConverter;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingOption;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.training.TrainingClassPlan;
import qupath.ext.histoforge.training.TrainingClassPlan.FixedChoice;
import qupath.ext.histoforge.training.TrainingClassPlan.ModelClass;
import qupath.ext.histoforge.training.TrainingClassPlan.NewClass;
import qupath.ext.histoforge.training.TrainingClassPlan.TrainAs;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.InformationButton;
import qupath.ext.histoforge.ui.ModelDisplay;

/**
 * Shows a {@link TrainingClassPlan}: the choice between the model's classes and a new class list,
 * what each QuPath class trains, and the classes the trained model will have. It never edits QuPath
 * objects.
 */
final class TrainingClassControls extends VBox {
    private static final String ASSIGNMENT_HELP =
            "Choose which model class to train using each QuPath class. Inside a training ROI, "
                    + "pixels outside objects assigned to a model class train as background.";
    private static final String EDIT_CLASS_HELP =
            "Rename model labels here. Annotations assigned to a renamed class keep that assignment.";

    private final Runnable changed;
    private final GridPane taskRow = TrainingRunForm.grid();
    private final Label task = new Label();
    private final ToggleGroup choices = new ToggleGroup();
    private final RadioButton keepChoice = new RadioButton();
    private final RadioButton replaceChoice = new RadioButton();
    private final InformationButton keepHelp = TrainingRunForm.information("");
    private final InformationButton replaceHelp = TrainingRunForm.information("");
    private final HBox keepOption = choiceRow(keepChoice, keepHelp);
    private final HBox replaceOption = choiceRow(replaceChoice, replaceHelp);
    private final GridPane assignmentRows = new GridPane(8, 6);
    private final Label qupathClassHeading = new Label("QuPath annotation class");
    private final Label trainAsLabel = new Label("Train as");
    private final InformationButton trainAsHelp = TrainingRunForm.information(ASSIGNMENT_HELP);
    private final HBox trainAsHeading = new HBox(4, trainAsLabel, trainAsHelp);
    private final VBox predictions = new VBox(2);
    private final Text problemText = new Text();
    private final Hyperlink removeUnused = new Hyperlink();
    private final Text problemEnd = new Text(".");
    private final TextFlow problem = new TextFlow(problemText, removeUnused, problemEnd);
    private final Label warning = wrapped("", false);
    private final VBox classRows = new VBox(6);
    private final TitledPane editClasses = new TitledPane();
    private final Button add = new Button("Add model class…");

    /** Where a class is named by hand; shown only while no Train as row can create it. */
    private final VBox classByHand = new VBox(4, add);

    private final Map<String, ComboBox<TrainAs>> pickers = new LinkedHashMap<>();
    private final List<ClassRow> classRowViews = new ArrayList<>();

    /** Changes with every class rename so Train as pickers redraw the names they show. */
    private final IntegerProperty classNameEdits = new SimpleIntegerProperty();

    private TrainingClassPlan plan;
    private String sourceType;
    private boolean updating;

    TrainingClassControls(Runnable changed) {
        super(8);
        this.changed = changed;
        task.setId("trainingTargetTask");
        task.setWrapText(true);
        task.setMinWidth(0);
        taskRow.addRow(0, TrainingRunForm.groupLabel("Training task"), task);

        keepChoice.setId("trainingKeepClasses");
        replaceChoice.setId("trainingReplaceClasses");
        keepChoice.setToggleGroup(choices);
        replaceChoice.setToggleGroup(choices);
        keepHelp.setId("trainingKeepClassesHelp");
        replaceHelp.setId("trainingReplaceClassesHelp");
        choices.selectedToggleProperty()
                .addListener(
                        (ignored, previous, current) -> {
                            if (updating || plan == null || current == null) return;
                            plan.setReplacing(replaceChoice.isSelected());
                            render(true);
                            changed.run();
                        });
        var choiceGrid = TrainingRunForm.grid();
        choiceGrid.addRow(
                0,
                TrainingRunForm.groupLabel("Model classes"),
                new VBox(4, keepOption, replaceOption));

        assignmentRows.setId("trainingAnnotationMapping");
        var left = new ColumnConstraints();
        left.setPercentWidth(44);
        var right = new ColumnConstraints();
        right.setPercentWidth(56);
        assignmentRows.getColumnConstraints().addAll(left, right);
        qupathClassHeading.setTooltip(
                TrainingRunForm.tooltip(
                        "Classes of area annotations and detections in images with training ROIs. "
                                + "QuPath cell objects are excluded."));
        trainAsHelp.setId("trainingTrainAsInfo");
        trainAsLabel.setTooltip(trainAsHelp.getTooltip());
        trainAsHeading.setAlignment(Pos.CENTER_LEFT);

        predictions.setId("trainingModelPredictions");
        var predictionGrid = TrainingRunForm.grid();
        predictionGrid.addRow(0, TrainingRunForm.groupLabel("Model predicts"), predictions);

        problem.setId("trainingClassesError");
        problemText.setId("trainingClassesErrorText");
        problemText.getStyleClass().add(HistoforgeStyles.ERROR_TEXT);
        problemEnd.getStyleClass().add(HistoforgeStyles.ERROR_TEXT);
        removeUnused.setId("trainingRemoveUnusedClass");
        removeUnused.setPadding(Insets.EMPTY);
        problem.managedProperty().bind(problem.visibleProperty());
        warning.setId("trainingClassesWarning");
        warning.getStyleClass().add(HistoforgeStyles.WARNING);
        warning.visibleProperty().bind(warning.textProperty().isNotEmpty());
        warning.managedProperty().bind(warning.visibleProperty());

        classRows.setId("trainingModelClasses");
        editClasses.setId("trainingEditModelClasses");
        editClasses.setText("Edit model classes");
        editClasses.setTooltip(TrainingRunForm.tooltip(EDIT_CLASS_HELP));
        editClasses.setContent(classRows);
        editClasses.setExpanded(false);
        add.setId("trainingAddClass");
        add.setOnAction(event -> openClassEditor());

        getChildren()
                .addAll(
                        taskRow,
                        choiceGrid,
                        assignmentRows,
                        classByHand,
                        predictionGrid,
                        problem,
                        warning,
                        editClasses);
    }

    void select(ModelOutput source, List<TrainingOption> options) {
        updating = true;
        var offered = EnumSet.noneOf(Initialization.class);
        options.forEach(option -> offered.addAll(option.classInitializations()));
        var previous = plan;
        plan = new TrainingClassPlan(source, offered);
        // What a project read found belongs to the project, so it outlives a model change.
        if (previous != null && previous.projectRead()) {
            plan.showQuPathClasses(Set.copyOf(previous.quPathClasses()));
        }
        sourceType = source.type();
        pickers.clear();
        show(plan.keepOffered(), keepOption, keepChoice);
        show(plan.replaceOffered(), replaceOption, replaceChoice);
        // Arrow keys move between the radios of a group and skip only disabled ones, so a choice
        // the backend does not offer must be disabled as well as hidden.
        keepChoice.setDisable(!plan.keepOffered());
        replaceChoice.setDisable(!plan.replaceOffered());
        choices.selectToggle(plan.replacing() ? replaceChoice : keepChoice);
        editClasses.setExpanded(false);
        closeClassEditor();
        render(true);
        updating = false;
    }

    Initialization initialization() {
        return plan.initialization();
    }

    String outputType() {
        return plan.outputType();
    }

    ModelOutput output() {
        return plan.output();
    }

    Map<String, String> annotationClassMap() {
        return plan.annotationClassMap();
    }

    /** Whether {@link #output()} would fail with a problem this section already shows. */
    boolean hasTargetProblem() {
        return !plan.targetProblem().isEmpty();
    }

    /** Why training cannot start with this class setup, or an empty string. */
    String trainingProblem() {
        return plan.problem();
    }

    /** Discovery adds visible defaults without changing the classes already being trained. */
    void showAnnotationClasses(Set<String> names) {
        plan.showQuPathClasses(names);
        pickers.keySet().retainAll(names);
        render(true);
    }

    /** Forget the listed classes and every choice made for them, as after a project switch. */
    void forgetAnnotationClasses() {
        if (plan == null) return;
        plan.forgetQuPathClasses();
        pickers.clear();
        render(true);
    }

    /** List no classes after a read that found no training ROI; the choices made are kept. */
    void hideAnnotationClasses() {
        plan.hideQuPathClasses();
        pickers.clear();
        render(true);
    }

    /**
     * Redraw from the plan. Rows are rebuilt only when the set of rows changes, so a Train as
     * picker keeps keyboard focus while the researcher works through the table.
     */
    private void render(boolean rebuildRows) {
        render(rebuildRows, null);
    }

    /**
     * @param reporting a picker that is still delivering its own selection; JavaFX must finish that
     *     before the picker's list can change, so the caller refreshes it afterwards
     */
    private void render(boolean rebuildRows, ComboBox<TrainAs> reporting) {
        boolean wasUpdating = updating;
        updating = true;
        renderChoices();
        if (rebuildRows) rebuildAssignmentRows();
        pickers.forEach(
                (name, picker) -> {
                    if (picker != reporting) refresh(name, picker);
                });
        renderClassRows();
        renderSummary();
        updating = wasUpdating;
    }

    private void refresh(String qupathClass, ComboBox<TrainAs> picker) {
        boolean wasUpdating = updating;
        updating = true;
        // Replacing a ComboBox's items drops its selected position even when the value survives,
        // and the arrow keys would then restart from the top. So the list is touched only when it
        // differs, and the value is set through the selection model, which keeps both in step.
        var choices = plan.choices(qupathClass);
        if (!picker.getItems().equals(choices)) picker.getItems().setAll(choices);
        picker.getSelectionModel().select(plan.trainAs(qupathClass));
        updating = wasUpdating;
    }

    private void renderChoices() {
        keepChoice.setText(ModelDisplay.keepClassesChoice(sourceType));
        replaceChoice.setText(ModelDisplay.replaceClassesChoice(sourceType));
        if (plan.sourceHasClasses()) {
            keepHelp.setTextContent(
                    "Continue training the model's existing classes."
                            + (plan.keptClassesCanGrow()
                                    ? " Add more with New model class in Train as."
                                    : ""));
            replaceHelp.setTextContent(
                    "Train a fresh set of classes from your annotations. "
                            + "The rest of the model starts from its existing weights.");
        } else {
            String label = plan.outlineLabel();
            keepHelp.setTextContent(
                    "Train the model to outline objects. Every object gets "
                            + (label.isEmpty() ? "the same label." : "the label " + label + "."));
            replaceHelp.setTextContent(
                    "Start from the existing segmentation weights and train a classifier "
                            + "from your annotations.");
        }
    }

    private void rebuildAssignmentRows() {
        assignmentRows.getChildren().clear();
        if (plan.quPathClasses().isEmpty()) {
            assignmentRows.add(
                    wrapped(
                            plan.foundNoQuPathClasses()
                                    ? "The project has no annotation classes to train on."
                                    : "Refresh the project to see its annotation classes.",
                            false),
                    0,
                    0,
                    2,
                    1);
            return;
        }
        assignmentRows.addRow(0, qupathClassHeading, trainAsHeading);
        int index = 1;
        for (String name : plan.quPathClasses()) {
            var label = wrapped(name, false);
            var picker = pickers.computeIfAbsent(name, this::picker);
            label.setLabelFor(picker);
            assignmentRows.addRow(index++, label, picker);
        }
    }

    private ComboBox<TrainAs> picker(String qupathClass) {
        var picker = new ComboBox<TrainAs>();
        picker.setCellFactory(ignored -> new TrainAsCell());
        picker.setButtonCell(selectedChoiceCell(picker));
        picker.setConverter(
                new StringConverter<>() {
                    @Override
                    public String toString(TrainAs trainAs) {
                        return text(trainAs);
                    }

                    @Override
                    public TrainAs fromString(String ignored) {
                        throw new UnsupportedOperationException("Train as is not editable");
                    }
                });
        picker.setMinWidth(0);
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setTooltip(TrainingRunForm.tooltip(ASSIGNMENT_HELP));
        picker.valueProperty()
                .addListener(
                        (ignored, previous, current) -> {
                            // Until the refresh below has run, this picker's list can still hold
                            // a value the plan no longer offers, such as a class just removed.
                            if (updating
                                    || current == null
                                    || !plan.choices(qupathClass).contains(current)) {
                                return;
                            }
                            plan.assign(qupathClass, current);
                            render(false, picker);
                            Platform.runLater(
                                    () -> {
                                        if (pickers.get(qupathClass) == picker) {
                                            refresh(qupathClass, picker);
                                        }
                                    });
                            changed.run();
                        });
        return picker;
    }

    /** Show the selected value when adding or removing choices moves its position in the list. */
    private ListCell<TrainAs> selectedChoiceCell(ComboBox<TrainAs> picker) {
        return new ListCell<>() {
            private final StringBinding label =
                    Bindings.createStringBinding(
                            () -> text(picker.getValue()), picker.valueProperty(), classNameEdits);

            {
                label.addListener((ignored, previous, current) -> setText(current));
            }

            @Override
            protected void updateItem(TrainAs item, boolean empty) {
                super.updateItem(item, empty);
                setText(label.get());
            }
        };
    }

    /** The text a researcher reads for a Train as value. */
    private String text(TrainAs trainAs) {
        return switch (trainAs) {
            case null -> "";
            case FixedChoice fixed ->
                    fixed == FixedChoice.BACKGROUND
                            ? ModelDisplay.backgroundChoice(sourceType)
                            : "Not chosen";
            case NewClass created -> "New model class “" + created.name() + "”";
            case ModelClass modelClass ->
                    modelClass.isTrainingOnly()
                            ? modelClass.name() + " (left out of results)"
                            : modelClass.name();
        };
    }

    private void renderClassRows() {
        classRowViews.clear();
        classRows.getChildren().clear();
        var classes = plan.classes();
        for (int index = 0; index < classes.size(); index++) {
            var modelClass = classes.get(index);
            if (!modelClass.isNamed()) continue;
            var name = new TextField(modelClass.name());
            name.setId("trainingClassName" + index);
            name.setPromptText("Class name");
            name.setTooltip(TrainingRunForm.tooltip(EDIT_CLASS_HELP));
            name.textProperty()
                    .addListener(
                            (ignored, old, value) -> {
                                if (updating) return;
                                plan.rename(modelClass, value);
                                classNameEdits.set(classNameEdits.get() + 1);
                                // A name decides which rows may still create a class of their own.
                                pickers.forEach(this::refresh);
                                renderChoices();
                                renderSummary();
                                changed.run();
                            });
            var origin = new Label();
            origin.setId("trainingClassOrigin" + index);
            origin.getStyleClass().add(HistoforgeStyles.MUTED);
            origin.setMinWidth(USE_PREF_SIZE);
            var error = wrapped("", false);
            error.setId("trainingClassError" + index);
            error.getStyleClass().add(HistoforgeStyles.ERROR);
            error.visibleProperty().bind(error.textProperty().isNotEmpty());
            error.managedProperty().bind(error.visibleProperty());
            var view = new HBox(6, name, origin);
            view.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(name, Priority.ALWAYS);
            if (modelClass.isNew()) {
                var remove = new Button("Remove");
                remove.setId("trainingRemoveClass" + index);
                remove.setTooltip(
                        TrainingRunForm.tooltip(
                                "Remove this class. QuPath classes set to it will need a new choice."));
                remove.setOnAction(event -> remove(modelClass));
                view.getChildren().add(remove);
            }
            classRows.getChildren().add(new VBox(2, view, error));
            classRowViews.add(new ClassRow(modelClass, origin, error));
        }
        if (classRowViews.isEmpty()) {
            classRows.getChildren().add(wrapped("No model classes yet.", true));
        }
    }

    /** Everything derived from the current names and choices; safe to run on every keystroke. */
    private void renderSummary() {
        task.setText(ModelDisplay.task(plan.outputType()));
        show(!plan.outputType().equals(sourceType), taskRow);
        // Reading the project needs a class list that can be built. When the list lacks a class
        // that no Train as row can create, the class is named here.
        boolean byHand = plan.needsClassNamedByHand();
        if (!byHand) closeClassEditor();
        show(byHand, classByHand);
        boolean nameError = false;
        for (var row : classRowViews) {
            String nameProblem = plan.nameProblem(row.modelClass());
            row.error().setText(nameProblem);
            row.origin().setText(origin(row.modelClass()));
            nameError |= !nameProblem.isEmpty();
        }
        // A name error is shown beside its field, so that field must be in view.
        if (nameError) editClasses.setExpanded(true);
        predictions.getChildren().setAll(predictionLines());

        String blocking = plan.problem();
        var unused =
                plan.unusedNewClass()
                        .filter(
                                modelClass ->
                                        blocking.startsWith(
                                                TrainingClassPlan.unusedClassProblem(modelClass)))
                        .orElse(null);
        problemText.setText(
                unused == null ? blocking : TrainingClassPlan.unusedClassProblem(unused));
        removeUnused.setText(unused == null ? "" : "remove " + unused.name());
        removeUnused.setOnAction(
                unused == null
                        ? null
                        : event -> {
                            removeUnused.setVisited(false);
                            remove(unused);
                        });
        show(unused != null, removeUnused, problemEnd);
        problem.setVisible(!blocking.isEmpty() && !nameError);
        warning.setText(blocking.isEmpty() ? untrainedClassesWarning() : "");
    }

    private List<Node> predictionLines() {
        var lines = new ArrayList<Node>();
        if (plan.outlinesOnly()) {
            lines.add(wrapped(plan.outlineLabel(), false));
            return lines;
        }
        var learned = new ArrayList<String>();
        var created = new ArrayList<String>();
        var omitted = new ArrayList<String>();
        for (var modelClass : plan.classes()) {
            if (modelClass.isTrainingOnly()) {
                omitted.add(modelClass.name());
            } else if (modelClass.isPredicted() && modelClass.isNew()) {
                created.add(modelClass.name());
            } else if (modelClass.isPredicted()) {
                learned.add(
                        modelClass.name().equals(modelClass.learnedAs())
                                ? modelClass.name()
                                : modelClass.name()
                                        + " (learned as "
                                        + modelClass.learnedAs()
                                        + ")");
            }
        }
        if (!learned.isEmpty()) lines.add(wrapped("Learned: " + String.join(", ", learned), false));
        if (!created.isEmpty()) lines.add(wrapped("New: " + String.join(", ", created), false));
        if (learned.isEmpty() && created.isEmpty()) lines.add(wrapped("No classes yet.", true));
        if (!omitted.isEmpty()) {
            lines.add(wrapped("Left out of results: " + String.join(", ", omitted), false));
        }
        return lines;
    }

    /** Explains what a kept, predicted class stands to lose when no QuPath class trains it. */
    private String untrainedClassesWarning() {
        var names = plan.untrainedLearnedClasses().stream().map(ModelClass::name).toList();
        if (names.isEmpty()) return "";
        String them = names.size() > 1 ? "them" : "it";
        return "No QuPath class is set to "
                + TrainingClassPlan.sentenceList(names, "or")
                + ", so training shows the model no examples of "
                + them
                + " and it may stop predicting "
                + them
                + ". Such objects inside training ROIs are taught as background unless their "
                + "QuPath class is set to another model class."
                + (plan.replaceOffered()
                        ? " To leave " + them + " out, choose " + replaceChoice.getText() + "."
                        : "");
    }

    /** Where a class's knowledge starts, shown beside its name in the class editor. */
    private String origin(ModelClass modelClass) {
        if (plan.outlinesOnly()) return "label for every object";
        String start =
                modelClass.isNew()
                        ? "new"
                        : modelClass.name().equals(modelClass.learnedAs())
                                ? "learned"
                                : "learned as " + modelClass.learnedAs();
        return modelClass.isTrainingOnly() ? start + " · left out of results" : start;
    }

    private void remove(ModelClass modelClass) {
        plan.remove(modelClass);
        render(false);
        changed.run();
    }

    /** Name a class that the list needs and no Train as row can create. */
    private void openClassEditor() {
        if (classByHand.getChildren().size() > 1) return;
        var name = new TextField();
        name.setId("trainingNewClassName");
        var label = new Label("New model class name");
        label.setLabelFor(name);
        var confirm = new Button("Add class");
        confirm.setId("trainingConfirmNewClass");
        var cancel = new Button("Cancel");
        var error = wrapped("", false);
        error.setId("trainingNewClassError");
        error.getStyleClass().add(HistoforgeStyles.ERROR);
        error.visibleProperty().bind(error.textProperty().isNotEmpty());
        error.managedProperty().bind(error.visibleProperty());
        var editor = new VBox(4, label, name, new HBox(6, confirm, cancel), error);
        classByHand.getChildren().add(editor);
        cancel.setOnAction(
                event -> {
                    closeClassEditor();
                    add.requestFocus();
                });
        editor.addEventFilter(
                KeyEvent.KEY_PRESSED,
                event -> {
                    if (event.getCode() == KeyCode.ESCAPE) {
                        event.consume();
                        cancel.fire();
                    }
                });
        name.setOnAction(event -> confirm.fire());
        confirm.setOnAction(
                event -> {
                    try {
                        plan.add(name.getText());
                    } catch (IllegalArgumentException invalid) {
                        error.setText(invalid.getMessage());
                        return;
                    }
                    render(true);
                    // The button goes once the list has its class; the class is then listed here.
                    (classByHand.isVisible() ? add : editClasses).requestFocus();
                    changed.run();
                });
        name.requestFocus();
    }

    private void closeClassEditor() {
        if (classByHand.getChildren().size() > 1) classByHand.getChildren().setAll(add);
    }

    private static void show(boolean visible, Node... nodes) {
        for (var node : nodes) {
            node.setVisible(visible);
            node.setManaged(visible);
        }
    }

    private static HBox choiceRow(RadioButton choice, InformationButton help) {
        choice.setTooltip(help.getTooltip());
        var row = new HBox(4, choice, help);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Label wrapped(String text, boolean muted) {
        var label = new Label(text);
        label.setWrapText(true);
        if (muted) label.getStyleClass().add(HistoforgeStyles.MUTED);
        return label;
    }

    /** The labels beside one class's name field, refreshed as the plan changes. */
    private record ClassRow(ModelClass modelClass, Label origin, Label error) {}

    /** Shows one choice in the open list and follows changes to its class name. */
    private final class TrainAsCell extends ListCell<TrainAs> {
        @Override
        protected void updateItem(TrainAs item, boolean empty) {
            super.updateItem(item, empty);
            textProperty().unbind();
            // In the list, the placeholder marks a missing choice and cannot be picked again.
            setDisable(item == FixedChoice.NOT_CHOSEN);
            if (item instanceof ModelClass) {
                textProperty().bind(Bindings.createStringBinding(() -> text(item), classNameEdits));
            } else {
                setText(text(item));
            }
        }
    }
}
