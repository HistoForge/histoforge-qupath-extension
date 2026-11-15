package qupath.ext.histoforge.ui.project;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import qupath.ext.histoforge.client.SourceTransferProgress;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.ModelRunStatusCodec;
import qupath.ext.histoforge.large.HistoricalActionRecord;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.large.LargeActionTypes.ModelRunRef;
import qupath.ext.histoforge.pipeline.BatchOrchestrator.BatchPhase;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.ui.jobs.JobRow;

/** Pure presentation of local action/save records and independently observed cloud progress. */
final class InferenceJobRows {

    private InferenceJobRows() {}

    /** Process-local progress only; an unstarted batch queue is deliberately not persisted. */
    record Live(
            String actionId,
            String imageId,
            String imageName,
            String moduleRef,
            BatchPhase phase,
            Optional<RunResults.ImageRunResult> result,
            Optional<String> problem,
            Instant updatedAt,
            boolean recovery,
            Optional<SourceTransferProgress> sourceProgress) {
        Live(
                String actionId,
                String imageId,
                String imageName,
                String moduleRef,
                BatchPhase phase,
                Optional<RunResults.ImageRunResult> result,
                Optional<String> problem,
                Instant updatedAt,
                boolean recovery) {
            this(
                    actionId,
                    imageId,
                    imageName,
                    moduleRef,
                    phase,
                    result,
                    problem,
                    updatedAt,
                    recovery,
                    Optional.empty());
        }

        Live withSourceProgress(Optional<SourceTransferProgress> transfer) {
            if (phase != BatchPhase.RUNNING) return this;
            return new Live(
                    actionId, imageId, imageName, moduleRef, phase, result, problem, updatedAt,
                    recovery, transfer);
        }
    }

    record Cloud(
            Optional<ModelRunStatusCodec.Status> status,
            Optional<String> problem,
            boolean stopConfirmed) {
        Cloud(Optional<ModelRunStatusCodec.Status> status, Optional<String> problem) {
            this(status, problem, false);
        }

        boolean running() {
            return problem.isEmpty() && status.filter(InferenceJobRows::running).isPresent();
        }

        boolean ended() {
            return problem.isEmpty()
                    && (stopConfirmed
                            || status.filter(s -> !InferenceJobRows.running(s)).isPresent());
        }
    }

    static String imageKey(String actionId, String imageId) {
        return actionId + "/" + imageId;
    }

    /** A local failure/cancellation does not prove its independently running cloud work ended. */
    static boolean needsCloudObservation(LargeActionHandle handle) {
        // RESULT_READY has complete model work; only its local save outcome remains uncertain.
        return !handle.phase().hasCompletedSave()
                && handle.phase() != LargeActionPhase.RESULT_READY;
    }

    /** An immutable COMPLETE manifest proves the run ended, even if its local bytes were lost. */
    static boolean needsCloudObservation(ModelRunRef run) {
        return run.completeResultFiles().isEmpty();
    }

    static JobRow.Inference row(
            String projectId,
            String imageName,
            List<LargeActionHandle> targets,
            Optional<Live> live,
            Map<String, Cloud> cloud,
            String reconnectUnavailable) {
        return row(projectId, imageName, targets, live, cloud, Set.of(), reconnectUnavailable);
    }

