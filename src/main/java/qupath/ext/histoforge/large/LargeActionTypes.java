package qupath.ext.histoforge.large;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ArtifactRefCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.RecipeDependencyGraph;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.spatial.SourceInputPlan.IncrementalSourcePlan;
import qupath.ext.histoforge.spatial.SourceInputPlan.SourceArtifactPlan;

/** Persisted client state for one explicitly recoverable target action. */
public final class LargeActionTypes {

    public static final int SCHEMA_VERSION = 3;

    private LargeActionTypes() {}

    /** Forced immutable file retained below one project recovery root. */
    public record LocalFileRecord(String projectRelativePosixPath, long sizeBytes, String sha256) {
        public LocalFileRecord {
            projectRelativePosixPath = requireProjectRelativePath(projectRelativePosixPath);
            sizeBytes =
                    CanonicalCodec.requirePositiveSafeInteger(sizeBytes, "local file size_bytes");
            sha256 = CanonicalCodec.requireSha256(sha256, "local file sha256");
        }
    }

    /** Upload request, optional owned bytes (prepared pixels or a ZIP), and sealed reference. */
    public record SourceUploadRef(
            String artifactId,
            LocalFileRecord startRequestFile,
            Optional<LocalFileRecord> ownedUploadFile,
            Optional<SourceArtifactPublicationCodec.SourceArtifactRef> sealedArtifact) {
        public SourceUploadRef {
            artifactId = CanonicalCodec.requireCanonicalUuid(artifactId, "artifact_id");
            startRequestFile = Objects.requireNonNull(startRequestFile, "startRequestFile");
            ownedUploadFile = Objects.requireNonNull(ownedUploadFile, "ownedUploadFile");
            sealedArtifact = Objects.requireNonNull(sealedArtifact, "sealedArtifact");
            if (sealedArtifact.isPresent()) {
                var sealed = sealedArtifact.orElseThrow();
                if (!artifactId.equals(sealed.artifactId())
                        || !startRequestFile.sha256().equals(sealed.startRequestDigest())) {
                    throw new IllegalArgumentException(
                            "sealed source does not match its artifact ID and start request");
                }
            }
        }
    }

    /** One canonical request shard for a durable model step. */
    public record ModelRunRef(
            int shardIndex,
            LocalFileRecord requestFile,
            String modelRunId,
            Optional<String> resultManifestDigest,
            Optional<CompleteResultFiles> completeResultFiles,
            Optional<RunImageSources> imageSources) {
        public ModelRunRef(
                int shardIndex,
                LocalFileRecord requestFile,
                String modelRunId,
                Optional<String> resultManifestDigest) {
            this(
                    shardIndex,
                    requestFile,
                    modelRunId,
                    resultManifestDigest,
                    Optional.empty(),
                    Optional.empty());
        }

        public ModelRunRef(
                int shardIndex,
                LocalFileRecord requestFile,
                String modelRunId,
                Optional<String> resultManifestDigest,
                Optional<CompleteResultFiles> completeResultFiles) {
            this(
                    shardIndex,
                    requestFile,
                    modelRunId,
                    resultManifestDigest,
                    completeResultFiles,
                    Optional.empty());
        }

        public ModelRunRef {
            shardIndex = CanonicalCodec.requireNonnegativeInt32(shardIndex, "shard_index");
            requestFile = Objects.requireNonNull(requestFile, "requestFile");
            modelRunId = CanonicalCodec.requireCanonicalUuid(modelRunId, "model_run_id");
            resultManifestDigest =
                    Objects.requireNonNull(resultManifestDigest, "resultManifestDigest")
                            .map(
                                    value ->
                                            CanonicalCodec.requireSha256(
                                                    value, "result_manifest_digest"));
            completeResultFiles =
                    Objects.requireNonNull(completeResultFiles, "completeResultFiles");
            Objects.requireNonNull(imageSources, "imageSources");
            if (completeResultFiles.isPresent() && resultManifestDigest.isEmpty()) {
                throw new IllegalArgumentException(
                        "complete result files require a manifest identity");
            }
        }
    }

