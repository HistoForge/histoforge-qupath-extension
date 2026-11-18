package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded durable model-run status projection over immutable artifacts. */
public final class ModelRunStatusCodec {

    public static final int SCHEMA_VERSION = ModelRunRequestCodec.SCHEMA_VERSION;

    private static final Set<String> REQUIRED_FIELDS =
            Set.of(
                    "schema_version",
                    "model_run_id",
                    "phase",
                    "request_artifact",
                    "completed_unit_count",
                    "total_unit_count",
                    "phase_deadline");
    private static final Set<String> OPTIONAL_FIELDS = Set.of("terminal_manifest", "failure");

    private ModelRunStatusCodec() {}

    /** Closed durable-run lifecycle; PARTIAL is terminal. */
    public enum Phase {
        QUEUED,
        RUNNING,
        PARTIAL,
        COMPLETE,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    /** Small mutable projection whose full collections remain in immutable manifests. */
    public record Status(
            int schemaVersion,
            String modelRunId,
            Phase phase,
            ArtifactRefCodec.ArtifactRef requestArtifact,
            int completedUnitCount,
            int totalUnitCount,
            Optional<ArtifactRefCodec.ArtifactRef> terminalManifest,
            Optional<GatewayErrorCodec.Error> failure,
            Instant phaseDeadline) {
        public Status {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported model run status schema_version");
            }
            modelRunId = CanonicalCodec.requireCanonicalUuid(modelRunId, "model_run_id");
            phase = Objects.requireNonNull(phase, "phase");
            requestArtifact = Objects.requireNonNull(requestArtifact, "request_artifact");
            completedUnitCount =
                    CanonicalCodec.requireNonnegativeInt32(
                            completedUnitCount, "completed_unit_count");
            totalUnitCount =
                    CanonicalCodec.requireNonnegativeInt32(totalUnitCount, "total_unit_count");
            terminalManifest = Objects.requireNonNull(terminalManifest, "terminal_manifest");
            failure = Objects.requireNonNull(failure, "failure");
            phaseDeadline = Objects.requireNonNull(phaseDeadline, "phase_deadline");
            CanonicalCodec.rfc3339Millis(phaseDeadline, "phase_deadline");
            if (completedUnitCount > totalUnitCount) {
                throw new IllegalArgumentException(
                        "completed_unit_count cannot exceed total_unit_count");
            }
            requirePhasePayload(phase, terminalManifest, failure);
        }
    }