    static JobRow.Inference row(
            String projectId,
            String imageName,
            List<LargeActionHandle> targets,
            Optional<Live> live,
            Map<String, Cloud> cloud,
            Set<String> locallyAvailableRunIds,
            String reconnectUnavailable) {
        LargeActionHandle first = targets.isEmpty() ? null : targets.getFirst();
        Live current = live.orElse(null);
        String actionId = current != null ? current.actionId() : first.actionId();
        String imageId = current != null ? current.imageId() : first.imageId();
        String module =
                current != null
                        ? current.moduleRef()
                        : first.module().module().moduleRef().toString();
        List<LargeActionHandle> unfinished =
                targets.stream().filter(t -> !t.phase().terminal()).toList();
        List<Cloud> observed =
                targets.stream()
                        .filter(InferenceJobRows::needsCloudObservation)
                        .flatMap(t -> t.steps().stream())
                        .flatMap(s -> s.runs().stream())
                        .filter(InferenceJobRows::needsCloudObservation)
                        .filter(r -> !locallyAvailableRunIds.contains(r.modelRunId()))
                        .map(r -> cloud.get(r.modelRunId()))
                        .filter(java.util.Objects::nonNull)
                        .toList();
        boolean localRunning =
                current != null
                        && (current.phase() == BatchPhase.RUNNING
                                || current.phase() == BatchPhase.QUEUED);
        boolean localSuspended = current != null && current.phase() == BatchPhase.SUSPENDED;
        boolean cloudRunning = observed.stream().anyMatch(Cloud::running);
        boolean cloudStopUnconfirmed =
                targets.stream()
                        .filter(t -> t.phase().terminal() && needsCloudObservation(t))
                        .flatMap(t -> t.steps().stream())
                        .flatMap(s -> s.runs().stream())
                        .filter(InferenceJobRows::needsCloudObservation)
                        .filter(r -> !locallyAvailableRunIds.contains(r.modelRunId()))
                        .anyMatch(
                                r ->
                                        cloud.get(r.modelRunId()) == null
                                                || !cloud.get(r.modelRunId()).ended());
        boolean terminal =
                !localRunning && !localSuspended && unfinished.isEmpty() && !cloudStopUnconfirmed;
        String problem =
                Stream.concat(
                                Stream.concat(
                                        targets.stream()
                                                .flatMap(t -> t.failure().stream())
                                                .map(f -> f.type() + ": " + f.message()),
                                        observed.stream()
                                                .flatMap(
                                                        c ->
                                                                Stream.concat(
                                                                        c.problem().stream(),
                                                                        c.status().stream()
                                                                                .flatMap(
                                                                                        s ->
                                                                                                s
                                                                                                        .failure()
                                                                                                        .stream())
                                                                                .map(
                                                                                        e ->
                                                                                                e
                                                                                                                .code()
                                                                                                        + ": "
                                                                                                        + e
                                                                                                                .message())))),
                                current == null
                                        ? Stream.empty()
                                        : Stream.concat(
                                                current.problem().stream(),
                                                current.result().stream()
                                                        .flatMap(InferenceJobRows::resultProblems)))
                        .distinct()
                        .collect(Collectors.joining("\n"));
        boolean saved =
                targets.stream().anyMatch(t -> t.phase() == LargeActionPhase.COMMITTED)
                        || current != null
                                && current.result()
                                        .filter(RunResults.ImageRunResult::hierarchySaved)
                                        .isPresent();
        String status;
        if (localRunning) {
            status =
                    current.phase() == BatchPhase.QUEUED
                            ? "Queued"
                            : liveStage(unfinished, observed, current.sourceProgress());
        } else if (cloudStopUnconfirmed && unfinished.isEmpty()) {
            status =
                    cloudRunning
                            ? "Local work ended — cloud still running"
                            : "Cloud status not confirmed";
        } else if (!terminal) {
            status =
                    cloudRunning
                            ? "Running in cloud"
                            : unfinished.stream()
                                            .anyMatch(
                                                    t -> t.phase() == LargeActionPhase.RESULT_READY)
                                    ? "Save needs verification"
                                    : !problem.isEmpty() || localSuspended
                                            ? "Needs attention"
                                            : !unfinished.isEmpty()
                                                            && unfinished.stream()
                                                                    .allMatch(
                                                                            t ->
                                                                                    allModelResultsLocal(
                                                                                            t,
                                                                                            locallyAvailableRunIds))
                                                    ? "Results available locally"
                                                    : "Ready to continue";
        } else if (current != null && current.result().isPresent()) {
            var result = current.result().orElseThrow();
            status =
                    saved && result.metrics().filter(m -> !m.regionFailures().isEmpty()).isPresent()
                            ? "Results saved — Metrics failed"
                            : saved && !result.hierarchySaved()
                                    ? "Some results saved — "
                                            + ProjectRowStatus.latest(Optional.of(result))
                                    : ProjectRowStatus.latest(Optional.of(result));
        } else {
            status =
                    !problem.isEmpty()
                            ? saved ? "Some results saved — failed" : "Failed"
                            : targets.stream()
                                            .anyMatch(t -> t.phase() == LargeActionPhase.CANCELLED)
                                    ? "Stopped"
                                    : saved ? "Results saved" : "Saved results superseded";
        }
        String reconnect =
                terminal || localRunning || unfinished.isEmpty() && !localSuspended
                        ? ""
                        : cloudRunning ? "Reconnect to analysis" : "Continue analysis";
        String stop =
                terminal
                        ? ""
                        : cloudStopUnconfirmed && !localRunning && !localSuspended
                                ? "Retry cloud cancellation"
                                : current != null
                                                && !current.recovery()
                                                && (localRunning || localSuspended)
                                        ? "Cancel batch"
                                        : "Stop analysis";
        Instant updated =
                current != null
                        ? current.updatedAt()
                        : targets.stream()
                                .map(LargeActionHandle::updatedAt)
                                .max(Instant::compareTo)
                                .orElseThrow();
        StringBuilder details =
                new StringBuilder("Image: ")
                        .append(imageName)
                        .append("\nModule: ")
                        .append(module)
                        .append("\nStatus: ")
                        .append(status);
        if (localRunning && current.sourceProgress().isPresent()) {
            var transfer = current.sourceProgress().orElseThrow();
            if (transfer.totalBytes() > 0) {
                details.append("\nUpload acknowledged: ")
                        .append(uploadSize(transfer.acknowledgedBytes()))
                        .append(" / ")
                        .append(uploadSize(transfer.totalBytes()));
            }
        }
        for (LargeActionHandle target : targets) {
            details.append("\n\n")
                    .append(targetName(target))
                    .append(": ")
                    .append(targetPhase(target.phase(), localRunning));
            for (var step : target.steps()) {
                for (var run : step.runs()) {
                    Cloud observation = cloud.get(run.modelRunId());
                    details.append("\n  Model ").append(step.stepId());
                    if (locallyAvailableRunIds.contains(run.modelRunId())) {
                        details.append(": results available locally");
                    } else if (!needsCloudObservation(run)) {
                        details.append(": complete; local result files unavailable");
                    } else if (observation != null && observation.status().isPresent()) {
                        var value = observation.status().orElseThrow();
                        details.append(": ")
                                .append(value.completedUnitCount())
                                .append('/')
                                .append(value.totalUnitCount())
                                .append(" processing regions · ")
                                .append(value.phase().name().toLowerCase(java.util.Locale.ROOT));
                        details.append(
                                        running(value)
                                                ? "\n  Run deadline (UTC): "
                                                : "\n  Cloud files expire (UTC): ")
                                .append(value.phaseDeadline());
                    }
                    details.append("\n  Cloud run: ").append(run.modelRunId());
                }
            }
        }
        if (!reconnectUnavailable.isBlank() && !terminal)
            details.append("\n\nCannot continue: ").append(reconnectUnavailable);
        if (!problem.isBlank()) details.append("\n\n").append(problem);
        if (!terminal)
            details.append(
                    "\n\nReopening QuPath restores started recoverable targets only, not the remaining batch queue. Uploaded images expire separately from completed results.");
        return new JobRow.Inference(
                projectId,
                actionId,
                imageId,
                imageName,
                module,
                status,
                updated,
                localRunning || cloudRunning && !terminal,
                terminal,
                saved,
                details.toString(),
                reconnect,
                reconnectUnavailable,
                stop,
                problem.isEmpty() ? Optional.empty() : Optional.of(problem),
                false);
    }

