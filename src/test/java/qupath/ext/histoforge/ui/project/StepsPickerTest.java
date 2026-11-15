package qupath.ext.histoforge.ui.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StepsPickerTest {

    @Test
    void operationStateCanBlockAndReenableTheSameModule() {
        assertFalse(StepsPicker.shouldDisable(false, true, 1));
        assertTrue(StepsPicker.shouldDisable(true, true, 1));
        assertFalse(StepsPicker.shouldDisable(false, true, 1));
    }

    @Test
    void absentModuleOrModelStepsRemainDisabled() {
        assertTrue(StepsPicker.shouldDisable(false, false, 1));
        assertTrue(StepsPicker.shouldDisable(false, true, 0));
    }
}
