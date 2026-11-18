package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.images.ImageData;

/** Sequences isolated recipe transactions against one disposable working image. */
public final class RecipeExecutor {

    private final InputRunner inputRunner;
    private final Supplier<String> generationIds;
    private final Clock clock;

    /** Production executor using the one deployment-bound finite model boundary. */
    public RecipeExecutor(ModelGatewayExecutor gateway) {
        FiniteModelInputExecutor finite = new FiniteModelInputExecutor(gateway);
        this.inputRunner = finite::execute;
        this.generationIds = () -> UUID.randomUUID().toString();
        this.clock = Clock.systemUTC();
    }

    /** Test seam for deterministic output generations and model results. */
    RecipeExecutor(InputRunner inputRunner, Supplier<String> generationIds, Clock clock) {
        this.inputRunner = Objects.requireNonNull(inputRunner, "inputRunner");
        this.generationIds = Objects.requireNonNull(generationIds, "generationIds");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @FunctionalInterface
    interface InputRunner {
        CandidateGeneration execute(
                FiniteModelInputExecutor.PreparedInput input, BooleanSupplier shouldStop)
                throws IOException,
                        InterruptedException,
                        InferenceException,
                        ModelGatewayExecutor.AcceptedFiniteCallFailure;
    }

    /** Prepared source geometry or a preparation failure for one target's model work. */
    public sealed interface SourceProvision permits AvailableSource, FailedSource {}

    /** Known sampling geometry, either backed by one sealed file or by later bounded uploads. */
    public sealed interface AvailableSource extends SourceProvision
            permits PublishedSource, CapturedSource {
        RasterInputCodec.RasterSourceView viewFor(SourceInputPlan.SourceViewPlan planned);
    }

    /** Exact immutable backend source used by every model input for one target. */
    public record PublishedSource(SourceArtifactPublicationCodec.SourceArtifactRef artifact)
            implements AvailableSource {
        public PublishedSource {
            artifact = Objects.requireNonNull(artifact, "artifact");
        }

        @Override
        public RasterInputCodec.SourceView viewFor(SourceInputPlan.SourceViewPlan planned) {
            return RasterInputCodec.buildSourceView(
                    artifact,
                    planned.seriesIndex(),
                    planned.resolutionLevel(),
                    planned.plane(),
                    planned.pixelRect(),
                    planned.viewPixelsToSlide());
        }
    }

    /** Complete source geometry retained before any of its bounded image files are encoded. */
    public record CapturedSource(SourceCaptureCodec.Capture capture) implements AvailableSource {
        public CapturedSource {
            Objects.requireNonNull(capture, "capture");
        }

        @Override
        public SourceCaptureCodec.View viewFor(SourceInputPlan.SourceViewPlan planned) {
            var view = new SourceCaptureCodec.View(capture, planned.pixelRect());
            if (!planned.plane().equals(view.plane())
                    || !planned.viewPixelsToSlide().equals(view.viewPixelsToSlide())) {
                throw new IllegalArgumentException(
                        "planned model view differs from the source capture");
            }
            return view;
        }
    }

    /** Prepare one finite input only when the recipe reaches it, after evidence-space admission. */
    @FunctionalInterface
    public interface InputImageUpload {
        SourceCaptureCodec.Binding publish(
                RasterInputCodec.ModelRasterInput input,
                qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot model,
                BooleanSupplier shouldStop)
                throws IOException, InterruptedException, InferenceException;
    }

    /** Target-local source preparation/publication failure; unrelated ROI targets may continue. */
    public record FailedSource(Exception cause, RunResults.FailureDetail failure)
            implements SourceProvision {
        public FailedSource {
            cause = Objects.requireNonNull(cause, "cause");
            failure = Objects.requireNonNull(failure, "failure");
        }
    }

    /** Exact source planning, terminal target provisions, and source revision for this action. */
    public record ExecutionInputs(
            SourceInputPlan sourcePlan,
            Map<ExecutionScopeCodec.RegionKey, SourceProvision> sourceProvisions,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            InputImageUpload imageUpload) {
        public ExecutionInputs(
                SourceInputPlan sourcePlan,
                Map<ExecutionScopeCodec.RegionKey, SourceProvision> sourceProvisions,
                RasterInputCodec.ActionSourceRevision sourceRevision) {
            this(
                    sourcePlan,
                    sourceProvisions,
                    sourceRevision,
                    (input, model, stop) -> {
                        throw new IllegalStateException(
                                "incremental image upload is not connected");
                    });
        }

        public ExecutionInputs {
            sourcePlan = Objects.requireNonNull(sourcePlan, "sourcePlan");
            sourceProvisions = immutableProvisions(sourceProvisions);
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            imageUpload = Objects.requireNonNull(imageUpload, "imageUpload");
        }
    }

    /** One target transaction, successful or discarded, in canonical execution order. */
    public sealed interface TransactionResult permits AppliedTransaction, TargetFailure {
        List<ExecutionScopeCodec.TargetRegion> targets();

        List<RunResults.ScopeWorkResult> scopeResults();
    }

    /** One target that reached the disposable working hierarchy. */
    public record AppliedTransaction(
            List<ExecutionScopeCodec.TargetRegion> targets,
            ModelOutputCommitter.ApplyResult applyResult,
            List<RunResults.ScopeWorkResult> scopeResults)
            implements TransactionResult {
        public AppliedTransaction {
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("applied transaction targets must be nonempty");
            }
            applyResult = Objects.requireNonNull(applyResult, "applyResult");
            scopeResults = List.copyOf(Objects.requireNonNull(scopeResults, "scopeResults"));
        }
    }

