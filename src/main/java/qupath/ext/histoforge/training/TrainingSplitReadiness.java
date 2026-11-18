package qupath.ext.histoforge.training;

import java.util.Objects;
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
            TrainingProjectIndex rois, SplitUnit splitUnit, int validationPercent) {
        if (rois.unit() != SplitUnit.ROI) {
            throw new IllegalArgumentException("split readiness requires ROI rows");
        }
        SplitUnit unit = Objects.requireNonNull(splitUnit, "splitUnit");
        if (validationPercent < 0 || validationPercent > 99) {
            throw new IllegalArgumentException("validation percent must be from 0 through 99");
        }

        if (!hasUsedObjects(rois)) {
            return new TrainingSplitReadiness(
                    false,
                    "No object inside a training ROI is set to a model class. Check Train as"
                            + " and the classes of your objects.",
                    false);
        }

        if (unit == SplitUnit.PROJECT_GROUP) {
            long ungrouped =
                    rois.rows().stream()
                            .filter(roi -> roi.projectGroup().isEmpty())
                            .map(roi -> roi.imageIds().getFirst())
                            .distinct()
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

        long unitCount =
                switch (unit) {
                    case ROI -> rois.rows().size();
                    case IMAGE ->
                            rois.rows().stream()
                                    .map(roi -> roi.imageIds().getFirst())
                                    .distinct()
                                    .count();
                    case PROJECT_GROUP ->
                            rois.rows().stream()
                                    .map(roi -> roi.projectGroup().orElseThrow())
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

    /**
     * Whether any object inside a training ROI teaches a model class. Without one, every pixel
     * would be taught as background and the trained model would find nothing.
     */
    static boolean hasUsedObjects(TrainingProjectIndex rois) {
        return rois.rows().stream().anyMatch(roi -> roi.counts().used() > 0);
    }

    private static TrainingSplitReadiness available() {
        return new TrainingSplitReadiness(true, "", false);
    }
}
