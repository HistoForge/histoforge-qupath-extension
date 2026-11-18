package qupath.ext.histoforge.roi;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Finalized edit notification without event-driven biological hierarchy mutation. */
class HierarchyListenerTest {

    @Test
    void ordinaryFinalizedEditInvalidatesPresentationWithoutReparenting() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        PathObject enclosing = detection(0, 0, 100, 100);
        PathObject child = detection(10, 10, 5, 5);
        hierarchy.getRootObject().addChildObjects(List.of(enclosing, child));
        RoiManager manager = new RoiManager();
        AtomicInteger notifications = new AtomicInteger();
        HierarchyListener listener = new HierarchyListener(manager, () -> null);
        listener.setOnHierarchyChanged(notifications::incrementAndGet);
        hierarchy.addListener(listener);

        hierarchy.fireObjectsChangedEvent(this, List.of(child));

        assertSame(hierarchy.getRootObject(), child.getParent());
        org.junit.jupiter.api.Assertions.assertEquals(1, notifications.get());
    }

    private static PathObject detection(double x, double y, double width, double height) {
        return PathObjects.createDetectionObject(
                ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane()));
    }
}
