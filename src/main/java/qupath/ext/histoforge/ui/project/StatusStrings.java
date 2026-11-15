package qupath.ext.histoforge.ui.project;

import java.util.regex.Pattern;

/** Shared Project-table display vocabulary used by status producers and cell renderers. */
final class StatusStrings {

    private StatusStrings() {}

    static final String OUTPUT_PRESENT = "Output present";
    static final String OUTPUT_STATE_INVALID = "Output state invalid";
    static final String SAVED = "Saved";
    static final String NO_HIERARCHY_CHANGE = "No hierarchy change";
    static final String CANCELLED = "Cancelled";
    static final String FAILED_PREFIX = "Failed:";
    static final String PARTIAL_PREFIX = "Partial:";
    static final String CONFLICT_PREFIX = "Conflict:";
    static final Pattern OUTPUT_PRESENCE = Pattern.compile("Output present (\\d+)/(\\d+)");
}
