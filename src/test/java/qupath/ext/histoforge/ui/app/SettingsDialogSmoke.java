package qupath.ext.histoforge.ui.app;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import qupath.ext.histoforge.client.BackendCompatibility;
import qupath.ext.histoforge.client.ConnectionProbe;
import qupath.ext.histoforge.client.Deployment;
import qupath.ext.histoforge.platform.ExtensionRelease;
import qupath.ext.histoforge.ui.HistoforgePrefs;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.ui.jobs.JobsPoller;
import qupath.ext.histoforge.ui.training.TrainingFormSmoke;

/** Render Settings without probing the gateway, running CLI actions, or changing saved choices. */
public final class SettingsDialogSmoke {
    private SettingsDialogSmoke() {}

    public static void verify(Stage owner, Path output) throws Exception {
        var distributionReceipt = distributionReceipt();
        require(
                java.util.prefs.Preferences.userRoot().getClass().getEnclosingClass()
                        == qupath.ext.histoforge.platform.IsolatedPreferencesFactory.class,
                "Settings fixture requires process-local preferences");
        verifyInspectionRemainsAvailable();
        verifyHelperInstallDialog(owner, output);
        verifySetupNavigation(owner, output);
        String savedGpu = HistoforgePrefs.defaultInferenceGpuProperty().get();
        var savedPrecision = HistoforgePrefs.inferencePrecision();
        var savedCapacity = HistoforgePrefs.localResourceEnvelopeProperty().get();
        boolean savedDuplicates =
                HistoforgePrefs.removeOverlappingSameClassDuplicatesProperty().get();
        var settings =
                new SettingsDialog(
                        owner,
                        () -> null,
                        () -> {},
                        () -> java.util.concurrent.CompletableFuture.completedFuture(null),
                        () -> {});
        var stageField = SettingsDialog.class.getDeclaredField("stage");
        stageField.setAccessible(true);
        var stage = (Stage) stageField.get(settings);
        try {
            Parent root = stage.getScene().getRoot();
            require(root instanceof ScrollPane, "Settings has no scrolling container");
            ScrollPane scroll = (ScrollPane) root;
            root.applyCss();
            var token = (PasswordField) root.lookup("#huggingFaceToken");
            require(token.getText().isEmpty(), "Token field must never be prefilled");
            var submissions = new java.util.ArrayList<String>();
            var entry = new HuggingFaceTokenControls(submissions::add);
            var entered = (PasswordField) entry.lookup("#huggingFaceToken");
            var save = (Button) entry.lookup("#saveHuggingFaceToken");
            require(save.isDisabled(), "Empty token must not be submitted");
            entered.setText("  hf_fixture_only  ");
            save.fire();
            require(
                    submissions.equals(java.util.List.of("hf_fixture_only")),
                    "Wrong submitted value");
            require(
                    entered.getText().isEmpty() && save.isDisabled(),
                    "Submitted token retained in field");
            ((TextField) root.lookup("#modalProfile")).setText("Research workspace");
            ((TextField) root.lookup("#gatewayUrl")).setText("https://example.invalid/histoforge");
            var status = (Label) root.lookup("#cloudConnectionStatus");
            status.setText("● connected");
            HistoforgeStyles.setSemantic(status, HistoforgeStyles.SUCCESS);
            require(status.getTooltip() == null, "Self-explanatory status gained a tooltip");
            require(
                    ((Button) root.lookup("#updateCloudBackend")).isDisabled(),
                    "Cloud update must wait for verified compatibility");
            require(
                    root.lookupAll(".button").stream()
                            .noneMatch(
                                    node ->
                                            node instanceof Button button
                                                    && java.util.Set.of(
                                                                    "Copy",
                                                                    "Redeploy",
                                                                    "Upgrade runtime")
                                                            .contains(button.getText())),
                    "Obsolete cloud action remains");
            showCompatibility(
                    settings,
                    root,
                    BackendCompatibility.State.CURRENT,
                    "Cloud backend is compatible.");
            @SuppressWarnings("unchecked")
            var gpu = (ComboBox<String>) root.lookup("#defaultInferenceGpu");
            @SuppressWarnings("unchecked")
            var precision =
                    (ComboBox<qupath.ext.histoforge.modules.InferencePrecision>)
                            root.lookup("#inferencePrecision");
            require(
                    precision
                            .getItems()
                            .equals(
                                    java.util.List.of(
                                            qupath.ext.histoforge.modules.InferencePrecision
                                                    .values())),
                    "Inference precision choices differ from Default / BF16 (if applicable)");
            require(precision.getValue() == savedPrecision, "Saved precision was not loaded");
            require(
                    precision
                            .getConverter()
                            .toString(qupath.ext.histoforge.modules.InferencePrecision.DEFAULT)
                            .equals("Default"),
                    "Wrong native precision label");
            require(
                    precision
                            .getConverter()
                            .toString(
                                    qupath.ext.histoforge.modules.InferencePrecision
                                            .BF16_IF_APPLICABLE)
                            .equals("BF16 (if applicable)"),
                    "Wrong BF16 precision label");
            precision.setValue(qupath.ext.histoforge.modules.InferencePrecision.DEFAULT);
            require(
                    HistoforgePrefs.inferencePrecision() == precision.getValue(),
                    "Precision choice did not persist");
            precision.setValue(savedPrecision);
            require(
                    gpu.getConverter().toString("").equals("Infer from recipe"),
                    "Wrong recipe default label");
            var updateGpus =
                    SettingsDialog.class.getDeclaredMethod(
                            "applyInferenceGpuChoices", long.class, java.util.List.class);
            updateGpus.setAccessible(true);
            updateGpus.invoke(
                    settings,
                    0L,
                    savedGpu == null || savedGpu.isBlank()
                            ? java.util.List.of("L40S")
                            : java.util.List.of(savedGpu));
            require(
                    gpu.getTooltip() != null
                            && gpu.getTooltip().getText().contains("new Project actions"),
                    "Capability refresh removed the inference GPU help");
            require(
                    ((CheckBox) root.lookup("#removeOverlappingDuplicates")).getTooltip() != null,
                    "Duplicate-removal help is missing");
            stage.show(); // Do not call SettingsDialog.show(): it intentionally starts a
            // connection probe.
            root.applyCss();
            root.layout();
            verifyGpuHelp(settings, gpu, updateGpus, output);
            for (String theme : new String[] {"Modena Light", "Modena Dark"}) {
                TrainingFormSmoke.applyQuPathStyle(theme, root);
                for (var node : root.lookupAll(".button")) {
                    if (node instanceof Button button) {
                        require(button.getTooltip() != null, button.getText() + " is missing help");
                    }
                }
                for (String id :
                        new String[] {
                            "modalProfile",
                            "gatewayUrl",
                            "huggingFaceToken",
                            "defaultInferenceGpu",
                            "inferencePrecision",
                            "localResourceEnvelope",
                            "removeOverlappingDuplicates"
                        }) {
                    var control = (Control) root.lookup("#" + id);
                    require(control.getTooltip() != null, id + " is missing help");
                    var bounds = control.localToScene(control.getBoundsInLocal());
                    require(
                            bounds.getMaxX() <= stage.getScene().getWidth(),
                            id + " exceeds right edge");
                    require(
                            bounds.getMaxY() <= stage.getScene().getHeight(),
                            id + " exceeds bottom edge");
                }
                TrainingFormSmoke.capture(
                        root,
                        output.resolve(
                                "settings-"
                                        + (theme.endsWith("Light") ? "light" : "dark")
                                        + ".png"));
            }
            require(
                    java.util.Objects.equals(
                            savedGpu, HistoforgePrefs.defaultInferenceGpuProperty().get()),
                    "GPU preference changed");
            require(
                    savedCapacity == HistoforgePrefs.localResourceEnvelopeProperty().get(),
                    "Capacity preference changed");
            require(
                    savedPrecision == HistoforgePrefs.inferencePrecision(),
                    "Precision preference changed");
            require(
                    savedDuplicates
                            == HistoforgePrefs.removeOverlappingSameClassDuplicatesProperty().get(),
                    "Duplicate preference changed");
            showCompatibility(
                    settings,
                    root,
                    BackendCompatibility.State.UPDATE_REQUIRED,
                    "This backend is not supported. Update cloud backend before starting new work.");
            TrainingFormSmoke.capture(root, output.resolve("settings-update-required.png"));
            showCompatibility(
                    settings,
                    root,
                    BackendCompatibility.State.NEWER_SUPPORTED,
                    "This backend is newer than the bundled release and is compatible. No cloud update needed.");
            TrainingFormSmoke.capture(root, output.resolve("settings-newer-backend.png"));
            showCompatibility(
                    settings,
                    root,
                    BackendCompatibility.State.UNKNOWN,
                    "Cloud compatibility could not be established. Check the extension and backend versions before updating.");
            showCompatibility(
                    settings,
                    root,
                    BackendCompatibility.State.CURRENT,
                    "Cloud backend is compatible.");
            var loginLine = SettingsDialog.class.getDeclaredMethod("reflectProgress", String.class);
            loginLine.setAccessible(true);
            loginLine.invoke(
                    settings, "No Modal credentials found. Opening a browser to log in...");
            loginLine.invoke(settings, "https://modal.com/token-flow/tf-fixture");
            loginLine.invoke(settings, "");
            loginLine.invoke(settings, "Building image: full fixture deployment output");
            for (int i = 0; i < 80; i++) {
                loginLine.invoke(settings, "Deployment fixture line " + i + ": ✓ …");
            }
            root.applyCss();
            root.layout();
            var instructions = (TextArea) root.lookup("#setupTechnicalLog");
            var details = (TitledPane) root.lookup("#setupTechnicalDetails");
            var login = (Hyperlink) root.lookup("#modalLoginLink");
            require(
                    !details.isExpanded()
                            && instructions
                                    .getText()
                                    .contains(
                                            "/token-flow/tf-fixture\n\nBuilding image: full fixture deployment output\n"),
                    "Diagnostics must be available without expanding by default");
            require(
                    !((Button) root.lookup("#copySetupLogs")).isDisabled(),
                    "Complete deployment logs cannot be copied");
            require(
                    login.isVisible() && login.isManaged(),
                    "The manual login link was hidden with the diagnostics");
            TrainingFormSmoke.capture(root, output.resolve("settings-modal-login.png"));
            details.setExpanded(true);
            finishPendingLayout(root);
            scroll.setVvalue(scroll.getVmax());
            finishPendingLayout(root);
            require(
                    instructions.getHeight() >= instructions.minHeight(instructions.getWidth()),
                    "Expanded diagnostics are shorter than the native control minimum");
            requireFullyVisible(instructions);
            var copyLogs = (Button) root.lookup("#copySetupLogs");
            requireFullyVisible(copyLogs);
            verifyFullLogCopy(instructions, copyLogs);
            TrainingFormSmoke.capture(root, output.resolve("settings-technical-details.png"));
            var scrollCases = verifyScrollableSettings(stage, scroll, output);
            details.setExpanded(false);
            finishPendingLayout(root);
            var statusField = SettingsDialog.class.getDeclaredField("actionStatus");
            statusField.setAccessible(true);
            var progressStatus = (Label) statusField.get(settings);
            loginLine.invoke(settings, "Deploying the bundled cloud backend…");
            require(
                    progressStatus.getText().equals("Deploying backend…"),
                    "Deployment still looks like a connection check");
            loginLine.invoke(settings, "Verifying the updated cloud backend…");
            require(
                    progressStatus.getText().equals("Checking backend connection…"),
                    "Final verification stage is missing");
            var progressField = SettingsDialog.class.getDeclaredField("progress");
            progressField.setAccessible(true);
            String retainedLog = instructions.getText();
            ((CloudSetupProgress) progressField.get(settings)).hideLoginLink();
            require(
                    !login.isVisible() && !login.isManaged(),
                    "Completed login link remains visible");
            require(
                    instructions.getText().equals(retainedLog),
                    "Hiding login removed the diagnostics");
            verifyUserWindowSize(settings, stage, details, output);
            verifyUpdateGate(settings, owner);
            token.setText("hf_not_a_real_secret");
            stage.hide();
            require(token.getText().isEmpty(), "Closing Settings retained entered token");
            verifyRetainedScreenSelection(settings, stage, owner);
            if (distributionReceipt != null) {
                distributionReceipt.addProperty("settings_passed", true);
                distributionReceipt.add("settings_scroll_cases", scrollCases);
                distributionReceipt.addProperty("settings_reopen_uses_retained_position", true);
                Files.writeString(
                        output.resolve("settings-distribution-receipt.json"),
                        distributionReceipt + "\n");
            }
        } catch (Exception | AssertionError failure) {
            try {
                Files.writeString(
                        output.resolve("settings-layout-failure.json"),
                        layoutEvidence(stage) + "\n");
                TrainingFormSmoke.capture(
                        stage.getScene().getRoot(), output.resolve("settings-layout-failure.png"));
            } catch (Exception | AssertionError evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            throw failure;
        } finally {
            HistoforgePrefs.inferencePrecisionProperty().set(savedPrecision);
            stage.close();
            TrainingFormSmoke.applyQuPathStyle("Modena Light", owner.getScene().getRoot());
        }
    }

    private static JsonObject distributionReceipt() throws Exception {
        String configured = System.getProperty("histoforge.test.distribution.artifact");
        if (configured == null) return null;
        Path artifact = Path.of(configured).toRealPath();
        require(Files.isRegularFile(artifact), "Packaged Settings fixture requires a JAR");
        var origins = new JsonObject();
        for (Class<?> type :
                new Class<?>[] {
                    SettingsDialog.class,
                    RuntimeHelperInstallDialog.class,
                    CloudSetupProgress.class,
                    MainController.class,
                    SetupPane.class,
                    HistoforgePrefs.class
                }) {
            Path origin =
                    Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .toRealPath();
            require(
                    origin.equals(artifact),
                    type.getSimpleName() + " did not load from the canonical JAR: " + origin);
            origins.addProperty(type.getName(), origin.toString());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(artifact), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        var receipt = new JsonObject();
        receipt.addProperty("artifact", artifact.toString());
        receipt.addProperty("sha256", HexFormat.of().formatHex(digest.digest()));
        receipt.addProperty("qupath_version", System.getProperty("histoforge.test.qupath.version"));
        receipt.addProperty("java_version", System.getProperty("java.version"));
        receipt.add("class_origins", origins);
        return receipt;
    }

    private static void verifyGpuHelp(
            SettingsDialog settings,
            ComboBox<String> gpu,
            java.lang.reflect.Method updateGpus,
            Path output)
            throws Exception {
        require(
                java.util.prefs.Preferences.userRoot().getClass().getEnclosingClass()
                        == qupath.ext.histoforge.platform.IsolatedPreferencesFactory.class,
                "GPU fixture changes require process-local preferences");
        var infoField = SettingsDialog.class.getDeclaredField("inferenceGpuInfo");
        infoField.setAccessible(true);
        var info = (qupath.ext.histoforge.ui.InformationButton) infoField.get(settings);
        String saved = HistoforgePrefs.defaultInferenceGpuProperty().get();
        try {
            HistoforgePrefs.defaultInferenceGpuProperty().set("L40S");
            updateGpus.invoke(settings, 0L, java.util.List.of("L40S"));
            String normal = gpu.getTooltip().getText();
            require(normal.contains("new Project actions"), "Normal GPU help is missing");
            requireGpuHelpMatches(gpu, info, output, "supported");

            HistoforgePrefs.defaultInferenceGpuProperty().set("fixture-unavailable-gpu");
            updateGpus.invoke(settings, 0L, java.util.List.of("L40S"));
            require(
                    gpu.getTooltip()
                            .getText()
                            .equals(
                                    "fixture-unavailable-gpu is not supported by the current deployment. Choose "
                                            + "another GPU or choose Infer from recipe."),
                    "Unsupported GPU warning changed");
            require(
                    gpu.getItems().contains("fixture-unavailable-gpu")
                            && gpu.getValue().equals("fixture-unavailable-gpu"),
                    "Capability refresh substituted the unsupported saved choice");
            requireGpuHelpMatches(gpu, info, output, "unsupported");

            updateGpus.invoke(settings, 0L, java.util.List.of("L40S", "fixture-unavailable-gpu"));
            require(normal.equals(gpu.getTooltip().getText()), "Normal GPU help was not restored");
            requireGpuHelpMatches(gpu, info, output, "supported-restored");
        } finally {
            HistoforgePrefs.defaultInferenceGpuProperty().set(saved);
            updateGpus.invoke(
                    settings,
                    0L,
                    saved == null || saved.isBlank()
                            ? java.util.List.of("L40S")
                            : java.util.List.of(saved));
        }
    }

    private static void requireGpuHelpMatches(
            ComboBox<String> gpu,
            qupath.ext.histoforge.ui.InformationButton info,
            Path output,
            String state) {
        String text = gpu.getTooltip().getText();
        require(
                info.getTooltip() != null && text.equals(info.getTooltip().getText()),
                "GPU selector and information hover differ");
        require(
                gpu.getTooltip().isWrapText() && gpu.getTooltip().getMaxWidth() == 420,
                "GPU help is not bounded");
        info.fire();
        var popup =
                javafx.stage.Window.getWindows().stream()
                        .filter(javafx.stage.Popup.class::isInstance)
                        .map(javafx.stage.Popup.class::cast)
                        .filter(window -> window.getOwnerNode() == info && window.isShowing())
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("GPU information did not open"));
        require(
                popup.getScene().getRoot().lookupAll(".label").stream()
                        .anyMatch(
                                node ->
                                        node instanceof Label label
                                                && text.equals(label.getText())),
                "GPU hover and information popup differ");
        TrainingFormSmoke.capture(
                popup.getScene().getRoot(), output.resolve("gpu-help-" + state + ".png"));
        info.fire();
        require(
                !popup.isShowing() && text.equals(info.getTooltip().getText()),
                "Closing GPU information did not restore its current hover");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void finishPendingLayout(Parent root) {
        Object barrier = new Object();
        var scene = root.getScene();
        boolean[] completed = {false};
        java.util.function.Consumer<Boolean> complete =
                success -> {
                    if (completed[0]) return;
                    completed[0] = true;
                    Platform.exitNestedEventLoop(barrier, success);
                };
        Runnable[] pulse = new Runnable[1];
        pulse[0] =
                () -> {
                    scene.removePostLayoutPulseListener(pulse[0]);
                    Platform.runLater(() -> complete.accept(Boolean.TRUE));
                };
        var deadline = new PauseTransition(Duration.seconds(2));
        deadline.setOnFinished(event -> complete.accept(Boolean.FALSE));
        scene.addPostLayoutPulseListener(pulse[0]);
        deadline.play();
        Platform.requestNextPulse();
        try {
            require(
                    Boolean.TRUE.equals(Platform.enterNestedEventLoop(barrier)),
                    "Settings layout pulse did not arrive");
        } finally {
            deadline.stop();
            scene.removePostLayoutPulseListener(pulse[0]);
        }
        root.applyCss();
        root.layout();
    }

    private static void requireFullyVisible(Node node) {
        var bounds = node.localToScene(node.getBoundsInLocal());
        require(
                bounds.getMinX() >= 0
                        && bounds.getMinY() >= 0
                        && bounds.getMaxX() <= node.getScene().getWidth()
                        && bounds.getMaxY() <= node.getScene().getHeight(),
                node.getId() + " exceeds the window");
        var screenBounds = node.localToScreen(node.getBoundsInLocal());
        var window = node.getScene().getWindow();
        require(
                screenBounds != null
                        && screenBounds.getMinX() >= window.getX()
                        && screenBounds.getMinY() >= window.getY()
                        && screenBounds.getMaxX() <= window.getX() + window.getWidth()
                        && screenBounds.getMaxY() <= window.getY() + window.getHeight(),
                node.getId() + " exceeds the native window frame");
        for (Node ancestor = node; ancestor != null; ancestor = ancestor.getParent()) {
            require(ancestor.isVisible(), node.getId() + " has a hidden ancestor");
            if (ancestor.getClip() != null) {
                var clip = ancestor.localToScene(ancestor.getClip().getBoundsInParent());
                require(clip.contains(bounds), node.getId() + " is clipped by an ancestor");
            }
        }
    }

    private static void verifyFullLogCopy(TextArea log, Button copy) {
        // Exercise the real button handler without accessing the owner's clipboard.
        var clipboard = mock(javafx.scene.input.Clipboard.class);
        try (var access = org.mockito.Mockito.mockStatic(javafx.scene.input.Clipboard.class)) {
            access.when(javafx.scene.input.Clipboard::getSystemClipboard).thenReturn(clipboard);
            log.selectRange(0, 1);
            copy.fire();
            org.mockito.Mockito.verify(clipboard)
                    .setContent(
                            org.mockito.ArgumentMatchers.argThat(
                                    content ->
                                            log.getText()
                                                    .equals(
                                                            content.get(
                                                                    javafx.scene.input.DataFormat
                                                                            .PLAIN_TEXT))));
        }
    }

    private static void verifyRetainedScreenSelection(
            SettingsDialog settings, Stage stage, Stage owner) throws Exception {
        require(!stage.isShowing(), "Screen-selection check requires hidden Settings");
        stage.setX(owner.getX() + 40);
        stage.setY(owner.getY() + 40);
        double x = stage.getX() + stage.getWidth() / 2;
        double y = stage.getY() + stage.getHeight() / 2;
        require(Double.isFinite(x) && Double.isFinite(y), "Settings has no retained position");
        require(
                x != owner.getX() + owner.getWidth() / 2
                        || y != owner.getY() + owner.getHeight() / 2,
                "Settings and owner centers must differ for this check");
        var resize = SettingsDialog.class.getDeclaredMethod("fitToScreen");
        resize.setAccessible(true);
        // Verify the chosen coordinates while retaining the platform's actual screen lookup.
        try (var screens =
                org.mockito.Mockito.mockStatic(
                        Screen.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            resize.invoke(settings);
            screens.verify(() -> Screen.getScreensForRectangle(x, y, 1, 1));
        }
    }

    private static JsonArray verifyScrollableSettings(Stage stage, ScrollPane scroll, Path output)
            throws Exception {
        var cases = new JsonArray();
        var log = (TextArea) scroll.lookup("#setupTechnicalLog");
        var copy = (Button) scroll.lookup("#copySetupLogs");
        for (String theme : new String[] {"Modena Light", "Modena Dark"}) {
            TrainingFormSmoke.applyQuPathStyle(theme, scroll);
            for (int height : new int[] {740, 650, 500}) {
                stage.setHeight(height);
                scroll.setVvalue(scroll.getVmin());
                finishPendingLayout(scroll);
                requireFullyVisible(scroll.lookup("#modalProfile"));
                if (height == 500) {
                    require(
                            scroll.getContent().getLayoutBounds().getHeight()
                                    > scroll.getViewportBounds().getHeight(),
                            "Short Settings window does not exercise scrolling");
                }
                scroll.setVvalue(scroll.getVmax());
                finishPendingLayout(scroll);
                requireFullyVisible(log);
                requireFullyVisible(copy);
                verifyFullLogCopy(log, copy);
                var record = layoutEvidence(stage);
                record.addProperty("theme", theme);
                record.addProperty("requested_window_height", height);
                cases.add(record);
                TrainingFormSmoke.capture(
                        scroll,
                        output.resolve(
                                "settings-scroll-"
                                        + (theme.endsWith("Light") ? "light" : "dark")
                                        + "-"
                                        + height
                                        + ".png"));
            }
        }
        Files.writeString(output.resolve("settings-layout-cases.json"), cases + "\n");
        return cases;
    }

    private static void verifyUserWindowSize(
            SettingsDialog settings, Stage stage, TitledPane details, Path output)
            throws Exception {
        var fit = SettingsDialog.class.getDeclaredMethod("fitToScreen");
        fit.setAccessible(true);
        fit.invoke(settings);
        stage.setWidth(900);
        stage.setHeight(Math.min(900, stage.getMaxHeight()));
        details.setExpanded(true);
        finishPendingLayout(stage.getScene().getRoot());
        double width = stage.getWidth();
        double height = stage.getHeight();
        var progressField = SettingsDialog.class.getDeclaredField("progress");
        progressField.setAccessible(true);
        var progress = (CloudSetupProgress) progressField.get(settings);
        progress.appendLine("https://modal.com/token-flow/tf-resize-fixture");
        progress.hideLoginLink();
        finishPendingLayout(stage.getScene().getRoot());
        require(
                Math.abs(stage.getWidth() - width) < 2 && Math.abs(stage.getHeight() - height) < 2,
                "Setup progress reset the user's Settings window size");
        var log = (TextArea) stage.getScene().getRoot().lookup("#setupTechnicalLog");
        double expandedHeight = log.getHeight();
        double minimumHeight = log.minHeight(log.getWidth());
        var scroll = (ScrollPane) stage.getScene().getRoot();
        double spareHeight =
                scroll.getViewportBounds().getHeight()
                        - scroll.getContent().minHeight(scroll.getViewportBounds().getWidth());
        require(expandedHeight >= minimumHeight, "Settings diagnostics are below their minimum");
        // A small native display may leave no extra height after the settings controls.
        if (spareHeight > 20) {
            require(
                    expandedHeight > minimumHeight + 20,
                    "Settings diagnostics did not use the larger window's spare height");
        }
        var evidence = new JsonObject();
        evidence.addProperty("spare_height", spareHeight);
        evidence.addProperty("minimum_log_height", minimumHeight);
        evidence.add("expanded", layoutEvidence(stage));
        double expandedSceneHeight = stage.getScene().getHeight();
        stage.setHeight(height - 100);
        finishPendingLayout(stage.getScene().getRoot());
        double sceneReduction = expandedSceneHeight - stage.getScene().getHeight();
        require(
                Math.abs(log.getHeight() - Math.max(minimumHeight, expandedHeight - sceneReduction))
                        < 2,
                "Settings diagnostics did not resize with the window");
        evidence.add("shorter", layoutEvidence(stage));
        scroll.setVvalue(scroll.getVmax());
        finishPendingLayout(scroll);
        requireFullyVisible(log);
        requireFullyVisible(scroll.lookup("#copySetupLogs"));
        verifyFullLogCopy(log, (Button) scroll.lookup("#copySetupLogs"));
        details.setExpanded(false);
        finishPendingLayout(stage.getScene().getRoot());
        require(details.getHeight() < 60, "Collapsed diagnostics retained an empty expanded panel");
        double retainedHeight = stage.getHeight();
        stage.hide();
        fit.invoke(settings);
        require(
                Math.abs(stage.getWidth() - width) < 2
                        && Math.abs(stage.getHeight() - retainedHeight) < 2,
                "Reopening Settings replaced the user's window size");
        stage.show();
        finishPendingLayout(scroll);
        evidence.add("reopened", layoutEvidence(stage));
        Files.writeString(output.resolve("settings-layout-user-resize.json"), evidence + "\n");
    }

    private static JsonObject layoutEvidence(Stage stage) {
        var result = new JsonObject();
        var scene = stage.getScene();
        result.addProperty("window_x", stage.getX());
        result.addProperty("window_y", stage.getY());
        result.addProperty("window_width", stage.getWidth());
        result.addProperty("window_height", stage.getHeight());
        result.addProperty("window_max_height", stage.getMaxHeight());
        result.addProperty("scene_width", scene.getWidth());
        result.addProperty("scene_height", scene.getHeight());
        var screens = new JsonArray();
        for (Screen screen : Screen.getScreens()) {
            var bounds = screen.getVisualBounds();
            var record = new JsonObject();
            record.addProperty("x", bounds.getMinX());
            record.addProperty("y", bounds.getMinY());
            record.addProperty("width", bounds.getWidth());
            record.addProperty("height", bounds.getHeight());
            screens.add(record);
        }
        result.add("screens", screens);
        if (scene.getRoot() instanceof ScrollPane scroll) {
            result.addProperty("viewport_height", scroll.getViewportBounds().getHeight());
            result.addProperty("content_height", scroll.getContent().getLayoutBounds().getHeight());
            result.addProperty("scroll_value", scroll.getVvalue());
        }
        for (String id : new String[] {"setupTechnicalLog", "copySetupLogs"}) {
            Node node = scene.getRoot().lookup("#" + id);
            if (node != null) {
                var bounds = node.localToScene(node.getBoundsInLocal());
                var record = new JsonObject();
                record.addProperty("top", bounds.getMinY());
                record.addProperty("bottom", bounds.getMaxY());
                record.addProperty("height", bounds.getHeight());
                result.add(id, record);
            }
        }
        return result;
    }

    private static void verifySetupNavigation(Stage owner, Path output) throws Exception {
        var title = new Label("Welcome to HistoForge");
        var status = new Label("Deploying backend…");
        var progress = new CloudSetupProgress(status);
        progress.appendLine("Deployment fixture output");
        var body = new javafx.scene.layout.VBox(10, title, status, progress.getNode());
        javafx.scene.layout.VBox.setVgrow(progress.getNode(), javafx.scene.layout.Priority.ALWAYS);
        body.setPadding(new javafx.geometry.Insets(16));
        // Replace only the onboarding CLI probe; render the production navigation shell.
        try (var ignored =
                org.mockito.Mockito.mockConstruction(
                        SetupPane.class,
                        (pane, context) ->
                                org.mockito.Mockito.when(pane.getNode()).thenReturn(body))) {
            var loader = new FXMLLoader(MainController.class.getResource("main.fxml"));
            Parent root = loader.load();
            MainController controller = loader.getController();
            var show = MainController.class.getDeclaredMethod("showSetup");
            show.setAccessible(true);
            show.invoke(controller);
            var stage = new Stage();
            stage.initOwner(owner);
            stage.setScene(new javafx.scene.Scene(root, 700, 440));
            try {
                stage.show();
                var content =
                        (javafx.scene.layout.VBox)
                                ((javafx.scene.layout.VBox) root).getChildren().get(2);
                var back = (Button) content.getChildren().getFirst();
                var details = (TitledPane) root.lookup("#setupTechnicalDetails");
                details.setExpanded(true);
                for (String theme : new String[] {"Modena Light", "Modena Dark"}) {
                    TrainingFormSmoke.applyQuPathStyle(theme, root);
                    for (int font : new int[] {12, 20}) {
                        root.setStyle("-fx-font-size: " + font + "px;");
                        root.applyCss();
                        root.layout();
                        var backBounds = back.localToScene(back.getBoundsInLocal());
                        var titleBounds = title.localToScene(title.getBoundsInLocal());
                        require(
                                backBounds.getMinX() >= 16,
                                "Back navigation touches the window edge");
                        require(
                                Math.abs(backBounds.getMinX() - titleBounds.getMinX()) < 0.5,
                                "Back navigation and setup heading have different left alignment");
                        require(
                                backBounds.getMaxX() < stage.getScene().getWidth(),
                                "Back navigation exceeds the window width");
                        var log = (TextArea) root.lookup("#setupTechnicalLog");
                        stage.setHeight(500);
                        finishPendingLayout(root);
                        double shortLogHeight = log.getHeight();
                        stage.setHeight(700);
                        finishPendingLayout(root);
                        require(
                                log.getHeight() > shortLogHeight + 180,
                                "Setup diagnostics did not fill the added window height");
                        var copy = (Button) root.lookup("#copySetupLogs");
                        requireFullyVisible(copy);
                        require(
                                stage.getScene().getHeight()
                                                - progress.getNode()
                                                        .localToScene(
                                                                progress.getNode()
                                                                        .getBoundsInLocal())
                                                        .getMaxY()
                                        < body.getPadding().getBottom() + 2,
                                "Setup diagnostics leave unused space below their panel");
                        TrainingFormSmoke.capture(
                                root,
                                output.resolve(
                                        "setup-navigation-"
                                                + (theme.endsWith("Light") ? "light" : "dark")
                                                + "-"
                                                + font
                                                + ".png"));
                    }
                }
                var scroll = (ScrollPane) content.getChildren().get(1);
                stage.setHeight(300);
                finishPendingLayout(root);
                require(
                        body.getHeight() > scroll.getViewportBounds().getHeight(),
                        "Short setup window does not exercise scrolling");
                scroll.setVvalue(scroll.getVmax());
                finishPendingLayout(root);
                requireFullyVisible(root.lookup("#copySetupLogs"));
            } finally {
                stage.close();
            }
        }
    }

    private static void verifyHelperInstallDialog(Stage owner, Path output) {
        for (String theme : new String[] {"Modena Light", "Modena Dark"}) {
            for (int fontSize : new int[] {12, 16, 20}) {
                var dialog = RuntimeHelperInstallDialog.create(owner);
                var pane = dialog.getDialogPane();
                try {
                    TrainingFormSmoke.applyQuPathStyle(theme, pane);
                    pane.setStyle("-fx-font-size: " + fontSize + "px;");
                    dialog.show();
                    pane.applyCss();
                    pane.layout();
                    var message = (Label) pane.getContent();
                    var rendered = (javafx.scene.text.Text) message.lookup(".text");
                    require(
                            rendered != null && rendered.getText().equals(message.getText()),
                            "Helper-installation explanation was shortened by the label skin");
                    require(
                            message.getHeight() + 1 >= message.prefHeight(message.getWidth()),
                            "Helper-installation explanation is clipped vertically");
                    require(dialog.isResizable(), "Helper-installation dialog cannot be resized");
                    require(
                            pane.getButtonTypes()
                                    .equals(
                                            java.util.List.of(
                                                    javafx.scene.control.ButtonType.YES,
                                                    javafx.scene.control.ButtonType.NO)),
                            "Helper-installation confirmation choices changed");
                    TrainingFormSmoke.capture(
                            pane,
                            output.resolve(
                                    "runtime-helper-"
                                            + (theme.endsWith("Light") ? "light" : "dark")
                                            + "-"
                                            + fontSize
                                            + ".png"));
                } finally {
                    dialog.close();
                }
            }
        }
    }

    private static void verifyUpdateGate(SettingsDialog settings, Stage owner) throws Exception {
        var block = SettingsDialog.class.getDeclaredMethod("blockSubmissionsForUpdate");
        var begin = SettingsDialog.class.getDeclaredMethod("beginAction");
        var failed =
                SettingsDialog.class.getDeclaredMethod(
                        "onHelperInstallFailed", qupath.ext.histoforge.cli.CliException.class);
        block.setAccessible(true);
        begin.setAccessible(true);
        failed.setAccessible(true);
        block.invoke(settings);
        require(
                owner.getScene().getRoot().isDisabled(),
                "New work remained available during helper preflight");
        begin.invoke(settings);
        begin.invoke(settings); // helper installation retry must retain the same maintenance gate
        require(owner.getScene().getRoot().isDisabled(), "Helper retry reopened submissions");
        failed.invoke(
                settings,
                new qupath.ext.histoforge.cli.CliException(
                        qupath.ext.histoforge.cli.CliException.Type.CLI_ERROR,
                        "injected helper failure"));
        require(
                !owner.getScene().getRoot().isDisabled(),
                "Failed helper left the main window disabled");
    }

    private static void showCompatibility(
            SettingsDialog settings, Parent root, BackendCompatibility.State state, String message)
            throws Exception {
        var checking = SettingsDialog.class.getDeclaredField("connectionChecking");
        checking.setAccessible(true);
        checking.set(settings, true);
        var apply =
                SettingsDialog.class.getDeclaredMethod(
                        "applyConnectionResult",
                        long.class,
                        java.util.Optional.class,
                        ConnectionProbe.Result.class);
        apply.setAccessible(true);
        var decision =
                new BackendCompatibility.Decision(
                        state,
                        java.util.Optional.of(ExtensionRelease.current().bundledBackendVersion()),
                        message);
        var deployment =
                new Deployment(
                        1,
                        "fixture",
                        "https://example.invalid",
                        "Research workspace",
                        "histoforge-gateway",
                        1,
                        "fixture-token",
                        java.time.Instant.parse("2026-09-29T00:00:00.000Z"));
        apply.invoke(
                settings,
                0L,
                java.util.Optional.of(deployment),
                new ConnectionProbe.Result(
                        decision.permitsWork()
                                ? ConnectionProbe.Status.CONNECTED
                                : ConnectionProbe.Status.INCOMPATIBLE,
                        java.util.Optional.of(decision),
                        true));
        require(
                ((Label) root.lookup("#cloudCompatibilityMessage")).getText().equals(message),
                "Compatibility guidance is missing");
        require(
                ((Label) root.lookup("#cloudBackendVersion"))
                        .getText()
                        .equals(decision.backendVersion().orElseThrow()),
                "Observed backend release is missing");
        require(
                ((Button) root.lookup("#updateCloudBackend")).isDisabled()
                        != decision.permitsCloudUpdate(),
                "Cloud update availability disagrees with the release policy");
        root.applyCss();
        root.layout();
    }

    private static void verifyInspectionRemainsAvailable() throws Exception {
        for (boolean inspectionSupported : new boolean[] {false, true}) {
            var loader = new FXMLLoader(MainController.class.getResource("main.fxml"));
            loader.load();
            MainController controller = loader.getController();
            var jobs = mock(JobsPoller.class);
            var modules = mock(ModulesRegistry.class);
            var jobsField = MainController.class.getDeclaredField("jobsPoller");
            jobsField.setAccessible(true);
            jobsField.set(controller, jobs);
            var modulesField = MainController.class.getDeclaredField("modulesRegistry");
            modulesField.setAccessible(true);
            modulesField.set(controller, modules);
            var apply =
                    MainController.class.getDeclaredMethod(
                            "applyConnectionStatus", ConnectionProbe.Result.class);
            apply.setAccessible(true);
            var decision =
                    new BackendCompatibility.Decision(
                            BackendCompatibility.State.UPDATE_REQUIRED,
                            java.util.Optional.of("0.0.0"),
                            "Update cloud backend before starting new work.");
            apply.invoke(
                    controller,
                    new ConnectionProbe.Result(
                            ConnectionProbe.Status.INCOMPATIBLE,
                            java.util.Optional.of(decision),
                            inspectionSupported));
            if (inspectionSupported) {
                org.mockito.Mockito.verify(jobs).start();
                org.mockito.Mockito.verify(modules).refresh();
            } else {
                verifyNoInteractions(jobs, modules);
            }
        }
    }
}
