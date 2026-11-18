package qupath.ext.histoforge.training;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.overlayng.OverlayNG;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingRoi;
import qupath.ext.histoforge.contract.TrainingGeometryCodec.TrainingObject;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PlannedImage;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedImage;
import qupath.ext.histoforge.training.TrainingProjectCapture.CapturedRoi;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder.ProjectPlan;

/** Proposes small, deterministic edge trims after a detached dataset's ROI label rejection. */
public final class TrainingOutlineOverlaps {

    private static final double MAX_OUTLINE_FRACTION = 0.01;
    private static final double RASTER_SEAM_CLEARANCE_PIXELS = 1e-6;
    // Clockwise neighbors. Adjacent entries share a side.
    private static final int[][] NEIGHBORS = {
        {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}
    };

    private TrainingOutlineOverlaps() {}

    public record Review(int count, String imageName, double x, double y) {}

    record Plan(ProjectPlan project, Review review) {}

    static boolean supports(String outputType) {
        return "instance_segmentation".equals(outputType)
                || "instance_segmentation_and_classification".equals(outputType);
    }

    static Optional<Plan> prepare(
            ProjectPlan project, TrainingRoi rejected, BooleanSupplier cancelled) {
        List<PlannedImage> images = new ArrayList<>(project.images().size());
        Coordinate rejectedCenter = null;
        int count = 0;
        for (int imageIndex = 0; imageIndex < project.images().size(); imageIndex++) {
            PlannedImage image = project.images().get(imageIndex);
            CapturedImage captured = image.capture();
            var calibration = captured.sourceRevision().pixelSource().calibration();
            double modelMpp = captured.task().modelMicronsPerPixel();
            double stepX = modelMpp / calibration.mppX();
            double stepY = modelMpp / calibration.mppY();
            List<CapturedRoi> rois = new ArrayList<>(captured.rois().size());
            int imageTrims = 0;
            for (CapturedRoi roi : captured.rois()) {
                checkCancelled(cancelled);
                Optional<RoiTrims> planned = planRoi(roi, stepX, stepY, cancelled);
                // The backend might not sample an unresolved overlap until training starts.
                if (planned.isEmpty()) return Optional.empty();
                RoiTrims trims = planned.orElseThrow();
                if (imageIndex == rejected.imageIndex()
                        && roi.sourceRoiUuid().equals(rejected.sourceRoiUuid())) {
                    rejectedCenter = trims.admissionCenter();
                    if (rejectedCenter == null) return Optional.empty();
                }
                imageTrims += trims.count();
                rois.add(trims.roi());
            }
            if (imageTrims > 0) {
                captured =
                        new CapturedImage(
                                captured.projectEntryId(),
                                captured.projectEntryIdDigest(),
                                captured.projectGroup(),
                                captured.sourceRevision(),
                                captured.task(),
                                rois,
                                captured.annotationClasses());
                image = new PlannedImage(captured, image.sourcePlan());
            }
            count += imageTrims;
            images.add(image);
        }
        if (rejectedCenter == null) return Optional.empty();
        String imageId = project.images().get(rejected.imageIndex()).capture().projectEntryId();
        return Optional.of(
                new Plan(
                        new ProjectPlan(images, project.imageNames()),
                        new Review(
                                count,
                                project.imageNames().get(imageId),
                                rejectedCenter.x,
                                rejectedCenter.y)));
    }

    private record RoiTrims(CapturedRoi roi, int count, Coordinate admissionCenter) {}

    private static Optional<RoiTrims> planRoi(
            CapturedRoi roi, double stepX, double stepY, BooleanSupplier cancelled) {
        try {
            return trim(roi, stepX, stepY, cancelled);
        } catch (TopologyException uncertain) {
            // Keep the original labels if the overlay cannot produce a reliable trim.
            return Optional.empty();
        }
    }

