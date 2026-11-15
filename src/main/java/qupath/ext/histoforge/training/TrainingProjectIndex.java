package qupath.ext.histoforge.training;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    /** One row in the generated assignment table. */
    public record UnitRow(
            int unitIndex,
            String stableId,
            String unitLabel,
            String imageDisplay,
            Optional<String> projectGroup,
            List<String> imageIds,
            int roiCount,
            LabelCounts counts) {
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
        ProjectPlan project = Objects.requireNonNull(plan, "plan");
        return switch (Objects.requireNonNull(unit, "unit")) {
            case ROI -> new TrainingProjectIndex(unit, roiRows(project));
            case IMAGE -> new TrainingProjectIndex(unit, imageRows(project));
            case PROJECT_GROUP -> new TrainingProjectIndex(unit, groupRows(project));
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

    private static List<UnitRow> roiRows(ProjectPlan plan) {
        List<UnitRow> rows = new ArrayList<>();
        for (var image : plan.images()) {
            String imageId = image.capture().projectEntryId();
            String imageName = plan.imageNames().get(imageId);
            for (var roi : image.capture().rois()) {
                rows.add(
                        new UnitRow(
                                rows.size(),
                                "roi:" + roi.sourceRoiUuid(),
                                "ROI " + shortId(roi.sourceRoiUuid()),
                                imageName,
                                image.capture().projectGroup(),
                                List.of(imageId),
                                1,
                                roi.counts()));
            }
        }
        return rows;
    }

    private static List<UnitRow> imageRows(ProjectPlan plan) {
        List<UnitRow> rows = new ArrayList<>();
        for (var image : plan.images()) {
            LabelCounts counts =
                    sum(image.capture().rois().stream().map(roi -> roi.counts()).toList());
            String imageId = image.capture().projectEntryId();
            String imageName = plan.imageNames().get(imageId);
            rows.add(
                    new UnitRow(
                            rows.size(),
                            "image:" + image.capture().projectEntryIdDigest(),
                            imageName,
                            imageName,
                            image.capture().projectGroup(),
                            List.of(imageId),
                            image.capture().rois().size(),
                            counts));
        }
        return rows;
    }

    private static List<UnitRow> groupRows(ProjectPlan plan) {
        if (plan.images().stream().anyMatch(image -> image.capture().projectGroup().isEmpty())) {
            throw new IllegalArgumentException(
                    "project-group validation requires a Group for every image");
        }
        List<String> groups =
                plan.images().stream()
                        .map(image -> image.capture().projectGroup().orElseThrow())
                        .distinct()
                        .sorted(TrainingDatasetCodec.canonicalProjectGroupOrder())
                        .toList();
        List<UnitRow> rows = new ArrayList<>();
        for (String group : groups) {
            var images =
                    plan.images().stream()
                            .filter(
                                    image ->
                                            image.capture()
                                                    .projectGroup()
                                                    .orElseThrow()
                                                    .equals(group))
                            .toList();
            List<LabelCounts> counts =
                    images.stream()
                            .flatMap(image -> image.capture().rois().stream())
                            .map(roi -> roi.counts())
                            .toList();
            int roiCount = images.stream().mapToInt(image -> image.capture().rois().size()).sum();
            rows.add(
                    new UnitRow(
                            rows.size(),
                            "project_group:" + group,
                            group,
                            images.size() + (images.size() == 1 ? " image" : " images"),
                            Optional.of(group),
                            images.stream().map(image -> image.capture().projectEntryId()).toList(),
                            roiCount,
                            sum(counts)));
        }
        return rows;
    }

    private static LabelCounts sum(List<LabelCounts> values) {
        int used = 0;
        int noClass = 0;
        int notUsed = 0;
        for (LabelCounts value : values) {
            used = Math.addExact(used, value.used());
            noClass = Math.addExact(noClass, value.noClass());
            notUsed = Math.addExact(notUsed, value.notUsed());
        }
        return new LabelCounts(used, noClass, notUsed);
    }

    private static String shortId(String uuid) {
        return uuid.substring(0, 8);
    }
}
