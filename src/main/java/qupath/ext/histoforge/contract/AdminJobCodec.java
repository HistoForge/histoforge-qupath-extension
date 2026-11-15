package qupath.ext.histoforge.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.modules.ModuleContract;

/** Exact administrative-job request-result/status contract. */
public final class AdminJobCodec {

    private static final Set<String> ACCEPTED_FIELDS = Set.of("job_id");
    private static final Set<String> STATUS_BASE_FIELDS =
            Set.of("job_id", "type", "requested_module_ref", "phase", "updated_at");
    private static final Set<String> PAGE_REQUIRED_FIELDS = Set.of("jobs");
    private static final Set<String> PAGE_OPTIONAL_FIELDS = Set.of("next_cursor");

    private AdminJobCodec() {}

    /** Closed administrative operation family. */
    public enum JobType {
        INSTALL,
        PUBLISH
    }

    /** Closed transient lifecycle. */
    public enum JobPhase {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        PARTIAL,
        FAILED
    }

    /** Outcome of the separately optional local installation after publication. */
    public sealed interface LocalSyncResult {
        record NotRequested() implements LocalSyncResult {}

        record Succeeded(InferenceIdentityCodec.ModuleContentIdentity installedModule)
                implements LocalSyncResult {
            public Succeeded {
                installedModule = Objects.requireNonNull(installedModule, "installed_module");
                if (installedModule.resolvedCommitSha().isEmpty()) {
                    throw new IllegalArgumentException(
                            "successful local sync requires resolved_commit_sha");
                }
            }
        }

        record Failed(GatewayErrorCodec.Error failure) implements LocalSyncResult {
            public Failed {
                failure = Objects.requireNonNull(failure, "failure");
            }
        }
    }

    /** Closed successful install-or-publish result family. */
    public sealed interface AdminJobResult {
        record Installed(InferenceIdentityCodec.ModuleContentIdentity installedModule)
                implements AdminJobResult {
            public Installed {
                installedModule = Objects.requireNonNull(installedModule, "installed_module");
                if (installedModule.resolvedCommitSha().isEmpty()) {
                    throw new IllegalArgumentException(
                            "installed result requires resolved_commit_sha");
                }
            }
        }

        record Published(ModuleRef targetRepository, String commitSha, LocalSyncResult localSync)
                implements AdminJobResult {
            public Published {
                targetRepository = Objects.requireNonNull(targetRepository, "target_repository");
                commitSha = ModuleContract.requireCommitSha(commitSha, "commit_sha");
                localSync = Objects.requireNonNull(localSync, "local_sync");
                if (localSync instanceof LocalSyncResult.Succeeded succeeded
                        && !succeeded.installedModule().moduleRef().equals(targetRepository)) {
                    throw new IllegalArgumentException(
                            "local sync installed module does not match target repository");
                }
                if (localSync instanceof LocalSyncResult.Succeeded succeeded
                        && !succeeded
                                .installedModule()
                                .resolvedCommitSha()
                                .orElseThrow()
                                .equals(commitSha)) {
                    throw new IllegalArgumentException(
                            "local sync installed module does not match published commit");
                }
            }
        }
    }

    /** Small 202 response from install or publish admission. */
    public record AdminJobAccepted(String jobId) {
        public AdminJobAccepted {
            jobId = CanonicalCodec.requireCanonicalUuid(jobId, "job_id");
        }
    }

    /** Current phase-valid projection of one transient administrative job. */
    public record AdminJobStatus(
            String jobId,
            JobType type,
            ModuleRef requestedModuleRef,
            JobPhase phase,
            Instant updatedAt,
            Optional<AdminJobResult> result,
            Optional<GatewayErrorCodec.Error> failure) {
        public AdminJobStatus {
            jobId = CanonicalCodec.requireCanonicalUuid(jobId, "job_id");
            type = Objects.requireNonNull(type, "type");
            requestedModuleRef = Objects.requireNonNull(requestedModuleRef, "requested_module_ref");
            phase = Objects.requireNonNull(phase, "phase");
            updatedAt = Objects.requireNonNull(updatedAt, "updated_at");
            CanonicalCodec.rfc3339Millis(updatedAt, "updated_at");
            result = Objects.requireNonNull(result, "result");
            failure = Objects.requireNonNull(failure, "failure");
            requirePhaseSemantics(type, requestedModuleRef, phase, result, failure);
        }

        /** Whether this row has a terminal result or failure. */
        public boolean isTerminal() {
            return phase == JobPhase.SUCCEEDED
                    || phase == JobPhase.PARTIAL
                    || phase == JobPhase.FAILED;
        }

        /** Failure shown for FAILED, or the local-sync failure shown for PARTIAL. */
        public Optional<GatewayErrorCodec.Error> displayedFailure() {
            if (failure.isPresent()) {
                return failure;
            }
            if (result.orElse(null) instanceof AdminJobResult.Published published
                    && published.localSync() instanceof LocalSyncResult.Failed failed) {
                return Optional.of(failed.failure());
            }
            return Optional.empty();
        }
    }

