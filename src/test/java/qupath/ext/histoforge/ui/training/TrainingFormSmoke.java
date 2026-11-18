package qupath.ext.histoforge.ui.training;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.training.TrainingAssignment;
import qupath.ext.histoforge.training.TrainingFieldSizes.Unit;
import qupath.ext.histoforge.training.TrainingFormFixtures;
import qupath.ext.histoforge.training.TrainingOutlineOverlaps;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder.ProjectPlan;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.InformationButton;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.ui.app.SettingsDialogSmoke;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.QuPathStyleManager;
import qupath.lib.projects.Project;

/** Opt-in rendered form checks. Uses fixture metadata, no user project or cloud connection. */
public final class TrainingFormSmoke {
    private TrainingFormSmoke() {}

    public static void main(String[] args) throws Exception {
        System.setProperty(
                "java.util.prefs.PreferencesFactory",
                qupath.ext.histoforge.platform.IsolatedPreferencesFactory.class.getName());
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var finished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Platform.startup(
                () -> {
                    Stage stage = new Stage();
                    try {
                        var loader =
                                new FXMLLoader(
                                        TrainingTabController.class.getResource(
                                                "training_tab.fxml"));
                        Parent tab = loader.load();
                        require(
                                !((Node) loader.getNamespace().get("sourceDetailsBox")).isManaged(),
                                "Source details must be absent before selecting a model");
                        var splitHeading =
                                (Label)
                                        ((Parent) loader.getNamespace().get("assignmentHeadingBox"))
                                                .getChildrenUnmodifiable()
                                                .getFirst();
                        require(
                                splitHeading.getText().equals("Train-Val Split"),
                                "Split section heading is incorrect");
                        ScrollPane root =
                                (ScrollPane) loader.getNamespace().get("trainingFormScrollPane");
                        TrainingTabController controller = loader.getController();
                        var qupath = mock(QuPathGUI.class);
                        Project<BufferedImage> project = mock(Project.class);
                        when(project.getPath())
                                .thenReturn(Path.of("/fixture-training/project.qpproj"));
                        when(qupath.getProject()).thenReturn(project);
                        when(qupath.projectProperty())
                                .thenReturn(new SimpleObjectProperty<>(project));
                        var client = mock(ModuleGatewayClient.class);
                        var transport = mock(GatewayTransport.class);
                        var registry = new ModulesRegistry(client);
                        var fixed =
                                TrainingFormFixtures.module(
                                        TrainingFormFixtures.capability(false),
                                        ignored -> {},
                                        true);
                        registry.asObservable().setAll(fixed);
                        controller.init(
                                qupath,
                                transport,
                                client,
                                registry,
                                () -> {
                                    throw new AssertionError("Unexpected project capture");
                                },
                                mock(ImageLifecycle.class),
                                () -> {},
                                () -> {});
                        Scene scene = new Scene(tab, 900, 880);
                        HistoforgeStyles.apply(scene);
                        stage.setScene(scene);
                        stage.setTitle("HistoForge training form smoke test");
                        stage.show();
                        applyQuPathStyle("Modena Light", root);
                        showReadingProgress(tab);
                        later(
                                () -> {
                                    try {
                                        checkProgressPlacement(tab, root, output, "top");
                                        require(
                                                ((Button) root.lookup("#generateSplitButton"))
                                                        .isDisabled(),
                                                "Generate split must wait for a current project"
                                                        + " capture");
                                        require(
                                                root.lookup("#smallRoiContext") == null,
                                                "Fixed context must not be an editable control");
                                        require(
                                                field(root, "minimumDetectedObjectArea")
                                                        .getText()
                                                        .equals("15"),
                                                "Wrong minimum object area default");
                                        field(root, "trainingResultName")
                                                .setText("Keep my chosen result name");
                                        registry.asObservable()
                                                .setAll(
                                                        new InstalledModule(
                                                                fixed.gateway(),
                                                                fixed.descriptor()));
                                        require(
                                                field(root, "trainingResultName")
                                                        .getText()
                                                        .equals("Keep my chosen result name"),
                                                "Unchanged catalog reset the form");
                                        capture(
                                                (Parent) root.getContent(),
                                                output.resolve("fixed-900.png"));
                                        checkBounds(root);
                                        checkLongTooltips(root);
                                        checkSourceSummary(root);
                                        checkInlineControls(fixed);
                                        checkOverlapDialog(stage, output);
                                        SettingsDialogSmoke.verify(stage, output);
                                        checkFlowControls(fixed, output);
                                        checkSamplingControls(stage, fixed, output);
                                        TrainingClassControlsSmoke.verify(stage, output);

                                        var variable =
                                                TrainingFormFixtures.module(
                                                        TrainingFormFixtures.capability(true),
                                                        ignored -> {},
                                                        true);
                                        registry.asObservable().setAll(variable);
                                        root.applyCss();
                                        root.layout();
                                        require(
                                                !field(root, "trainingResultName")
                                                        .getText()
                                                        .equals("Keep my chosen result name"),
                                                "Changed catalog did not reset the form");
                                        require(
                                                root.lookup("#smallRoiContext") instanceof CheckBox,
                                                "Editable context is missing");
                                        checkUnits(root);
                                        stage.setWidth(700);
                                        showReadingProgress(tab);
                                        root.setVvalue(1);
                                        later(
                                                () -> {
                                                    try {
                                                        checkProgressPlacement(
                                                                tab, root, output, "bottom");
                                                        capture(
                                                                (Parent) root.getContent(),
                                                                output.resolve(
                                                                        "variable-light-700.png"));
                                                        checkBounds(root);
                                                        applyQuPathStyle("Modena Dark", root);
                                                        capture(
                                                                (Parent) root.getContent(),
                                                                output.resolve(
                                                                        "variable-dark-700.png"));
                                                        checkBounds(root);
                                                        applyQuPathStyle("Modena Light", root);
                                                        var populated =
                                                                TrainingFormFixtures
                                                                        .populatedProject(
                                                                                variable, "cells");
                                                        showProject(
                                                                controller,
                                                                populated.plan(),
                                                                populated.assignment());
                                                        stage.setWidth(1100);
                                                        later(
                                                                () -> {
                                                                    try {
                                                                        checkPopulated(
                                                                                root, output);
                                                                        checkTargetReadiness(
                                                                                root,
                                                                                controller,
                                                                                variable,
                                                                                output);
                                                                        registry.asObservable()
                                                                                .clear();
                                                                        require(
                                                                                root.lookup(
                                                                                                "#trainingResultName")
                                                                                        == null,
                                                                                "Removed module left an active form");
                                                                        require(
                                                                                !root.lookup(
                                                                                                        "#sourceDetailsBox")
                                                                                                .isManaged()
                                                                                        && root
                                                                                                        .lookup(
                                                                                                                "#trainingSourceTask")
                                                                                                == null,
                                                                                "Removed module left stale source details");
                                                                        verifyNoInteractions(
                                                                                transport, client);
                                                                        System.out.println(
                                                                                "PASS: FXML, populated assignment, catalog refresh, unit conversion, context/flow controls, augmentation defaults, inline settings, light/dark themes, and layout at 700/900/1100 px. No project or cloud IO.");
                                                                    } catch (Throwable error) {
                                                                        failure.set(error);
                                                                    } finally {
                                                                        stage.close();
                                                                        finished.countDown();
                                                                    }
                                                                });
                                                    } catch (Throwable error) {
                                                        failure.set(error);
                                                        stage.close();
                                                        finished.countDown();
                                                    }
                                                });
                                    } catch (Throwable error) {
                                        failure.set(error);
                                        stage.close();
                                        finished.countDown();
                                    }
                                });
                    } catch (Throwable error) {
                        failure.set(error);
                        stage.close();
                        finished.countDown();
                    }
                });
        try {
            if (!finished.await(30, TimeUnit.SECONDS))
                throw new AssertionError("UI smoke test timed out");
            if (failure.get() != null)
                throw new AssertionError("Training form smoke test failed", failure.get());
        } finally {
            Platform.exit();
        }
    }

