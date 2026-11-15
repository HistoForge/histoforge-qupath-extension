package qupath.ext.histoforge.roi;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseEvent;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.util.ConversionUtils;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Context actions capture their own selection and image, rather than sharing mutable targets. */
public class ViewerContextMenuHandler {
    private final QuPathGUI qupath;
    private final RoiManager rois;
    private final Set<QuPathViewer> installed = Collections.newSetFromMap(new WeakHashMap<>());

    public ViewerContextMenuHandler(QuPathGUI qupath, RoiManager rois) {
        this.qupath = qupath;
        this.rois = rois;
        qupath.viewerProperty().addListener((property, previous, current) -> attach(current));
        attach(qupath.getViewer());
    }

    enum SelectionKind {
        NONE,
        ROIS,
        SEGMENTATIONS
    }

    static SelectionKind selectionKind(
            RoiManager rois, PathObject primary, List<PathObject> selected) {
        if (selected.isEmpty()) return SelectionKind.NONE;
        PathObject active = primary == null ? selected.getFirst() : primary;
        if (RoiManager.isHistoforgeRoi(active)
                && selected.stream().allMatch(RoiManager::isHistoforgeRoi))
            return SelectionKind.ROIS;
        if (active.isDetection()
                && rois.findAncestorRoi(active).isPresent()
                && selected.stream().allMatch(object -> rois.findAncestorRoi(object).isPresent()))
            return SelectionKind.SEGMENTATIONS;
        return SelectionKind.NONE;
    }

    private void attach(QuPathViewer viewer) {
        if (viewer == null || !installed.add(viewer)) return;
        viewer.getView()
                .addEventFilter(
                        MouseEvent.MOUSE_PRESSED,
                        event -> {
                            var image = viewer.getImageData();
                            if (!event.isSecondaryButtonDown() || image == null) return;
                            var targets =
                                    List.copyOf(
                                            image.getHierarchy()
                                                    .getSelectionModel()
                                                    .getSelectedObjects());
                            var kind = selectionKind(rois, viewer.getSelectedObject(), targets);
                            ContextMenu menu =
                                    switch (kind) {
                                        case ROIS -> roiMenu(viewer, image, targets);
                                        case SEGMENTATIONS ->
                                                segmentationMenu(viewer, image, targets);
                                        case NONE -> null;
                                    };
                            if (menu != null) {
                                menu.setAutoHide(true);
                                menu.show(
                                        viewer.getView().getScene().getWindow(),
                                        event.getScreenX(),
                                        event.getScreenY());
                                event.consume();
                            }
                        });
    }

    static boolean stillInHierarchy(PathObjectHierarchy hierarchy, List<PathObject> targets) {
        return targets.stream()
                .allMatch(
                        object -> {
                            var ancestor = object;
                            while (ancestor.getParent() != null) ancestor = ancestor.getParent();
                            return ancestor == hierarchy.getRootObject();
                        });
    }

    private boolean requireActiveSelection(
            QuPathViewer viewer, ImageData<?> image, List<PathObject> targets) {
        if (viewer.getImageData() == image && stillInHierarchy(image.getHierarchy(), targets))
            return true;
        Dialogs.showErrorMessage(
                "HistoForge", "The selected image or objects are no longer active.");
        return false;
    }

    private ContextMenu segmentationMenu(
            QuPathViewer viewer, ImageData<BufferedImage> image, List<PathObject> targets) {
        var convert =
                new MenuItem(
                        targets.size() == 1
                                ? "Convert to Annotation (for editing)"
                                : "Convert to Annotations (for editing)");
        convert.setOnAction(
                event -> {
                    if (!requireActiveSelection(viewer, image, targets)) return;
                    var converted = new ArrayList<PathObject>();
                    for (var target : targets) {
                        var annotation =
                                ConversionUtils.convertDetectionToAnnotation(
                                        target, image.getHierarchy());
                        if (annotation != null) converted.add(annotation);
                    }
                    if (!converted.isEmpty())
                        image.getHierarchy()
                                .getSelectionModel()
                                .setSelectedObjects(converted, converted.getFirst());
                });
        var delete = new MenuItem(targets.size() == 1 ? "Delete" : "Delete all");
        delete.setOnAction(
                event -> {
                    if (!requireActiveSelection(viewer, image, targets)) return;
                    long children =
                            targets.stream()
                                    .flatMap(target -> target.getChildObjects().stream())
                                    .filter(child -> !targets.contains(child))
                                    .count();
                    ButtonType choice =
                            children == 0
                                    ? ButtonType.YES
                                    : Dialogs.showYesNoCancelDialog(
                                            "Delete object",
                                            children == 1
                                                    ? "Keep descendant object?"
                                                    : "Keep " + children + " descendant objects?");
                    if (choice == ButtonType.CANCEL
                            || !requireActiveSelection(viewer, image, targets)) return;
                    deleteSegmentations(image.getHierarchy(), targets, choice == ButtonType.YES);
                });
        return new ContextMenu(convert, new SeparatorMenuItem(), delete);
    }

    static void deleteSegmentations(
            PathObjectHierarchy hierarchy, List<PathObject> targets, boolean keepChildren) {
        // The host's batch removal geometrically reparents retained children. Single removal
        // preserves their existing parent chain, which is the contract of this editing action.
        targets.forEach(target -> hierarchy.removeObjectWithoutUpdate(target, keepChildren));
        hierarchy.fireHierarchyChangedEvent(ViewerContextMenuHandler.class);
        hierarchy.getSelectionModel().clearSelection();
    }

    private ContextMenu roiMenu(
            QuPathViewer viewer, ImageData<BufferedImage> image, List<PathObject> targets) {
        var delete =
                new MenuItem(
                        targets.size() == 1 ? "Delete HistoForge ROI" : "Delete HistoForge ROIs");
        delete.setOnAction(
                event -> {
                    String message =
                            targets.size() == 1
                                    ? "Delete this HistoForge ROI and its ROI-scoped model output?\n\nManual objects and slide-scoped output will be kept."
                                    : String.format(
                                            "Delete these %d HistoForge ROIs and their ROI-scoped model output?%n%nManual objects and slide-scoped output will be kept.",
                                            targets.size());
                    if (!Dialogs.showYesNoDialog("Delete HistoForge ROI", message)) return;
                    if (qupath.getImageData() != image
                            || !requireActiveSelection(viewer, image, targets)) {
                        if (qupath.getImageData() != image)
                            Dialogs.showErrorMessage(
                                    "HistoForge", "The selected ROI image is no longer active.");
                        return;
                    }
                    try {
                        rois.deleteRois(image, targets);
                        image.getHierarchy().getSelectionModel().clearSelection();
                    } catch (RuntimeException failure) {
                        LoggerFactory.getLogger(ViewerContextMenuHandler.class)
                                .error("Could not delete HistoForge ROI", failure);
                        String detail =
                                failure.getMessage() == null
                                        ? failure.getClass().getSimpleName()
                                        : failure.getMessage();
                        Dialogs.showErrorMessage(
                                "HistoForge", "Could not delete the selected ROI: " + detail);
                    }
                });
        return new ContextMenu(delete);
    }
}
