package qupath.ext.histoforge.ui.app;

import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.cli.CliException;
import qupath.ext.histoforge.cli.HistoforgeCli;
import qupath.ext.histoforge.cli.HistoforgeCli.ModalContext;
import qupath.ext.histoforge.cli.HistoforgeCli.StatusResult;
import qupath.ext.histoforge.cli.UvInstaller;
import qupath.ext.histoforge.client.Deployment;
import qupath.ext.histoforge.client.DeploymentStore;
import qupath.ext.histoforge.ui.HistoforgeStyles;

/**
 * First-run / reconnect onboarding shown in place of the tabs when there's no reachable backend. It
 * probes whether the toolchain is missing, not deployed, unreachable, or already connected, and
 * closes the gap behind one button: install uv + the exact CLI bundled with this extension
 * (confirmation-gated), then {@code connect} (join an existing gateway) or {@code deploy}
 * (provision one), streaming progress and surfacing the browser Modal-login step. Cancel interrupts
 * the subprocess.
 *
 * <p>Setup uses the active local Modal profile. Explicit profile selection remains a CLI/settings
 * concern; the onboarding path does not infer a profile from a gateway URL.
 */
final class SetupPane {

    private static final Logger logger = LoggerFactory.getLogger(SetupPane.class);

    /** Where the user is, decided by the mount probe; drives the button text + action. */
    private enum State {
        CHECKING,
        NEEDS_INSTALL,
        NOT_DEPLOYED,
        RECONNECT,
        UNREACHABLE,
        ERROR,
        WORKING
    }

    private final Runnable onConnected;

    private final VBox node;
    private final Label statusLabel = new Label();
    private final Button actionButton = new Button("Set up backend");
    private final CloudSetupProgress progress = new CloudSetupProgress(statusLabel);
    private final HuggingFaceTokenControls hfToken = new HuggingFaceTokenControls(this::saveToken);

    // FX-thread confined.
    private State state = State.CHECKING;
    private String modalProfile;
    private ModalContext setupContext;
    private Thread worker;

    SetupPane(Runnable onConnected) {
        this.onConnected = onConnected;
        this.node = build();
        probeAsync();
    }

    Region getNode() {
        return node;
    }

    void clearToken() {
        hfToken.clear();
    }