    private static void checkUnits(ScrollPane root) {
        @SuppressWarnings("unchecked")
        ComboBox<Unit> units = (ComboBox<Unit>) root.lookup("#viewUnits");
        units.setValue(Unit.MODEL_PIXELS);
        TextField minimum = field(root, "minimumView");
        require(minimum.getText().equals("128"), "Wrong pixel conversion");
        minimum.setText("136");
        units.setValue(Unit.MICROMETRES);
        require(minimum.getText().equals("72"), "Halfway alignment not reflected in unit switch");
        minimum.setText("bad");
        units.setValue(Unit.MODEL_PIXELS);
        require(
                units.getValue() == Unit.MICROMETRES && minimum.getText().equals("bad"),
                "Invalid input was silently replaced");
        minimum.setText("64");
    }

    private static void checkSourceSummary(Parent root) {
        var architecture = (Label) root.lookup("#trainingSourceArchitecture");
        var task = (Label) root.lookup("#trainingSourceTask");
        var info = (InformationButton) root.lookup("#trainingSourceArchitectureInfo");
        require(
                architecture.getText().equals("DINOv3 + Cellpose head"),
                "Source architecture is not taken from the selected recipe");
        require(
                task.getText().equals("Instance segmentation"),
                "Source task is not taken from the selected output contract");
        require(
                info.isVisible() && info.getTooltip().getText().contains("dinov3-adapter-cellpose"),
                "Source architecture information must expose the exact ID");
        require(
                architecture.getTooltip() == info.getTooltip()
                        || architecture.getTooltip().getText().equals(info.getTooltip().getText()),
                "Architecture label and information help differ");
        require(
                !task.getStyleClass().contains(HistoforgeStyles.HEADING),
                "Source task must use ordinary parameter styling");
        var model = root.lookup("#modelComboBox");
        require(
                Math.abs(
                                architecture.localToScene(architecture.getBoundsInLocal()).getMinX()
                                        - model.localToScene(model.getBoundsInLocal()).getMinX())
                        <= 1,
                "Source details do not align with Model");
    }

