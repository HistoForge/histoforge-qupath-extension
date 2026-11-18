package qupath.ext.histoforge.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.Group;
import org.junit.jupiter.api.Test;

class HistoforgeStylesTest {

    @Test
    void semanticStateReplacesPriorStateOnRecycledNode() {
        Group node = new Group();
        node.getStyleClass().add(HistoforgeStyles.CONNECTION_INDICATOR);

        HistoforgeStyles.setSemantic(node, HistoforgeStyles.ERROR);
        HistoforgeStyles.setSemantic(node, HistoforgeStyles.SUCCESS);

        assertFalse(node.getStyleClass().contains(HistoforgeStyles.ERROR));
        assertTrue(node.getStyleClass().contains(HistoforgeStyles.SUCCESS));
        assertTrue(node.getStyleClass().contains(HistoforgeStyles.CONNECTION_INDICATOR));
    }

    @Test
    void sharedStylesheetIsPackaged() {
        assertNotNull(HistoforgeStyles.class.getResource("histoforge.css"));
    }
}
