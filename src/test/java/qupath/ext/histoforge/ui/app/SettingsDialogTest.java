package qupath.ext.histoforge.ui.app;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettingsDialogTest {

    @Test
    void precisionHelpExplainsApplicabilityAndFutureActions() {
        String help = SettingsDialog.INFERENCE_PRECISION_HELP;
        assertAll(
                () -> assertTrue(help.contains("checkpoint precision")),
                () -> assertTrue(help.contains("model and GPU support")),
                () -> assertTrue(help.contains("otherwise it uses Default")),
                () -> assertTrue(help.contains("can change numerical results")),
                () -> assertTrue(help.contains("New Fill/Redo actions only")),
                () -> assertTrue(help.contains("training are unchanged")));
    }

    @Test
    void updateConfirmationExplainsCancellationAndPreservation() {
        String text = SettingsDialog.cloudUpdateConfirmation();
        assertAll(
                () -> assertTrue(text.contains("must be started again")),
                () -> assertTrue(text.contains("wait for any result save")),
                () ->
                        assertTrue(
                                text.contains(
                                        "Saved results, datasets, installed modules and annotations are kept")),
                () -> assertTrue(text.contains("this same Modal workspace")));
    }

    @Test
    void capacityHelpDistinguishesCountAllowancesFromTheJavaMemoryLimit() {
        String help = SettingsDialog.capacityHelp(8L * 1_073_741_824L);

        assertAll(
                () -> assertTrue(help.contains("Limits the number and complexity")),
                () -> assertTrue(help.contains("do not reserve RAM")),
                () -> assertFalse(help.contains("GiB values are memory estimates")),
                () -> assertTrue(help.contains("QuPath's Java memory limit is currently 8.0 GiB")),
                () -> assertTrue(help.contains("uses computer memory outside this limit")),
                () -> assertTrue(help.contains("or change model accuracy")),
                () -> assertTrue(help.contains("New settings apply to new actions only")));
    }
}
