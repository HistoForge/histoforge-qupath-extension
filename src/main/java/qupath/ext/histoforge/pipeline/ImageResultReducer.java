package qupath.ext.histoforge.pipeline;

import java.util.List;
import java.util.Optional;
import qupath.ext.histoforge.data.MetricTypes;

/** Closed reduction from recipe work plus lifecycle truth to one terminal image result. */
final class ImageResultReducer {

    private ImageResultReducer() {}

    static RunResults.ImageRunResult completed(
            String imageId,
            RecipeExecutor.Result result,
            boolean hierarchySaved,
            Optional<RunResults.FailureDetail> viewerFailure,
            Optional<MetricTypes.MetricImageResult> metrics,
            List<RunResults.FailureDetail> metricImageFailures,
            boolean metricsOnly) {
        return completed(
                imageId,
                result.scopeResults(),
                warnings(result),
                hierarchySaved,
                viewerFailure,
                metrics,
                metricImageFailures,
                metricsOnly);
    }

    static RunResults.ImageRunResult completed(
            String imageId,
            List<RunResults.ScopeWorkResult> scopeResults,
            List<RunResults.MissingGenerationWarning> warnings,
            boolean hierarchySaved,
            Optional<RunResults.FailureDetail> viewerFailure,
            Optional<MetricTypes.MetricImageResult> metrics,
            List<RunResults.FailureDetail> metricImageFailures) {
        return completed(
                imageId,
                scopeResults,
                warnings,
                hierarchySaved,
                viewerFailure,
                metrics,
                metricImageFailures,
                false);
    }

    private static RunResults.ImageRunResult completed(
            String imageId,
            List<RunResults.ScopeWorkResult> scopeResults,
            List<RunResults.MissingGenerationWarning> warnings,
            boolean hierarchySaved,
            Optional<RunResults.FailureDetail> viewerFailure,
            Optional<MetricTypes.MetricImageResult> metrics,
            List<RunResults.FailureDetail> metricImageFailures,
            boolean metricsOnly) {
        boolean failedWork = scopeResults.stream().anyMatch(value -> value.failure().isPresent());
        boolean completedWork = scopeResults.stream().anyMatch(value -> value.failure().isEmpty());
        boolean failedMetrics =
                !metricImageFailures.isEmpty()
                        || metrics.map(value -> !value.complete()).orElse(false);
        boolean completedMetrics =
                metrics.map(value -> !value.regionResults().isEmpty()).orElse(false);
        List<RunResults.FailureDetail> imageFailures =
                java.util.stream.Stream.concat(viewerFailure.stream(), metricImageFailures.stream())
                        .toList();
        RunResults.ImageOutcomeType outcome;
        if (!imageFailures.isEmpty() || failedWork || failedMetrics) {
            outcome =
                    hierarchySaved
                                    || (!metricsOnly && completedWork)
                                    || (metricsOnly && completedMetrics)
                            ? RunResults.ImageOutcomeType.PARTIAL
                            : RunResults.ImageOutcomeType.FAILED;
        } else {
            outcome =
                    hierarchySaved
                            ? RunResults.ImageOutcomeType.SUCCEEDED
                            : RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE;
        }
        return work(
                imageId, outcome, scopeResults, imageFailures, hierarchySaved, warnings, metrics);
    }

    static RunResults.ImageRunResult imageFailure(
            String imageId,
            RunResults.ImageOutcomeType outcome,
            List<RunResults.ScopeWorkResult> work,
            RunResults.FailureDetail failure) {
        return work(imageId, outcome, work, List.of(failure), false);
    }

    static RunResults.ImageRunResult work(
            String imageId,
            RunResults.ImageOutcomeType outcome,
            List<RunResults.ScopeWorkResult> work,
            List<RunResults.FailureDetail> imageFailures,
            boolean hierarchySaved) {
        return work(imageId, outcome, work, imageFailures, hierarchySaved, List.of());
    }

    static RunResults.FailureDetail failure(RunResults.FailureType type, Throwable cause) {
        return new RunResults.FailureDetail(type, RecipeFailures.message(cause));
    }

    private static RunResults.ImageRunResult work(
            String imageId,
            RunResults.ImageOutcomeType outcome,
            List<RunResults.ScopeWorkResult> work,
            List<RunResults.FailureDetail> imageFailures,
            boolean hierarchySaved,
            List<RunResults.MissingGenerationWarning> warnings) {
        return work(
                imageId, outcome, work, imageFailures, hierarchySaved, warnings, Optional.empty());
    }

    private static RunResults.ImageRunResult work(
            String imageId,
            RunResults.ImageOutcomeType outcome,
            List<RunResults.ScopeWorkResult> work,
            List<RunResults.FailureDetail> imageFailures,
            boolean hierarchySaved,
            List<RunResults.MissingGenerationWarning> warnings,
            Optional<MetricTypes.MetricImageResult> metrics) {
        List<RunResults.ScopeOutcome> scopes =
                work.stream().map(value -> value.afterPersistence(hierarchySaved)).toList();
        return new RunResults.ImageRunResult(
                imageId, outcome, hierarchySaved, scopes, warnings, imageFailures, metrics);
    }

    private static List<RunResults.MissingGenerationWarning> warnings(
            RecipeExecutor.Result result) {
        if (result.appliedTransactions().isEmpty()) {
            return List.of();
        }
        // Each apply result describes the complete current missing-reference set; the last
        // successful transaction is therefore authoritative for the final working hierarchy.
        return warnings(
                result.appliedTransactions().getLast().applyResult().missingGenerationReferences());
    }

    static List<RunResults.MissingGenerationWarning> warnings(
            List<ModelOutputCommitter.MissingGenerationReference> references) {
        return references.stream()
                .map(
                        reference ->
                                new RunResults.MissingGenerationWarning(
                                        reference.output(),
                                        reference.objectId(),
                                        "object "
                                                + reference.objectId()
                                                + " references missing generation "
                                                + reference.generationId()))
                .toList();
    }
}
