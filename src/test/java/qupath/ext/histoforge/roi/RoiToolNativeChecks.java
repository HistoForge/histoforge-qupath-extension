package qupath.ext.histoforge.roi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.geom.Point2D;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.tools.PathTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.RoiEditor;

/** Gesture contracts exercised on JavaFX with the host's real ROI editor. */
public final class RoiToolNativeChecks {
    private RoiToolNativeChecks() {}

    public static void run() {
        int width = RoiToolHandler.defaultWidthProperty.get();
        int height = RoiToolHandler.defaultHeightProperty.get();
        boolean snapping = PathPrefs.usePixelSnappingProperty().get();
        try (var dialogs = mockStatic(Dialogs.class)) {
            RoiToolHandler.defaultWidthProperty.set(1024);
            RoiToolHandler.defaultHeightProperty.set(1024);
            for (boolean snap : new boolean[] {false, true}) {
                PathPrefs.usePixelSnappingProperty().set(snap);
                for (boolean square : new boolean[] {false, true}) {
                    for (int dx : new int[] {-1, 1}) {
                        for (int dy : new int[] {-1, 1}) {
                            var drawing = new Drawing();
                            drawing.press(10000.5, 10000.5, square);
                            assertTrue(drawing.editor.hasActiveHandle());
                            assertEquals(0, drawing.hierarchy.nObjects());
                            drawing.drag(10000.5 + dx * 4000, 10000.5 + dy * 2000, square);
                            var draft = drawing.selected.get();
                            var id = draft.getID();
                            var roi = draft.getROI();
                            assertEquals(
                                    snap ? 10000 : 10000.5,
                                    dx > 0
                                            ? roi.getBoundsX()
                                            : roi.getBoundsX() + roi.getBoundsWidth());
                            assertEquals(
                                    snap ? 10000 : 10000.5,
                                    dy > 0
                                            ? roi.getBoundsY()
                                            : roi.getBoundsY() + roi.getBoundsHeight());
                            if (square) assertEquals(roi.getBoundsWidth(), roi.getBoundsHeight());
                            else {
                                assertEquals(4000, roi.getBoundsWidth());
                                assertEquals(2000, roi.getBoundsHeight());
                            }
                            drawing.release();
                            assertSame(
                                    draft,
                                    drawing.hierarchy.getAnnotationObjects().iterator().next());
                            assertEquals(id, draft.getID());
                            assertSame(roi, draft.getROI());
                            assertEquals(ImagePlane.getPlane(2, 3), roi.getImagePlane());
                            assertTrue(draft.isLocked());
                            assertEquals(RoiManager.HISTOFORGE_ROI_CLASS, draft.getPathClass());
                            assertNull(drawing.editor.getROI());
                            verify(drawing.gui.getToolManager()).setSelectedTool(PathTools.MOVE);
                        }
                    }
                }
            }
            PathPrefs.usePixelSnappingProperty().set(false);
            for (double[] click : new double[][] {{0, 0, 0, 0}, {19999, 19999, 18976, 18976}}) {
                var drawing = new Drawing();
                drawing.press(click[0], click[1], false);
                drawing.release();
                assertEquals(click[2], drawing.selected.get().getROI().getBoundsX());
                assertEquals(click[3], drawing.selected.get().getROI().getBoundsY());
            }
            var jitter = new Drawing();
            jitter.press(10000, 10000, false);
            var initial = jitter.editor.getROI();
            jitter.drag(10000.1, 10000.1, false);
            assertSame(initial, jitter.editor.getROI());
            jitter.drag(10002, 10003, false);
            jitter.release();
            assertEquals(1024, jitter.selected.get().getROI().getBoundsWidth());

            var capped = new Drawing();
            capped.press(10000, 10000, false);
            capped.drag(30000, -5000, false);
            assertEquals(6000, capped.selected.get().getROI().getBoundsWidth());
            assertEquals(6000, capped.selected.get().getROI().getBoundsHeight());
            capped.release();
            verify(capped.viewer).componentPointToImagePoint(30000, -5000, null, true);

            var focused = new Drawing();
            var node = spy(new Pane());
            node.setFocusTraversable(true);
            var press = event(MouseEvent.MOUSE_PRESSED, 5000, 5000, true, false, false);
            press = (MouseEvent) press.copyFor(node, node);
            focused.tool.handle(press);
            verify(node).requestFocus();
            assertTrue(press.isConsumed());
            assertNotNull(focused.selected.get());
            focused.release();

            for (String ignored :
                    new String[] {"consumed", "popup", "secondary", "outside", "no image"}) {
                var drawing = new Drawing();
                if (ignored.equals("no image"))
                    when(drawing.viewer.getHierarchy()).thenReturn(null);
                var e =
                        event(
                                MouseEvent.MOUSE_PRESSED,
                                ignored.equals("outside") ? -1 : 4000,
                                4000,
                                !ignored.equals("secondary"),
                                false,
                                ignored.equals("popup"));
                if (ignored.equals("consumed")) e.consume();
                drawing.tool.handle(e);
                drawing.release();
                assertNull(drawing.selected.get(), ignored);
                assertEquals(0, drawing.hierarchy.nObjects(), ignored);
                if (ignored.equals("popup")) assertTrue(e.isConsumed());
            }

            var interrupted = new Drawing();
            interrupted.press(5000, 5000, false);
            interrupted.drag(7000, 7000, false);
            interrupted.editor.setROI(null);
            interrupted.release();
            assertEquals(0, interrupted.hierarchy.nObjects());
            verify(interrupted.gui.getToolManager(), never()).setSelectedTool(any());

            var overlap = new Drawing();
            overlap.press(5000, 5000, false);
            overlap.release();
            overlap.press(5000, 5000, false);
            overlap.release();
            assertEquals(1, overlap.hierarchy.nObjects());
            assertNull(overlap.selected.get());
            dialogs.verify(() -> Dialogs.showErrorMessage(eq("ROI Overlap"), anyString()));

            var tooSmall = new Drawing();
            tooSmall.press(5000, 5000, false);
            tooSmall.drag(7000, 5100, false);
            tooSmall.release();
            assertEquals(0, tooSmall.hierarchy.nObjects());
            dialogs.verify(() -> Dialogs.showErrorMessage(eq("Invalid ROI"), anyString()));

            var smallImage = new Drawing();
            when(smallImage.viewer.getServerWidth()).thenReturn(512);
            smallImage.press(100, 100, false);
            smallImage.release();
            assertEquals(0, smallImage.hierarchy.nObjects());
            dialogs.verify(
                    () -> Dialogs.showErrorMessage(eq("Default ROI too large"), anyString()));
        } finally {
            RoiToolHandler.defaultWidthProperty.set(width);
            RoiToolHandler.defaultHeightProperty.set(height);
            PathPrefs.usePixelSnappingProperty().set(snapping);
        }
    }

