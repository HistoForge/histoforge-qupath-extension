package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;

/** Closed deployment-health and authenticated-capabilities wire contract. */
public final class GatewayCapabilitiesCodec {

    /** Contract versions executable by this plugin build. */
    public static final int API_MAJOR = 1;

    public static final int MODULE_SCHEMA_VERSION = 3;
    public static final int SOURCE_ARTIFACT_SCHEMA_VERSION = SourceArtifactCodec.SCHEMA_VERSION;
    public static final int FINITE_INFERENCE_SCHEMA_VERSION = 2;
    public static final int DURABLE_MODEL_RUN_SCHEMA_VERSION = 4;
    public static final int TRAINING_API_SCHEMA_VERSION = 1;

    private static final Set<String> SOURCE_ARTIFACT_LIMIT_FIELDS =
            Set.of(
                    "max_start_canonical_bytes",
                    "max_artifact_bytes",
                    "max_chunks",
                    "max_chunk_bytes",
                    "max_image_series",
                    "max_pyramid_levels_per_series",
                    "max_level_width_px",
                    "max_level_height_px",
                    "max_complete_decode_pixels",
                    "upload_idle_timeout_seconds",
                    "absolute_lifetime_seconds");
    private static final Set<String> GATEWAY_LIMIT_FIELDS =
            Set.of(
                    "max_json_request_bytes",
                    "max_catalog_page_size",
                    "max_catalog_encoded_bytes",
                    "max_admin_request_bytes",
                    "max_module_bytes",
                    "max_installed_bytes",
                    "max_active_admin_jobs",
                    "max_active_source_artifacts",
                    "max_active_model_runs");
    private static final Set<String> GATEWAY_HEALTH_FIELDS = Set.of("api_major", "status");
    private static final Set<String> GATEWAY_CAPABILITY_FIELDS =
            Set.of(
                    "api_major",
                    "module_schema_version",
                    "source_artifact_schema_version",
                    "finite_inference_schema_version",
                    "durable_model_run_schema_version",
                    "training_api_schema_version",
                    "deployment_id",
                    "auth_revision",
                    "gateway_build",
                    "runner_registry_digest",
                    "inference_gpus",
                    "source_artifact_limits",
                    "training_api_limits",
                    "limits");

    private GatewayCapabilitiesCodec() {}

    /** Source-upload, decode, and lifetime ceilings enforced by one deployment. */
    public record SourceArtifactLimits(
            // Maximum canonical bytes in a source-artifact start request.
            long maxStartCanonicalBytes,
            // Maximum bytes in one complete immutable source artifact.
            long maxArtifactBytes,
            // Maximum upload chunks in one source artifact.
            int maxChunks,
            // Maximum source-artifact bytes carried by one chunk.
            long maxChunkBytes,
            // Maximum discoverable image series in one source container.
            int maxImageSeries,
            // Maximum resolution levels in any one source series.
            int maxPyramidLevelsPerSeries,
            // Maximum decoded width of any accepted source level.
            int maxLevelWidthPx,
            // Maximum decoded height of any accepted source level.
            int maxLevelHeightPx,
            // Maximum pixels read through a complete-decode source path.
            long maxCompleteDecodePixels,
            // Maximum idle interval between accepted upload activity, in seconds.
            int uploadIdleTimeoutSeconds,
            // Absolute source-artifact lifetime from accepted start, in seconds.
            int absoluteLifetimeSeconds) {
        public SourceArtifactLimits {
            maxStartCanonicalBytes =
                    positiveSafe(maxStartCanonicalBytes, "max_start_canonical_bytes");
            maxArtifactBytes = positiveSafe(maxArtifactBytes, "max_artifact_bytes");
            maxChunks = positiveInt(maxChunks, "max_chunks");
            maxChunkBytes = positiveSafe(maxChunkBytes, "max_chunk_bytes");
            maxImageSeries = positiveInt(maxImageSeries, "max_image_series");
            maxPyramidLevelsPerSeries =
                    positiveInt(maxPyramidLevelsPerSeries, "max_pyramid_levels_per_series");
            maxLevelWidthPx = positiveInt(maxLevelWidthPx, "max_level_width_px");
            maxLevelHeightPx = positiveInt(maxLevelHeightPx, "max_level_height_px");
            maxCompleteDecodePixels =
                    positiveSafe(maxCompleteDecodePixels, "max_complete_decode_pixels");
            uploadIdleTimeoutSeconds =
                    positiveInt(uploadIdleTimeoutSeconds, "upload_idle_timeout_seconds");
            absoluteLifetimeSeconds =
                    positiveInt(absoluteLifetimeSeconds, "absolute_lifetime_seconds");
        }
    }

