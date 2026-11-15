package qupath.ext.histoforge.training;

import java.util.Objects;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder.ProjectPlan;
import qupath.ext.histoforge.training.TrainingProjectIndex.SplitUnit;

/** User-actionable readiness for generating a split from one captured project. */
public record TrainingSplitReadiness(boolean ready, String guidance, boolean offerGroupAssignment) {

    public TrainingSplitReadiness {
        guidance = Objects.requireNonNull(guidance, "guidance");
        if (ready && (!guidance.isEmpty() || offerGroupAssignment)) {
            throw new IllegalArgumentException("ready split must not carry corrective guidance");
        }
        if (offerGroupAssignment && guidance.isEmpty()) {
            throw new IllegalArgumentException("group assignment requires guidance");
        }
    }

    /** Check only conditions the current Train form can correct before generation. */
    public static TrainingSplitReadiness evaluate(
            ProjectPlan project, SplitUnit splitUnit, int validationPercent) {
        ProjectPlan plan = Objects.requireNonNull(project, "project");
        SplitUnit unit = Objects.requireNonNull(splitUnit, "splitUnit");
        if (validationPercent < 0 || validationPercent > 99) {
            throw new IllegalArgumentException("validation percent must be from 0 through 99");
        }

        if (unit == SplitUnit.PROJECT_GROUP) {
            long ungrouped =
                    plan.images().stream()
                            .filter(image -> image.capture().projectGroup().isEmpty())
                            .count();
            if (ungrouped > 0) {
                return new TrainingSplitReadiness(
                        false,
                        ungrouped
                                + (ungrouped == 1
                                        ? " image has no Project group. "
                                        : " images have no Project group. ")
                                + "Assign groups in Project before generating this split.",
                        true);
            }
        }

        if (validationPercent == 0) {
            return available();
        }

        int unitCount =
                switch (unit) {
                    case ROI -> plan.roiCount();
                    case IMAGE -> plan.images().size();
                    case PROJECT_GROUP ->
                            (int)
                                    plan.images().stream()
                                            .map(
                                                    image ->
                                                            image.capture()
                                                                    .projectGroup()
                                                                    .orElseThrow())
                                            .distinct()
                                            .count();
                };
        if (unitCount >= 2) {
            return available();
        }
        String guidance =
                switch (unit) {
                    case ROI ->
                            "ROI validation requires at least two ROIs. Set Validation size to"
                                    + " 0%.";
                    case IMAGE ->
                            "Image validation requires at least two images. Choose ROI splitting"
                                    + " or set Validation size to 0%.";
                    case PROJECT_GROUP ->
                            "Project group validation requires at least two groups. Choose Image"
                                    + " or ROI splitting, or set Validation size to 0%.";
                };
        return new TrainingSplitReadiness(false, guidance, false);
    }

    private static TrainingSplitReadiness available() {
        return new TrainingSplitReadiness(true, "", false);
    }
}