    private static void checkInlineControls(InstalledModule source) {
        var form = new TrainingRunForm(() -> {}, () -> {});
        form.select(source, "cells");
        Parent root = (Parent) form.settingsNode();
        require(root.lookup("#resolvedSchedule") == null, "Schedule summary is still displayed");
        var capability =
                qupath.ext.histoforge.training.TrainingRunDraft.options(source, "cells")
                        .getFirst()
                        .capability();
        require(
                form.draft().settings().equals(capability.defaultTrainingSettings()),
                "Inline controls changed the capability defaults");
        var profile = capability.augmentationProfiles().getFirst();
        for (var operator : profile.operators()) {
            var box = (CheckBox) root.lookup("#" + operator.operatorId());
            require(
                    box.getTooltip().getText().equals(operator.description()),
                    "Missing augmentation help");
            box.setSelected(false);
        }
        require(
                form.draft().augmentation().enabledOperatorIds().isEmpty(),
                "Empty augmentation selection lost");
        ((Button) root.lookup("#augmentationDefaults")).fire();
        require(
                form.draft()
                        .augmentation()
                        .enabledOperatorIds()
                        .equals(
                                profile.operators().stream()
                                        .filter(value -> value.defaultEnabled())
                                        .map(value -> value.operatorId())
                                        .toList()),
                "Restore defaults did not restore the canonical operator selection");

        ((CheckBox) root.lookup("#balanceFlowLoss")).setSelected(true);
        int steps = Math.toIntExact(capability.gradientAccumulationStepsRange().maximum());
        field(root, "gradientAccumulation").setText(Integer.toString(steps));
        require(
                form.draft().settings().gradientAccumulationSteps() == steps,
                "Accumulation was not captured");
        require(
                "equal_instance".equals(form.draft().settings().flowLossWeighting()),
                "Resource edit lost flow weighting");
        for (String id :
                new String[] {
                    "trainingBatchSize",
                    "trainingEpochs",
                    "dataLoadingWorkers",
                    "gradientAccumulation"
                }) {
            var input = field(root, id);
            String valid = input.getText();
            input.setText("bad");
            require(!form.isValid(), "Invalid " + id + " was accepted");
            require(
                    ((Label) root.lookup("#trainingFormError")).isVisible(),
                    "Invalid input has no explanation");
            input.setText(valid);
            require(form.isValid(), "Corrected " + id + " remains invalid");
        }
        field(root, "trainingBatchSize")
                .setText(Long.toString(capability.trainingBatchSizeRange().maximum() + 1));
        require(!form.isValid(), "Out-of-range batch size was accepted");
        field(root, "trainingBatchSize")
                .setText(
                        Integer.toString(capability.defaultTrainingSettings().trainingBatchSize()));

        @SuppressWarnings("unchecked")
        var mode = (ComboBox<String>) root.lookup("#warmupMode");
        var total = field(root, "trainingEpochs");
        var warmup = field(root, "warmupEpochs");
        total.setText("1");
        require(
                warmup.isDisabled() && warmup.getText().equals("0"),
                "Auto warmup did not follow epochs");
        require(form.draft().settings().warmupEpochs() == 0, "One-epoch run has warmup");
        total.setText("80");
        mode.setValue("Custom");
        warmup.setText("7");
        total.setText("5");
        require(
                !form.isValid() && warmup.getText().equals("7"),
                "Custom warmup was silently clamped");
        warmup.setText("0");
        require(form.draft().settings().warmupEpochs() == 0, "Custom zero warmup was lost");
        warmup.setText("bad");
        require(!form.isValid(), "Invalid custom warmup was accepted");
        mode.setValue("Automatic");
        require(form.isValid(), "Automatic warmup did not replace invalid custom value");
        require(
                form.draft().settings().warmupEpochs()
                        == TrainingCapabilityCodec.automaticWarmupEpochs(
                                5, capability.automaticWarmup()),
                "Wrong automatic warmup resolution");
        total.clear();
        require(
                !form.isValid() && warmup.getText().isEmpty(),
                "Empty epochs left a misleading warmup");
        total.setText("80");
        require(form.isValid(), "Restored epoch count did not recover form");
        form.select(source, "cells");
        require(
                form.draft().settings().equals(capability.defaultTrainingSettings()),
                "Model reset retained edited settings");
    }

