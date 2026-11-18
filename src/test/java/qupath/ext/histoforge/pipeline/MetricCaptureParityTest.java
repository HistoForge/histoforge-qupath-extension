package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import qupath.ext.histoforge.contract.*;
import qupath.ext.histoforge.data.BaselineMetricSnapshotBuilder;
import qupath.ext.histoforge.data.MetricSnapshotBuilder;
import qupath.ext.histoforge.data.MetricTypes;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ScriptHeaderParser;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/** Fixed-build comparison against the pre-optimization capture algorithm. */
class MetricCaptureParityTest {
    private static final String THIRD = "99999999-9999-4999-8999-999999999999";

    @Test
    void completeAndSubsetSelectionsKeepExactInputsParentsAndExportEvidence() {
        var hierarchy = PlanningTestData.hierarchyWithRois();
        var tubule = object(1, 10, 10, 60, 60, ImagePlane.getDefaultPlane());
        var cell = object(2, 20, 20, 5, 5, ImagePlane.getDefaultPlane());
        var manual = object(3, 150, 10, 5, 5, ImagePlane.getDefaultPlane());
        writeOwner(tubule, PlanningTestData.ROI_A);
        hierarchy.addObjects(List.of(tubule, cell, manual));
        var selection = targets(hierarchy);
        parity(hierarchy, selection, selection);
        parity(hierarchy, selection.subList(0, 1), selection);
        parity(hierarchy, List.of(slide()), List.of(slide()));
        var module = PlanningTestData.module(Set.of(), true);
        var baseline =
                BaselineMetricSnapshotBuilder.capture(
                        PlanningTestData.clientBuild(), module, selection, revision(), hierarchy);
        var candidate =
                MetricSnapshotBuilder.capture(
                        PlanningTestData.clientBuild(), module, selection, revision(), hierarchy);
        assertEquals(baseline.attributedObjects(), candidate.attributedObjects());
        assertEquals(Set.of(tubule.getID()), candidate.attributedObjects().keySet());
    }

    @Test
    void overlappingUnrequestedRegionsDoNotPoisonSubsetButRequestedAmbiguityFails() {
        var hierarchy = PlanningTestData.hierarchyWithRois();
        hierarchy.addObject(
                PlanningTestData.administrativeRoi(
                        THIRD, 120, 0, 80, 100, ImagePlane.getDefaultPlane()));
        hierarchy.addObject(object(1, 140, 10, 5, 5, ImagePlane.getDefaultPlane()));
        var selection =
                List.of(PlanningTestData.ROI_A, PlanningTestData.ROI_B, THIRD).stream()
                        .flatMap(
                                id ->
                                        RoiRegistry.fromHierarchy(hierarchy)
                                                .captureTargets(Set.of(id), 200, 100)
                                                .stream())
                        .toList();
        var a =
                selection.stream()
                        .filter(
                                t ->
                                        t.key()
                                                .equals(
                                                        new ExecutionScopeCodec.Roi(
                                                                PlanningTestData.ROI_A)))
                        .toList();
        parity(hierarchy, a, selection);
        assertTrue(
                MetricSnapshotBuilder.prepare(
                                PlanningTestData.clientBuild(),
                                PlanningTestData.module(Set.of(), true),
                                a,
                                revision(),
                                hierarchy,
                                selection)
                        .snapshot()
                        .metricInput()
                        .regions()
                        .getFirst()
                        .objects()
                        .isEmpty());
        failureParity(hierarchy, selection, selection, "AmbiguousRoiMembershipException");
    }

