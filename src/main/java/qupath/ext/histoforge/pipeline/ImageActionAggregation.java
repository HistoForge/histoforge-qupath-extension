package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.data.MetricTypes;
import qupath.ext.histoforge.pipeline.RecoverableActionAttempt.ResumeOwnership;

/** Reduces independently persisted target results to one terminal process-local image result. */
final class ImageActionAggregation {

    private ImageActionAggregation() {}

    /** One terminal execution partition and the exact logical regions it owned. */
    record TargetResult(
            List<ExecutionScopeCodec.TargetRegion> targets,
            RunResults.ImageRunResult result,
            boolean automaticContinuationAllowed) {
        TargetResult(
                List<ExecutionScopeCodec.TargetRegion> targets, RunResults.ImageRunResult result) {
            this(targets, result, true);
        }

        TargetResult {
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            result = Objects.requireNonNull(result, "result");
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("target result must own at least one region");
            }
            if (targets.stream().map(ExecutionScopeCodec.TargetRegion::key).distinct().count()
                    != targets.size()) {
                throw new IllegalArgumentException("target result regions must be unique");
            }
        }
    }

    /** Do not reopen a stale viewer or let an old recovery action mutate newer results. */
    static boolean allowsAutomaticContinuation(List<TargetResult> targets) {
        return targets.stream()
                .noneMatch(
                        target ->
                                !target.automaticContinuationAllowed()
                                        || target.result().imageFailures().stream()
                                                .anyMatch(
                                                        failure ->
                                                                failure.type()
                                                                        == RunResults.FailureType
                                                                                .VIEWER_REFRESH));
    }

    static RunResults.ImageRunResult reduce(
            String imageId,
            List<TargetResult> targetResults,
            Optional<RunResults.ImageRunResult> metricResult) {
        List<TargetResult> targets = List.copyOf(targetResults);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targetResults must be nonempty");
        }
        List<RunResults.ImageRunResult> results = new ArrayList<>();
        Set<ExecutionScopeCodec.RegionKey> regions = new LinkedHashSet<>();
        for (TargetResult target : targets) {
            for (ExecutionScopeCodec.TargetRegion region : target.targets()) {
                if (!regions.add(region.key())) {
                    throw new IllegalArgumentException(
                            "independent target results contain a duplicate region");
                }
            }
            results.add(target.result());
        }
        Objects.requireNonNull(metricResult, "metricResult").ifPresent(results::add);

        Map<RunResults.OutcomeKey, RunResults.ScopeOutcome> scopes = new LinkedHashMap<>();
        Map<UUID, RunResults.MissingGenerationWarning> warnings = new LinkedHashMap<>();
        List<RunResults.FailureDetail> failures = new ArrayList<>();
        Optional<MetricTypes.MetricImageResult> metrics = Optional.empty();
        boolean hierarchySaved = false;
        boolean completedTargetWork = false;
        boolean anyTargetProblem = false;
        boolean everyTargetProblemCancelled = true;
        boolean everyTargetProblemConflict = true;
        boolean metricProblem = false;

        for (int index = 0; index < results.size(); index++) {
            RunResults.ImageRunResult result = results.get(index);
            boolean targetResult = index < targets.size();
            if (!Objects.requireNonNull(imageId, "imageId").equals(result.imageId())) {
                throw new IllegalArgumentException(
                        "target result belongs to another project image");
            }
            hierarchySaved |= result.hierarchySaved();
            for (RunResults.ScopeOutcome scope : result.scopes()) {
                if (scopes.putIfAbsent(scope.key(), scope) != null) {
                    throw new IllegalArgumentException(
                            "independent target results contain a duplicate scope key");
                }
                if (targetResult) {
                    completedTargetWork |= scope.failure().isEmpty();
                }
            }
            result.warnings().forEach(warning -> warnings.put(warning.objectId(), warning));
            failures.addAll(result.imageFailures());
            if (result.metrics().isPresent()) {
                if (metrics.isPresent()) {
                    throw new IllegalArgumentException(
                            "only the final image-wide metrics pass may publish metrics");
                }
                metrics = result.metrics();
            }

            boolean problem =
                    result.outcome() == RunResults.ImageOutcomeType.PARTIAL
                            || result.outcome() == RunResults.ImageOutcomeType.CANCELLED
                            || result.outcome() == RunResults.ImageOutcomeType.CONFLICT
                            || result.outcome() == RunResults.ImageOutcomeType.FAILED;
            if (problem) {
                if (targetResult) {
                    anyTargetProblem = true;
                    everyTargetProblemCancelled &=
                            result.outcome() == RunResults.ImageOutcomeType.CANCELLED;
                    everyTargetProblemConflict &=
                            result.outcome() == RunResults.ImageOutcomeType.CONFLICT;
                } else {
                    metricProblem = true;
                }
            } else if (targetResult) {
                completedTargetWork = true;
            }
        }

        RunResults.ImageOutcomeType outcome;
        if (anyTargetProblem) {
            if (hierarchySaved || completedTargetWork) {
                outcome = RunResults.ImageOutcomeType.PARTIAL;
            } else if (everyTargetProblemCancelled) {
                outcome = RunResults.ImageOutcomeType.CANCELLED;
            } else if (everyTargetProblemConflict) {
                outcome = RunResults.ImageOutcomeType.CONFLICT;
            } else {
                outcome = RunResults.ImageOutcomeType.FAILED;
            }
        } else if (metricProblem) {
            outcome = RunResults.ImageOutcomeType.PARTIAL;
        } else {
            outcome =
                    hierarchySaved
                            ? RunResults.ImageOutcomeType.SUCCEEDED
                            : RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE;
        }

        return new RunResults.ImageRunResult(
                imageId,
                outcome,
                hierarchySaved,
                List.copyOf(scopes.values()),
                List.copyOf(warnings.values()),
                List.copyOf(failures),
                metrics);
    }

    /** Regions whose failed/cancelled model transaction must not reuse unchanged old output. */
    static Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> failedMetricRegions(
            List<TargetResult> targetResults) {
        Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> failures =
                new LinkedHashMap<>();
        Set<ExecutionScopeCodec.RegionKey> allRegions = new LinkedHashSet<>();
        for (TargetResult targetResult : List.copyOf(targetResults)) {
            Map<ExecutionScopeCodec.RegionKey, List<RunResults.ScopeOutcome>> scopesByRegion =
                    new LinkedHashMap<>();
            for (ExecutionScopeCodec.TargetRegion target : targetResult.targets()) {
                if (!allRegions.add(target.key())) {
                    throw new IllegalArgumentException(
                            "independent target results contain a duplicate region");
                }
                scopesByRegion.put(target.key(), new ArrayList<>());
            }
            for (RunResults.ScopeOutcome scope : targetResult.result().scopes()) {
                ExecutionScopeCodec.RegionKey region = region(scope.key());
                List<RunResults.ScopeOutcome> owned = scopesByRegion.get(region);
                if (owned == null) {
                    throw new IllegalArgumentException(
                            "scope outcome belongs to another execution partition");
                }
                owned.add(scope);
            }

            for (Map.Entry<ExecutionScopeCodec.RegionKey, List<RunResults.ScopeOutcome>> entry :
                    scopesByRegion.entrySet()) {
                Optional<RunResults.FailureDetail> scopedFailure =
                        entry.getValue().stream()
                                .flatMap(scope -> scope.failure().stream())
                                .findFirst();
                if (scopedFailure.isPresent()) {
                    failures.put(entry.getKey(), metricFailure(scopedFailure.orElseThrow()));
                    continue;
                }
                if (terminalFailure(targetResult.result().outcome())) {
                    RunResults.FailureDetail failure =
                            targetResult.result().imageFailures().stream()
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "terminal target failure lacks a"
                                                                    + " scoped or image failure"));
                    failures.put(entry.getKey(), metricFailure(failure));
                } else if (entry.getValue().isEmpty()
                        && targetResult.result().outcome() == RunResults.ImageOutcomeType.PARTIAL) {
                    RunResults.FailureDetail failure =
                            targetResult.result().imageFailures().stream()
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "partial target without scope outcomes"
                                                                    + " lacks an image failure"));
                    failures.put(entry.getKey(), metricFailure(failure));
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    static RunResults.ImageRunResult cancelled(
            ResumeOwnership ownership, CancellationException cancellation) {
        ImageExecutionPlan plan = ownership.lastObservedPlan().orElse(null);
        if (plan == null) {
            return imageFailure(
                    ownership.lastObservedHandle().imageId(),
                    RunResults.FailureType.CANCELLED,
                    cancellation,
                    ownership.observedWork());
        }
        RunResults.FailureDetail detail =
                ImageResultReducer.failure(RunResults.FailureType.CANCELLED, cancellation);
        return ImageResultReducer.work(
                plan.imageId(),
                RunResults.ImageOutcomeType.CANCELLED,
                RecipeOutcomes.cancelledTransaction(
                        plan, plan.targetRegions(), ownership.observedWork(), detail),
                List.of(),
                false);
    }

    static RunResults.ImageRunResult cancelled(ImageExecutionPlan plan, String message) {
        CancellationException cancellation = new CancellationException(message);
        RunResults.FailureDetail detail =
                ImageResultReducer.failure(RunResults.FailureType.CANCELLED, cancellation);
        return ImageResultReducer.work(
                plan.imageId(),
                RunResults.ImageOutcomeType.CANCELLED,
                RecipeOutcomes.cancelledTransaction(plan, plan.targetRegions(), List.of(), detail),
                List.of(),
                false);
    }

    static RunResults.ImageRunResult imageFailure(
            String imageId,
            RunResults.FailureType type,
            Throwable failure,
            List<RunResults.ScopeWorkResult> observedWork) {
        RunResults.ImageOutcomeType outcome =
                type == RunResults.FailureType.CANCELLED
                        ? RunResults.ImageOutcomeType.CANCELLED
                        : type == RunResults.FailureType.REVISION_CONFLICT
                                ? RunResults.ImageOutcomeType.CONFLICT
                                : RunResults.ImageOutcomeType.FAILED;
        return ImageResultReducer.imageFailure(
                imageId, outcome, observedWork, ImageResultReducer.failure(type, failure));
    }

    static RunResults.ImageRunResult failedTarget(
            ImageExecutionPlan plan, RunResults.FailureDetail detail) {
        return failedTarget(plan, List.of(), detail);
    }

    static RunResults.ImageRunResult failedTarget(
            ImageExecutionPlan plan,
            List<RunResults.ScopeWorkResult> observedWork,
            RunResults.FailureDetail detail) {
        RunResults.ImageOutcomeType outcome =
                detail.type() == RunResults.FailureType.REVISION_CONFLICT
                        ? RunResults.ImageOutcomeType.CONFLICT
                        : detail.type() == RunResults.FailureType.CANCELLED
                                ? RunResults.ImageOutcomeType.CANCELLED
                                : RunResults.ImageOutcomeType.FAILED;
        return ImageResultReducer.work(
                plan.imageId(),
                outcome,
                RecipeOutcomes.failedTransaction(plan, plan.targetRegions(), observedWork, detail),
                List.of(),
                false);
    }

    private static ExecutionScopeCodec.RegionKey region(RunResults.OutcomeKey key) {
        if (key instanceof RunResults.ExecutionOutput output) {
            return ExecutionScopeCodec.regionOf(output.output().scope());
        }
        ActionSelectionCodec.TargetSelector target = ((RunResults.RequestedTarget) key).target();
        return switch (target) {
            case ActionSelectionCodec.RoiTarget roi -> new ExecutionScopeCodec.Roi(roi.roiId());
            case ActionSelectionCodec.ParentTarget parent -> parent.region();
            case ActionSelectionCodec.SlideTarget ignored -> new ExecutionScopeCodec.Slide();
        };
    }

    private static boolean terminalFailure(RunResults.ImageOutcomeType outcome) {
        return outcome == RunResults.ImageOutcomeType.FAILED
                || outcome == RunResults.ImageOutcomeType.CANCELLED
                || outcome == RunResults.ImageOutcomeType.CONFLICT;
    }

    private static RunResults.FailureDetail metricFailure(RunResults.FailureDetail failure) {
        return new RunResults.FailureDetail(
                RunResults.FailureType.METRICS,
                "metrics skipped because model work failed: " + failure.message());
    }
}
