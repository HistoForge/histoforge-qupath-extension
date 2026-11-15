package qupath.ext.histoforge.ui.jobs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JobsTabControllerTest {

    @Test
    void trainingHistoryConfirmationNamesTheExactRunAndPreservedArtifacts() throws Exception {
        JobRow.Training row = TrainingJobsFixtures.row("CANCELLED");

        String message = JobsTabController.trainingClearConfirmation(row);

        assertTrue(message.contains(row.name()));
        assertTrue(message.contains(row.id()));
        assertTrue(message.contains("removes the Jobs entry and its report copy"));
        assertTrue(message.contains("does not delete uploaded training data"));
        assertTrue(message.contains("completed result modules, including their reports"));
    }

    @Test
    void refusingTrainingHistoryConfirmationStopsBeforeDeletion() throws Exception {
        JobRow.Training row = TrainingJobsFixtures.row("CANCELLED");
        AtomicInteger confirmations = new AtomicInteger();
        AtomicReference<String> title = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        var controller =
                new JobsTabController(
                        (requestedTitle, requestedMessage) -> {
                            confirmations.incrementAndGet();
                            title.set(requestedTitle);
                            message.set(requestedMessage);
                            return false;
                        });

        assertDoesNotThrow(() -> controller.clear(row));

        assertEquals(1, confirmations.get());
        assertEquals("Clear training history", title.get());
        assertTrue(message.get().contains(row.id()));
    }
}