    private VBox build() {
        Label title = new Label("Welcome to HistoForge");
        title.setStyle("-fx-font-size: 15; -fx-font-weight: bold;");
        Label blurb =
                new Label(
                        "Run AI on your histology in your own cloud account. Your slides never"
                                + " leave it. No terminal needed.");
        blurb.setWrapText(true);
        Label prerequisite =
                new Label(
                        "Save a Hugging Face token before your first deployment. "
                                + "You can replace it later in Settings.");
        prerequisite.setWrapText(true);
        prerequisite.getStyleClass().add(HistoforgeStyles.MUTED);

        actionButton.setDefaultButton(true);
        actionButton.setDisable(true); // enabled once the probe settles
        actionButton.setOnAction(e -> onAction());

        statusLabel.setWrapText(true);

        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(progress.getNode(), Priority.ALWAYS);

        VBox box =
                new VBox(
                        10,
                        title,
                        blurb,
                        prerequisite,
                        hfToken,
                        actionButton,
                        statusLabel,
                        progress.getNode());
        box.setPadding(new Insets(16));
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    // ── Probe ───────────────────────────────────────────────────────

    /** Work out where the user is (off the FX thread), then settle the button. */
    private void probeAsync() {
        state = State.CHECKING;
        actionButton.setDisable(true);
        hfToken.setDisable(true);
        setStatus("Checking your setup…", HistoforgeStyles.BUSY);
        Thread.ofVirtual().start(this::probe);
    }

    private void probe() {
        State next;
        String message;
        String profile = null;
        try {
            StatusResult status = new HistoforgeCli().status(null);
            if (status.type() == StatusResult.Type.CONFIGURED) {
                profile = status.profile();
            }
            if (status.type() == StatusResult.Type.CONFIGURED && status.connected()) {
                Platform.runLater(onConnected); // already set up + reachable
                return;
            } else if (status.type() == StatusResult.Type.CONFIGURED && status.reachable()) {
                next = State.RECONNECT;
                message = "Your local gateway credential needs to be reconnected.";
            } else if (status.type() == StatusResult.Type.CONFIGURED) {
                next = State.UNREACHABLE;
                message = "Your backend isn't responding. It may be waking up.";
            } else {
                next = State.NOT_DEPLOYED;
                message =
                        status.hint() == null || status.hint().isBlank()
                                ? "Connect your Modal account to set up your backend."
                                : status.hint();
            }
        } catch (CliException e) {
            if (e.type() == CliException.Type.NOT_INSTALLED) {
                profile =
                        new DeploymentStore()
                                .load()
                                .map(deployment -> deployment.modalProfile())
                                .orElse(null);
                next = State.NEEDS_INSTALL;
                message = "First run installs a private local helper, then connects Modal.";
            } else {
                next = State.ERROR;
                message = e.getMessage();
            }
        }
        State settled = next;
        String settledMessage = message;
        String settledProfile = profile;
        Platform.runLater(() -> applyProbe(settled, settledMessage, settledProfile));
    }

    private void applyProbe(State next, String message, String profile) {
        state = next;
        if (profile != null) {
            modalProfile = profile;
        }
        setStatus(message, next == State.ERROR ? HistoforgeStyles.ERROR : HistoforgeStyles.BUSY);
        actionButton.setText(
                switch (next) {
                    case RECONNECT -> "Reconnect";
                    case UNREACHABLE, ERROR -> "Recheck";
                    default -> "Set up backend";
                });
        actionButton.setDisable(false);
        hfToken.setDisable(false);
    }

    // ── Action ──────────────────────────────────────────────────────

    private void onAction() {
        switch (state) {
            case WORKING -> {
                if (worker != null) {
                    worker.interrupt();
                }
            }
            case UNREACHABLE, ERROR -> probeAsync();
            case RECONNECT -> startWork(false);
            default -> {
                boolean needsInstall = state == State.NEEDS_INSTALL;
                if (!needsInstall || confirmInstall()) {
                    startWork(needsInstall);
                }
            }
        }
    }

    private boolean confirmInstall() {
        return RuntimeHelperInstallDialog.confirm(node.getScene().getWindow());
    }

    private void startWork(boolean needsInstall) {
        startWork(needsInstall, null);
    }

    private void saveToken(String token) {
        boolean needsInstall = state == State.NEEDS_INSTALL;
        if (state == State.WORKING
                || state == State.CHECKING
                || (needsInstall && !confirmInstall())) {
            return;
        }
        startWork(needsInstall, token);
    }

    private void startWork(boolean needsInstall, String token) {
        var target = new DeploymentStore().load();
        if (state == State.RECONNECT && target.isEmpty()) {
            probeAsync();
            return;
        }
        boolean allowCreate = state != State.RECONNECT && target.isEmpty();
        state = State.WORKING;
        hfToken.setDisable(true);
        String selectedProfile = modalProfile;
        ModalContext selectedContext = setupContext;
        actionButton.setText("Cancel");
        setStatus("Setting up your backend…", HistoforgeStyles.BUSY);
        progress.reset();

        Consumer<String> onLine = line -> Platform.runLater(() -> appendLine(line));
        worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        if (needsInstall) {
                                            install(onLine);
                                        }
                                        if (token != null) {
                                            Platform.runLater(
                                                    () ->
                                                            statusLabel.setText(
                                                                    "Verifying and saving token… Finish Modal login in your browser if asked."));
                                            ModalContext savedContext =
                                                    new HistoforgeCli()
                                                            .saveHuggingFaceToken(
                                                                    selectedContext,
                                                                    target.orElse(null),
                                                                    token,
                                                                    onLine);
                                            Platform.runLater(
                                                    () -> {
                                                        worker = null;
                                                        progress.hideLoginLink();
                                                        setupContext = savedContext;
                                                        applyProbe(
                                                                State.NOT_DEPLOYED,
                                                                "Token verified and saved. Set up your backend next.",
                                                                savedContext.profile());
                                                    });
                                            return;
                                        }
                                        Platform.runLater(() -> statusLabel.setText("Connecting…"));
                                        connectOrDeploy(
                                                selectedProfile,
                                                selectedContext,
                                                onLine,
                                                allowCreate,
                                                target.orElse(null));
                                        Platform.runLater(this::onSuccess);
                                    } catch (CliException ex) {
                                        Platform.runLater(() -> onError(ex, token != null));
                                    }
                                });
    }

    private void install(Consumer<String> onLine) throws CliException {
        UvInstaller uv = new UvInstaller();
        if (!uv.isInstalled()) {
            Platform.runLater(() -> statusLabel.setText("Installing uv…"));
            uv.bootstrapUv(onLine);
        }
        Platform.runLater(() -> statusLabel.setText("Installing the histoforge CLI…"));
        uv.installHistoforge(onLine);
    }

    /** Only first-time setup may create an absent gateway; reconnect never provisions one. */
    private void connectOrDeploy(
            String selectedProfile,
            ModalContext selectedContext,
            Consumer<String> onLine,
            boolean allowCreate,
            Deployment target)
            throws CliException {
        HistoforgeCli cli = new HistoforgeCli();
        ModalContext context =
                target == null
                        ? selectedContext == null
                                ? cli.login(selectedProfile, onLine)
                                : selectedContext
                        : null;
        try {
            if (target == null) cli.connect(context, onLine);
            else cli.reconnect(target, onLine);
        } catch (CliException ce) {
            if (!allowCreate || !shouldDeployAfterConnectFailure(ce.type())) {
                throw ce;
            }
            Platform.runLater(() -> setStatus("Deploying backend…", HistoforgeStyles.BUSY));
            cli.deploy(context, onLine);
        }
    }

    static boolean shouldDeployAfterConnectFailure(CliException.Type type) {
        return type == CliException.Type.GATEWAY_NOT_FOUND;
    }

    private void appendLine(String line) {
        progress.appendLine(line);
    }

    private void onSuccess() {
        setStatus("✓ Connected", HistoforgeStyles.SUCCESS);
        onConnected.run(); // MainController swaps in the tabs
    }

    private void onError(CliException ex, boolean tokenSetup) {
        worker = null;
        hfToken.setDisable(false);
        if (ex.type() == CliException.Type.INTERRUPTED && !tokenSetup) {
            probeAsync(); // cancelled; re-evaluate and offer the right next step
            return;
        }
        logger.warn("Backend setup failed", ex);
        state = State.ERROR;
        String guidance =
                ex.type() == CliException.Type.GATEWAY_TARGET_MISMATCH
                                && new DeploymentStore().load().isPresent()
                        ? " Open Settings (the gear button) to restore the saved backend connection."
                        : "";
        setStatus("Setup failed: " + ex.getMessage() + guidance, HistoforgeStyles.ERROR);
        actionButton.setText("Recheck");
    }

    private void setStatus(String text, String semanticStyle) {
        HistoforgeStyles.setSemantic(statusLabel, semanticStyle);
        statusLabel.setText(text);
    }
}
