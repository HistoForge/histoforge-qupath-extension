package qupath.ext.histoforge.large;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;

class HierarchyTransactionDigesterTest {

    private static final String ROI_ID = "11111111-1111-4111-8111-111111111111";
    private static final ImagePlane PLANE = ImagePlane.getDefaultPlane();

    @Test
    void roiTransactionIgnoresOutsideEditsButRejectsTargetEdits() {
        Fixture fixture = fixture();
        var baseline =
                HierarchyTransactionDigester.captureBaseline(
                        fixture.target(), Set.of(), false, fixture.hierarchy());

        fixture.outside().setPathClass(PathClass.fromString("Outside changed"));
        assertEquals(
                baseline,
                HierarchyTransactionDigester.captureForRebase(
                        fixture.target(), baseline, false, fixture.hierarchy()));

        fixture.inside().setPathClass(PathClass.fromString("Inside changed"));
        assertNotEquals(
                baseline,
                HierarchyTransactionDigester.captureForRebase(
                        fixture.target(), baseline, false, fixture.hierarchy()));
    }

    @Test
    void equivalentRoiTransactionsIgnoreTheHierarchyRootInstance() {
        Fixture first = fixture();
        Fixture reopened = fixture();

        var baseline =
                HierarchyTransactionDigester.captureBaseline(
                        first.target(), Set.of(), false, first.hierarchy());

        assertEquals(
                baseline,
                HierarchyTransactionDigester.captureForRebase(
                        reopened.target(), baseline, false, reopened.hierarchy()));
    }

    @Test
    void slideTransactionCoversTheCompleteHierarchy() {
        Fixture fixture = fixture();
        var slide =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Slide(),
                        ROIs.createRectangleROI(0, 0, 1000, 1000, PLANE).getGeometry(),
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(
                                ROIs.createRectangleROI(0, 0, 1000, 1000, PLANE).getGeometry()));
        var baseline =
                HierarchyTransactionDigester.captureBaseline(
                        slide, Set.of(), false, fixture.hierarchy());

        fixture.outside().setPathClass(PathClass.fromString("Outside changed"));