    private static void showReadingProgress(Parent tab) {
        var progress = (Region) tab.lookup("#progressBox");
        require(progress.getParent() == tab, "Progress is inside the scrolling form");
        require(!progress.isVisible() && !progress.isManaged(), "Idle progress occupies space");
        progress.setVisible(true);
        progress.setManaged(true);
        ((Label) tab.lookup("#progressLabel"))
                .setText(
                        "Reading training-slide-with-a-long-descriptive-filename-for-layout-testing.tiff (11/58)");
        ((ProgressBar) tab.lookup("#progressBar")).setProgress(11.0 / 58);
    }

    /** Called after the next JavaFX pulse, so the skin and scroll position have been laid out. */
    private static void checkProgressPlacement(
            Parent tab, ScrollPane form, Path output, String position) {
        tab.applyCss();
        tab.layout();
        var progress = (Region) tab.lookup("#progressBox");
        var bounds = progress.localToScene(progress.getBoundsInLocal());
        var formBounds = form.localToScene(form.getBoundsInLocal());
        require(bounds.getMinY() >= 0, "Reading progress moved above the viewport");
        require(bounds.getMaxY() <= formBounds.getMinY(), "Reading progress overlaps the form");
        var bar = (ProgressBar) tab.lookup("#progressBar");
        require(
                bar.isVisible() && bar.getHeight() > 0,
                "Progress bar is not visible: height="
                        + bar.getHeight()
                        + ", preferred="
                        + bar.prefHeight(-1)
                        + ", minimum="
                        + bar.minHeight(-1)
                        + ", container="
                        + progress.getHeight()
                        + ", container preferred="
                        + progress.prefHeight(-1));
        var cancel = tab.lookup("#cancelButton");
        require(!cancel.isDisabled() && cancel.isVisible(), "Cancel is unavailable");
        require(
                cancel.localToScene(cancel.getBoundsInLocal()).getMaxX()
                        <= tab.getScene().getWidth(),
                "Reading label pushed Cancel outside the viewport");
        capture(tab, output.resolve("reading-progress-" + position + ".png"));
        progress.setVisible(false);
        progress.setManaged(false);
        form.setVvalue(0);
    }

