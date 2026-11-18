package qupath.ext.histoforge.ui.modules;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
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
import qupath.ext.histoforge.client.dto.InstallRequest;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.util.Errors;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Modal dialog to install a module from Hugging Face. Installation is the explicit user-owned
 * boundary for any Groovy code in the module, so the request is submitted only after one warning.
 */
public final class InstallDialog {

    private static final Logger logger = LoggerFactory.getLogger(InstallDialog.class);

    private final ModuleGatewayClient client;
    private final Runnable onSubmissionFinished;
    private final Stage stage = new Stage();
    private final TextField refField = new TextField();
    private final Label hint = new Label();
    private final Button cancelButton = new Button("Cancel");
    private final Button installButton = new Button("Install");
    private boolean submitting;

    /** Open the install dialog, owned by the QuPath window. */
    public static void show(
            QuPathGUI qupath, ModuleGatewayClient client, Runnable onSubmissionFinished) {
        show(qupath, client, null, onSubmissionFinished);
    }

    /**
     * Open the install dialog with the ref field pre-filled — e.g. from a Project-tab
     * missing-module cell. The field stays editable and is validated live like any typed input.
     */
    public static void show(
            QuPathGUI qupath,
            ModuleGatewayClient client,
            String prefill,
            Runnable onSubmissionFinished) {
        new InstallDialog(qupath, client, prefill, onSubmissionFinished);
    }

    private InstallDialog(
            QuPathGUI qupath,
            ModuleGatewayClient client,
            String prefill,
            Runnable onSubmissionFinished) {
        this.client = client;
        this.onSubmissionFinished = Objects.requireNonNull(onSubmissionFinished);
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Install module");
        stage.setOnCloseRequest(
                event -> {
                    if (submitting) event.consume();
                });
        if (prefill != null && !prefill.isBlank()) {
            refField.setText(prefill.strip());
        }
        Scene scene = new Scene(buildContent(), 480, 180);
        HistoforgeStyles.apply(scene);
        stage.setScene(scene);
        stage.show();
    }

    private Parent buildContent() {
        Label prompt = new Label("Paste a Hugging Face module ref or URL:");

        refField.setPromptText("histoforge/module-name   or   https://huggingface.co/owner/name");
        refField.textProperty().addListener((obs, old, text) -> validate(text));

        hint.setWrapText(true);
        hint.setMinHeight(32);

        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> stage.close());
        installButton.setDefaultButton(true);
        installButton.setOnAction(e -> submit());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancelButton, installButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, prompt, refField, hint, buttons);
        root.setPadding(new Insets(15));

        validate(refField.getText());
        return root;
    }

    /** Enable Install only when the field parses to a canonical Hugging Face source. */
    private void validate(String text) {
        if (submitting) {
            installButton.setDisable(true);
            return;
        }
        if (text == null || text.isBlank()) {
            setHint("", HistoforgeStyles.NEUTRAL);
            installButton.setDisable(true);
            return;
        }
        Optional<ModuleRef.HuggingFaceSource> source = ModuleRef.parseHuggingFaceSource(text);
        if (source.isEmpty()) {
            setHint(
                    "Not a valid HF ref (expected owner/name, owner/name@sha, or a huggingface.co"
                            + " URL).",
                    HistoforgeStyles.ERROR);
            installButton.setDisable(true);
        } else {
            setHint("Will install " + source.get() + ".", HistoforgeStyles.NEUTRAL);
            installButton.setDisable(false);
        }
    }

    private void setHint(String text, String semanticStyle) {
        hint.setText(text);
        HistoforgeStyles.setSemantic(hint, semanticStyle);
    }

    private void submit() {
        if (submitting) return;
        ModuleRef.HuggingFaceSource source =
                ModuleRef.parseHuggingFaceSource(refField.getText()).orElseThrow();
        Optional<InstallRequest> authorized = authorizedRequest(source, this::confirmTrust);
        if (authorized.isEmpty()) {
            return;
        }
        InstallRequest req = authorized.orElseThrow();
        setSubmitting(true);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                client.submitInstall(req);
                                Platform.runLater(() -> onSubmitted(source.moduleRef().repo()));
                            } catch (Exception e) {
                                logger.warn("submitInstall {} failed", source, e);
                                Platform.runLater(() -> onFailed(e));
                            }
                        });
    }

    private boolean confirmTrust(ModuleRef.HuggingFaceSource source) {
        ButtonType install = new ButtonType("Install", ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Install module");
        dialog.setHeaderText("Trust this module?");
        dialog.getDialogPane().getButtonTypes().addAll(install, ButtonType.CANCEL);
        Label warning = new Label(trustWarning(source));
        warning.setWrapText(true);
        warning.setMaxWidth(480);
        dialog.getDialogPane().setContent(warning);
        return dialog.showAndWait().filter(install::equals).isPresent();
    }

    static Optional<InstallRequest> authorizedRequest(
            ModuleRef.HuggingFaceSource source, Predicate<ModuleRef.HuggingFaceSource> authorizer) {
        if (!authorizer.test(source)) {
            return Optional.empty();
        }
        return Optional.of(
                new InstallRequest(source.moduleRef().repo(), source.revision().orElse(null)));
    }

    static String trustWarning(ModuleRef.HuggingFaceSource source) {
        return source
                + "\n\nModules may include code for processing results and calculating measurements."
                + " This code runs in QuPath and can access your files, project data and the"
                + " internet. Choose a module from a source you trust.";
    }

    private void onSubmitted(String repo) {
        setSubmitting(false);
        Dialogs.showInfoNotification("Install", "Installing " + repo + "…");
        stage.close();
        onSubmissionFinished.run();
    }

    private void onFailed(Exception e) {
        setSubmitting(false);
        // The backend may have admitted a job even when its acceptance response was lost.
        onSubmissionFinished.run();
        Dialogs.showErrorMessage("Install failed", Errors.message(e));
    }

    private void setSubmitting(boolean value) {
        submitting = value;
        refField.setDisable(value);
        cancelButton.setDisable(value);
        validate(refField.getText());
    }
}