    private static MouseEvent event(
            javafx.event.EventType<MouseEvent> type,
            double x,
            double y,
            boolean down,
            boolean shift,
            boolean popup) {
        return new MouseEvent(
                type,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                1,
                shift,
                false,
                false,
                false,
                down,
                false,
                false,
                false,
                popup,
                true,
                null);
    }

    private static final class Drawing {
        final PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        final RoiEditor editor = RoiEditor.createInstance();
        final AtomicReference<PathObject> selected = new AtomicReference<>();
        final QuPathViewer viewer = mock(QuPathViewer.class);
        final QuPathGUI gui = mock(QuPathGUI.class, RETURNS_DEEP_STUBS);
        final RoiToolHandler tool = new RoiToolHandler(gui, new RoiManager());

        Drawing() {
            when(gui.getViewer()).thenReturn(viewer);
            when(viewer.getHierarchy()).thenReturn(hierarchy);
            when(viewer.getServerWidth()).thenReturn(20000);
            when(viewer.getServerHeight()).thenReturn(20000);
            when(viewer.getImagePlane()).thenReturn(ImagePlane.getPlane(2, 3));
            when(viewer.getROIEditor()).thenReturn(editor);
            when(viewer.getMaxROIHandleSize()).thenReturn(10.0);
            when(viewer.componentPointToImagePoint(
                            anyDouble(), anyDouble(), isNull(), anyBoolean()))
                    .thenAnswer(
                            call -> {
                                double x = call.getArgument(0), y = call.getArgument(1);
                                return new Point2D.Double(
                                        call.getArgument(3) ? Math.clamp(x, 0, 20000) : x,
                                        call.getArgument(3) ? Math.clamp(y, 0, 20000) : y);
                            });
            doAnswer(
                            call -> {
                                selected.set(call.getArgument(0));
                                return null;
                            })
                    .when(viewer)
                    .setSelectedObject(any());
            when(viewer.getCurrentROI())
                    .thenAnswer(call -> selected.get() == null ? null : selected.get().getROI());
        }

        void press(double x, double y, boolean shift) {
            tool.handle(event(MouseEvent.MOUSE_PRESSED, x, y, true, shift, false));
        }

        void drag(double x, double y, boolean shift) {
            tool.handle(event(MouseEvent.MOUSE_DRAGGED, x, y, true, shift, false));
        }

        void release() {
            tool.handle(event(MouseEvent.MOUSE_RELEASED, 0, 0, false, false, false));
        }
    }
}
