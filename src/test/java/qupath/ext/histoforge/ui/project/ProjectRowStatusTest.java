package qupath.ext.histoforge.ui.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.data.MetricTypes;
import qupath.ext.histoforge.pipeline.RunResults;

class ProjectRowStatusTest {

    @Test
    void outputPresence_reportsCurrentFactsOnly() {
        assertEquals("—", ProjectRowStatus.outputPresence(0, 5, false));
        assertEquals("—", ProjectRowStatus.outputPresence(0, 0, false));
        assertEquals("Output present 3/5", ProjectRowStatus.outputPresence(3, 5, false));
        assertEquals(
                StatusStrings.OUTPUT_STATE_INVALID, ProjectRowStatus.outputPresence(3, 5, true));
        assertThrows(
                IllegalArgumentException.class, () -> ProjectRowStatus.outputPresence(6, 5, false));
        assertEquals("—", ProjectRowStatus.slideOutputPresence(false, false));
        assertEquals(
                "Output present · whole slide", ProjectRowStatus.slideOutputPresence(true, false));
        assertEquals(
                StatusStrings.OUTPUT_STATE_INVALID,
                ProjectRowStatus.slideOutputPresence(true, true));
    }

    @Test
    void latest_projectsEveryTypedImageOutcome() {
        assertEquals("", ProjectRowStatus.latest(Optional.empty()));
        assertEquals(
                StatusStrings.SAVED,
                ProjectRowStatus.latest(
                        Optional.of(result(RunResults.ImageOutcomeType.SUCCEEDED))));
        assertEquals(
                StatusStrings.NO_HIERARCHY_CHANGE,
                ProjectRowStatus.latest(
                        Optional.of(
                                result(
                                        RunResults.ImageOutcomeType
                                                .SUCCEEDED_NO_HIERARCHY_CHANGE))));
        assertEquals(
                StatusStrings.CANCELLED,
                ProjectRowStatus.latest(
                        Optional.of(result(RunResults.ImageOutcomeType.CANCELLED))));
        assertTrue(
                ProjectRowStatus.latest(Optional.of(result(RunResults.ImageOutcomeType.PARTIAL)))
                        .startsWith(StatusStrings.PARTIAL_PREFIX));
        assertTrue(
                ProjectRowStatus.latest(Optional.of(result(RunResults.ImageOutcomeType.CONFLICT)))
                        .startsWith(StatusStrings.CONFLICT_PREFIX));
        assertTrue(
                ProjectRowStatus.latest(Optional.of(result(RunResults.ImageOutcomeType.FAILED)))
                        .startsWith(StatusStrings.FAILED_PREFIX));
    }

    @ParameterizedTest
    @EnumSource(
            value = RunResults.ImageOutcomeType.class,
            names = {"FAILED", "PARTIAL", "CONFLICT"})
    void latest_preservesCompleteDiagnostics(RunResults.ImageOutcomeType outcome) {
        String message =
                "derived source artifact exceeds the byte ceiling and no eligible original is available\n"
                        + "Further details: "
                        + "x".repeat(200);
        String text =
                ProjectRowStatus.latest(
                        Optional.of(
                                failed(outcome, RunResults.FailureType.INVALID_REQUEST, message)));
        String prefix =
                switch (outcome) {
                    case FAILED -> "Failed:";
                    case PARTIAL -> "Partial:";
                    case CONFLICT -> "Conflict:";
                    default -> throw new AssertionError(outcome);
                };
        assertEquals(prefix + " INVALID_REQUEST: " + message, text);
    }

    @Test
    void latest_exposesCalibrationFailure() {
        assertEquals(
                "Failed: CALIBRATION: pixel calibration is not square",
                ProjectRowStatus.latest(
                        Optional.of(
                                failed(
                                        RunResults.ImageOutcomeType.FAILED,
                                        RunResults.FailureType.CALIBRATION,
                                        "pixel calibration is not square"))));
    }

    @Test
    void localCapacity_usesOnePathologistFacingStatusAndFlexibleGuidance() {
        RunResults.ImageRunResult result =
                failed(
                        RunResults.ImageOutcomeType.FAILED,
                        RunResults.FailureType.LOCAL_CAPACITY,
                        "max_hierarchy_objects exceeded: captured 6, limit 5");

        assertEquals(
                "Failed: Analysis exceeds selected result setting",
                ProjectRowStatus.latest(Optional.of(result)));
        assertTrue(ProjectRowStatus.hasLocalCapacityFailure(result));
        assertFalse(
                ProjectRowStatus.hasLocalCapacityFailure(
                        failed(
                                RunResults.ImageOutcomeType.FAILED,
                                RunResults.FailureType.BACKEND,
                                "backend failed")));

        String oneImage = ProjectRowStatus.localCapacityMessage(1);
        assertTrue(oneImage.contains("for one image"));
        assertTrue(oneImage.contains("Previously completed results remain saved"));
        assertTrue(oneImage.contains("if one is available"));
        assertFalse(oneImage.contains("Maximum"));

        String severalImages = ProjectRowStatus.localCapacityMessage(3);
        assertTrue(severalImages.contains("for 3 images"));
        assertThrows(
                IllegalArgumentException.class, () -> ProjectRowStatus.localCapacityMessage(0));
    }

    @Test
    void localCapacity_insideMetricResultUsesTheSameStatusAndGuidance() {
        RunResults.FailureDetail capacity =
                new RunResults.FailureDetail(
                        RunResults.FailureType.LOCAL_CAPACITY,
                        "max_metric_values_per_image exceeded");
        MetricTypes.MetricImageResult metrics =
                new MetricTypes.MetricImageResult(
                        "1".repeat(64),
                        new RasterInputCodec.ImageRevisionToken(
                                "image", "2".repeat(64), "3".repeat(64)),
                        Map.of(),
                        Map.of(
                                new ExecutionScopeCodec.Roi("11111111-1111-4111-8111-111111111111"),
                                capacity));
        RunResults.ImageRunResult result =
                new RunResults.ImageRunResult(
                        "image",
                        RunResults.ImageOutcomeType.PARTIAL,
                        false,
                        List.of(),
                        List.of(),
                        List.of(),
                        Optional.of(metrics));

        assertEquals(
                "Partial: Analysis exceeds selected result setting",
                ProjectRowStatus.latest(Optional.of(result)));
        assertTrue(ProjectRowStatus.hasLocalCapacityFailure(result));
    }

    private static RunResults.ImageRunResult result(RunResults.ImageOutcomeType outcome) {
        if (outcome == RunResults.ImageOutcomeType.SUCCEEDED) {
            return new RunResults.ImageRunResult(
                    "image", outcome, true, List.of(), List.of(), List.of(), Optional.empty());
        }
        if (outcome == RunResults.ImageOutcomeType.SUCCEEDED_NO_HIERARCHY_CHANGE) {
            return new RunResults.ImageRunResult(
                    "image", outcome, false, List.of(), List.of(), List.of(), Optional.empty());
        }
        RunResults.FailureType type =
                outcome == RunResults.ImageOutcomeType.CANCELLED
                        ? RunResults.FailureType.CANCELLED
                        : RunResults.FailureType.BACKEND;
        return failed(outcome, type, "diagnostic");
    }

    private static RunResults.ImageRunResult failed(
            RunResults.ImageOutcomeType outcome,
            RunResults.FailureType failureType,
            String message) {
        return new RunResults.ImageRunResult(
                "image",
                outcome,
                false,
                List.of(),
                List.of(),
                List.of(new RunResults.FailureDetail(failureType, message)),
                Optional.empty());
    }
}
