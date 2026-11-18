package qupath.ext.histoforge.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.state.OutputIndex;

/** Sole owner of recipe scope-outcome construction and discarded-transaction reduction. */
final class RecipeOutcomes {

    private RecipeOutcomes() {}

    /** Mutable target-local recorder whose immutable snapshot enters a staged transaction. */
    static final class Tracker {
        private final List<RunResults.ScopeWorkResult> values = new ArrayList<>();

        List<RunResults.ScopeWorkResult> snapshot() {
            return List.copyOf(values);
        }

        void generation(CandidateGeneration generation, boolean replacesCurrentOutput) {
            var counts = generation.counts();
            RunResults.ScopeOutcomeType type;
            if (counts.backendFeatures() == 0) {
                type = RunResults.ScopeOutcomeType.SUCCEEDED_EMPTY;
            } else if (counts.committedObjects() == 0) {
                type = RunResults.ScopeOutcomeType.SUCCEEDED_NO_ADDITIONS;
            } else {
                type = RunResults.ScopeOutcomeType.PRODUCED;
            }
            add(
                    new RunResults.ScopeWorkResult(
                            new RunResults.ExecutionOutput(generation.output()),
                            type,
                            counts.committedObjects() > 0 || replacesCurrentOutput,
                            Optional.of(generation.execution()),
                            Optional.empty()));
        }

        void skipped(ExecutionScopeCodec.OutputKey output, RunResults.ScopeOutcomeType type) {
            add(
                    new RunResults.ScopeWorkResult(
                            new RunResults.ExecutionOutput(output),
                            type,
                            false,
                            Optional.empty(),
                            Optional.empty()));
        }

        void blocked(
                InferenceIdentityCodec.ModelStepIdentity producer,
                ExecutionScopeCodec.RegionKey region,
                RunResults.FailureDetail failure) {
            add(
                    new RunResults.ScopeWorkResult(
                            new RunResults.RequestedTarget(producer, selectorFor(region)),
                            RunResults.ScopeOutcomeType.BLOCKED_PREREQUISITE,
                            false,
                            Optional.empty(),
                            Optional.of(failure)));
        }

        void noParents(
                InferenceIdentityCodec.ModelStepIdentity producer,
                ExecutionScopeCodec.RegionKey region) {
            add(
                    new RunResults.ScopeWorkResult(
                            new RunResults.RequestedTarget(producer, selectorFor(region)),
                            RunResults.ScopeOutcomeType.SKIPPED_NO_PARENTS,
                            false,
                            Optional.empty(),
                            Optional.empty()));
        }

        void failedAfterAcceptance(
                ExecutionScopeCodec.OutputKey output,
                BackendExecutionCodec.BackendExecutionRef execution,
                RunResults.FailureDetail failure) {
            terminal(output, RunResults.ScopeOutcomeType.FAILED, Optional.of(execution), failure);
        }

        void cancelledAfterAcceptance(
                ExecutionScopeCodec.OutputKey output,
                BackendExecutionCodec.BackendExecutionRef execution,
                RunResults.FailureDetail failure) {
            terminal(
                    output, RunResults.ScopeOutcomeType.CANCELLED, Optional.of(execution), failure);
        }

        void failedBeforeAcceptance(
                ExecutionScopeCodec.OutputKey output, RunResults.FailureDetail failure) {
            terminal(output, RunResults.ScopeOutcomeType.FAILED, Optional.empty(), failure);
        }

        void cancelledBeforeAcceptance(
                ExecutionScopeCodec.OutputKey output, RunResults.FailureDetail failure) {
            terminal(output, RunResults.ScopeOutcomeType.CANCELLED, Optional.empty(), failure);
        }

        boolean containsOutput(ExecutionScopeCodec.OutputKey output) {
            return contains(new RunResults.ExecutionOutput(output));
        }

        void unresolvedParent(
                ExecutionScopeCodec.TargetRegion target,
                InferenceIdentityCodec.ModelStepIdentity producer,
                String parentId,
                ExecutionScopeCodec.OutputKey output,
                boolean redo,
                OutputIndex outputIndex,
                StagedRegionView view) {
            if (redo && outputIndex.contains(output)) {
                clear(output, view.object(parentId).isPresent());
                return;
            }
            add(
                    new RunResults.ScopeWorkResult(
                            new RunResults.RequestedTarget(
                                    producer,
                                    new ActionSelectionCodec.ParentTarget(target.key(), parentId)),
                            RunResults.ScopeOutcomeType.SKIPPED_INELIGIBLE,
                            false,
                            Optional.empty(),
                            Optional.empty()));
        }

