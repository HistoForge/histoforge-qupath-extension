package qupath.ext.histoforge.modules;

import java.util.List;
import java.util.Map;

/**
 * A module's recipe — the self-contained {@code {models, steps}} contract, decoded from the recipe
 * served inline in {@code GET /v1/modules}. Authoritative shape: the backend's {@code
 * custom_docs/contracts/modules.md} (code is truth on any conflict with this doc comment).
 *
 * <p>The display header and provenance fields fold in; there is no separate manifest. {@code
 * models} is keyed by LOCAL model-id; {@code steps} reference those local ids only (never external
 * refs), so there is no cross-module resolution. "model" vs "pipeline" is a shape (one step vs
 * many), not a stored type.
 */
public record ModuleRecipe(
        int schemaVersion,
        String name,
        String description,
        String upstream,
        String trainingReportDigest,
        Map<String, ModelSpec> models,
        List<Step> steps) {

    /** Null-safe view of the models map (Gson can leave it null on a malformed recipe). */
    public Map<String, ModelSpec> models() {
        return models == null ? Map.of() : models;
    }

    /** Null-safe view of the steps list. */
    public List<Step> steps() {
        return steps == null ? List.of() : steps;
    }

    /**
     * One execution step. Exactly one of {@code model} / {@code script} is set. A model step that
     * also carries a {@code parentModel} is region-gated (the two-wave executor, 7.K): run the
     * child model on each parent-model instance whose class is in {@code parentClass}.
     */
    public record Step(String model, String parentModel, List<String> parentClass, String script) {

        public boolean isModelStep() {
            return model != null;
        }

        public boolean isRegionGated() {
            return model != null && parentModel != null;
        }

        public boolean isScriptStep() {
            return script != null;
        }
    }
}
