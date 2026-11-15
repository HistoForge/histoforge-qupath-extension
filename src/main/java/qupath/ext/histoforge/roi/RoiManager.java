/*-
 * Histoforge QuPath Extension
 * Copyright (C) 2026 HistoForge contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package qupath.ext.histoforge.roi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.common.ColorTools;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.interfaces.ROI;

/** Validates administrative rectangles and maintains their lookup for the active hierarchy. */
public class RoiManager {
    private static final Logger logger = LoggerFactory.getLogger(RoiManager.class);

    public static final int MIN_SIZE = 1024;
    public static final int MAX_SIZE = 6000;

    public static final Integer HISTOFORGE_ROI_COLOR = ColorTools.packRGB(0, 255, 255);

    /** Persisted administrative class; biological classes belong to installed modules. */
    public static final PathClass HISTOFORGE_ROI_CLASS =
            PathClass.fromString("HistoForge:ROI", HISTOFORGE_ROI_COLOR);

    // Derived from the hierarchy; this lookup is not a separate source of saved state.
    private final Map<String, PathObject> roiCache = new ConcurrentHashMap<>();

    public RoiManager() {}

    /** Returns a mutable list without exposing the lookup's backing collection. */
    public List<PathObject> getHistoforgeRois() {
        return new ArrayList<>(roiCache.values());
    }

    /** The nearest marked ancestor, excluding the object itself. */
    public Optional<PathObject> findAncestorRoi(PathObject object) {
        for (var ancestor = object == null ? null : object.getParent();
                ancestor != null;
                ancestor = ancestor.getParent()) {
            if (isHistoforgeRoi(ancestor)) return Optional.of(ancestor);
        }
        return Optional.empty();
    }

    public static boolean isHistoforgeRoi(PathObject obj) {
        return RoiRegistry.isAdministrativeRoi(obj);
    }

    /** Case-insensitive comparison also recognizes the host's reloaded class instance. */
    public static boolean isHistoforgeRoiClass(PathClass pc) {
        if (pc == null) return false;
        return pc.toString().equalsIgnoreCase(HISTOFORGE_ROI_CLASS.toString());
    }

    /** Admit axis-aligned rectangles, including equivalent polygon representations. */
    public ValidationResult validateRoi(PathObject object) {
        ROI roi = object == null ? null : object.getROI();
        String error = null;
        if (roi == null) error = "No ROI found";
        else {
            var geometry = NativeRoiGeometry.convert(roi);
            if (!roi.isArea() || !geometry.equalsTopo(geometry.getEnvelope()))
                error = "Only axis-aligned rectangle ROIs are supported";
            else if (roi.getBoundsWidth() < MIN_SIZE || roi.getBoundsHeight() < MIN_SIZE)
                error = sizeError(roi, "small", "minimum", MIN_SIZE);
            else if (roi.getBoundsWidth() > MAX_SIZE || roi.getBoundsHeight() > MAX_SIZE)
                error = sizeError(roi, "large", "maximum", MAX_SIZE);
        }
        return new ValidationResult(error == null, error);
    }

    private static String sizeError(ROI roi, String size, String bound, int limit) {
        return String.format(
                "ROI too %s: %.0f×%.0f px (%s: %d×%d px)",
                size, roi.getBoundsWidth(), roi.getBoundsHeight(), bound, limit, limit);
    }

    /** Edge contact is allowed; only a positive-area intersection is overlap. */
    public boolean overlapsExistingRoi(ROI candidate) {
        if (candidate == null) return false;
        var geometry = NativeRoiGeometry.convert(candidate);
        return roiCache.values().stream()
                .map(PathObject::getROI)
                .filter(java.util.Objects::nonNull)
                .map(NativeRoiGeometry::convert)
                .anyMatch(
                        existing ->
                                geometry.intersects(existing)
                                        && geometry.intersection(existing).getArea() > 0);
    }

    /** Historical API name: this finds any intersection, not full geometric containment. */
    public Optional<PathObject> findContainingRoi(PathObject object) {
        if (object == null || object.getROI() == null) return Optional.empty();
        var geometry = NativeRoiGeometry.convert(object.getROI());
        return roiCache.values().stream()
                .filter(roi -> roi.getROI() != null)
                .filter(roi -> NativeRoiGeometry.convert(roi.getROI()).intersects(geometry))
                .findFirst();
    }

