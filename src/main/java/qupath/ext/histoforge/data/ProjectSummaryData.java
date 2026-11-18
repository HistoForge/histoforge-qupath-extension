package qupath.ext.histoforge.data;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javafx.scene.image.Image;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Immutable Project-tab image facts plus exact process-local metric cache projections. */
public final class ProjectSummaryData {

    /** Entry reference for IO, with its mutable display/assignment metadata copied by the owner. */
    public record ImageMetadata(
            ProjectImageEntry<BufferedImage> entry,
            String imageId,
            String imageName,
            String moduleRef,
            String group) {
        public static ImageMetadata capture(ProjectImageEntry<BufferedImage> entry) {
            return new ImageMetadata(
                    entry,
                    entry.getID(),
                    entry.getImageName(),
                    entry.getMetadata().get(HistoforgeKeys.MODULE),
                    ProjectSummaryData.group(entry));
        }
    }

    /** Detached current HistoForge ROI display facts; no live PathObject is retained. */
    public record RoiSummary(
            String roiId,
            String name,
            String imageEntryId,
            String imageName,
            String group,
            double boundsX,
            double boundsY,
            double boundsWidth,
            double boundsHeight) {
        public RoiSummary {
            roiId = Objects.requireNonNull(roiId, "roiId");
            imageEntryId = Objects.requireNonNull(imageEntryId, "imageEntryId");
            imageName = Objects.requireNonNull(imageName, "imageName");
        }

        ThumbnailRenderer.RoiOverlay overlay() {
            return new ThumbnailRenderer.RoiOverlay(boundsX, boundsY, boundsWidth, boundsHeight);
        }
    }

    /** Per-project-image UI facts derived from one hierarchy capture. */
    public record ImageSummary(
            ImageMetadata metadata,
            List<RoiSummary> rois,
            Map<String, Integer> outputRoiCountByModule,
            Set<String> slideOutputModules,
            boolean outputStateInvalid,
            Image thumbnail) {
        public ImageSummary {
            metadata = Objects.requireNonNull(metadata, "metadata");
            rois = List.copyOf(rois);
            outputRoiCountByModule = Map.copyOf(outputRoiCountByModule);
            slideOutputModules = Set.copyOf(slideOutputModules);
        }

        public Set<String> roiIds() {
            Set<String> result = new LinkedHashSet<>();
            rois.forEach(roi -> result.add(roi.roiId()));
            return Collections.unmodifiableSet(result);
        }

        public int roiCount() {
            return rois.size();
        }

        public int outputRoiCount(String moduleRef) {
            return outputRoiCountByModule.getOrDefault(moduleRef, 0);
        }

        public boolean hasSlideOutput(String moduleRef) {
            return slideOutputModules.contains(moduleRef);
        }

        public String group() {
            return metadata.group();
        }

        public ProjectImageEntry<BufferedImage> entry() {
            return metadata.entry();
        }
    }

    /** Complete exact-key cache hit used for Project-tab display; never persisted. */
    public record CachedImageMetrics(
            String imageId,
            String imageName,
            String group,
            InferenceIdentityCodec.ModuleContentIdentity module,
            String moduleName,
            MetricTypes.MetricSchema schema,
            MetricTypes.MetricImageResult result) {
        public CachedImageMetrics {
            imageId = Objects.requireNonNull(imageId, "imageId");
            imageName = Objects.requireNonNull(imageName, "imageName");
            group = Objects.requireNonNull(group, "group");
            module = Objects.requireNonNull(module, "module");
            moduleName = Objects.requireNonNull(moduleName, "moduleName");
            schema = Objects.requireNonNull(schema, "schema");
            result = Objects.requireNonNull(result, "result");
            if (!result.complete()) {
                throw new IllegalArgumentException(
                        "Project metrics require one complete exact cache hit");
            }
            if (!result.sourceRevision().imageId().equals(imageId)) {
                throw new IllegalArgumentException("Project metrics belong to another image");
            }
        }
    }

