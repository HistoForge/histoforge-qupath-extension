package qupath.ext.histoforge.ui.training;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import qupath.ext.histoforge.contract.ModelOutputCodec.ClassChannel;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModelDescriptor;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAvailable;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingOption;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.training.TrainingFormFixtures;
import qupath.ext.histoforge.training.TrainingRunDraft.ResultForm;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.InformationButton;

/** Native controls exercise detached class edits, capability changes and incomplete input. */
final class TrainingClassControlsSmoke {
    private static final String BACKGROUND = "Background (not an object)";

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
        form.forgetAnnotationClasses();
        form.select(source, "cells");
        var sections = ((Parent) form.annotationNode()).getChildrenUnmodifiable();
        require(
                sections.get(0) instanceof Label annotation
                        && annotation.getText().equals("Training classes")
                        && sections.get(1) instanceof TrainingClassControls,
                "Class controls must have a separate section above the split");
        var previous = owner.getScene();
        var content =
                new VBox(
                        10,
                        form.sourceNode(),
                        form.resultNode(),
                        form.annotationNode(),
                        form.settingsNode());
        Parent root = content;
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        var scene = new Scene(scroll, 700, 880);
        HistoforgeStyles.apply(scene);
        owner.setScene(scene);
        TrainingFormSmoke.applyQuPathStyle("Modena Light", scroll);

        checkChoices(root, "Keep the model's classes", "Replace the model's classes");
        require(
                keep(root).isSelected() && form.classInitialization() == Initialization.USE,
                "A model with classes must start by keeping them");
        form.showAnnotationClasses(Set.of("Previous project class"));
        form.select(null, null);
        form.forgetAnnotationClasses();
        form.select(source, "cells");
        require(
                findMapping(root, "Previous project class") == null,
                "Switching projects without a selected model carried over the old class list");
        var task = form.targetTask();
        require(!canAddByHand(root), "Keeping classes offers a class without annotations");
        form.showAnnotationClasses(Set.of("New annotation", "Cell", "Other"));
        require(
                task.equals(form.targetTask()) && edits.get() == 0,
                "Discovery changed the captured task instead of adding background defaults");
        require(
                chosen(root, "Cell").equals("Cell")
                        && chosen(root, "Other").equals("Other (left out of results)")
                        && chosen(root, "New annotation").equals(BACKGROUND),
                "Exact-name presets or the Background default are wrong");
        require(
                choices(root, "New annotation")
                        .equals(
                                List.of(
                                        BACKGROUND,
                                        "Cell",
                                        "Other (left out of results)",
                                        "New model class “New annotation”")),
                "Train as must offer Background, the model's classes and one new class");
        require(
                !choices(root, "Cell").contains("New model class “Cell”"),
                "A class named like the QuPath class was offered a second time");
        checkPredictions(root, "Learned: Cell", "Left out of results: Other");
        checkAssignmentColumns(root);

