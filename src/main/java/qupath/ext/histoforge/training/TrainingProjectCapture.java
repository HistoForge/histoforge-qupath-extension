package qupath.ext.histoforge.training;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.TrainingGeometryCodec.TrainingObject;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingDatasetLimits;
import qupath.ext.histoforge.contract.TrainingTaskCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.interfaces.ROI;

/** Pure owner of one detached, immutable training-label capture from a QuPath hierarchy. */
public final class TrainingProjectCapture {

    private TrainingProjectCapture() {}

    /**
     * Check only ROI markers, without copying geometry, hashing state, or retaining the population.
     * Invalid marked objects also return true so the normal capture reports their validation error.
     * The caller must own the hierarchy thread; this is not an eligibility or integrity verdict.
     */
    public static boolean hasRoiMarkers(PathObjectHierarchy hierarchy) {
        var pending = new ArrayDeque<Iterator<PathObject>>();
        pending.push(List.<PathObject>of(hierarchy.getRootObject()).iterator());
        while (!pending.isEmpty()) {
            var children = pending.peek();
            if (!children.hasNext()) {
                pending.pop();
                continue;
            }
            PathObject object = children.next();
            if (RoiRegistry.isAdministrativeRoi(object)) return true;
            if (object.hasChildObjects()) pending.push(object.getChildObjects().iterator());
        }
        return false;
    }

    /** Per-ROI advisory counts displayed before dataset preparation. */
    public record LabelCounts(int used, int noClass, int notUsed) {
        public LabelCounts {
            if (used < 0 || noClass < 0 || notUsed < 0) {
                throw new IllegalArgumentException("training label counts must be nonnegative");
            }
        }
    }

    /** One persistent project ROI and the complete target objects intersecting it. */
    public record CapturedRoi(
            String sourceRoiUuid,
            Geometry geometry,
            List<TrainingObject> targetObjects,
            LabelCounts counts) {
        public CapturedRoi {
            sourceRoiUuid = CanonicalCodec.requireCanonicalUuid(sourceRoiUuid, "source_roi_uuid");
            geometry = canonicalArea(geometry, "training ROI geometry");
            targetObjects = List.copyOf(targetObjects);
            counts = Objects.requireNonNull(counts, "counts");
            if (counts.used() != targetObjects.size()) {
                throw new IllegalArgumentException("used count must equal captured target objects");
            }
        }

        @Override
        public Geometry geometry() {
            return geometry.copy();
        }
    }

    /** Detached labels and project metadata for one image. */
    public record CapturedImage(
            String projectEntryId,
            String projectEntryIdDigest,
            Optional<String> projectGroup,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            TrainingTask task,
            List<CapturedRoi> rois,
            Set<String> annotationClasses) {
        public CapturedImage {
            projectEntryId = CanonicalCodec.requireNonblank(projectEntryId, "project_entry_id");
            projectEntryIdDigest =
                    CanonicalCodec.requireSha256(projectEntryIdDigest, "project_entry_id_digest");
            if (!projectEntryIdDigest.equals(
                    CanonicalCodec.sha256(projectEntryId.getBytes(StandardCharsets.UTF_8)))) {
                throw new IllegalArgumentException(
                        "project_entry_id_digest does not match project_entry_id");
            }
            projectGroup = Objects.requireNonNull(projectGroup, "projectGroup");
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            if (!projectEntryId.equals(sourceRevision.imageDataRevision().imageId())) {
                throw new IllegalArgumentException(
                        "project entry differs from the captured source revision");
            }
            task = TrainingTaskCodec.parseTask(TrainingTaskCodec.toJson(task));
            rois = List.copyOf(rois);
            annotationClasses = Set.copyOf(annotationClasses);
            if (rois.isEmpty()) {
                throw new IllegalArgumentException("captured training image must contain ROIs");
            }
        }
    }