    public Optional<PathObject> getRoiById(String roiId) {
        return Optional.ofNullable(roiCache.get(roiId));
    }

    /** Order visible objects from top to bottom, then left to right. Missing geometry ties. */
    public static Comparator<PathObject> spatialOrder() {
        Comparator<ROI> position =
                Comparator.comparingDouble(ROI::getBoundsY).thenComparingDouble(ROI::getBoundsX);
        return (left, right) ->
                left == null || right == null || left.getROI() == null || right.getROI() == null
                        ? 0
                        : position.compare(left.getROI(), right.getROI());
    }

    /** Uses the same display ordering for callers that hold IDs rather than objects. */
    public Comparator<String> spatialIdOrder() {
        return (a, b) ->
                spatialOrder().compare(getRoiById(a).orElse(null), getRoiById(b).orElse(null));
    }

    /** Apply administrative properties without changing geometry or the persistent UUID. */
    public void applyHistoforgeRoiSettings(PathObject object) {
        if (object == null) return;
        object.setName("ROI " + nextRoiNumber());
        object.getMetadata().put(HistoforgeKeys.ROI_TYPE, HistoforgeKeys.ROI_TYPE_VALUE);
        object.setPathClass(HISTOFORGE_ROI_CLASS);
        object.setLocked(true);
        roiCache.put(object.getID().toString(), object);
    }

    /**
     * Delete marked HistoForge ROIs through the one logical cleanup operation.
     *
     * <p>The caller must own the ImageData hierarchy thread. The cleanup updates hierarchy and
     * generation evidence first; this manager then refreshes its derived cache and emits one
     * ordinary structure event.
     */
    public void deleteRois(ImageData<?> imageData, Collection<PathObject> rois) {
        RoiDeletionCleanup.Result result = RoiDeletionCleanup.apply(imageData, rois);
        populateFromHierarchy(imageData.getHierarchy());
        imageData.getHierarchy().fireHierarchyChangedEvent(this);
        logger.info(
                "Deleted {} HistoForge ROI(s), removed {} attributed object(s), and lifted {}"
                        + " preserved object(s)",
                result.removedRois(),
                result.removedAttributedObjects(),
                result.liftedPreservedObjects());
        if (!result.missingGenerationRecordIds().isEmpty()) {
            logger.warn(
                    "ROI cleanup retained {} attributed generation reference(s) without current"
                            + " records",
                    result.missingGenerationRecordIds().size());
        }
    }

    /** Restore cached ROI snapshots when post-delete fallback validation fails. */
    void restoreDeletedRois(ImageData<?> imageData, Collection<PathObject> rois) {
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        Set<String> currentIds =
                hierarchy.getAllObjects(false).stream()
                        .map(object -> object.getID().toString())
                        .collect(Collectors.toSet());
        for (PathObject roi : rois) {
            if (!currentIds.contains(roi.getID().toString())) {
                hierarchy.getRootObject().addChildObject(roi);
            }
        }
        populateFromHierarchy(hierarchy);
        hierarchy.fireHierarchyChangedEvent(this);
    }

    /** Rebuild the derived lookup after an image switch, load or structural edit. */
    public void populateFromHierarchy(PathObjectHierarchy hierarchy) {
        roiCache.clear();
        if (hierarchy != null)
            hierarchy.getAnnotationObjects().stream()
                    .filter(RoiManager::isHistoforgeRoi)
                    .forEach(object -> roiCache.put(object.getID().toString(), object));
    }

    private int nextRoiNumber() {
        return roiCache.values().stream()
                        .map(PathObject::getName)
                        .mapToInt(
                                name -> {
                                    if (name == null || !name.startsWith("ROI ")) return 0;
                                    try {
                                        return Integer.parseInt(name.substring(4));
                                    } catch (NumberFormatException ignored) {
                                        return 0;
                                    }
                                })
                        .filter(number -> number > 0)
                        .max()
                        .orElse(0)
                + 1;
    }

    /** Invalid results carry a user-facing reason; valid results have no error message. */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        public ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
