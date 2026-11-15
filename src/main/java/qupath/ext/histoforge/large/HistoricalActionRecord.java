package qupath.ext.histoforge.large;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.large.LargeActionTypes.LocalFileRecord;
import qupath.ext.histoforge.pipeline.RunResults;

/**
 * Read-only display and file protection for retained version-2 records. This is not a recovery
 * handle: old source plans and requests are never interpreted as current execution authority.
 */
public record HistoricalActionRecord(
        String actionId,
        String handleGenerationId,
        String imageId,
        ModuleRef moduleRef,
        BuildIdentityCodec.ClientBuildIdentity clientBuild,
        ExecutionScopeCodec.TargetRegion target,
        LargeActionPhase phase,
        Optional<RunResults.FailureDetail> failure,
        Instant createdAt,
        Instant updatedAt,
        List<ModelRun> runs,
        Set<LocalFileRecord> protectedFiles,
        Set<String> preparedImageDirectories) {

    static final int SCHEMA_VERSION = 2;

    public HistoricalActionRecord {
        runs = List.copyOf(runs);
        protectedFiles = Set.copyOf(protectedFiles);
        preparedImageDirectories = Set.copyOf(preparedImageDirectories);
    }

    /** Stored identifiers only; this record cannot authorize a gateway request. */
    public record ModelRun(String stepId, String modelRunId) {}

    static HistoricalActionRecord parse(JsonObject value) {
        JsonObject object = LargeActionCodec.handleObject(value);
        if (CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version")
                != SCHEMA_VERSION)
            throw new IllegalArgumentException("unsupported historical action schema_version");
        String action = uuid(object, "action_id");
        String generation = uuid(object, "handle_generation_id");
        Instant created = time(object, "created_at");
        Instant updated = time(object, "updated_at");
        if (updated.isBefore(created))
            throw new IllegalArgumentException("updated_at must not precede created_at");
        LargeActionPhase phase =
                LargeActionPhase.valueOf(LargeActionCodec.requiredString(object, "phase"));
        Optional<RunResults.FailureDetail> failure =
                object.has("failure")
                        ? Optional.of(LargeActionCodec.parseFailure(object.get("failure")))
                        : Optional.empty();
        if ((phase == LargeActionPhase.FAILED || phase == LargeActionPhase.CONFLICT)
                != failure.isPresent())
            throw new IllegalArgumentException("historical failure does not match its phase");
        JsonObject module =
                object(object(object.get("module"), "module").get("module"), "module identity");
        ModuleRef ref =
                ModuleRef.parse(LargeActionCodec.requiredString(module, "module_ref"))
                        .orElseThrow(() -> new IllegalArgumentException("invalid module_ref"));
        JsonElement steps = object.get("steps");
        if (!steps.isJsonArray()) throw new IllegalArgumentException("steps must be an array");
        List<ModelRun> runs = new ArrayList<>();
        for (JsonElement item : steps.getAsJsonArray()) {
            JsonObject step = object(item, "step");
            String stepId =
                    CanonicalCodec.requireNonblank(
                            LargeActionCodec.requiredString(step, "step_id"), "step_id");
            JsonElement recordedRuns = step.get("runs");
            if (recordedRuns == null || !recordedRuns.isJsonArray())
                throw new IllegalArgumentException("runs must be an array");
            for (JsonElement run : recordedRuns.getAsJsonArray())
                runs.add(new ModelRun(stepId, uuid(object(run, "run"), "model_run_id")));
        }
        Set<LocalFileRecord> files = new LinkedHashSet<>();
        Set<String> directories = new LinkedHashSet<>();
        directories.add("actions/" + action + "/" + generation + "/source");
        // File references are protection metadata only. No old source origin, source plan,
        // upload state or model request is converted into an executable current type.
        collectFiles(object.get("source"), files, directories);
        collectFiles(steps, files, directories);
        return new HistoricalActionRecord(
                action,
                generation,
                CanonicalCodec.requireNonblank(
                        LargeActionCodec.requiredString(object, "image_id"), "image_id"),
                ref,
                BuildIdentityCodec.parseClientBuildIdentity(object.get("client_build")),
                ExecutionScopeCodec.parseTargetRegion(object.get("target")),
                phase,
                failure,
                created,
                updated,
                runs,
                files,
                directories);
    }

    private static void collectFiles(
            JsonElement value, Set<LocalFileRecord> files, Set<String> directories) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(item -> collectFiles(item, files, directories));
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("project_relative_posix_path")) {
                files.add(LargeActionCodec.parseFile(object));
                return;
            }
            for (var field : object.entrySet()) {
                if (field.getKey().equals("derived_source_file")) {
                    LocalFileRecord file = LargeActionCodec.parseFile(field.getValue());
                    String path = file.projectRelativePosixPath();
                    int separator = path.lastIndexOf('/');
                    directories.add(separator < 0 ? "" : path.substring(0, separator));
                }
                collectFiles(field.getValue(), files, directories);
            }
        }
    }

    private static JsonObject object(JsonElement value, String path) {
        if (value == null || !value.isJsonObject())
            throw new IllegalArgumentException(path + " must be an object");
        return value.getAsJsonObject();
    }

    private static String uuid(JsonObject value, String field) {
        return CanonicalCodec.requireCanonicalUuid(
                LargeActionCodec.requiredString(value, field), field);
    }

    private static Instant time(JsonObject value, String field) {
        return CanonicalCodec.requireRfc3339Millis(
                LargeActionCodec.requiredString(value, field), field);
    }
}
