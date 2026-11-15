package qupath.ext.histoforge.ui.modules;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.client.dto.HuggingFaceVisibility;
import qupath.ext.histoforge.client.dto.PublishRequest;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingReport;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.util.Errors;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Modal dialog to publish a module to Hugging Face (the uniform vendor-transform, any module). Pick
 * a target repo and new-repository visibility, then submit. It renders the {@link PublishCard} and
 * fires {@link ModuleGatewayClient#submitPublish} off the FX thread; the gateway deployment owns
 * the registry credential.
 */
final class PublishDialog {

    private static final Logger logger = LoggerFactory.getLogger(PublishDialog.class);

    private final ModuleGatewayClient client;
    private final InstalledModule module;
    private final Runnable onSubmissionFinished;
    private final boolean localSyncOffered;
    private final Stage stage = new Stage();
    private final TextField targetField = new TextField();
    private final ChoiceBox<String> visibility = new ChoiceBox<>();
    private final CheckBox syncLocal = new CheckBox("Also install the published copy locally");
    private final Label hint = new Label();
    private final Button cancelButton = new Button("Cancel");
    private final Button publishButton = new Button("Publish");
    private boolean submitting;

    static void show(
            QuPathGUI qupath,
            ModuleGatewayClient client,
            InstalledModule module,
            Runnable onSubmissionFinished) {
        new PublishDialog(qupath, client, module, onSubmissionFinished);
    }

    private PublishDialog(
            QuPathGUI qupath,
            ModuleGatewayClient client,
            InstalledModule module,
            Runnable onSubmissionFinished) {
        this.client = client;
        this.module = module;
        this.onSubmissionFinished = Objects.requireNonNull(onSubmissionFinished);
        this.localSyncOffered = offersLocalSync(module.ref());
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Publish module");
        stage.setOnCloseRequest(
                event -> {
                    if (submitting) event.consume();
                });
        Scene scene = new Scene(buildContent(), 480, 260);
        HistoforgeStyles.apply(scene);
        stage.setScene(scene);
        stage.show();
    }

    private Parent buildContent() {
        Label source = new Label(module.ref());
        source.getStyleClass().add(HistoforgeStyles.MUTED);

        targetField.setPromptText("owner/name");
        targetField.textProperty().addListener((obs, old, text) -> validate());

        visibility.getItems().addAll("Private", "Public");
        visibility.setValue("Private");

        hint.setWrapText(true);
        hint.setMinHeight(28);

        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> stage.close());
        publishButton.setDefaultButton(true);
        publishButton.setOnAction(e -> submit());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancelButton, publishButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root =
                new VBox(
                        10,
                        labeled("Source module", source),
                        labeled("Target repo", targetField),
                        labeled("New repo visibility", visibility));
        if (localSyncOffered) {
            syncLocal.setTooltip(
                    new Tooltip(
                            "The published copy will appear as a separate module in the Modules"
                                    + " tab."));
            root.getChildren().add(syncLocal);
        }
        root.getChildren().addAll(hint, buttons);
        root.setPadding(new Insets(15));

        validate();
        return root;
    }

    /** Enable Publish only when the target parses to canonical {@code owner/name}. */
    private void validate() {
        String target = targetField.getText();
        if (target == null || target.isBlank()) {
            setHint("Enter a target repo as owner/name.", HistoforgeStyles.NEUTRAL);
        } else if (ModuleRef.parse(target.strip()).isEmpty()) {
            setHint("Target must be owner/name.", HistoforgeStyles.ERROR);
        } else {
            setHint("", HistoforgeStyles.NEUTRAL);
        }
        publishButton.setDisable(publishDisabled(submitting, target));
    }

    private void submit() {
        if (submitting) {
            return;
        }
        ModuleRef target = ModuleRef.parse(targetField.getText().strip()).orElseThrow();
        HuggingFaceVisibility requestedVisibility =
                HuggingFaceVisibility.valueOf(visibility.getValue().toUpperCase(Locale.ROOT));
        boolean requestedSync = localSyncOffered && syncLocal.isSelected();
        setSubmitting(true);
        Thread.ofVirtual().start(() -> runPublish(target, requestedVisibility, requestedSync));
    }

    private void runPublish(
            ModuleRef target, HuggingFaceVisibility requestedVisibility, boolean requestedSync) {
        try {
            Optional<TrainingReport> report =
                    module.recipe().trainingReportDigest() == null
                            ? Optional.empty()
                            : Optional.of(client.moduleTrainingReport(module).report());
            PublishRequest request =
                    new PublishRequest(
                            module.ref(),
                            target.repo(),
                            requestedVisibility,
                            PublishCard.render(module, target.repo(), report),
                            requestedSync);
            client.submitPublish(request);
            Platform.runLater(() -> onSubmitted(target.repo()));
        } catch (Exception e) {
            logger.warn("submitPublish {} failed", target.repo(), e);
            Platform.runLater(() -> onFailed(e));
        }
    }

    private void onSubmitted(String targetRepo) {
        setSubmitting(false);
        Dialogs.showInfoNotification("Publish", "Publishing to " + targetRepo + "…");
        stage.close();
        onSubmissionFinished.run();
    }

    private void onFailed(Exception e) {
        setSubmitting(false);
        // A failed response does not prove that admission or dispatch failed.
        onSubmissionFinished.run();
        Dialogs.showErrorMessage("Publish failed", Errors.message(e));
    }

    private void setSubmitting(boolean value) {
        submitting = value;
        targetField.setDisable(value);
        visibility.setDisable(value);
        syncLocal.setDisable(value);
        cancelButton.setDisable(value);
        validate();
    }

    private void setHint(String text, String semanticStyle) {
        hint.setText(text);
        HistoforgeStyles.setSemantic(hint, semanticStyle);
    }

    static boolean offersLocalSync(String moduleRef) {
        return ModuleRef.parse(moduleRef)
                .map(ref -> ModuleContract.workspaceTrainingRunId(ref).isEmpty())
                .orElse(false);
    }

    static boolean publishDisabled(boolean submitting, String target) {
        return submitting || target == null || ModuleRef.parse(target.strip()).isEmpty();
    }

    /** A "Name [control]" row; the control grows to fill the width. */
    private static HBox labeled(String name, Node control) {
        Label label = new Label(name);
        label.setMinWidth(130);
        HBox row = new HBox(8, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(control, Priority.ALWAYS);
        return row;
    }
}