    /**
     * Historical local phases are displayed literally; this build never observes their cloud work.
     */
    static JobRow.Inference historicalRow(
            String projectId,
            String imageName,
            List<HistoricalActionRecord> records,
            Optional<JobRow.Inference> current) {
        var first = records.getFirst();
        boolean terminal =
                records.stream().allMatch(record -> record.phase().terminal())
                        && current.map(JobRow.Inference::isTerminal).orElse(true);
        boolean saved =
                records.stream().anyMatch(record -> record.phase() == LargeActionPhase.COMMITTED)
                        || current.map(JobRow.Inference::saved).orElse(false);
        String status =
                !terminal
                        ? "Historical analysis — original build required"
                        : saved
                                ? "Results saved — historical"
                                : records.stream().anyMatch(record -> record.failure().isPresent())
                                        ? "Failed — historical"
                                        : records.stream()
                                                        .anyMatch(
                                                                record ->
                                                                        record.phase()
                                                                                == LargeActionPhase
                                                                                        .CANCELLED)
                                                ? "Locally stopped — historical"
                                                : "Saved results superseded — historical";
        StringBuilder details =
                new StringBuilder("Image: ")
                        .append(imageName)
                        .append("\nModule: ")
                        .append(first.moduleRef())
                        .append("\nAction: ")
                        .append(first.actionId())
                        .append("\nStatus: ")
                        .append(status)
                        .append(
                                "\n\nRecorded version-2 history. This build does not resume, cancel, or clear these records. Cloud state is not observed.");
        for (var record : records) {
            details.append("\n\n")
                    .append(targetName(record.target()))
                    .append(": ")
                    .append(targetPhase(record.phase(), false))
                    .append("\n  Handle: ")
                    .append(record.handleGenerationId())
                    .append("\n  Client build: ")
                    .append(record.clientBuild().extension().artifactDigest())
                    .append("\n  Recorded update: ")
                    .append(record.updatedAt());
            for (var run : record.runs())
                details.append("\n  Model ")
                        .append(run.stepId())
                        .append(" — run ")
                        .append(run.modelRunId());
            record.failure()
                    .ifPresent(
                            failure ->
                                    details.append("\n  ")
                                            .append(failure.type())
                                            .append(": ")
                                            .append(failure.message()));
        }
        current.ifPresent(row -> details.append("\n\nCurrent records:\n").append(row.details()));
        if (!terminal)
            details.append(
                    "\n\nUnfinished work requires its original client build. Saved QuPath results remain readable.");
        Instant updated =
                records.stream()
                        .map(HistoricalActionRecord::updatedAt)
                        .max(Instant::compareTo)
                        .orElseThrow();
        if (current.isPresent() && current.orElseThrow().updatedAt().isAfter(updated))
            updated = current.orElseThrow().updatedAt();
        return new JobRow.Inference(
                projectId,
                first.actionId(),
                first.imageId(),
                imageName,
                first.moduleRef().toString(),
                status,
                updated,
                false,
                terminal,
                saved,
                details.toString(),
                "",
                "",
                "",
                Optional.empty(),
                true);
    }