    /** Capture all current valid HistoForge ROIs and task labels from one image hierarchy. */
    public static Optional<CapturedImage> captureImage(
            String projectEntryId,
            String projectGroup,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            PathObjectHierarchy hierarchy,
            TrainingTask task) {
        String entryId = CanonicalCodec.requireNonblank(projectEntryId, "project_entry_id");
        RasterInputCodec.ActionSourceRevision revision =
                Objects.requireNonNull(sourceRevision, "sourceRevision");
        if (!entryId.equals(revision.imageDataRevision().imageId())) {
            throw new IllegalArgumentException(
                    "project entry differs from the captured source revision");
        }
        int imageWidth = revision.pixelSource().width();
        int imageHeight = revision.pixelSource().height();
        Objects.requireNonNull(hierarchy, "hierarchy");
        TrainingTask selectedTask =
                TrainingTaskCodec.parseTask(
                        TrainingTaskCodec.toJson(Objects.requireNonNull(task, "task")));
        var targets =
                captureTargets(hierarchy.getFlattenedObjectList(null), imageWidth, imageHeight);
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        List<Geometry> trainingRois =
                targets.stream().map(ExecutionScopeCodec.TargetRegion::geometrySlide).toList();

        Set<String> mappedClasses = selectedTask.annotationClassMap().keySet();
        Set<String> annotationClasses = new HashSet<>();
        STRtree targetIndex = new STRtree();
        STRtree advisoryIndex = new STRtree();

        for (PathObject object : hierarchy.getAllObjects(false)) {
            if (RoiRegistry.isAdministrativeRoi(object) || object.isTile()) {
                continue;
            }
            String objectType = objectType(object);
            boolean excludedCell = object instanceof PathCellObject;
            if (objectType == null && !excludedCell) {
                continue;
            }
            ROI main = object.getROI();
            if (main == null || !isSchemaOneArea(main)) {
                continue;
            }
            String pathClass =
                    object.getPathClass() == null
                            ? null
                            : CanonicalCodec.requirePathClassName(
                                    object.getPathClass().toString(), "training object PathClass");
            if (objectType != null && pathClass != null) {
                annotationClasses.add(pathClass);
            }
            if (objectType != null && pathClass != null && mappedClasses.contains(pathClass)) {
                Geometry geometry =
                        canonicalArea(NativeRoiGeometry.convert(main), "training target geometry");
                if (!intersectsAny(geometry, trainingRois)) {
                    continue;
                }
                targetIndex.insert(
                        geometry.getEnvelopeInternal(),
                        new IndexedObject(
                                object.getID().toString(), objectType, pathClass, geometry));
            } else {
                Geometry geometry =
                        canonicalArea(NativeRoiGeometry.convert(main), "advisory object geometry");
                advisoryIndex.insert(
                        geometry.getEnvelopeInternal(),
                        new AdvisoryObject(pathClass == null, geometry));
            }
        }
        targetIndex.build();
        advisoryIndex.build();

        List<CapturedRoi> captured = new ArrayList<>(targets.size());
        for (var target : targets) {
            String roiId = ((ExecutionScopeCodec.Roi) target.key()).roiId();
            Geometry roi = target.geometrySlide();
            List<TrainingObject> objects = new ArrayList<>();
            for (Object candidate : targetIndex.query(roi.getEnvelopeInternal())) {
                IndexedObject item = (IndexedObject) candidate;
                if (positiveAreaIntersection(roi, item.geometry())) {
                    objects.add(
                            new TrainingObject(
                                    item.uuid(),
                                    item.objectType(),
                                    item.pathClass(),
                                    item.geometry()));
                }
            }
            objects.sort(java.util.Comparator.comparing(TrainingObject::sourceObjectUuid));
            if (objects.isEmpty() && !selectedTask.supportsNegativeRois()) {
                throw new IllegalArgumentException(
                        "training ROI " + roiId + " contains no target objects");
            }
            int noClass = 0;
            int notUsed = 0;
            for (Object candidate : advisoryIndex.query(roi.getEnvelopeInternal())) {
                AdvisoryObject item = (AdvisoryObject) candidate;
                if (positiveAreaIntersection(roi, item.geometry())) {
                    if (item.noClass()) {
                        noClass++;
                    } else {
                        notUsed++;
                    }
                }
            }
            captured.add(
                    new CapturedRoi(
                            roiId,
                            roi,
                            objects,
                            new LabelCounts(objects.size(), noClass, notUsed)));
        }
        captured.sort(java.util.Comparator.comparing(CapturedRoi::sourceRoiUuid));

        Optional<String> group =
                Optional.ofNullable(projectGroup)
                        .map(String::strip)
                        .filter(value -> !value.isEmpty());
        return Optional.of(
                new CapturedImage(
                        entryId,
                        CanonicalCodec.sha256(entryId.getBytes(StandardCharsets.UTF_8)),
                        group,
                        revision,
                        selectedTask,
                        captured,
                        annotationClasses));
    }

    /** Validate all dataset counts again on the complete detached capture before pixel work. */
    public static void requireWithinLimits(
            List<CapturedImage> images, TrainingDatasetLimits limits) {
        List<CapturedImage> captures = List.copyOf(images);
        if (captures.isEmpty()) {
            throw new IllegalArgumentException("training capture requires project images");
        }
        TrainingDatasetLimits maximums = Objects.requireNonNull(limits, "limits");
        long roiCount = 0;
        long objectCount = 0;
        long vertexCount = 0;
        for (CapturedImage image : captures) {
            Objects.requireNonNull(image, "captured image");
            for (CapturedRoi roi : image.rois()) {
                roiCount = add(roiCount, 1, "training ROI count overflow");
                requireAtMost("training ROIs", roiCount, maximums.maxRois());

                long roiObjects = roi.targetObjects().size();
                requireRoiObjectLimit(
                        image.projectEntryId(),
                        roi.sourceRoiUuid(),
                        roi.geometry(),
                        roiObjects,
                        maximums.maxObjectsPerRoi());
                objectCount = add(objectCount, roiObjects, "training object count overflow");
                requireAtMost("training objects", objectCount, maximums.maxTotalObjects());

                vertexCount = addGeometryVertices(roi.geometry(), vertexCount, maximums);
                for (TrainingObject object : roi.targetObjects()) {
                    vertexCount = addGeometryVertices(object.geometry(), vertexCount, maximums);
                }
            }
        }
    }

