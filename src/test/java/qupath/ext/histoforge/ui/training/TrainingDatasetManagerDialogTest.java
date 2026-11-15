package qupath.ext.histoforge.ui.training;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetCleanupRecord;

class TrainingDatasetManagerDialogTest {

    @Test
    void cleanupMessageShowsThePersistedFailureOrTheFixedRetryExplanation() {
        var error =
                new GatewayErrorCodec.Error(
                        1,
                        GatewayErrorCodec.Code.INTERNAL_ERROR,
                        "volume deletion failed",
                        Optional.empty(),
                        Optional.empty());

        assertEquals(
                "Last known stored data: 1.00 KiB. Cleanup failed: volume deletion failed Retry cleanup.",
                TrainingDatasetManagerDialog.cleanupMessage(cleanup(Optional.of(error))));
        assertEquals(
                "Last known stored data: 1.00 KiB. Cleanup did not complete. Retry cleanup.",
                TrainingDatasetManagerDialog.cleanupMessage(cleanup(Optional.empty())));
        assertEquals(
                "Last known: 1.00 KiB",
                TrainingDatasetManagerDialog.storageText(cleanup(Optional.empty())));
    }

    private static TrainingDatasetCleanupRecord cleanup(Optional<GatewayErrorCodec.Error> error) {
        return new TrainingDatasetCleanupRecord(
                "11111111-1111-4111-8111-111111111111",
                "Leydig nuclei",
                2,
                4,
                1024,
                Instant.parse("2026-09-03T12:00:00Z"),
                error);
    }
}
