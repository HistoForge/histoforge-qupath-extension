package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.data.MetricTypes;

/** Process-local work and persisted-state outcomes for image actions. */
public final class RunResults {

    private RunResults() {}

    /** Closed result vocabulary for one requested or resolved model unit. */
    public enum ScopeOutcomeType {
        PRODUCED,
        SUCCEEDED_EMPTY,
        SUCCEEDED_NO_ADDITIONS,
        CLEARED_INELIGIBLE,
        CLEARED_ORPHANED,
        SKIPPED_PRESENT,
        SKIPPED_INELIGIBLE,
        SKIPPED_NO_PARENTS,
        BLOCKED_PREREQUISITE,
        FAILED,
        CANCELLED
    }

    /** Closed image-level reduction after model, metric, and lifecycle phases finish. */
    public enum ImageOutcomeType {
        SUCCEEDED,
        SUCCEEDED_NO_HIERARCHY_CHANGE,
        PARTIAL,
        CANCELLED,
        CONFLICT,
        FAILED
    }

    /** Closed machine-actionable failure boundary; messages remain diagnostic. */
    public enum FailureType {
        INVALID_REQUEST,
        LOCAL_CAPACITY,
        RESOURCE_LIMIT,
        UNSUPPORTED_MODULE,
        PREREQUISITE,
        CALIBRATION,
        CORRUPT_IMAGE_STATE,
        EXTRACTION,
        SUBMISSION,
        TIMEOUT,
        BACKEND,
        RESPONSE_VALIDATION,
        INSUFFICIENT_CONTEXT,
        PROVENANCE,
        SCRIPT_CONTRACT,
        FILL_REQUIRES_REDO,
        CROSS_MODE_REQUIRES_REDO,
        METRICS,
        PERSISTENCE,
        VIEWER_REFRESH,
        REVISION_CONFLICT,
        CANCELLED
    }

    /** Exact requested target or concrete logical output that owns one scope outcome. */
    public sealed interface OutcomeKey permits RequestedTarget, ExecutionOutput {}

    /** A selected producer/target before concrete scope expansion, or when expansion is blocked. */
    public record RequestedTarget(
            InferenceIdentityCodec.ModelStepIdentity producer,
            ActionSelectionCodec.TargetSelector target)
            implements OutcomeKey {
        public RequestedTarget {
            producer = Objects.requireNonNull(producer, "producer");
            target = Objects.requireNonNull(target, "target");
        }
    }

    /** One concrete logical producer/scope output after recipe expansion. */
    public record ExecutionOutput(ExecutionScopeCodec.OutputKey output) implements OutcomeKey {
        public ExecutionOutput {
            output = Objects.requireNonNull(output, "output");
        }
    }

    /** One coded failure owned by its containing scope, metric region, or image. */
    public record FailureDetail(FailureType type, String message) {
        public FailureDetail {
            type = Objects.requireNonNull(type, "type");
            message = CanonicalCodec.requireNonblank(message, "message");
        }
    }

    /**
     * Recipe work before the enclosing image save decides persisted-state truth.
     *
     * <p>{@code changesHierarchy} records whether this scope contributed a model delta to its
     * successful target transaction. It is deliberately not exposed in the final result: after
     * lifecycle finalization it becomes the narrower {@code hierarchySaved} fact.
     */
    public record ScopeWorkResult(
            OutcomeKey key,
            ScopeOutcomeType type,
            boolean changesHierarchy,
            Optional<BackendExecutionCodec.BackendExecutionRef> execution,
            Optional<FailureDetail> failure) {
        public ScopeWorkResult {
            key = Objects.requireNonNull(key, "key");
            type = Objects.requireNonNull(type, "type");
            execution = Objects.requireNonNull(execution, "execution");
            failure = Objects.requireNonNull(failure, "failure");
            requireFailureShape(type, failure);
            requireExecutionShape(type, execution);
            if (changesHierarchy && !mayChangeHierarchy(type)) {
                throw new IllegalArgumentException(
                        "scope outcome type cannot contribute a hierarchy delta");
            }
            if (type == ScopeOutcomeType.PRODUCED && !changesHierarchy) {
                throw new IllegalArgumentException("PRODUCED must add hierarchy output");
            }
            if ((type == ScopeOutcomeType.CLEARED_INELIGIBLE
                            || type == ScopeOutcomeType.CLEARED_ORPHANED)
                    && !changesHierarchy) {
                throw new IllegalArgumentException("clear outcomes must remove hierarchy output");
            }
        }

        /** Bind this work result to the enclosing target transaction's successful image save. */
        public ScopeOutcome afterPersistence(boolean targetTransactionSaved) {
            return new ScopeOutcome(
                    key, type, targetTransactionSaved && changesHierarchy, execution, failure);
        }
    }

    /** Final scoped result after the QuPath persistence outcome is known. */
    public record ScopeOutcome(
            OutcomeKey key,
            ScopeOutcomeType type,
            boolean hierarchySaved,
            Optional<BackendExecutionCodec.BackendExecutionRef> execution,
            Optional<FailureDetail> failure) {
        public ScopeOutcome {
            key = Objects.requireNonNull(key, "key");
            type = Objects.requireNonNull(type, "type");
            execution = Objects.requireNonNull(execution, "execution");
            failure = Objects.requireNonNull(failure, "failure");
            requireFailureShape(type, failure);
            requireExecutionShape(type, execution);
            if (hierarchySaved && !mayChangeHierarchy(type)) {
                throw new IllegalArgumentException(
                        "scope outcome type cannot have saved hierarchy output");
            }
        }
    }

