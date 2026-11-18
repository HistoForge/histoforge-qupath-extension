package qupath.ext.histoforge.ui.jobs;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import qupath.ext.histoforge.contract.AdminJobCodec.AdminJobStatus;
import qupath.ext.histoforge.contract.AdminJobCodec.JobPhase;
import qupath.ext.histoforge.contract.GatewayErrorCodec.Error;
import qupath.ext.histoforge.contract.TrainingRunTypes.FailedTrainingResult;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunSummary;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModuleContract;

/** Display of existing execution records. It has no wire or persistence format. */
public sealed interface JobRow {

    String id();

    String name();

    String type();

    String status();

    Instant updatedAt();

    boolean isTerminal();

    Optional<Error> failure();

    default boolean isRunning() {
        return !isTerminal();
    }

    default boolean needsAttention() {
        return !isTerminal() && !isRunning();
    }

    default Optional<String> problem() {
        return failure().map(Error::message);
    }

    /** Keep administrative and training identifiers distinct in one observed list. */
    default String key() {
        if (this instanceof Inference inference) {
            return "inference/" + inference.projectId() + "/" + id();
        }
        return (this instanceof Training ? "training/" : "admin/") + id();
    }

    /**
     * One image's live action or recorded targets. Button labels describe the action that is
     * currently available; the existing execution owner revalidates every click.
     */
    record Inference(
            String projectId,
            String actionId,
            String imageId,
            String name,
            String moduleRef,
            String status,
            Instant updatedAt,
            boolean isRunning,
            boolean isTerminal,
            boolean saved,
            String details,
            String reconnectLabel,
            String reconnectUnavailable,
            String stopLabel,
            Optional<String> problem,
            boolean readOnlyHistory)
            implements JobRow {

        public Inference {
            Objects.requireNonNull(projectId);
            Objects.requireNonNull(actionId);
            Objects.requireNonNull(imageId);
            Objects.requireNonNull(name);
            Objects.requireNonNull(moduleRef);
            Objects.requireNonNull(status);
            Objects.requireNonNull(updatedAt);
            Objects.requireNonNull(details);
            Objects.requireNonNull(reconnectLabel);
            Objects.requireNonNull(reconnectUnavailable);
            Objects.requireNonNull(stopLabel);
            Objects.requireNonNull(problem);
            if (isTerminal && isRunning) {
                throw new IllegalArgumentException("saved or stopped analysis cannot be running");
            }
            if (readOnlyHistory && (!reconnectLabel.isEmpty() || !stopLabel.isEmpty()))
                throw new IllegalArgumentException("historical analysis exposes details only");
        }

        public boolean canClearHistory() {
            return isTerminal && !readOnlyHistory;
        }

        public void requireMutable() {
            if (readOnlyHistory)
                throw new IllegalStateException(
                        "Historical analysis is read-only in this client build");
        }

        @Override
        public String id() {
            return actionId + "/" + imageId;
        }

        @Override
        public String type() {
            return "Inference";
        }

        @Override
        public Optional<Error> failure() {
            return Optional.empty();
        }
    }

    record Admin(AdminJobStatus job) implements JobRow {
        public Admin {
            Objects.requireNonNull(job, "job");
        }

        @Override
        public String id() {
            return job.jobId();
        }

        @Override
        public String name() {
            return job.requestedModuleRef().toString();
        }

        @Override
        public String type() {
            return job.type().name().toLowerCase(Locale.ROOT);
        }

        @Override
        public String status() {
            return job.phase() == JobPhase.PARTIAL
                    ? "Partially completed"
                    : job.phase().name().toLowerCase(Locale.ROOT);
        }

        @Override
        public Instant updatedAt() {
            return job.updatedAt();
        }

        @Override
        public boolean isTerminal() {
            return job.isTerminal();
        }

        @Override
        public Optional<Error> failure() {
            return job.displayedFailure();
        }
    }

    record Training(TrainingRunSummary run) implements JobRow {
        public Training {
            Objects.requireNonNull(run, "run");
        }

        @Override
        public String id() {
            return run.runId();
        }

        @Override
        public String name() {
            return run.resultDisplayName();
        }

        @Override
        public String type() {
            return "Train";
        }

        @Override
        public String status() {
            return switch (run.state()) {
                case "QUEUED" -> "Queued";
                case "RUNNING" ->
                        "Epoch "
                                + run.progress().completedEpochs()
                                + " of "
                                + run.progress().totalEpochs();
                case "FINALIZING" -> "Preparing result module";
                case "SUCCEEDED" -> "Complete";
                case "FAILED" -> "Failed";
                case "CANCELLED" -> "Cancelled";
                default -> throw new IllegalArgumentException("Unknown training state");
            };
        }

        @Override
        public Instant updatedAt() {
            return run.updatedAt();
        }

        @Override
        public boolean isTerminal() {
            return run.result().isPresent();
        }

        @Override
        public Optional<Error> failure() {
            return run.result()
                    .filter(FailedTrainingResult.class::isInstance)
                    .map(FailedTrainingResult.class::cast)
                    .map(FailedTrainingResult::error);
        }

        public boolean canCancel() {
            return "QUEUED".equals(run.state()) || "RUNNING".equals(run.state());
        }

        public boolean succeeded() {
            return "SUCCEEDED".equals(run.state());
        }

        /** The latest defined observation, not a recomputed score or a default zero. */
        public String primaryMetric() {
            return run.primaryMetric()
                    .map(
                            metric ->
                                    metric.displayName()
                                            + " "
                                            + BigDecimal.valueOf(metric.value())
                                                    .stripTrailingZeros()
                                                    .toPlainString()
                                            + " (epoch "
                                            + metric.epoch()
                                            + ")")
                    .orElse("");
        }

        /** Only the reserved workspace result belongs to this run, not a later HF copy. */
        public boolean ownsResult(InstalledModule module) {
            return succeeded()
                    && ModuleContract.workspaceTrainingRunId(
                                    module.descriptor().identity().moduleRef())
                            .filter(id()::equals)
                            .isPresent();
        }
    }
}