    /**
     * A validated COMPLETE manifest and the feature files already published locally. The manifest
     * reference is retained before downloading features, so an interrupted download can be repaired
     * from the same immutable artifacts. File references do not prove presence.
     */
    public record CompleteResultFiles(
            ArtifactRefCodec.ArtifactRef manifestArtifact,
            LocalFileRecord manifestFile,
            List<LocalFileRecord> featureFiles) {
        public CompleteResultFiles {
            manifestArtifact = Objects.requireNonNull(manifestArtifact, "manifestArtifact");
            manifestFile = Objects.requireNonNull(manifestFile, "manifestFile");
            featureFiles = List.copyOf(featureFiles);
            if (!manifestArtifact.mediaType().equals("application/json")
                    || manifestArtifact.contentEncoding().isPresent()
                    || manifestArtifact.sizeBytes() != manifestFile.sizeBytes()
                    || !manifestArtifact.sha256().equals(manifestFile.sha256())) {
                throw new IllegalArgumentException(
                        "retained manifest file differs from its artifact");
            }
            var paths = new LinkedHashSet<String>();
            paths.add(manifestFile.projectRelativePosixPath());
            for (var file : featureFiles) {
                if (!paths.add(file.projectRelativePosixPath())) {
                    throw new IllegalArgumentException("complete result repeats a local file");
                }
            }
        }
    }

    /** Complete active or terminal backend shards for one model step. */
    public record StepRunRef(
            String stepId,
            String inputPlanDigest,
            Optional<String> parentPlanDigest,
            List<ModelRunRef> runs) {
        public StepRunRef {
            stepId = ModuleContract.requireModelId(stepId, "step_id");
            inputPlanDigest = CanonicalCodec.requireSha256(inputPlanDigest, "input_plan_digest");
            parentPlanDigest =
                    Objects.requireNonNull(parentPlanDigest, "parentPlanDigest")
                            .map(
                                    value ->
                                            CanonicalCodec.requireSha256(
                                                    value, "parent_plan_digest"));
            if (runs == null) {
                throw new IllegalArgumentException("step runs must not be null");
            }
            List<ModelRunRef> normalized = new ArrayList<>(runs.size());
            Set<String> modelRunIds = new LinkedHashSet<>();
            for (int index = 0; index < runs.size(); index++) {
                ModelRunRef run = Objects.requireNonNull(runs.get(index), "model run ref");
                if (run.shardIndex() != index) {
                    throw new IllegalArgumentException(
                            "model run shards must be contiguous from zero");
                }
                if (!modelRunIds.add(run.modelRunId())) {
                    throw new IllegalArgumentException(
                            "model run shards must use unique model_run_id values");
                }
                normalized.add(run);
            }
            runs = List.copyOf(normalized);
        }
    }

    /** Exact semantic hierarchy population and Redo replacement authority for one target. */
    public record HierarchyTransactionIdentity(
            Set<ExecutionScopeCodec.OutputKey> replacementClosure,
            Set<String> guardedObjectIds,
            String contentDigest) {
        public HierarchyTransactionIdentity {
            if (replacementClosure == null) {
                throw new IllegalArgumentException("replacementClosure must not be null");
            }
            replacementClosure =
                    Collections.unmodifiableSet(new LinkedHashSet<>(replacementClosure));
            if (replacementClosure.contains(null)) {
                throw new IllegalArgumentException("replacementClosure must not contain null");
            }
            if (guardedObjectIds == null) {
                throw new IllegalArgumentException("guardedObjectIds must not be null");
            }
            LinkedHashSet<String> guarded = new LinkedHashSet<>();
            for (String objectId : guardedObjectIds) {
                guarded.add(CanonicalCodec.requireCanonicalUuid(objectId, "guarded_object_id"));
            }
            guardedObjectIds = Collections.unmodifiableSet(guarded);
            contentDigest = CanonicalCodec.requireSha256(contentDigest, "hierarchy content_digest");
        }
    }

    /** Upload-time pixel and hierarchy evidence used by final rebase. */
    public record LargeActionBaseline(
            SourceIdentityCodec.ImageSourceIdentity pixelSource,
            HierarchyTransactionIdentity hierarchy) {
        public LargeActionBaseline {
            pixelSource = Objects.requireNonNull(pixelSource, "pixelSource");
            hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        }
    }

    /** Coarse client recovery state; backend records own upload and unit progress. */
    public enum LargeActionPhase {
        PREPARING,
        UPLOADING,
        EXECUTING,
        RESULT_READY,
        COMMITTED,
        SUPERSEDED,
        FAILED,
        CANCELLED,
        CONFLICT;

        public boolean terminal() {
            return switch (this) {
                case COMMITTED, SUPERSEDED, FAILED, CANCELLED, CONFLICT -> true;
                default -> false;
            };
        }

        /** These phases require completed cloud work and a confirmed QuPath save. */
        public boolean hasCompletedSave() {
            return this == COMMITTED || this == SUPERSEDED;
        }
    }

