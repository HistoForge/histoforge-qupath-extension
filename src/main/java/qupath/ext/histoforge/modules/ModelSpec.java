package qupath.ext.histoforge.modules;

import java.util.Map;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;

/**
 * One servable model within a module — its runner {@code arch}, weights source, and I/O contract.
 * Keyed by local id in {@link ModuleRecipe#models()} and decoded from the recipe served inline in
 * {@code GET /v1/modules}. Inference addresses it as {@code <module-ref>/<model-id>}; the gateway
 * picks the runner by {@code arch}.
 */
public record ModelSpec(
        String arch,
        String gpu,
        WeightsSpec weights,
        InputSpec input,
        ModelOutput output,
        Map<String, Object> params,
        ModelSpec backbone) {

    /**
     * Where a model's weights come from.
     *
     * @param type {@code "vendored"} (a {@code file} in the module repo), {@code "referenced"} (an
     *     external SHA-pinned {@code ref}), or {@code "local"} (a Volume working form)
     * @param file vendored weight filename (vendored only)
     * @param ref external HF repo (referenced only)
     * @param revision pinned commit for {@code ref} (referenced only)
     */
    public record WeightsSpec(String type, String file, String ref, String revision) {}

    /**
     * Input contract: how the client shapes pixels for this model.
     *
     * @param type {@code "field"} (whole region) or {@code "crop"} (cut from a parent instance)
     * @param micronsPerPixel mpp the model was trained at
     * @param fieldProfile exact variable-field planner, or null for fixed neural fields
     * @param largeRegion processing-core dimensions and default halo, or null when not declared
     * @param framing crop-model padding rules; null for {@code field}
     */
    public record InputSpec(
            String type,
            double micronsPerPixel,
            VariableFieldProfile fieldProfile,
            LargeRegion largeRegion,
            Framing framing) {}

    /** Nonoverlapping processing cores and context per side, all in model-resolution pixels. */
    public record LargeRegion(
            int coreWidthModelPx, int coreHeightModelPx, int haloPerSideModelPx) {}

    /** Crop framing: add this many pixels of context at the model's target MPP. */
    public record Framing(int paddingModelPx) {}
}
