package qupath.ext.histoforge.modules;

/** User intent for new inference actions; each model captures its resolved runner build. */
public enum InferencePrecision {
    DEFAULT("Default"),
    BF16_IF_APPLICABLE("BF16 (if applicable)");

    private final String displayName;

    InferencePrecision(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
