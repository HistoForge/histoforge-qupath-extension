package qupath.ext.histoforge.roi;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.controlsfx.tools.Duplicatable;
import qupath.lib.gui.QuPathGUI;

/** QuPath toolbar graphic with a separate Node for each toolbar/menu use. */
public final class ToolIcons {
    private ToolIcons() {}

    public static Node createRoiToolIcon() {
        return new RoiIcon();
    }

    private static final class RoiIcon extends Label implements Duplicatable<Node> {
        RoiIcon() {
            int size = QuPathGUI.TOOLBAR_ICON_SIZE;
            var outline = new Rectangle(size - 2, size - 2, Color.TRANSPARENT);
            outline.setStyle("-fx-stroke: -fx-text-fill; -fx-stroke-width: 1.5;");
            var text = new Label("ROI");
            text.setStyle("-fx-font-size: 7px; -fx-font-weight: bold;");
            var graphic = new StackPane(outline, text);
            graphic.setMinSize(size, size);
            graphic.setPrefSize(size, size);
            graphic.setMaxSize(size, size);
            graphic.getStyleClass().add("qupath-icon");
            setGraphic(graphic);
        }

        @Override
        public Node duplicate() {
            return new RoiIcon().getGraphic();
        }
    }
}