    /**
     * An admission failure, not an unreadable image; preserve its actionable message through I/O.
     */
    public static final class RoiObjectLimitException extends IllegalArgumentException {
        private RoiObjectLimitException(String message) {
            super(message);
        }
    }

    static void requireRoiObjectLimit(
            String imageId, String roiId, Geometry roi, long count, long maximum) {
        if (count <= maximum) return;
        var bounds = roi.getEnvelopeInternal();
        throw new RoiObjectLimitException(
                String.format(
                        Locale.ROOT,
                        "Training ROI contains %,d target objects; the limit is %,d per ROI.\n"
                                + "Project image ID: %s\n"
                                + "ROI ID: %s\n"
                                + "Location in slide pixels: x=%s, y=%s, width=%s, height=%s.\n\n"
                                + "Resize this ROI or replace it with smaller, non-overlapping HistoForge training ROIs, then click Refresh in Train. "
                                + "Existing detections whose classes are mapped to this training task also count as labels.",
                        count,
                        maximum,
                        imageId,
                        roiId,
                        bounds.getMinX(),
                        bounds.getMinY(),
                        bounds.getWidth(),
                        bounds.getHeight()));
    }

    static List<ExecutionScopeCodec.TargetRegion> captureTargets(
            List<PathObject> objects, int imageWidth, int imageHeight) {
        RoiRegistry registry = RoiRegistry.fromHierarchyObjects(objects);
        List<String> ids = registry.rois().stream().map(item -> item.getID().toString()).toList();
        if (ids.isEmpty()) return List.of();
        var targets = registry.captureTargets(ids, imageWidth, imageHeight);
        for (var target : targets) {
            if (target.plane().z() != 0 || target.plane().t() != 0) {
                throw new IllegalArgumentException("training ROIs must be on z=0, t=0");
            }
        }
        return targets;
    }

    private static long addGeometryVertices(
            Geometry geometry, long current, TrainingDatasetLimits limits) {
        long count = CanonicalGeometryCodec.vertexCount(geometry);
        requireAtMost("vertices in one geometry", count, limits.maxVerticesPerGeometry());
        long total = add(current, count, "training vertex count overflow");
        requireAtMost("training vertices", total, limits.maxTotalVertices());
        return total;
    }

    private static long add(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(message, error);
        }
    }

    private static void requireAtMost(String path, long value, long maximum) {
        if (value > maximum) {
            throw new IllegalArgumentException(
                    String.format(
                            Locale.ROOT,
                            "Limit exceeded for %s: actual %,d; maximum %,d.",
                            path,
                            value,
                            maximum));
        }
    }

    static String objectType(PathObject object) {
        return switch (object) {
            case PathCellObject ignored -> null;
            case PathDetectionObject ignored -> "detection";
            case PathAnnotationObject ignored -> "annotation";
            default -> null;
        };
    }

    static boolean isSchemaOneArea(ROI roi) {
        return roi.isArea() && isSchemaOnePlane(roi);
    }

    private static boolean isSchemaOnePlane(ROI roi) {
        return roi.getImagePlane().getZ() == 0 && roi.getImagePlane().getT() == 0;
    }

    static boolean positiveAreaIntersection(Geometry left, Geometry right) {
        return left.getEnvelopeInternal().intersects(right.getEnvelopeInternal())
                && left.intersection(right).getArea() > 0.0;
    }

    private static boolean intersectsAny(Geometry geometry, List<Geometry> trainingRois) {
        return trainingRois.stream()
                .anyMatch(trainingRoi -> positiveAreaIntersection(trainingRoi, geometry));
    }

    static Geometry canonicalArea(Geometry geometry, String path) {
        Geometry copy = Objects.requireNonNull(geometry, path).copy();
        CanonicalGeometryCodec.canonicalCoordinates(copy);
        if (!(copy.getArea() > 0.0)) {
            throw new IllegalArgumentException(path + " must have positive area");
        }
        return copy;
    }

    private record IndexedObject(
            String uuid, String objectType, String pathClass, Geometry geometry) {}

    private record AdvisoryObject(boolean noClass, Geometry geometry) {}
}