    /** Parse one closed status and enforce its phase/payload combination. */
    public static Status parse(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("model run status must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        Set<String> fields = object.keySet();
        Set<String> allowed = new HashSet<>(REQUIRED_FIELDS);
        allowed.addAll(OPTIONAL_FIELDS);
        if (!fields.containsAll(REQUIRED_FIELDS) || !allowed.containsAll(fields)) {
            throw new IllegalArgumentException("model run status has missing or unknown fields");
        }
        return new Status(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "model_run_id"),
                exactPhase(object.get("phase")),
                ArtifactRefCodec.parse(object.get("request_artifact")),
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("completed_unit_count"), "completed_unit_count"),
                CanonicalCodec.requireNonnegativeInt32(
                        object.get("total_unit_count"), "total_unit_count"),
                object.has("terminal_manifest")
                        ? Optional.of(ArtifactRefCodec.parse(object.get("terminal_manifest")))
                        : Optional.empty(),
                object.has("failure")
                        ? Optional.of(GatewayErrorCodec.parse(object.get("failure")))
                        : Optional.empty(),
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "phase_deadline"), "phase_deadline"));
    }

    /** Bind status to the exact request and optional downloaded terminal manifest. */
    public static Status validateProjection(
            JsonElement value,
            ModelRunRequestCodec.ModelRunRequest request,
            ExecutionLimitsCodec.ModelRunLimits limits,
            Optional<JsonElement> terminalManifestValue) {
        Status status = parse(value);
        ModelRunRequestCodec.validateResourceLimits(request, limits);
        Objects.requireNonNull(terminalManifestValue, "terminal_manifest_value");
        if (!status.modelRunId().equals(request.modelRunId())) {
            throw new IllegalArgumentException(
                    "status model_run_id does not match model run request");
        }
        requireCanonicalJsonArtifact(
                status.requestArtifact(), ModelRunRequestCodec.toJson(request), "request_artifact");

        int totalUnits = 0;
        for (var plan : ModelRunPublicationCodec.buildExpectedPlans(request, limits).values()) {
            totalUnits = Math.addExact(totalUnits, ModelExecutionPlanCodec.unitCount(plan));
        }
        if (status.totalUnitCount() != totalUnits) {
            throw new IllegalArgumentException(
                    "status total_unit_count does not match generated plans");
        }
        if ((status.phase() == Phase.QUEUED || status.phase() == Phase.TIMED_OUT)
                && status.completedUnitCount() != 0) {
            throw new IllegalArgumentException(
                    status.phase() + " status must report zero completed units");
        }

        if (status.phase() == Phase.PARTIAL || status.phase() == Phase.COMPLETE) {
            JsonElement terminalValue =
                    terminalManifestValue.orElseThrow(
                            () ->
                                    new IllegalArgumentException(
                                            status.phase()
                                                    + " status requires downloaded terminal manifest"));
            var manifest =
                    ModelRunPublicationCodec.validateManifest(terminalValue, request, limits);
            boolean expectedComplete = status.phase() == Phase.COMPLETE;
            if (manifest.complete() != expectedComplete) {
                throw new IllegalArgumentException(
                        "status phase disagrees with terminal manifest completeness");
            }
            if (!status.failure().equals(manifest.failure())) {
                throw new IllegalArgumentException(
                        "status failure does not match terminal manifest");
            }
            requireCanonicalJsonArtifact(
                    status.terminalManifest().orElseThrow(),
                    ModelRunPublicationCodec.toJson(manifest),
                    "terminal_manifest");
            if (status.completedUnitCount()
                    != ModelRunPublicationCodec.completedUnitCount(manifest)) {
                throw new IllegalArgumentException(
                        "status completed_unit_count does not match terminal manifest");
            }
        } else if (terminalManifestValue.isPresent()) {
            throw new IllegalArgumentException(
                    status.phase() + " status cannot be paired with a terminal manifest");
        }
        return status;
    }

    /** Encode one status using exact lower-snake fields and uppercase phase spelling. */
    public static JsonObject toJson(Status status) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", status.schemaVersion());
        value.addProperty("model_run_id", status.modelRunId());
        value.addProperty("phase", status.phase().name());
        value.add("request_artifact", ArtifactRefCodec.toJson(status.requestArtifact()));
        value.addProperty("completed_unit_count", status.completedUnitCount());
        value.addProperty("total_unit_count", status.totalUnitCount());
        status.terminalManifest()
                .ifPresent(
                        manifest ->
                                value.add("terminal_manifest", ArtifactRefCodec.toJson(manifest)));
        status.failure()
                .ifPresent(failure -> value.add("failure", GatewayErrorCodec.toJson(failure)));
        value.addProperty(
                "phase_deadline",
                CanonicalCodec.rfc3339Millis(status.phaseDeadline(), "phase_deadline"));
        return value;
    }

    private static void requireCanonicalJsonArtifact(
            ArtifactRefCodec.ArtifactRef artifact, JsonElement value, String path) {
        byte[] bytes = CanonicalCodec.canonicalBytes(value);
        if (!artifact.mediaType().equals("application/json")) {
            throw new IllegalArgumentException(path + " must use application/json");
        }
        if (artifact.contentEncoding().isPresent()) {
            throw new IllegalArgumentException(path + " must not use content encoding");
        }
        if (artifact.sizeBytes() != bytes.length
                || !artifact.sha256().equals(CanonicalCodec.sha256(bytes))) {
            throw new IllegalArgumentException(
                    path + " does not bind the supplied canonical JSON bytes");
        }
    }

    private static void requirePhasePayload(
            Phase phase,
            Optional<ArtifactRefCodec.ArtifactRef> terminalManifest,
            Optional<GatewayErrorCodec.Error> failure) {
        boolean hasManifest = terminalManifest.isPresent();
        boolean hasFailure = failure.isPresent();
        switch (phase) {
            case QUEUED, RUNNING, CANCELLED, TIMED_OUT -> {
                if (hasManifest || hasFailure) {
                    throw new IllegalArgumentException(
                            phase + " status cannot carry terminal payloads");
                }
            }
            case PARTIAL -> {
                if (!hasManifest) {
                    throw new IllegalArgumentException("PARTIAL status requires terminal_manifest");
                }
            }
            case COMPLETE -> {
                if (!hasManifest || hasFailure) {
                    throw new IllegalArgumentException(
                            phase + " status requires only terminal_manifest");
                }
            }
            case FAILED -> {
                if (hasManifest || !hasFailure) {
                    throw new IllegalArgumentException("FAILED status requires only failure");
                }
            }
        }
    }

    private static Phase exactPhase(JsonElement value) {
        String text = requiredString(value, "phase");
        try {
            return Phase.valueOf(text);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported model run phase: " + text, exception);
        }
    }

    private static String requiredString(JsonObject value, String field) {
        return requiredString(value.get(field), field);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }
}