        expandClasses(root);
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
                "Keeping classes lost a training-only channel");
        require(
                form.classInitialization() == Initialization.USE
                        && chosen(root, "Cell").equals("Renamed cell"),
                "Renaming a kept class changed the initialization or left a stale Train as name");
        checkPredictions(
                root, "Learned: Renamed cell (learned as Cell)", "Left out of results: Other");
        require(
                origin(root, 1).equals("learned as Cell")
                        && origin(root, 2).equals("learned · left out of results"),
                "The class editor hides where a class's knowledge starts");
        require(
                root.lookup("#trainingCopiedClassNames") == null,
                "Renaming a class still shows the copied-workflow warning");
        var renamedTask = form.targetTask();
        require(
                form.draft().resultForm() == ResultForm.COMPLETE_WORKFLOW,
                "Renaming a class changed the selected result type");
        resultForm(root).setValue(ResultForm.STANDALONE);
        require(
                form.draft().resultForm() == ResultForm.STANDALONE
                        && form.targetTask().equals(renamedTask),
                "Switching to standalone did not preserve the renamed classes");
        resultForm(root).setValue(ResultForm.COMPLETE_WORKFLOW);

        int changes = edits.get();
        choose(root, "New annotation", "New model class “New annotation”");
        require(
                form.classInitialization() == Initialization.ADD && edits.get() > changes,
                "A class added to kept classes did not become an Add run");
        var addedTask = form.targetTask();
        require(
                addedTask.annotationClassMap().get("New annotation").equals("New annotation"),
                "New class was not assigned to the QuPath label");
        require(
                addedTask
                        .modelOutput()
                        .classChannels()
                        .equals(
                                List.of(
                                        new ClassChannel(0, "background", null),
                                        new ClassChannel(1, "emitted", "Renamed cell"),
                                        new ClassChannel(2, "training_only", "Other"),
                                        new ClassChannel(3, "emitted", "New annotation"))),
                "Add changed the retained channel order, roles or names");
        checkPredictions(
                root,
                "Learned: Renamed cell (learned as Cell)",
                "New: New annotation",
                "Left out of results: Other");
        require(
                !choices(root, "New annotation").contains("New model class “New annotation”")
                        && origin(root, 3).equals("new"),
                "A created class is offered again or not marked as new");
        require(
                root.lookup("#trainingRemoveClass3") != null
                        && root.lookup("#trainingRemoveClass1") == null
                        && root.lookup("#trainingRemoveClass2") == null,
                "Only a new class may be removed");
        TrainingFormSmoke.capture(content, output.resolve("class-added-light-700.png"));

        className(root, 3).clear();
        require(
                !root.lookup("#trainingFormError").isVisible(),
                "Class error was repeated below unrelated Parameters");
        TrainingFormSmoke.capture(content, output.resolve("class-error-light-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Dark", scroll);
        TrainingFormSmoke.capture(content, output.resolve("class-error-dark-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Light", scroll);
        checkNameValidation(form, root);
        editor(root).setExpanded(false);
        className(root, 3).setText("Parent:Child");
        require(editor(root).isExpanded(), "A name error stayed hidden in the collapsed editor");
        className(root, 3).setText("New cell");

        require(
                choices(root, "New annotation").contains("New model class “New annotation”"),
                "Renaming a class did not update which rows may create their own class");
        ((Button) root.lookup("#trainingRemoveClass3")).fire();
        require(
                chosen(root, "New annotation").equals("Not chosen")
                        && problem(root).equals("Choose Train as for New annotation.")
                        && !form.isValid()
                        && form.isTargetValid(),
                "A removed class must ask again for its annotations without blocking Refresh");
        choose(root, "New annotation", "New model class “New annotation”");
        require(form.classInitialization() == Initialization.ADD, "Re-creating a class failed");
        choose(root, "New annotation", BACKGROUND);
        require(
                form.isValid()
                        && form.classInitialization() == Initialization.USE
                        && root.lookup("#trainingClassName3") == null
                        && !root.lookup("#trainingClassesError").isVisible(),
                "A class made by a row outlived its last row");
        checkRenamedRowClassStays(form, root);
        checkRemovedClassCannotBeChosenAgain(form, root);
        checkKeyboardAndPopupSelection(form, root);
        checkAccessibleValue(root);

        var warning = (Label) root.lookup("#trainingClassesWarning");
        choose(root, "Cell", BACKGROUND);
        require(
                form.isTargetValid()
                        && warning.isVisible()
                        && warning.getText().startsWith("No QuPath class is set to Renamed cell,")
                        && warning.getText().endsWith("choose Replace the model's classes."),
                "A kept class without annotations is not explained");
        choose(root, "Other", BACKGROUND);
        require(
                form.isTargetValid()
                        && !form.isValid()
                        && problem(root)
                                .startsWith("Set at least one QuPath class to a model class.")
                        && !removeUnused(root).isVisible()
                        && !warning.isVisible(),
                "Training with every QuPath class as background was accepted");
        choose(root, "Cell", "Renamed cell");
        choose(root, "Other", "Other (left out of results)");
        require(form.isValid() && !warning.isVisible(), "Restored choices left a stale message");
        require(
                source.recipe().models().get("cells").output().classChannels().size() == 3,
                "Editing the target mutated the installed source");

        changes = edits.get();
        replace(root).setSelected(true);
        require(
                edits.get() > changes && form.classInitialization() == Initialization.DEFINE,
                "Replacing the model's classes did not become a Define run");
        require(
                chosen(root, "Cell").equals("Not chosen")
                        && choices(root, "Cell").getFirst().equals("Not chosen")
                        && chosen(root, "Other").equals("Other (left out of results)")
                        && chosen(root, "New annotation").equals(BACKGROUND),
                "A new class list must ask again only for annotations of a replaced class");
        require(
                !form.isValid() && problem(root).equals("Choose Train as for Cell."),
                "A missing choice for a replaced class did not block readiness");
        TrainingFormSmoke.capture(content, output.resolve("class-not-chosen-light-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Dark", scroll);
        TrainingFormSmoke.capture(content, output.resolve("class-not-chosen-dark-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Light", scroll);
        choose(root, "Cell", BACKGROUND);
        require(
                !form.isValid()
                        && problem(root)
                                .equals(
                                        "Add at least one model class: choose New model class in Train as."),
                "A target without a predicted class was accepted");
        choose(root, "New annotation", "New model class “New annotation”");
        require(form.isValid(), "Choosing what to train did not recover the form");
        var replaced = form.targetTask().modelOutput();
        require(
                replaced.classChannels()
                        .equals(
                                List.of(
                                        new ClassChannel(0, "background", null),
                                        new ClassChannel(1, "training_only", "Other"),
                                        new ClassChannel(2, "emitted", "New annotation"))),
                "A new class list lost the background or training-only class, or kept an old one");
        checkPredictions(root, "New: New annotation", "Left out of results: Other");
        require(
                origin(root, 1).equals("new · left out of results"),
                "A carried training-only class is still described as learned");
        choose(root, "Other", BACKGROUND);
        require(
                form.isTargetValid()
                        && !form.isValid()
                        && root.lookup("#trainingClassesError").isVisible()
                        && problem(root)
                                .equals(
                                        "No QuPath class is set to Other. Choose it in Train as, or ")
                        && removeUnused(root).isVisible()
                        && removeUnused(root).getText().equals("remove Other"),
                "A new class without annotations was accepted or cannot be removed in place");
        TrainingFormSmoke.capture(content, output.resolve("class-unused-light-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Dark", scroll);
        TrainingFormSmoke.capture(content, output.resolve("class-unused-dark-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Light", scroll);
        removeUnused(root).fire();
        require(
                form.isValid()
                        && !root.lookup("#trainingClassesError").isVisible()
                        && form.targetTask().modelOutput().classChannels().size() == 2,
                "Removing the unused class did not leave a trainable class list");

        keep(root).setSelected(true);
        require(
                form.classInitialization() == Initialization.USE
                        && form.targetTask()
                                .annotationClassMap()
                                // A QuPath class named like the renamed class would train it too.
                                .equals(
                                        Map.of(
                                                "Cell", "Renamed cell",
                                                "Other", "Other",
                                                "Renamed cell", "Renamed cell")),
                "Keeping classes lost its own choices after the new list was edited");
        replace(root).setSelected(true);
        require(
                form.targetTask()
                        .annotationClassMap()
                        .equals(Map.of("New annotation", "New annotation")),
                "The new class list lost its own choices after switching away");
        form.showAnnotationClasses(Set.of("New annotation", "Other"));
        require(findMapping(root, "Cell") == null, "Removed QuPath labels remained after refresh");
        checkAssignmentColumns(root);
        TrainingFormSmoke.capture(content, output.resolve("custom-classes-light-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Dark", scroll);
        checkAssignmentColumns(root);
        TrainingFormSmoke.capture(content, output.resolve("custom-classes-dark-700.png"));
        TrainingFormSmoke.applyQuPathStyle("Modena Light", scroll);

        form.select(
                withOptions(
                        source,
                        List.of(new TrainingOption(classified, List.of(Initialization.USE)))),
                "cells");
        require(
                !replace(root).isVisible()
                        && keep(root).isSelected()
                        && !keep(root).getTooltip().getText().contains("New model class")
                        && choices(root, "New annotation")
                                .equals(List.of(BACKGROUND, "Cell", "Other (left out of results)")),
                "A choice the backend does not offer is shown");
        // Arrow keys move between the radios of a group, including one that is only hidden.
        keep(root)
                .fireEvent(
                        new KeyEvent(
                                KeyEvent.KEY_PRESSED,
                                "",
                                "",
                                KeyCode.DOWN,
                                false,
                                false,
                                false,
                                false));
        flush();
        require(
                keep(root).isSelected()
                        && !replace(root).isSelected()
                        && form.classInitialization() == Initialization.USE,
                "The arrow keys reached a choice the backend does not offer");
        form.select(
                withOptions(
                        source,
                        List.of(new TrainingOption(classified, List.of(Initialization.ADD)))),
                "cells");
        form.forgetAnnotationClasses();
        require(
                !form.isTargetValid() && canAddByHand(root),
                "A model that must gain a class cannot name it before the project is read");
        ((Button) root.lookup("#trainingAddClass")).fire();
        confirmNewClass(root, "Extra");
        require(
                form.isValid()
                        && form.classInitialization() == Initialization.ADD
                        && !canAddByHand(root),
                "Naming the first added class did not make the project readable");
        ((Button) root.lookup("#trainingRemoveClass3")).fire();
        require(
                !form.isTargetValid() && canAddByHand(root),
                "Removing the added class left no way to name another and blocked Refresh");
        // Every QuPath class is named like a model class, so no Train as row offers a new one.
        form.showAnnotationClasses(Set.of("Cell", "Other"));
        require(
                !form.isTargetValid()
                        && canAddByHand(root)
                        && problem(root).equals("Add at least one new model class."),
                "A model that must gain a class cannot name it when every row is taken");
        TrainingFormSmoke.capture(content, output.resolve("class-by-hand-light-700.png"));
        ((Button) root.lookup("#trainingAddClass")).fire();
        TrainingFormSmoke.capture(content, output.resolve("class-by-hand-editor-light-700.png"));
        confirmNewClass(root, "Extra");
        require(
                form.isTargetValid()
                        && !canAddByHand(root)
                        && root.lookup("#trainingNewClassName") == null
                        && root.getScene().getFocusOwner() == editor(root),
                "A class named by hand did not make the project readable, or left focus on a"
                        + " hidden control");
        form.showAnnotationClasses(Set.of("Cell", "Other", "New annotation"));
        ((Button) root.lookup("#trainingRemoveClass3")).fire();
        require(
                !canAddByHand(root)
                        && problem(root)
                                .equals(
                                        "Add at least one new model class: choose New model class in"
                                                + " Train as."),
                "The class is offered by hand although a Train as row can create it");
        choose(root, "New annotation", "New model class “New annotation”");
        require(form.isValid(), "Creating the class from a row did not recover the form");

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
        form.forgetAnnotationClasses();
        checkChoices(root, "Outline objects only", "Outline and classify objects");
        checkTrainingTask(form, false);
        checkPredictions(root, "Cell");
        var resolution = (TextField) root.lookup("#trainingResolution");
        require(
                root.lookup("#oversampleTrainingFields") == null
                        && form.isTargetValid()
                        && form.classInitialization() == Initialization.USE,
                "Outline-only training has classification controls or is not trainable");
        form.showAnnotationClasses(Set.of());
        require(
                form.isTargetValid()
                        && !form.isValid()
                        && problem(root)
                                .startsWith("No classified annotations or detections were found")
                        && guidance(root)
                                .equals("The project has no annotation classes to train on."),
                "A project read that found no class looks unread or leaves training enabled");
        form.forgetAnnotationClasses();
        require(
                form.isValid()
                        && guidance(root)
                                .equals("Refresh the project to see its annotation classes."),
                "An unread project is reported as empty");
        form.showAnnotationClasses(Set.of("Nucleus"));
        require(
                form.isTargetValid()
                        && !form.isValid()
                        && problem(root)
                                .startsWith("Set at least one QuPath class to a model class."),
                "Outline-only training with every class as background was accepted");
        require(
                choices(root, "Nucleus").equals(List.of(BACKGROUND, "Cell")),
                "Outline-only training offers a new class");
        choose(root, "Nucleus", "Cell");
        require(
                form.isTargetValid()
                        && form.targetTask()
                                .annotationClassMap()
                                .equals(Map.of("Cell", "Cell", "Nucleus", "Cell")),
                "Assigning the one label did not make outline-only training ready");
        TrainingFormSmoke.capture(content, output.resolve("outline-only-light-700.png"));
        className(root, 0).setText("Outline");
        require(
                keep(root).getTooltip().getText().endsWith("Every object gets the label Outline.")
                        && form.targetTask().modelOutput().classes().equals(List.of("Outline")),
                "The outline-only help names a label the trained model will not use");
        className(root, 0).setText("Cell");
        form.forgetAnnotationClasses();

        resolution.clear();
        replace(root).setSelected(true);
        checkTrainingTask(form, true);
        require(
                keep(root).getTooltip().getText().endsWith("Every object gets the label Cell."),
                "The outline-only choice lost its label while the other choice is active");
        require(!form.isTargetValid(), "Blank resolution was accepted");
        require(canAddByHand(root), "A new class list cannot be named before the project is read");
        ((Button) root.lookup("#trainingAddClass")).fire();
        confirmNewClass(root, "Nucleus");
        resolution.setText("0.37");
        require(form.isValid(), "Restored resolution did not rebind the target capability");
        require(
                root.lookup("#oversampleTrainingFields") != null,
                "Changing the choice while resolution was blank left stale settings");
        require(
                form.draft().settings().trainingBatchSize() == 2,
                "Target capability defaults were not applied");
        require(form.targetTask().modelMicronsPerPixel() == 0.37, "Target resolution was lost");
        require(
                form.targetTask().modelOutput().classChannels().size() == 2
                        && form.classInitialization() == Initialization.DEFINE,
                "One-foreground-class target lost its explicit background/class head");
        form.showAnnotationClasses(Set.of("Nucleus", "Debris"));
        require(
                chosen(root, "Nucleus").equals("Nucleus")
                        && chosen(root, "Debris").equals(BACKGROUND)
                        && form.isValid(),
                "A class named before the project was read did not pick up its annotations");
        form.forgetAnnotationClasses();
        keep(root).setSelected(true);
        checkTrainingTask(form, false);
        require(
                root.lookup("#oversampleTrainingFields") == null
                        && form.draft().settings().oversample() == null,
                "Hidden classification settings carried into segmentation-only task");
        replace(root).setSelected(true);
        TrainingFormSmoke.capture(content, output.resolve("segmentation-to-classified-target.png"));
        form.select(withOptions(base, List.of(create)), "cells");
        checkTrainingTask(form, true);
        require(
                !form.isValid() && !keep(root).isVisible() && replace(root).isSelected(),
                "A model without outline-only training still offers it");
        form.select(
                withOptions(
                        base, List.of(new TrainingOption(classified, List.of(Initialization.ADD)))),
                "cells");
        ((Button) root.lookup("#trainingAddClass")).fire();
        confirmNewClass(root, "Nucleus");
        require(
                form.isValid() && form.classInitialization() == Initialization.ADD,
                "A catalog with only Add lost an advertised option");
        checkReturningOneClassToBackground(form, root, source);
        owner.setScene(previous);
    }

    private static void checkReturningOneClassToBackground(
            TrainingRunForm form, Parent root, InstalledModule source) {
        var labels =
                List.of(
                        "Ignore",
                        "Intra-Tubule",
                        "Leydig Cell",
                        "Myoid",
                        "Round Leydig Nuclei",
                        "Spindle Leydig Nuclei");
        form.forgetAnnotationClasses();
        form.select(source, "cells");
        var found = new HashSet<>(labels);
        found.add("Other");
        form.showAnnotationClasses(found);
        replace(root).setSelected(true);

        Map<String, String> expected = new HashMap<>();
        expected.put("Other", "Other");
        for (String label : labels) {
            int create = choices(root, label).indexOf("New model class “" + label + "”");
            require(create >= 0, "Missing new model class choice for " + label);
            selectInOpenList(mapping(root, label), create);
            expected.put(label, label);
            var current = form.targetTask().annotationClassMap();
            require(
                    current.equals(expected),
                    "Creating " + label + " changed mappings: " + current);
        }
        selectInOpenList(mapping(root, "Ignore"), 0);
        expected.remove("Ignore");
        var current = form.targetTask().annotationClassMap();
        require(
                current.equals(expected),
                "Setting Ignore to Background changed mappings: " + current);
        root.applyCss();
        root.layout();
        require(
                mapping(root, "Ignore").getButtonCell().getText().equals(BACKGROUND),
                "Ignore did not return to Background");
        for (String label : labels.subList(1, labels.size())) {
            var picker = mapping(root, label);
            require(
                    chosen(root, label).equals(label)
                            && picker.getSelectionModel().getSelectedIndex()
                                    == picker.getItems().indexOf(picker.getValue()),
                    "Setting Ignore to Background changed the displayed mapping for " + label);
            require(
                    label.equals(picker.getButtonCell().getText()),
                    "Train as displays " + picker.getButtonCell().getText() + " for " + label);
        }
        require(
                mapping(root, "Other")
                        .getButtonCell()
                        .getText()
                        .equals("Other (left out of results)"),
                "The carried training class lost its label");
        require(
                form.isValid(),
                "Returning one annotation class to Background invalidated the form");
    }

    private static void checkTrainingTask(TrainingRunForm form, boolean changed) {
        var source = (Label) form.sourceNode().lookup("#trainingSourceTask");
        require(
                source.getText().equals("Instance segmentation"),
                "Target class choice overwrote the source task");
        var target = (Label) form.annotationNode().lookup("#trainingTargetTask");
        require(
                target.getParent().isVisible() == changed
                        && target.getParent().isManaged() == changed,
                "Training task row must appear only when different from the source");
        if (changed)
            require(
                    target.getText().equals("Instance segmentation + classification"),
                    "Changed training task is not explained in Training classes");
    }

    private static void checkAssignmentColumns(Parent root) {
        var table = (GridPane) root.lookup("#trainingAnnotationMapping");
        require(
                table.getChildren().getFirst() instanceof Label left
                        && left.getText().equals("QuPath annotation class")
                        && table.getChildren().get(1) instanceof HBox right
                        && right.getChildren().getFirst() instanceof Label name
                        && name.getText().equals("Train as"),
                "Mapping column headings are missing");
        var info = (InformationButton) table.lookup("#trainingTrainAsInfo");
        require(
                info != null && info.getTooltip().getText().contains("background"),
                "Train as does not explain the background assignment");
        require(
                table.getChildren().stream()
                        .filter(ComboBox.class::isInstance)
                        .map(ComboBox.class::cast)
                        .allMatch(
                                box ->
                                        GridPane.getColumnIndex(box) == 1
                                                && box.getTooltip()
                                                        .getText()
                                                        .equals(info.getTooltip().getText())
                                                && box.getTooltip()
                                                        .getShowDuration()
                                                        .isIndefinite()),
                "Train as pickers must align and share the header's hover help");
    }

    private static void checkNameValidation(TrainingRunForm form, Parent root) {
        for (String blank : List.of("   ", " ")) {
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
        require(
                !root.lookup("#trainingClassesError").isVisible(),
                "A name error is repeated away from its field");
        for (String valid : List.of("Parent: Child", "Background", "New cell")) {
            className(root, 3).setText(valid);
            require(form.isValid(), "Valid class name was rejected: " + valid);
            for (int index = 1; index < 4; index++) {
                require(
                        !root.lookup("#trainingClassError" + index).isVisible(),
                        "Corrected name left stale feedback at class " + index);
            }
        }
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

    /**
     * Drive one picker the way JavaFX delivers real input. The arrow keys apply every stop at once,
     * and the open list reports a selection while the picker's own list is still in use.
     */
    private static void checkKeyboardAndPopupSelection(TrainingRunForm form, Parent root) {
        var picker = mapping(root, "New annotation");
        int lastStop = picker.getItems().size() - 1;
        for (int step = 0; step < lastStop; step++) press(picker, KeyCode.DOWN);
        require(
                chosen(root, "New annotation").equals("New annotation")
                        && form.classInitialization() == Initialization.ADD,
                "Arrowing onto New model class did not create and assign it");
        for (int step = 0; step < lastStop; step++) press(picker, KeyCode.UP);
        require(
                chosen(root, "New annotation").equals(BACKGROUND)
                        && form.isValid()
                        && form.classInitialization() == Initialization.USE
                        && root.lookup("#trainingClassName3") == null,
                "Arrowing past New model class left a class without annotations behind");

        selectInOpenList(picker, lastStop);
        require(
                chosen(root, "New annotation").equals("New annotation")
                        && choices(root, "New annotation").size() == lastStop + 1
                        && form.isValid(),
                "Picking New model class in the open list did not create and assign it");
        selectInOpenList(picker, 0);
        require(
                chosen(root, "New annotation").equals(BACKGROUND)
                        && form.classInitialization() == Initialization.USE
                        && choices(root, "New annotation")
                                .contains("New model class “New annotation”"),
                "Picking Background in the open list did not undo the created class");
    }

    /** A class the researcher renamed is theirs to remove; moving its row away keeps it. */
    private static void checkRenamedRowClassStays(TrainingRunForm form, Parent root) {
        choose(root, "New annotation", "New model class “New annotation”");
        className(root, 3).setText("Dividing nucleus");
        choose(root, "New annotation", BACKGROUND);
        require(
                root.lookup("#trainingClassName3") != null
                        && choices(root, "New annotation").contains("Dividing nucleus")
                        && form.classInitialization() == Initialization.ADD
                        && problem(root)
                                .equals(
                                        "No QuPath class is set to Dividing nucleus. Choose it in"
                                                + " Train as, or ")
                        && removeUnused(root).getText().equals("remove Dividing nucleus"),
                "Moving a row off a class it created deleted the class and its typed name");
        removeUnused(root).fire();
        require(
                form.isValid()
                        && form.classInitialization() == Initialization.USE
                        && root.lookup("#trainingClassName3") == null,
                "Removing the renamed class did not restore the kept classes");
    }

    /**
     * A picker's own list is redrawn only after JavaFX has delivered its selection. Until then it
     * still holds a class that the selection just removed.
     */
    private static void checkRemovedClassCannotBeChosenAgain(TrainingRunForm form, Parent root) {
        var picker = mapping(root, "New annotation");
        choose(root, "New annotation", "New model class “New annotation”");
        Object created = picker.getValue();
        picker.setValue(picker.getItems().getFirst());
        require(picker.getItems().contains(created), "The picker's list was redrawn too early");
        picker.setValue(created);
        flush();
        require(
                chosen(root, "New annotation").equals(BACKGROUND)
                        && form.isValid()
                        && form.classInitialization() == Initialization.USE
                        && !form.targetTask().annotationClassMap().containsKey("New annotation"),
                "A class that was just removed could be chosen again from the picker's old list");
    }

    /** A screen reader reads the closed picker's selected value, and its row label names it. */
    private static void checkAccessibleValue(Parent root) {
        var picker = mapping(root, "Cell");
        require(
                "Renamed cell".equals(accessibleValue(root, picker)),
                "The closed picker hides its selected value from screen readers: "
                        + accessibleValue(root, picker));
        choose(root, "Cell", BACKGROUND);
        require(
                BACKGROUND.equals(accessibleValue(root, picker)),
                "The picker's accessible value did not follow its selection");
        choose(root, "Cell", "Renamed cell");
    }

    private static Object accessibleValue(Parent root, ComboBox<Object> picker) {
        root.applyCss();
        root.layout();
        return picker.queryAccessibleAttribute(AccessibleAttribute.TEXT);
    }

    private static void press(ComboBox<Object> picker, KeyCode key) {
        picker.fireEvent(
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
        flush();
    }

    @SuppressWarnings("unchecked")
    private static void selectInOpenList(ComboBox<Object> picker, int index) {
        picker.show();
        flush();
        var list =
                (ListView<Object>)
                        ((ComboBoxListViewSkin<Object>) picker.getSkin()).getPopupContent();
        list.getSelectionModel().select(index);
        flush();
        picker.hide();
        flush();
    }

    private static void checkChoices(Parent root, String first, String second) {
        require(
                keep(root).isVisible()
                        && keep(root).getText().equals(first)
                        && replace(root).isVisible()
                        && replace(root).getText().equals(second),
                "Model classes must offer: " + first + " / " + second);
        for (var option : List.of(keep(root), replace(root))) {
            var explanation =
                    (InformationButton) ((HBox) option.getParent()).getChildren().getLast();
            var hover = option.getTooltip();
            require(
                    explanation.isVisible()
                            && hover == explanation.getTooltip()
                            && !hover.getText().isBlank()
                            && hover.isWrapText()
                            && hover.getShowDuration().isIndefinite(),
                    "Each Model classes option must share hover and information-button help");
        }
    }

    private static void checkPredictions(Parent root, String... expected) {
        var lines =
                ((VBox) root.lookup("#trainingModelPredictions"))
                        .getChildren().stream().map(node -> ((Label) node).getText()).toList();
        require(lines.equals(List.of(expected)), "Model predicts shows " + lines);
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

    /** Whether the form offers to name a model class by hand. */
    private static boolean canAddByHand(Parent root) {
        var add = root.lookup("#trainingAddClass");
        return add != null && add.getParent().isVisible() && add.getParent().isManaged();
    }

    private static RadioButton keep(Parent root) {
        return (RadioButton) root.lookup("#trainingKeepClasses");
    }

    private static RadioButton replace(Parent root) {
        return (RadioButton) root.lookup("#trainingReplaceClasses");
    }

    private static TitledPane editor(Parent root) {
        return (TitledPane) root.lookup("#trainingEditModelClasses");
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ResultForm> resultForm(Parent root) {
        return (ComboBox<ResultForm>) root.lookup("#trainingResultForm");
    }

    private static TextField className(Parent root, int index) {
        return (TextField) root.lookup("#trainingClassName" + index);
    }

    private static String origin(Parent root, int index) {
        return ((Label) root.lookup("#trainingClassOrigin" + index)).getText();
    }

    private static String problem(Parent root) {
        return ((Text) root.lookup("#trainingClassesErrorText")).getText();
    }

    private static Hyperlink removeUnused(Parent root) {
        return (Hyperlink) root.lookup("#trainingRemoveUnusedClass");
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Object> findMapping(Parent root, String name) {
        var table = (GridPane) root.lookup("#trainingAnnotationMapping");
        return table.getChildren().stream()
                .filter(node -> node instanceof Label label && label.getText().equals(name))
                .map(node -> (ComboBox<Object>) ((Label) node).getLabelFor())
                .findFirst()
                .orElse(null);
    }

    private static ComboBox<Object> mapping(Parent root, String name) {
        var box = findMapping(root, name);
        require(box != null, "Missing QuPath class " + name);
        return box;
    }

    /** The Train as choices of one QuPath class, as the researcher reads them. */
    private static List<String> choices(Parent root, String name) {
        var box = mapping(root, name);
        return box.getItems().stream().map(box.getConverter()::toString).toList();
    }

    private static String chosen(Parent root, String name) {
        var box = mapping(root, name);
        return box.getConverter().toString(box.getValue());
    }

    private static void choose(Parent root, String name, String choice) {
        var box = mapping(root, name);
        box.setValue(
                box.getItems().stream()
                        .filter(item -> box.getConverter().toString(item).equals(choice))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "No Train as choice "
                                                        + choice
                                                        + " for "
                                                        + name
                                                        + ": "
                                                        + choices(root, name))));
        flush();
    }

    /** The line shown in place of the Train as rows while no annotation class is listed. */
    private static String guidance(Parent root) {
        var table = (GridPane) root.lookup("#trainingAnnotationMapping");
        return ((Label) table.getChildren().getFirst()).getText();
    }

    /**
     * Run what the controls deferred until JavaFX finished delivering a picker's selection, so the
     * next check reads the redrawn picker.
     */
    private static void flush() {
        var loop = new Object();
        Platform.runLater(() -> Platform.exitNestedEventLoop(loop, null));
        Platform.enterNestedEventLoop(loop);
    }

    private static void expandClasses(Parent root) {
        // Without animation frames in this synchronous check, an animated pane would stay drawn
        // closed in the screenshots.
        editor(root).setAnimated(false);
        editor(root).setExpanded(true);
        root.applyCss();
        root.layout();
    }

    private static void confirmNewClass(Parent root, String output) {
        ((TextField) root.lookup("#trainingNewClassName")).setText(output);
        ((Button) root.lookup("#trainingConfirmNewClass")).fire();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
