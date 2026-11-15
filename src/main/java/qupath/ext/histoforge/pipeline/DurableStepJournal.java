package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes;
import qupath.ext.histoforge.large.RecoveryResultFiles;
import qupath.ext.histoforge.large.RunImageSources;
import qupath.ext.histoforge.spatial.SourceInputPlan.IncrementalSourcePlan;

/**
 * Saves requests, uploaded-image references and completed results in an action's recovery record.
 */
final class DurableStepJournal {

    private final LargeActionHandleStore handles;
    private final Clock clock;
    private LargeActionHandleStore.ResumableAction current;

    DurableStepJournal(
            LargeActionHandleStore.ResumableAction action,
            LargeActionHandleStore handles,
            Clock clock) {
        this.current = Objects.requireNonNull(action, "action");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    LargeActionHandleStore.ResumableAction currentAction() {
        return current;
    }

    RecoveryResultFiles resultFiles() {
        return handles.resultFiles();
    }

    LargeActionTypes.ModelRunRef run(String stepId, int shardIndex) {
        return current.handle()
                .steps()
                .get(stepPosition(current.handle().steps(), stepId))
                .runs()
                .get(shardIndex);
    }

    /** The COMPLETE manifest is durable before downloading its potentially large feature files. */
    void recordCompleteFiles(
            String stepId,
            int shardIndex,
            String digest,
            LargeActionTypes.CompleteResultFiles files)
            throws IOException {
        var run = run(stepId, shardIndex);
        var next =
                new LargeActionTypes.ModelRunRef(
                        run.shardIndex(),
                        run.requestFile(),
                        run.modelRunId(),
                        Optional.of(digest),
                        Optional.of(files),
                        run.imageSources());
        saveRun(stepId, shardIndex, next);
    }

    /** Publish upload intent, sealing or binding confirmation before the next external effect. */
    void recordImages(String stepId, int shardIndex, RunImageSources images) throws IOException {
        var run = run(stepId, shardIndex);
        var next =
                new LargeActionTypes.ModelRunRef(
                        run.shardIndex(),
                        run.requestFile(),
                        run.modelRunId(),
                        run.resultManifestDigest(),
                        run.completeResultFiles(),
                        Optional.of(images));
        saveRun(stepId, shardIndex, next);
    }

    LargeActionHandleStore handles() {
        return handles;
    }

    /** Replace one unit's upload while preserving the other units and any pending failure. */
    void recordImage(String stepId, int shardIndex, RunImageSources.Upload upload)
            throws IOException {
        var images = run(stepId, shardIndex).imageSources().orElseThrow();
        var next = new ArrayList<>(images.uploads());
        next.removeIf(
                value -> value.inputId() == upload.inputId() && value.unitId() == upload.unitId());
        next.add(upload);
        next.sort(
                java.util.Comparator.comparingInt(RunImageSources.Upload::inputId)
                        .thenComparingInt(RunImageSources.Upload::unitId));
        recordImages(stepId, shardIndex, new RunImageSources(next, images.failureReport()));
    }

    /** Force every canonical shard request before atomically appending the step reference. */
    void appendStep(
            String stepId,
            Optional<String> parentPlanDigest,
            DurableRequestSharder.StepPlan stepPlan)
            throws IOException {
        String modelId =
                qupath.ext.histoforge.modules.ModuleContract.requireModelId(stepId, "step_id");
        Optional<String> parentPlan = Objects.requireNonNull(parentPlanDigest, "parentPlanDigest");
        DurableRequestSharder.StepPlan plan = Objects.requireNonNull(stepPlan, "stepPlan");
        List<LargeActionTypes.ModelRunRef> runs = new ArrayList<>();
        for (DurableRequestSharder.PlannedShard shard : plan.shards()) {
            byte[] bytes =
                    CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(shard.request()));
            String path =
                    "actions/"
                            + current.handle().actionId()
                            + "/"
                            + current.handle().handleGenerationId()
                            + "/requests/"
                            + modelId
                            + "/"
                            + shard.request().modelRunId()
                            + ".json";
            LargeActionTypes.LocalFileRecord file = handles.publishImmutable(path, bytes);
            runs.add(
                    new LargeActionTypes.ModelRunRef(
                            shard.shardIndex(),
                            file,
                            shard.request().modelRunId(),
                            Optional.empty(),
                            Optional.empty(),
                            current.handle().sourcePlan() instanceof IncrementalSourcePlan
                                    ? Optional.of(RunImageSources.empty())
                                    : Optional.empty()));
        }
        List<LargeActionTypes.StepRunRef> retained = new ArrayList<>(current.handle().steps());
        retained.add(
                new LargeActionTypes.StepRunRef(modelId, plan.inputPlanDigest(), parentPlan, runs));
        save(withSteps(current.handle(), retained));
    }

    /** Record one already validated terminal manifest before any candidate can leave its step. */
    void recordManifest(String stepId, int shardIndex, String manifestDigest) throws IOException {
        String modelId =
                qupath.ext.histoforge.modules.ModuleContract.requireModelId(stepId, "step_id");
        int index = CanonicalCodec.requireNonnegativeInt32(shardIndex, "shard_index");
        var steps = current.handle().steps();
        var step = steps.get(stepPosition(steps, modelId));
        if (index >= step.runs().size() || step.runs().get(index).shardIndex() != index) {
            throw new IllegalStateException("durable manifest has no retained request shard");
        }
        LargeActionTypes.ModelRunRef run = step.runs().get(index);
        String digest = CanonicalCodec.requireSha256(manifestDigest, "result_manifest_digest");
        if (run.resultManifestDigest().isPresent()) {
            if (!run.resultManifestDigest().orElseThrow().equals(digest)) {
                throw new IllegalStateException("durable run manifest identity changed");
            }
            return;
        }
        saveRun(
                modelId,
                index,
                new LargeActionTypes.ModelRunRef(
                        run.shardIndex(),
                        run.requestFile(),
                        run.modelRunId(),
                        Optional.of(digest),
                        run.completeResultFiles(),
                        run.imageSources()));
    }

