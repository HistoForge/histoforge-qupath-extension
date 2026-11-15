package qupath.ext.histoforge.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.contract.CanonicalCodec;

/** Exact recipe/script identities built after module-content validation. */
public final class ModuleContract {

    /** Reserved recipe filename for the one pure metric script. */
    public static final String METRICS_SCRIPT_NAME = "metrics.groovy";

    private ModuleContract() {}

    /** Validate one schema-owned local model identifier. */
    public static String requireModelId(String value, String path) {
        return ModuleRecipeValidator.requireModelId(value, path);
    }

    /** Validate one immutable full lowercase Git commit SHA. */
    public static String requireCommitSha(String value, String path) {
        return ModuleRecipeValidator.requireCommitSha(value, path);
    }

    /** Validate one schema-owned model output type. */
    public static String requireOutputType(String value, String path) {
        return ModuleRecipeValidator.requireOutputType(value, path);
    }

    /** Return the run ID only for the reserved private training-result module ref. */
    public static Optional<String> workspaceTrainingRunId(ModuleRef moduleRef) {
        if (moduleRef == null
                || !"workspace".equals(moduleRef.owner())
                || !moduleRef.name().startsWith("run-")) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    CanonicalCodec.requireCanonicalUuid(
                            moduleRef.name().substring("run-".length()), "run_id"));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    /**
     * Strictly validate exact recipe and script sources and derive their canonical identities.
     * Runner availability is intentionally outside this content-schema operation.
     */
    public static ValidatedDefinition parse(byte[] recipeSource, Map<String, String> scripts) {
        return parse(recipeSource, scripts, false);
    }

    /**
     * Read saved actions without rewriting their recipe or identities. Schema 2 remains readable
     * for inspection and abandonment; the action's client-build check prevents old-build resume.
     * Catalog loading and new activation must use {@link #parse(byte[], Map)} instead.
     */
    public static ValidatedDefinition parseRetainedSnapshot(
            byte[] recipeSource, Map<String, String> scripts) {
        return parse(recipeSource, scripts, true);
    }

    private static ValidatedDefinition parse(
            byte[] recipeSource, Map<String, String> scripts, boolean retainedSnapshot) {
        JsonElement parsed = CanonicalCodec.parseJson(recipeSource);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("recipe must be a JSON object");
        }
        JsonObject rawRecipe = parsed.getAsJsonObject();
        ModuleRecipeValidator.validate(rawRecipe, retainedSnapshot);

        // Validate the author's raw tree first so integer-only schema fields reject decimal
        // spellings. Execution and identity then share the tree reparsed from JCS bytes, preventing
        // equal digests from retaining different numeric semantics.
        JsonObject recipe =
                CanonicalCodec.parseJson(CanonicalCodec.canonicalBytes(rawRecipe))
                        .getAsJsonObject();

        Map<String, String> validatedScripts = new LinkedHashMap<>();
        for (Map.Entry<String, String> script :
                scripts == null ? Map.<String, String>of().entrySet() : scripts.entrySet()) {
            ModuleRecipeValidator.safeRelativeFile(script.getKey(), "script filename", ".groovy");
            if (script.getValue() == null) {
                throw new IllegalArgumentException(
                        "script " + script.getKey() + " source must not be null");
            }
            CanonicalCodec.utf8Bytes(script.getValue());
            validatedScripts.put(script.getKey(), script.getValue());
        }
        Map<String, String> exactScripts = Map.copyOf(validatedScripts);

        List<String> referencedScripts = new ArrayList<>();
        for (JsonElement stepValue : recipe.getAsJsonArray("steps")) {
            JsonObject step = stepValue.getAsJsonObject();
            if (step.has("script")) {
                referencedScripts.add(step.get("script").getAsString());
            }
        }
        Set<String> expected = Set.copyOf(referencedScripts);
        if (!exactScripts.keySet().equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(exactScripts.keySet());
            Set<String> extra = new HashSet<>(exactScripts.keySet());
            extra.removeAll(expected);
            throw new IllegalArgumentException(
                    "script closure mismatch: missing=" + missing + ", extra=" + extra);
        }

        Map<String, ScriptContract> scriptContracts = new LinkedHashMap<>();
        JsonObject scriptDigests = new JsonObject();
        for (String filename : referencedScripts) {
            String source = exactScripts.get(filename);
            byte[] sourceBytes = CanonicalCodec.utf8Bytes(source);
            scriptDigests.addProperty(filename, CanonicalCodec.sha256(sourceBytes));
            if (METRICS_SCRIPT_NAME.equals(filename)) {
                scriptContracts.put(
                        filename,
                        new ScriptContract.Metrics(ScriptHeaderParser.parseMetrics(source)));
            } else {
                scriptContracts.put(
                        filename,
                        new ScriptContract.Mutation(ScriptHeaderParser.parseClasses(source)));
            }
        }

        Map<String, String> modelDigests = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> model : recipe.getAsJsonObject("models").entrySet()) {
            modelDigests.put(model.getKey(), CanonicalCodec.canonicalDigest(model.getValue()));
        }

        List<String> stepIds = new ArrayList<>();
        for (JsonElement stepValue : recipe.getAsJsonArray("steps")) {
            JsonObject step = stepValue.getAsJsonObject();
            stepIds.add(
                    step.has("model")
                            ? step.get("model").getAsString()
                            : step.get("script").getAsString());
        }

        return new ValidatedDefinition(
                recipe.deepCopy(),
                CanonicalCodec.canonicalDigest(recipe),
                Map.copyOf(modelDigests),
                CanonicalCodec.canonicalDigest(scriptDigests),
                List.copyOf(stepIds),
                Map.copyOf(scriptContracts));
    }

    /** Exact validated tree plus every identity needed by later module activation. */
    public record ValidatedDefinition(
            JsonObject recipe,
            String recipeDigest,
            Map<String, String> modelConfigDigests,
            String scriptSetDigest,
            List<String> stepIds,
            Map<String, ScriptContract> scriptContracts) {}

    /** Closed distinction between ordinary class-mutation and metric script contracts. */
    public sealed interface ScriptContract {
        /** Classes an ordinary script may introduce. */
        record Mutation(List<String> classes) implements ScriptContract {
            public Mutation {
                classes = List.copyOf(classes);
            }
        }

        /** Strict metric definitions declared by metrics.groovy. */
        record Metrics(List<MetricInfo> definitions) implements ScriptContract {
            public Metrics {
                definitions = List.copyOf(definitions);
            }
        }
    }
}
