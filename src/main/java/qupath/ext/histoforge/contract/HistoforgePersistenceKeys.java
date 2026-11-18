package qupath.ext.histoforge.contract;

/** Exact keys for HistoForge values persisted through QuPath public properties. */
public final class HistoforgePersistenceKeys {

    /** PathObject metadata key containing canonical {@link ProvenanceCodec.ObjectProvenance}. */
    public static final String OBJECT_PROVENANCE = "histoforge:provenance";

    /** ImageData property key containing the canonical generation store. */
    public static final String GENERATION_STORE = "histoforge:generation-store";

    /** ImageData property key containing the operational large-action commit witness. */
    public static final String ACTION_COMMIT_WITNESS = "histoforge:action-commit-witness";

    private HistoforgePersistenceKeys() {}
}