    /** One target whose complete staged delta was discarded. */
    public record TargetFailure(
            List<ExecutionScopeCodec.TargetRegion> targets,
            Exception cause,
            RunResults.FailureDetail failure,
            List<RunResults.ScopeWorkResult> scopeResults)
            implements TransactionResult {
        public TargetFailure {
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("failed transaction targets must be nonempty");
            }
            cause = Objects.requireNonNull(cause, "cause");
            failure = Objects.requireNonNull(failure, "failure");
            scopeResults = List.copyOf(Objects.requireNonNull(scopeResults, "scopeResults"));
        }
    }

    /** Working-image transactions in canonical execution order; persistence happens later. */
    public record Result(List<TransactionResult> transactions) {
        public Result {
            transactions = List.copyOf(Objects.requireNonNull(transactions, "transactions"));
        }

        public List<AppliedTransaction> appliedTransactions() {
            return transactions.stream()
                    .filter(AppliedTransaction.class::isInstance)
                    .map(AppliedTransaction.class::cast)
                    .toList();
        }

        public List<TargetFailure> failures() {
            return transactions.stream()
                    .filter(TargetFailure.class::isInstance)
                    .map(TargetFailure.class::cast)
                    .toList();
        }

        /** Every successful or failed target's model-work result in execution order. */
        public List<RunResults.ScopeWorkResult> scopeResults() {
            List<RunResults.ScopeWorkResult> result = new ArrayList<>();
            transactions.forEach(value -> result.addAll(value.scopeResults()));
            return List.copyOf(result);
        }
    }

    /** Fill cannot repair stale downstream child output without Redo ownership. */
    public static final class FillRequiresRedoException extends IllegalStateException {
        FillRequiresRedoException(String message) {
            super(message);
        }
    }

    /** Fill cannot manufacture an unselected or unavailable prerequisite. */
    public static final class BlockedPrerequisiteException extends IllegalStateException {
        BlockedPrerequisiteException(String message) {
            super(message);
        }
    }

    /** Fill requested a producer materialization mode that only explicit Redo may replace. */
    public static final class CrossModeRequiresRedoException extends IllegalStateException {
        private final ProducerModePolicy.CrossModeRequiresRedo decision;

        CrossModeRequiresRedoException(ProducerModePolicy.CrossModeRequiresRedo decision) {
            super("Fill requires Redo to convert ROI/slide producer materialization");
            this.decision = Objects.requireNonNull(decision, "decision");
        }

        public ProducerModePolicy.CrossModeRequiresRedo decision() {
            return decision;
        }
    }

    /** Cancellation plus exact work observed before the disposable image is discarded. */
    public static final class ExecutionCancelledException extends CancellationException {
        private final List<RunResults.ScopeWorkResult> scopeResults;

        private ExecutionCancelledException(
                String message, List<RunResults.ScopeWorkResult> scopeResults, Exception cause) {
            super(message);
            this.scopeResults = List.copyOf(Objects.requireNonNull(scopeResults, "scopeResults"));
            initCause(Objects.requireNonNull(cause, "cause"));
        }

        public List<RunResults.ScopeWorkResult> scopeResults() {
            return scopeResults;
        }
    }

    /** Fatal local mutation failure plus every work result retained before image discard. */
    public static final class ExecutionFailedException extends RuntimeException {
        private final RunResults.FailureDetail failure;
        private final List<RunResults.ScopeWorkResult> scopeResults;

        private ExecutionFailedException(
                RunResults.FailureDetail failure,
                List<RunResults.ScopeWorkResult> scopeResults,
                RuntimeException cause) {
            super(failure.message(), Objects.requireNonNull(cause, "cause"));
            this.failure = Objects.requireNonNull(failure, "failure");
            this.scopeResults = List.copyOf(Objects.requireNonNull(scopeResults, "scopeResults"));
        }

        public RunResults.FailureDetail failure() {
            return failure;
        }

        public List<RunResults.ScopeWorkResult> scopeResults() {
            return scopeResults;
        }
    }

    /**
     * Execute finite targets in canonical order and apply only complete target transactions.
     *
     * <p>Independent pre-commit ROI failures are returned and later ROIs continue. Cancellation and
     * every failure during or after working-hierarchy mutation propagate so the image owner can
     * discard the complete unsaved working image. A cross-mode Redo keeps the same independent ROI
     * transaction boundary: the first successful ROI removes the old producer mode, and later ROI
     * failures do not roll that successful work back.
     */
    public Result execute(
            ImageExecutionPlan plan,
            ExecutionInputs inputs,
            ImageData<?> workingImage,
            BooleanSupplier shouldStop) {
        ImageExecutionPlan actionPlan = Objects.requireNonNull(plan, "plan");
        if (actionPlan.action() instanceof ActionSelectionCodec.Metrics) {
            throw new IllegalArgumentException("RecipeExecutor does not execute Metrics actions");
        }
        ExecutionInputs executionInputs = Objects.requireNonNull(inputs, "inputs");
        ImageData<?> working = Objects.requireNonNull(workingImage, "workingImage");
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        Map<ExecutionScopeCodec.RegionKey, SourceInputPlan.TargetPlanningResult> sources =
                validateInputs(actionPlan, executionInputs);
        ModelOutputCommitter committer =
                new ModelOutputCommitter(working, actionPlan.clientBuild().limits());
        OutputIndex initialIndex = OutputIndex.fromHierarchy(working.getHierarchy());
        ProducerModePolicy.Decision mode = ProducerModePolicy.evaluate(actionPlan, initialIndex);
        if (mode instanceof ProducerModePolicy.CrossModeRequiresRedo conflict) {
            throw new CrossModeRequiresRedoException(conflict);
        }
        ProducerModePolicy.Ready ready = (ProducerModePolicy.Ready) mode;
        Map<InferenceIdentityCodec.ModelStepIdentity, Set<ExecutionScopeCodec.OutputKey>>
                pendingProducerWideReplacements =
                        new LinkedHashMap<>(ready.producerWideReplacements());
        TargetRecipeTransaction transaction =
                new TargetRecipeTransaction(
                        actionPlan, executionInputs, working, inputRunner, generationIds);

        List<TransactionResult> transactions = new ArrayList<>();
        for (int targetIndex = 0; targetIndex < actionPlan.targetRegions().size(); targetIndex++) {
            ExecutionScopeCodec.TargetRegion target = actionPlan.targetRegions().get(targetIndex);
            FailedSource sourceFailure = failedSource(executionInputs, target.key());
            if (sourceFailure != null) {
                transactions.add(
                        new TargetFailure(
                                List.of(target),
                                sourceFailure.cause(),
                                sourceFailure.failure(),
                                RecipeOutcomes.failedTransaction(
                                        actionPlan,
                                        List.of(target),
                                        List.of(),
                                        sourceFailure.failure())));
                continue;
            }
            OutputIndex currentIndex = OutputIndex.fromHierarchy(working.getHierarchy());
            Set<ExecutionScopeCodec.OutputKey> closure =
                    RecipeScopeResolver.replacementClosure(
                            actionPlan,
                            target,
                            currentIndex,
                            pendingProducerWideReplacements,
                            transaction.plannedModels());
            HierarchyDelta delta;
            RecipeStaging.Target staged = null;
            try {
                requireRunning(stop);
                staged =
                        transaction.stage(
                                target,
                                sources.get(target.key()),
                                currentIndex,
                                closure,
                                new RecipeStaging.Projection(),
                                stop);
                requireRunning(stop);
                delta = stageDelta(actionPlan, committer, staged, clock.instant());
            } catch (TargetRecipeTransaction.TargetTransactionCancelled cancelled) {
                throw cancelledExecution(
                        actionPlan,
                        transactions,
                        actionPlan
                                .targetRegions()
                                .subList(targetIndex, actionPlan.targetRegions().size()),
                        cancelled.outcomes(),
                        cancelled);
            } catch (CancellationException cancelled) {
                throw cancelledExecution(
                        actionPlan,
                        transactions,
                        actionPlan
                                .targetRegions()
                                .subList(targetIndex, actionPlan.targetRegions().size()),
                        List.of(),
                        cancelled);
            } catch (TargetRecipeTransaction.TargetTransactionFailure failure) {
                transactions.add(targetFailure(actionPlan, target, failure));
                continue;
            } catch (Exception failure) {
                RunResults.FailureDetail detail = RecipeFailures.localStaging(failure);
                List<RunResults.ScopeWorkResult> completed =
                        staged == null ? List.of() : staged.outcomes();
                transactions.add(
                        new TargetFailure(
                                List.of(target),
                                failure,
                                detail,
                                RecipeOutcomes.failedTransaction(
                                        actionPlan, List.of(target), completed, detail)));
                continue;
            }

            // Apply is intentionally outside the recoverable target-failure boundary. Once a
            // mutation starts, any failure poisons the disposable image and must propagate.
            try {
                requireRunning(stop);
            } catch (CancellationException cancelled) {
                throw cancelledExecution(
                        actionPlan,
                        transactions,
                        actionPlan
                                .targetRegions()
                                .subList(targetIndex, actionPlan.targetRegions().size()),
                        staged.outcomes(),
                        cancelled);
            }
            ModelOutputCommitter.ApplyResult result;
            try {
                result = committer.apply(delta);
            } catch (RuntimeException failure) {
                throw failedExecution(
                        actionPlan,
                        transactions,
                        actionPlan
                                .targetRegions()
                                .subList(targetIndex, actionPlan.targetRegions().size()),
                        staged.outcomes(),
                        failure);
            }
            transactions.add(new AppliedTransaction(List.of(target), result, staged.outcomes()));
            // An initial conversion closure is one-shot authority. Reusing its logical keys after
            // a successful apply could remove output newly materialized by that same producer.
            pendingProducerWideReplacements
                    .entrySet()
                    .removeIf(entry -> closure.containsAll(entry.getValue()));
        }
        return new Result(transactions);
    }

    private static TargetFailure targetFailure(
            ImageExecutionPlan plan,
            ExecutionScopeCodec.TargetRegion target,
            TargetRecipeTransaction.TargetTransactionFailure failure) {
        return new TargetFailure(
                List.of(target),
                failure.originalCause(),
                failure.failure(),
                RecipeOutcomes.failedTransaction(
                        plan, List.of(target), failure.outcomes(), failure.failure()));
    }

    private static ExecutionCancelledException cancelledExecution(
            ImageExecutionPlan plan,
            List<TransactionResult> completedTransactions,
            List<ExecutionScopeCodec.TargetRegion> remainingTargets,
            List<RunResults.ScopeWorkResult> interruptedTargetOutcomes,
            CancellationException cause) {
        RunResults.FailureDetail failure =
                new RunResults.FailureDetail(
                        RunResults.FailureType.CANCELLED, RecipeFailures.message(cause));
        List<RunResults.ScopeWorkResult> outcomes = new ArrayList<>();
        completedTransactions.forEach(value -> outcomes.addAll(value.scopeResults()));
        outcomes.addAll(
                RecipeOutcomes.cancelledTransaction(
                        plan, remainingTargets, interruptedTargetOutcomes, failure));
        return new ExecutionCancelledException(failure.message(), outcomes, cause);
    }

    private static ExecutionFailedException failedExecution(
            ImageExecutionPlan plan,
            List<TransactionResult> completedTransactions,
            List<ExecutionScopeCodec.TargetRegion> remainingTargets,
            List<RunResults.ScopeWorkResult> currentOutcomes,
            RuntimeException cause) {
        RunResults.FailureDetail failure = RecipeFailures.applyFailure(cause);
        List<RunResults.ScopeWorkResult> outcomes = new ArrayList<>();
        completedTransactions.forEach(value -> outcomes.addAll(value.scopeResults()));
        outcomes.addAll(
                RecipeOutcomes.failedTransaction(plan, remainingTargets, currentOutcomes, failure));
        return new ExecutionFailedException(failure, outcomes, cause);
    }

    static HierarchyDelta stageDelta(
            ImageExecutionPlan plan,
            ModelOutputCommitter committer,
            RecipeStaging.Target staged,
            Instant committedAt) {
        Instant canonicalCommittedAt =
                Objects.requireNonNull(committedAt, "committedAt").truncatedTo(ChronoUnit.MILLIS);
        return committer.stage(
                new ModelOutputCommitter.StagingRequest(
                        plan.actionId(),
                        removeOverlappingDuplicates(plan),
                        plan.clientBuild(),
                        plan.module().deploymentId(),
                        staged.replacementClosure(),
                        staged.generations(),
                        staged.classChanges(),
                        canonicalCommittedAt));
    }

    private static Map<ExecutionScopeCodec.RegionKey, SourceInputPlan.TargetPlanningResult>
            validateInputs(ImageExecutionPlan plan, ExecutionInputs inputs) {
        Map<ExecutionScopeCodec.RegionKey, SourceInputPlan.TargetPlanningResult> byTarget =
                new LinkedHashMap<>();
        Set<ExecutionScopeCodec.RegionKey> targetsWithSources = new LinkedHashSet<>();
        Map<ExecutionScopeCodec.RegionKey, ExecutionScopeCodec.TargetRegion> expectedTargets =
                new LinkedHashMap<>();
        for (ExecutionScopeCodec.TargetRegion target : plan.targetRegions()) {
            expectedTargets.put(target.key(), target);
        }
        for (SourceInputPlan.TargetPlanningResult target : inputs.sourcePlan().targets()) {
            if (!target.target().equals(expectedTargets.get(target.target().key()))) {
                throw new IllegalArgumentException(
                        "source planning target differs from the captured image target");
            }
            if (byTarget.putIfAbsent(target.target().key(), target) != null) {
                throw new IllegalArgumentException("source planning targets must be unique");
            }
            if (target.sourcePlan().isPresent()) {
                targetsWithSources.add(target.target().key());
                SourceInputPlan.TargetSourcePlan source = target.sourcePlan().orElseThrow();
                if (!source.sourceRevision().equals(inputs.sourceRevision())) {
                    throw new IllegalArgumentException(
                            "source planning and action source revisions differ");
                }
                SourceProvision provision = inputs.sourceProvisions().get(target.target().key());
                if (provision == null) {
                    throw new IllegalArgumentException(
                            "target source plan lacks a terminal source provision");
                }
                if (provision instanceof PublishedSource published
                        && (source.protocol() != SourceInputPlan.ExecutionProtocol.FINITE
                                || published.artifact().container()
                                        != expectedContainer(source.artifact()))) {
                    throw new IllegalArgumentException(
                            "target source plan lacks its exact sealed artifact container");
                }
                if (provision instanceof CapturedSource captured
                        && (!(source.artifact()
                                        instanceof
                                        SourceInputPlan.IncrementalSourcePlan incremental)
                                || !incremental
                                        .capture(
                                                inputs.sourceRevision().pixelSource(),
                                                plan.clientBuild().extension())
                                        .equals(captured.capture()))) {
                    throw new IllegalArgumentException(
                            "target source capture differs from its saved delivery plan");
                }
            } else if (inputs.sourceProvisions().containsKey(target.target().key())) {
                throw new IllegalArgumentException(
                        "target without source work must not carry a source provision");
            }
        }
        if (!inputs.sourceRevision().imageDataRevision().imageId().equals(plan.imageId())) {
            throw new IllegalArgumentException(
                    "action source revision belongs to another project image");
        }
        if (!byTarget.keySet().equals(expectedTargets.keySet())
                || !inputs.sourceProvisions().keySet().equals(targetsWithSources)) {
            throw new IllegalArgumentException(
                    "source planning targets do not match the image execution plan");
        }
        return Collections.unmodifiableMap(byTarget);
    }

    private static SourceArtifactCodec.SourceContainer expectedContainer(
            SourceInputPlan.SourceArtifactPlan artifact) {
        return artifact instanceof SourceInputPlan.OriginalArtifactPlan original
                ? original.container()
                : ((SourceInputPlan.DerivedArtifactPlan) artifact).container();
    }

    private static boolean removeOverlappingDuplicates(ImageExecutionPlan plan) {
        if (plan.action() instanceof ActionSelectionCodec.Fill fill) {
            return fill.removeOverlappingSameClassDuplicates();
        }
        return ((ActionSelectionCodec.Redo) plan.action()).removeOverlappingSameClassDuplicates();
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("recipe execution cancelled");
        }
    }

    private static FailedSource failedSource(
            ExecutionInputs inputs, ExecutionScopeCodec.RegionKey target) {
        SourceProvision provision = inputs.sourceProvisions().get(target);
        return provision instanceof FailedSource failed ? failed : null;
    }

    private static Map<ExecutionScopeCodec.RegionKey, SourceProvision> immutableProvisions(
            Map<ExecutionScopeCodec.RegionKey, SourceProvision> values) {
        Objects.requireNonNull(values, "sourceProvisions");
        Map<ExecutionScopeCodec.RegionKey, SourceProvision> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) ->
                        result.put(
                                Objects.requireNonNull(key, "source provision target"),
                                Objects.requireNonNull(value, "source provision")));
        return Collections.unmodifiableMap(result);
    }
}
