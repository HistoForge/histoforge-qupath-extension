package qupath.ext.histoforge.ui;

import java.util.List;
import java.util.Optional;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.StringProperty;
import qupath.ext.histoforge.modules.InferencePrecision;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;
import qupath.lib.gui.prefs.PathPrefs;

/** Sole owner of HistoForge user preferences stored by QuPath. */
public final class HistoforgePrefs {

    /** Empty persisted value means that each model's recipe chooses its inference GPU. */
    public static final String USE_RECIPE_INFERENCE_GPU = "";

    private static final BooleanProperty REMOVE_OVERLAPPING_SAME_CLASS_DUPLICATES =
            PathPrefs.createPersistentPreference(
                    "histoforge.removeOverlappingSameClassDuplicates", true);
    private static final ObjectProperty<LocalResourceEnvelope> LOCAL_RESOURCE_ENVELOPE =
            PathPrefs.createPersistentPreference(
                    "histoforge.localResourceEnvelope",
                    LocalResourceEnvelope.SAFE,
                    LocalResourceEnvelope.class);
    private static final StringProperty DEFAULT_INFERENCE_GPU =
            PathPrefs.createPersistentPreference(
                    "histoforge.defaultInferenceGpu", USE_RECIPE_INFERENCE_GPU);
    private static final ObjectProperty<InferencePrecision> INFERENCE_PRECISION =
            PathPrefs.createPersistentPreference(
                    "histoforge.inferencePrecision",
                    InferencePrecision.BF16_IF_APPLICABLE,
                    InferencePrecision.class);

    private HistoforgePrefs() {}

    /**
     * Whether a Fill/Redo action suppresses new same-class model candidates that overlap an
     * unattributed existing object by positive area. The action snapshots this value at start.
     */
    public static BooleanProperty removeOverlappingSameClassDuplicatesProperty() {
        return REMOVE_OVERLAPPING_SAME_CLASS_DUPLICATES;
    }

    public static boolean removeOverlappingSameClassDuplicates() {
        return REMOVE_OVERLAPPING_SAME_CLASS_DUPLICATES.get();
    }

    /** The explicit hierarchy/metric envelope snapshotted by each newly started action. */
    public static ObjectProperty<LocalResourceEnvelope> localResourceEnvelopeProperty() {
        return LOCAL_RESOURCE_ENVELOPE;
    }

    public static LocalResourceEnvelope localResourceEnvelope() {
        return LOCAL_RESOURCE_ENVELOPE.get();
    }

    /** Modal inference GPU preference; blank means to preserve each recipe's setting. */
    public static StringProperty defaultInferenceGpuProperty() {
        return DEFAULT_INFERENCE_GPU;
    }

    /** Global preference used only when capturing a new Fill/Redo action. */
    public static ObjectProperty<InferencePrecision> inferencePrecisionProperty() {
        return INFERENCE_PRECISION;
    }

    public static InferencePrecision inferencePrecision() {
        return INFERENCE_PRECISION.get();
    }

    /** Resolve one action's override against the exact gateway capability it will use. */
    public static Optional<String> inferenceGpuOverride(List<String> supported) {
        List<String> choices = List.copyOf(supported);
        String selected = DEFAULT_INFERENCE_GPU.get();
        if (selected == null || selected.isBlank()) {
            return Optional.empty();
        }
        if (!choices.contains(selected)) {
            throw new IllegalStateException(
                    "Default inference GPU "
                            + selected
                            + " is not supported by the current deployment. Change it in Settings.");
        }
        return Optional.of(selected);
    }
}
