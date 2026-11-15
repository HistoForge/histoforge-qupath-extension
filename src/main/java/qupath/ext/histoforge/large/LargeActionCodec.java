package qupath.ext.histoforge.large;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ArtifactRefCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.ModuleSnapshotCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.large.LargeActionTypes.HierarchyTransactionIdentity;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionBaseline;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.large.LargeActionTypes.LocalFileRecord;
import qupath.ext.histoforge.large.LargeActionTypes.ModelRunRef;
import qupath.ext.histoforge.large.LargeActionTypes.SourceUploadRef;
import qupath.ext.histoforge.large.LargeActionTypes.StepRunRef;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.spatial.SourceArtifactPlanCodec;

/** Strict canonical JSON codec for the project-scoped large-action handle. */
public final class LargeActionCodec {

    private static final Set<String> HANDLE_REQUIRED_FIELDS =
            Set.of(
                    "schema_version",
                    "action_id",
                    "handle_generation_id",
                    "client_build",
                    "image_id",
                    "module",
                    "action",
                    "target",
                    "selection_regions",
                    "baseline",
                    "source_plan",
                    "steps",
                    "phase",
                    "created_at",
                    "updated_at");
    private static final Set<String> HANDLE_OPTIONAL_FIELDS =
            Set.of("source", "expected_committed_hierarchy", "failure");
    private static final Set<String> FILE_FIELDS =
            Set.of("project_relative_posix_path", "size_bytes", "sha256");
    private static final Set<String> SOURCE_REQUIRED_FIELDS =
            Set.of("artifact_id", "start_request_file");
    private static final Set<String> SOURCE_OPTIONAL_FIELDS =
            Set.of("owned_upload_file", "sealed_artifact");
    private static final Set<String> RUN_REQUIRED_FIELDS =
            Set.of("shard_index", "request_file", "model_run_id");
    private static final Set<String> RUN_OPTIONAL_FIELDS =
            Set.of("result_manifest_digest", "complete_result_files", "image_sources");
    private static final Set<String> STEP_REQUIRED_FIELDS =
            Set.of("step_id", "input_plan_digest", "runs");
    private static final Set<String> STEP_OPTIONAL_FIELDS = Set.of("parent_plan_digest");
    private static final Set<String> TRANSACTION_FIELDS =
            Set.of("replacement_closure", "guarded_object_ids", "content_digest");
    private static final Set<String> BASELINE_FIELDS = Set.of("pixel_source", "hierarchy");
    private static final Set<String> FAILURE_FIELDS = Set.of("type", "message");

    private LargeActionCodec() {}

    /** Decode an exact canonical persisted handle; noncanonical text is corrupt state. */
    public static LargeActionHandle decodeCanonical(byte[] source) {
        byte[] bytes = Arrays.copyOf(source, source.length);
        LargeActionHandle handle = parse(CanonicalCodec.parseJson(bytes));
        if (!Arrays.equals(bytes, canonicalBytes(handle))) {
            throw new IllegalArgumentException("large-action handle must be canonical JSON");
        }
        return handle;
    }

