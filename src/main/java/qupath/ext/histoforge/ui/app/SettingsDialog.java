package qupath.ext.histoforge.ui.app;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.cli.CliException;
import qupath.ext.histoforge.cli.HistoforgeCli;
import qupath.ext.histoforge.cli.UvInstaller;
import qupath.ext.histoforge.client.BackendCompatibility;
import qupath.ext.histoforge.client.ConnectionProbe;
import qupath.ext.histoforge.client.Deployment;
import qupath.ext.histoforge.client.DeploymentStore;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.modules.InferencePrecision;
import qupath.ext.histoforge.platform.ExtensionRelease;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;
import qupath.ext.histoforge.ui.HistoforgePrefs;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.HistoforgeTooltip;
import qupath.ext.histoforge.ui.InformationButton;

/**
 * The gear ⚙ dialog: shows the authenticated connection (Modal profile · gateway URL ·
 * compatibility) and exposes the recovery actions, each driving the extension-owned CLI off the FX
 * thread. Registry credentials are entry-only: stdin to the helper, persistent storage in Modal.
 * Programmatic (no FXML), modelled on {@code cloud.CloudSlideDialog}.
 */
final class SettingsDialog {

    private static final Logger logger = LoggerFactory.getLogger(SettingsDialog.class);
    private static final String INFERENCE_GPU_HELP =
            "Infer from recipe uses each model's default GPU. Choosing a GPU overrides that default "
                    + "for new Project actions. Running or unfinished actions keep the GPU chosen when they started.";
    static final String INFERENCE_PRECISION_HELP =
            "Default uses the checkpoint precision supported by each model's architecture. "
                    + "BF16 (if applicable) uses BF16 when the model and GPU support it; otherwise it uses Default. "
                    + "BF16 can change numerical results. New Fill/Redo actions only; saved results, unfinished actions and training are unchanged.";

    private final Stage stage;
    private final Stage owner;
    private final Supplier<ModuleGatewayClient> sharedClient;
    private final Consumer<ConnectionProbe.Result> onConnectionChecked;
    private final Function<String, CompletableFuture<Void>> stopLocalWork;
    private final Runnable finishCloudUpdate;
    private final DeploymentStore store = new DeploymentStore();

    private final TextField profileField = new TextField();
    private final TextField gatewayField = new TextField();
    private final TextField environmentField = new TextField();
    private final Label connectionLabel = new Label();
    private final Label backendVersionLabel = new Label("Not checked");
    private final Label compatibilityMessage = new Label();
    private final Label actionStatus = new Label();
    private final CloudSetupProgress progress;
    private final HBox actionRow = new HBox(6);
    private final Button recheckButton = new Button("Recheck");
    private final Button locateBackendButton = new Button("Locate saved backend…");
    private Button cloudUpdateButton;
    private BackendCompatibility.Decision compatibility;
    private final ComboBox<String> inferenceGpuComboBox = new ComboBox<>();
    private final InformationButton inferenceGpuInfo = new InformationButton(INFERENCE_GPU_HELP);
    private final Label inferenceGpuAvailability = new Label();
    private final HuggingFaceTokenControls hfToken = new HuggingFaceTokenControls(this::saveToken);

    private long connectionGeneration;
    private boolean connectionChecking;
    private boolean actionRunning;
    private boolean cloudUpdateAction;
    private boolean deploymentConfigured;
    private boolean inferenceGpuChoicesLoaded;
    private boolean locationRepairAvailable;

