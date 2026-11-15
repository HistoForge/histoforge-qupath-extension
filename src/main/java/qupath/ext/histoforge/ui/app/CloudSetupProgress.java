package qupath.ext.histoforge.ui.app;

import java.net.URI;
import java.util.Locale;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.lib.gui.QuPathGUI;

/** Short setup stages, an explicit browser-login link, and optional diagnostic output. */
final class CloudSetupProgress {
    private final Label status;
    private final TextArea log = new TextArea();
    private final Button copy = new Button("Copy logs");
    private final TitledPane details = new TitledPane("Technical details", new VBox(6, log, copy));
    private final Hyperlink login = new Hyperlink("Open Modal login in your browser");
    private final VBox node = new VBox(6, login, details);
    private URI loginUri;

    CloudSetupProgress(Label status) {
        this.status = status;
        log.setId("setupTechnicalLog");
        log.setEditable(false);
        log.setPrefRowCount(6);
        log.setMinHeight(Region.USE_PREF_SIZE);
        log.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
        copy.setId("copySetupLogs");
        copy.setTooltip(new Tooltip("Copy all setup and deployment details to the clipboard."));
        copy.disableProperty().bind(log.textProperty().isEmpty());
        copy.setOnAction(
                event -> {
                    var content = new ClipboardContent();
                    content.putString(log.getText());
                    Clipboard.getSystemClipboard().setContent(content);
                });
        VBox.setVgrow(log, Priority.ALWAYS);
        details.setId("setupTechnicalDetails");
        details.setAnimated(false);
        details.setExpanded(false);
        details.maxHeightProperty()
                .bind(
                        Bindings.when(details.expandedProperty())
                                .then(Double.MAX_VALUE)
                                .otherwise(Region.USE_PREF_SIZE));
        login.setId("modalLoginLink");
        login.setTooltip(new Tooltip("Use this link if the sign-in browser did not open."));
        login.setOnAction(event -> QuPathGUI.openInBrowser(loginUri.toString()));
        VBox.setVgrow(details, Priority.ALWAYS);
        reset();
    }

    VBox getNode() {
        return node;
    }

    void reset() {
        log.clear();
        details.setExpanded(false);
        hideLoginLink();
        node.setVisible(false);
        node.setManaged(false);
    }

    void hideLoginLink() {
        loginUri = null;
        login.setVisible(false);
        login.setManaged(false);
    }

    void appendLine(String line) {
        log.appendText(line + "\n");
        if (line.isBlank()) return;
        node.setVisible(true);
        node.setManaged(true);
        URI uri = loginUri(line);
        if (uri != null) {
            loginUri = uri;
            login.setVisible(true);
            login.setManaged(true);
        }
        String message = statusMessage(line);
        if (message != null) {
            HistoforgeStyles.setSemantic(status, HistoforgeStyles.BUSY);
            status.setText(message);
        }
    }

    static String statusMessage(String line) {
        String text = line.strip().toLowerCase(Locale.ROOT);
        if (text.contains("opening a browser")) return "Finish Modal login in your browser…";
        if (text.startsWith("deploying the bundled cloud backend")) return "Deploying backend…";
        if (text.startsWith("verifying the updated cloud backend"))
            return "Checking backend connection…";
        if (text.startsWith("stopping local ")) return "Finishing local work…";
        if (text.startsWith("stopping ")) return "Stopping cloud work…";
        if (text.startsWith("waiting for ")) return "Waiting for active work to finish…";
        return null;
    }

    static URI loginUri(String line) {
        String text = line.strip();
        if (!text.startsWith("https://modal.com/token-flow/")) return null;
        try {
            URI uri = URI.create(text);
            return "modal.com".equals(uri.getHost())
                            && uri.getRawUserInfo() == null
                            && uri.getPort() == -1
                    ? uri
                    : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
