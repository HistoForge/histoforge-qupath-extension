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

import java.awt.geom.Point2D;
import java.util.List;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.event.EventHandler;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.tools.PathTools;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiEditor;
import qupath.lib.roi.interfaces.ROI;

/**
 * Draws a bounded rectangle through QuPath's public ROI editor, or places a default rectangle on
 * click. A draft enters the hierarchy only after release passes size and overlap validation.
 */
public class RoiToolHandler implements EventHandler<MouseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(RoiToolHandler.class);

    /** Width and height in pixels for a new user's click-placed ROI. */
    public static final int DEFAULT_INITIAL_SIZE = 1024;

    /**
     * Click placement reads these pixel dimensions; the UI also supports calibrated display units.
     */
    public static final IntegerProperty defaultWidthProperty =
            PathPrefs.createPersistentPreference(
                    "histoforge.roi.defaultWidth", DEFAULT_INITIAL_SIZE);

    public static final IntegerProperty defaultHeightProperty =
            PathPrefs.createPersistentPreference(
                    "histoforge.roi.defaultHeight", DEFAULT_INITIAL_SIZE);

    /** Display unit only. Changing it does not rescale the stored pixel dimensions. */
    public static final StringProperty defaultUnitProperty =
            PathPrefs.createPersistentPreference("histoforge.roi.defaultUnit", "PX");

    /** Enum names are stored in user preferences. */
    public enum SizeUnit {
        PX("px", 0),
        UM("\u00B5m", 1),
        MM("mm", 2);

        private final String label;
        private final int decimals;

        SizeUnit(String label, int decimals) {
            this.label = label;
            this.decimals = decimals;
        }

        public int getDecimals() {
            return decimals;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final QuPathGUI qupath;
    private final RoiManager roiManager;

    // Image-space origin of the current draft, before any drag or click placement.
    private double anchorX;

    private double anchorY;

    private PathAnnotationObject currentObject;

    // Release also checks distance: a short accidental drag still places the default rectangle.
    private boolean dragged;

    public RoiToolHandler(QuPathGUI qupath, RoiManager roiManager) {
        this.qupath = qupath;
        this.roiManager = roiManager;
    }

    @Override
    public void handle(MouseEvent event) {
        var type = event.getEventType();
        if (type == MouseEvent.MOUSE_PRESSED) {
            dragged = false;
            beginRectangle(event);
        } else if (type == MouseEvent.MOUSE_RELEASED) {
            mouseReleased(event);
        } else if (type == MouseEvent.MOUSE_MOVED) {
            var viewer = qupath.getViewer();
            if (viewer != null) viewer.setCursor(Cursor.CROSSHAIR);
        } else if (type == MouseEvent.MOUSE_DRAGGED || type == MouseEvent.DRAG_DETECTED) {
            dragged = true;
            resizeRectangle(event);
        }
    }

    private static Point2D imagePoint(QuPathViewer viewer, MouseEvent event, boolean constrain) {
        Point2D point =
                viewer.componentPointToImagePoint(event.getX(), event.getY(), null, constrain);
        return PathPrefs.usePixelSnappingProperty().get()
                ? new Point2D.Double(Math.floor(point.getX()), Math.floor(point.getY()))
                : point;
    }

    private void beginRectangle(MouseEvent event) {
        if (event.isConsumed() || !event.isPrimaryButtonDown()) return;
        if (event.isPopupTrigger()) {
            event.consume();
            return;
        }
        if (event.getSource() instanceof Node node
                && node.isFocusTraversable()
                && !node.isFocused()) {
            node.requestFocus();
            event.consume();
            // A click that focuses this viewer also starts drawing.
        }
        var viewer = qupath.getViewer();
        if (viewer.getHierarchy() == null) return;
        // Do not clamp a press: an outside click must not create an ROI at the image edge.
        var point = imagePoint(viewer, event, false);
        anchorX = point.getX();
        anchorY = point.getY();
        if (anchorX < 0
                || anchorX >= viewer.getServerWidth()
                || anchorY < 0
                || anchorY >= viewer.getServerHeight()) return;
        logger.trace("Creating new HistoForge ROI at ({}, {})", anchorX, anchorY);
        currentObject =
                (PathAnnotationObject)
                        PathObjects.createAnnotationObject(
                                ROIs.createRectangleROI(
                                        anchorX, anchorY, 0, 0, viewer.getImagePlane()));
        viewer.setSelectedObject(currentObject);
        var editor = viewer.getROIEditor();
        editor.setROI(currentObject.getROI());
        editor.grabHandle(
                anchorX, anchorY, 1.5 * viewer.getMaxROIHandleSize(), event.isShiftDown());
    }

    private void resizeRectangle(MouseEvent event) {
        if (!event.isPrimaryButtonDown()) return;
        var viewer = qupath.getViewer();
        if (currentObject == null) return;
        ROI visible = viewer.getCurrentROI();
        RoiEditor editor = viewer.getROIEditor();
        if (visible == null || visible != editor.getROI() || !editor.hasActiveHandle()) return;
        var point = imagePoint(viewer, event, true);
        // The host editor owns Shift-to-square and the subpixel movement threshold.
        ROI resized =
                editor.setActiveHandlePosition(
                        cappedCoordinate(anchorX, point.getX()),
                        cappedCoordinate(anchorY, point.getY()),
                        0.25,
                        event.isShiftDown());
        if (resized != visible) {
            currentObject.setROI(resized);
            viewer.repaint();
        }
        viewer.getHierarchy().fireObjectsChangedEvent(this, List.of(currentObject), true);
        viewer.repaint();
    }

    /** Commit a valid draft, or discard it and explain the rejected placement. */
    private void mouseReleased(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }

        var viewer = qupath.getViewer();
        if (currentObject == null) {
            return;
        }

        boolean intentionalDrag = isIntentionalDrag(dragged, currentObject.getROI());
        var editor = viewer.getROIEditor();
        if (!intentionalDrag) {
            int width = clampDefaultSize(defaultWidthProperty.get());
            int height = clampDefaultSize(defaultHeightProperty.get());
            if (width > viewer.getServerWidth() || height > viewer.getServerHeight()) {
                reject(
                        viewer,
                        "Default ROI too large",
                        String.format(
                                "Default ROI size %d×%d exceeds image dimensions %d×%d.%n%nLower the ROI defaults on the HistoForge Project tab, or drag to draw a smaller ROI.",
                                width, height, viewer.getServerWidth(), viewer.getServerHeight()));
                return;
            }
            currentObject.setROI(
                    ROIs.createRectangleROI(
                            centeredCoordinate(anchorX, width, viewer.getServerWidth()),
                            centeredCoordinate(anchorY, height, viewer.getServerHeight()),
                            width,
                            height,
                            viewer.getImagePlane()));
        }
        ROI finalROI = currentObject.getROI();
        boolean editorMatches =
                !intentionalDrag || editor.getROI() == finalROI && editor.hasActiveHandle();
        editor.setROI(null);
        editor.resetActiveHandle();
        if (finalROI == null || !editorMatches) {
            resetState();
            return;
        }
        var validation = roiManager.validateRoi(currentObject);
        if (!validation.isValid()) {
            reject(viewer, "Invalid ROI", validation.getErrorMessage());
            return;
        }
        if (roiManager.overlapsExistingRoi(finalROI)) {
            reject(
                    viewer,
                    "ROI Overlap",
                    "This location overlaps with an existing HistoForge ROI.\n\nClick elsewhere, or drag to draw a smaller ROI in a different spot.");
            return;
        }
        roiManager.applyHistoforgeRoiSettings(currentObject);
        viewer.getHierarchy().addObject(currentObject);
        viewer.setSelectedObject(currentObject);
        editor.ensureHandlesUpdated();
        resetState();
        switchToMoveTool();
    }

    private void reject(QuPathViewer viewer, String title, String message) {
        if (currentObject != null && currentObject.getParent() != null)
            viewer.getHierarchy().removeObject(currentObject, true);
        viewer.setSelectedObject(null);
        viewer.getROIEditor().setROI(null);
        viewer.getROIEditor().resetActiveHandle();
        resetState();
        switchToMoveTool();
        Dialogs.showErrorMessage(title, message);
    }

    static boolean isIntentionalDrag(boolean dragged, ROI roi) {
        return dragged
                && roi != null
                && Math.max(roi.getBoundsWidth(), roi.getBoundsHeight()) >= RoiManager.MIN_SIZE;
    }

    static double cappedCoordinate(double anchor, double pointer) {
        return Math.clamp(pointer, anchor - RoiManager.MAX_SIZE, anchor + RoiManager.MAX_SIZE);
    }

    static double centeredCoordinate(double center, int size, int imageSize) {
        return Math.clamp(center - size / 2.0, 0, imageSize - size);
    }

    static int clampDefaultSize(int value) {
        return Math.clamp(value, RoiManager.MIN_SIZE, RoiManager.MAX_SIZE);
    }

    private void resetState() {
        currentObject = null;
        anchorX = 0;
        anchorY = 0;
        dragged = false;
    }

    private void switchToMoveTool() {
        qupath.getToolManager().setSelectedTool(PathTools.MOVE);
    }
}
