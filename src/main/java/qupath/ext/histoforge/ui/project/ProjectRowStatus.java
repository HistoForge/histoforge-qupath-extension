package qupath.ext.histoforge.ui.project;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import qupath.ext.histoforge.pipeline.RunResults;

/**
 * Pure display projection of current output presence and the latest process-local action result.
 */
final class ProjectRowStatus {

    private ProjectRowStatus() {}

    /** Describe only currently present attributed output; never infer action completeness. */
    static String outputPresence(int presentRois, int totalRois, boolean invalidProvenance) {
        if (presentRois < 0 || totalRois < 0 || presentRois > totalRois) {
            throw new IllegalArgumentException("ROI output counts are inconsistent");
        }
        if (invalidProvenance) {
            return StatusStrings.OUTPUT_STATE_INVALID;
        }
        if (totalRois == 0 || presentRois == 0) {
            return "—";
        }
        return StatusStrings.OUTPUT_PRESENT + " " + presentRois + "/" + totalRois;
    }

    /** Describe current slide-scoped attributed output without implying completeness. */
    static String slideOutputPresence(boolean present, boolean invalidProvenance) {
        if (invalidProvenance) {
            return StatusStrings.OUTPUT_STATE_INVALID;
        }
        return present ? StatusStrings.OUTPUT_PRESENT + " · whole slide" : "—";
    }

    /** Describe the latest in-process typed result; absence means no action in this process. */
    static String latest(Optional<RunResults.ImageRunResult> result) {
        Objects.requireNonNull(result, "result");
        if (result.isEmpty()) {
            return "";
        }
        RunResults.ImageRunResult value = result.orElseThrow();
        return switch (value.outcome()) {
            case SUCCEEDED -> StatusStrings.SAVED;
            case SUCCEEDED_NO_HIERARCHY_CHANGE -> StatusStrings.NO_HIERARCHY_CHANGE;
            case PARTIAL -> StatusStrings.PARTIAL_PREFIX + " " + diagnostic(value);
            case CANCELLED -> StatusStrings.CANCELLED;
            case CONFLICT -> StatusStrings.CONFLICT_PREFIX + " " + diagnostic(value);
            case FAILED -> StatusStrings.FAILED_PREFIX + " " + diagnostic(value);
        };
    }

    private static String diagnostic(RunResults.ImageRunResult result) {
        // The table can visually shorten text; tooltips and copying need the complete message.
        return failures(result)
                .findFirst()
                .map(ProjectRowStatus::diagnostic)
                .orElse("See application log");
    }

    private static String diagnostic(RunResults.FailureDetail failure) {
        if (failure.type() == RunResults.FailureType.LOCAL_CAPACITY) {
            return "Analysis exceeds selected result setting";
        }
        return failure.type() + ": " + failure.message();
    }

    static boolean hasLocalCapacityFailure(RunResults.ImageRunResult result) {
        return failures(Objects.requireNonNull(result, "result"))
                .anyMatch(failure -> failure.type() == RunResults.FailureType.LOCAL_CAPACITY);
    }

    static String localCapacityMessage(long imageCount) {
        if (imageCount < 1) {
            throw new IllegalArgumentException("imageCount must be positive");
        }
        String affected = imageCount == 1 ? "one image" : imageCount + " images";
        return "HistoForge could not complete analysis for "
                + affected
                + " because it would exceed the selected Results per image setting. Previously"
                + " completed results remain saved, and incomplete results were not applied."
                + " Select a larger setting if one is available, or analyze a smaller area, then"
                + " try again.";
    }

    private static Stream<RunResults.FailureDetail> failures(RunResults.ImageRunResult result) {
        return Stream.concat(
                Stream.concat(
                        result.imageFailures().stream(),
                        result.scopes().stream().flatMap(scope -> scope.failure().stream())),
                result.metrics().stream()
                        .flatMap(metrics -> metrics.regionFailures().values().stream()));
    }
}
