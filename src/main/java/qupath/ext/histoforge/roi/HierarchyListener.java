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
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent.HierarchyEventType;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;

/**
 * Protects HistoForge ROI identity, routes generic ROI deletion through scoped cleanup, refreshes
 * the derived ROI cache, and announces finalized edits. It never performs biological reparenting;
 * explicit Metrics normalization owns that mutation.
 */
public class HierarchyListener implements PathObjectHierarchyListener {
    private static final Logger logger = LoggerFactory.getLogger(HierarchyListener.class);

    private final RoiManager roiManager;
    private final Supplier<ImageData<?>> currentImageData;

    // Notifications produced by our own property repairs must not schedule more repairs.
    private volatile boolean isReverting = false;

    private volatile boolean batchRunning = false;

    // A batch gets at most one edit warning, even if it produces several hierarchy events.
    private volatile boolean warnedDuringBatch = false;

    /** UI-only callback used to hide process-local metric values after a live edit. */
    private Runnable onHierarchyChanged = () -> {};

    public HierarchyListener(RoiManager roiManager, Supplier<ImageData<?>> currentImageData) {
        this.roiManager = roiManager;
        this.currentImageData = currentImageData;
    }

    /**
     * MainController calls this on the FX thread. Ending the batch allows the next batch to show
     * its own edit warning.
     */
    public void setBatchRunning(boolean running) {
        batchRunning = running;
        if (!running) {
            warnedDuringBatch = false;
        }
    }

    public void setOnHierarchyChanged(Runnable callback) {
        onHierarchyChanged = callback != null ? callback : () -> {};
    }

    @Override
    public void hierarchyChanged(PathObjectHierarchyEvent event) {
        if (isReverting) {
            return;
        }
        // RoiManager publishes the already-complete scoped cleanup; do not reinterpret it as a
        // second generic deletion.
        if (event.getSource() == roiManager) {
            notifyHierarchyChanged();
            return;
        }

        // A drag is not a finalized edit; process it when the host ends the change.
        if (event.isChanging()) {
            return;
        }

        try {
            // Warn once if the user edits while a batch is running. The batch works on its own
            // disk-loaded copy, so that edit may conflict with the final revision guard.
            if (batchRunning && !warnedDuringBatch) {
                warnedDuringBatch = true;
                Platform.runLater(
                        () ->
                                Dialogs.showWarningNotification(
                                        "Batch Running",
                                        "Edits to this image may conflict with the running"
                                                + " HistoForge action."));
            }

            HierarchyEventType eventType = event.getEventType();
            switch (eventType) {
                case REMOVED -> cleanupMissingRois(event.getHierarchy());
                case CHANGE_CLASSIFICATION -> protectRoiProperties(event, false);
                case CHANGE_OTHER -> protectRoiProperties(event, true);
                case OTHER_STRUCTURE_CHANGE -> handleStructureChange(event);
                default -> {
                    // Added and ordinary object edits require no automatic hierarchy mutation.
                }
            }
        } finally {
            notifyHierarchyChanged();
        }
    }

    private void handleStructureChange(PathObjectHierarchyEvent event) {
        // QuPath omits the changed-object list here. Reconcile removed ROIs, or refresh the
        // lookup to include any ROIs restored by undo.
        PathObjectHierarchy hierarchy = event.getHierarchy();
        if (cleanupMissingRois(hierarchy)) {
            return;
        }
        roiManager.populateFromHierarchy(hierarchy);
    }

    /** Detect cached ROIs absent from the current hierarchy and run the shared scoped cleanup. */
    private boolean cleanupMissingRois(PathObjectHierarchy hierarchy) {
        Set<String> currentIds =
                hierarchy.getAllObjects(false).stream()
                        .map(object -> object.getID().toString())
                        .collect(java.util.stream.Collectors.toSet());
        List<PathObject> removed =
                roiManager.getHistoforgeRois().stream()
                        .filter(roi -> !currentIds.contains(roi.getID().toString()))
                        .toList();
        if (removed.isEmpty()) {
            return false;
        }

        ImageData<?> imageData = currentImageData.get();
        if (imageData == null || imageData.getHierarchy() != hierarchy) {
            logger.error("Cannot clean deleted HistoForge ROI: current ImageData does not match");
            return true;
        }

        try {
            roiManager.deleteRois(imageData, removed);
        } catch (RuntimeException error) {
            logger.error("HistoForge ROI cleanup failed; restoring the deleted ROI", error);
            roiManager.restoreDeletedRois(imageData, removed);
        }
        return true;
    }

    private void notifyHierarchyChanged() {
        try {
            onHierarchyChanged.run();
        } catch (RuntimeException failure) {
            logger.error("Hierarchy-change callback failed", failure);
        }
    }

    /** Repair only administrative properties; biological objects and parent links are untouched. */
    private void protectRoiProperties(PathObjectHierarchyEvent event, boolean checkLock) {
        var classifications = new ArrayList<PathObject>();
        var locks = new ArrayList<PathObject>();
        for (var object : event.getChangedObjects()) {
            if (!RoiManager.isHistoforgeRoi(object)) continue;
            if (!RoiManager.isHistoforgeRoiClass(object.getPathClass()))
                classifications.add(object);
            if (checkLock && !object.isLocked()) locks.add(object);
        }
        if (classifications.isEmpty() && locks.isEmpty()) return;
        Platform.runLater(
                () -> {
                    try {
                        isReverting = true;
                        var hierarchy = event.getHierarchy();
                        locks.forEach(object -> object.setLocked(true));
                        classifications.forEach(
                                object -> object.setPathClass(RoiManager.HISTOFORGE_ROI_CLASS));
                        if (!locks.isEmpty()) {
                            if (hierarchy != null) hierarchy.fireObjectsChangedEvent(this, locks);
                            Dialogs.showWarningNotification(
                                    "HistoForge",
                                    locks.size() == 1
                                            ? "This HistoForge ROI must stay locked. To change its outline, delete it and draw a new ROI."
                                            : "HistoForge ROIs must stay locked. To change an outline, delete that ROI and draw a new one.");
                        }
                        if (!classifications.isEmpty()) {
                            if (hierarchy != null)
                                hierarchy.fireObjectClassificationsChangedEvent(
                                        this, classifications);
                            Dialogs.showWarningNotification(
                                    "HistoForge",
                                    classifications.size() == 1
                                            ? "HistoForge ROI classification cannot be changed."
                                            : classifications.size()
                                                    + " HistoForge ROI classifications cannot be changed.");
                        }
                    } finally {
                        isReverting = false;
                    }
                });
    }
}
