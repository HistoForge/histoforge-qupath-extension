package qupath.ext.histoforge.ui;

import javafx.scene.control.Tooltip;
import javafx.util.Duration;

/** Keep help visible while the pointer rests on a HistoForge control. */
public final class HistoforgeTooltip extends Tooltip {
    public HistoforgeTooltip() {
        setShowDuration(Duration.INDEFINITE);
    }

    public HistoforgeTooltip(String text) {
        this();
        setText(text);
    }
}
