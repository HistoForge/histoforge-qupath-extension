package qupath.ext.histoforge.ui;

import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;

/** Shared stylesheet and semantic UI states for every HistoForge-owned scene. */
public final class HistoforgeStyles {

    public static final String MUTED = "histoforge-muted";
    public static final String HEADING = "histoforge-heading";
    public static final String SUCCESS = "histoforge-success";
    public static final String NEUTRAL = "histoforge-neutral";
    public static final String WARNING = "histoforge-warning";
    public static final String ERROR = "histoforge-error";
    public static final String BUSY = "histoforge-busy";
    public static final String CLICKABLE = "histoforge-clickable";
    public static final String CONNECTION_INDICATOR = "histoforge-connection-indicator";

    private static final String ROOT = "histoforge-root";
    private static final String STYLESHEET =
            HistoforgeStyles.class.getResource("histoforge.css").toExternalForm();
    private static final List<String> SEMANTIC_STYLES =
            List.of(SUCCESS, NEUTRAL, WARNING, ERROR, BUSY);

    private HistoforgeStyles() {}

    /** Apply the HistoForge stylesheet once to a scene and mark its root as the style owner. */
    public static void apply(Scene scene) {
        if (!scene.getStylesheets().contains(STYLESHEET)) {
            scene.getStylesheets().add(STYLESHEET);
        }
        Parent root = scene.getRoot();
        if (!root.getStyleClass().contains(ROOT)) {
            root.getStyleClass().add(ROOT);
        }
    }

    /** Replace a node's semantic state without retaining state from a recycled table cell. */
    public static void setSemantic(Node node, String styleClass) {
        node.getStyleClass().removeAll(SEMANTIC_STYLES);
        if (styleClass != null && !node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }

    /** Add or remove the shared hand-cursor affordance. */
    public static void setClickable(Node node, boolean clickable) {
        if (clickable) {
            if (!node.getStyleClass().contains(CLICKABLE)) {
                node.getStyleClass().add(CLICKABLE);
            }
        } else {
            node.getStyleClass().remove(CLICKABLE);
        }
    }
}
