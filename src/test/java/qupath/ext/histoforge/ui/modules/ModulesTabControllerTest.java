package qupath.ext.histoforge.ui.modules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModulesTabControllerTest {

    @Test
    void workspaceTrainingResultHasNoHuggingFaceRecoveryClaim() {
        String ref = "workspace/run-44444444-4444-4444-8444-444444444444";

        assertFalse(ModulesTabController.canViewOnHuggingFace(ref));
        String message = ModulesTabController.deleteConfirmation(ref);
        assertTrue(message.contains("may be the only copy"), message);
        assertFalse(message.contains("reinstall"), message);
    }

    @Test
    void installedHuggingFaceModuleKeepsItsSourceAction() {
        String ref = "histoforge/leydig";

        assertTrue(ModulesTabController.canViewOnHuggingFace(ref));
        assertTrue(ModulesTabController.deleteConfirmation(ref).contains("reinstall"));
    }
}