    private static void checkFlowControls(InstalledModule source, Path output) throws Exception {
        var form = new TrainingRunForm(() -> {}, () -> {});
        form.select(source, "cells");
        var control = (CheckBox) form.settingsNode().lookup("#balanceFlowLoss");
        require(
                control != null && !control.isSelected() && !control.isDisabled(),
                "Flow weighting did not use the advertised default/options");
        checkFlowHelp(form, control, output);
        control.setSelected(true);
        require(
                "equal_instance".equals(form.draft().settings().flowLossWeighting()),
                "Checkbox did not reach the run draft");
        var capability =
                TrainingCapabilityCodec.toJson(TrainingFormFixtures.capability(false), 4096);
        capability.remove("flow_loss_weighting_options");
        capability.getAsJsonObject("default_training_settings").remove("flow_loss_weighting");
        form.select(
                TrainingFormFixtures.module(
                        TrainingCapabilityCodec.parseCapability(capability, 4096),
                        ignored -> {},
                        true),
                "cells");
        require(
                form.settingsNode().lookup("#balanceFlowLoss") == null,
                "Inapplicable flow control is visible");
        require(
                form.draft().settings().flowLossWeighting() == null,
                "Hidden flow choice leaked into another model's draft");
        form.select(source, "cells");
        require(
                !((CheckBox) form.settingsNode().lookup("#balanceFlowLoss")).isSelected(),
                "Model switch did not reset the selected flow weighting");
        for (String fixed : new String[] {"pixel", "equal_instance"}) {
            var options = new JsonArray();
            options.add(fixed);
            capability.add("flow_loss_weighting_options", options);
            capability
                    .getAsJsonObject("default_training_settings")
                    .addProperty("flow_loss_weighting", fixed);
            form.select(
                    TrainingFormFixtures.module(
                            TrainingCapabilityCodec.parseCapability(capability, 4096),
                            ignored -> {},
                            true),
                    "cells");
            var fixedControl = (CheckBox) form.settingsNode().lookup("#balanceFlowLoss");
            require(
                    fixedControl != null && fixedControl.isDisabled(),
                    "A fixed flow setting must not be editable");
            require(
                    fixed.equals(form.draft().settings().flowLossWeighting()),
                    "A fixed flow setting did not reach the run draft");
        }
    }

    private static void checkFlowHelp(TrainingRunForm form, CheckBox control, Path output) {
        var info =
                control.getParent().getChildrenUnmodifiable().stream()
                        .filter(InformationButton.class::isInstance)
                        .map(InformationButton.class::cast)
                        .findFirst()
                        .orElseThrow();
        String text = control.getTooltip().getText();
        require(
                text.equals(info.getTooltip().getText()),
                "Flow control and information hover differ");
        require(
                text.contains("small annotated objects")
                        && text.contains("does not balance classes"),
                "Flow help lost its size weighting or class-balance limitation");
        var preview = new Stage();
        preview.setScene(
                new Scene(new javafx.scene.layout.StackPane(form.settingsNode()), 720, 850));
        HistoforgeStyles.apply(preview.getScene());
        try {
            preview.show();
            preview.getScene().getRoot().applyCss();
            preview.getScene().getRoot().layout();
            info.fire();
            var popup =
                    javafx.stage.Window.getWindows().stream()
                            .filter(javafx.stage.Popup.class::isInstance)
                            .map(javafx.stage.Popup.class::cast)
                            .filter(window -> window.getOwnerNode() == info && window.isShowing())
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Flow information did not open"));
            require(
                    popup.getScene().getRoot().lookupAll(".label").stream()
                            .anyMatch(
                                    node ->
                                            node instanceof Label label
                                                    && text.equals(label.getText())),
                    "Flow hover and information popup differ");
            capture(popup.getScene().getRoot(), output.resolve("flow-help-popup.png"));
            info.fire();
            require(
                    !popup.isShowing() && text.equals(info.getTooltip().getText()),
                    "Closing flow information did not restore its hover");
        } finally {
            preview.close();
        }
    }

    private static void checkOverlapDialog(Stage owner, Path output) {
        var review =
                new TrainingOutlineOverlaps.Review(
                        1, "fixture-slide-with-a-long-name-for-wrapping.tiff", 36581.86, 89291.19);
        var dialog = TrainingTabController.outlineTrimDialog(owner, review);
        var pane = dialog.getDialogPane();
        require(pane.getButtonTypes().size() == 2, "Repair should offer only Yes and No");
        var decline = pane.getButtonTypes().getLast();
        var no = (Button) pane.lookupButton(decline);
        var yes = (Button) pane.lookupButton(ButtonType.YES);
        require(
                no.getText().equals("No")
                        && no.isCancelButton()
                        && no.isDefaultButton()
                        && !yes.isDefaultButton(),
                "Closing or pressing Enter must default to No");
        dialog.show();
        capture(pane, output.resolve("training-overlap-confirmation.png"));
        no.fire();
        require(
                !dialog.isShowing() && dialog.getResult() == decline,
                "No must dismiss the prompt without accepting");
        dialog.show();
        yes.fire();
        require(
                !dialog.isShowing() && dialog.getResult() == ButtonType.YES,
                "Yes must accept the planned repair");
    }