    /** Minimal owner-thread copy. Repeated provenance values describe the same output presence. */
    public record ViewerHierarchyCapture(
            List<DetachedRoi> rois, Set<String> provenanceValues, Set<String> annotationClasses) {
        public ViewerHierarchyCapture {
            rois = List.copyOf(rois);
            // A present null is malformed, not absent; retain it for worker-side validation.
            provenanceValues = Collections.unmodifiableSet(new LinkedHashSet<>(provenanceValues));
            annotationClasses = Set.copyOf(annotationClasses);
        }

        public HierarchySummary prepare() {
            Set<String> roiIds = new LinkedHashSet<>();
            rois.forEach(roi -> roiIds.add(roi.roiId()));
            Map<String, Set<String>> outputRoisByModule = new HashMap<>();
            Set<String> slideOutputModules = new LinkedHashSet<>();
            boolean invalid = false;
            for (String text : provenanceValues) {
                try {
                    var provenance = ProvenanceCodec.decodeMetadataValue(text);
                    var region = ExecutionScopeCodec.regionOf(provenance.output().scope());
                    String moduleRef = provenance.output().producer().moduleRef().toString();
                    if (region instanceof ExecutionScopeCodec.Roi roi
                            && roiIds.contains(roi.roiId())) {
                        outputRoisByModule
                                .computeIfAbsent(moduleRef, ignored -> new LinkedHashSet<>())
                                .add(roi.roiId());
                    } else if (region instanceof ExecutionScopeCodec.Slide) {
                        slideOutputModules.add(moduleRef);
                    }
                } catch (IllegalArgumentException malformed) {
                    invalid = true;
                }
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            outputRoisByModule.forEach((ref, ids) -> counts.put(ref, ids.size()));
            return new HierarchySummary(
                    rois.stream()
                            .sorted(java.util.Comparator.comparing(DetachedRoi::roiId))
                            .toList(),
                    counts,
                    slideOutputModules,
                    invalid,
                    annotationClasses);
        }
    }

    /** Worker-built display facts; contains no live object or raw provenance text. */
    public record HierarchySummary(
            List<DetachedRoi> rois,
            Map<String, Integer> outputRoiCountByModule,
            Set<String> slideOutputModules,
            boolean outputStateInvalid,
            Set<String> annotationClasses) {
        public HierarchySummary {
            rois = List.copyOf(rois);
            outputRoiCountByModule = Map.copyOf(outputRoiCountByModule);
            slideOutputModules = Set.copyOf(slideOutputModules);
            annotationClasses = Set.copyOf(annotationClasses);
        }
    }

    /** One owner-thread copy of an administrative ROI used later for UI and thumbnails. */
    public record DetachedRoi(
            String roiId,
            String name,
            double boundsX,
            double boundsY,
            double boundsWidth,
            double boundsHeight) {}

    private final List<ImageSummary> imageSummaries;
    private final List<CachedImageMetrics> cachedMetrics;
    private final Map<String, String> metricFailures;
    private final Set<String> annotationClasses;
    private final ProjectInferenceScope scope;

    private ProjectSummaryData(
            List<ImageSummary> imageSummaries,
            List<CachedImageMetrics> cachedMetrics,
            Map<String, String> metricFailures,
            Set<String> annotationClasses,
            ProjectInferenceScope scope) {
        this.imageSummaries = List.copyOf(imageSummaries);
        this.cachedMetrics = List.copyOf(cachedMetrics);
        this.metricFailures = Map.copyOf(metricFailures);
        this.annotationClasses = Set.copyOf(annotationClasses);
        this.scope = Objects.requireNonNull(scope);
    }

    /** Load hierarchy display facts and thumbnails without reading any persisted metric value. */
    public static ProjectSummaryData load(
            Project<BufferedImage> project,
            ViewerHierarchyCapture viewerCapture,
            ProjectImageEntry<BufferedImage> viewerEntry,
            Consumer<String> progress)
            throws IOException {
        return load(
                project, viewerCapture, viewerEntry, progress, ProjectInferenceScope.defaults());
    }

    public static ProjectSummaryData load(
            Project<BufferedImage> project,
            ViewerHierarchyCapture viewerCapture,
            ProjectImageEntry<BufferedImage> viewerEntry,
            Consumer<String> progress,
            ProjectInferenceScope scope)
            throws IOException {
        Objects.requireNonNull(project, "project");
        return load(
                project.getImageList().stream().map(ImageMetadata::capture).toList(),
                viewerCapture,
                viewerEntry,
                progress,
                scope);
    }

    public static ProjectSummaryData load(
            List<ImageMetadata> entries,
            ViewerHierarchyCapture viewerCapture,
            ProjectImageEntry<BufferedImage> viewerEntry,
            Consumer<String> progress,
            ProjectInferenceScope scope)
            throws IOException {
        List<ImageSummary> images = new ArrayList<>();
        Set<String> classes = new java.util.TreeSet<>();
        for (ImageMetadata metadata : entries) {
            ProjectImageEntry<BufferedImage> entry = metadata.entry();
            if (progress != null) {
                progress.accept(metadata.imageName());
            }
            boolean viewer = viewerEntry == entry;
            HierarchySummary capture;
            if (viewer) {
                capture =
                        Objects.requireNonNull(
                                        viewerCapture,
                                        "viewer hierarchy capture is required for the open image")
                                .prepare();
            } else {
                capture = captureHierarchy(entry.readHierarchy(), scope).prepare();
            }
            classes.addAll(capture.annotationClasses());
            String group = metadata.group();
            List<RoiSummary> rois = new ArrayList<>(capture.rois().size());
            for (DetachedRoi roi : capture.rois()) {
                rois.add(
                        new RoiSummary(
                                roi.roiId(),
                                roi.name(),
                                metadata.imageId(),
                                metadata.imageName(),
                                group,
                                roi.boundsX(),
                                roi.boundsY(),
                                roi.boundsWidth(),
                                roi.boundsHeight()));
            }
            Image thumbnail =
                    ThumbnailRenderer.generateWsiThumbnail(
                            entry, rois.stream().map(RoiSummary::overlay).toList());
            images.add(
                    new ImageSummary(
                            metadata,
                            rois,
                            capture.outputRoiCountByModule(),
                            capture.slideOutputModules(),
                            capture.outputStateInvalid(),
                            thumbnail));
        }
        return new ProjectSummaryData(images, List.of(), Map.of(), classes, scope);
    }

    /** Attach exact process-local cache hits and visible per-image capture failures. */
    public ProjectSummaryData withMetrics(
            List<CachedImageMetrics> metrics, Map<String, String> failures) {
        return new ProjectSummaryData(imageSummaries, metrics, failures, annotationClasses, scope);
    }

    /** Drop displayed metric values immediately after a live hierarchy edit. */
    public ProjectSummaryData withoutMetrics() {
        return new ProjectSummaryData(
                imageSummaries, List.of(), Map.of(), annotationClasses, scope);
    }

    /** Capture viewer hierarchy facts on its owning FX thread. */
    public static ViewerHierarchyCapture captureViewerHierarchy(PathObjectHierarchy hierarchy) {
        return captureViewerHierarchy(hierarchy, ProjectInferenceScope.defaults());
    }

    public static ViewerHierarchyCapture captureViewerHierarchy(
            PathObjectHierarchy hierarchy, ProjectInferenceScope scope) {
        return captureHierarchy(Objects.requireNonNull(hierarchy, "hierarchy"), scope);
    }

    private static ViewerHierarchyCapture captureHierarchy(
            PathObjectHierarchy hierarchy, ProjectInferenceScope scope) {
        List<PathObject> objects = hierarchy.getFlattenedObjectList(null);
        RoiRegistry.fromHierarchyObjects(objects); // validate UUIDs, including the root
        List<DetachedRoi> rois = new ArrayList<>();
        Set<String> provenanceValues = new LinkedHashSet<>();
        Set<String> classes = new java.util.TreeSet<>();
        PathObject root = hierarchy.getRootObject();
        for (PathObject object : objects) {
            if (object == root) {
                continue;
            }
            if (object.hasMetadata()) {
                var metadata = object.getMetadata();
                if (metadata.containsKey(HistoforgePersistenceKeys.OBJECT_PROVENANCE)) {
                    provenanceValues.add(metadata.get(HistoforgePersistenceKeys.OBJECT_PROVENANCE));
                }
            }
            if (RoiRegistry.isOrdinaryAreaAnnotation(object) && object.getPathClass() != null) {
                classes.add(object.getPathClass().toString());
            }
            if (!scope.selects(object)) {
                continue;
            }
            var roi = object.getROI();
            String id = object.getID().toString();
            rois.add(
                    new DetachedRoi(
                            id,
                            object.getName(),
                            roi.getBoundsX(),
                            roi.getBoundsY(),
                            roi.getBoundsWidth(),
                            roi.getBoundsHeight()));
        }
        return new ViewerHierarchyCapture(rois, provenanceValues, classes);
    }

    public Set<String> annotationClasses() {
        return annotationClasses;
    }

    public ProjectInferenceScope scope() {
        return scope;
    }

    public List<ImageSummary> getImageSummaries() {
        return imageSummaries;
    }

    public List<RoiSummary> getProjectRois() {
        return imageSummaries.stream().flatMap(image -> image.rois().stream()).toList();
    }

    public int totalRoiCount() {
        return imageSummaries.stream().mapToInt(ImageSummary::roiCount).sum();
    }

    public List<CachedImageMetrics> cachedMetrics() {
        return cachedMetrics;
    }

    public Map<String, String> metricFailures() {
        return metricFailures;
    }

    public Map<String, String> getImageIdToName() {
        Map<String, String> result = new LinkedHashMap<>();
        imageSummaries.forEach(
                image -> result.put(image.metadata().imageId(), image.metadata().imageName()));
        return Collections.unmodifiableMap(result);
    }

    public Map<String, String> getImageIdToGroup() {
        Map<String, String> result = new LinkedHashMap<>();
        imageSummaries.forEach(image -> result.put(image.metadata().imageId(), image.group()));
        return Collections.unmodifiableMap(result);
    }

    private static String group(ProjectImageEntry<BufferedImage> entry) {
        String value = entry.getMetadata().get(HistoforgeKeys.GROUP);
        return value == null || value.isBlank() ? GroupManager.UNGROUPED : value;
    }
}
