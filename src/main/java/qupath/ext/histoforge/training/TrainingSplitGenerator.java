package qupath.ext.histoforge.training;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetManifest;
import qupath.ext.histoforge.contract.TrainingSplitCodec.NoValidationPlan;
import qupath.ext.histoforge.contract.TrainingSplitCodec.SplitUnitAssignment;
import qupath.ext.histoforge.contract.TrainingSplitCodec.TrainingSplitPlan;
import qupath.ext.histoforge.contract.TrainingSplitCodec.ValidationSplitPlan;

/** Generates the exact Train-tab assignment from one captured dataset. */
public final class TrainingSplitGenerator {

    private TrainingSplitGenerator() {}

    public static TrainingSplitPlan generate(
            TrainingProjectIndex project, int validationPercent, RandomGenerator random) {
        TrainingProjectIndex index = Objects.requireNonNull(project, "project");
        return generate(index.unit().wireValue(), index.rows().size(), validationPercent, random);
    }

    public static TrainingSplitPlan generate(
            TrainingDatasetManifest manifest,
            String unit,
            int validationPercent,
            RandomGenerator random) {
        TrainingDatasetManifest dataset = Objects.requireNonNull(manifest, "manifest");
        if (validationPercent < 0 || validationPercent > 99) {
            throw new IllegalArgumentException("validation percent must be from 0 through 99");
        }
        if (validationPercent == 0) {
            return new NoValidationPlan();
        }

        int count = unitCount(unit, dataset);
        return generate(unit, count, validationPercent, random);
    }

    private static TrainingSplitPlan generate(
            String unit, int count, int validationPercent, RandomGenerator random) {
        if (validationPercent < 0 || validationPercent > 99) {
            throw new IllegalArgumentException("validation percent must be from 0 through 99");
        }
        if (validationPercent == 0) {
            return new NoValidationPlan();
        }
        if (count < 2) {
            throw new IllegalArgumentException(
                    splitUnitLabel(unit) + " validation requires at least two units");
        }
        int validationCount =
                Math.min(
                        count - 1,
                        Math.max(1, (int) Math.floor(count * validationPercent / 100.0 + 0.5)));

        List<Integer> shuffled = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            shuffled.add(index);
        }
        RandomGenerator generator = Objects.requireNonNull(random, "random");
        for (int index = count - 1; index > 0; index--) {
            int selected = generator.nextInt(index + 1);
            Integer value = shuffled.get(index);
            shuffled.set(index, shuffled.get(selected));
            shuffled.set(selected, value);
        }
        boolean[] validation = new boolean[count];
        for (int index = 0; index < validationCount; index++) {
            validation[shuffled.get(index)] = true;
        }

        List<SplitUnitAssignment> assignments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            assignments.add(
                    new SplitUnitAssignment(index, validation[index] ? "validation" : "training"));
        }
        return new ValidationSplitPlan(
                unit, "generated", java.util.OptionalInt.of(validationPercent), assignments);
    }

    private static int unitCount(String unit, TrainingDatasetManifest manifest) {
        return switch (Objects.requireNonNull(unit, "unit")) {
            case "roi" -> manifest.rois().size();
            case "image" -> manifest.images().size();
            case "project_group" -> {
                if (manifest.images().stream()
                        .anyMatch(image -> image.projectGroupIndex().isEmpty())) {
                    throw new IllegalArgumentException(
                            "project-group validation requires a Group for every image");
                }
                yield manifest.projectGroups().size();
            }
            default -> throw new IllegalArgumentException("unsupported split unit: " + unit);
        };
    }

    private static String splitUnitLabel(String unit) {
        return switch (unit) {
            case "roi" -> "ROI";
            case "image" -> "Image";
            case "project_group" -> "Project-group";
            default -> throw new IllegalArgumentException("unsupported split unit: " + unit);
        };
    }
}