    private static void checkLongTooltips(Parent root) {
        for (String id :
                new String[] {"modelComboBox", "generateSplitButton", "validationPercentSpinner"}) {
            var control = (javafx.scene.control.Control) root.lookup("#" + id);
            var help = control.getTooltip();
            require(
                    help != null && help.isWrapText() && help.getMaxWidth() == 420,
                    id + " help is not bounded");
            require(help.getShowDuration().isIndefinite(), id + " help has a hover timeout");
            var bounds = control.localToScreen(control.getBoundsInLocal());
            require(bounds != null, id + " is not in the native scene");
            help.show(control, bounds.getMinX(), bounds.getMaxY());
            help.getScene().getRoot().applyCss();
            help.getScene().getRoot().layout();
            try {
                require(
                        help.isShowing() && help.getWidth() > 0 && help.getWidth() <= 450,
                        id + " help exceeds its text width plus popup decoration");
            } finally {
                help.hide();
            }
        }
    }

    private static void checkSamplingControls(
            Stage owner, InstalledModule unclassified, Path output) throws Exception {
        var encoded = TrainingCapabilityCodec.toJson(TrainingFormFixtures.capability(false), 4096);
        encoded.addProperty("output_type", "instance_segmentation_and_classification");
        encoded.getAsJsonObject("default_training_settings").addProperty("oversample", false);
        var capability = TrainingCapabilityCodec.parseCapability(encoded, 4096);
        var source =
                TrainingFormFixtures.module(
                        capability,
                        recipe ->
                                recipe.getAsJsonObject("models")
                                        .getAsJsonObject("cells")
                                        .add(
                                                "output",
                                                com.google.gson.JsonParser.parseString(
                                                        """
                                {"type":"instance_segmentation_and_classification","classChannels":[
                                {"channel":0,"role":"background"},
                                {"channel":1,"role":"emitted","pathClass":"Leydig cell"}]}
                                """)),
                        true);
        var form = new TrainingRunForm(() -> {}, () -> {});
        form.select(source, "cells");
        var check = (CheckBox) form.settingsNode().lookup("#oversampleTrainingFields");
        require(check != null && !check.isSelected(), "Oversampling default must be off");
        check.setSelected(true);
        require(
                Boolean.TRUE.equals(form.draft().settings().oversample()),
                "Sampling choice lost in draft");
        field((Parent) form.settingsNode(), "dataLoadingWorkers").setText("0");
        require(
                Boolean.TRUE.equals(form.draft().settings().oversample()),
                "Editing inline resource settings lost sampling choice");
        var previous = owner.getScene();
        var content = new javafx.scene.layout.VBox(10, form.resultNode(), form.settingsNode());
        var root = new ScrollPane(content);
        root.setFitToWidth(true);
        Scene scene = new Scene(root, 700, 880);
        HistoforgeStyles.apply(scene);
        owner.setScene(scene);
        root.applyCss();
        root.layout();
        capture(content, output.resolve("oversampling-light-700.png"));
        require(
                check.localToScene(check.getBoundsInLocal()).getMaxX() <= scene.getWidth(),
                "Oversampling control exceeds right edge");
        applyQuPathStyle("Modena Dark", root);
        capture(content, output.resolve("oversampling-dark-700.png"));
        applyQuPathStyle("Modena Light", root);
        owner.setScene(previous);
        form.select(unclassified, "cells");
        require(
                form.settingsNode().lookup("#oversampleTrainingFields") == null,
                "Inapplicable sampling control is visible");
        require(form.draft().settings().oversample() == null, "Hidden sampling setting leaked");
        form.select(source, "cells");
        require(
                Boolean.FALSE.equals(form.draft().settings().oversample()),
                "Sampling default was not restored");
    }

    public static void applyQuPathStyle(String name, Parent root) {
        var style =
                QuPathStyleManager.availableStylesProperty().stream()
                        .filter(value -> value.getName().equals(name))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Missing QuPath style " + name));
        style.setStyle();
        root.applyCss();
        root.layout();
    }