    /** One bounded page in descending update-time and ascending job-ID order. */
    public record AdminJobPage(List<AdminJobStatus> jobs, Optional<String> nextCursor) {
        public AdminJobPage {
            jobs = List.copyOf(Objects.requireNonNull(jobs, "jobs"));
            nextCursor = Objects.requireNonNull(nextCursor, "next_cursor");
            nextCursor.ifPresent(value -> CanonicalCodec.requireNonblank(value, "next_cursor"));
        }
    }

    /** Parse the exact 202 response. */
    public static AdminJobAccepted parseAccepted(JsonElement value) {
        JsonObject object = requireExactObject(value, ACCEPTED_FIELDS, "admin job accepted");
        return new AdminJobAccepted(requiredString(object, "job_id"));
    }

    /** Parse one exact phase-valid status. */
    public static AdminJobStatus parseStatus(JsonElement value) {
        JsonObject object = requiredObject(value, "admin job status");
        JobPhase phase = requiredEnum(object, "phase", JobPhase.class);
        Set<String> expected = new java.util.HashSet<>(STATUS_BASE_FIELDS);
        if (phase == JobPhase.SUCCEEDED || phase == JobPhase.PARTIAL) {
            expected.add("result");
        } else if (phase == JobPhase.FAILED) {
            expected.add("failure");
        }
        requireFields(object, expected, "admin job status");
        return new AdminJobStatus(
                requiredString(object, "job_id"),
                requiredEnum(object, "type", JobType.class),
                parseModuleRef(object.get("requested_module_ref"), "requested_module_ref"),
                phase,
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "updated_at"), "updated_at"),
                object.has("result")
                        ? Optional.of(parseResult(object.get("result")))
                        : Optional.empty(),
                object.has("failure")
                        ? Optional.of(GatewayErrorCodec.parse(object.get("failure")))
                        : Optional.empty());
    }

    /** Parse one exact bounded status page. */
    public static AdminJobPage parsePage(JsonElement value) {
        JsonObject object = requiredObject(value, "admin job page");
        requireFields(
                object,
                object.has("next_cursor")
                        ? union(PAGE_REQUIRED_FIELDS, PAGE_OPTIONAL_FIELDS)
                        : PAGE_REQUIRED_FIELDS,
                "admin job page");
        JsonElement jobsValue = object.get("jobs");
        if (jobsValue == null || !jobsValue.isJsonArray()) {
            throw new IllegalArgumentException("jobs must be an array");
        }
        List<AdminJobStatus> jobs = new ArrayList<>();
        for (JsonElement item : jobsValue.getAsJsonArray()) {
            jobs.add(parseStatus(item));
        }
        Optional<String> cursor =
                object.has("next_cursor")
                        ? Optional.of(requiredString(object, "next_cursor"))
                        : Optional.empty();
        return new AdminJobPage(jobs, cursor);
    }

    /** Encode one accepted-job response with exact lower-snake fields. */
    public static JsonObject toJson(AdminJobAccepted accepted) {
        JsonObject value = new JsonObject();
        value.addProperty("job_id", accepted.jobId());
        return value;
    }

    /** Encode one status with phase-valid optionals only. */
    public static JsonObject toJson(AdminJobStatus status) {
        JsonObject value = new JsonObject();
        value.addProperty("job_id", status.jobId());
        value.addProperty("type", status.type().name());
        value.addProperty("requested_module_ref", status.requestedModuleRef().toString());
        value.addProperty("phase", status.phase().name());
        value.addProperty(
                "updated_at", CanonicalCodec.rfc3339Millis(status.updatedAt(), "updated_at"));
        status.result().ifPresent(result -> value.add("result", toJson(result)));
        status.failure()
                .ifPresent(failure -> value.add("failure", GatewayErrorCodec.toJson(failure)));
        return value;
    }

    /** Encode one bounded status page. */
    public static JsonObject toJson(AdminJobPage page) {
        JsonObject value = new JsonObject();
        JsonArray jobs = new JsonArray();
        page.jobs().forEach(job -> jobs.add(toJson(job)));
        value.add("jobs", jobs);
        page.nextCursor().ifPresent(cursor -> value.addProperty("next_cursor", cursor));
        return value;
    }

    private static AdminJobResult parseResult(JsonElement value) {
        JsonObject object = requiredObject(value, "admin job result");
        String type = requiredString(object, "type");
        if ("installed".equals(type)) {
            requireFields(object, Set.of("type", "installed_module"), "installed result");
            return new AdminJobResult.Installed(
                    InferenceIdentityCodec.parseModuleContentIdentity(
                            object.get("installed_module")));
        }
        if (!"published".equals(type)) {
            throw new IllegalArgumentException("unknown admin job result type");
        }
        requireFields(
                object,
                Set.of("type", "target_repository", "commit_sha", "local_sync"),
                "published result");
        return new AdminJobResult.Published(
                parseModuleRef(object.get("target_repository"), "target_repository"),
                requiredString(object, "commit_sha"),
                parseLocalSync(object.get("local_sync")));
    }

    private static LocalSyncResult parseLocalSync(JsonElement value) {
        JsonObject object = requiredObject(value, "local_sync");
        String type = requiredString(object, "type");
        return switch (type) {
            case "not-requested" -> {
                requireFields(object, Set.of("type"), "not-requested local sync");
                yield new LocalSyncResult.NotRequested();
            }
            case "succeeded" -> {
                requireFields(object, Set.of("type", "installed_module"), "successful local sync");
                yield new LocalSyncResult.Succeeded(
                        InferenceIdentityCodec.parseModuleContentIdentity(
                                object.get("installed_module")));
            }
            case "failed" -> {
                requireFields(object, Set.of("type", "failure"), "failed local sync");
                yield new LocalSyncResult.Failed(GatewayErrorCodec.parse(object.get("failure")));
            }
            default -> throw new IllegalArgumentException("unknown local_sync result type");
        };
    }

    private static JsonObject toJson(AdminJobResult result) {
        JsonObject value = new JsonObject();
        if (result instanceof AdminJobResult.Installed installed) {
            value.addProperty("type", "installed");
            value.add(
                    "installed_module", InferenceIdentityCodec.toJson(installed.installedModule()));
            return value;
        }
        AdminJobResult.Published published = (AdminJobResult.Published) result;
        value.addProperty("type", "published");
        value.addProperty("target_repository", published.targetRepository().toString());
        value.addProperty("commit_sha", published.commitSha());
        value.add("local_sync", toJson(published.localSync()));
        return value;
    }

    private static JsonObject toJson(LocalSyncResult result) {
        JsonObject value = new JsonObject();
        if (result instanceof LocalSyncResult.NotRequested) {
            value.addProperty("type", "not-requested");
        } else if (result instanceof LocalSyncResult.Succeeded succeeded) {
            value.addProperty("type", "succeeded");
            value.add(
                    "installed_module", InferenceIdentityCodec.toJson(succeeded.installedModule()));
        } else {
            LocalSyncResult.Failed failed = (LocalSyncResult.Failed) result;
            value.addProperty("type", "failed");
            value.add("failure", GatewayErrorCodec.toJson(failed.failure()));
        }
        return value;
    }

    private static void requirePhaseSemantics(
            JobType type,
            ModuleRef requested,
            JobPhase phase,
            Optional<AdminJobResult> result,
            Optional<GatewayErrorCodec.Error> failure) {
        if (phase == JobPhase.QUEUED || phase == JobPhase.RUNNING) {
            if (result.isPresent() || failure.isPresent()) {
                throw new IllegalArgumentException("active jobs cannot have an outcome");
            }
            return;
        }
        if (phase == JobPhase.FAILED) {
            if (failure.isEmpty() || result.isPresent()) {
                throw new IllegalArgumentException("FAILED requires failure and no result");
            }
            return;
        }
        if (result.isEmpty() || failure.isPresent()) {
            throw new IllegalArgumentException("successful jobs require result and no failure");
        }
        AdminJobResult outcome = result.orElseThrow();
        if (type == JobType.INSTALL) {
            if (phase != JobPhase.SUCCEEDED
                    || !(outcome instanceof AdminJobResult.Installed installed)
                    || !installed.installedModule().moduleRef().equals(requested)) {
                throw new IllegalArgumentException(
                        "INSTALL success requires the requested installed module");
            }
            return;
        }
        if (!(outcome instanceof AdminJobResult.Published published)
                || !published.targetRepository().equals(requested)) {
            throw new IllegalArgumentException(
                    "PUBLISH success requires the requested target repository");
        }
        boolean localFailed = published.localSync() instanceof LocalSyncResult.Failed;
        if ((phase == JobPhase.PARTIAL) != localFailed) {
            throw new IllegalArgumentException(
                    "PARTIAL is exactly a publication with failed local sync");
        }
    }

    private static ModuleRef parseModuleRef(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a module ref string");
        }
        String text = value.getAsString();
        return ModuleRef.parse(text)
                .filter(ref -> ref.toString().equals(text))
                .orElseThrow(
                        () -> new IllegalArgumentException(path + " must be canonical owner/name"));
    }

    private static <E extends Enum<E>> E requiredEnum(
            JsonObject object, String field, Class<E> type) {
        String text = requiredString(object, field);
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown " + field + ": " + text, error);
        }
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        requireFields(object, fields, path);
        return object;
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.getAsString();
    }

    private static void requireFields(JsonObject object, Set<String> expected, String path) {
        if (!object.keySet().equals(expected)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        java.util.HashSet<String> result = new java.util.HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }
}