        void broadRedoClears(
                ExecutionScopeCodec.TargetRegion target,
                InferenceIdentityCodec.ModelStepIdentity producer,
                boolean redo,
                OutputIndex outputIndex,
                Set<ExecutionScopeCodec.OutputKey> closure,
                StagedRegionView view,
                Set<String> eligibleParentIds) {
            if (!redo) {
                return;
            }
            outputIndex.materialization(producer).currentOutputs().stream()
                    .filter(closure::contains)
                    .filter(
                            output ->
                                    ExecutionScopeCodec.regionOf(output.scope())
                                            .equals(target.key()))
                    .filter(output -> output.scope() instanceof ExecutionScopeCodec.ParentScope)
                    .filter(output -> !contains(new RunResults.ExecutionOutput(output)))
                    .map(
                            output ->
                                    Map.entry(
                                            output,
                                            ((ExecutionScopeCodec.ParentScope) output.scope())
                                                    .parentId()))
                    .filter(entry -> !eligibleParentIds.contains(entry.getValue()))
                    .forEach(
                            entry ->
                                    clear(
                                            entry.getKey(),
                                            view.object(entry.getValue()).isPresent()));
        }

        boolean hasProducer(
                InferenceIdentityCodec.ModelStepIdentity producer,
                ExecutionScopeCodec.RegionKey region) {
            return values.stream()
                    .map(RunResults.ScopeWorkResult::key)
                    .anyMatch(
                            key ->
                                    keyProducer(key).equals(producer)
                                            && keyRegion(key).equals(region));
        }

        private void clear(ExecutionScopeCodec.OutputKey output, boolean parentPresent) {
            add(
                    new RunResults.ScopeWorkResult(
                            new RunResults.ExecutionOutput(output),
                            parentPresent
                                    ? RunResults.ScopeOutcomeType.CLEARED_INELIGIBLE
                                    : RunResults.ScopeOutcomeType.CLEARED_ORPHANED,
                            true,
                            Optional.empty(),
                            Optional.empty()));
        }

        private void terminal(
                ExecutionScopeCodec.OutputKey output,
                RunResults.ScopeOutcomeType type,
                Optional<BackendExecutionCodec.BackendExecutionRef> execution,
                RunResults.FailureDetail failure) {
            if (type != RunResults.ScopeOutcomeType.FAILED
                    && type != RunResults.ScopeOutcomeType.CANCELLED) {
                throw new IllegalArgumentException("terminal outcome type is invalid");
            }
            add(
                    new RunResults.ScopeWorkResult(
                            new RunResults.ExecutionOutput(output),
                            type,
                            false,
                            execution,
                            Optional.of(failure)));
        }

        private boolean contains(RunResults.OutcomeKey key) {
            return values.stream().anyMatch(outcome -> outcome.key().equals(key));
        }

        private void add(RunResults.ScopeWorkResult outcome) {
            if (contains(outcome.key())) {
                throw new IllegalArgumentException(
                        "target transaction outcomes must have unique keys");
            }
            values.add(outcome);
        }
    }

    /**
     * Mark all mutation-producing work in a discarded transaction failed and cover work not reached
     * dynamically with requested-target failures.
     */
    static List<RunResults.ScopeWorkResult> failedTransaction(
            ImageExecutionPlan plan,
            List<ExecutionScopeCodec.TargetRegion> targets,
            List<RunResults.ScopeWorkResult> completed,
            RunResults.FailureDetail failure) {
        return terminatedTransaction(
                plan, targets, completed, RunResults.ScopeOutcomeType.FAILED, failure);
    }

    /** Complete the interrupted and not-yet-started work owned by one cancelled transaction. */
    static List<RunResults.ScopeWorkResult> cancelledTransaction(
            ImageExecutionPlan plan,
            List<ExecutionScopeCodec.TargetRegion> targets,
            List<RunResults.ScopeWorkResult> completed,
            RunResults.FailureDetail failure) {
        return terminatedTransaction(
                plan, targets, completed, RunResults.ScopeOutcomeType.CANCELLED, failure);
    }

