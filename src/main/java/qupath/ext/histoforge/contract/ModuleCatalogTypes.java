package qupath.ext.histoforge.contract;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAvailability;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingAvailable;
import qupath.ext.histoforge.modules.ModelSpec;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.modules.ModuleRecipe;

/** Immutable values carried by the exact module catalog contract. */
public final class ModuleCatalogTypes {

    private static final Gson GSON = new Gson();
    public static final int MAX_README_BYTES = 256 * 1024;
    public static final String BF16_OVERRIDE = "bf16";

    private ModuleCatalogTypes() {}

    /** Closed reason one schema-valid model cannot execute on this deployment. */
    public enum RunnerUnavailableCode {
        UNKNOWN_ARCHITECTURE,
        RUNNER_NOT_DEPLOYED,
        UNSUPPORTED_OUTPUT_TYPE,
        UNSUPPORTED_INPUT_FEATURE,
        UNSUPPORTED_MODEL_CONFIGURATION
    }

    /** Available capabilities or one closed unavailable result. */
    public sealed interface RunnerAvailability permits Available, Unavailable {}

    /** Exact deployed capabilities for one executable model. */
    public record Available(RunnerCapabilitiesCodec.RunnerCapabilities capabilities)
            implements RunnerAvailability {
        public Available {
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
        }
    }

    /** Coded reason one cataloged model cannot execute. */
    public record Unavailable(RunnerUnavailableCode code, String message)
            implements RunnerAvailability {
        public Unavailable {
            code = Objects.requireNonNull(code, "code");
            message = CanonicalCodec.requireNonblank(message, "message");
        }
    }

    /** Catalog-only alternative runner build and the GPUs on which it can execute. */
    public record PrecisionOverride(
            BuildIdentityCodec.SoftwareBuildIdentity runnerBuild, List<String> gpus) {
        public PrecisionOverride {
            runnerBuild = Objects.requireNonNull(runnerBuild, "runner_build");
            gpus = List.copyOf(gpus);
            if (gpus.isEmpty() || gpus.stream().distinct().count() != gpus.size()) {
                throw new IllegalArgumentException(
                        "precision override GPUs must be nonempty and unique");
            }
            gpus.forEach(gpu -> CanonicalCodec.requireNonblank(gpu, "precision override GPU"));
        }
    }

    /** Exact model-entry/artifact identity and current runner availability. */
    public record InstalledModelDescriptor(
            String modelConfigDigest,
            Map<String, String> artifactDigests,
            Optional<LargeRegionProfileCodec.LargeRegionProfile> largeRegionProfile,
            RunnerAvailability runnerAvailability,
            Map<String, PrecisionOverride> precisionOverrides,
            TrainingAvailability trainingAvailability) {
        public InstalledModelDescriptor {
            modelConfigDigest =
                    CanonicalCodec.requireSha256(modelConfigDigest, "model_config_digest");
            artifactDigests = immutableDigestMap(artifactDigests);
            largeRegionProfile = Objects.requireNonNull(largeRegionProfile, "large_region_profile");
            runnerAvailability = Objects.requireNonNull(runnerAvailability, "runner_availability");
            precisionOverrides = Map.copyOf(precisionOverrides);
            if (!Set.of(BF16_OVERRIDE).containsAll(precisionOverrides.keySet())) {
                throw new IllegalArgumentException("unsupported precision override");
            }
            if (!precisionOverrides.isEmpty()) {
                if (!(runnerAvailability instanceof Available available)) {
                    throw new IllegalArgumentException(
                            "unavailable runner cannot have precision overrides");
                }
                if (precisionOverrides
                        .get(BF16_OVERRIDE)
                        .runnerBuild()
                        .equals(available.capabilities().runnerBuild())) {
                    throw new IllegalArgumentException(
                            "precision override must differ from the native runner build");
                }
            }
            trainingAvailability =
                    Objects.requireNonNull(trainingAvailability, "training_availability");
            if (trainingAvailability instanceof TrainingAvailable
                    && !(runnerAvailability instanceof Available)) {
                throw new IllegalArgumentException(
                        "trainable catalog model requires an available runner");
            }
            if (largeRegionProfile.isPresent()
                    && (!(runnerAvailability instanceof Available available)
                            || !available
                                    .capabilities()
                                    .modes()
                                    .contains(
                                            RunnerCapabilitiesCodec.InferenceMode
                                                    .DURABLE_MODEL_RUN))) {
                throw new IllegalArgumentException(
                        "large_region_profile requires available durable capabilities");
            }
        }
    }

