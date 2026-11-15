package qupath.ext.histoforge.ui.project;

import com.google.gson.GsonBuilder;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import qupath.ext.histoforge.data.ProjectInferenceScope;
import qupath.ext.histoforge.data.ProjectInferenceScope.Mode;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.lib.projects.Projects;

/** Real Project-tab controls; disposable projects only, with no inference or cloud connection. */
public final class AnnotationScopeSmoke {
    private AnnotationScopeSmoke() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path projects = Files.createTempDirectory(output, "projects-");
        var first =
                Projects.createProject(
                        Files.createDirectory(projects.resolve("first")).toFile(),
                        BufferedImage.class);
        var second =
                Projects.createProject(
                        Files.createDirectory(projects.resolve("second")).toFile(),
                        BufferedImage.class);
        var selected = new ProjectInferenceScope(Mode.ANNOTATIONS_BY_CLASS, Optional.of("Tissue"));
        selected.save(first);
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Platform.startup(
                () -> {
                    Stage stage = new Stage();
                    try {
                        var loader =
                                new FXMLLoader(
                                        ProjectTabController.class.getResource("project_tab.fxml"));
                        Parent root = loader.load();
                        @SuppressWarnings("unchecked")
                        var mode = (ComboBox<Mode>) loader.getNamespace().get("regionModeComboBox");
                        @SuppressWarnings("unchecked")
                        var annotationClass =
                                (ComboBox<String>)
                                        loader.getNamespace().get("annotationClassComboBox");
                        var changes = new AtomicInteger();
                        var controls =
                                new InferenceScopeControls(
                                        mode, annotationClass, changes::incrementAndGet);
                        require(
                                !annotationClass.isManaged(),
                                "Class selector must be hidden in HistoForge ROI mode");
                        controls.restore(ProjectInferenceScope.read(first));
                        Scene scene = new Scene(root, 1250, 650);
                        HistoforgeStyles.apply(scene);
                        stage.setScene(scene);
                        stage.setTitle("HistoForge annotation scope — local UI verification");
                        stage.show();
                        root.applyCss();
                        root.layout();
                        @SuppressWarnings("unchecked")
                        var skin = (ComboBoxListViewSkin<String>) annotationClass.getSkin();
                        @SuppressWarnings("unchecked")
                        var popup = (ListView<String>) skin.getPopupContent();
                        // Discovery runs after the control has been rendered and its menu used.
                        // The displayed label, popup selection and submitted class must agree.
                        controls.refreshClasses(List.of("Tissue", "Tissue: Other", "Stroma"));
                        requireSelection(annotationClass, skin, popup, "Tissue");
                        controls.refreshClasses(List.of("Tissue", "Tissue: Other", "Stroma"));
                        requireSelection(annotationClass, skin, popup, "Tissue");
                        require(
                                controls.selected().equals(selected),
                                "Saved project selection was lost");
                        require(
                                changes.get() == 0,
                                "Restore/discovery must not initiate another refresh");
                        require(
                                annotationClass.isVisible() && annotationClass.isManaged(),
                                "Class selector is missing");
                        require(
                                annotationClass
                                        .getItems()
                                        .equals(List.of("Stroma", "Tissue", "Tissue: Other")),
                                "Class choices changed exact names");
                        popup.getSelectionModel().select("Stroma");
                        requireSelection(annotationClass, skin, popup, "Stroma");
                        require(
                                changes.get() == 1
                                        && controls.selected()
                                                .selectedClass()
                                                .orElseThrow()
                                                .equals("Stroma"),
                                "Selecting a class did not update the scope");
                        controls.refreshClasses(List.of("Tissue"));
                        requireSelection(annotationClass, skin, popup, "Stroma");
                        require(
                                annotationClass.getValue().equals("Stroma"),
                                "Refresh substituted a missing selected class");
                        controls.restore(ProjectInferenceScope.read(second));
                        require(
                                controls.selected().equals(ProjectInferenceScope.defaults()),
                                "Scope leaked into another project");
                        require(
                                annotationClass.getItems().isEmpty(),
                                "Annotation choices leaked into another project");
                        controls.restore(ProjectInferenceScope.read(first));
                        controls.refreshClasses(List.of("Tissue", "Stroma"));

                        root.applyCss();
                        root.layout();
                        requireSelection(annotationClass, skin, popup, "Tissue");
                        require(changes.get() == 1, "Restore/discovery emitted a selection change");
                        require(
                                annotationClass.getTooltip().getText().contains("lock and save"),
                                "Lock behavior is not explained");
                        for (String id :
                                List.of(
                                        "regionModeComboBox",
                                        "annotationClassComboBox",
                                        "stepsPickerButton",
                                        "saveAndRefreshButton",
                                        "redoButton")) {
                            var control =
                                    (javafx.scene.control.Control) loader.getNamespace().get(id);
                            var help = control.getTooltip();
                            require(
                                    help != null && help.isWrapText() && help.getMaxWidth() == 420,
                                    id + " help is not bounded");
                        }
                        var help = annotationClass.getTooltip();
                        var helpBounds =
                                annotationClass.localToScreen(annotationClass.getBoundsInLocal());
                        help.show(annotationClass, helpBounds.getMinX(), helpBounds.getMaxY());
                        help.getScene().getRoot().applyCss();
                        help.getScene().getRoot().layout();
                        try {
                            require(
                                    help.isShowing()
                                            && help.getWidth() > 0
                                            && help.getWidth() <= 450,
                                    "Annotation help exceeds its text width plus popup decoration");
                        } finally {
                            help.hide();
                        }
                        ImageIO.write(
                                SwingFXUtils.fromFXImage(root.snapshot(null, null), null),
                                "PNG",
                                output.resolve("annotation-scope.png").toFile());
                        verifyLayouts(loader, root, scene, controls, output);
                        System.out.println(
                                "PASS: exact choices, visibility, restore, refresh, project isolation and responsive labeled controls; real FXML captured.");
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        stage.close();
                        done.countDown();
                    }
                });
        if (!done.await(45, TimeUnit.SECONDS))
            throw new AssertionError("Native scope check timed out");
        Platform.exit();
        if (failure.get() != null)
            throw new AssertionError("Native scope check failed", failure.get());
    }

    private static void verifyLayouts(
            FXMLLoader loader,
            Parent root,
            Scene scene,
            InferenceScopeControls controls,
            Path output)
            throws Exception {
        var region = (Region) root;
        scene.setRoot(new StackPane());
        var frame = new StackPane(root);
        scene.setRoot(frame);
        var fields = loader.getNamespace();
        var annotation = (Node) fields.get("annotationClassField");
        var models = (MenuButton) fields.get("stepsPickerButton");
        String longModel = "whole-cell-segmentation-with-a-long-model-name";
        var cases = new ArrayList<Map<String, Object>>();
        for (String theme : List.of("light", "dark")) {
            root.setStyle(
                    theme.equals("dark") ? "-fx-base: #303030; -fx-background: #242424;" : "");
            for (int width : List.of(300, 360, 560, 760, 1100)) {
                region.setPrefWidth(width);
                region.setMaxWidth(width);
                for (Mode mode : Mode.values()) {
                    boolean byClass = mode == Mode.ANNOTATIONS_BY_CLASS;
                    controls.restore(
                            new ProjectInferenceScope(
                                    mode, byClass ? Optional.of("Tissue") : Optional.empty()));
                    for (String model : List.of("All models", longModel)) {
                        models.setText(model);
                        frame.applyCss();
                        frame.layout();
                        require(Math.abs(region.getWidth() - width) < 1, "Test width not applied");
                        require(
                                annotation.isVisible() == byClass
                                        && annotation.isManaged() == byClass,
                                "Annotation label left visible or taking space in another scope");
                        for (String id :
                                List.of(
                                        "setModuleButton",
                                        "inferenceSettingsButton",
                                        "assignGroupButton",
                                        "exportButton",
                                        "saveAndRefreshButton",
                                        "regionModeComboBox",
                                        "stepsPickerButton",
                                        "runButton",
                                        "redoButton",
                                        "metricsButton",
                                        "defaultWidthSpinner",
                                        "defaultHeightSpinner",
                                        "roiUnitComboBox")) {
                            requireInside(root, (Node) fields.get(id), id);
                        }
                        if (byClass)
                            requireInside(
                                    root,
                                    (Node) fields.get("annotationClassComboBox"),
                                    "annotation class");
                        for (String id :
                                List.of(
                                        "regionModeComboBox",
                                        "annotationClassComboBox",
                                        "stepsPickerButton")) {
                            Node control = (Node) fields.get(id);
                            Label label =
                                    (Label)
                                            control.getParent()
                                                    .getChildrenUnmodifiable()
                                                    .getFirst();
                            require(
                                    label.getLabelFor() == control,
                                    "Field label is not associated with " + id);
                            if (!id.equals("annotationClassComboBox") || byClass) {
                                require(
                                        label.localToScene(label.getBoundsInLocal()).getMaxY()
                                                <= control.localToScene(control.getBoundsInLocal())
                                                        .getMinY(),
                                        "Field label overlaps " + id);
                            }
                        }
                        Node project = (Node) fields.get("projectActions");
                        Node analysis = (Node) fields.get("analysisControls");
                        require(
                                project.localToScene(project.getBoundsInLocal()).getMaxY()
                                        <= analysis.localToScene(analysis.getBoundsInLocal())
                                                .getMinY(),
                                "Project and analysis controls overlap");
                        require(
                                models.getWidth() <= 141 && models.getText().equals(model),
                                "Long model name widened the toolbar or changed its value");
                        var fill = (Node) fields.get("runButton");
                        if (Math.abs(
                                        fill.getParent().getLayoutY()
                                                - models.getParent().getLayoutY())
                                < 1) {
                            require(
                                    Math.abs(
                                                    fill.localToScene(fill.getBoundsInLocal())
                                                                    .getMaxY()
                                                            - models.localToScene(
                                                                            models
                                                                                    .getBoundsInLocal())
                                                                    .getMaxY())
                                            < 1,
                                    "Analysis actions do not align with the fields in their row");
                        }
                        for (String id : List.of("redoButton", "metricsButton")) {
                            Node action = (Node) fields.get(id);
                            require(
                                    action.getParent() == fill.getParent(),
                                    "Analysis actions split apart");
                            require(
                                    Math.abs(
                                                    action.localToScene(action.getBoundsInLocal())
                                                                    .getMinY()
                                                            - fill.localToScene(
                                                                            fill.getBoundsInLocal())
                                                                    .getMinY())
                                            < 1,
                                    "Analysis actions wrapped individually");
                        }
                        cases.add(
                                Map.of(
                                        "theme",
                                        theme,
                                        "width",
                                        width,
                                        "scope",
                                        mode.name(),
                                        "longModelName",
                                        model.equals(longModel),
                                        "status",
                                        "PASS"));
                        if (byClass && model.equals("All models")) {
                            ImageIO.write(
                                    SwingFXUtils.fromFXImage(root.snapshot(null, null), null),
                                    "PNG",
                                    output.resolve(
                                                    "project-controls-"
                                                            + theme
                                                            + "-"
                                                            + width
                                                            + ".png")
                                            .toFile());
                        }
                    }
                }
            }
        }
        var evidence =
                Map.of(
                        "status",
                        "PASS",
                        "cases",
                        cases,
                        "fxml",
                        ProjectTabController.class.getResource("project_tab.fxml").toString(),
                        "controllerOrigin",
                        ProjectTabController.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toString());
        Files.writeString(
                output.resolve("project-controls-layout.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(evidence) + "\n");
    }

    private static void requireInside(Node root, Node control, String description) {
        var outer = root.localToScene(root.getLayoutBounds());
        var inner = control.localToScene(control.getBoundsInLocal());
        require(
                inner.getMinX() >= outer.getMinX() - 1 && inner.getMaxX() <= outer.getMaxX() + 1,
                description + " extends outside the window");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void requireSelection(
            ComboBox<String> control,
            ComboBoxListViewSkin<String> skin,
            ListView<String> popup,
            String expected) {
        require(expected.equals(control.getValue()), "Submitted class differs from selection");
        require(
                expected.equals(control.getSelectionModel().getSelectedItem()),
                "Selected item differs from the submitted class");
        require(
                expected.equals(popup.getSelectionModel().getSelectedItem()),
                "Popup highlights a class different from the submitted class");
        require(
                expected.equals(((Labeled) skin.getDisplayNode()).getText()),
                "Displayed class differs from the submitted class");
    }
}