    /** Minimal durable handle for one large target and one client-handle generation. */
    public record LargeActionHandle(
            int schemaVersion,
            String actionId,
            String handleGenerationId,
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            String imageId,
            ModuleSnapshot module,
            ActionSelectionCodec.ImageAction action,
            ExecutionScopeCodec.TargetRegion target,
            LargeActionBaseline baseline,
            SourceArtifactPlan sourcePlan,
            Optional<SourceUploadRef> source,
            List<StepRunRef> steps,
            Optional<HierarchyTransactionIdentity> expectedCommittedHierarchy,
            LargeActionPhase phase,
            Optional<RunResults.FailureDetail> failure,
            Instant createdAt,
            Instant updatedAt,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        public LargeActionHandle(
                int schemaVersion,
                String actionId,
                String handleGenerationId,
                BuildIdentityCodec.ClientBuildIdentity clientBuild,
                String imageId,
                ModuleSnapshot module,
                ActionSelectionCodec.ImageAction action,
                ExecutionScopeCodec.TargetRegion target,
                LargeActionBaseline baseline,
                SourceArtifactPlan sourcePlan,
                Optional<SourceUploadRef> source,
                List<StepRunRef> steps,
                Optional<HierarchyTransactionIdentity> expectedCommittedHierarchy,
                LargeActionPhase phase,
                Optional<RunResults.FailureDetail> failure,
                Instant createdAt,
                Instant updatedAt) {
            this(
                    schemaVersion,
                    actionId,
                    handleGenerationId,
                    clientBuild,
                    imageId,
                    module,
                    action,
                    target,
                    baseline,
                    sourcePlan,
                    source,
                    steps,
                    expectedCommittedHierarchy,
                    phase,
                    failure,
                    createdAt,
                    updatedAt,
                    List.of(target));
        }

        public LargeActionHandle {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported large-action schema_version");
            }
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            handleGenerationId =
                    CanonicalCodec.requireCanonicalUuid(handleGenerationId, "handle_generation_id");
            clientBuild = Objects.requireNonNull(clientBuild, "clientBuild");
            imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
            module = Objects.requireNonNull(module, "module");
            action = requireSingleTargetAction(action, target);
            target = Objects.requireNonNull(target, "target");
            var targetType = target.key().getClass();
            selectionRegions = List.copyOf(selectionRegions);
            if (!selectionRegions.contains(target)
                    || selectionRegions.stream()
                                    .map(ExecutionScopeCodec.TargetRegion::key)
                                    .distinct()
                                    .count()
                            != selectionRegions.size()
                    || selectionRegions.stream()
                            .anyMatch(region -> region.key().getClass() != targetType)) {
                throw new IllegalArgumentException(
                        "Recovery selection must contain unique regions including the exact executed target");
            }
            baseline = Objects.requireNonNull(baseline, "baseline");
            sourcePlan = Objects.requireNonNull(sourcePlan, "sourcePlan");
            if (action instanceof ActionSelectionCodec.Fill
                    && (!baseline.hierarchy().replacementClosure().isEmpty()
                            || !baseline.hierarchy().guardedObjectIds().isEmpty())) {
                throw new IllegalArgumentException(
                        "Fill baseline cannot contain replacement or removal-guard state");
            }
            source = Objects.requireNonNull(source, "source");
            boolean incremental = sourcePlan instanceof IncrementalSourcePlan;
            if (incremental && source.isPresent()) {
                throw new IllegalArgumentException(
                        "incremental images belong to their model runs, not one whole-target source");
            }
            boolean sourceReady =
                    incremental || source.flatMap(SourceUploadRef::sealedArtifact).isPresent();
            steps = immutableSteps(steps);
            expectedCommittedHierarchy =
                    Objects.requireNonNull(
                            expectedCommittedHierarchy, "expectedCommittedHierarchy");
            phase = Objects.requireNonNull(phase, "phase");
            failure = Objects.requireNonNull(failure, "failure");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            CanonicalCodec.rfc3339Millis(createdAt, "created_at");
            CanonicalCodec.rfc3339Millis(updatedAt, "updated_at");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updated_at must not precede created_at");
            }
            if ((phase == LargeActionPhase.FAILED || phase == LargeActionPhase.CONFLICT)
                    != failure.isPresent()) {
                throw new IllegalArgumentException(
                        "failure is present exactly for FAILED or CONFLICT");
            }
            if (phase == LargeActionPhase.PREPARING
                    && (source.isPresent()
                            || !steps.isEmpty()
                            || expectedCommittedHierarchy.isPresent())) {
                throw new IllegalArgumentException(
                        "PREPARING cannot contain source, run, or commit state");
            }
            if (phase == LargeActionPhase.UPLOADING
                    && (source.isEmpty()
                            || source.orElseThrow().sealedArtifact().isPresent()
                            || !steps.isEmpty()
                            || expectedCommittedHierarchy.isPresent())) {
                throw new IllegalArgumentException(
                        "UPLOADING requires one unsealed source and no run or commit state");
            }
            if ((phase == LargeActionPhase.EXECUTING || phase == LargeActionPhase.RESULT_READY)
                    && !sourceReady) {
                throw new IllegalArgumentException(
                        "execution phases require one sealed source artifact");
            }
            if (!steps.isEmpty() && !sourceReady) {
                throw new IllegalArgumentException(
                        "retained model runs require one sealed source artifact");
            }
            if (phase == LargeActionPhase.EXECUTING && expectedCommittedHierarchy.isPresent()) {
                throw new IllegalArgumentException(
                        "EXECUTING cannot contain expected committed hierarchy state");
            }
            if ((phase == LargeActionPhase.COMMITTED || phase == LargeActionPhase.SUPERSEDED)
                    && !sourceReady) {
                throw new IllegalArgumentException(
                        "completed large-action phases require one sealed source artifact");
            }
            if ((phase == LargeActionPhase.RESULT_READY
                            || phase == LargeActionPhase.COMMITTED
                            || phase == LargeActionPhase.SUPERSEDED)
                    && expectedCommittedHierarchy.isEmpty()) {
                throw new IllegalArgumentException(
                        "completed large-action phases require expected hierarchy identity");
            }
            if (expectedCommittedHierarchy.isPresent()
                    && (!sourceReady
                            || steps.stream()
                                    .flatMap(step -> step.runs().stream())
                                    .anyMatch(run -> run.resultManifestDigest().isEmpty()))) {
                throw new IllegalArgumentException(
                        "expected hierarchy requires a sealed source and every retained run"
                                + " manifest");
            }
            if (expectedCommittedHierarchy.isPresent()
                    && !baseline.hierarchy()
                            .guardedObjectIds()
                            .equals(expectedCommittedHierarchy.orElseThrow().guardedObjectIds())) {
                throw new IllegalArgumentException(
                        "expected hierarchy must retain the baseline guarded object set");
            }
            if (expectedCommittedHierarchy.isPresent()
                    && !baseline.hierarchy()
                            .replacementClosure()
                            .equals(
                                    expectedCommittedHierarchy
                                            .orElseThrow()
                                            .replacementClosure())) {
                throw new IllegalArgumentException(
                        "expected hierarchy must retain the baseline replacement closure");
            }
            requireActivatedModels(module, action);
            requireStepOrder(module, steps, phase);
        }

        /** True when this exact build is allowed to resume or commit the handle. */
        public boolean resumableBy(BuildIdentityCodec.ClientBuildIdentity runningBuild) {
            return !phase.terminal() && clientBuild.equals(Objects.requireNonNull(runningBuild));
        }

        /** True once a retained canonical model-run request binds this source identity. */
        public boolean hasRetainedModelRunRequest() {
            return steps.stream().anyMatch(step -> !step.runs().isEmpty());
        }
    }

    private static List<StepRunRef> immutableSteps(List<StepRunRef> values) {
        if (values == null) {
            throw new IllegalArgumentException("steps must not be null");
        }
        List<StepRunRef> result = new ArrayList<>(values.size());
        Set<String> stepIds = new LinkedHashSet<>();
        for (StepRunRef step : values) {
            StepRunRef value = Objects.requireNonNull(step, "step run ref");
            if (!stepIds.add(value.stepId())) {
                throw new IllegalArgumentException("steps contain duplicate step_id");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void requireStepOrder(
            ModuleSnapshot module, List<StepRunRef> steps, LargeActionPhase phase) {
        List<String> activatedRecipeModels =
                module.recipe().steps().stream()
                        .filter(qupath.ext.histoforge.modules.ModuleRecipe.Step::isModelStep)
                        .map(qupath.ext.histoforge.modules.ModuleRecipe.Step::model)
                        .filter(module.activatedModels()::containsKey)
                        .toList();
        for (int index = 0; index < steps.size(); index++) {
            StepRunRef step = steps.get(index);
            if (index >= activatedRecipeModels.size()
                    || !activatedRecipeModels.get(index).equals(step.stepId())) {
                throw new IllegalArgumentException(
                        "large-action steps must be an activated recipe-order prefix");
            }
            boolean crop =
                    module.activatedModels().get(step.stepId()).input().type()
                            == ModuleSnapshot.ModelInputType.CROP;
            if (crop != step.parentPlanDigest().isPresent()) {
                throw new IllegalArgumentException(
                        "parent_plan_digest is present exactly for crop model steps");
            }
        }
        if ((phase == LargeActionPhase.RESULT_READY
                        || phase == LargeActionPhase.COMMITTED
                        || phase == LargeActionPhase.SUPERSEDED)
                && steps.size() != activatedRecipeModels.size()) {
            throw new IllegalArgumentException(
                    "completed large action requires every activated model step");
        }
    }

    private static void requireActivatedModels(
            ModuleSnapshot module, ActionSelectionCodec.ImageAction action) {
        Set<String> required =
                new LinkedHashSet<>(
                        RecipeDependencyGraph.from(module.recipe()).requiredModelIds(action));
        if (!module.activatedModels().keySet().equals(required)) {
            throw new IllegalArgumentException(
                    "large-action module does not contain the exact action-required models");
        }
        if (module.activatedModels().values().stream()
                .anyMatch(model -> model.capabilities().durableRun().isEmpty())) {
            throw new IllegalArgumentException(
                    "large-action models must support durable model runs");
        }
    }

    private static ActionSelectionCodec.ImageAction requireSingleTargetAction(
            ActionSelectionCodec.ImageAction action, ExecutionScopeCodec.TargetRegion target) {
        ActionSelectionCodec.ImageAction value = Objects.requireNonNull(action, "action");
        ExecutionScopeCodec.TargetRegion exact = Objects.requireNonNull(target, "target");
        ActionSelectionCodec.ModelWorkSelection work =
                switch (value) {
                    case ActionSelectionCodec.Fill fill -> fill.work();
                    case ActionSelectionCodec.Redo redo -> redo.work();
                    case ActionSelectionCodec.Metrics ignored ->
                            throw new IllegalArgumentException(
                                    "Metrics cannot create a large-action handle");
                };
        requireRegionOwner(work, exact.key());
        if (work instanceof ActionSelectionCodec.SelectedModels selected) {
            for (ActionSelectionCodec.StepTargets step : selected.steps()) {
                for (ActionSelectionCodec.TargetSelector selector : step.targets().selectors()) {
                    if (!selectorBelongsTo(selector, exact.key())) {
                        throw new IllegalArgumentException(
                                "large-action selector belongs to another target");
                    }
                }
            }
        }
        return value;
    }

    private static void requireRegionOwner(
            ActionSelectionCodec.ModelWorkSelection work, ExecutionScopeCodec.RegionKey key) {
        ActionSelectionCodec.RegionSelection selection =
                work instanceof ActionSelectionCodec.AllModels all
                        ? all.regions()
                        : ((ActionSelectionCodec.SelectedModels) work).regions();
        boolean matches =
                selection instanceof ActionSelectionCodec.RoiRegions rois
                        ? key instanceof ExecutionScopeCodec.Roi roi
                                && rois.roiIds().equals(Set.of(roi.roiId()))
                        : key instanceof ExecutionScopeCodec.Slide;
        if (!matches) {
            throw new IllegalArgumentException(
                    "large-action work must select exactly the captured target");
        }
    }

    private static boolean selectorBelongsTo(
            ActionSelectionCodec.TargetSelector selector, ExecutionScopeCodec.RegionKey key) {
        return switch (selector) {
            case ActionSelectionCodec.RoiTarget roi ->
                    key instanceof ExecutionScopeCodec.Roi target
                            && target.roiId().equals(roi.roiId());
            case ActionSelectionCodec.ParentTarget parent -> parent.region().equals(key);
            case ActionSelectionCodec.SlideTarget ignored ->
                    key instanceof ExecutionScopeCodec.Slide;
        };
    }

    static String requireProjectRelativePath(String value) {
        String path = Objects.requireNonNull(value, "projectRelativePosixPath");
        if (path.isEmpty()
                || path.startsWith("/")
                || path.endsWith("/")
                || path.indexOf('\\') >= 0
                || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "project recovery path must be normalized POSIX-relative");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "project recovery path contains an invalid segment");
            }
        }
        return path;
    }
}