    SettingsDialog(
            Stage owner,
            Supplier<ModuleGatewayClient> sharedClient,
            Consumer<ConnectionProbe.Result> onConnectionChecked,
            Function<String, CompletableFuture<Void>> stopLocalWork,
            Runnable finishCloudUpdate) {
        this.sharedClient = sharedClient;
        this.onConnectionChecked = onConnectionChecked;
        this.stopLocalWork = stopLocalWork;
        this.finishCloudUpdate = finishCloudUpdate;
        this.owner = owner;
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("HistoForge settings");
        progress = new CloudSetupProgress(actionStatus);
        connectionLabel.setId("cloudConnectionStatus");
        connectionLabel.getStyleClass().add(HistoforgeStyles.CONNECTION_INDICATOR);
        backendVersionLabel.setId("cloudBackendVersion");
        compatibilityMessage.setId("cloudCompatibilityMessage");
        compatibilityMessage.setWrapText(true);

        actionStatus.setWrapText(true);
        actionStatus.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        VBox content = new VBox(12, cloudSection(), new Separator(), localSection());
        content.setPadding(new Insets(15));
        VBox.setVgrow(progress.getNode(), Priority.ALWAYS);
        ScrollPane root = new ScrollPane(content);
        root.setId("settingsScroll");
        root.setFitToWidth(true);
        root.setFitToHeight(true);
        root.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.setPrefWidth(620);
        Scene scene = new Scene(root);
        HistoforgeStyles.apply(scene);
        stage.setScene(scene);
        stage.setOnHidden(event -> hfToken.clear());
        loadStoredDeployment();
    }

    /** Show or focus the single settings window owned by the HistoForge controller. */
    void show() {
        if (!stage.isShowing()) {
            fitToScreen();
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
        if (!actionRunning && !connectionChecking) {
            refreshConnection();
        }
    }

    /** Keep the window on its screen without replacing a size the user already chose. */
    private void fitToScreen() {
        if (stage.getScene() == null) return;
        boolean positioned = Double.isFinite(stage.getX()) && Double.isFinite(stage.getY());
        Stage location = positioned ? stage : owner;
        Screen screen =
                Screen.getScreensForRectangle(
                                location.getX() + location.getWidth() / 2,
                                location.getY() + location.getHeight() / 2,
                                1,
                                1)
                        .stream()
                        .findFirst()
                        .orElse(Screen.getPrimary());
        stage.setMaxHeight(screen.getVisualBounds().getHeight());
        if (!Double.isFinite(stage.getWidth())) stage.sizeToScene();
    }

    private VBox localSection() {
        ComboBox<LocalResourceEnvelope> resourceEnvelope = new ComboBox<>();
        resourceEnvelope.setId("localResourceEnvelope");
        resourceEnvelope.getItems().setAll(LocalResourceEnvelope.values());
        resourceEnvelope.setMaxWidth(Double.MAX_VALUE);
        resourceEnvelope.setConverter(
                new javafx.util.StringConverter<>() {
                    @Override
                    public String toString(LocalResourceEnvelope value) {
                        return value == null ? "" : value.displayName();
                    }

                    @Override
                    public LocalResourceEnvelope fromString(String value) {
                        throw new UnsupportedOperationException();
                    }
                });
        resourceEnvelope
                .valueProperty()
                .bindBidirectional(HistoforgePrefs.localResourceEnvelopeProperty());

        String capacityHelpText = capacityHelp(Runtime.getRuntime().maxMemory());
        InformationButton capacityInfo = new InformationButton(capacityHelpText);
        HistoforgeTooltip capacityTooltip = new HistoforgeTooltip(capacityHelpText);
        capacityTooltip.setWrapText(true);
        capacityTooltip.setMaxWidth(420);
        capacityTooltip.setShowDelay(Duration.millis(500));
        HBox capacityLabel = new HBox(4, new Label("Results per image"), capacityInfo);
        capacityLabel.setAlignment(Pos.CENTER_LEFT);
        capacityLabel.setMinWidth(140);
        resourceEnvelope.setTooltip(capacityTooltip);
        HBox capacityRow = new HBox(6, capacityLabel, resourceEnvelope);
        capacityRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(resourceEnvelope, Priority.ALWAYS);

        CheckBox removeDuplicates = new CheckBox("Remove overlapping same-class model duplicates");
        removeDuplicates.setId("removeOverlappingDuplicates");
        removeDuplicates
                .selectedProperty()
                .bindBidirectional(HistoforgePrefs.removeOverlappingSameClassDuplicatesProperty());
        String duplicateHelp =
                "Skip new model objects that overlap same-class manual objects. "
                        + "Existing objects are never changed by this filter.";
        removeDuplicates.setTooltip(tooltip(duplicateHelp));
        var duplicatesRow = new HBox(6, removeDuplicates, new InformationButton(duplicateHelp));
        duplicatesRow.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, heading("Local"), capacityRow, duplicatesRow);
    }

