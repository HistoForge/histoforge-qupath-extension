package qupath.ext.histoforge.util;

import java.util.List;
import java.util.Objects;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.measurements.MeasurementList.MeasurementListType;
import qupath.lib.measurements.MeasurementListFactory;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/**
 * Replaces detection and annotation objects while retaining their shared state and identity.
 * Annotation conversion makes a detection editable with QuPath's annotation tools.
 */
public final class ConversionUtils {

    private ConversionUtils() {}

    /**
     * Replaces a detection under its existing parent and attaches its children to the replacement.
     * UUID, geometry, class and common metadata survive; measurements are copied. Returns null for
     * a null input or any other object type.
     */
    public static PathObject convertDetectionToAnnotation(
            PathObject detection, PathObjectHierarchy hierarchy) {
        if (detection == null || !detection.isDetection()) {
            return null;
        }

        PathObject annotation =
                PathObjects.createAnnotationObject(
                        detection.getROI(), detection.getPathClass(), copyMeasurements(detection));
        copyCommonState(detection, annotation);
        return replaceInHierarchy(detection, annotation, hierarchy);
    }

    /**
     * Replaces an annotation in its hierarchy using the same parent and children. Returns null when
     * the input is null or is not an annotation.
     */
    public static PathObject convertAnnotationToDetection(
            PathObject annotation, PathObjectHierarchy hierarchy) {
        if (annotation == null || !annotation.isAnnotation()) {
            return null;
        }

        return replaceInHierarchy(annotation, convertAnnotationToDetection(annotation), hierarchy);
    }

    private static PathObject replaceInHierarchy(
            PathObject original, PathObject replacement, PathObjectHierarchy hierarchy) {
        var parent = original.getParent();
        var children = List.copyOf(original.getChildObjects());
        hierarchy.removeObject(original, true);
        hierarchy.addObjectBelowParent(parent, replacement, true);
        children.forEach(child -> hierarchy.addObjectBelowParent(replacement, child, false));
        if (!children.isEmpty()) hierarchy.fireHierarchyChangedEvent(replacement);
        return replacement;
    }

    /**
     * Creates a detached detection with the annotation's UUID and shared state. Leaves hierarchy
     * mutation to the caller. Other object types, including null, pass through unchanged.
     */
    public static PathObject convertAnnotationToDetection(PathObject annotation) {
        if (annotation == null || !annotation.isAnnotation()) {
            return annotation;
        }

        PathObject detection =
                PathObjects.createDetectionObject(
                        annotation.getROI(),
                        annotation.getPathClass(),
                        copyMeasurements(annotation));
        copyCommonState(annotation, detection);

        return detection;
    }

    /** Copy every field shared by annotations and detections without aliasing mutable maps. */
    private static void copyCommonState(PathObject source, PathObject target) {
        target.setPathClass(source.getPathClass(), source.getClassProbability());
        target.setName(source.getName());
        target.setColor(source.getColor());
        target.setLocked(source.isLocked());
        if (source.hasMetadata()) {
            target.getMetadata().putAll(source.getMetadata());
        }
        target.setID(Objects.requireNonNull(source.getID(), "persistent object UUID"));
    }

    private static MeasurementList copyMeasurements(PathObject source) {
        if (!source.hasMeasurements()) {
            return null;
        }
        MeasurementList measurements =
                MeasurementListFactory.createMeasurementList(
                        source.getMeasurementList().size(), MeasurementListType.GENERAL);
        measurements.putAll(source.getMeasurementList());
        measurements.close();
        return measurements;
    }
}
