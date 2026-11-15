package qupath.ext.histoforge.ui.training;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.geometry.VPos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModelDescriptor;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAvailable;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingOption;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.training.TrainingFormFixtures;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.InformationButton;

/** Native controls exercise detached class edits, capability changes and incomplete input. */
final class TrainingClassControlsSmoke {
    private TrainingClassControlsSmoke() {}

    static void verify(Stage owner, Path output) throws Exception {
        var encoded = TrainingCapabilityCodec.toJson(TrainingFormFixtures.capability(true), 4096);
        encoded.addProperty("capability_id", "custom-class-fixture");
        encoded.addProperty("output_type", "instance_segmentation_and_classification");
        encoded.getAsJsonObject("default_training_settings").addProperty("oversample", false);
        encoded.getAsJsonObject("default_training_settings").addProperty("training_batch_size", 2);
        var classified = TrainingCapabilityCodec.parseCapability(encoded, 4096);
        var source =
                TrainingFormFixtures.module(
                        classified,
                        recipe -> {
                            var model = recipe.getAsJsonObject("models").getAsJsonObject("cells");
                            model.add(
                                    "output",
                                    JsonParser.parseString(
                                            """
                    {"type":"instance_segmentation_and_classification","classChannels":[
                     {"channel":0,"role":"background"},
                     {"channel":1,"role":"emitted","pathClass":"Cell"},
                     {"channel":2,"role":"training_only","pathClass":"Other"}]}
                    """));
                            model.getAsJsonObject("params").addProperty("num_classes", 3);
                        },
                        true);
        var edits = new AtomicInteger();
        var form = new TrainingRunForm(() -> {}, edits::incrementAndGet);
        form.select(source, "cells");
        Parent root = (Parent) form.settingsNode();
        var sections = root.getChildrenUnmodifiable();
        require(
                sections.get(0) instanceof Label annotation
                        && annotation.getText().equals("Annotation")
                        && sections.get(1) instanceof TrainingClassControls
                        && sections.get(2) instanceof Label parameters
                        && parameters.getText().equals("Parameters"),
                "Class controls must form an Annotation section separate from Parameters");
        String classHelp = mode(root).getTooltip().getText();
        checkClassHelp(root, classHelp);
        var previous = owner.getScene();
        var content = new VBox(10, form.sourceNode(), form.resultNode(), form.settingsNode());
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        var scene = new Scene(scroll, 700, 880);
        HistoforgeStyles.apply(scene);
        owner.setScene(scene);
        TrainingFormSmoke.applyQuPathStyle("Modena Light", scroll);
        checkClassHelpPopup(root, output.resolve("classes-help-light.png"));
        var task = form.targetTask();
        form.showAnnotationClasses(Set.of("New annotation"));
        require(
                task.equals(form.targetTask()) && edits.get() == 0,
                "Discovery changed the captured task instead of adding background defaults");
        className(root, 1).setText("Renamed cell");
        require(
                form.targetTask().annotationClassMap().get("Cell").equals("Renamed cell"),
                "Renaming broke the existing annotation mapping");
        require(edits.get() > 0, "Class edit did not invalidate capture");
        require(
                form.targetTask()
                        .modelOutput()
                        .classChannels()
                        .get(2)
                        .role()
                        .equals("training_only"),
                "Use lost a training-only channel");
        mode(root).setValue(Initialization.ADD);
        checkClassHelp(root, classHelp);
        require(!form.isValid(), "Unnamed additional class was accepted");
        checkNameError(root, 3, "Enter a class name or remove this row.");
        require(
                !root.lookup("#trainingFormError").isVisible(),
                "Class error was repeated below unrelated Parameters");
        checkNameValidation(form, root);
        TrainingFormSmoke.capture(content, output.resolve("class-error-light-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Dark", scroll);
        TrainingFormSmoke.capture(content, output.resolve("class-error-dark-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Light", scroll);
        className(root, 3).setText("New cell");
        form.showAnnotationClasses(Set.of("New annotation"));
        mapping(root, 2).getSelectionModel().select(3);
        require(
                form.targetTask().annotationClassMap().get("New annotation").equals("New cell"),
                "New annotation mapping was not captured");
        ((Button) classRow(root, 3).getChildren().getLast()).fire();
        checkListError(root, "Add at least one new class.");
        ((Button) root.lookup("#trainingAddClass")).fire();
        className(root, 3).setText("New cell");
        require(
                !form.targetTask().annotationClassMap().containsKey("New annotation"),
                "Removed class retained a dangling mapping");
        mapping(root, 2).getSelectionModel().select(3);
        require(form.isValid(), "Corrected Add form remains invalid");
        require(
                source.recipe().models().get("cells").output().classChannels().size() == 3,
                "Editing the target mutated the installed source");

        TrainingFormSmoke.applyQuPathStyle("Modena Light", scroll);

        mode(root).setValue(Initialization.DEFINE);
        ((Button) classRow(root, 1).getChildren().getLast()).fire();
        require(!form.isValid(), "A target without a predicted class was accepted");
        checkListError(root, "Add at least one predicted class.");
        ((Button) root.lookup("#trainingAddClass")).fire();
        className(root, 2).setText("Recovered class");
        require(form.isValid(), "Adding a predicted class did not recover the form");
        require(!root.lookup("#trainingClassesError").isVisible(), "Class-list error is stale");
        checkGroupAlignment(root);
        TrainingFormSmoke.capture(content, output.resolve("custom-classes-light-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Dark", scroll);
        checkGroupAlignment(root);
        checkClassHelpPopup(root, output.resolve("classes-help-dark.png"));
        TrainingFormSmoke.capture(content, output.resolve("custom-classes-dark-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Light", scroll);

        var base =
                TrainingFormFixtures.module(
                        TrainingFormFixtures.capability(false), ignored -> {}, true);
        var use =
                ((TrainingAvailable) base.descriptor().models().get("cells").trainingAvailability())
                        .options()
                        .getFirst();
        var create =
                new TrainingOption(classified, List.of(Initialization.ADD, Initialization.DEFINE));
        form.select(withOptions(base, List.of(use, create)), "cells");
        root = (Parent) form.settingsNode();
        checkTrainingTask(form, false);
        var resolution = (TextField) root.lookup("#trainingResolution");
        require(
                root.lookup("#oversampleTrainingFields") == null,
                "Base Use has classification controls");
        resolution.clear();
        mode(root).setValue(Initialization.DEFINE);
        checkTrainingTask(form, true);
        checkClassHelp(root, classHelp);
        require(!form.isTargetValid(), "Blank resolution was accepted");
        resolution.setText("0.37");
        require(form.isValid(), "Restored resolution did not rebind the target capability");
        require(
                root.lookup("#oversampleTrainingFields") != null,
                "Changing mode while resolution was blank left stale settings");
        require(
                form.draft().settings().trainingBatchSize() == 2,
                "Target capability defaults were not applied");
        require(form.targetTask().modelMicronsPerPixel() == 0.37, "Target resolution was lost");
        require(
                form.targetTask().modelOutput().classChannels().size() == 2,
                "One-foreground-class target lost its explicit background/class head");
        mode(root).setValue(Initialization.USE);
        checkTrainingTask(form, false);
        checkClassHelp(root, classHelp);
        require(
                root.lookup("#oversampleTrainingFields") == null
                        && form.draft().settings().oversample() == null,
                "Hidden classification settings carried into segmentation-only task");
        mode(root).setValue(Initialization.ADD);
        checkTrainingTask(form, true);
        TrainingFormSmoke.capture(content, output.resolve("segmentation-to-classified-target.png"));
        form.select(withOptions(base, List.of(create)), "cells");
        checkTrainingTask(form, true);
        require(
                form.isValid() && form.classInitialization() == Initialization.DEFINE,
                "Base model cannot initialize a supported classified target");
        checkClassHelp(root, classHelp);
        owner.setScene(previous);
    }

    private static void checkTrainingTask(TrainingRunForm form, boolean changed) {
        var source = (Label) form.sourceNode().lookup("#trainingSourceTask");
        require(
                source.getText().equals("Instance segmentation"),
                "Target class choice overwrote the source task");
        var target = (Label) form.settingsNode().lookup("#trainingTargetTask");
        require(
                target.getParent().isVisible() == changed
                        && target.getParent().isManaged() == changed,
                "Training task row must appear only when different from the source");
        if (changed)
            require(
                    target.getText().equals("Instance segmentation + classification"),
                    "Changed training task is not explained in Annotation");
    }

    private static void checkGroupAlignment(Parent root) {
        for (String text : List.of("Model classes", "Annotation mapping", "Augmentations")) {
            var label =
                    root.lookupAll(".label").stream()
                            .filter(
                                    node ->
                                            node instanceof Label value
                                                    && value.getText().equals(text))
                            .map(Label.class::cast)
                            .findFirst()
                            .orElseThrow();
            var grid = (GridPane) label.getParent();
            var controls =
                    grid.getChildren().stream()
                            .filter(
                                    node ->
                                            Integer.valueOf(1)
                                                    .equals(GridPane.getColumnIndex(node)))
                            .filter(
                                    node ->
                                            Objects.equals(
                                                    GridPane.getRowIndex(node),
                                                    GridPane.getRowIndex(label)))
                            .findFirst()
                            .orElseThrow();
            require(GridPane.getValignment(label) == VPos.TOP, text + " must be top-aligned");
            require(
                    Math.abs(
                                    label.getBoundsInParent().getMinY()
                                            - controls.getBoundsInParent().getMinY())
                            <= 1,
                    text + " does not align with the top of its controls");
        }
    }

    private static void checkNameValidation(TrainingRunForm form, Parent root) {
        for (String blank : List.of("   ", "\u00a0")) {
            className(root, 3).setText(blank);
            require(!form.isTargetValid(), "Whitespace-only class name was accepted");
            checkNameError(root, 3, "Enter a class name or remove this row.");
        }
        for (int existing : List.of(1, 2)) {
            className(root, 3).setText(" " + className(root, existing).getText() + " ");
            require(!form.isValid(), "Duplicate foreground/training-only class was accepted");
            checkNameError(root, 3, "Use a different name; this class name is already used.");
            checkNameError(
                    root, existing, "Use a different name; this class name is already used.");
        }
        className(root, 3).setText("Parent:Child");
        require(!form.isValid(), "A noncanonical compound class name was accepted");
        checkNameError(
                root, 3, "Enter a valid QuPath class name (for subclasses, use Parent: Child).");
        for (String valid : List.of("Parent: Child", "Background", "New cell")) {
            className(root, 3).setText(valid);
            require(form.isValid(), "Valid class name was rejected: " + valid);
            for (int index = 0; index < 4; index++) {
                require(
                        !root.lookup("#trainingClassError" + index).isVisible(),
                        "Corrected name left stale feedback at class " + index);
            }
        }
        className(root, 3).clear();
    }

    private static void checkNameError(Parent root, int index, String expected) {
        var name = className(root, index);
        var error = (Label) root.lookup("#trainingClassError" + index);
        require(
                error != null && error.isVisible() && error.isManaged(),
                "Class error is not visible at row " + index);
        require(error.getText().equals(expected), "Wrong class error: " + error.getText());
        require(
                error.getParent() == name.getParent().getParent(),
                "Class error is not adjacent to its name field");
    }

    private static void checkListError(Parent root, String expected) {
        var error = (Label) root.lookup("#trainingClassesError");
        require(
                error.isVisible() && error.getText().equals(expected),
                "Missing actionable class-list error: " + expected);
        require(
                error.getParent() == root.lookup("#trainingModelClasses"),
                "Class-list error is outside Model classes");
    }

    private static void checkClassHelp(Parent root, String expected) {
        var label = (Label) root.lookup("#trainingClassesLabel");
        var info = (InformationButton) root.lookup("#trainingClassesInfo");
        var help = mode(root).getTooltip();
        require(info != null && info.isVisible(), "Classes must have a visible information button");
        require(
                label.getTooltip() == help && info.getTooltip() == help,
                "Classes label, selector and information button must share their help");
        require(help.getText().equals(expected), "Classes help must not change with the selection");
        for (var choice : Initialization.values()) {
            require(
                    help.getText().contains(choice + ":"),
                    "Classes help must explain every choice together: " + choice);
        }
        require(
                help.getText().contains("resets the class list and annotation mapping"),
                "Classes help lost the mode-change warning");
        require(
                root.lookupAll(".label").stream()
                        .noneMatch(
                                node ->
                                        node instanceof Label row
                                                && row.getText().equals("Initialization")),
                "Redundant Initialization row is still visible");
    }

    private static void checkClassHelpPopup(Parent root, Path screenshot) {
        var info = (InformationButton) root.lookup("#trainingClassesInfo");
        var help = mode(root).getTooltip();
        var bounds = info.localToScreen(info.getBoundsInLocal());
        require(
                bounds != null && bounds.getWidth() > 0,
                "Classes information button is not laid out");
        help.show(info, bounds.getMinX(), bounds.getMaxY());
        require(help.isShowing(), "Classes help preview did not open");
        info.fire();
        require(!help.isShowing(), "Clicking Classes information must replace its hover preview");
        var popup =
                Window.getWindows().stream()
                        .filter(Popup.class::isInstance)
                        .map(Popup.class::cast)
                        .filter(window -> window.getOwnerNode() == info && window.isShowing())
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Classes information did not open"));
        require(
                popup.getScene().getRoot().lookupAll(".label").stream()
                        .anyMatch(
                                node ->
                                        node instanceof Label label
                                                && label.getText().equals(help.getText())),
                "Classes popup and hover preview differ");
        require(
                popup.isAutoHide() && popup.isHideOnEscape(),
                "Classes popup lost standard dismissal");
        TrainingFormSmoke.capture((Parent) popup.getContent().getFirst(), screenshot);
        info.fire();
        require(
                !popup.isShowing() && info.getTooltip() == help,
                "Closing Classes help did not restore hover");
    }

    private static InstalledModule withOptions(
            InstalledModule source, List<TrainingOption> options) {
        var descriptor = source.descriptor();
        var models = new HashMap<>(descriptor.models());
        var model = models.get("cells");
        models.put(
                "cells",
                new InstalledModelDescriptor(
                        model.modelConfigDigest(),
                        model.artifactDigests(),
                        model.largeRegionProfile(),
                        model.runnerAvailability(),
                        model.precisionOverrides(),
                        new TrainingAvailable(options)));
        return new InstalledModule(
                source.gateway(),
                new InstalledModuleDescriptor(
                        descriptor.identity(),
                        descriptor.installedAt(),
                        descriptor.installedModuleFileBytes(),
                        descriptor.recipe(),
                        descriptor.scripts(),
                        descriptor.scriptSetDigest(),
                        models,
                        descriptor.readmeMarkdown()));
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Initialization> mode(Parent root) {
        return (ComboBox<Initialization>) root.lookup("#trainingClassInitialization");
    }

    private static HBox classRow(Parent root, int index) {
        var row = (VBox) ((VBox) root.lookup("#trainingModelClasses")).getChildren().get(index);
        return (HBox) row.getChildren().getFirst();
    }

    private static TextField className(Parent root, int index) {
        return (TextField) root.lookup("#trainingClassName" + index);
    }

    private static ComboBox<?> mapping(Parent root, int index) {
        var row =
                (HBox) ((VBox) root.lookup("#trainingAnnotationMapping")).getChildren().get(index);
        return (ComboBox<?>) row.getChildren().get(1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
