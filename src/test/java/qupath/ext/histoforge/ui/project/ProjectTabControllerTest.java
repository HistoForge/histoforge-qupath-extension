package qupath.ext.histoforge.ui.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ProjectTabControllerTest {

    @Test
    void actionAvailabilityRequiresSelectionAndNoActiveOperation() {
        assertEquals(
                new ProjectTabController.ActionAvailability(true, false),
                ProjectTabController.actionAvailability(false, false, false, false));
        assertEquals(
                new ProjectTabController.ActionAvailability(true, true),
                ProjectTabController.actionAvailability(false, false, false, true));
        assertEquals(
                new ProjectTabController.ActionAvailability(false, false),
                ProjectTabController.actionAvailability(true, false, false, true));
        assertEquals(
                new ProjectTabController.ActionAvailability(false, false),
                ProjectTabController.actionAvailability(false, true, false, true));
        assertEquals(
                new ProjectTabController.ActionAvailability(false, false),
                ProjectTabController.actionAvailability(false, false, true, true));
    }

    @Test
    void regionModeChangeHidesOldMetricsBeforeStartingTheNewLoad() {
        ProjectTabController controller = spy(new ProjectTabController());
        doNothing().when(controller).invalidateMetricPresentation();
        doNothing().when(controller).loadProjectData();

        controller.onRegionModeChanged();

        InOrder order = inOrder(controller);
        order.verify(controller).invalidateMetricPresentation();
        order.verify(controller).loadProjectData();
    }
}