    /** Request, catalog, storage, and concurrency ceilings enforced by one deployment. */
    public record GatewayLimits(
            // Maximum raw bytes in any JSON request body before parsing.
            long maxJsonRequestBytes,
            // Maximum module descriptors in one catalog page.
            int maxCatalogPageSize,
            // Maximum encoded bytes in one catalog page.
            long maxCatalogEncodedBytes,
            // Maximum canonical bytes in one parsed administrative request.
            long maxAdminRequestBytes,
            // Maximum stored bytes in one complete installed module.
            long maxModuleBytes,
            // Maximum aggregate bytes in modules, private stages, and the shared cache.
            long maxInstalledBytes,
            // Maximum concurrently active administrative jobs.
            int maxActiveAdminJobs,
            // Maximum concurrently active source artifacts.
            int maxActiveSourceArtifacts,
            // Maximum concurrently active durable model runs.
            int maxActiveModelRuns) {
        public GatewayLimits {
            maxJsonRequestBytes = positiveSafe(maxJsonRequestBytes, "max_json_request_bytes");
            maxCatalogPageSize = positiveInt(maxCatalogPageSize, "max_catalog_page_size");
            maxCatalogEncodedBytes =
                    positiveSafe(maxCatalogEncodedBytes, "max_catalog_encoded_bytes");
            maxAdminRequestBytes = positiveSafe(maxAdminRequestBytes, "max_admin_request_bytes");
            maxModuleBytes = positiveSafe(maxModuleBytes, "max_module_bytes");
            maxInstalledBytes = positiveSafe(maxInstalledBytes, "max_installed_bytes");
            maxActiveAdminJobs = positiveInt(maxActiveAdminJobs, "max_active_admin_jobs");
            maxActiveSourceArtifacts =
                    positiveInt(maxActiveSourceArtifacts, "max_active_source_artifacts");
            maxActiveModelRuns = positiveInt(maxActiveModelRuns, "max_active_model_runs");
        }
    }

    /** Minimal unauthenticated liveness response. */
    public record GatewayHealth(
            // Gateway API major version served by this deployment.
            int apiMajor,
            // Fixed literal "ok"; any other body is not a healthy response.
            String status) {
        public GatewayHealth {
            apiMajor = positiveInt(apiMajor, "api_major");
            if (!"ok".equals(status)) {
                throw new IllegalArgumentException("gateway health status must be 'ok'");
            }
        }
    }

    /** Authenticated handshake binding schemas, deployment, build, registry, and limits. */
    public record GatewayCapabilities(
            // Gateway API major version used by these capabilities.
            int apiMajor,
            // Supported module-content schema version.
            int moduleSchemaVersion,
            // Supported source-artifact wire schema version.
            int sourceArtifactSchemaVersion,
            // Supported finite-inference wire schema version.
            int finiteInferenceSchemaVersion,
            // Supported durable model-run wire schema version.
            int durableModelRunSchemaVersion,
            // Supported training API wire schema version.
            int trainingApiSchemaVersion,
            // Identity of the deployed gateway instance.
            String deploymentId,
            // Current token revision required of local credentials.
            long authRevision,
            // Immutable build identity of the gateway implementation.
            BuildIdentityCodec.SoftwareBuildIdentity gatewayBuild,
            // Digest of the build-generated runner registry in use.
            String runnerRegistryDigest,
            // Ordered Modal GPUs accepted as explicit inference overrides.
            List<String> inferenceGpus,
            // Admission and transfer limits for source artifacts.
            SourceArtifactLimits sourceArtifactLimits,
            // Capture, transfer, report, and pagination limits for training.
            TrainingApiLimits trainingApiLimits,
            // Deployment-wide request, storage, and concurrency limits.
            GatewayLimits limits) {
        public GatewayCapabilities {
            apiMajor = positiveInt(apiMajor, "api_major");
            moduleSchemaVersion = positiveInt(moduleSchemaVersion, "module_schema_version");
            sourceArtifactSchemaVersion =
                    positiveInt(sourceArtifactSchemaVersion, "source_artifact_schema_version");
            finiteInferenceSchemaVersion =
                    positiveInt(finiteInferenceSchemaVersion, "finite_inference_schema_version");
            durableModelRunSchemaVersion =
                    positiveInt(durableModelRunSchemaVersion, "durable_model_run_schema_version");
            trainingApiSchemaVersion =
                    positiveInt(trainingApiSchemaVersion, "training_api_schema_version");
            deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
            authRevision = nonnegativeSafe(authRevision, "auth_revision");
            gatewayBuild = Objects.requireNonNull(gatewayBuild, "gateway_build");
            runnerRegistryDigest =
                    CanonicalCodec.requireSha256(runnerRegistryDigest, "runner_registry_digest");
            inferenceGpus = nonemptyUniqueStrings(inferenceGpus, "inference_gpus");
            sourceArtifactLimits =
                    Objects.requireNonNull(sourceArtifactLimits, "source_artifact_limits");
            trainingApiLimits = Objects.requireNonNull(trainingApiLimits, "training_api_limits");
            limits = Objects.requireNonNull(limits, "limits");
        }
    }

