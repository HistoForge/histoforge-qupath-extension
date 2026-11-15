package qupath.ext.histoforge.contract;

import static qupath.ext.histoforge.contract.StrictJson.requireExactObject;
import static qupath.ext.histoforge.contract.StrictJson.requireFields;
import static qupath.ext.histoforge.contract.StrictJson.requireObject;
import static qupath.ext.histoforge.contract.StrictJson.requiredString;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Closed runner behavior, source-container, execution-mode, and limit contract. */
public final class RunnerCapabilitiesCodec {

    private static final Set<String> CAPABILITY_FIELDS =
            Set.of(
                    "modes",
                    "output_types",
                    "source_containers",
                    "runner_build",
                    "rasterizer",
                    "preprocessing_contract_digest",
                    "finite");
    private static final Set<String> CAPABILITY_FIELDS_WITH_DURABLE =
            Set.of(
                    "modes",
                    "output_types",
                    "source_containers",
                    "runner_build",
                    "rasterizer",
                    "preprocessing_contract_digest",
                    "finite",
                    "durable_run");
    private static final Set<String> FINITE_FIELDS =
            Set.of(
                    "max_attempt_execution_seconds",
                    "max_request_canonical_bytes",
                    "target_raster",
                    "max_inclusion_mask_vertices",
                    "result");
    private static final Set<String> DURABLE_FIELDS = Set.of("run", "terminal_retention_seconds");

    private RunnerCapabilitiesCodec() {}

    /** Operational invocation families implemented by a runner. */
    public enum InferenceMode {
        FINITE_CALL,
        DURABLE_MODEL_RUN
    }

    /** Scientific output families implemented by a runner and decoder. */
    public enum ModelOutputType {
        INSTANCE_SEGMENTATION,
        INSTANCE_SEGMENTATION_AND_CLASSIFICATION,
        DENSE_CLASSIFICATION,
        REGION_CLASSIFICATION
    }

