package qupath.ext.histoforge.ui;

import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Popup;

/** Compact help control: hover previews the text and click pins it until dismissed. */
public final class InformationButton extends Button {
    private final HistoforgeTooltip preview = new HistoforgeTooltip();
    private final Label content = new Label();
    private final Popup popup = new Popup();

    public InformationButton(String text) {
        super("ⓘ");
        setAccessibleText("Information");
        setFocusTraversable(true);
        setMinWidth(USE_PREF_SIZE);
        getStyleClass().add("histoforge-info-button");

        preview.setWrapText(true);
        preview.setMaxWidth(420);
        setTooltip(preview);

        content.setWrapText(true);
        content.setMaxWidth(400);
        StackPane surface = new StackPane(content);
        surface.getStylesheets()
                .add(InformationButton.class.getResource("histoforge.css").toExternalForm());
        surface.getStyleClass().addAll("histoforge-root", "histoforge-info-surface");
        popup.getContent().setAll(surface);
        popup.setAutoFix(true);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setOnHidden(event -> setTooltip(preview));
        setOnAction(event -> togglePopup());
        setTextContent(text);
    }

    public void setTextContent(String text) {
        String resolved = text == null ? "" : text;
        preview.setText(resolved);
        content.setText(resolved);
        setAccessibleText(resolved.isEmpty() ? "Information" : "Information: " + resolved);
    }

    private void togglePopup() {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        preview.hide();
        setTooltip(null);
        Bounds bounds = localToScreen(getBoundsInLocal());
        if (bounds != null) {
            popup.show(this, bounds.getMinX(), bounds.getMaxY() + 4);
        } else {
            setTooltip(preview);
        }
    }
}