    /** Parse and validate the current recovery-handle format. */
    public static LargeActionHandle parse(JsonElement value) {
        JsonObject object = handleObject(value);
        int version = CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version");
        if (version != LargeActionTypes.SCHEMA_VERSION)
            throw new IllegalArgumentException(
                    "unsupported large-action schema_version: " + version);
        return new LargeActionHandle(
                version,
                requiredString(object, "action_id"),
                requiredString(object, "handle_generation_id"),
                BuildIdentityCodec.parseClientBuildIdentity(object.get("client_build")),
                requiredString(object, "image_id"),
                ModuleSnapshotCodec.parse(object.get("module")),
                ActionSelectionCodec.parseImageAction(object.get("action")),
                ExecutionScopeCodec.parseTargetRegion(object.get("target")),
                parseBaseline(object.get("baseline")),
                SourceArtifactPlanCodec.parse(object.get("source_plan")),
                optional(object, "source", LargeActionCodec::parseSource),
                parseSteps(object.get("steps")),
                optional(
                        object, "expected_committed_hierarchy", LargeActionCodec::parseTransaction),
                parseEnum(requiredString(object, "phase"), LargeActionPhase.class, "phase"),
                optional(object, "failure", LargeActionCodec::parseFailure),
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "created_at"), "created_at"),
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "updated_at"), "updated_at"),
                parseSelection(object.get("selection_regions")));
    }

    private static List<ExecutionScopeCodec.TargetRegion> parseSelection(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("selection_regions must be an array");
        }
        List<ExecutionScopeCodec.TargetRegion> result = new ArrayList<>();
        value.getAsJsonArray()
                .forEach(item -> result.add(ExecutionScopeCodec.parseTargetRegion(item)));
        return List.copyOf(result);
    }

    /** Encode one handle with exact persisted lower-snake fields. */
    public static JsonObject toJson(LargeActionHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("large-action handle must not be null");
        }
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", handle.schemaVersion());
        value.addProperty("action_id", handle.actionId());
        value.addProperty("handle_generation_id", handle.handleGenerationId());
        value.add("client_build", BuildIdentityCodec.toJson(handle.clientBuild()));
        value.addProperty("image_id", handle.imageId());
        value.add("module", ModuleSnapshotCodec.toJson(handle.module()));
        value.add("action", ActionSelectionCodec.toJson(handle.action()));
        value.add("target", ExecutionScopeCodec.toJson(handle.target()));
        JsonArray selection = new JsonArray();
        handle.selectionRegions()
                .forEach(region -> selection.add(ExecutionScopeCodec.toJson(region)));
        value.add("selection_regions", selection);
        value.add("baseline", toJson(handle.baseline()));
        value.add("source_plan", SourceArtifactPlanCodec.toJson(handle.sourcePlan()));
        handle.source().ifPresent(source -> value.add("source", toJson(source)));
        JsonArray steps = new JsonArray();
        handle.steps().forEach(step -> steps.add(toJson(step)));
        value.add("steps", steps);
        handle.expectedCommittedHierarchy()
                .ifPresent(
                        hierarchy -> value.add("expected_committed_hierarchy", toJson(hierarchy)));
        value.addProperty("phase", handle.phase().name());
        handle.failure().ifPresent(failure -> value.add("failure", toJson(failure)));
        value.addProperty(
                "created_at", CanonicalCodec.rfc3339Millis(handle.createdAt(), "created_at"));
        value.addProperty(
                "updated_at", CanonicalCodec.rfc3339Millis(handle.updatedAt(), "updated_at"));
        return value;
    }

    /** Exact bytes forced and atomically replaced by the handle store. */
    public static byte[] canonicalBytes(LargeActionHandle handle) {
        return CanonicalCodec.canonicalBytes(toJson(handle));
    }

    static JsonObject handleObject(JsonElement value) {
        return fieldsObject(
                value, HANDLE_REQUIRED_FIELDS, HANDLE_OPTIONAL_FIELDS, "large-action handle");
    }

    static LocalFileRecord parseFile(JsonElement value) {
        JsonObject object = exactObject(value, FILE_FIELDS, "local file record");
        return new LocalFileRecord(
                requiredString(object, "project_relative_posix_path"),
                CanonicalCodec.requirePositiveSafeInteger(object.get("size_bytes"), "size_bytes"),
                requiredString(object, "sha256"));
    }

    private static JsonObject toJson(LocalFileRecord file) {
        JsonObject value = new JsonObject();
        value.addProperty("project_relative_posix_path", file.projectRelativePosixPath());
        value.addProperty("size_bytes", file.sizeBytes());
        value.addProperty("sha256", file.sha256());
        return value;
    }

    static SourceUploadRef parseSource(JsonElement value) {
        JsonObject object =
                fieldsObject(
                        value, SOURCE_REQUIRED_FIELDS, SOURCE_OPTIONAL_FIELDS, "source upload ref");
        return new SourceUploadRef(
                requiredString(object, "artifact_id"),
                parseFile(object.get("start_request_file")),
                optional(object, "owned_upload_file", LargeActionCodec::parseFile),
                optional(
                        object,
                        "sealed_artifact",
                        SourceArtifactPublicationCodec::parseArtifactRef));
    }

    static JsonObject toJson(SourceUploadRef source) {
        JsonObject value = new JsonObject();
        value.addProperty("artifact_id", source.artifactId());
        value.add("start_request_file", toJson(source.startRequestFile()));
        source.ownedUploadFile().ifPresent(file -> value.add("owned_upload_file", toJson(file)));
        source.sealedArtifact()
                .ifPresent(
                        artifact ->
                                value.add(
                                        "sealed_artifact",
                                        SourceArtifactPublicationCodec.toJson(artifact)));
        return value;
    }

    private static List<StepRunRef> parseSteps(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("steps must be an array");
        }
        List<StepRunRef> result = new ArrayList<>();
        value.getAsJsonArray().forEach(item -> result.add(parseStep(item)));
        return List.copyOf(result);
    }

    private static StepRunRef parseStep(JsonElement value) {
        JsonObject object =
                fieldsObject(value, STEP_REQUIRED_FIELDS, STEP_OPTIONAL_FIELDS, "step run ref");
        JsonElement runsValue = object.get("runs");
        if (runsValue == null || !runsValue.isJsonArray()) {
            throw new IllegalArgumentException("runs must be an array");
        }
        List<ModelRunRef> runs = new ArrayList<>();
        runsValue.getAsJsonArray().forEach(item -> runs.add(parseRun(item)));
        return new StepRunRef(
                requiredString(object, "step_id"),
                requiredString(object, "input_plan_digest"),
                optionalString(object, "parent_plan_digest"),
                runs);
    }

    private static JsonObject toJson(StepRunRef step) {
        JsonObject value = new JsonObject();
        value.addProperty("step_id", step.stepId());
        value.addProperty("input_plan_digest", step.inputPlanDigest());
        step.parentPlanDigest().ifPresent(item -> value.addProperty("parent_plan_digest", item));
        JsonArray runs = new JsonArray();
        step.runs().forEach(run -> runs.add(toJson(run)));
        value.add("runs", runs);
        return value;
    }

    private static ModelRunRef parseRun(JsonElement value) {
        JsonObject object =
                fieldsObject(value, RUN_REQUIRED_FIELDS, RUN_OPTIONAL_FIELDS, "model run ref");
        return new ModelRunRef(
                CanonicalCodec.requireNonnegativeInt32(object.get("shard_index"), "shard_index"),
                parseFile(object.get("request_file")),
                requiredString(object, "model_run_id"),
                optionalString(object, "result_manifest_digest"),
                optional(
                        object,
                        "complete_result_files",
                        LargeActionCodec::parseCompleteResultFiles),
                optional(object, "image_sources", RunImageSources::parse));
    }

    private static JsonObject toJson(ModelRunRef run) {
        JsonObject value = new JsonObject();
        value.addProperty("shard_index", run.shardIndex());
        value.add("request_file", toJson(run.requestFile()));
        value.addProperty("model_run_id", run.modelRunId());
        run.resultManifestDigest()
                .ifPresent(item -> value.addProperty("result_manifest_digest", item));
        run.completeResultFiles()
                .ifPresent(files -> value.add("complete_result_files", toJson(files)));
        run.imageSources()
                .ifPresent(images -> value.add("image_sources", RunImageSources.toJson(images)));
        return value;
    }

    private static LargeActionTypes.CompleteResultFiles parseCompleteResultFiles(
            JsonElement value) {
        var object =
                exactObject(
                        value,
                        Set.of("manifest_artifact", "manifest_file", "feature_files"),
                        "complete result files");
        var files = object.get("feature_files");
        if (!files.isJsonArray()) {
            throw new IllegalArgumentException("feature_files must be an array");
        }
        List<LocalFileRecord> features = new ArrayList<>();
        files.getAsJsonArray().forEach(file -> features.add(parseFile(file)));
        return new LargeActionTypes.CompleteResultFiles(
                ArtifactRefCodec.parse(object.get("manifest_artifact")),
                parseFile(object.get("manifest_file")),
                features);
    }

    private static JsonObject toJson(LargeActionTypes.CompleteResultFiles files) {
        var value = new JsonObject();
        value.add("manifest_artifact", ArtifactRefCodec.toJson(files.manifestArtifact()));
        value.add("manifest_file", toJson(files.manifestFile()));
        var features = new JsonArray();
        files.featureFiles().forEach(file -> features.add(toJson(file)));
        value.add("feature_files", features);
        return value;
    }

    private static LargeActionBaseline parseBaseline(JsonElement value) {
        JsonObject object = exactObject(value, BASELINE_FIELDS, "large-action baseline");
        return new LargeActionBaseline(
                SourceIdentityCodec.parseImageSourceIdentity(object.get("pixel_source")),
                parseTransaction(object.get("hierarchy")));
    }

    private static JsonObject toJson(LargeActionBaseline baseline) {
        JsonObject value = new JsonObject();
        value.add("pixel_source", SourceIdentityCodec.toJson(baseline.pixelSource()));
        value.add("hierarchy", toJson(baseline.hierarchy()));
        return value;
    }

    private static HierarchyTransactionIdentity parseTransaction(JsonElement value) {
        JsonObject object = exactObject(value, TRANSACTION_FIELDS, "hierarchy transaction");
        JsonElement outputsValue = object.get("replacement_closure");
        JsonElement guardedValue = object.get("guarded_object_ids");
        if (outputsValue == null || !outputsValue.isJsonArray()) {
            throw new IllegalArgumentException("replacement_closure must be an array");
        }
        if (guardedValue == null || !guardedValue.isJsonArray()) {
            throw new IllegalArgumentException("guarded_object_ids must be an array");
        }
        Set<ExecutionScopeCodec.OutputKey> outputs = new LinkedHashSet<>();
        outputsValue
                .getAsJsonArray()
                .forEach(
                        item -> {
                            if (!outputs.add(ExecutionScopeCodec.parseOutputKey(item))) {
                                throw new IllegalArgumentException(
                                        "replacement_closure contains duplicates");
                            }
                        });
        Set<String> guarded = new LinkedHashSet<>();
        guardedValue
                .getAsJsonArray()
                .forEach(
                        item -> {
                            String id = requiredString(item, "guarded_object_id");
                            if (!guarded.add(id)) {
                                throw new IllegalArgumentException(
                                        "guarded_object_ids contains duplicates");
                            }
                        });
        return new HierarchyTransactionIdentity(
                outputs, guarded, requiredString(object, "content_digest"));
    }

    private static JsonObject toJson(HierarchyTransactionIdentity transaction) {
        JsonObject value = new JsonObject();
        JsonArray outputs = new JsonArray();
        transaction.replacementClosure().stream()
                .sorted(
                        (left, right) ->
                                Arrays.compareUnsigned(
                                        CanonicalCodec.canonicalBytes(
                                                ExecutionScopeCodec.toJson(left)),
                                        CanonicalCodec.canonicalBytes(
                                                ExecutionScopeCodec.toJson(right))))
                .forEach(output -> outputs.add(ExecutionScopeCodec.toJson(output)));
        value.add("replacement_closure", outputs);
        JsonArray guarded = new JsonArray();
        transaction.guardedObjectIds().stream().sorted().forEach(guarded::add);
        value.add("guarded_object_ids", guarded);
        value.addProperty("content_digest", transaction.contentDigest());
        return value;
    }

    static RunResults.FailureDetail parseFailure(JsonElement value) {
        JsonObject object = exactObject(value, FAILURE_FIELDS, "failure detail");
        return new RunResults.FailureDetail(
                parseEnum(
                        requiredString(object, "type"),
                        RunResults.FailureType.class,
                        "failure type"),
                requiredString(object, "message"));
    }

    private static JsonObject toJson(RunResults.FailureDetail failure) {
        JsonObject value = new JsonObject();
        value.addProperty("type", failure.type().name());
        value.addProperty("message", failure.message());
        return value;
    }

    private static <T> Optional<T> optional(
            JsonObject object, String field, java.util.function.Function<JsonElement, T> parser) {
        return object.has(field) ? Optional.of(parser.apply(object.get(field))) : Optional.empty();
    }

    private static Optional<String> optionalString(JsonObject object, String field) {
        return object.has(field)
                ? Optional.of(requiredString(object.get(field), field))
                : Optional.empty();
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, String path) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(path + " contains an unsupported value", error);
        }
    }

    private static JsonObject exactObject(JsonElement value, Set<String> fields, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static JsonObject fieldsObject(
            JsonElement value, Set<String> required, Set<String> optional, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        Set<String> allowed = new java.util.HashSet<>(required);
        allowed.addAll(optional);
        if (!object.keySet().containsAll(required) || !allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    static String requiredString(JsonObject object, String field) {
        return requiredString(object.get(field), field);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }
}