    /** Complete installed module descriptor retaining the exact raw recipe tree. */
    public record InstalledModuleDescriptor(
            InferenceIdentityCodec.ModuleContentIdentity identity,
            Instant installedAt,
            long installedModuleFileBytes,
            JsonObject recipe,
            Map<String, String> scripts,
            String scriptSetDigest,
            Map<String, InstalledModelDescriptor> models,
            Optional<String> readmeMarkdown) {
        public InstalledModuleDescriptor {
            identity = Objects.requireNonNull(identity, "identity");
            installedAt = Objects.requireNonNull(installedAt, "installed_at");
            CanonicalCodec.rfc3339Millis(installedAt, "installed_at");
            installedModuleFileBytes =
                    CanonicalCodec.requireNonnegativeSafeInteger(
                            installedModuleFileBytes, "installed_module_file_bytes");
            recipe = Objects.requireNonNull(recipe, "recipe").deepCopy();
            scripts = immutableStringMap(scripts, "scripts");
            scriptSetDigest = CanonicalCodec.requireSha256(scriptSetDigest, "script_set_digest");
            models = Map.copyOf(Objects.requireNonNull(models, "models"));
            readmeMarkdown = Objects.requireNonNull(readmeMarkdown, "readme_markdown");
            if (readmeMarkdown.isPresent()) {
                if (identity.resolvedCommitSha().isEmpty()) {
                    throw new IllegalArgumentException(
                            "saved README requires a resolved HF commit");
                }
                if (CanonicalCodec.utf8Bytes(readmeMarkdown.orElseThrow()).length
                        > MAX_README_BYTES) {
                    throw new IllegalArgumentException(
                            "README.md exceeds the 256 KiB module-card limit");
                }
            }
        }

        /**
         * Typed projection for current recipe consumers; exact identity stays in {@link #recipe}.
         */
        public ModuleRecipe typedRecipe() {
            return GSON.fromJson(recipe, ModuleRecipe.class);
        }

        /** Defensive copy of the exact recipe tree retained for identity checks. */
        @Override
        public JsonObject recipe() {
            return recipe.deepCopy();
        }

        /** Derive the exact backend identity a finite request must echo for one model. */
        public InferenceIdentityCodec.BackendModelIdentity backendIdentity(String modelId) {
            String canonicalId = ModuleContract.requireModelId(modelId, "model_id");
            InstalledModelDescriptor descriptor = models.get(canonicalId);
            if (descriptor == null) {
                throw new IllegalArgumentException("module has no model " + canonicalId);
            }
            if (!(descriptor.runnerAvailability() instanceof Available available)) {
                throw new IllegalStateException("model is unavailable: " + canonicalId);
            }
            ModelSpec model = typedRecipe().models().get(canonicalId);
            var capabilities = available.capabilities();
            return new InferenceIdentityCodec.BackendModelIdentity(
                    identity,
                    canonicalId,
                    model.arch(),
                    model.gpu(),
                    descriptor.modelConfigDigest(),
                    descriptor.artifactDigests(),
                    capabilities.runnerBuild(),
                    capabilities.rasterizer(),
                    capabilities.preprocessingContractDigest());
        }
    }

    /** Stable module-ref-sorted page and opaque exclusive continuation cursor. */
    public record ModuleCatalogPage(
            List<InstalledModuleDescriptor> modules, Optional<String> nextCursor) {
        public ModuleCatalogPage {
            modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
            nextCursor = Objects.requireNonNull(nextCursor, "next_cursor");
            nextCursor =
                    nextCursor.map(value -> CanonicalCodec.requireNonblank(value, "next_cursor"));
            if (modules.isEmpty() && nextCursor.isPresent()) {
                throw new IllegalArgumentException("empty page cannot have next_cursor");
            }
            List<String> refs =
                    modules.stream().map(item -> item.identity().moduleRef().toString()).toList();
            if (!refs.equals(refs.stream().distinct().sorted().toList())) {
                throw new IllegalArgumentException("catalog modules must be unique and ref-sorted");
            }
        }
    }

    static Map<String, String> immutableStringMap(Map<String, String> values, String path) {
        Objects.requireNonNull(values, path);
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) ->
                        result.put(
                                CanonicalCodec.requireNonblank(key, path + " key"),
                                Objects.requireNonNull(value, path + "." + key)));
        return Map.copyOf(result);
    }

    static Map<String, String> immutableDigestMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("artifact_digests must be nonempty");
        }
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) -> {
                    String role = CanonicalCodec.requireNonblank(key, "artifact role");
                    result.put(
                            role, CanonicalCodec.requireSha256(value, "artifact_digests." + role));
                });
        return Map.copyOf(result);
    }
}
