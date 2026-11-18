package qupath.ext.histoforge.util;

/**
 * User-authored ROI and project-assignment keys. Scientific provenance, generation evidence, and
 * commit witnesses are owned separately by {@link
 * qupath.ext.histoforge.contract.HistoforgePersistenceKeys}.
 */
public final class HistoforgeKeys {

    private HistoforgeKeys() {}

    // ── ROI identity + grouping ─────────────────────────────────────
    /** Marks a PathObject as a HistoForge ROI (value {@link #ROI_TYPE_VALUE}). */
    public static final String ROI_TYPE = "histoforge:type";

    /** The value stored under {@link #ROI_TYPE}. */
    public static final String ROI_TYPE_VALUE = "roi";

    /** Per-image treatment group name. */
    public static final String GROUP = "histoforge:group";

    // ── Recipe assignment ───────────────────────────────────────────
    /** Module ref assigned to a project image for explicit actions. */
    public static final String MODULE = "histoforge:module";

    /** Project metadata containing custom model-inference choices, keyed by module and model. */
    public static final String INFERENCE_SETTINGS = "histoforge:inference-settings";

    public static final String INFERENCE_SCOPE = "histoforge:inference-scope";
    public static final String INFERENCE_ANNOTATION_CLASS = "histoforge:inference-annotation-class";
}
