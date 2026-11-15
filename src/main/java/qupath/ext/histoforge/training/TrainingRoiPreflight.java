package qupath.ext.histoforge.training;

import java.util.List;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Count-only ROI admission before source preparation and full semantic image capture. */
final class TrainingRoiPreflight {
    private TrainingRoiPreflight() {}

    /**
     * Run on the hierarchy's owner thread. Native bounds only exclude disjoint objects; exact
     * positive-area intersection decides every count. Retain no label geometries or capture result.
     * Passing this check never replaces complete capture, including validation of objects outside
     * the training ROIs and the final aggregate dataset limits.
     */
    static void requireWithinObjectLimit(
            String imageId,
            PathObjectHierarchy hierarchy,
            int width,
            int height,
            TrainingTask task,
            long maximum,
            Runnable checkRunning) {
        checkRunning.run();
        var objects = hierarchy.getFlattenedObjectList(null);
        var targets = TrainingProjectCapture.captureTargets(objects, width, height);
        var geometries =
                targets.stream().map(ExecutionScopeCodec.TargetRegion::geometrySlide).toList();
        long[] counts = new long[targets.size()];
        var index = new STRtree();
        for (int i = 0; i < targets.size(); i++) {
            index.insert(geometries.get(i).getEnvelopeInternal(), i);
        }
        index.build();

        for (var object : objects) {
            checkRunning.run();
            if (RoiRegistry.isAdministrativeRoi(object)
                    || object.isTile()
                    || TrainingProjectCapture.objectType(object) == null
                    || object.getPathClass() == null
                    || !task.annotationClassMap().containsKey(object.getPathClass().toString())) {
                continue;
            }
            var roi = object.getROI();
            if (roi == null || !TrainingProjectCapture.isSchemaOneArea(roi)) continue;
            var bounds =
                    new Envelope(
                            roi.getBoundsX(),
                            roi.getBoundsX() + roi.getBoundsWidth(),
                            roi.getBoundsY(),
                            roi.getBoundsY() + roi.getBoundsHeight());
            @SuppressWarnings("unchecked")
            List<Integer> possible = index.query(bounds);
            if (possible.isEmpty()) continue;
            var geometry =
                    TrainingProjectCapture.canonicalArea(
                            NativeRoiGeometry.convert(roi), "training target geometry");
            for (int target : possible) {
                if (TrainingProjectCapture.positiveAreaIntersection(
                        geometries.get(target), geometry)) {
                    counts[target]++;
                }
            }
        }
        // Count the entire population so the error reports an actual total, not a lower bound.
        for (int i = 0; i < targets.size(); i++) {
            TrainingProjectCapture.requireRoiObjectLimit(
                    imageId,
                    ((ExecutionScopeCodec.Roi) targets.get(i).key()).roiId(),
                    geometries.get(i),
                    counts[i],
                    maximum);
        }
        checkRunning.run();
    }
}
