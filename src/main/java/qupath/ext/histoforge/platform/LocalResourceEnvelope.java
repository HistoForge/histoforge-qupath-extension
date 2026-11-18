package qupath.ext.histoforge.platform;

/** Explicit count allowances for new local actions, not estimates or allocations of RAM. */
public enum LocalResourceEnvelope {
    SAFE(1, "Standard"),
    DOUBLE(2, "Large"),
    FOURFOLD(4, "Very large"),
    // Keep the saved preference identifier; only geometry and Metrics still use its 8x scale.
    EIGHTFOLD(8, 32, 80, 64, "High capacity");

    private final int multiplier;
    private final int objectMultiplier;
    private final int measurementMultiplier;
    private final int textMultiplier;
    private final String displayName;

    LocalResourceEnvelope(int multiplier, String displayName) {
        this(multiplier, multiplier, multiplier, multiplier, displayName);
    }

    LocalResourceEnvelope(
            int multiplier,
            int objectMultiplier,
            int measurementMultiplier,
            int textMultiplier,
            String displayName) {
        this.multiplier = multiplier;
        this.objectMultiplier = objectMultiplier;
        this.measurementMultiplier = measurementMultiplier;
        this.textMultiplier = textMultiplier;
        this.displayName = displayName;
    }

    /** Geometry and computed Metrics retain the original preset scales. */
    public int multiplier() {
        return multiplier;
    }

    public int objectMultiplier() {
        return objectMultiplier;
    }

    public int measurementMultiplier() {
        return measurementMultiplier;
    }

    public int textMultiplier() {
        return textMultiplier;
    }

    public String displayName() {
        return displayName;
    }
}
