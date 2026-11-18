package qupath.ext.histoforge.ui.modules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PublishDialogTest {

    @Test
    void localSyncIsNotOfferedForRunDerivedWorkspaceModules() {
        assertFalse(
                PublishDialog.offersLocalSync(
                        "workspace/run-44444444-4444-4444-8444-444444444444"));
        assertTrue(PublishDialog.offersLocalSync("owner/module"));
    }

    @Test
    void validTargetRemainsDisabledWhileSubmissionIsInFlight() {
        assertFalse(PublishDialog.publishDisabled(false, "owner/module"));
        assertTrue(PublishDialog.publishDisabled(true, "owner/module"));
        assertTrue(PublishDialog.publishDisabled(false, "not-a-module-ref"));
    }
}
