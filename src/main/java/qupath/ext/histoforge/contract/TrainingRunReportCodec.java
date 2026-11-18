package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingReportTypes.BestValidationSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.FinalWeightsSelection;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingEpochMetricRecord;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingModelSelection;
import qupath.ext.histoforge.contract.TrainingRunTypes.CancelledTrainingResult;
import qupath.ext.histoforge.contract.TrainingRunTypes.FailedTrainingResult;
import qupath.ext.histoforge.contract.TrainingRunTypes.SucceededTrainingResult;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingReportResponse;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunRecord;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunResult;

/** Closed report-response wrapper and advertised-run binding for managed training. */
public final class TrainingRunReportCodec {

    private static final Set<String> RESPONSE_FIELDS =
            Set.of("report_revision", "report_digest", "report");

    private TrainingRunReportCodec() {}

    /** Parse and digest-check one immutable report response. */
    public static TrainingReportResponse parse(JsonElement value, TrainingApiLimits limits) {
        Objects.requireNonNull(limits, "training API limits");
        JsonObject object =
                TrainingJson.exactObject(value, RESPONSE_FIELDS, "training report response");
        var report = TrainingReportCodec.parseReport(object.get("report"), limits.report());
        String reportDigest =
                CanonicalCodec.requireSha256(
                        TrainingJson.string(object, "report_digest"), "report_digest");
        if (!reportDigest.equals(
                CanonicalCodec.canonicalDigest(
                        TrainingReportCodec.toJson(report, limits.report())))) {
            throw new IllegalArgumentException("report_digest does not match report");
        }
        return new TrainingReportResponse(
                CanonicalCodec.requirePositiveInt32(
                        object.get("report_revision"), "report_revision"),
                reportDigest,
                report);
    }

    /** Bind a fetched report response to the run record that advertised it. */
    public static TrainingReportResponse validateForRun(
            TrainingRunRecord run, JsonElement value, TrainingApiLimits limits) {
        Objects.requireNonNull(run, "training run");
        TrainingReportResponse response = parse(value, limits);
        if (!reportContentMatchesRun(run, response)
                || response.reportRevision() < run.reportRevision()) {
            throw new IllegalArgumentException(
                    "training report response does not match advertised run state");
        }
        boolean sameRevision = response.reportRevision() == run.reportRevision();
        if (sameRevision) {
            requireSnapshotBinding(run, response);
        } else {
            requireMonotonicAdvancement(run, response);
        }
        Optional<TrainingModelSelection> selection = response.report().modelSelection();
        if (sameRevision && run.finalization().isEmpty() && selection.isPresent()) {
            throw new IllegalArgumentException(
                    "training report selects weights before finalization");
        }
        if (run.finalization().isPresent()
                && (selection.isEmpty()
                        || !selectedWeightDigest(selection.orElseThrow())
                                .equals(run.finalization().orElseThrow().selectedWeightDigest()))) {
            throw new IllegalArgumentException(
                    "training report selection does not match finalization");
        }
        if ("SUCCEEDED".equals(response.report().state())
                && run.finalization().isPresent()
                && !run.finalization()
                        .orElseThrow()
                        .reportDigest()
                        .equals(response.reportDigest())) {
            throw new IllegalArgumentException(
                    "successful training report digest does not match finalization");
        }
        return response;
    }

    private static void requireSnapshotBinding(
            TrainingRunRecord run, TrainingReportResponse response) {
        if (!reportStateMatchesRun(run.state(), response.report().state())
                || lastEpoch(response) != run.progress().completedEpochs()) {
            throw new IllegalArgumentException(
                    "training report response does not match advertised run state");
        }
        if (run.result().isPresent()
                && !(run.result().orElseThrow() instanceof SucceededTrainingResult)
                && !terminalReportDigest(run.result().orElseThrow())
                        .equals(response.reportDigest())) {
            throw new IllegalArgumentException(
                    "terminal training report digest does not match run result");
        }
        if (run.result().orElse(null) instanceof FailedTrainingResult failed
                && !response.report().failure().equals(Optional.of(failed.error()))) {
            throw new IllegalArgumentException(
                    "failed training report error does not match run result");
        }
    }

