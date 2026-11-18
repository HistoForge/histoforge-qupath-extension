package qupath.ext.histoforge.training;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import qupath.ext.histoforge.contract.TrainingDatasetCodec;
import qupath.ext.histoforge.training.TrainingProjectCapture.LabelCounts;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder.ProjectPlan;

/** Canonical local split-unit projection of one detached training-project capture. */
public final class TrainingProjectIndex {

    public enum SplitUnit {
        ROI("roi", "ROI"),
        IMAGE("image", "Image"),
        PROJECT_GROUP("project_group", "Project group");

        private final String wireValue;
        private final String displayName;

        SplitUnit(String wireValue, String displayName) {
            this.wireValue = wireValue;
            this.displayName = displayName;
        }

        public String wireValue() {
            return wireValue;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * One row in the generated assignment table.
     *
     * @param taughtClasses model classes that at least one object in this row's ROIs teaches
     */
    public record UnitRow(
            int unitIndex,
            String stableId,
            String unitLabel,
            String imageDisplay,
            Optional<String> projectGroup,
            List<String> imageIds,
            int roiCount,
            LabelCounts counts,
            Set<String> taughtClasses) {
        public UnitRow {
            if (unitIndex < 0) {
                throw new IllegalArgumentException("training unit index must be nonnegative");
            }
            stableId = Objects.requireNonNull(stableId, "stableId");
            unitLabel = Objects.requireNonNull(unitLabel, "unitLabel");
            imageDisplay = Objects.requireNonNull(imageDisplay, "imageDisplay");
            projectGroup = Objects.requireNonNull(projectGroup, "projectGroup");
            imageIds = List.copyOf(Objects.requireNonNull(imageIds, "imageIds"));
            if (imageIds.isEmpty()
                    || imageIds.size() != imageIds.stream().distinct().count()
                    || roiCount < 1) {
                throw new IllegalArgumentException("training unit must contain images and ROIs");
            }
            counts = Objects.requireNonNull(counts, "counts");
            taughtClasses = Set.copyOf(taughtClasses);
        }

        public int imageCount() {
            return imageIds.size();
        }
    }

    private final SplitUnit unit;
    private final List<UnitRow> rows;

    private TrainingProjectIndex(SplitUnit unit, List<UnitRow> rows) {
        this.unit = Objects.requireNonNull(unit, "unit");
        this.rows = List.copyOf(rows);
        if (this.rows.isEmpty()) {
            throw new IllegalArgumentException("training project contains no split units");
        }
        for (int index = 0; index < this.rows.size(); index++) {
            if (this.rows.get(index).unitIndex() != index) {
                throw new IllegalArgumentException("training split units are not in index order");
            }
        }
    }

    public static TrainingProjectIndex from(ProjectPlan plan, SplitUnit unit) {
        List<UnitRow> rois = new ArrayList<>();
        for (var image : plan.images()) {
            var captured = image.capture();
            var classMap = captured.task().annotationClassMap();
            for (var roi : captured.rois()) {
                Set<String> taught = new TreeSet<>();
                for (var object : roi.targetObjects()) {
                    String modelClass = classMap.get(object.pathClass());
                    if (modelClass != null) taught.add(modelClass);
                }
                rois.add(
                        roiRow(
                                rois.size(),
                                roi.sourceRoiUuid(),
                                captured.projectEntryId(),
                                plan.imageNames().get(captured.projectEntryId()),
                                captured.projectGroup(),
                                roi.counts(),
                                taught));
            }
        }
        return fromRoiRows(rois).forUnit(unit);
    }

    static UnitRow roiRow(
            int index,
            String roiId,
            String imageId,
            String imageName,
            Optional<String> group,
            LabelCounts counts,
            Set<String> taughtClasses) {
        return new UnitRow(
                index,
                "roi:" + roiId,
                "ROI " + roiId.substring(0, 8),
                imageName,
                group,
                List.of(imageId),
                1,
                counts,
                taughtClasses);
    }

    static TrainingProjectIndex fromRoiRows(List<UnitRow> rows) {
        return new TrainingProjectIndex(SplitUnit.ROI, rows);
    }

    /** Group the same ROI rows without accessing any project images. */
    public TrainingProjectIndex forUnit(SplitUnit unit) {
        if (this.unit != SplitUnit.ROI) {
            throw new IllegalStateException("split grouping requires ROI rows");
        }
        return switch (Objects.requireNonNull(unit, "unit")) {
            case ROI -> this;
            case IMAGE -> new TrainingProjectIndex(unit, imageRows(rows));
            case PROJECT_GROUP -> new TrainingProjectIndex(unit, groupRows(imageRows(rows)));
        };
    }

    public SplitUnit unit() {
        return unit;
    }

    public List<UnitRow> rows() {
        return rows;
    }

    /** Whether a later capture has exactly the same assignable units in canonical index order. */
    public boolean hasSameUnits(TrainingProjectIndex other) {
        return other != null
                && unit == other.unit
                && rows.stream()
                        .map(UnitRow::stableId)
                        .toList()
                        .equals(other.rows.stream().map(UnitRow::stableId).toList());
    }

    private static List<UnitRow> imageRows(List<UnitRow> rois) {
        var byImage = new LinkedHashMap<String, List<UnitRow>>();
        for (var roi : rois) {
            byImage.computeIfAbsent(roi.imageIds().getFirst(), ignored -> new ArrayList<>())
                    .add(roi);
        }
        List<UnitRow> rows = new ArrayList<>();
        for (var image : byImage.entrySet()) {
            var members = image.getValue();
            var first = members.getFirst();
            rows.add(
                    aggregate(
                            rows.size(),
                            "image:" + TrainingProjectCapture.entryDigest(image.getKey()),
                            first.imageDisplay(),
                            first.imageDisplay(),
                            first.projectGroup(),
                            members,
                            List.of(image.getKey())));
        }
        return rows;
    }

    private static List<UnitRow> groupRows(List<UnitRow> images) {
        var byGroup =
                new TreeMap<String, List<UnitRow>>(
                        TrainingDatasetCodec.canonicalProjectGroupOrder());
        for (var image : images) {
            String group =
                    image.projectGroup()
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "project-group validation requires a Group for every image"));
            byGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(image);
        }
        List<UnitRow> rows = new ArrayList<>();
        for (var entry : byGroup.entrySet()) {
            var members = entry.getValue();
            String group = entry.getKey();
            rows.add(
                    aggregate(
                            rows.size(),
                            "project_group:" + group,
                            group,
                            members.size() + (members.size() == 1 ? " image" : " images"),
                            Optional.of(group),
                            members,
                            members.stream().map(image -> image.imageIds().getFirst()).toList()));
        }
        return rows;
    }

    private static UnitRow aggregate(
            int index,
            String id,
            String label,
            String imageDisplay,
            Optional<String> group,
            List<UnitRow> members,
            List<String> imageIds) {
        Set<String> taught = new TreeSet<>();
        int roiCount = 0;
        int used = 0;
        int noClass = 0;
        int notUsed = 0;
        for (var member : members) {
            roiCount = Math.addExact(roiCount, member.roiCount());
            used = Math.addExact(used, member.counts().used());
            noClass = Math.addExact(noClass, member.counts().noClass());
            notUsed = Math.addExact(notUsed, member.counts().notUsed());
            taught.addAll(member.taughtClasses());
        }
        return new UnitRow(
                index,
                id,
                label,
                imageDisplay,
                group,
                imageIds,
                roiCount,
                new LabelCounts(used, noClass, notUsed),
                taught);
    }
}