    /** Complete bounded-call timeout, request, target-raster, mask, and result limits. */
    public record FiniteCallLimits(
            int maxAttemptExecutionSeconds,
            long maxRequestCanonicalBytes,
            ExecutionLimitsCodec.TargetRasterLimits targetRaster,
            long maxInclusionMaskVertices,
            ExecutionLimitsCodec.ResultLimits result) {
        public FiniteCallLimits {
            maxAttemptExecutionSeconds =
                    CanonicalCodec.requirePositiveInt32(
                            maxAttemptExecutionSeconds, "max_attempt_execution_seconds");
            maxRequestCanonicalBytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxRequestCanonicalBytes, "max_request_canonical_bytes");
            targetRaster = Objects.requireNonNull(targetRaster, "target_raster");
            maxInclusionMaskVertices =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxInclusionMaskVertices, "max_inclusion_mask_vertices");
            result = Objects.requireNonNull(result, "result");
        }
    }

    /** Durable-run limits and terminal result retention. */
    public record DurableRunLimits(
            ExecutionLimitsCodec.ModelRunLimits run, int terminalRetentionSeconds) {
        public DurableRunLimits {
            run = Objects.requireNonNull(run, "run");
            terminalRetentionSeconds =
                    CanonicalCodec.requirePositiveInt32(
                            terminalRetentionSeconds, "terminal_retention_seconds");
        }
    }

    /** Exact deployed source reading, rasterization, inference behavior, and limits. */
    public record RunnerCapabilities(
            Set<InferenceMode> modes,
            Set<ModelOutputType> outputTypes,
            Set<SourceArtifactCodec.SourceContainer> sourceContainers,
            BuildIdentityCodec.SoftwareBuildIdentity runnerBuild,
            InferenceIdentityCodec.RasterizerIdentity rasterizer,
            String preprocessingContractDigest,
            FiniteCallLimits finite,
            Optional<DurableRunLimits> durableRun) {
        public RunnerCapabilities {
            modes = immutableNonemptySet(modes, "modes");
            if (!modes.contains(InferenceMode.FINITE_CALL)) {
                throw new IllegalArgumentException(
                        "runner capabilities must advertise FINITE_CALL");
            }
            outputTypes = immutableNonemptySet(outputTypes, "output_types");
            sourceContainers = immutableNonemptySet(sourceContainers, "source_containers");
            runnerBuild = Objects.requireNonNull(runnerBuild, "runner_build");
            rasterizer = Objects.requireNonNull(rasterizer, "rasterizer");
            preprocessingContractDigest =
                    CanonicalCodec.requireSha256(
                            preprocessingContractDigest, "preprocessing_contract_digest");
            finite = Objects.requireNonNull(finite, "finite");
            durableRun = Objects.requireNonNull(durableRun, "durable_run");
            if (modes.contains(InferenceMode.DURABLE_MODEL_RUN) != durableRun.isPresent()) {
                throw new IllegalArgumentException(
                        "DURABLE_MODEL_RUN mode and durable_run limits must occur together");
            }
        }
    }

    /** Parse one exact runner-capability object. */
    public static RunnerCapabilities parse(JsonElement value) {
        JsonObject object = requireObject(value, "runner capabilities");
        requireFields(
                object,
                object.has("durable_run") ? CAPABILITY_FIELDS_WITH_DURABLE : CAPABILITY_FIELDS,
                "runner capabilities");
        return new RunnerCapabilities(
                parseEnumSet(object.get("modes"), InferenceMode.class, "modes"),
                parseEnumSet(object.get("output_types"), ModelOutputType.class, "output_types"),
                parseEnumSet(
                        object.get("source_containers"),
                        SourceArtifactCodec.SourceContainer.class,
                        "source_containers"),
                BuildIdentityCodec.parseSoftwareBuildIdentity(object.get("runner_build")),
                InferenceIdentityCodec.parseRasterizerIdentity(object.get("rasterizer")),
                requiredString(object, "preprocessing_contract_digest"),
                parseFiniteCallLimits(object.get("finite")),
                object.has("durable_run")
                        ? Optional.of(parseDurableRunLimits(object.get("durable_run")))
                        : Optional.empty());
    }

    /** Parse one exact finite-call limit object. */
    public static FiniteCallLimits parseFiniteCallLimits(JsonElement value) {
        JsonObject object = requireExactObject(value, FINITE_FIELDS, "finite call limits");
        return new FiniteCallLimits(
                CanonicalCodec.requirePositiveInt32(
                        object.get("max_attempt_execution_seconds"),
                        "max_attempt_execution_seconds"),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_request_canonical_bytes"), "max_request_canonical_bytes"),
                ExecutionLimitsCodec.parseTargetRasterLimits(object.get("target_raster")),
                CanonicalCodec.requirePositiveSafeInteger(
                        object.get("max_inclusion_mask_vertices"), "max_inclusion_mask_vertices"),
                ExecutionLimitsCodec.parseResultLimits(object.get("result")));
    }

    /** Encode one exact runner-capability object. */
    public static JsonObject toJson(RunnerCapabilities capabilities) {
        JsonObject value = new JsonObject();
        value.add("modes", enumArray(capabilities.modes()));
        value.add("output_types", enumArray(capabilities.outputTypes()));
        value.add("source_containers", enumArray(capabilities.sourceContainers()));
        value.add("runner_build", BuildIdentityCodec.toJson(capabilities.runnerBuild()));
        value.add("rasterizer", InferenceIdentityCodec.toJson(capabilities.rasterizer()));
        value.addProperty(
                "preprocessing_contract_digest", capabilities.preprocessingContractDigest());
        value.add("finite", toJson(capabilities.finite()));
        capabilities.durableRun().ifPresent(durable -> value.add("durable_run", toJson(durable)));
        return value;
    }

    /** Encode one exact finite-call limit object. */
    public static JsonObject toJson(FiniteCallLimits limits) {
        JsonObject value = new JsonObject();
        value.addProperty("max_attempt_execution_seconds", limits.maxAttemptExecutionSeconds());
        value.addProperty("max_request_canonical_bytes", limits.maxRequestCanonicalBytes());
        value.add("target_raster", ExecutionLimitsCodec.toJson(limits.targetRaster()));
        value.addProperty("max_inclusion_mask_vertices", limits.maxInclusionMaskVertices());
        value.add("result", ExecutionLimitsCodec.toJson(limits.result()));
        return value;
    }

    private static DurableRunLimits parseDurableRunLimits(JsonElement value) {
        JsonObject object = requireExactObject(value, DURABLE_FIELDS, "durable_run");
        return new DurableRunLimits(
                ExecutionLimitsCodec.parseModelRunLimits(object.get("run")),
                CanonicalCodec.requirePositiveInt32(
                        object.get("terminal_retention_seconds"), "terminal_retention_seconds"));
    }

    private static JsonObject toJson(DurableRunLimits limits) {
        JsonObject value = new JsonObject();
        value.add("run", ExecutionLimitsCodec.toJson(limits.run()));
        value.addProperty("terminal_retention_seconds", limits.terminalRetentionSeconds());
        return value;
    }

    private static <E extends Enum<E>> Set<E> parseEnumSet(
            JsonElement value, Class<E> type, String path) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException(path + " must be a nonempty array");
        }
        List<String> names = new ArrayList<>();
        EnumSet<E> result = EnumSet.noneOf(type);
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(path + " values must be strings");
            }
            String name = item.getAsString();
            try {
                names.add(name);
                if (!result.add(Enum.valueOf(type, name))) {
                    throw new IllegalArgumentException(path + " contains duplicates");
                }
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(path + " contains an unsupported value", error);
            }
        }
        List<String> sorted = names.stream().sorted().toList();
        if (!names.equals(sorted)) {
            throw new IllegalArgumentException(path + " must be sorted");
        }
        return Set.copyOf(result);
    }

    private static JsonArray enumArray(Set<? extends Enum<?>> values) {
        JsonArray result = new JsonArray();
        values.stream()
                .sorted(Comparator.comparing(Enum::name))
                .forEach(value -> result.add(value.name()));
        return result;
    }

    private static <E> Set<E> immutableNonemptySet(Set<E> value, String path) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(path + " must be nonempty");
        }
        return Set.copyOf(value);
    }
}