    // ── Cloud section ───────────────────────────────────────────────

    private VBox cloudSection() {
        Label heading = heading("Cloud");

        inferenceGpuComboBox.setId("defaultInferenceGpu");
        inferenceGpuComboBox.setMaxWidth(Double.MAX_VALUE);
        inferenceGpuComboBox.setConverter(
                new javafx.util.StringConverter<>() {
                    @Override
                    public String toString(String value) {
                        return value == null || value.isBlank() ? "Infer from recipe" : value;
                    }

                    @Override
                    public String fromString(String value) {
                        throw new UnsupportedOperationException();
                    }
                });
        String savedGpu = HistoforgePrefs.defaultInferenceGpuProperty().get();
        inferenceGpuComboBox
                .getItems()
                .setAll(
                        savedGpu == null || savedGpu.isBlank()
                                ? java.util.List.of(HistoforgePrefs.USE_RECIPE_INFERENCE_GPU)
                                : java.util.List.of(
                                        HistoforgePrefs.USE_RECIPE_INFERENCE_GPU, savedGpu));
        inferenceGpuComboBox
                .valueProperty()
                .bindBidirectional(HistoforgePrefs.defaultInferenceGpuProperty());
        inferenceGpuComboBox.setTooltip(tooltip(INFERENCE_GPU_HELP));

        ComboBox<InferencePrecision> precision = new ComboBox<>();
        precision.setId("inferencePrecision");
        precision.getItems().setAll(InferencePrecision.values());
        precision.setMaxWidth(Double.MAX_VALUE);
        precision.setConverter(
                new javafx.util.StringConverter<>() {
                    @Override
                    public String toString(InferencePrecision value) {
                        return value == null ? "" : value.displayName();
                    }

                    @Override
                    public InferencePrecision fromString(String value) {
                        throw new UnsupportedOperationException();
                    }
                });
        precision.valueProperty().bindBidirectional(HistoforgePrefs.inferencePrecisionProperty());
        precision.setTooltip(tooltip(INFERENCE_PRECISION_HELP));

        // v1 has one active Modal profile/origin. Switching requires rebuilding
        // every shared client/registry/poller owner and is deliberately not exposed.
        profileField.setEditable(false);
        profileField.setId("modalProfile");
        profileField.setTooltip(tooltip("Modal account profile used for this cloud deployment."));
        gatewayField.setEditable(false);
        gatewayField.setId("gatewayUrl");
        gatewayField.setTooltip(tooltip("Address of your HistoForge cloud backend."));
        environmentField.setId("modalEnvironment");
        environmentField.setEditable(false);
        environmentField.setTooltip(tooltip("Modal environment where this backend was set up."));
        locateBackendButton.setId("locateSavedBackend");
        locateBackendButton.setTooltip(
                tooltip("Reconnect to this backend using its original Modal environment."));
        locateBackendButton.setOnAction(event -> locateSavedBackend());

        recheckButton.setOnAction(e -> refreshConnection());
        recheckButton.setTooltip(tooltip("Check the connection and available inference GPUs."));

        cloudUpdateButton = new Button("Update cloud backend");
        cloudUpdateButton.setTooltip(
                tooltip(
                        "Install backend "
                                + ExtensionRelease.current().bundledBackendVersion()
                                + " bundled with this extension. Stops unfinished work; saved results are kept."));
        cloudUpdateButton.setOnAction(event -> confirmCloudUpdate());
        cloudUpdateButton.setId("updateCloudBackend");
        Button reconnect = new Button("Reconnect");
        reconnect.setTooltip(tooltip("Restore this computer's access to the saved cloud backend."));
        reconnect.setOnAction(
                event -> {
                    var target = store.load();
                    if (target.isEmpty()) {
                        refreshConnection();
                        return;
                    }
                    runAction("Reconnect", onLine -> cli().reconnect(target.orElseThrow(), onLine));
                });
        actionRow.getChildren().addAll(reconnect, cloudUpdateButton, locateBackendButton);
        inferenceGpuAvailability.setId("inferenceGpuAvailability");
        inferenceGpuAvailability.setWrapText(true);
        inferenceGpuAvailability.getStyleClass().add(HistoforgeStyles.MUTED);
        VBox cloud =
                new VBox(
                        8,
                        heading,
                        labeled("Modal profile", profileField),
                        labeled("Modal environment", environmentField),
                        labeled("Gateway", gatewayField),
                        labeled(
                                "Extension version",
                                new Label(ExtensionRelease.current().extensionVersion())),
                        labeled("Cloud backend version", backendVersionLabel),
                        labeled(
                                "Bundled backend",
                                new Label(ExtensionRelease.current().bundledBackendVersion())),
                        labeled("Status", connectionLabel, recheckButton),
                        compatibilityMessage,
                        labeled("Default inference GPU", inferenceGpuComboBox, inferenceGpuInfo),
                        inferenceGpuAvailability,
                        labeled(
                                "Inference precision",
                                precision,
                                new InformationButton(INFERENCE_PRECISION_HELP)),
                        labeled("Hugging Face token", hfToken),
                        actionRow,
                        actionStatus,
                        progress.getNode());
        cloud.setId("cloudSettingsSection");
        cloud.maxHeightProperty().bind(progress.getNode().maxHeightProperty());
        VBox.setVgrow(cloud, Priority.ALWAYS);
        return cloud;
    }

