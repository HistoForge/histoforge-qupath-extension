package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricSnapshotBuilder;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Worker preparation sees captured fields, never later viewer edits or parent changes. */
class MetricPopulationCaptureTest {
    @Test
    void workerPreparationPreservesInputsAfterTheOriginalHierarchyChanges() throws Exception {
        var hierarchy = PlanningTestData.hierarchyWithRois();
        var boundary =
                RoiRegistry.fromHierarchy(hierarchy).findRoi(PlanningTestData.ROI_A).orElseThrow();
        var parent =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(10, 10, 70, 70, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Tubule"));
        var child =
                PathObjects.createDetectionObject(
                        ROIs.createPolygonROI(
                                new double[] {20, 25, 20},
                                new double[] {20, 20, 25},
                                ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Cell"));
        hierarchy.getRootObject().addChildObjects(List.of(parent, child));
        var targets =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(Set.of(PlanningTestData.ROI_A), 200, 100);
        var module = PlanningTestData.module(Set.of(), true);
        var build = PlanningTestData.clientBuild();
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "metric-test",
                        "7".repeat(64),
                        Optional.empty(),
                        200,
                        100,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                "metric-image",
                                SourceIdentityCodec.imageSourceIdentityDigest(source),
                                "8".repeat(64)),
                        source);
        var expected = MetricSnapshotBuilder.prepare(build, module, targets, revision, hierarchy);
        var captured =
                MetricSnapshotBuilder.captureForCalculation(
                        build, module, targets, revision, hierarchy, targets);
        assertFalse(expected.parentNormalization().isEmpty());

        parent.setPathClass(PathClass.fromString("Edited"));
        ((PathROIObject) parent).setROI(parent.getROI().translate(100, 100));
        child.setID(UUID.randomUUID());
        boundary.setID(UUID.randomUUID());
        parent.addChildObject(child);
        hierarchy.getRootObject().clearChildObjects();

        try (var worker = Executors.newSingleThreadExecutor()) {
            var actual = worker.submit(() -> captured.prepare().preparation()).get();
            assertEquals(expected, actual);
            assertEquals(expected.parentNormalization(), actual.parentNormalization());
            assertEquals(
                    expected.snapshot().metricInput().metricInputDigest(),
                    actual.snapshot().metricInput().metricInputDigest());
        }
    }
}
