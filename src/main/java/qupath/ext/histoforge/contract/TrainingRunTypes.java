package qupath.ext.histoforge.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.BuildIdentityCodec.SoftwareBuildIdentity;
import qupath.ext.histoforge.contract.GatewayErrorCodec.Error;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingReport;
import qupath.ext.histoforge.contract.TrainingRunRequestCodec.TrainingRunRequest;

/** Immutable Java values for the managed-training Jobs and report wire contracts. */
public final class TrainingRunTypes {

    private TrainingRunTypes() {}

    /** Epoch progress shown while a training run is active. */
    public record TrainingProgress(int completedEpochs, int totalEpochs) {}

    /** Candidate identities frozen before one result module becomes visible. */
    public record TrainingFinalization(
            String selectedWeightDigest, String reportDigest, String moduleContentDigest) {}

    /** Terminal result variant whose concrete type must agree with the run state. */
    public sealed interface TrainingRunResult
            permits SucceededTrainingResult, FailedTrainingResult, CancelledTrainingResult {}

    public record SucceededTrainingResult() implements TrainingRunResult {}

    public record FailedTrainingResult(Error error, String reportDigest)
            implements TrainingRunResult {
        public FailedTrainingResult {
            error = Objects.requireNonNull(error, "error");
        }
    }

    public record CancelledTrainingResult(String reportDigest) implements TrainingRunResult {}

    /** Complete public state for one accepted durable training run. */
    public record TrainingRunRecord(
            String runId,
            TrainingRunRequest request,
            String requestDigest,
            SoftwareBuildIdentity trainerBuild,
            String state,
            TrainingProgress progress,
            int reportRevision,
            Instant createdAt,
            Instant updatedAt,
            Optional<TrainingFinalization> finalization,
            Optional<TrainingRunResult> result) {
        public TrainingRunRecord {
            request = Objects.requireNonNull(request, "request");
            trainerBuild = Objects.requireNonNull(trainerBuild, "trainer_build");
            progress = Objects.requireNonNull(progress, "progress");
            createdAt = Objects.requireNonNull(createdAt, "created_at");
            updatedAt = Objects.requireNonNull(updatedAt, "updated_at");
            finalization = Objects.requireNonNull(finalization, "finalization");
            result = Objects.requireNonNull(result, "result");
        }
    }

    /** Latest defined value of the metric used to select validation weights. */
    public record PrimaryMetricObservation(
            String seriesId, String displayName, double value, int epoch) {}

    /** Bounded Jobs-row projection for one training run. */
    public record TrainingRunSummary(
            String runId,
            String resultDisplayName,
            ModuleRef sourceModuleRef,
            String selectedModelId,
            String resultForm,
            String state,
            TrainingProgress progress,
            int reportRevision,
            Optional<PrimaryMetricObservation> primaryMetric,
            Optional<TrainingRunResult> result,
            Instant createdAt,
            Instant updatedAt) {
        public TrainingRunSummary {
            sourceModuleRef = Objects.requireNonNull(sourceModuleRef, "source_module_ref");
            progress = Objects.requireNonNull(progress, "progress");
            primaryMetric = Objects.requireNonNull(primaryMetric, "primary_metric");
            result = Objects.requireNonNull(result, "result");
            createdAt = Objects.requireNonNull(createdAt, "created_at");
            updatedAt = Objects.requireNonNull(updatedAt, "updated_at");
        }
    }

    /** Stable, byte-bounded page of Jobs rows. */
    public record TrainingRunPage(List<TrainingRunSummary> items, Optional<String> nextCursor) {
        public TrainingRunPage {
            items = List.copyOf(items);
            nextCursor = Objects.requireNonNull(nextCursor, "next_cursor");
        }
    }

    /** Durable acceptance echo for one submitted request. */
    public record TrainingRunAccepted(String runId, String requestDigest) {}

    /** Currently advertised immutable report revision for one run. */
    public record TrainingReportResponse(
            int reportRevision, String reportDigest, TrainingReport report) {
        public TrainingReportResponse {
            report = Objects.requireNonNull(report, "report");
        }
    }
}