    /** Parse one exact snake-case source-artifact limits object. */
    public static SourceArtifactLimits parseSourceArtifactLimits(JsonElement value) {
        JsonObject object =
                requireExactObject(value, SOURCE_ARTIFACT_LIMIT_FIELDS, "source artifact limits");
        return new SourceArtifactLimits(
                positiveSafe(object, "max_start_canonical_bytes"),
                positiveSafe(object, "max_artifact_bytes"),
                positiveInt(object, "max_chunks"),
                positiveSafe(object, "max_chunk_bytes"),
                positiveInt(object, "max_image_series"),
                positiveInt(object, "max_pyramid_levels_per_series"),
                positiveInt(object, "max_level_width_px"),
                positiveInt(object, "max_level_height_px"),
                positiveSafe(object, "max_complete_decode_pixels"),
                positiveInt(object, "upload_idle_timeout_seconds"),
                positiveInt(object, "absolute_lifetime_seconds"));
    }

    /** Parse one exact snake-case gateway-limits object. */
    public static GatewayLimits parseGatewayLimits(JsonElement value) {
        JsonObject object = requireExactObject(value, GATEWAY_LIMIT_FIELDS, "gateway limits");
        return new GatewayLimits(
                positiveSafe(object, "max_json_request_bytes"),
                positiveInt(object, "max_catalog_page_size"),
                positiveSafe(object, "max_catalog_encoded_bytes"),
                positiveSafe(object, "max_admin_request_bytes"),
                positiveSafe(object, "max_module_bytes"),
                positiveSafe(object, "max_installed_bytes"),
                positiveInt(object, "max_active_admin_jobs"),
                positiveInt(object, "max_active_source_artifacts"),
                positiveInt(object, "max_active_model_runs"));
    }

    /** Parse one exact snake-case unauthenticated health response. */
    public static GatewayHealth parseGatewayHealth(JsonElement value) {
        JsonObject object = requireExactObject(value, GATEWAY_HEALTH_FIELDS, "gateway health");
        return new GatewayHealth(
                positiveInt(object, "api_major"), requiredString(object, "status"));
    }

    /** Parse one exact snake-case authenticated capability response. */
    public static GatewayCapabilities parseGatewayCapabilities(JsonElement value) {
        JsonObject object =
                requireExactObject(value, GATEWAY_CAPABILITY_FIELDS, "gateway capabilities");
        return new GatewayCapabilities(
                positiveInt(object, "api_major"),
                positiveInt(object, "module_schema_version"),
                positiveInt(object, "source_artifact_schema_version"),
                positiveInt(object, "finite_inference_schema_version"),
                positiveInt(object, "durable_model_run_schema_version"),
                positiveInt(object, "training_api_schema_version"),
                requiredString(object, "deployment_id"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("auth_revision"), "auth_revision"),
                BuildIdentityCodec.parseSoftwareBuildIdentity(object.get("gateway_build")),
                requiredString(object, "runner_registry_digest"),
                parseNonemptyUniqueStrings(object.get("inference_gpus"), "inference_gpus"),
                parseSourceArtifactLimits(object.get("source_artifact_limits")),
                TrainingLimitsCodec.parse(object.get("training_api_limits")),
                parseGatewayLimits(object.get("limits")));
    }

    /** Return whether every wire/schema version is executable by this plugin build. */
    public static boolean supports(GatewayCapabilities capabilities) {
        return capabilities.apiMajor() == API_MAJOR
                && capabilities.moduleSchemaVersion() == MODULE_SCHEMA_VERSION
                && capabilities.sourceArtifactSchemaVersion() == SOURCE_ARTIFACT_SCHEMA_VERSION
                && capabilities.finiteInferenceSchemaVersion() == FINITE_INFERENCE_SCHEMA_VERSION
                && capabilities.durableModelRunSchemaVersion() == DURABLE_MODEL_RUN_SCHEMA_VERSION
                && capabilities.trainingApiSchemaVersion() == TRAINING_API_SCHEMA_VERSION;
    }

