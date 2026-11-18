package qupath.ext.histoforge.training;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingDatasetLimits;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.data.GroupManager;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.training.TrainingDatasetWorkflow.Phase;
import qupath.ext.histoforge.training.TrainingDatasetWorkflow.Progress;
import qupath.ext.histoforge.training.TrainingProjectCapture.ImageAnnotations;
import qupath.ext.histoforge.training.TrainingProjectCapture.LabelCounts;
import qupath.ext.histoforge.training.TrainingProjectIndex.UnitRow;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Model-independent annotation counts for the Train form and its split chooser. */
public final class TrainingProjectPreview {
    private final List<Image> images;

    TrainingProjectPreview(List<Image> images) {
        this.images =
                images.stream()
                        .sorted(
                                Comparator.comparing(
                                        image -> TrainingProjectCapture.entryDigest(image.id())))
                        .toList();
    }

    /** Read one project snapshot without opening any source for pixel materialization. */
    public static Optional<TrainingProjectPreview> read(
            Project<BufferedImage> project,
            ImageLifecycle imageLifecycle,
            BooleanSupplier cancelled,
            Consumer<Progress> progress)
            throws IOException {
        Runnable checkRunning =
                () -> {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("training project read cancelled");
                    }
                };
        var entries = List.copyOf(project.getImageList());
        List<Image> images = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            checkRunning.run();
            var entry = entries.get(i);
            var annotations = readImage(entry, imageLifecycle, checkRunning);
            annotations.ifPresent(
                    value ->
                            images.add(
                                    new Image(
                                            entry.getID(),
                                            entry.getImageName(),
                                            TrainingProjectCapture.normalizedGroup(
                                                    GroupManager.getGroup(entry)),
                                            value)));
            progress.accept(
                    new Progress(Phase.CAPTURING, i + 1, entries.size(), entry.getImageName()));
        }
        checkRunning.run();
        return images.isEmpty()
                ? Optional.empty()
                : Optional.of(new TrainingProjectPreview(images));
    }

    private static Optional<ImageAnnotations> readImage(
            ProjectImageEntry<BufferedImage> entry,
            ImageLifecycle imageLifecycle,
            Runnable checkRunning)
            throws IOException {
        try {
            return imageLifecycle.readProjectAnnotations(
                    entry,
                    checkRunning,
                    image -> {
                        var hierarchy = image.getHierarchy();
                        if (!TrainingProjectCapture.hasRoiMarkers(hierarchy))
                            return Optional.empty();
                        var metadata = image.getServerMetadata();
                        return TrainingProjectCapture.countAnnotations(
                                hierarchy, metadata.getWidth(), metadata.getHeight(), checkRunning);
                    });
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (IOException failure) {
            throw new IOException(
                    TrainingProjectDatasetBuilder.imageReadFailure(entry, failure), failure);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    TrainingProjectDatasetBuilder.imageReadFailure(entry, failure), failure);
        }
    }

    public Set<String> annotationClasses() {
        Set<String> classes = new HashSet<>();
        for (var image : images) classes.addAll(image.annotations().annotationClasses());
        return Set.copyOf(classes);
    }

    public int imageCount() {
        return images.size();
    }

    public int roiCount() {
        return images.stream().mapToInt(image -> image.annotations().rois().size()).sum();
    }

    /** Apply the current mapping in memory; the resulting counts are still advisory. */
    public TrainingProjectIndex roiIndex(TrainingTask task, TrainingDatasetLimits limits) {
        TrainingProjectCapture.requireAtMost("training ROIs", roiCount(), limits.maxRois());
        Map<String, String> classMap = task.annotationClassMap();
        List<UnitRow> rows = new ArrayList<>();
        long totalUsed = 0;
        for (var image : images) {
            for (var roi : image.annotations().rois()) {
                int used = 0;
                int notUsed = roi.excludedClassified();
                Set<String> taught = new HashSet<>();
                for (var count : roi.byClass().entrySet()) {
                    String target = classMap.get(count.getKey());
                    if (target == null) {
                        notUsed = Math.addExact(notUsed, count.getValue());
                    } else {
                        used = Math.addExact(used, count.getValue());
                        taught.add(target);
                    }
                }
                TrainingProjectCapture.requireRoiExamples(roi.roiId(), used, task);
                if (used > limits.maxObjectsPerRoi()) {
                    TrainingProjectCapture.requireRoiObjectLimit(
                            image.id(), roi.roiId(), roi.bounds(), used, limits.maxObjectsPerRoi());
                }
                totalUsed += used;
                TrainingProjectCapture.requireAtMost(
                        "training objects", totalUsed, limits.maxTotalObjects());
                rows.add(
                        TrainingProjectIndex.roiRow(
                                rows.size(),
                                roi.roiId(),
                                image.id(),
                                image.name(),
                                image.group(),
                                new LabelCounts(used, roi.noClass(), notUsed),
                                taught));
            }
        }
        return TrainingProjectIndex.fromRoiRows(rows);
    }

    record Image(String id, String name, Optional<String> group, ImageAnnotations annotations) {}
}