    private static Optional<RoiTrims> trim(
            CapturedRoi roi, double stepX, double stepY, BooleanSupplier cancelled) {
        var unchanged = Optional.of(new RoiTrims(roi, 0, null));
        if (roi.targetObjects().size() < 2) return unchanged;

        var labels =
                roi.targetObjects().stream()
                        .map(object -> new Label(object, object.geometry()))
                        .sorted(
                                Comparator.comparingDouble(
                                                (Label label) -> label.geometry().getArea())
                                        .thenComparing(label -> label.object().sourceObjectUuid()))
                        .toList();
        Geometry region = roi.geometry();
        var interior = PreparedGeometryFactory.prepare(region);
        STRtree index = new STRtree();
        for (int i = 0; i < labels.size(); i++) {
            index.insert(labels.get(i).geometry().getEnvelopeInternal(), i);
        }
        index.build();

        List<Label> resolved = new ArrayList<>(labels.size());
        int count = 0;
        Coordinate admissionCenter = null;
        for (int i = 0; i < labels.size(); i++) {
            checkCancelled(cancelled);
            Label label = labels.get(i);
            Geometry original = label.geometry();
            Geometry geometry = original;
            List<Integer> preceding = new ArrayList<>();
            for (Object entry : index.query(original.getEnvelopeInternal())) {
                int rank = (Integer) entry;
                if (rank < i) preceding.add(rank);
            }
            preceding.sort(Integer::compareTo);
            for (int rank : preceding) {
                checkCancelled(cancelled);
                Label winner = resolved.get(rank);
                if (!geometry.intersects(winner.geometry())) continue;
                Geometry shared = areaOverlay(geometry, winner.geometry(), OverlayNG.INTERSECTION);
                if (shared.getArea() == 0
                        || !TrainingProjectCapture.positiveAreaIntersection(region, shared)) {
                    continue;
                }
                var bounds = shared.getEnvelopeInternal();
                // A patch smaller than the model pixel spacing on both axes can cover at most
                // one pixel center, including when the model uses a padded ROI grid.
                if (bounds.getWidth() >= stepX || bounds.getHeight() >= stepY)
                    return Optional.empty();
                if (!interior.containsProperly(original)
                        || !interior.containsProperly(winner.geometry())
                        || !(original instanceof Polygon polygon)
                        || geometry != original) {
                    return Optional.empty();
                }
                // Two polygons can both paint a shared sloping edge after raster clipping.
                // Leave a subpixel gap and check the size and topology of the entire cut.
                double clearance = Math.min(stepX, stepY) * RASTER_SEAM_CLEARANCE_PIXELS;
                Geometry trimmed =
                        areaOverlay(original, shared.buffer(clearance, 1), OverlayNG.DIFFERENCE);
                if (!(trimmed instanceof Polygon result)
                        || !result.isValid()
                        || result.getNumInteriorRing() != polygon.getNumInteriorRing()
                        || !original.covers(result)
                        || !(result.distance(winner.geometry()) > clearance / 2)) {
                    return Optional.empty();
                }
                Geometry removed = areaOverlay(original, result, OverlayNG.DIFFERENCE);
                var cutBounds = removed.getEnvelopeInternal();
                if (!(removed.getArea() > 0)
                        || removed.getArea()
                                > MAX_OUTLINE_FRACTION
                                        * Math.min(original.getArea(), winner.geometry().getArea())
                        || cutBounds.getWidth() >= stepX
                        || cutBounds.getHeight() >= stepY
                        || !keepsNeighborPixelsConnected(
                                original, result, cutBounds, stepX, stepY)) {
                    return Optional.empty();
                }

                // Confirm the reported ROI independently. Trim all eligible geometric overlaps
                // because a context-padded training grid can sample a different pixel phase.
                if (admissionCenter == null) {
                    admissionCenter = sharedModelPixelCenter(shared, region, stepX, stepY);
                }
                geometry = trimmed;
                count++;
            }
            TrainingObject source = label.object();
            resolved.add(
                    geometry == original
                            ? label
                            : new Label(
                                    new TrainingObject(
                                            source.sourceObjectUuid(),
                                            source.objectType(),
                                            source.pathClass(),
                                            geometry),
                                    geometry));
        }
        if (count == 0) return unchanged;

        List<TrainingObject> objects =
                resolved.stream()
                        .map(Label::object)
                        .sorted(Comparator.comparing(TrainingObject::sourceObjectUuid))
                        .toList();
        return Optional.of(
                new RoiTrims(
                        new CapturedRoi(roi.sourceRoiUuid(), region, objects, roi.counts()),
                        count,
                        admissionCenter));
    }

    private record Label(TrainingObject object, Geometry geometry) {}

    private static Geometry areaOverlay(Geometry left, Geometry right, int operation) {
        var overlay = new OverlayNG(left, right, operation);
        overlay.setStrictMode(true);
        return overlay.getResult();
    }

    /** A one-pixel edit may not cut a thin bridge or erase the mask on any sampled grid. */
    private static boolean keepsNeighborPixelsConnected(
            Geometry original, Geometry trimmed, Envelope removed, double stepX, double stepY) {
        var before = PreparedGeometryFactory.prepare(original);
        var after = PreparedGeometryFactory.prepare(trimmed);
        boolean[] possible = new boolean[NEIGHBORS.length];
        boolean[] certain = new boolean[NEIGHBORS.length];
        int retained = 0;
        for (int i = 0; i < NEIGHBORS.length; i++) {
            // A removed center can fall anywhere inside the patch envelope. Test the full
            // envelope shifted by each neighboring pixel offset to cover all grid origins.
            var offset = new Envelope(removed);
            offset.translate(NEIGHBORS[i][0] * stepX, NEIGHBORS[i][1] * stepY);
            var positions = original.getFactory().toGeometry(offset);
            possible[i] = before.intersects(positions);
            certain[i] = after.containsProperly(positions);
            if (certain[i]) retained++;
        }
        if (retained == 0) return false;
        int runs = 0;
        for (int i = 0; i < NEIGHBORS.length; i++) {
            int previous = (i + NEIGHBORS.length - 1) % NEIGHBORS.length;
            int next = (i + 1) % NEIGHBORS.length;
            if (certain[i] && !certain[previous]) runs++;
            if (possible[i] && !certain[i] && !certain[previous] && !certain[next]) return false;
        }
        return runs <= 1;
    }

    private static Coordinate sharedModelPixelCenter(
            Geometry shared, Geometry roi, double stepX, double stepY) {
        var bounds = shared.getEnvelopeInternal();
        var frame = roi.getEnvelopeInternal();
        double x = firstCenter(bounds.getMinX(), frame.getMinX(), stepX);
        double y = firstCenter(bounds.getMinY(), frame.getMinY(), stepY);
        var position = new Coordinate(x, y);
        var center = shared.getFactory().createPoint(position);
        return shared.contains(center) && roi.contains(center) ? position : null;
    }

    private static double firstCenter(double minimum, double origin, double step) {
        return origin + (Math.ceil((minimum - origin) / step - 0.5) + 0.5) * step;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Training data preparation cancelled.");
        }
    }
}