    /** Encode validated source-artifact limits as exact snake-case JSON. */
    public static JsonObject toJson(SourceArtifactLimits limits) {
        JsonObject value = new JsonObject();
        value.addProperty("max_start_canonical_bytes", limits.maxStartCanonicalBytes());
        value.addProperty("max_artifact_bytes", limits.maxArtifactBytes());
        value.addProperty("max_chunks", limits.maxChunks());
        value.addProperty("max_chunk_bytes", limits.maxChunkBytes());
        value.addProperty("max_image_series", limits.maxImageSeries());
        value.addProperty("max_pyramid_levels_per_series", limits.maxPyramidLevelsPerSeries());
        value.addProperty("max_level_width_px", limits.maxLevelWidthPx());
        value.addProperty("max_level_height_px", limits.maxLevelHeightPx());
        value.addProperty("max_complete_decode_pixels", limits.maxCompleteDecodePixels());
        value.addProperty("upload_idle_timeout_seconds", limits.uploadIdleTimeoutSeconds());
        value.addProperty("absolute_lifetime_seconds", limits.absoluteLifetimeSeconds());
        return value;
    }

    /** Encode validated gateway limits as exact snake-case JSON. */
    public static JsonObject toJson(GatewayLimits limits) {
        JsonObject value = new JsonObject();
        value.addProperty("max_json_request_bytes", limits.maxJsonRequestBytes());
        value.addProperty("max_catalog_page_size", limits.maxCatalogPageSize());
        value.addProperty("max_catalog_encoded_bytes", limits.maxCatalogEncodedBytes());
        value.addProperty("max_admin_request_bytes", limits.maxAdminRequestBytes());
        value.addProperty("max_module_bytes", limits.maxModuleBytes());
        value.addProperty("max_installed_bytes", limits.maxInstalledBytes());
        value.addProperty("max_active_admin_jobs", limits.maxActiveAdminJobs());
        value.addProperty("max_active_source_artifacts", limits.maxActiveSourceArtifacts());
        value.addProperty("max_active_model_runs", limits.maxActiveModelRuns());
        return value;
    }

    /** Encode a validated health response as exact snake-case JSON. */
    public static JsonObject toJson(GatewayHealth health) {
        JsonObject value = new JsonObject();
        value.addProperty("api_major", health.apiMajor());
        value.addProperty("status", health.status());
        return value;
    }

    /** Encode validated capabilities as exact snake-case JSON. */
    public static JsonObject toJson(GatewayCapabilities capabilities) {
        JsonObject value = new JsonObject();
        value.addProperty("api_major", capabilities.apiMajor());
        value.addProperty("module_schema_version", capabilities.moduleSchemaVersion());
        value.addProperty(
                "source_artifact_schema_version", capabilities.sourceArtifactSchemaVersion());
        value.addProperty(
                "finite_inference_schema_version", capabilities.finiteInferenceSchemaVersion());
        value.addProperty(
                "durable_model_run_schema_version", capabilities.durableModelRunSchemaVersion());
        value.addProperty("training_api_schema_version", capabilities.trainingApiSchemaVersion());
        value.addProperty("deployment_id", capabilities.deploymentId());
        value.addProperty("auth_revision", capabilities.authRevision());
        value.add("gateway_build", BuildIdentityCodec.toJson(capabilities.gatewayBuild()));
        value.addProperty("runner_registry_digest", capabilities.runnerRegistryDigest());
        JsonArray inferenceGpus = new JsonArray();
        capabilities.inferenceGpus().forEach(inferenceGpus::add);
        value.add("inference_gpus", inferenceGpus);
        value.add("source_artifact_limits", toJson(capabilities.sourceArtifactLimits()));
        value.add(
                "training_api_limits",
                TrainingLimitsCodec.toJson(capabilities.trainingApiLimits()));
        value.add("limits", toJson(capabilities.limits()));
        return value;
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static String requiredString(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return item.getAsString();
    }

    private static List<String> parseNonemptyUniqueStrings(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        int index = 0;
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(path + " values must be strings");
            }
            result.add(
                    CanonicalCodec.requireNonblank(item.getAsString(), path + "[" + index + "]"));
            index++;
        }
        return nonemptyUniqueStrings(result, path);
    }

    private static List<String> nonemptyUniqueStrings(List<String> values, String path) {
        List<String> result = List.copyOf(Objects.requireNonNull(values, path));
        if (result.isEmpty()) {
            throw new IllegalArgumentException(path + " must be nonempty");
        }
        result.forEach(value -> CanonicalCodec.requireNonblank(value, path));
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(path + " contains duplicate values");
        }
        return result;
    }

    private static int positiveInt(JsonObject value, String field) {
        return CanonicalCodec.requirePositiveInt32(value.get(field), field);
    }

    private static long positiveSafe(JsonObject value, String field) {
        return CanonicalCodec.requirePositiveSafeInteger(value.get(field), field);
    }

    private static int positiveInt(int value, String path) {
        return CanonicalCodec.requirePositiveInt32(value, path);
    }

    private static long positiveSafe(long value, String path) {
        return CanonicalCodec.requirePositiveSafeInteger(value, path);
    }

    private static long nonnegativeSafe(long value, String path) {
        return CanonicalCodec.requireNonnegativeSafeInteger(value, path);
    }
}