    /** Nonfatal audit warning for attributed output missing its generation record. */
    public record MissingGenerationWarning(
            ExecutionScopeCodec.OutputKey output, UUID objectId, String message) {
        public MissingGenerationWarning {
            output = Objects.requireNonNull(output, "output");
            objectId = Objects.requireNonNull(objectId, "objectId");
            message = CanonicalCodec.requireNonblank(message, "message");
        }
    }

    /** Complete terminal state and work truth for one image. */
    public record ImageRunResult(
            String imageId,
            ImageOutcomeType outcome,
            boolean hierarchySaved,
            List<ScopeOutcome> scopes,
            List<MissingGenerationWarning> warnings,
            List<FailureDetail> imageFailures,
            Optional<MetricTypes.MetricImageResult> metrics) {
        public ImageRunResult {
            imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
            outcome = Objects.requireNonNull(outcome, "outcome");
            scopes = immutableList(scopes, "scopes");
            warnings = immutableList(warnings, "warnings");
            imageFailures = immutableList(imageFailures, "imageFailures");
            metrics = Objects.requireNonNull(metrics, "metrics");
            requireUnique(scopes, ScopeOutcome::key, "scope outcome keys");
            requireUnique(warnings, MissingGenerationWarning::objectId, "warning object IDs");
            MetricTypes.MetricImageResult metricResult = metrics.orElse(null);
            if (metricResult != null && !metricResult.sourceRevision().imageId().equals(imageId)) {
                throw new IllegalArgumentException("metric result belongs to another image");
            }
            if (outcome == ImageOutcomeType.SUCCEEDED && !hierarchySaved) {
                throw new IllegalArgumentException("SUCCEEDED requires a saved hierarchy delta");
            }
            if ((outcome == ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE
                            || outcome == ImageOutcomeType.CANCELLED
                            || outcome == ImageOutcomeType.CONFLICT
                            || outcome == ImageOutcomeType.FAILED)
                    && hierarchySaved) {
                throw new IllegalArgumentException(
                        "image outcome cannot carry a saved hierarchy delta");
            }
            if (scopes.stream().anyMatch(ScopeOutcome::hierarchySaved) && !hierarchySaved) {
                throw new IllegalArgumentException(
                        "saved scope output requires image hierarchySaved");
            }
        }
    }

    /** One action ID with independent per-image terminal results. */
    public record BatchRunResult(String actionId, List<ImageRunResult> images) {
        public BatchRunResult {
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            images = immutableList(images, "images");
            if (images.isEmpty()) {
                throw new IllegalArgumentException("images must be nonempty");
            }
            requireUnique(images, ImageRunResult::imageId, "batch image IDs");
        }
    }

    private static void requireFailureShape(
            ScopeOutcomeType type, Optional<FailureDetail> failure) {
        boolean requiresFailure =
                type == ScopeOutcomeType.BLOCKED_PREREQUISITE
                        || type == ScopeOutcomeType.FAILED
                        || type == ScopeOutcomeType.CANCELLED;
        if (requiresFailure != failure.isPresent()) {
            throw new IllegalArgumentException(
                    "scope failure is present exactly for blocked, failed, or cancelled outcomes");
        }
    }

    private static void requireExecutionShape(
            ScopeOutcomeType type, Optional<BackendExecutionCodec.BackendExecutionRef> execution) {
        boolean requiresExecution =
                type == ScopeOutcomeType.PRODUCED
                        || type == ScopeOutcomeType.SUCCEEDED_EMPTY
                        || type == ScopeOutcomeType.SUCCEEDED_NO_ADDITIONS;
        if (requiresExecution && execution.isEmpty()) {
            throw new IllegalArgumentException(
                    "successful inference outcome requires its backend execution reference");
        }
        if ((type == ScopeOutcomeType.SKIPPED_PRESENT
                        || type == ScopeOutcomeType.SKIPPED_INELIGIBLE
                        || type == ScopeOutcomeType.SKIPPED_NO_PARENTS
                        || type == ScopeOutcomeType.BLOCKED_PREREQUISITE
                        || type == ScopeOutcomeType.CLEARED_INELIGIBLE
                        || type == ScopeOutcomeType.CLEARED_ORPHANED)
                && execution.isPresent()) {
            throw new IllegalArgumentException(
                    "scope outcome without backend execution must not carry a reference");
        }
    }

    private static boolean mayChangeHierarchy(ScopeOutcomeType type) {
        return type == ScopeOutcomeType.PRODUCED
                || type == ScopeOutcomeType.SUCCEEDED_EMPTY
                || type == ScopeOutcomeType.SUCCEEDED_NO_ADDITIONS
                || type == ScopeOutcomeType.CLEARED_INELIGIBLE
                || type == ScopeOutcomeType.CLEARED_ORPHANED;
    }

    private static <T> List<T> immutableList(List<T> values, String path) {
        Objects.requireNonNull(values, path);
        List<T> result = new ArrayList<>(values.size());
        for (T value : values) {
            result.add(Objects.requireNonNull(value, path + " item"));
        }
        return List.copyOf(result);
    }

    private static <T, K> void requireUnique(List<T> values, Function<T, K> key, String path) {
        Set<K> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(Objects.requireNonNull(key.apply(value), path + " key"))) {
                throw new IllegalArgumentException(path + " must be unique");
            }
        }
    }
}
