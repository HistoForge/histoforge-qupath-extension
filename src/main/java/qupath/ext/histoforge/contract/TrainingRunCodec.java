package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec.TrainingRunRequest;
import qupath.ext.histoforge.contract.TrainingRunTypes.CancelledTrainingResult;
import qupath.ext.histoforge.contract.TrainingRunTypes.FailedTrainingResult;
import qupath.ext.histoforge.contract.TrainingRunTypes.SucceededTrainingResult;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingFinalization;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingProgress;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunAccepted;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunRecord;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunResult;

/** Closed schema-1 accepted and complete-record codec for managed training runs. */
public final class TrainingRunCodec {

    private static final Set<String> ACCEPTED_FIELDS = Set.of("run_id", "request_digest");
    private static final Set<String> PROGRESS_FIELDS = Set.of("completed_epochs", "total_epochs");
    private static final Set<String> FINALIZATION_FIELDS =
            Set.of("selected_weight_digest", "report_digest", "module_content_digest");
    private static final Set<String> RECORD_REQUIRED_FIELDS =
            Set.of(
                    "run_id",
                    "request",
                    "request_digest",
                    "trainer_build",
                    "state",
                    "progress",
                    "report_revision",
                    "created_at",
                    "updated_at");
    private static final Set<String> RECORD_OPTIONAL_FIELDS = Set.of("finalization", "result");
    private static final Set<String> STATES =
            Set.of("QUEUED", "RUNNING", "FINALIZING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> ACTIVE_STATES = Set.of("QUEUED", "RUNNING", "FINALIZING");
    private static final Set<String> TERMINAL_STATES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    private TrainingRunCodec() {}

    /** Parse the durable acceptance echo returned by run submission. */
    public static TrainingRunAccepted parseAccepted(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, ACCEPTED_FIELDS, "training run accepted");
        return new TrainingRunAccepted(
                CanonicalCodec.requireCanonicalUuid(
                        TrainingJson.string(object, "run_id"), "run_id"),
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "request_digest"), "request_digest"));
    }

    /** Require an acceptance response to identify the exact submitted request. */
    public static TrainingRunAccepted validateAcceptance(
            TrainingRunRequest request, JsonElement value, TrainingApiLimits limits) {
        TrainingRunAccepted accepted = parseAccepted(value);
        if (!accepted.requestDigest().equals(TrainingRunRequestCodec.digest(request, limits))) {
            throw new IllegalArgumentException(
                    "training run accepted request_digest does not match request");
        }
        return accepted;
    }

    /** Parse one complete public run record. */
    public static TrainingRunRecord parseRecord(JsonElement value, TrainingApiLimits limits) {
        Objects.requireNonNull(limits, "training API limits");
        JsonObject object =
                objectWithOptional(
                        value,
                        RECORD_REQUIRED_FIELDS,
                        RECORD_OPTIONAL_FIELDS,
                        "training run record");
        TrainingRunRequest request = TrainingRunRequestCodec.parse(object.get("request"), limits);
        String requestDigest =
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "request_digest"), "request_digest");
        if (!requestDigest.equals(TrainingRunRequestCodec.digest(request, limits))) {
            throw new IllegalArgumentException(
                    "training run request_digest does not match request");
        }
        String state = TrainingJson.enumValue(object.get("state"), STATES, "state");
        TrainingProgress progress = parseProgress(object.get("progress"), limits);
        Instant createdAt = parseTimestamp(object, "created_at");
        Instant updatedAt = parseTimestamp(object, "updated_at");
        requireOrderedTimes(createdAt, updatedAt);
        Optional<TrainingFinalization> finalization =
                object.has("finalization")
                        ? Optional.of(parseFinalization(object.get("finalization")))
                        : Optional.empty();
        Optional<TrainingRunResult> result =
                object.has("result")
                        ? Optional.of(parseResult(object.get("result"), state, limits))
                        : Optional.empty();
        requireTerminalResultPresence(state, result);
        if (Set.of("QUEUED", "RUNNING", "CANCELLED").contains(state) && finalization.isPresent()) {
            throw new IllegalArgumentException(state + " cannot have finalization");
        }
        if (Set.of("FINALIZING", "SUCCEEDED").contains(state) && finalization.isEmpty()) {
            throw new IllegalArgumentException(state + " requires finalization");
        }
        if (finalization.isPresent() && progress.completedEpochs() != progress.totalEpochs()) {
            throw new IllegalArgumentException(
                    "finalization requires every training epoch to be complete");
        }
        return new TrainingRunRecord(
                CanonicalCodec.requireCanonicalUuid(
                        TrainingJson.string(object, "run_id"), "run_id"),
                request,
                requestDigest,
                BuildIdentityCodec.parseSoftwareBuildIdentity(object.get("trainer_build")),
                state,
                progress,
                CanonicalCodec.requirePositiveInt32(
                        object.get("report_revision"), "report_revision"),
                createdAt,
                updatedAt,
                finalization,
                result);
    }

    public static JsonObject toJson(TrainingRunAccepted value) {
        TrainingRunAccepted checked = parseAccepted(encodeAccepted(value));
        return encodeAccepted(checked);
    }

    public static JsonObject toJson(TrainingRunRecord value, TrainingApiLimits limits) {
        return encodeRecord(parseRecord(encodeRecord(value, limits), limits), limits);
    }

    static TrainingProgress parseProgress(JsonElement value, TrainingApiLimits limits) {
        JsonObject object = TrainingJson.exactObject(value, PROGRESS_FIELDS, "training progress");
        int completed =
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("completed_epochs"), "completed_epochs");
        int total = CanonicalCodec.requirePositiveInt32(object.get("total_epochs"), "total_epochs");
        if (total > limits.report().maxEpochs()) {
            throw new IllegalArgumentException("total_epochs exceeds the training report limit");
        }
        if (completed > total) {
            throw new IllegalArgumentException("completed_epochs cannot exceed total_epochs");
        }
        return new TrainingProgress(completed, total);
    }

    static TrainingRunResult parseResult(
            JsonElement value, String state, TrainingApiLimits limits) {
        JsonObject object = TrainingJson.object(value, "training run result");
        String type = TrainingJson.string(object, "type");
        return switch (state) {
            case "SUCCEEDED" -> {
                TrainingJson.requireFields(object, Set.of("type"), "succeeded training result");
                if (!"succeeded".equals(type)) {
                    throw new IllegalArgumentException("SUCCEEDED requires a succeeded result");
                }
                yield new SucceededTrainingResult();
            }
            case "FAILED" -> {
                TrainingJson.requireFields(
                        object, Set.of("type", "error", "report_digest"), "failed training result");
                if (!"failed".equals(type)) {
                    throw new IllegalArgumentException("FAILED requires a failed result");
                }
                GatewayErrorCodec.Error error = GatewayErrorCodec.parse(object.get("error"));
                TrainingReportEvidenceCodec.validateFailureText(error, limits.report());
                yield new FailedTrainingResult(
                        error,
                        CanonicalCodec.requireSha256(
                                TrainingJson.string(object, "report_digest"), "report_digest"));
            }
            case "CANCELLED" -> {
                TrainingJson.requireFields(
                        object, Set.of("type", "report_digest"), "cancelled training result");
                if (!"cancelled".equals(type)) {
                    throw new IllegalArgumentException("CANCELLED requires a cancelled result");
                }
                yield new CancelledTrainingResult(
                        CanonicalCodec.requireSha256(
                                TrainingJson.string(object, "report_digest"), "report_digest"));
            }
            default ->
                    throw new IllegalArgumentException(
                            "only a terminal training run may have a result");
        };
    }

    static void requireTerminalResultPresence(String state, Optional<TrainingRunResult> result) {
        if (ACTIVE_STATES.contains(state) && result.isPresent()) {
            throw new IllegalArgumentException("an active training run cannot have a result");
        }
        if (TERMINAL_STATES.contains(state) && result.isEmpty()) {
            throw new IllegalArgumentException("a terminal training run requires a result");
        }
    }

    static Instant parseTimestamp(JsonObject object, String field) {
        return CanonicalCodec.requireRfc3339Millis(TrainingJson.string(object, field), field);
    }

    static void requireOrderedTimes(Instant createdAt, Instant updatedAt) {
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updated_at cannot precede created_at");
        }
    }

    static JsonObject encodeProgress(TrainingProgress value) {
        JsonObject result = new JsonObject();
        result.addProperty("completed_epochs", value.completedEpochs());
        result.addProperty("total_epochs", value.totalEpochs());
        return result;
    }

    static JsonObject encodeResult(TrainingRunResult value) {
        JsonObject result = new JsonObject();
        if (value instanceof SucceededTrainingResult) {
            result.addProperty("type", "succeeded");
        } else if (value instanceof FailedTrainingResult failed) {
            result.addProperty("type", "failed");
            result.add("error", GatewayErrorCodec.toJson(failed.error()));
            result.addProperty("report_digest", failed.reportDigest());
        } else if (value instanceof CancelledTrainingResult cancelled) {
            result.addProperty("type", "cancelled");
            result.addProperty("report_digest", cancelled.reportDigest());
        } else {
            throw new IllegalArgumentException("unsupported training run result");
        }
        return result;
    }

    private static TrainingFinalization parseFinalization(JsonElement value) {
        JsonObject object =
                TrainingJson.exactObject(value, FINALIZATION_FIELDS, "training finalization");
        return new TrainingFinalization(
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "selected_weight_digest"),
                        "selected_weight_digest"),
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "report_digest"), "report_digest"),
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "module_content_digest"),
                        "module_content_digest"));
    }

    private static JsonObject encodeAccepted(TrainingRunAccepted value) {
        Objects.requireNonNull(value, "training run accepted");
        JsonObject result = new JsonObject();
        result.addProperty("run_id", value.runId());
        result.addProperty("request_digest", value.requestDigest());
        return result;
    }

    private static JsonObject encodeRecord(TrainingRunRecord value, TrainingApiLimits limits) {
        Objects.requireNonNull(value, "training run record");
        JsonObject result = new JsonObject();
        result.addProperty("run_id", value.runId());
        result.add("request", TrainingRunRequestCodec.toJson(value.request(), limits));
        result.addProperty("request_digest", value.requestDigest());
        result.add("trainer_build", BuildIdentityCodec.toJson(value.trainerBuild()));
        result.addProperty("state", value.state());
        result.add("progress", encodeProgress(value.progress()));
        result.addProperty("report_revision", value.reportRevision());
        result.addProperty(
                "created_at", CanonicalCodec.rfc3339Millis(value.createdAt(), "created_at"));
        result.addProperty(
                "updated_at", CanonicalCodec.rfc3339Millis(value.updatedAt(), "updated_at"));
        value.finalization()
                .ifPresent(item -> result.add("finalization", encodeFinalization(item)));
        value.result().ifPresent(item -> result.add("result", encodeResult(item)));
        return result;
    }

    private static JsonObject encodeFinalization(TrainingFinalization value) {
        JsonObject result = new JsonObject();
        result.addProperty("selected_weight_digest", value.selectedWeightDigest());
        result.addProperty("report_digest", value.reportDigest());
        result.addProperty("module_content_digest", value.moduleContentDigest());
        return result;
    }

    private static JsonObject objectWithOptional(
            JsonElement value, Set<String> required, Set<String> optional, String path) {
        JsonObject object = TrainingJson.object(value, path);
        if (!object.keySet().containsAll(required)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        if (!allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }
}