    private static List<RunResults.ScopeWorkResult> terminatedTransaction(
            ImageExecutionPlan plan,
            List<ExecutionScopeCodec.TargetRegion> targets,
            List<RunResults.ScopeWorkResult> completed,
            RunResults.ScopeOutcomeType terminalType,
            RunResults.FailureDetail failure) {
        if (terminalType != RunResults.ScopeOutcomeType.FAILED
                && terminalType != RunResults.ScopeOutcomeType.CANCELLED) {
            throw new IllegalArgumentException("discarded transaction terminal type is invalid");
        }
        Map<RunResults.OutcomeKey, RunResults.ScopeWorkResult> results = new LinkedHashMap<>();
        for (RunResults.ScopeWorkResult outcome : completed) {
            RunResults.ScopeWorkResult terminal =
                    terminalIfDiscarded(outcome, terminalType, failure);
            if (results.putIfAbsent(terminal.key(), terminal) != null) {
                throw new IllegalArgumentException(
                        "discarded transaction contains duplicate scope outcomes");
            }
        }

        for (ExecutionScopeCodec.TargetRegion target : targets) {
            for (ImageExecutionPlan.PlannedModelStep model : plan.modelsInRecipeOrder()) {
                RecipeScopeResolver.TargetSelection selection =
                        RecipeScopeResolver.selectionForTarget(model.targets(), target.key());
                if (selection.empty()) {
                    continue;
                }
                InferenceIdentityCodec.ModelStepIdentity producer =
                        new InferenceIdentityCodec.ModelStepIdentity(
                                plan.module().module().moduleRef(), model.stepId());
                if (selection.broad()) {
                    addUnresolvedTerminal(
                            results,
                            producer,
                            selectorFor(target.key()),
                            target.key(),
                            null,
                            terminalType,
                            failure);
                    continue;
                }
                selection.parentIds().stream()
                        .sorted()
                        .forEach(
                                parentId ->
                                        addUnresolvedTerminal(
                                                results,
                                                producer,
                                                new ActionSelectionCodec.ParentTarget(
                                                        target.key(), parentId),
                                                target.key(),
                                                parentId,
                                                terminalType,
                                                failure));
            }
        }
        return List.copyOf(results.values());
    }

    private static RunResults.ScopeWorkResult terminalIfDiscarded(
            RunResults.ScopeWorkResult outcome,
            RunResults.ScopeOutcomeType terminalType,
            RunResults.FailureDetail failure) {
        boolean discarded =
                switch (outcome.type()) {
                    case PRODUCED,
                            SUCCEEDED_EMPTY,
                            SUCCEEDED_NO_ADDITIONS,
                            CLEARED_INELIGIBLE,
                            CLEARED_ORPHANED ->
                            true;
                    default -> false;
                };
        if (!discarded) {
            return outcome;
        }
        return new RunResults.ScopeWorkResult(
                outcome.key(), terminalType, false, outcome.execution(), Optional.of(failure));
    }

    private static void addUnresolvedTerminal(
            Map<RunResults.OutcomeKey, RunResults.ScopeWorkResult> results,
            InferenceIdentityCodec.ModelStepIdentity producer,
            ActionSelectionCodec.TargetSelector selector,
            ExecutionScopeCodec.RegionKey region,
            String parentId,
            RunResults.ScopeOutcomeType terminalType,
            RunResults.FailureDetail failure) {
        boolean represented =
                results.keySet().stream()
                        .anyMatch(key -> represents(key, producer, selector, region, parentId));
        if (represented) {
            return;
        }
        RunResults.RequestedTarget key = new RunResults.RequestedTarget(producer, selector);
        results.put(
                key,
                new RunResults.ScopeWorkResult(
                        key, terminalType, false, Optional.empty(), Optional.of(failure)));
    }

    private static boolean represents(
            RunResults.OutcomeKey key,
            InferenceIdentityCodec.ModelStepIdentity producer,
            ActionSelectionCodec.TargetSelector selector,
            ExecutionScopeCodec.RegionKey region,
            String parentId) {
        if (key instanceof RunResults.RequestedTarget requested) {
            return requested.producer().equals(producer) && requested.target().equals(selector);
        }
        ExecutionScopeCodec.OutputKey output = ((RunResults.ExecutionOutput) key).output();
        if (!output.producer().equals(producer)
                || !ExecutionScopeCodec.regionOf(output.scope()).equals(region)) {
            return false;
        }
        if (parentId == null) {
            return true;
        }
        return output.scope() instanceof ExecutionScopeCodec.ParentScope parent
                && parent.parentId().equals(parentId);
    }

    private static InferenceIdentityCodec.ModelStepIdentity keyProducer(RunResults.OutcomeKey key) {
        return key instanceof RunResults.ExecutionOutput output
                ? output.output().producer()
                : ((RunResults.RequestedTarget) key).producer();
    }

    private static ExecutionScopeCodec.RegionKey keyRegion(RunResults.OutcomeKey key) {
        if (key instanceof RunResults.ExecutionOutput output) {
            return ExecutionScopeCodec.regionOf(output.output().scope());
        }
        ActionSelectionCodec.TargetSelector selector = ((RunResults.RequestedTarget) key).target();
        if (selector instanceof ActionSelectionCodec.RoiTarget roi) {
            return new ExecutionScopeCodec.Roi(roi.roiId());
        }
        if (selector instanceof ActionSelectionCodec.ParentTarget parent) {
            return parent.region();
        }
        return new ExecutionScopeCodec.Slide();
    }

    static ActionSelectionCodec.TargetSelector selectorFor(ExecutionScopeCodec.RegionKey region) {
        return region instanceof ExecutionScopeCodec.Roi roi
                ? new ActionSelectionCodec.RoiTarget(roi.roiId())
                : new ActionSelectionCodec.SlideTarget();
    }
}