    // ── Actions ─────────────────────────────────────────────────────

    private void confirmCloudUpdate() {
        if (actionRunning || connectionChecking) return;
        ButtonType update = new ButtonType("Stop work and update", ButtonBar.ButtonData.OK_DONE);
        Alert dialog =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        cloudUpdateConfirmation(),
                        update,
                        ButtonType.CANCEL);
        dialog.initOwner(stage);
        dialog.setTitle("Update cloud backend");
        dialog.setHeaderText("Stop unfinished work and update?");
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != update) return;
        var target = store.load();
        if (target.isEmpty()) {
            refreshConnection();
            return;
        }
        blockSubmissionsForUpdate();
        runAction(
                "Update cloud backend",
                onLine -> updateCloudBackend(target.orElseThrow(), cli(), onLine));
    }

    /**
     * Check the captured backend before stopping local work; the helper rechecks before updating.
     */
    private void updateCloudBackend(
            Deployment target, HistoforgeCli helper, Consumer<String> onLine) throws CliException {
        helper.verifyRuntime();
        onLine.accept("Checking the saved backend before updating…");
        Deployment verifiedTarget = helper.checkUpgrade(target, onLine);
        try {
            onLine.accept("Stopping local analysis and training preparation…");
            stopLocalWork.apply(verifiedTarget.deploymentId()).join();
            helper.upgrade(verifiedTarget, onLine);
        } catch (CompletionException failure) {
            throw new CliException(
                    CliException.Type.CLI_ERROR,
                    "Could not stop local work. The cloud update was not started: "
                            + qupath.ext.histoforge.util.Errors.message(failure.getCause()));
        }
    }

    static String cloudUpdateConfirmation() {
        return "Unfinished analysis and training will be cancelled and must be started again. "
                + "HistoForge will wait for any result save or module installation/publication already underway.\n\n"
                + "Saved results, datasets, installed modules and annotations are kept.\n\n"
                + "Do not submit work or reconnect from another computer using this same Modal workspace until the update finishes.";
    }

    private void blockSubmissionsForUpdate() {
        cloudUpdateAction = true;
        owner.getScene().getRoot().setDisable(true);
    }

    private void finishAction() {
        actionRunning = false;
        if (cloudUpdateAction) {
            cloudUpdateAction = false;
            finishCloudUpdate.run();
            owner.getScene().getRoot().setDisable(false);
        }
    }

    private void saveToken(String token) {
        var target = store.load();
        if (target.isEmpty()) {
            refreshConnection();
            setStatus(
                    "Connect to a cloud backend before saving a token.", HistoforgeStyles.WARNING);
            return;
        }
        runAction(
                "Save token",
                ignored ->
                        cli().saveHuggingFaceToken(
                                        null,
                                        target.orElseThrow(),
                                        token,
                                        line -> Platform.runLater(() -> reflectProgress(line))));
        if (actionRunning) {
            setStatus(
                    "Verifying and saving token… Finish Modal login in your browser if asked.",
                    HistoforgeStyles.BUSY);
        }
    }

    /** Repair only missing v1 location metadata, after proving the original backend identity. */
    private void locateSavedBackend() {
        var current = store.load();
        if (current.isEmpty() || current.orElseThrow().modalEnvironment() != null) return;
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(stage);
        dialog.setTitle("Locate saved backend");
        dialog.setHeaderText(
                "Reconnecting refreshes access to this backend.\n"
                        + "Other computers will need to reconnect.");
        dialog.setContentText("Environment used during setup:");
        dialog.getEditor().setPromptText("Shown in your Modal dashboard");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Reconnect");
        dialog.showAndWait()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .ifPresent(
                        environment -> {
                            Deployment target = current.orElseThrow().withEnvironment(environment);
                            runAction("Reconnect", onLine -> cli().reconnect(target, onLine));
                        });
    }

    private void runAction(String verb, CliCall call) {
        if (actionRunning || connectionChecking) {
            return;
        }
        beginAction();
        setStatus(verb + "…", HistoforgeStyles.BUSY);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                call.run(line -> Platform.runLater(() -> reflectProgress(line)));
                                Platform.runLater(() -> onActionDone(verb));
                            } catch (CliException ex) {
                                Platform.runLater(() -> onActionFailed(verb, call, ex));
                            } catch (RuntimeException ex) {
                                Platform.runLater(
                                        () ->
                                                reportActionFailure(
                                                        verb,
                                                        new CliException(
                                                                CliException.Type.CLI_ERROR,
                                                                qupath.ext.histoforge.util.Errors
                                                                        .message(ex))));
                            }
                        });
    }

    private void onActionDone(String verb) {
        finishAction();
        progress.hideLoginLink();
        setStatus(
                "Save token".equals(verb)
                        ? "Token verified and saved to Modal."
                        : verb + " completed",
                HistoforgeStyles.SUCCESS);
        refreshConnection();
    }

    private void onActionFailed(String verb, CliCall call, CliException ex) {
        if (ex.type() == CliException.Type.NOT_INSTALLED) {
            boolean install = RuntimeHelperInstallDialog.confirm(stage);
            if (install) {
                installHelperAndRetry(verb, call);
            } else {
                finishAction();
                updateActionControls();
                setStatus(
                        "The runtime helper is required for deployment management.",
                        HistoforgeStyles.ERROR);
            }
            return;
        }
        reportActionFailure(verb, ex);
    }

    private void installHelperAndRetry(String verb, CliCall call) {
        beginAction();
        setStatus("Installing runtime helper…", HistoforgeStyles.BUSY);
        Thread.ofVirtual()
                .start(
                        () -> {
                            Consumer<String> progress =
                                    line -> Platform.runLater(() -> reflectProgress(line));
                            try {
                                UvInstaller installer = new UvInstaller();
                                if (!installer.isInstalled()) {
                                    installer.bootstrapUv(progress);
                                }
                                installer.installHistoforge(progress);
                            } catch (CliException failure) {
                                Platform.runLater(() -> onHelperInstallFailed(failure));
                                return;
                            } catch (RuntimeException failure) {
                                Platform.runLater(
                                        () ->
                                                onHelperInstallFailed(
                                                        new CliException(
                                                                CliException.Type.CLI_ERROR,
                                                                qupath.ext.histoforge.util.Errors
                                                                        .message(failure))));
                                return;
                            }
                            try {
                                call.run(progress);
                                Platform.runLater(() -> onActionDone(verb));
                            } catch (CliException failure) {
                                Platform.runLater(() -> reportActionFailure(verb, failure));
                            } catch (RuntimeException failure) {
                                Platform.runLater(
                                        () ->
                                                reportActionFailure(
                                                        verb,
                                                        new CliException(
                                                                CliException.Type.CLI_ERROR,
                                                                qupath.ext.histoforge.util.Errors
                                                                        .message(failure))));
                            }
                        });
    }

    private void onHelperInstallFailed(CliException failure) {
        finishAction();
        updateActionControls();
        logger.warn("runtime helper installation failed", failure);
        setStatus(
                "Runtime helper installation failed: " + failure.getMessage(),
                HistoforgeStyles.ERROR);
    }

    private void reportActionFailure(String verb, CliException failure) {
        finishAction();
        locationRepairAvailable =
                failure.type() == CliException.Type.GATEWAY_TARGET_MISMATCH
                        && store.load()
                                .filter(target -> target.modalEnvironment() == null)
                                .isPresent();
        updateActionControls();
        logger.warn("{} failed", verb, failure);
        progress.appendLine(verb + " failed: " + failure.getMessage());
        setStatus(actionFailureMessage(verb, failure), HistoforgeStyles.ERROR);
        // Deployment can rotate access before a later failure. Never retain an old green status.
        refreshConnection();
    }

    private void reflectProgress(String line) {
        progress.appendLine(line);
    }

    private void beginAction() {
        progress.reset();
        connectionGeneration++;
        connectionChecking = false;
        actionRunning = true;
        locationRepairAvailable = false;
        updateActionControls();
    }

    // ── Connection status ───────────────────────────────────────────

    private void refreshConnection() {
        if (actionRunning) {
            return;
        }
        long generation = ++connectionGeneration;
        connectionChecking = true;
        compatibility = null;
        inferenceGpuChoicesLoaded = false;
        updateActionControls();
        setConnection("Checking…", HistoforgeStyles.NEUTRAL);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                var deployment = store.reload();
                                if (deployment.isEmpty()) {
                                    Platform.runLater(() -> applyNotConfigured(generation));
                                    return;
                                }
                                ModuleGatewayClient client = sharedClient.get();
                                var status =
                                        client == null
                                                ? ConnectionProbe.probe(store)
                                                : ConnectionProbe.probe(store, client);
                                var current = store.load();
                                Platform.runLater(
                                        () -> applyConnectionResult(generation, current, status));
                            } catch (RuntimeException failure) {
                                Platform.runLater(
                                        () -> applyConnectionFailure(generation, failure));
                            }
                        });
    }

    private void applyNotConfigured(long generation) {
        if (!isCurrentConnectionProbe(generation)) {
            return;
        }
        gatewayField.clear();
        profileField.clear();
        environmentField.clear();
        deploymentConfigured = false;
        compatibility = null;
        backendVersionLabel.setText("Not configured");
        compatibilityMessage.setText("");
        finishConnectionProbe();
        setConnection("not configured", HistoforgeStyles.NEUTRAL);
        onConnectionChecked.accept(
                new ConnectionProbe.Result(
                        ConnectionProbe.Status.NOT_CONFIGURED, Optional.empty(), false));
    }

    private void applyConnectionResult(
            long generation,
            java.util.Optional<Deployment> current,
            ConnectionProbe.Result result) {
        if (!isCurrentConnectionProbe(generation)) {
            return;
        }
        if (current.isEmpty()) {
            applyNotConfigured(generation);
            return;
        }
        gatewayField.setText(current.get().gatewayUrl());
        profileField.setText(current.get().modalProfile());
        environmentField.setText(
                current.get().modalEnvironment() == null
                        ? "Not recorded by the older extension"
                        : current.get().modalEnvironment());
        deploymentConfigured = true;
        compatibility = result.compatibility().orElse(null);
        backendVersionLabel.setText(
                result.compatibility()
                        .flatMap(decision -> decision.backendVersion())
                        .orElse("Unknown"));
        compatibilityMessage.setText(
                result.compatibility().map(decision -> decision.message()).orElse(""));
        finishConnectionProbe();
        switch (result.status()) {
            case CONNECTED -> {
                setConnection("connected", HistoforgeStyles.SUCCESS);
                applyInferenceGpuChoices(generation, result.inferenceGpus());
            }
            case UNREACHABLE -> setConnection("not reachable", HistoforgeStyles.WARNING);
            case AUTHENTICATION_REQUIRED ->
                    setConnection("credential rejected", HistoforgeStyles.ERROR);
            case INCOMPATIBLE -> setConnection("incompatible backend", HistoforgeStyles.ERROR);
            case IDENTITY_MISMATCH ->
                    setConnection(
                            "connection identity changed — reconnect", HistoforgeStyles.ERROR);
            case RESTART_REQUIRED -> setConnection("restart required", HistoforgeStyles.ERROR);
            case NOT_CONFIGURED -> {
                deploymentConfigured = false;
                updateActionControls();
                setConnection("not configured", HistoforgeStyles.NEUTRAL);
            }
        }
        onConnectionChecked.accept(result);
    }

    private void applyInferenceGpuChoices(long generation, java.util.List<String> advertised) {
        if (generation != connectionGeneration || actionRunning) {
            return;
        }
        java.util.ArrayList<String> choices = new java.util.ArrayList<>();
        choices.add(HistoforgePrefs.USE_RECIPE_INFERENCE_GPU);
        choices.addAll(advertised);
        String selected = HistoforgePrefs.defaultInferenceGpuProperty().get();
        String help = INFERENCE_GPU_HELP;
        if (selected != null && !selected.isBlank() && !choices.contains(selected)) {
            choices.add(selected);
            help =
                    selected
                            + " is not supported by the current deployment. Choose "
                            + "another GPU or choose Infer from recipe.";
        }
        inferenceGpuComboBox.setTooltip(tooltip(help));
        inferenceGpuInfo.setTextContent(help);
        inferenceGpuComboBox.getItems().setAll(choices);
        inferenceGpuComboBox.setValue(
                selected == null ? HistoforgePrefs.USE_RECIPE_INFERENCE_GPU : selected);
        inferenceGpuChoicesLoaded = !advertised.isEmpty();
        updateActionControls();
    }

    private void applyConnectionFailure(long generation, RuntimeException failure) {
        if (!isCurrentConnectionProbe(generation)) {
            return;
        }
        logger.warn("connection check failed", failure);
        backendVersionLabel.setText("Unknown");
        finishConnectionProbe();
        setConnection("check failed", HistoforgeStyles.ERROR);
        onConnectionChecked.accept(
                new ConnectionProbe.Result(
                        ConnectionProbe.Status.UNREACHABLE, Optional.empty(), false));
    }

    private boolean isCurrentConnectionProbe(long generation) {
        return generation == connectionGeneration && connectionChecking && !actionRunning;
    }

    private void finishConnectionProbe() {
        connectionChecking = false;
        updateActionControls();
    }

    private void loadStoredDeployment() {
        var current = store.load();
        deploymentConfigured = current.isPresent();
        if (current.isPresent()) {
            gatewayField.setText(current.get().gatewayUrl());
            profileField.setText(current.get().modalProfile());
            environmentField.setText(
                    current.get().modalEnvironment() == null
                            ? "Not recorded by the older extension"
                            : current.get().modalEnvironment());
        }
        updateActionControls();
    }

    // ── Small helpers ───────────────────────────────────────────────

    private HistoforgeCli cli() {
        return new HistoforgeCli();
    }

    private void updateActionControls() {
        boolean busy = actionRunning || connectionChecking;
        actionRow.setDisable(busy || !deploymentConfigured);
        locateBackendButton.setVisible(locationRepairAvailable);
        locateBackendButton.setManaged(locationRepairAvailable);
        cloudUpdateButton.setDisable(compatibility == null || !compatibility.permitsCloudUpdate());
        hfToken.setDisable(busy || !deploymentConfigured);
        recheckButton.setDisable(busy);
        boolean gpuReady =
                !busy
                        && compatibility != null
                        && compatibility.permitsWork()
                        && inferenceGpuChoicesLoaded;
        inferenceGpuComboBox.setDisable(!gpuReady);
        String unavailable =
                gpuReady
                        ? ""
                        : busy
                                ? "GPU choices load after the connection check."
                                : compatibility != null && !compatibility.permitsWork()
                                        ? "Connect to a compatible cloud backend to choose a GPU."
                                        : "Recheck the cloud connection to load GPU choices.";
        inferenceGpuAvailability.setText(unavailable);
        inferenceGpuAvailability.setVisible(!unavailable.isEmpty());
        inferenceGpuAvailability.setManaged(!unavailable.isEmpty());
        if (!gpuReady) {
            inferenceGpuComboBox.setTooltip(tooltip(unavailable));
            inferenceGpuInfo.setTextContent(unavailable);
        }
    }

    static String actionFailureMessage(String verb, CliException failure) {
        return switch (failure.type()) {
            case GATEWAY_TARGET_MISMATCH, RECONNECT_REQUIRED, IDENTITY_RECOVERY_REQUIRED ->
                    failure.getMessage();
            default ->
                    "Save token".equals(verb)
                            ? "Save token failed: " + failure.getMessage()
                            : verb + " failed. Open Technical details for the error.";
        };
    }

    private void setStatus(String text, String semanticStyle) {
        HistoforgeStyles.setSemantic(actionStatus, semanticStyle);
        actionStatus.setText(text);
    }

    private void setConnection(String text, String semanticStyle) {
        HistoforgeStyles.setSemantic(connectionLabel, semanticStyle);
        connectionLabel.setText("● " + text);
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add(HistoforgeStyles.HEADING);
        return label;
    }

    private static HistoforgeTooltip tooltip(String text) {
        HistoforgeTooltip tooltip = new HistoforgeTooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        return tooltip;
    }

    private static String formatGibibytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f GiB", bytes / 1_073_741_824.0);
    }

    static String capacityHelp(long maximumJavaHeapBytes) {
        return "Limits the number and complexity of objects, outlines and measurements HistoForge"
                + " can save for one image, including results already present. Use Standard unless"
                + " a result exceeds the limit. Higher settings allow more results but can slow"
                + " QuPath or exhaust memory; they do not reserve RAM or change model accuracy."
                + " QuPath's Java memory limit is currently "
                + formatGibibytes(maximumJavaHeapBytes)
                + ". QuPath also uses computer memory outside this limit. This setting does not"
                + " increase that memory limit. New settings apply to new actions only. Unfinished"
                + " actions keep their original limits and require the same extension build.";
    }

    /** A "Name [field] [buttons…]" row; the first control grows. */
    private static HBox labeled(
            String name, javafx.scene.Node field, javafx.scene.Node... trailing) {
        Label label = new Label(name);
        label.setMinWidth(155);
        HBox row = new HBox(6, label, field);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        row.getChildren().addAll(trailing);
        return row;
    }

    /** One CLI invocation that may stream progress lines. */
    @FunctionalInterface
    private interface CliCall {
        void run(Consumer<String> onLine) throws CliException;
    }
}
