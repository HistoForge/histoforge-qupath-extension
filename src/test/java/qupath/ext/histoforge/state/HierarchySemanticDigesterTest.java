package qupath.ext.histoforge.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.ObjectStreamClass;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.PathTileObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.DefaultTMAGrid;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

class HierarchySemanticDigesterTest {

    @Test
    void classReuseCoversEveryPersistedHostField() throws Exception {
        assertTrue(Modifier.isFinal(PathClass.class.getModifiers()));
        assertEquals(
                List.of("colorRGB", "name", "parentClass"),
                Arrays.stream(ObjectStreamClass.lookup(PathClass.class).getFields())
                        .map(field -> field.getName())
                        .sorted()
                        .toList());
        for (String field : List.of("name", "parentClass")) {
            assertTrue(Modifier.isFinal(PathClass.class.getDeclaredField(field).getModifiers()));
        }
    }

    @Test
    void repeatedClassesSerializeOncePerCaptureAndNeverAcrossCaptures() {
        var hierarchy = new PathObjectHierarchy();
        var base = PathClass.fromString("Capture reuse base", 0x112233);
        var derived = PathClass.getInstance(base, "Derived", 0x223344);
        var first =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(1, 1, 2, 2, null), derived);
        var second =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(5, 1, 2, 2, null), derived);
        hierarchy.getRootObject().addChildObjects(List.of(first, second));
        try (var serialization = mockStatic(ImageDataRevisionDigester.class, CALLS_REAL_METHODS)) {
            String digest = HierarchySemanticDigester.capture(hierarchy, false).digest();
            serialization.verify(
                    () -> ImageDataRevisionDigester.serializedComponentDigest(base, "PathClass"));
            serialization.verify(
                    () ->
                            ImageDataRevisionDigester.serializedComponentDigest(
                                    derived, "PathClass"));
            assertEquals(digest, HierarchySemanticDigester.capture(hierarchy, false).digest());
            serialization.verify(
                    () -> ImageDataRevisionDigester.serializedComponentDigest(base, "PathClass"),
                    times(2));
            serialization.verify(
                    () -> ImageDataRevisionDigester.serializedComponentDigest(derived, "PathClass"),
                    times(2));
        }
    }

    @Test
    void classAndAncestorColorChangesRemainVisibleWithinAndBetweenCaptures() {
        for (boolean ancestor : List.of(false, true)) {
            var hierarchy = new PathObjectHierarchy();
            var base = PathClass.fromString("Capture color base " + ancestor, 0x112233);
            var derived = PathClass.getInstance(base, "Derived", 0x223344);
            var changed = ancestor ? base : derived;
            Integer original = changed.getColor();
            var first =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(1, 1, 2, 2, null), derived);
            var second =
                    spy(
                            PathObjects.createDetectionObject(
                                    ROIs.createRectangleROI(5, 1, 2, 2, null), derived));
            hierarchy.getRootObject().addChildObjects(List.of(first, second));
            String before = HierarchySemanticDigester.capture(hierarchy, false).digest();
            // Model a shared-class edit between visits, including a parent-class edit that must
            // invalidate a derived class's serialized bytes even if its own color did not change.
            doAnswer(
                            call -> {
                                changed.setColor(0x445566);
                                return call.callRealMethod();
                            })
                    .when(second)
                    .getName();
            try (var serialization =
                    mockStatic(ImageDataRevisionDigester.class, CALLS_REAL_METHODS)) {
                String during = HierarchySemanticDigester.capture(hierarchy, false).digest();
                serialization.verify(
                        () ->
                                ImageDataRevisionDigester.serializedComponentDigest(
                                        derived, "PathClass"),
                        times(2));
                assertNotEquals(before, during);
                assertNotEquals(
                        during, HierarchySemanticDigester.capture(hierarchy, false).digest());
            } finally {
                doReturn(null).when(second).getName();
                changed.setColor(original);
            }
            assertEquals(before, HierarchySemanticDigester.capture(hierarchy, false).digest());
        }
    }

    @Test
    void depthFirstTraversalRetainsChildOrderAndResourceTotals() {
        var hierarchy = new PathObjectHierarchy();
        PathObject parent = hierarchy.getRootObject();
        for (int depth = 0; depth < 200; depth++) {
            var siblings = new ArrayList<PathObject>();
            for (int index = 0; index < 10; index++) {
                siblings.add(
                        PathObjects.createAnnotationObject(
                                ROIs.createRectangleROI(index, depth, 1, 1, null)));
            }
            parent.addChildObjects(siblings);
            parent = siblings.get(4);
        }
        var before = HierarchySemanticDigester.capture(hierarchy, true);
        assertEquals(2001, before.size().objects());
        assertEquals(before.size(), HierarchySemanticDigester.measure(hierarchy, null));
        assertEquals(2001, before.objectIdsByIdentity().size());
        var root = hierarchy.getRootObject();
        var children = new ArrayList<>(root.getChildObjects());
        root.removeChildObject(children.get(0));
        root.addChildObject(children.get(0));
        assertNotEquals(
                before.digest(), HierarchySemanticDigester.capture(hierarchy, false).digest());
        root.clearChildObjects();
        root.addChildObjects(children);
        assertEquals(before.digest(), HierarchySemanticDigester.capture(hierarchy, false).digest());
    }

    @Test
    void omittingConnectionLookupPreservesHierarchyDigestAndResourceTotals() {
        var hierarchy = new PathObjectHierarchy();
        var parent =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 40, 40, null));
        var child = PathObjects.createDetectionObject(ROIs.createRectangleROI(5, 5, 3, 4, null));
        parent.addChildObject(child);
        hierarchy.getRootObject().addChildObject(parent);
        var core = PathObjects.createTMACoreObject(60, 60, 5, false);
        hierarchy.setTMAGrid(DefaultTMAGrid.create(List.of(core), 1));

        var omitted = HierarchySemanticDigester.capture(hierarchy, false);
        var retained = HierarchySemanticDigester.capture(hierarchy, true);
        var measuredIds = new IdentityHashMap<PathObject, String>();
        assertEquals(retained.size(), HierarchySemanticDigester.measure(hierarchy, null));
        assertEquals(retained.size(), HierarchySemanticDigester.measure(hierarchy, measuredIds));
        assertEquals(retained.objectIdsByIdentity().size(), measuredIds.size());
        retained.objectIdsByIdentity()
                .forEach((object, id) -> assertEquals(id, measuredIds.get(object)));
        assertEquals(retained.digest(), omitted.digest());
        assertEquals(retained.size(), omitted.size());
        assertEquals(4, omitted.size().objects());
        assertTrue(omitted.objectIdsByIdentity().isEmpty());
        assertEquals(4, retained.objectIdsByIdentity().size());
        for (PathObject object : List.of(hierarchy.getRootObject(), parent, child, core)) {
            assertEquals(object.getID().toString(), retained.objectIdsByIdentity().get(object));
        }
        var detached = PathObjects.createDetectionObject(child.getROI());
        detached.setID(child.getID());
        assertFalse(retained.objectIdsByIdentity().containsKey(detached));
        assertThrows(
                UnsupportedOperationException.class,
                () -> retained.objectIdsByIdentity().put(detached, detached.getID().toString()));
    }

    @Test
    void duplicateUuidsStillFailWhenConnectionLookupIsOmitted() {
        var hierarchy = new PathObjectHierarchy();
        var child = PathObjects.createDetectionObject(ROIs.createRectangleROI(5, 5, 3, 4, null));
        child.setID(hierarchy.getRootObject().getID());
        hierarchy.getRootObject().addChildObject(child);
        for (boolean retain : new boolean[] {false, true}) {
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> HierarchySemanticDigester.capture(hierarchy, retain));
            assertTrue(failure.getMessage().contains("duplicate PathObject UUID"));
            var countFailure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    HierarchySemanticDigester.measure(
                                            hierarchy, retain ? new IdentityHashMap<>() : null));
            assertEquals(failure.getMessage(), countFailure.getMessage());
        }
    }

    @Test
    void inconsistentParentStillFailsWhenConnectionLookupIsOmitted() throws Exception {
        var hierarchy = new PathObjectHierarchy();
        var child = PathObjects.createDetectionObject(ROIs.createRectangleROI(5, 5, 3, 4, null));
        hierarchy.getRootObject().addChildObject(child);
        // Create the corrupt link that QuPath's ordinary add/remove operations prevent.
        var parentField = PathObject.class.getDeclaredField("parent");
        parentField.setAccessible(true);
        parentField.set(child, null);
        for (boolean retain : new boolean[] {false, true}) {
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> HierarchySemanticDigester.capture(hierarchy, retain));
            assertEquals("hierarchy contains an inconsistent parent link", failure.getMessage());
            var countFailure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    HierarchySemanticDigester.measure(
                                            hierarchy, retain ? new IdentityHashMap<>() : null));
            assertEquals(failure.getMessage(), countFailure.getMessage());
        }
    }

    @Test
    void foreignTmaCoreStillFailsWhenConnectionLookupIsOmitted() {
        var hierarchy = new PathObjectHierarchy();
        var core = PathObjects.createTMACoreObject(60, 60, 5, false);
        hierarchy.setTMAGrid(DefaultTMAGrid.create(List.of(core), 1));
        hierarchy.getRootObject().removeChildObject(core);
        for (boolean retain : new boolean[] {false, true}) {
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> HierarchySemanticDigester.capture(hierarchy, retain));
            assertEquals("TMA grid references a core outside the hierarchy", failure.getMessage());
            var countFailure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    HierarchySemanticDigester.measure(
                                            hierarchy, retain ? new IdentityHashMap<>() : null));
            assertEquals(failure.getMessage(), countFailure.getMessage());
        }
    }

    @Test
    void countingRetainsExactTotalsWithoutMakingSemanticRoiCopies() {
        var hierarchy = new PathObjectHierarchy();
        ROI roi = spy(ROIs.createRectangleROI(1, 2, 3, 4, null));
        ROI nucleus = spy(ROIs.createEllipseROI(2, 3, 1, 1, null));
        var cell = PathObjects.createCellObject(roi, nucleus, PathClass.fromString("Leydig cell"));
        cell.setName("Unicode \u03bc");
        cell.getMetadata().put("review", "kept");
        cell.getMeasurementList().put("area", 12.5);
        hierarchy.getRootObject().addChildObject(cell);
        var captured = HierarchySemanticDigester.capture(hierarchy, false);
        clearInvocations(roi, nucleus);

        assertEquals(captured.size(), HierarchySemanticDigester.measure(hierarchy, null));
        verify(roi, never()).duplicate();
        verify(nucleus, never()).duplicate();
        assertEquals(
                captured.digest(), HierarchySemanticDigester.capture(hierarchy, false).digest());
    }

    @Test
    void countingRejectsUnsupportedObjectAndRoiTypes() {
        var hierarchy = new PathObjectHierarchy();
        hierarchy.getRootObject().addChildObject(new PathAnnotationObject() {});
        assertThrows(
                IllegalArgumentException.class,
                () -> HierarchySemanticDigester.measure(hierarchy, null));
        hierarchy.getRootObject().clearChildObjects();
        hierarchy
                .getRootObject()
                .addChildObject(PathObjects.createAnnotationObject(mock(ROI.class)));
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> HierarchySemanticDigester.measure(hierarchy, null));
        assertTrue(failure.getMessage().contains("unsupported persisted ROI type"));
    }

    @Test
    void nonClassDigestsRetainTheirRecordedValuesForEverySupportedObjectType() {
        var roi = ROIs.createRectangleROI(10, 20, 30, 40, ImagePlane.getDefaultPlane());
        var nucleus = ROIs.createEllipseROI(15, 25, 5, 5, ImagePlane.getDefaultPlane());
        var pathClass = PathClass.fromString("Script input");
        List<PathObject> objects =
                List.of(
                        new PathRootObject(),
                        PathObjects.createAnnotationObject(roi, pathClass),
                        PathObjects.createDetectionObject(roi, pathClass),
                        PathObjects.createTileObject(roi, pathClass),
                        PathObjects.createCellObject(roi, nucleus, pathClass),
                        PathObjects.createTMACoreObject(10, 20, 30, 40, false));
        // Recorded with the previous implementation, before omitting classification at capture.
        Map<Class<?>, String> expected =
                Map.of(
                        PathRootObject.class,
                        "d68f08ab4275db385bd8b9411463ebdf869873d4897d83c44af685f49fbd608e",
                        PathAnnotationObject.class,
                        "77a2ed1c1802f60763eb91525ba038e0fa4579712233197e9c0ff10e4c397f57",
                        PathDetectionObject.class,
                        "e25bcd17bd3ba8b3f6f2a274418e47bda810a676948129ca149063ace60a0cbf",
                        PathTileObject.class,
                        "acad53c2a67c3f5d2b371fed9b2854a649b1b3e4e64a71604c2fefcf959d120a",
                        PathCellObject.class,
                        "4623adeb2b5300e701b5587da6269e46013deceff242de0ac035f8dfcda5644f",
                        TMACoreObject.class,
                        "7ddbd102584dbcbab094a761f4e43b1e21b1a62a37789a1232f1378c13d63114");
        for (PathObject object : objects) {
            object.setID(UUID.fromString("11111111-1111-4111-8111-111111111111"));
            object.setName("Preserved name");
            object.getMetadata().put("kept", "value");
            object.getMeasurementList().put("area", 25.5);
            assertEquals(
                    expected.get(object.getClass()),
                    HierarchySemanticDigester.objectNonClassSemanticDigest(object),
                    object.getClass().getSimpleName());
        }
    }

    @Test
    void classificationIsIgnoredButOtherFieldsRemainCovered() {
        var object =
                PathObjects.createDetectionObject(
                        ROIs.createRectangleROI(1, 2, 3, 4, ImagePlane.getDefaultPlane()),
                        PathClass.fromString("Before script"));
        String before = HierarchySemanticDigester.objectNonClassSemanticDigest(object);
        String completeBefore = HierarchySemanticDigester.objectSemanticDigest(object);
        object.setPathClass(PathClass.fromString("After script"), 0.9);
        assertEquals(before, HierarchySemanticDigester.objectNonClassSemanticDigest(object));
        assertNotEquals(completeBefore, HierarchySemanticDigester.objectSemanticDigest(object));
        object.setName("Not allowed by a class-only script");
        assertNotEquals(before, HierarchySemanticDigester.objectNonClassSemanticDigest(object));
    }

    @Test
    void transactionDigestIgnoresChildOrderButStillRejectsAMissingChildUuid() {
        var parent =
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 40, 40, null));
        var first =
                spy(PathObjects.createDetectionObject(ROIs.createRectangleROI(5, 5, 3, 4, null)));
        var second = PathObjects.createDetectionObject(ROIs.createRectangleROI(15, 15, 3, 4, null));
        parent.addChildObjects(List.of(first, second));
        String transaction = HierarchySemanticDigester.objectTransactionSemanticDigest(parent);
        String complete = HierarchySemanticDigester.objectSemanticDigest(parent);
        parent.removeChildObject(first);
        parent.addChildObject(first);
        assertEquals(
                transaction, HierarchySemanticDigester.objectTransactionSemanticDigest(parent));
        assertNotEquals(complete, HierarchySemanticDigester.objectSemanticDigest(parent));
        // Stock QuPath repairs a null backing ID lazily. Pin rejection at this API boundary too.
        doReturn(null).when(first).getID();
        assertThrows(
                NullPointerException.class,
                () -> HierarchySemanticDigester.objectTransactionSemanticDigest(parent));
    }
}
