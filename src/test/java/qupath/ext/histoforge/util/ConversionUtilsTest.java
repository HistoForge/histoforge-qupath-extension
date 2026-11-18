package qupath.ext.histoforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

class ConversionUtilsTest {

    private static final UUID OBJECT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void hierarchyConversionPreservesIdentityStateParentAndChildrenWithoutAliasing() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathObject parent = annotation(0, 0, 100, 100);
        PathObject source = detection(10, 10, 40, 40);
        PathObject child = detection(15, 15, 5, 5);
        populate(source);

        assertTrue(hierarchy.addObject(parent));
        assertTrue(hierarchy.addObjectBelowParent(parent, source, false));
        assertTrue(hierarchy.addObjectBelowParent(source, child, false));

        PathObject editable = ConversionUtils.convertDetectionToAnnotation(source, hierarchy);

        assertTrue(editable.isAnnotation());
        assertCommonState(source, editable);
        assertSame(parent, editable.getParent());
        assertSame(editable, child.getParent());
        assertFalse(hierarchy.getFlattenedObjectList(null).contains(source));
        assertTrue(hierarchy.getFlattenedObjectList(null).contains(editable));

        source.getMeasurementList().put("Area", 99.0);
        source.getMetadata().put("origin", "changed");
        assertEquals(12.5, editable.getMeasurementList().get("Area"));
        assertEquals("manual", editable.getMetadata().get("origin"));

        PathObject compact = ConversionUtils.convertAnnotationToDetection(editable, hierarchy);

        assertTrue(compact.isDetection());
        assertCommonState(editable, compact);
        assertEquals(
                Double.doubleToLongBits(editable.getMeasurementList().get("Precise")),
                Double.doubleToLongBits(compact.getMeasurementList().get("Precise")));
        assertSame(parent, compact.getParent());
        assertSame(compact, child.getParent());
        assertFalse(hierarchy.getFlattenedObjectList(null).contains(editable));
        assertTrue(hierarchy.getFlattenedObjectList(null).contains(compact));

        long compactPreciseBits =
                Double.doubleToLongBits(compact.getMeasurementList().get("Precise"));
        editable.getMeasurementList().put("Precise", 9.0);
        compact.getMeasurementList().put("Area", 7.0);
        assertEquals(
                compactPreciseBits,
                Double.doubleToLongBits(compact.getMeasurementList().get("Precise")));
        assertEquals(12.5, editable.getMeasurementList().get("Area"));
    }

    @Test
    void detachedConversionRetainsPersistentUuidAndCopiesMutableState() {
        PathObject source = annotation(3, 4, 20, 25);
        populate(source);

        PathObject converted = ConversionUtils.convertAnnotationToDetection(source);

        assertCommonState(source, converted);
        assertNotSame(source.getMeasurementList(), converted.getMeasurementList());
        assertNotSame(source.getMetadata(), converted.getMetadata());
        assertEquals(
                Double.doubleToLongBits(1.2345678901234567),
                Double.doubleToLongBits(converted.getMeasurementList().get("Precise")));

        source.getMeasurementList().put("Area", 3.0);
        converted.getMeasurementList().put("Precise", 4.0);
        assertEquals(12.5, converted.getMeasurementList().get("Area"));
        assertEquals(1.2345678901234567, source.getMeasurementList().get("Precise"));
    }

    private static void populate(PathObject object) {
        PathClass pathClass = PathClass.fromString("Tubule");
        object.setID(OBJECT_ID);
        object.setPathClass(pathClass, 0.875);
        object.setName("edited tubule");
        object.setColor(0x123456);
        object.setLocked(true);
        object.getMeasurementList().put("Area", 12.5);
        object.getMeasurementList().put("Precise", 1.2345678901234567);
        object.getMetadata().put("origin", "manual");
    }

    private static void assertCommonState(PathObject expected, PathObject actual) {
        assertEquals(expected.getID(), actual.getID());
        assertSame(expected.getROI(), actual.getROI());
        assertEquals(expected.getPathClass(), actual.getPathClass());
        assertEquals(
                Double.doubleToLongBits(expected.getClassProbability()),
                Double.doubleToLongBits(actual.getClassProbability()));
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getColor(), actual.getColor());
        assertEquals(expected.isLocked(), actual.isLocked());
        assertEquals(expected.getMeasurements(), actual.getMeasurements());
        assertEquals(Map.copyOf(expected.getMetadata()), Map.copyOf(actual.getMetadata()));
    }

    private static PathObject annotation(double x, double y, double width, double height) {
        return PathObjects.createAnnotationObject(
                ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane()));
    }

    private static PathObject detection(double x, double y, double width, double height) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane()));
    }
}