    /** Force one exact replacement request before atomically changing its retained shard. */
    void replaceRun(String stepId, int shardIndex, ModelRunRequestCodec.ModelRunRequest replacement)
            throws IOException {
        String modelId =
                qupath.ext.histoforge.modules.ModuleContract.requireModelId(stepId, "step_id");
        int index = CanonicalCodec.requireNonnegativeInt32(shardIndex, "shard_index");
        ModelRunRequestCodec.ModelRunRequest next =
                Objects.requireNonNull(replacement, "replacement");
        var steps = current.handle().steps();
        var step = steps.get(stepPosition(steps, modelId));
        if (index >= step.runs().size() || step.runs().get(index).shardIndex() != index) {
            throw new IllegalStateException("durable replacement has no retained request shard");
        }
        LargeActionTypes.ModelRunRef prior = step.runs().get(index);
        LargeActionHandleStore.AuthorizedModelRun authorized = authorizedRun(modelId, index);
        if (!authorized.request().modelRunId().equals(prior.modelRunId())
                || next.modelRunId().equals(prior.modelRunId())
                || !Arrays.equals(
                        ModelRunRequestCodec.replacementPlanBytes(authorized.request()),
                        ModelRunRequestCodec.replacementPlanBytes(next))) {
            throw new IllegalArgumentException(
                    "durable replacement must retain the exact prior scientific request plan");
        }
        Set<String> retainedIds = new HashSet<>();
        current.handle().steps().stream()
                .flatMap(value -> value.runs().stream())
                .forEach(value -> retainedIds.add(value.modelRunId()));
        if (retainedIds.contains(next.modelRunId())) {
            throw new IllegalArgumentException("durable replacement run ID is already retained");
        }
        next.resumeFrom()
                .ifPresent(
                        source -> {
                            if (!source.previousRunId().equals(prior.modelRunId())
                                    || prior.resultManifestDigest().isEmpty()
                                    || !source.previousManifestDigest()
                                            .equals(prior.resultManifestDigest().orElseThrow())) {
                                throw new IllegalArgumentException(
                                        "durable replacement resume source is not the retained"
                                                + " prior manifest");
                            }
                        });

        byte[] bytes = CanonicalCodec.canonicalBytes(ModelRunRequestCodec.toJson(next));
        String path =
                "actions/"
                        + current.handle().actionId()
                        + "/"
                        + current.handle().handleGenerationId()
                        + "/requests/"
                        + modelId
                        + "/"
                        + next.modelRunId()
                        + ".json";
        LargeActionTypes.LocalFileRecord requestFile = handles.publishImmutable(path, bytes);
        saveRun(
                modelId,
                index,
                new LargeActionTypes.ModelRunRef(
                        index,
                        requestFile,
                        next.modelRunId(),
                        Optional.empty(),
                        Optional.empty(),
                        prior.imageSources()
                                .map(
                                        images ->
                                                new RunImageSources(
                                                        images.uploads(), Optional.empty()))));
    }

    LargeActionHandleStore.AuthorizedModelRun authorizedRun(String stepId, int shardIndex) {
        return current.authorizedRuns().stream()
                .filter(run -> run.stepId().equals(stepId) && run.shardIndex() == shardIndex)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "durable retained shard lacks its canonical request"));
    }

    private static int stepPosition(List<LargeActionTypes.StepRunRef> retained, String stepId) {
        for (int position = 0; position < retained.size(); position++) {
            if (retained.get(position).stepId().equals(stepId)) {
                return position;
            }
        }
        throw new IllegalStateException(
                "durable action has no retained request for step " + stepId);
    }

    private void saveRun(String stepId, int shardIndex, LargeActionTypes.ModelRunRef next)
            throws IOException {
        var steps = new ArrayList<>(current.handle().steps());
        int position = stepPosition(steps, stepId);
        var step = steps.get(position);
        var runs = new ArrayList<>(step.runs());
        if (runs.get(shardIndex).equals(next)) return;
        runs.set(shardIndex, next);
        steps.set(
                position,
                new LargeActionTypes.StepRunRef(
                        step.stepId(), step.inputPlanDigest(), step.parentPlanDigest(), runs));
        save(withSteps(current.handle(), steps));
    }

    private void save(LargeActionTypes.LargeActionHandle next) throws IOException {
        handles.save(next);
        current = handles.requireResumable(next.actionId(), next.handleGenerationId());
    }

    private LargeActionTypes.LargeActionHandle withSteps(
            LargeActionTypes.LargeActionHandle handle,
            List<LargeActionTypes.StepRunRef> retainedSteps) {
        return new LargeActionTypes.LargeActionHandle(
                handle.schemaVersion(),
                handle.actionId(),
                handle.handleGenerationId(),
                handle.clientBuild(),
                handle.imageId(),
                handle.module(),
                handle.action(),
                handle.target(),
                handle.baseline(),
                handle.sourcePlan(),
                handle.source(),
                retainedSteps,
                handle.expectedCommittedHierarchy(),
                handle.phase(),
                handle.failure(),
                handle.createdAt(),
                now(handle.updatedAt()),
                handle.selectionRegions());
    }

    private Instant now(Instant notBefore) {
        Instant value = Instant.ofEpochMilli(clock.instant().toEpochMilli());
        return value.isBefore(notBefore) ? notBefore : value;
    }
}
