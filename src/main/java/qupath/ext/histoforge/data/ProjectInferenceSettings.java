package qupath.ext.histoforge.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.projects.Project;

/** Custom halo choices owned and persisted by the current QuPath project. */
public final class ProjectInferenceSettings {

    private ProjectInferenceSettings() {}

    /** Read a detached snapshot. Missing entries mean the model's advertised default. */
    public static Map<String, Map<String, Integer>> read(Project<?> project) {
        String stored =
                Objects.requireNonNull(project, "project")
                        .getMetadata()
                        .get(HistoforgeKeys.INFERENCE_SETTINGS);
        if (stored == null) {
            return Map.of();
        }
        JsonElement parsed = CanonicalCodec.parseJson(stored);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Project inference settings must be an object");
        }
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (var module : parsed.getAsJsonObject().entrySet()) {
            String moduleRef = requireModuleRef(module.getKey());
            if (!module.getValue().isJsonObject()) {
                throw new IllegalArgumentException(
                        "Inference settings for " + moduleRef + " must be an object");
            }
            Map<String, Integer> models = new LinkedHashMap<>();
            for (var model : module.getValue().getAsJsonObject().entrySet()) {
                String modelId =
                        ModuleContract.requireModelId(model.getKey(), "inference settings model");
                models.put(
                        modelId,
                        CanonicalCodec.requireNonnegativeInt32(model.getValue(), "custom halo"));
            }
            if (!models.isEmpty()) {
                result.put(moduleRef, Map.copyOf(models));
            }
        }
        return Map.copyOf(result);
    }

    /**
     * Save only custom entries through QuPath. A project switch rejects the edit before mutation. A
     * failed sync keeps the intended in-memory values so an explicit project save can retry.
     */
    public static Optional<Throwable> save(
            Project<?> capturedProject,
            Project<?> currentProject,
            Map<String, Map<String, Integer>> overrides) {
        Objects.requireNonNull(capturedProject, "capturedProject");
        if (capturedProject != currentProject) {
            throw new IllegalStateException(
                    "The current project changed. Reopen Inference settings for the new project.");
        }
        JsonObject modules = new JsonObject();
        for (var module : overrides.entrySet()) {
            String moduleRef = requireModuleRef(module.getKey());
            JsonObject models = new JsonObject();
            for (var model : module.getValue().entrySet()) {
                String modelId =
                        ModuleContract.requireModelId(model.getKey(), "inference settings model");
                int halo = CanonicalCodec.requireNonnegativeInt32(model.getValue(), "custom halo");
                models.addProperty(modelId, halo);
            }
            if (!models.isEmpty()) {
                modules.add(moduleRef, models);
            }
        }
        Map<String, String> metadata = capturedProject.getMetadata();
        if (modules.isEmpty()) {
            metadata.remove(HistoforgeKeys.INFERENCE_SETTINGS);
        } else {
            metadata.put(
                    HistoforgeKeys.INFERENCE_SETTINGS,
                    new String(CanonicalCodec.canonicalBytes(modules), StandardCharsets.UTF_8));
        }
        try {
            capturedProject.syncChanges();
            return Optional.empty();
        } catch (IOException | RuntimeException failure) {
            // QuPath may already have persisted part of the project before a late sync failure.
            return Optional.of(failure);
        }
    }

    private static String requireModuleRef(String value) {
        return ModuleRef.parse(value)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Inference settings require a canonical module ref: "
                                                + value))
                .toString();
    }
}