        assertNotEquals(
                baseline,
                HierarchyTransactionDigester.captureForRebase(
                        slide, baseline, false, fixture.hierarchy()));
    }

    @Test
    void outOfDomainDescendantsAreGuardedAndTheSetIsRederived() {
        Fixture fixture = fixture();
        ExecutionScopeCodec.OutputKey output = output();
        PathObject produced = detection(500, 500, 20, 20, "Produced");
        ProvenanceCodec.writeMetadata(
                produced.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        output,
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        PathObject preserved = detection(525, 525, 10, 10, "Preserved");
        produced.addChildObject(preserved);
        fixture.hierarchy().addObject(produced);

        var fillBaseline =
                HierarchyTransactionDigester.captureBaseline(
                        fixture.target(), Set.of(), false, fixture.hierarchy());
        assertEquals(Set.of(), fillBaseline.guardedObjectIds());

        var baseline =
                HierarchyTransactionDigester.captureBaseline(
                        fixture.target(), Set.of(output), false, fixture.hierarchy());

        assertEquals(Set.of(preserved.getID().toString()), baseline.guardedObjectIds());
        preserved.setPathClass(PathClass.fromString("Changed preserved"));
        assertNotEquals(
                baseline,
                HierarchyTransactionDigester.captureForRebase(
                        fixture.target(), baseline, false, fixture.hierarchy()));

        preserved.setPathClass(PathClass.fromString("Preserved"));
        PathObject later = detection(540, 540, 5, 5, "Later child");
        produced.addChildObject(later);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HierarchyTransactionDigester.captureForRebase(
                                fixture.target(), baseline, false, fixture.hierarchy()));
    }

    @Test
    void expectedProjectionRetainsTheBaselineGuardSetAfterItsParentIsReplaced() {
        Fixture fixture = fixture();
        ExecutionScopeCodec.OutputKey output = output();
        PathObject produced = detection(500, 500, 20, 20, "Produced");
        ProvenanceCodec.writeMetadata(
                produced.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        output,
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        PathObject preserved = detection(525, 525, 10, 10, "Preserved");
        produced.addChildObject(preserved);
        fixture.hierarchy().addObject(produced);
        var baseline =
                HierarchyTransactionDigester.captureBaseline(
                        fixture.target(), Set.of(output), false, fixture.hierarchy());

        produced.removeChildObject(preserved);
        fixture.hierarchy().getRootObject().addChildObject(preserved);
        fixture.hierarchy().removeObject(produced, false);

        var expected =
                HierarchyTransactionDigester.captureExpected(
                        fixture.target(), baseline, false, fixture.hierarchy());

        assertEquals(baseline.guardedObjectIds(), expected.guardedObjectIds());
        assertEquals(baseline.replacementClosure(), expected.replacementClosure());
        assertNotEquals(baseline, expected);
    }

    @Test
    void expectedProjectionRejectsANewOutOfDomainGuard() {
        Fixture fixture = fixture();
        ExecutionScopeCodec.OutputKey output = output();
        var baseline =
                HierarchyTransactionDigester.captureBaseline(
                        fixture.target(), Set.of(output), false, fixture.hierarchy());
        PathObject produced = detection(500, 500, 20, 20, "Produced");
        ProvenanceCodec.writeMetadata(
                produced.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        output,
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        produced.addChildObject(detection(525, 525, 10, 10, "Unexpected child"));
        fixture.hierarchy().addObject(produced);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HierarchyTransactionDigester.captureExpected(
                                fixture.target(), baseline, false, fixture.hierarchy()));
    }

    @Test
    void roiMetricPopulationIncludesSpatiallyAssignedSlideOutputWithoutChangingRecipeOwnership() {
        Fixture fixture = fixture();
        PathObject slideObject = detection(40, 40, 10, 10, "Slide output");
        slideObject.setID(UUID.fromString("44444444-4444-4444-8444-444444444444"));
        ProvenanceCodec.writeMetadata(
                slideObject.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(
                                output().producer(),
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Slide())),
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
        fixture.hierarchy().getRootObject().addChildObject(slideObject);

        var recipeOnly =
                HierarchyTransactionDigester.captureBaseline(
                        fixture.target(), Set.of(), false, fixture.hierarchy());
        var withMetrics =
                HierarchyTransactionDigester.captureBaseline(
                        fixture.target(), Set.of(), true, fixture.hierarchy());
        assertNotEquals(recipeOnly, withMetrics);

        slideObject.setPathClass(PathClass.fromString("Changed slide output"));
        assertEquals(
                recipeOnly,
                HierarchyTransactionDigester.captureForRebase(
                        fixture.target(), recipeOnly, false, fixture.hierarchy()));
        assertNotEquals(
                withMetrics,
                HierarchyTransactionDigester.captureForRebase(
                        fixture.target(), withMetrics, true, fixture.hierarchy()));
    }

    @Test
    void metricOnlyInvalidMembershipIsFencedWithoutBlockingModelTransactionCapture() {
        Fixture fixture = fixture();
        PathObject missingLogicalRoi = detection(40, 40, 10, 10, "Missing logical ROI");
        missingLogicalRoi.setID(UUID.fromString("55555555-5555-4555-8555-555555555555"));
        ProvenanceCodec.writeMetadata(
                missingLogicalRoi.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        ProvenanceCodec.SCHEMA_VERSION,
                        new ExecutionScopeCodec.OutputKey(
                                output().producer(),
                                new ExecutionScopeCodec.FieldScope(
                                        new ExecutionScopeCodec.Roi(
                                                "66666666-6666-4666-8666-666666666666"))),
                        "77777777-7777-4777-8777-777777777777"));
        fixture.hierarchy().getRootObject().addChildObject(missingLogicalRoi);

        var baseline =
                HierarchyTransactionDigester.captureBaseline(
                        fixture.target(), Set.of(), true, fixture.hierarchy());
        missingLogicalRoi.setPathClass(PathClass.fromString("User repaired membership"));

        assertNotEquals(
                baseline,
                HierarchyTransactionDigester.captureForRebase(
                        fixture.target(), baseline, true, fixture.hierarchy()));
    }

    @Test
    void mixedNativeOutlinesKeepRoiAndSlideTransactionIdentities() throws Exception {
        Fixture fixture = fixture();
        // Whole-slide fingerprints include the root UUID; make this test hierarchy reproducible.
        fixture.hierarchy()
                .getRootObject()
                .setID(UUID.fromString("99999999-9999-4999-8999-999999999999"));
        int index = 0;
        for (String text :
                List.of(
                        "POLYGON ((20 20, 20 20, 40 20, 20 40, 20 20, 20 20))",
                        "POLYGON ((50 20, 70 20, 70 40, 50 40, 50 20), (52 22, 52 28, 58 28, 58 22, 52 22))",
                        "MULTIPOLYGON (((20 50, 30 50, 20 60, 20 50)), ((40 50, 50 50, 40 60, 40 50)))")) {
            var roi = GeometryTools.geometryToROI(new WKTReader().read(text), PLANE);
            var object = PathObjects.createDetectionObject(roi, PathClass.fromString("Cell"));
            object.setID(UUID.fromString("00000000-0000-4000-8000-00000000000" + (++index)));
            fixture.hierarchy().getRootObject().addChildObject(object);
        }
        var slideGeometry = ROIs.createRectangleROI(0, 0, 1000, 1000, PLANE).getGeometry();
        var slide =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Slide(),
                        slideGeometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(slideGeometry));
        for (var target : List.of(fixture.target(), slide)) {
            var baseline =
                    HierarchyTransactionDigester.captureBaseline(
                            target, Set.of(), true, fixture.hierarchy());
            String expected =
                    target.key() instanceof ExecutionScopeCodec.Roi
                            ? "b87f7b84b80c572615c6bf29a7191b54481b9f16bccc5742a9499bd0be495d22"
                            : "6d1f9a6493f652cc4f6568b73aaf89e0b1f494c0d1e0fd97688130fcba4200ed";
            assertEquals(expected, baseline.contentDigest());
            assertEquals(
                    baseline,
                    HierarchyTransactionDigester.captureExpected(
                            target, baseline, true, fixture.hierarchy()));
        }
    }

    @Test
    void expectedCaptureStillChecksRootUuidAndMissingPreviouslyGuardedObjects() {
        Fixture fixture = fixture();
        var baseline =
                HierarchyTransactionDigester.captureBaseline(
                        fixture.target(), Set.of(), false, fixture.hierarchy());
        fixture.hierarchy().getRootObject().setID(fixture.inside().getID());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HierarchyTransactionDigester.captureExpected(
                                fixture.target(), baseline, false, fixture.hierarchy()));

        Fixture guarded = fixture();
        var produced = detection(500, 500, 20, 20, "Produced");
        ProvenanceCodec.writeMetadata(
                produced.getMetadata(),
                new ProvenanceCodec.ObjectProvenance(
                        1, output(), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        var preserved = detection(525, 525, 10, 10, "Preserved");
        produced.addChildObject(preserved);
        guarded.hierarchy().getRootObject().addChildObject(produced);
        var before =
                HierarchyTransactionDigester.captureBaseline(
                        guarded.target(), Set.of(output()), false, guarded.hierarchy());
        produced.removeChildObject(preserved);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HierarchyTransactionDigester.captureExpected(
                                guarded.target(), before, false, guarded.hierarchy()));
    }

    private static Fixture fixture() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathObject roi =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 100, 100, PLANE));
        roi.setID(UUID.fromString(ROI_ID));
        roi.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        PathObject inside = detection(10, 10, 10, 10, "Inside");
        PathObject outside = detection(200, 200, 10, 10, "Outside");
        inside.setID(UUID.fromString("22222222-2222-4222-8222-222222222222"));
        outside.setID(UUID.fromString("33333333-3333-4333-8333-333333333333"));
        roi.addChildObject(inside);
        hierarchy.addObject(roi);
        hierarchy.addObject(outside);
        var geometry = roi.getROI().getGeometry();
        var target =
                new ExecutionScopeCodec.TargetRegion(
                        new ExecutionScopeCodec.Roi(ROI_ID),
                        geometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(geometry));
        return new Fixture(hierarchy, target, inside, outside);
    }

    private static PathObject detection(
            double x, double y, double width, double height, String pathClass) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, y, width, height, PLANE),
                PathClass.fromString(pathClass));
    }

    private static ExecutionScopeCodec.OutputKey output() {
        return new ExecutionScopeCodec.OutputKey(
                new InferenceIdentityCodec.ModelStepIdentity(
                        new ModuleRef("histoforge", "fixture"), "cells"),
                new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Roi(ROI_ID)));
    }

    private record Fixture(
            PathObjectHierarchy hierarchy,
            ExecutionScopeCodec.TargetRegion target,
            PathObject inside,
            PathObject outside) {}
}
