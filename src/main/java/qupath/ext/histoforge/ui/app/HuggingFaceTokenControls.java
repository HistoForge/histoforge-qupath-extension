package qupath.ext.histoforge.ui.app;

import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Shared entry-only control. Its parent owns work, errors and clearing on window close. */
final class HuggingFaceTokenControls extends HBox {
    private final PasswordField token = new PasswordField();

    HuggingFaceTokenControls(Consumer<String> onSave) {
        super(6);
        setAlignment(Pos.CENTER_LEFT);
        token.setId("huggingFaceToken");
        token.setPromptText("Enter or replace token");
        token.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(token, Priority.ALWAYS);
        var help =
                new Tooltip(
                        "Allows your cloud backend to download and publish modules. "
                                + "Saved in your Modal workspace, never in this project or QuPath settings. "
                                + "The token must have permission for the repositories you use.");
        help.setWrapText(true);
        help.setMaxWidth(420);
        token.setTooltip(help);
        Button save = new Button("Save token");
        save.setId("saveHuggingFaceToken");
        save.setMinWidth(USE_PREF_SIZE);
        save.setTooltip(new Tooltip("Verify this token with Hugging Face, then save it to Modal."));
        save.disableProperty().bind(token.textProperty().isEmpty());
        save.setOnAction(
                event -> {
                    String entered = token.getText().strip();
                    clear();
                    if (!entered.isEmpty()) {
                        onSave.accept(entered);
                    }
                });
        getChildren().addAll(token, save);
    }

    void clear() {
        token.clear();
    }
}