    @Test
    void malformedOutsideTargetStillFailsRoiButNotSlideCoverageOrPlaneFilter() {
        for (var plane : List.of(ImagePlane.getDefaultPlane(), ImagePlane.getPlane(1, 0))) {
            var hierarchy = PlanningTestData.hierarchyWithRois();
            var malformed = object(1, 300, 10, 5, 5, plane);
            malformed.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "not-json");
            hierarchy.addObject(malformed);
            var selection = targets(hierarchy);
            failureParity(
                    hierarchy,
                    selection.subList(0, 1),
                    selection,
                    "MalformedObjectProvenanceException");
            parity(hierarchy, List.of(slide()), List.of(slide()));
        }
        var hierarchy = PlanningTestData.hierarchyWithRois();
        var malformed = object(1, 10, 10, 5, 5, ImagePlane.getPlane(1, 0));
        malformed.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "not-json");
        hierarchy.addObject(malformed);
        parity(hierarchy, List.of(slide()), List.of(slide()));
    }

    @Test
    void logicalOwnershipDoesNotReassignAcrossTargetsAndPlanes() {
        var hierarchy = PlanningTestData.hierarchyWithRois();
        var object = object(1, 10, 10, 5, 5, ImagePlane.getPlane(1, 0));
        writeOwner(object, PlanningTestData.ROI_B);
        hierarchy.addObject(object);
        var selection = targets(hierarchy);
        var a =
                selection.stream()
                        .filter(
                                t ->
                                        t.key()
                                                .equals(
                                                        new ExecutionScopeCodec.Roi(
                                                                PlanningTestData.ROI_A)))
                        .toList();
        parity(hierarchy, a, selection);
        failureParity(hierarchy, selection, selection, "IllegalArgumentException");
        writeOwner(object, THIRD);
        // A missing owner on another plane cannot affect the selected plane.
        parity(hierarchy, a, selection);
    }

    @Test
    void emptyPopulationsAndUnselectedBoundariesStayExcluded() {
        var hierarchy = PlanningTestData.hierarchyWithRois();
        var selection = targets(hierarchy);
        parity(hierarchy, selection, selection);
        parity(hierarchy, selection.subList(0, 1), selection);
        parity(hierarchy, List.of(slide()), List.of(slide()));
    }

    @Test
    void actualMetricScriptProducesIdenticalScalarsAndDistributions() {
        var hierarchy = PlanningTestData.hierarchyWithRois();
        hierarchy.addObjects(
                List.of(
                        object(1, 10, 10, 5, 5, ImagePlane.getDefaultPlane()),
                        object(2, 20, 10, 10, 5, ImagePlane.getDefaultPlane()),
                        object(3, 150, 10, 5, 5, ImagePlane.getDefaultPlane())));
        var base = PlanningTestData.module(Set.of(), true);
        String source =
                """
                /* @histoforge-metrics
                [{"name":"count","label":"Count","type":"scalar","decimals":0,"aggregation":"sum"},
                 {"name":"areas","label":"Areas","type":"distribution","decimals":2,"unit":"px2","aggregation":"pool"}]
                */
                return [count: children.size(), areas: children.collect { it.getROI().getArea() }]
                """;
        var script =
                new ModuleSnapshot.ScriptSnapshot(
                        "metrics.groovy",
                        source,
                        CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source)));
        var module =
                new ModuleSnapshot(
                        base.deploymentId(),
                        base.module(),
                        base.exactRecipe(),
                        base.scriptSetDigest(),
                        base.activatedModels(),
                        base.scripts(),
                        Optional.of(
                                new ModuleSnapshot.MetricScriptSnapshot(
                                        script, ScriptHeaderParser.parseMetrics(source))));
        var old =
                BaselineMetricSnapshotBuilder.prepare(
                        PlanningTestData.clientBuild(),
                        module,
                        targets(hierarchy),
                        revision(),
                        hierarchy);
        var next =
                MetricSnapshotBuilder.prepare(
                        PlanningTestData.clientBuild(),
                        module,
                        targets(hierarchy),
                        revision(),
                        hierarchy);
        var before = new MetricsEngine().evaluate(old, Map.of(), false, () -> false);
        var after = new MetricsEngine().evaluate(next, Map.of(), false, () -> false);
        assertTrue(before.complete(), before.regionFailures().toString());
        assertTrue(after.complete(), after.regionFailures().toString());
        assertEquals(before.result(), after.result());
        var values =
                after.regionResults()
                        .get(new ExecutionScopeCodec.Roi(PlanningTestData.ROI_A))
                        .values();
        assertEquals(new MetricTypes.Scalar(2), values.get("count"));
        assertEquals(new MetricTypes.Distribution(List.of(25.0, 50.0)), values.get("areas"));
    }

    @Test
    void invalidGeometryKeepsTheSameConditionalFailureBoundary() {
        var hierarchy = PlanningTestData.hierarchyWithRois();
        // A native PolygonROI repairs self-crossings during public conversion. Use an ROI
        // whose conversion really is invalid to exercise validation rather than host repair.
        var roi = mock(ROI.class);
        when(roi.isArea()).thenReturn(true);
        when(roi.getGeometry())
                .thenReturn(
                        new GeometryFactory()
                                .createPolygon(
                                        new Coordinate[] {
                                            new Coordinate(10, 10),
                                            new Coordinate(20, 20),
                                            new Coordinate(10, 20),
                                            new Coordinate(20, 10),
                                            new Coordinate(10, 10)
                                        }));
        var invalid = PathObjects.createDetectionObject(roi, PathClass.fromString("Cell"));
        hierarchy.addObject(invalid);
        var selection = targets(hierarchy);
        var a =
                selection.stream()
                        .filter(
                                t ->
                                        t.key()
                                                .equals(
                                                        new ExecutionScopeCodec.Roi(
                                                                PlanningTestData.ROI_A)))
                        .toList();
        writeOwner(invalid, PlanningTestData.ROI_B);
        parity(hierarchy, a, selection); // valid foreign ownership excludes invalid geometry
        failureParity(hierarchy, selection, selection, "IllegalArgumentException");
        failureParity(hierarchy, List.of(slide()), List.of(slide()), "IllegalArgumentException");
        invalid.getMetadata().clear();
        failureParity(hierarchy, a, selection, "CorruptHierarchyException");
    }

    private static void parity(
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> targets,
            List<ExecutionScopeCodec.TargetRegion> selection) {
        var module = PlanningTestData.module(Set.of(), true);
        var old =
                BaselineMetricSnapshotBuilder.prepare(
                        PlanningTestData.clientBuild(),
                        module,
                        targets,
                        revision(),
                        hierarchy,
                        selection);
        var next =
                MetricSnapshotBuilder.prepare(
                        PlanningTestData.clientBuild(),
                        module,
                        targets,
                        revision(),
                        hierarchy,
                        selection);
        assertEquals(old.parentNormalization(), next.parentNormalization());
        assertEquals(
                old.snapshot().metricInput().metricInputDigest(),
                next.snapshot().metricInput().metricInputDigest());
        var before = old.snapshot().metricInput().regions();
        var after = next.snapshot().metricInput().regions();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).target(), after.get(i).target());
            var x = before.get(i).objects();
            var y = after.get(i).objects();
            assertEquals(x.size(), y.size());
            for (int j = 0; j < x.size(); j++) {
                assertEquals(x.get(j).objectId(), y.get(j).objectId());
                assertEquals(x.get(j).objectType(), y.get(j).objectType());
                assertEquals(x.get(j).pathClass(), y.get(j).pathClass());
                assertEquals(x.get(j).geometrySlide(), y.get(j).geometrySlide());
                assertEquals(x.get(j).parentObjectId(), y.get(j).parentObjectId());
            }
        }
    }

    private static void failureParity(
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> targets,
            List<ExecutionScopeCodec.TargetRegion> selection,
            String category) {
        var module = PlanningTestData.module(Set.of(), true);
        var old =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                BaselineMetricSnapshotBuilder.prepare(
                                        PlanningTestData.clientBuild(),
                                        module,
                                        targets,
                                        revision(),
                                        hierarchy,
                                        selection));
        var next =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                MetricSnapshotBuilder.prepare(
                                        PlanningTestData.clientBuild(),
                                        module,
                                        targets,
                                        revision(),
                                        hierarchy,
                                        selection));
        assertEquals(category, old.getClass().getSimpleName());
        assertEquals(category, next.getClass().getSimpleName());
    }

    private static List<ExecutionScopeCodec.TargetRegion> targets(PathObjectHierarchy hierarchy) {
        return RoiRegistry.fromHierarchy(hierarchy)
                .captureTargets(Set.of(PlanningTestData.ROI_A, PlanningTestData.ROI_B), 200, 100);
    }

    private static PathObject object(
            int id, double x, double y, double w, double h, ImagePlane plane) {
        var object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(x, y, w, h, plane), PathClass.fromString("Cell"));
        object.setID(new UUID(6, id));
        return object;
    }

    private static void writeOwner(PathObject object, String roi) {
        ProvenanceCodec.writeMetadata(
                object.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(
                                PlanningTestData.producer("parent"),
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Roi(roi))),
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    }

    private static ExecutionScopeCodec.TargetRegion slide() {
        var geometry =
                ROIs.createRectangleROI(0, 0, 200, 100, ImagePlane.getDefaultPlane()).getGeometry();
        return new ExecutionScopeCodec.TargetRegion(
                new ExecutionScopeCodec.Slide(),
                geometry,
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                CanonicalGeometryCodec.canonicalDigest(geometry));
    }

    private static RasterInputCodec.ActionSourceRevision revision() {
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "parity",
                        "7".repeat(64),
                        Optional.empty(),
                        200,
                        100,
                        2,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        "image",
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        "8".repeat(64)),
                source);
    }
}
