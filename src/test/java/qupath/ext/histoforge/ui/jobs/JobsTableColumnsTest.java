package qupath.ext.histoforge.ui.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.AdminJobCodec.AdminJobStatus;
import qupath.ext.histoforge.contract.AdminJobCodec.JobPhase;
import qupath.ext.histoforge.contract.AdminJobCodec.JobType;

/** Verifies that publication navigation survives active and failed transient outcomes. */
class JobsTableColumnsTest {

    @Test
    void publicationTargetAlwaysOwnsTheHuggingFaceLink() {
        assertEquals(
                "https://huggingface.co/owner/target",
                JobsTableColumns.hfUrl(new JobRow.Admin(status(JobType.PUBLISH))));
        assertNull(JobsTableColumns.hfUrl(new JobRow.Admin(status(JobType.INSTALL))));
    }

    @Test
    void updatedTimestampIncludesTheLocalCalendarDateForBothJobTypes() throws Exception {
        List<JobRow> rows =
                List.of(
                        new JobRow.Admin(status(JobType.INSTALL)),
                        TrainingJobsFixtures.row("RUNNING"));
        for (JobRow row : rows) {
            String updated = JobsTableColumns.updated(row);

            assertEquals(19, updated.length());
            assertEquals(
                    row.updatedAt().atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                    updated.substring(0, 10));
            assertEquals(' ', updated.charAt(10));
            assertEquals(':', updated.charAt(13));
            assertEquals(':', updated.charAt(16));
        }
    }

    private static AdminJobStatus status(JobType type) {
        return new AdminJobStatus(
                "11111111-1111-4111-8111-111111111111",
                type,
                new ModuleRef("owner", "target"),
                JobPhase.QUEUED,
                Instant.parse("2026-08-15T01:02:03.004Z"),
                Optional.empty(),
                Optional.empty());
    }
}
