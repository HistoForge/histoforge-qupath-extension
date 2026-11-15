package qupath.ext.histoforge.training;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.random.RandomGenerator;
import qupath.ext.histoforge.contract.TrainingSplitCodec.NoValidationPlan;
import qupath.ext.histoforge.contract.TrainingSplitCodec.SplitUnitAssignment;
import qupath.ext.histoforge.contract.TrainingSplitCodec.TrainingSplitPlan;
import qupath.ext.histoforge.contract.TrainingSplitCodec.ValidationSplitPlan;

/** Exact editable Train-tab assignment tied to one canonical project-unit snapshot. */
public final class TrainingAssignment {

    public static final String TRAINING = "training";
    public static final String VALIDATION = "validation";

    private final TrainingProjectIndex projectIndex;
    private final int requestedValidationPercent;
    private final boolean customized;
    private final List<String> partitions;

    private TrainingAssignment(
            TrainingProjectIndex projectIndex,
            int requestedValidationPercent,
            boolean customized,
            List<String> partitions) {
        this.projectIndex = Objects.requireNonNull(projectIndex, "projectIndex");
        if (requestedValidationPercent < 0 || requestedValidationPercent > 99) {
            throw new IllegalArgumentException("validation percent must be from 0 through 99");
        }
        this.requestedValidationPercent = requestedValidationPercent;
        this.customized = customized;
        this.partitions = List.copyOf(partitions);
        if (this.partitions.size() != projectIndex.rows().size()
                || this.partitions.stream()
                        .anyMatch(value -> !TRAINING.equals(value) && !VALIDATION.equals(value))) {
            throw new IllegalArgumentException("assignment must partition every project unit");
        }
        if (requestedValidationPercent == 0
                && (customized || this.partitions.stream().anyMatch(VALIDATION::equals))) {
            throw new IllegalArgumentException("zero-percent assignment must use training only");
        }
    }

    public static TrainingAssignment generate(
            TrainingProjectIndex projectIndex, int validationPercent, RandomGenerator random) {
        TrainingProjectIndex index = Objects.requireNonNull(projectIndex, "projectIndex");
        TrainingSplitPlan plan = TrainingSplitGenerator.generate(index, validationPercent, random);
        List<String> partitions = new ArrayList<>();
        if (plan instanceof NoValidationPlan) {
            for (int ignored = 0; ignored < index.rows().size(); ignored++) {
                partitions.add(TRAINING);
            }
        } else {
            ValidationSplitPlan split = (ValidationSplitPlan) plan;
            split.units().forEach(unit -> partitions.add(unit.partition()));
        }
        return new TrainingAssignment(index, validationPercent, false, partitions);
    }

    public TrainingProjectIndex projectIndex() {
        return projectIndex;
    }

    public int requestedValidationPercent() {
        return requestedValidationPercent;
    }

    public boolean customized() {
        return customized;
    }

    public String partition(int unitIndex) {
        return partitions.get(unitIndex);
    }

    public List<Integer> unitIndices(String partition) {
        requirePartition(partition);
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < partitions.size(); index++) {
            if (partition.equals(partitions.get(index))) {
                result.add(index);
            }
        }
        return List.copyOf(result);
    }

    /** Move complete units. An empty side is displayable but cannot be submitted or exported. */
    public TrainingAssignment move(Collection<Integer> unitIndices, String destination) {
        if (requestedValidationPercent == 0) {
            throw new IllegalStateException("no-validation assignments cannot be edited");
        }
        requirePartition(destination);
        Collection<Integer> selected = Objects.requireNonNull(unitIndices, "unitIndices");
        if (selected.isEmpty()) {
            return this;
        }
        List<String> changed = new ArrayList<>(partitions);
        for (Integer unitIndex : selected) {
            if (unitIndex == null || unitIndex < 0 || unitIndex >= changed.size()) {
                throw new IllegalArgumentException("assignment unit index is outside the project");
            }
            changed.set(unitIndex, destination);
        }
        return new TrainingAssignment(projectIndex, requestedValidationPercent, true, changed);
    }

    public boolean isValid() {
        return requestedValidationPercent == 0
                || (partitions.contains(TRAINING) && partitions.contains(VALIDATION));
    }

    /** Preserve the assignment while updating display counts from a later equivalent capture. */
    public TrainingAssignment refresh(TrainingProjectIndex current) {
        if (!projectIndex.hasSameUnits(current)) {
            throw new IllegalStateException("training split assignment is outdated");
        }
        return new TrainingAssignment(current, requestedValidationPercent, customized, partitions);
    }

    public TrainingSplitPlan requirePlan() {
        if (!isValid()) {
            throw new IllegalStateException(
                    "training and validation must both contain at least one unit");
        }
        if (requestedValidationPercent == 0) {
            return new NoValidationPlan();
        }
        List<SplitUnitAssignment> units = new ArrayList<>(partitions.size());
        for (int index = 0; index < partitions.size(); index++) {
            units.add(new SplitUnitAssignment(index, partitions.get(index)));
        }
        return new ValidationSplitPlan(
                projectIndex.unit().wireValue(),
                customized ? "custom" : "generated",
                customized ? OptionalInt.empty() : OptionalInt.of(requestedValidationPercent),
                units);
    }

    private static void requirePartition(String partition) {
        if (!TRAINING.equals(partition) && !VALIDATION.equals(partition)) {
            throw new IllegalArgumentException("unsupported training partition");
        }
    }
}