    private static void requireMonotonicAdvancement(
            TrainingRunRecord run, TrainingReportResponse response) {
        int completed = lastEpoch(response);
        if (!canAdvance(run.state(), response.report().state())
                || completed < run.progress().completedEpochs()
                || completed > run.progress().totalEpochs()) {
            throw new IllegalArgumentException(
                    "training report response does not monotonically advance the run");
        }
    }

    private static boolean canAdvance(String from, String to) {
        return switch (from) {
            case "QUEUED" ->
                    Set.of("QUEUED", "RUNNING", "FINALIZING", "SUCCEEDED", "FAILED", "CANCELLED")
                            .contains(to);
            case "RUNNING" ->
                    Set.of("RUNNING", "FINALIZING", "SUCCEEDED", "FAILED", "CANCELLED")
                            .contains(to);
            case "FINALIZING" -> Set.of("FINALIZING", "SUCCEEDED", "FAILED").contains(to);
            default -> false;
        };
    }

    public static JsonObject toJson(TrainingReportResponse value, TrainingApiLimits limits) {
        return encode(parse(encode(value, limits), limits), limits);
    }

    private static boolean reportStateMatchesRun(String runState, String reportState) {
        return switch (runState) {
            case "QUEUED" -> "QUEUED".equals(reportState);
            case "RUNNING" -> Set.of("QUEUED", "RUNNING").contains(reportState);
            default -> runState.equals(reportState);
        };
    }

    private static boolean reportContentMatchesRun(
            TrainingRunRecord run, TrainingReportResponse response) {
        var request = run.request();
        var report = response.report();
        return report.runId().equals(run.runId())
                && report.datasetDigest().equals(request.datasetDigest())
                && report.splitPlan().equals(request.splitPlan())
                && report.sourceModule().equals(request.sourceModule())
                && report.selectedModelId().equals(request.selectedModelId())
                && report.classInitialization() == request.classInitialization()
                && report.resultForm().equals(request.resultForm())
                && report.resultDisplayName().equals(request.resultDisplayName())
                && report.resultInferenceGpu().equals(request.resultInferenceGpu())
                && report.trainerBuild().equals(run.trainerBuild())
                && report.trainingSeed() == request.trainingSeed()
                && report.trainerSettings().equals(request.trainerSettings())
                && report.totalEpochs() == run.progress().totalEpochs()
                && report.fieldProfile().equals(request.fieldProfile())
                && Objects.equals(report.minInstancePixels(), request.minInstancePixels())
                && report.augmentation().equals(request.augmentation());
    }

    private static int lastEpoch(TrainingReportResponse response) {
        List<TrainingEpochMetricRecord> epochs = response.report().epochs();
        return epochs.isEmpty() ? 0 : epochs.getLast().epoch();
    }

    private static String terminalReportDigest(TrainingRunResult result) {
        if (result instanceof FailedTrainingResult failed) {
            return failed.reportDigest();
        }
        if (result instanceof CancelledTrainingResult cancelled) {
            return cancelled.reportDigest();
        }
        throw new IllegalArgumentException("successful result digest belongs to finalization");
    }

    private static String selectedWeightDigest(TrainingModelSelection selection) {
        if (selection instanceof BestValidationSelection best) {
            return best.selectedWeightDigest();
        }
        if (selection instanceof FinalWeightsSelection last) {
            return last.selectedWeightDigest();
        }
        throw new IllegalArgumentException("unsupported training model selection");
    }

    private static JsonObject encode(TrainingReportResponse value, TrainingApiLimits limits) {
        Objects.requireNonNull(value, "training report response");
        JsonObject result = new JsonObject();
        result.addProperty("report_revision", value.reportRevision());
        result.addProperty("report_digest", value.reportDigest());
        result.add("report", TrainingReportCodec.toJson(value.report(), limits.report()));
        return result;
    }
}