    private static boolean allModelResultsLocal(LargeActionHandle handle, Set<String> available) {
        var recordedSteps =
                handle.steps().stream().map(s -> s.stepId()).collect(Collectors.toSet());
        return recordedSteps.equals(handle.module().activatedModels().keySet())
                && handle.steps().stream()
                        .flatMap(s -> s.runs().stream())
                        .allMatch(r -> available.contains(r.modelRunId()));
    }

    static boolean running(ModelRunStatusCodec.Status status) {
        return status.phase() == ModelRunStatusCodec.Phase.RUNNING
                || status.phase() == ModelRunStatusCodec.Phase.QUEUED;
    }

    private static String liveStage(
            List<LargeActionHandle> targets,
            List<Cloud> cloud,
            Optional<SourceTransferProgress> progress) {
        if (progress.isPresent()) {
            var transfer = progress.orElseThrow();
            switch (transfer.phase()) {
                case PREPARING:
                    return "Preparing image";
                case UPLOADING:
                    long percent =
                            transfer.acknowledgedBytes() == transfer.totalBytes()
                                    ? 100
                                    : Math.min(
                                            99,
                                            (long)
                                                    (100.0
                                                            * transfer.acknowledgedBytes()
                                                            / transfer.totalBytes()));
                    return "Uploading "
                            + percent
                            + "% · "
                            + uploadSize(transfer.acknowledgedBytes())
                            + " / "
                            + uploadSize(transfer.totalBytes());
                case VERIFYING:
                    return "Verifying upload";
                case READY:
                    break;
            }
        }
        if (progress.isEmpty()) {
            if (targets.stream().anyMatch(t -> t.phase() == LargeActionPhase.PREPARING))
                return "Preparing image";
            if (targets.stream().anyMatch(t -> t.phase() == LargeActionPhase.UPLOADING))
                return "Uploading image";
        }
        if (targets.stream().anyMatch(t -> t.phase() == LargeActionPhase.RESULT_READY))
            return "Saving results";
        return cloud.stream()
                .filter(Cloud::running)
                .map(c -> c.status().orElseThrow())
                .findFirst()
                .map(
                        s ->
                                "Running inference · "
                                        + s.completedUnitCount()
                                        + "/"
                                        + s.totalUnitCount()
                                        + " regions")
                .orElse(progress.isPresent() ? "Running inference" : "Processing analysis");
    }

    private static String uploadSize(long bytes) {
        if (bytes >= 1_000_000_000L)
            return String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000L) return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000L) return String.format(Locale.ROOT, "%.1f kB", bytes / 1_000.0);
        return bytes + " bytes";
    }

    private static String targetName(LargeActionHandle handle) {
        return targetName(handle.target());
    }

    private static String targetName(ExecutionScopeCodec.TargetRegion target) {
        return target.key() instanceof ExecutionScopeCodec.Roi roi
                ? "ROI " + roi.roiId()
                : "Whole slide";
    }

    private static String targetPhase(LargeActionPhase phase, boolean active) {
        return switch (phase) {
            case PREPARING -> active ? "preparing image" : "preparation interrupted";
            case UPLOADING -> active ? "uploading image" : "upload incomplete";
            case EXECUTING -> "analysis not yet saved";
            case RESULT_READY -> active ? "saving results" : "save needs verification";
            case COMMITTED -> "results saved";
            case SUPERSEDED -> "saved results superseded";
            case FAILED -> "failed";
            case CANCELLED -> "stopped";
            case CONFLICT -> "image changed; analysis cannot be applied";
        };
    }

    private static Stream<String> resultProblems(RunResults.ImageRunResult result) {
        return Stream.concat(
                        Stream.concat(
                                result.imageFailures().stream(),
                                result.scopes().stream().flatMap(s -> s.failure().stream())),
                        result.metrics().stream()
                                .flatMap(m -> m.regionFailures().values().stream()))
                .map(f -> f.type() + ": " + f.message());
    }
}
