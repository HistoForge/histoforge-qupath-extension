package qupath.ext.histoforge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.modules.InferencePrecision;
import qupath.lib.gui.prefs.PathPrefs;

class HistoforgePrefsTest {

    @Test
    void precisionStartsWithBf16IfApplicableAndPersistsExplicitDefault() {
        var property = HistoforgePrefs.inferencePrecisionProperty();
        var previous = property.get();
        assertEquals(InferencePrecision.BF16_IF_APPLICABLE, previous);
        try {
            property.set(InferencePrecision.DEFAULT);
            assertEquals(InferencePrecision.DEFAULT, HistoforgePrefs.inferencePrecision());
            var reloaded =
                    PathPrefs.createPersistentPreference(
                            property.getName(),
                            InferencePrecision.BF16_IF_APPLICABLE,
                            InferencePrecision.class);
            assertEquals(InferencePrecision.DEFAULT, reloaded.get());
        } finally {
            property.set(previous);
        }
    }

    @Test
    void inferenceGpuPreferenceResolvesOnceAgainstCurrentCapabilities() {
        String previous = HistoforgePrefs.defaultInferenceGpuProperty().get();
        try {
            HistoforgePrefs.defaultInferenceGpuProperty()
                    .set(HistoforgePrefs.USE_RECIPE_INFERENCE_GPU);
            assertTrue(HistoforgePrefs.inferenceGpuOverride(List.of("A10G", "A100")).isEmpty());

            HistoforgePrefs.defaultInferenceGpuProperty().set("A100");
            assertEquals(
                    "A100",
                    HistoforgePrefs.inferenceGpuOverride(List.of("A10G", "A100")).orElseThrow());

            HistoforgePrefs.defaultInferenceGpuProperty().set("T4");
            assertThrows(
                    IllegalStateException.class,
                    () -> HistoforgePrefs.inferenceGpuOverride(List.of("A10G", "A100")));
        } finally {
            HistoforgePrefs.defaultInferenceGpuProperty().set(previous);
        }
    }
}