    private static TextField field(Parent root, String id) {
        return (TextField) root.lookup("#" + id);
    }

    private static void later(Runnable task) {
        var pause = new PauseTransition(Duration.millis(250));
        pause.setOnFinished(event -> Platform.runLater(task));
        pause.play();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void checkPopulated(ScrollPane root, Path output) {
        root.applyCss();
        root.layout();
        for (String id : new String[] {"trainingAssignmentLabel", "validationAssignmentLabel"}) {
            var label = (Label) root.lookup("#" + id);
            require(
                    !label.getStyleClass().contains(HistoforgeStyles.HEADING),
                    "Partition label is styled as a section heading: " + id);
            require(
                    !label.getFont().getStyle().toLowerCase().contains("bold"),
                    "Partition label is still bold: " + id);
        }
        TableView<?> trainingTable = (TableView<?>) root.lookup("#trainingAssignmentTable");
        TableView<?> validationTable = (TableView<?>) root.lookup("#validationAssignmentTable");
        require(
                trainingTable.getItems().size() + validationTable.getItems().size() == 10,
                "Populated assignment rows are missing");
        require(
                !((Button) root.lookup("#startTrainingButton")).isDisabled(),
                "Valid populated form cannot start training");
        capture((Parent) root.getContent(), output.resolve("populated-light-1100.png"));
        validationTable.scrollTo(validationTable.getItems().size() - 1);
        validationTable.layout();
        capture((Parent) root.getContent(), output.resolve("populated-validation-light-1100.png"));
        trainingTable.scrollTo(0);
        trainingTable.layout();
        checkBounds(root);
        applyQuPathStyle("Modena Dark", root);
        capture((Parent) root.getContent(), output.resolve("populated-dark-1100.png"));
        validationTable.scrollTo(validationTable.getItems().size() - 1);
        validationTable.layout();
        capture((Parent) root.getContent(), output.resolve("populated-validation-dark-1100.png"));
        checkBounds(root);
    }

    private static void showProject(
            TrainingTabController controller,
            ProjectPlan capturedProject,
            TrainingAssignment assignment)
            throws ReflectiveOperationException {
        setField(controller, "preview", TrainingFormFixtures.preview(capturedProject));
        setField(controller, "assignment", assignment);
        setField(controller, "previewStale", false);
        setField(controller, "assignmentOutdated", false);
        var availabilityField = TrainingTabController.class.getDeclaredField("availabilityLabel");
        availabilityField.setAccessible(true);
        Label availability = (Label) availabilityField.get(controller);
        availability.setText("Training annotations loaded.");
        HistoforgeStyles.setSemantic(availability, HistoforgeStyles.SUCCESS);
        var render = TrainingTabController.class.getDeclaredMethod("updatePreviewCounts");
        render.setAccessible(true);
        render.invoke(controller);
    }

    private static void checkTargetReadiness(
            ScrollPane root, TrainingTabController controller, InstalledModule source, Path output)
            throws ReflectiveOperationException {
        var refresh = (Button) root.lookup("#refreshProjectButton");
        var generate = (Button) root.lookup("#generateSplitButton");
        var export = (Button) root.lookup("#exportDatasetButton");
        var start = (Button) root.lookup("#startTrainingButton");
        var guidance = (Label) root.lookup("#splitGuidanceLabel");
        var availability = (Label) root.lookup("#availabilityLabel");
        ((javafx.scene.control.TitledPane) root.lookup("#trainingEditModelClasses"))
                .setExpanded(true);
        root.applyCss();
        root.layout();
        var name = field(root, "trainingClassName0");
        String original = name.getText();
        String batch = field(root, "trainingBatchSize").getText();
        field(root, "trainingBatchSize").setText("bad");
        require(
                start.isDisabled() && !refresh.isDisabled() && !generate.isDisabled(),
                "Run-setting validation incorrectly invalidated the capture");
        field(root, "trainingBatchSize").setText(batch);
        require(!start.isDisabled(), "Corrected run settings did not restore Start");

        name.clear();
        require(
                !refresh.isDisabled()
                        && generate.isDisabled()
                        && export.isDisabled()
                        && start.isDisabled(),
                "An invalid target must still allow reading annotations and block split, export and training");
        require(
                guidance.isVisible() && guidance.getText().contains("Enter a class name."),
                "Split guidance does not explain the class correction");
        require(
                !guidance.getText().contains("Refresh the training project"),
                "Invalid target is told to use disabled Refresh");
        require(
                !availability.getText().contains("Enter a class name."),
                "Invalid class edits repeat the error in the source section");
        require(
                ((Label) root.lookup("#assignmentStateLabel")).getText().equals("Outdated"),
                "Outdated badge prescribes regeneration before fixing the target");
        require(
                root.lookup("#trainingClassError0").isVisible(),
                "Name error is not beside the field");
        require(
                !root.lookup("#trainingFormError").isVisible(),
                "Class error remains at form bottom");
        root.setVvalue(0);
        TrainingFormSmoke.capture(
                (Parent) root.getContent(), output.resolve("invalid-target-guidance.png"));

        name.setText(original);
        require(
                !refresh.isDisabled()
                        && !generate.isDisabled()
                        && !export.isDisabled()
                        && !start.isDisabled(),
                "Corrected class did not reuse the annotation counts");
        require(!root.lookup("#trainingClassError0").isVisible(), "Corrected name error is stale");
        require(guidance.getText().isEmpty(), "Corrected form still asks for another project read");
        require(
                !availability.getText().contains("Refresh"),
                "Class edit repeats the Refresh instruction in the source section");

        var resolution = field(root, "trainingResolution");
        String mpp = resolution.getText();
        resolution.clear();
        require(
                !refresh.isDisabled() && guidance.getText().contains("positive resolution"),
                "Invalid resolution does not allow Refresh and explain the split error");
        resolution.setText(mpp);
        require(
                !refresh.isDisabled() && !start.isDisabled(),
                "MPP correction asked for another read");

        var populated = TrainingFormFixtures.populatedProject(source, "cells");
        showProject(controller, populated.plan(), populated.assignment());
        require(!start.isDisabled(), "Current captured fixture did not restore training readiness");
        controller.invalidateProjectCapture();
        require(
                availability.getText().startsWith("Training project changed."),
                "Actual project edit lost project-specific guidance");
        require(
                !refresh.isDisabled() && generate.isDisabled() && start.isDisabled(),
                "Project edit did not preserve the capture fence");
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static void capture(Parent content, Path target) {
        content.applyCss();
        content.layout();
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(snapshotBackground(content));
        try {
            ImageIO.write(
                    SwingFXUtils.fromFXImage(content.snapshot(parameters, null), null),
                    "png",
                    target.toFile());
        } catch (java.io.IOException error) {
            throw new AssertionError("Could not write screenshot", error);
        }
    }

    /** A cropped node omits ancestor backgrounds, including the ScrollPane viewport. */
    private static Paint snapshotBackground(Parent content) {
        for (Node ancestor = content.getParent();
                ancestor != null;
                ancestor = ancestor.getParent()) {
            if (ancestor instanceof Region region && region.getBackground() != null) {
                for (var fill : region.getBackground().getFills().reversed()) {
                    if (fill.getFill() instanceof Color color && color.getOpacity() == 1) {
                        return color;
                    }
                }
            }
        }
        return content.getScene() == null ? Color.TRANSPARENT : content.getScene().getFill();
    }

    private static void checkBounds(ScrollPane root) {
        for (String id :
                new String[] {
                    "trainingResultName",
                    "trainingSourceArchitecture",
                    "trainingSourceArchitectureInfo",
                    "trainingSourceTask",
                    "trainingKeepClassesHelp",
                    "minimumDetectedObjectArea",
                    "trainingGpu",
                    "trainingBatchSize",
                    "trainingEpochs",
                    "balanceFlowLoss",
                    "augmentationDefaults",
                    "dataLoadingWorkers",
                    "gradientAccumulation",
                    "warmupMode",
                    "warmupEpochs",
                    "generateSplitButton",
                    "startTrainingButton"
                }) {
            var node = root.lookup("#" + id);
            var bounds = node.localToScene(node.getBoundsInLocal());
            require(
                    bounds.getMaxX() <= root.getScene().getWidth(),
                    id + " exceeds right edge: " + bounds);
            if (node instanceof InformationButton info) {
                require(
                        info.getWidth() >= info.prefWidth(-1),
                        id + " shrank below its readable width");
            }
        }
    }
}
