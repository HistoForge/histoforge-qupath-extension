package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.HistoforgePersistenceKeys;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricSnapshotBuilder;
import qupath.ext.histoforge.data.MetricTypes.MetricInputDraft;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectType;
import qupath.ext.histoforge.data.MetricTypes.MetricPreparation;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;

/** Live-hierarchy capture, administrative exclusion, and detached parent projection. */
class MetricSnapshotBuilderTest {

    private static final UUID TUBULE_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID CELL_ID = UUID.fromString("60000000-0000-4000-8000-000000000002");
    private static final String MISSING_ROI = "99999999-9999-4999-8999-999999999999";

    @Test
    void capturesCompleteDetachedRoiPopulationAndRepairsOnlyParentEdges() {
        PathObjectHierarchy hierarchy = PlanningTestData.hierarchyWithRois();
        PathObject tubule = detection(TUBULE_ID, "Tubule", 10, 10, 70, 70);
        PathObject cell = detection(CELL_ID, "Cell", 20, 20, 5, 5);
        hierarchy.addObjects(List.of(tubule, cell));
        ExecutionScopeCodec.TargetRegion target =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(Set.of(PlanningTestData.ROI_A), 200, 100)
                        .getFirst();

        MetricPreparation preparation =
                MetricSnapshotBuilder.prepare(
                        PlanningTestData.clientBuild(),
                        PlanningTestData.module(Set.of(), true),
                        List.of(target),
                        sourceRevision(),
                        hierarchy);

        var region = preparation.snapshot().metricInput().regions().getFirst();
        Map<UUID, MetricObjectSnapshot> objects =
                region.objects().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        MetricObjectSnapshot::objectId, value -> value));
        assertEquals(Set.of(TUBULE_ID, CELL_ID), objects.keySet());
        assertEquals(Optional.empty(), objects.get(TUBULE_ID).parentObjectId());
        assertEquals(Optional.of(TUBULE_ID), objects.get(CELL_ID).parentObjectId());
        assertEquals(
                List.of(
                        new HierarchyDelta.ParentAssignment(
                                TUBULE_ID, Optional.of(UUID.fromString(PlanningTestData.ROI_A))),
                        new HierarchyDelta.ParentAssignment(CELL_ID, Optional.of(TUBULE_ID))),
                preparation.parentNormalization());

        cell.setPathClass(PathClass.fromString("Changed after capture"));
        ((PathROIObject) cell)
                .setROI(ROIs.createRectangleROI(30, 30, 10, 10, ImagePlane.getDefaultPlane()));
        assertEquals("Cell", objects.get(CELL_ID).pathClass());
        assertEquals(25.0, objects.get(CELL_ID).geometrySlide().getArea());
    }

    @Test
    void metricsAndExportCaptureIdenticalInputWithMixedAttribution() {
        PathObjectHierarchy hierarchy = PlanningTestData.hierarchyWithRois();
        PathObject attributed = detection(TUBULE_ID, "Tubule", 10, 10, 70, 70);
        PathObject manual = detection(CELL_ID, "Cell", 20, 20, 5, 5);
        UUID outsideId = UUID.fromString("60000000-0000-4000-8000-000000000003");
        PathObject outside = detection(outsideId, "Outside", 150, 10, 5, 5);
        var provenance = provenance(PlanningTestData.ROI_A);
        var outsideProvenance = provenance(PlanningTestData.ROI_B);
        ProvenanceCodec.writeMetadata(attributed.getMetadata(), provenance);
        ProvenanceCodec.writeMetadata(outside.getMetadata(), outsideProvenance);
        hierarchy.addObjects(List.of(attributed, manual, outside));
        var roiTarget =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(Set.of(PlanningTestData.ROI_A), 200, 100)
                        .getFirst();
        var build = PlanningTestData.clientBuild();
        var module = PlanningTestData.module(Set.of(), true);

        for (var target : List.of(roiTarget, slideTarget())) {
            var prepared =
                    MetricSnapshotBuilder.prepare(
                            build, module, List.of(target), sourceRevision(), hierarchy);
            var exported =
                    MetricSnapshotBuilder.capture(
                            build, module, List.of(target), sourceRevision(), hierarchy);

            assertEquals(prepared, exported.preparation());
            assertEquals(
                    prepared.snapshot().metricInput().metricInputDigest(),
                    exported.preparation().snapshot().metricInput().metricInputDigest());
            assertEquals(
                    prepared.parentNormalization(), exported.preparation().parentNormalization());
            assertEquals(
                    target.key() instanceof ExecutionScopeCodec.Slide
                            ? Map.of(TUBULE_ID, provenance, outsideId, outsideProvenance)
                            : Map.of(TUBULE_ID, provenance),
                    exported.attributedObjects());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> exported.attributedObjects().clear());
        }
    }

    @Test
    void captureRejectsAnObjectSelectedIntoMoreThanOneRegion() {
        PathObjectHierarchy hierarchy = PlanningTestData.hierarchyWithRois();
        hierarchy.addObject(detection(CELL_ID, "Cell", 20, 20, 5, 5));
        var target =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(Set.of(PlanningTestData.ROI_A), 200, 100)
                        .getFirst();

        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                MetricSnapshotBuilder.prepare(
                                        PlanningTestData.clientBuild(),
                                        PlanningTestData.module(Set.of(), true),
                                        List.of(target, slideTarget()),
                                        sourceRevision(),
                                        hierarchy));

        assertEquals(
                "one metric preparation cannot select an object into multiple regions",
                failure.getMessage());
    }

    @Test
    void metricsPreparationRejectsMalformedProvenanceForRoiAndUnanchoredSlide() {
        PathObjectHierarchy roiHierarchy = PlanningTestData.hierarchyWithRois();
        var roiTarget =
                RoiRegistry.fromHierarchy(roiHierarchy)
                        .captureTargets(Set.of(PlanningTestData.ROI_A), 200, 100)
                        .getFirst();
        for (var target : List.of(roiTarget, slideTarget())) {
            PathObjectHierarchy hierarchy =
                    target.key() instanceof ExecutionScopeCodec.Slide
                            ? new PathObjectHierarchy()
                            : roiHierarchy;
            PathObject corrupt = detection(CELL_ID, "Cell", 20, 20, 5, 5);
            corrupt.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, "not-json");
            hierarchy.addObject(corrupt);

            assertThrows(
                    RoiRegistry.MalformedObjectProvenanceException.class,
                    () ->
                            MetricSnapshotBuilder.prepare(
                                    PlanningTestData.clientBuild(),
                                    PlanningTestData.module(Set.of(), true),
                                    List.of(target),
                                    sourceRevision(),
                                    hierarchy));
        }
    }

    @Test
    void exportAttributionCannotReferenceObjectsOutsideItsSnapshot() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObject(detection(CELL_ID, "Cell", 20, 20, 5, 5));
        var prepared =
                MetricSnapshotBuilder.prepare(
                        PlanningTestData.clientBuild(),
                        PlanningTestData.module(Set.of(), true),
                        List.of(slideTarget()),
                        sourceRevision(),
                        hierarchy);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MetricSnapshotBuilder.CapturedPreparation(
                                prepared, Map.of(TUBULE_ID, provenance(PlanningTestData.ROI_A))));
    }

    @Test
    void captureReturnsOnlyAttributionFromTheSameDetachedPopulation() {
        PathObjectHierarchy hierarchy = PlanningTestData.hierarchyWithRois();
        PathObject attributed = detection(TUBULE_ID, "Tubule", 10, 10, 70, 70);
        ProvenanceCodec.ObjectProvenance provenance =
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(
                                PlanningTestData.producer("parent"),
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Roi(PlanningTestData.ROI_A))),
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        ProvenanceCodec.writeMetadata(attributed.getMetadata(), provenance);
        PathObject outside = detection(CELL_ID, "Outside", 150, 10, 5, 5);
        ProvenanceCodec.writeMetadata(
                outside.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(
                                PlanningTestData.producer("parent"),
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Roi(PlanningTestData.ROI_B))),
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
        hierarchy.addObjects(List.of(attributed, outside));
        ExecutionScopeCodec.TargetRegion target =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(Set.of(PlanningTestData.ROI_A), 200, 100)
                        .getFirst();

        MetricSnapshotBuilder.CapturedPreparation captured =
                MetricSnapshotBuilder.capture(
                        PlanningTestData.clientBuild(),
                        PlanningTestData.module(Set.of(), true),
                        List.of(target),
                        sourceRevision(),
                        hierarchy);

        assertEquals(Map.of(TUBULE_ID, provenance), captured.attributedObjects());
        assertEquals(
                List.of(TUBULE_ID),
                captured
                        .preparation()
                        .snapshot()
                        .metricInput()
                        .regions()
                        .getFirst()
                        .objects()
                        .stream()
                        .map(MetricObjectSnapshot::objectId)
                        .toList());
    }

    @Test
    void validExistingRoiAndBiologicalParentsProduceNoDelta() {
        PathObjectHierarchy hierarchy = PlanningTestData.hierarchyWithRois();
        PathObject anchor =
                RoiRegistry.fromHierarchy(hierarchy).findRoi(PlanningTestData.ROI_A).orElseThrow();
        PathObject tubule = detection(TUBULE_ID, "Tubule", 10, 10, 70, 70);
        PathObject cell = detection(CELL_ID, "Cell", 20, 20, 5, 5);
        hierarchy.addObjectBelowParent(anchor, tubule, false);
        hierarchy.addObjectBelowParent(tubule, cell, false);
        ExecutionScopeCodec.TargetRegion target =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(Set.of(PlanningTestData.ROI_A), 200, 100)
                        .getFirst();

        MetricPreparation preparation =
                MetricSnapshotBuilder.prepare(
                        PlanningTestData.clientBuild(),
                        PlanningTestData.module(Set.of(), true),
                        List.of(target),
                        sourceRevision(),
                        hierarchy);

        assertTrue(preparation.parentNormalization().isEmpty());
        assertEquals(
                Optional.of(TUBULE_ID),
                preparation.snapshot().metricInput().regions().getFirst().objects().stream()
                        .filter(value -> value.objectId().equals(CELL_ID))
                        .findFirst()
                        .orElseThrow()
                        .parentObjectId());
    }

    @Test
    void mixedParentChangesPreserveExactInputAndDoNotMutateTheHierarchy() {
        PathObjectHierarchy hierarchy = PlanningTestData.hierarchyWithRois();
        PathObject anchor =
                RoiRegistry.fromHierarchy(hierarchy).findRoi(PlanningTestData.ROI_A).orElseThrow();
        PathObject excludedParent =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(0, 0, 100, 100, ImagePlane.getDefaultPlane()));
        PathObject tubule = detection(TUBULE_ID, "Tubule", 10, 10, 40, 40);
        PathObject repairedChild = detection(CELL_ID, "Cell", 20, 20, 5, 5);
        PathObject validChild =
                detection(
                        new UUID(
                                CELL_ID.getMostSignificantBits(),
                                CELL_ID.getLeastSignificantBits() + 1),
                        "Cell",
                        30,
                        30,
                        5,
                        5);
        PathObject unchangedAnchor =
                detection(
                        new UUID(
                                CELL_ID.getMostSignificantBits(),
                                CELL_ID.getLeastSignificantBits() + 2),
                        "Cell",
                        60,
                        10,
                        5,
                        5);
        PathObject repairedFallback =
                detection(
                        new UUID(
                                CELL_ID.getMostSignificantBits(),
                                CELL_ID.getLeastSignificantBits() + 3),
                        "Cell",
                        70,
                        20,
                        5,
                        5);
        PathObject unchangedAfter =
                detection(
                        new UUID(
                                CELL_ID.getMostSignificantBits(),
                                CELL_ID.getLeastSignificantBits() + 4),
                        "Cell",
                        80,
                        10,
                        5,
                        5);
        hierarchy.addObjectBelowParent(hierarchy.getRootObject(), excludedParent, false);
        hierarchy.addObjectBelowParent(anchor, tubule, false);
        hierarchy.addObjectBelowParent(excludedParent, repairedFallback, false);
        hierarchy.addObjectBelowParent(excludedParent, repairedChild, false);
        hierarchy.addObjectBelowParent(tubule, validChild, false);
        hierarchy.addObjectBelowParent(anchor, unchangedAnchor, false);
        hierarchy.addObjectBelowParent(anchor, unchangedAfter, false);
        var roiTarget =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(Set.of(PlanningTestData.ROI_A), 200, 100)
                        .getFirst();
        var objects =
                List.of(
                        tubule,
                        repairedChild,
                        validChild,
                        unchangedAnchor,
                        repairedFallback,
                        unchangedAfter);
        var expectedObjects =
                objects.stream()
                        .map(
                                object ->
                                        new MetricObjectSnapshot(
                                                object.getID(),
                                                MetricObjectType.DETECTION,
                                                object.getPathClass().toString(),
                                                object.getROI().getGeometry(),
                                                object == repairedChild || object == validChild
                                                        ? Optional.of(TUBULE_ID)
                                                        : Optional.empty()))
                        .toList();

        for (var target : List.of(roiTarget, slideTarget())) {
            var preparation =
                    MetricSnapshotBuilder.prepare(
                            PlanningTestData.clientBuild(),
                            PlanningTestData.module(Set.of(), true),
                            List.of(target),
                            sourceRevision(),
                            hierarchy);
            assertEquals(
                    List.of(
                            new HierarchyDelta.ParentAssignment(CELL_ID, Optional.of(TUBULE_ID)),
                            new HierarchyDelta.ParentAssignment(
                                    repairedFallback.getID(),
                                    target.key() instanceof ExecutionScopeCodec.Roi
                                            ? Optional.of(anchor.getID())
                                            : Optional.empty())),
                    preparation.parentNormalization());
            var actual = preparation.snapshot().metricInput();
            var captured = actual.regions().getFirst().objects();
            assertEquals(expectedObjects.size(), captured.size());
            for (var expected : expectedObjects) {
                var value =
                        captured.stream()
                                .filter(o -> o.objectId().equals(expected.objectId()))
                                .findFirst()
                                .orElseThrow();
                assertEquals(expected.objectType(), value.objectType());
                assertEquals(expected.pathClass(), value.pathClass());
                assertEquals(expected.geometrySlide(), value.geometrySlide());
                assertEquals(expected.parentObjectId(), value.parentObjectId());
            }
            assertEquals(
                    actual.metricInputDigest(),
                    MetricInputDraft.build(
                                    actual.clientBuild(),
                                    actual.calibration(),
                                    actual.module(),
                                    actual.script(),
                                    actual.regions())
                            .metricInputDigest());
            assertEquals(excludedParent, repairedChild.getParent());
            assertEquals(excludedParent, repairedFallback.getParent());
            assertEquals(anchor, tubule.getParent());
            assertEquals(tubule, validChild.getParent());
            assertEquals(anchor, unchangedAnchor.getParent());
            assertEquals(anchor, unchangedAfter.getParent());
        }
    }

    @Test
    void captureDetachesNativeAndFallbackOutlinesBeforeNormalizingParents() throws Exception {
        WKTReader reader = new WKTReader(GeometryTools.getDefaultFactory());
        for (String shape :
                List.of(
                        "POLYGON ((10 10,10 20,20 15,10 10))",
                        "POLYGON ((10 10,10 20,20 20,20 10,10 10),(12 12,18 12,18 18,12 18,12 12))")) {
            PathObjectHierarchy hierarchy = new PathObjectHierarchy();
            var geometry = reader.read(shape).norm();
            var object =
                    PathObjects.createDetectionObject(
                            GeometryTools.geometryToROI(geometry, ImagePlane.getDefaultPlane()),
                            PathClass.fromString("Cell"));
            object.setID(CELL_ID);
            hierarchy.addObject(object);
            var preparation =
                    MetricSnapshotBuilder.prepare(
                            PlanningTestData.clientBuild(),
                            PlanningTestData.module(Set.of(), true),
                            List.of(slideTarget()),
                            sourceRevision(),
                            hierarchy);
            var captured =
                    preparation.snapshot().metricInput().regions().getFirst().objects().getFirst();
            var input = preparation.snapshot().metricInput();
            String inputDigest = input.metricInputDigest();
            assertEquals(geometry, captured.geometrySlide());
            assertTrue(preparation.parentNormalization().isEmpty());
            var exposed = captured.geometrySlide();
            exposed.apply(
                    new CoordinateSequenceFilter() {
                        public void filter(CoordinateSequence sequence, int index) {
                            sequence.setOrdinate(index, 0, sequence.getX(index) + 100);
                        }

                        public boolean isDone() {
                            return false;
                        }

                        public boolean isGeometryChanged() {
                            return true;
                        }
                    });
            assertEquals(
                    geometry.getEnvelopeInternal().getMinX() + 100,
                    exposed.getEnvelopeInternal().getMinX());
            object.setPathClass(PathClass.fromString("Changed"));
            ((PathROIObject) object).setROI(object.getROI().translate(50, 50));
            assertEquals(geometry, captured.geometrySlide());
            assertEquals("Cell", captured.pathClass());
            assertEquals(Optional.empty(), captured.parentObjectId());
            assertEquals(
                    inputDigest,
                    MetricInputDraft.build(
                                    input.clientBuild(),
                                    input.calibration(),
                                    input.module(),
                                    input.script(),
                                    input.regions())
                            .metricInputDigest());
        }
    }

    @Test
    void slideSelectionIsOneRegionAndUsesTheHierarchyRootAsItsSyntheticParent() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathObject tubule = detection(TUBULE_ID, "Tubule", 10, 10, 70, 70);
        hierarchy.addObject(tubule);
        var geometry =
                ROIs.createRectangleROI(0, 0, 200, 100, ImagePlane.getDefaultPlane()).getGeometry();
        ExecutionScopeCodec.TargetRegion target =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Slide(),
                        geometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(geometry));

        MetricPreparation preparation =
                MetricSnapshotBuilder.prepare(
                        PlanningTestData.clientBuild(),
                        PlanningTestData.module(Set.of(), true),
                        List.of(target),
                        sourceRevision(),
                        hierarchy);

        assertEquals(1, preparation.snapshot().metricInput().regions().size());
        assertEquals(
                new ExecutionScopeCodec.Slide(),
                preparation.snapshot().metricInput().regions().getFirst().target().key());
        assertEquals(
                Optional.empty(),
                preparation
                        .snapshot()
                        .metricInput()
                        .regions()
                        .getFirst()
                        .objects()
                        .getFirst()
                        .parentObjectId());
        assertTrue(preparation.parentNormalization().isEmpty());
    }

    @Test
    void slideSelectionPreservesLiveRoiAnchorButProjectsItFromDetachedTree() {
        PathObjectHierarchy hierarchy = PlanningTestData.hierarchyWithRois();
        PathObject anchor =
                RoiRegistry.fromHierarchy(hierarchy).findRoi(PlanningTestData.ROI_A).orElseThrow();
        PathObject tubule = detection(TUBULE_ID, "Tubule", 10, 10, 70, 70);
        hierarchy.addObjectBelowParent(anchor, tubule, false);
        var geometry =
                ROIs.createRectangleROI(0, 0, 200, 100, ImagePlane.getDefaultPlane()).getGeometry();
        ExecutionScopeCodec.TargetRegion target =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Slide(),
                        geometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(geometry));

        MetricPreparation preparation =
                MetricSnapshotBuilder.prepare(
                        PlanningTestData.clientBuild(),
                        PlanningTestData.module(Set.of(), true),
                        List.of(target),
                        sourceRevision(),
                        hierarchy);

        assertTrue(preparation.parentNormalization().isEmpty());
        assertEquals(
                Optional.empty(),
                preparation
                        .snapshot()
                        .metricInput()
                        .regions()
                        .getFirst()
                        .objects()
                        .getFirst()
                        .parentObjectId());
        assertEquals(anchor, tubule.getParent());
    }

    @Test
    void missingLogicalRoiFailsOnlyWhenTheObjectIsInsideTheRequestedRoiScan() {
        PathObjectHierarchy hierarchy = PlanningTestData.hierarchyWithRois();
        PathObject outside = detection(TUBULE_ID, "Outside", 150, 10, 5, 5);
        writeMissingRoiProvenance(outside);
        hierarchy.addObject(outside);
        ExecutionScopeCodec.TargetRegion target =
                RoiRegistry.fromHierarchy(hierarchy)
                        .captureTargets(Set.of(PlanningTestData.ROI_A), 200, 100)
                        .getFirst();

        MetricPreparation preparation =
                assertDoesNotThrow(
                        () ->
                                MetricSnapshotBuilder.prepare(
                                        PlanningTestData.clientBuild(),
                                        PlanningTestData.module(Set.of(), true),
                                        List.of(target),
                                        sourceRevision(),
                                        hierarchy));
        assertTrue(preparation.snapshot().metricInput().regions().getFirst().objects().isEmpty());

        PathObject inside = detection(CELL_ID, "Inside", 10, 10, 5, 5);
        writeMissingRoiProvenance(inside);
        hierarchy.addObject(inside);
        assertThrows(
                MetricSnapshotBuilder.MissingLogicalRoiException.class,
                () ->
                        MetricSnapshotBuilder.prepare(
                                PlanningTestData.clientBuild(),
                                PlanningTestData.module(Set.of(), true),
                                List.of(target),
                                sourceRevision(),
                                hierarchy));
    }

    @Test
    void slideScanRejectsAmbiguousMembershipInsideItsCapturedGeometry() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(
                List.of(
                        PlanningTestData.administrativeRoi(
                                PlanningTestData.ROI_A,
                                0,
                                0,
                                100,
                                100,
                                ImagePlane.getDefaultPlane()),
                        PlanningTestData.administrativeRoi(
                                PlanningTestData.ROI_B,
                                50,
                                0,
                                100,
                                100,
                                ImagePlane.getDefaultPlane()),
                        detection(TUBULE_ID, "Ambiguous", 60, 10, 5, 5)));
        var geometry =
                ROIs.createRectangleROI(0, 0, 200, 100, ImagePlane.getDefaultPlane()).getGeometry();
        ExecutionScopeCodec.TargetRegion target =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Slide(),
                        geometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(geometry));

        assertThrows(
                MetricSnapshotBuilder.AmbiguousRoiMembershipException.class,
                () ->
                        MetricSnapshotBuilder.prepare(
                                PlanningTestData.clientBuild(),
                                PlanningTestData.module(Set.of(), true),
                                List.of(target),
                                sourceRevision(),
                                hierarchy));
    }

    private static PathObject detection(
            UUID id, String pathClass, double x, double y, double width, double height) {
        PathObject object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane()),
                        PathClass.fromString(pathClass));
        object.setID(id);
        return object;
    }

    private static RasterInputCodec.ActionSourceRevision sourceRevision() {
        SourceIdentityCodec.ImageSourceIdentity source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "metric-test",
                        "7".repeat(64),
                        Optional.empty(),
                        200,
                        100,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        "metric-image",
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        "8".repeat(64)),
                source);
    }

    private static void writeMissingRoiProvenance(PathObject object) {
        ProvenanceCodec.writeMetadata(
                object.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(
                                PlanningTestData.producer("parent"),
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Roi(MISSING_ROI))),
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    }

    private static ProvenanceCodec.ObjectProvenance provenance(String roiId) {
        return new ProvenanceCodec.ObjectProvenance(
                ProvenanceCodec.SCHEMA_VERSION,
                new ExecutionScopeCodec.OutputKey(
                        PlanningTestData.producer("parent"),
                        new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(roiId))),
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    }

    private static ExecutionScopeCodec.TargetRegion slideTarget() {
        var geometry =
                ROIs.createRectangleROI(0, 0, 200, 100, ImagePlane.getDefaultPlane()).getGeometry();
        return new ExecutionScopeCodec.TargetRegion(
                new ExecutionScopeCodec.Slide(),
                geometry,
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                CanonicalGeometryCodec.canonicalDigest(geometry));
    }
}
