package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class ProjectSummaryDataTest {

    private static final String ROI_A = "11111111-1111-4111-8111-111111111111";
    private static final String ROI_B = "22222222-2222-4222-8222-222222222222";
    private static final String UNKNOWN_ROI = "33333333-3333-4333-8333-333333333333";
    private static final String GENERATION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Test
    void viewerCaptureProjectsDistinctRoiCoverageAndMalformedStateImmediately() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathObject roiA = administrativeRoi(ROI_A, 0);
        PathObject roiB = administrativeRoi(ROI_B, 20);
        PathObject moduleAField = output(ROI_A, "histoforge/first", "cells");
        PathObject moduleAParent = output(ROI_A, "histoforge/first", "children");
        PathObject moduleASecondRoi = output(ROI_B, "histoforge/first", "cells");
        PathObject moduleB = output(ROI_A, "histoforge/second", "cells");
        PathObject moduleBSlide = slideOutput("histoforge/second", "slide_cells");
        PathObject unknown = output(UNKNOWN_ROI, "histoforge/first", "cells");
        PathObject malformed = detection(80);
        malformed
                .getMetadata()
                .put(
                        qupath.ext.histoforge.contract.HistoforgePersistenceKeys.OBJECT_PROVENANCE,
                        "{");

        hierarchy.getRootObject().addChildObject(roiA);
        hierarchy.getRootObject().addChildObject(roiB);
        roiA.addChildObject(moduleAField);
        roiA.addChildObject(moduleAParent);
        roiB.addChildObject(moduleASecondRoi);
        roiA.addChildObject(moduleB);
        hierarchy.getRootObject().addChildObject(moduleBSlide);
        hierarchy.getRootObject().addChildObject(unknown);
        hierarchy.getRootObject().addChildObject(malformed);

        var detached = ProjectSummaryData.captureViewerHierarchy(hierarchy);
        var capture = detached.prepare();

        assertEquals(
                Set.of(ROI_A, ROI_B),
                capture.rois().stream()
                        .map(ProjectSummaryData.DetachedRoi::roiId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Map.of("histoforge/first", 2, "histoforge/second", 1),
                capture.outputRoiCountByModule());
        assertEquals(Set.of("histoforge/second"), capture.slideOutputModules());
        assertTrue(capture.outputStateInvalid());

        moduleASecondRoi.getMetadata().clear();
        assertEquals(2, capture.outputRoiCountByModule().get("histoforge/first"));
        assertEquals(capture, detached.prepare());
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryWorkerUsesCapturedAssignmentNameAndGroup() throws Exception {
        var entry =
                (qupath.lib.projects.ProjectImageEntry<java.awt.image.BufferedImage>)
                        org.mockito.Mockito.mock(qupath.lib.projects.ProjectImageEntry.class);
        var metadata = new java.util.HashMap<String, String>();
        metadata.put(HistoforgeKeys.MODULE, "histoforge/before");
        metadata.put(HistoforgeKeys.GROUP, "Control");
        org.mockito.Mockito.when(entry.getID()).thenReturn("image");
        org.mockito.Mockito.when(entry.getImageName()).thenReturn("Before");
        org.mockito.Mockito.when(entry.getMetadata()).thenReturn(metadata);
        var captured = ProjectSummaryData.ImageMetadata.capture(entry);
        metadata.put(HistoforgeKeys.MODULE, "histoforge/after");
        metadata.put(HistoforgeKeys.GROUP, "Treatment");
        org.mockito.Mockito.when(entry.getImageName()).thenReturn("After");
        var hierarchy = new PathObjectHierarchy();
        var raw = ProjectSummaryData.captureViewerHierarchy(hierarchy);
        org.mockito.Mockito.clearInvocations(entry);
        var summary =
                ProjectSummaryData.load(
                        java.util.List.of(captured),
                        raw,
                        entry,
                        null,
                        ProjectInferenceScope.defaults());
        assertEquals(
                "histoforge/before", summary.getImageSummaries().getFirst().metadata().moduleRef());
        assertEquals("Control", summary.getImageSummaries().getFirst().group());
        assertEquals(Map.of("image", "Before"), summary.getImageIdToName());
        org.mockito.Mockito.verify(entry, org.mockito.Mockito.never()).getMetadata();
        org.mockito.Mockito.verify(entry, org.mockito.Mockito.never()).getImageName();
    }

    @Test
    void captureDoesNotCreateAbsentMetadataAndStillRejectsPresentNullProvenance() {
        var hierarchy = new PathObjectHierarchy();
        PathObject unannotated = org.mockito.Mockito.spy(detection(0));
        hierarchy.getRootObject().addChildObject(unannotated);
        org.mockito.Mockito.clearInvocations(unannotated);

        var valid = ProjectSummaryData.captureViewerHierarchy(hierarchy).prepare();
        assertFalse(valid.outputStateInvalid());
        org.mockito.Mockito.verify(unannotated, org.mockito.Mockito.never()).getMetadata();

        unannotated
                .getMetadata()
                .put(
                        qupath.ext.histoforge.contract.HistoforgePersistenceKeys.OBJECT_PROVENANCE,
                        null);
        assertTrue(
                ProjectSummaryData.captureViewerHierarchy(hierarchy)
                        .prepare()
                        .outputStateInvalid());
    }

    @Test
    void summaryRetainsRootUuidCollisionFailure() {
        var hierarchy = new PathObjectHierarchy();
        PathObject child = detection(0);
        child.setID(hierarchy.getRootObject().getID());
        hierarchy.getRootObject().addChildObject(child);
        assertThrows(
                qupath.ext.histoforge.roi.RoiRegistry.CorruptHierarchyException.class,
                () -> ProjectSummaryData.captureViewerHierarchy(hierarchy));
    }

    private static PathObject administrativeRoi(String id, double x) {
        PathObject object =
                PathObjects.createAnnotationObject(
                        ROIs.createRectangleROI(x, 0, 10, 10, ImagePlane.getDefaultPlane()));
        object.setID(UUID.fromString(id));
        object.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        return object;
    }

    private static PathObject output(String roiId, String moduleRef, String stepId) {
        return output(
                new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(roiId)),
                moduleRef,
                stepId);
    }

    private static PathObject slideOutput(String moduleRef, String stepId) {
        return output(
                new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()),
                moduleRef,
                stepId);
    }

    private static PathObject output(
            ExecutionScopeCodec.ExecutionScope scope, String moduleRef, String stepId) {
        PathObject object = detection(40);
        ProvenanceCodec.writeMetadata(
                object.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(
                                new InferenceIdentityCodec.ModelStepIdentity(
                                        ModuleRef.parse(moduleRef).orElseThrow(), stepId),
                                scope),
                        GENERATION));
        return object;
    }

    private static PathObject detection(double x) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, 0, 5, 5, ImagePlane.getDefaultPlane()));
    }
}
