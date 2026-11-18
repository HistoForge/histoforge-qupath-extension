package qupath.ext.histoforge.ui.app;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import qupath.fx.dialogs.Dialogs;

/** The helper-installation prompt shared by first-time setup and Settings. */
final class RuntimeHelperInstallDialog {
    private RuntimeHelperInstallDialog() {}

    static boolean confirm(Window owner) {
        return create(owner).showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    static Dialog<ButtonType> create(Window owner) {
        Label message =
                new Label(
                        "HistoForge needs a private helper on this computer to connect to Modal.\n\n"
                                + "It installs the required Python runtime and HistoForge tools. "
                                + "It includes no model weights.\n\n"
                                + "Install it now?");
        message.setWrapText(true);
        message.setPrefWidth(440);
        message.setMaxWidth(Double.MAX_VALUE);
        message.setMinHeight(Region.USE_PREF_SIZE);

        Dialog<ButtonType> dialog =
                Dialogs.builder()
                        .alertType(Alert.AlertType.NONE)
                        .title("Install runtime helper")
                        .owner(owner)
                        .content(message)
                        .buttons(ButtonType.YES, ButtonType.NO)
                        .prefWidth(480)
                        .resizable()
                        .build();
        dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        return dialog;
    }
}
