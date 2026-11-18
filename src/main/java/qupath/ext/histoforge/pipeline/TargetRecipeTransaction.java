package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.modules.ModuleContract;
import qupath.ext.histoforge.modules.ModuleRecipe;
import qupath.ext.histoforge.spatial.CandidateGeneration;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;

/** Executes one recipe against isolated target state and returns a complete staged transaction. */
final class TargetRecipeTransaction {

    private final ImageExecutionPlan plan;
    private final RecipeExecutor.ExecutionInputs inputs;
    private final ImageData<?> workingImage;
    private final RecipeModelInputAdapter inputAdapter;
    private final Map<String, ImageExecutionPlan.PlannedModelStep> plannedModels;

    TargetRecipeTransaction(
            ImageExecutionPlan plan,
            RecipeExecutor.ExecutionInputs inputs,
            ImageData<?> workingImage,
            RecipeExecutor.InputRunner inputRunner,
            java.util.function.Supplier<String> generationIds) {
        this(
                plan,
                inputs,
                workingImage,
                new FiniteRecipeModelInputAdapter(
                        plan, inputRunner, generationIds, inputs.imageUpload()));
    }

    TargetRecipeTransaction(
            ImageExecutionPlan plan,
            RecipeExecutor.ExecutionInputs inputs,
            ImageData<?> workingImage,
            RecipeModelInputAdapter inputAdapter) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.workingImage = Objects.requireNonNull(workingImage, "workingImage");
        this.inputAdapter = Objects.requireNonNull(inputAdapter, "inputAdapter");
        this.plannedModels = RecipeScopeResolver.plannedModels(plan);
    }

    Map<String, ImageExecutionPlan.PlannedModelStep> plannedModels() {
        return plannedModels;
    }

    RecipeStaging.Target stage(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            OutputIndex outputIndex,
            Set<ExecutionScopeCodec.OutputKey> transactionClosure,
            RecipeStaging.Projection projection,
            BooleanSupplier shouldStop)
            throws TargetTransactionFailure {
        Objects.requireNonNull(projection, "projection");
        RecipeOutcomes.Tracker outcomes = new RecipeOutcomes.Tracker();
        try {
            StagedRegionView view =
                    StagedRegionView.capture(
                            target, workingImage.getHierarchy(), plan.selectionRegions());
            view.removeOutputs(transactionClosure);
            List<CandidateGeneration> generations = new ArrayList<>();
            Set<String> invokedModels = new LinkedHashSet<>();
            boolean scriptBarrierActive = false;
            List<ModuleRecipe.Step> recipeSteps = plan.module().recipe().steps();

            for (int position = 0; position < recipeSteps.size(); position++) {
                requireRunning(shouldStop);
                ModuleRecipe.Step step = recipeSteps.get(position);
                if (step.isModelStep()) {
                    ImageExecutionPlan.PlannedModelStep planned = plannedModels.get(step.model());
                    RecipeScopeResolver.TargetSelection selection =
                            planned == null
                                    ? RecipeScopeResolver.TargetSelection.none()
                                    : RecipeScopeResolver.selectionForTarget(
                                            planned.targets(), target.key());
                    if (selection.empty()) {
                        continue;
                    }
                    boolean invoked =
                            executeModelStep(
                                    target,
                                    source,
                                    outputIndex,
                                    transactionClosure,
                                    view,
                                    step,
                                    selection,
                                    invokedModels,
                                    projection,
                                    generations,
                                    outcomes,
                                    shouldStop);
                    if (invoked) {
                        invokedModels.add(step.model());
                    }
                    scriptBarrierActive = true;
                    continue;
                }
                if (!step.isScriptStep()
                        || ModuleContract.METRICS_SCRIPT_NAME.equals(step.script())
                        || !scriptBarrierActive) {
                    continue;
                }
                Map<ModuleRecipe.Step, Set<String>> protectedChildren =
                        plan.action() instanceof ActionSelectionCodec.Fill
                                ? protectedChildParents(
                                        recipeSteps, position, target, outputIndex, view)
                                : Map.of();
                requireScriptCalibration();
                List<HierarchyDelta.ClassChange> changes;
                try {
                    changes =
                            ScriptExecutor.executeStaged(
                                    plan.module(),
                                    step.script(),
                                    new ScriptExecutor.ScriptContext(
                                            plan.imageId(),
                                            target,
                                            inputs.sourceRevision().pixelSource().calibration()),
                                    view);
                    view.applyClassChanges(changes);
                    requireProtectedChildrenRemainEligible(protectedChildren, view, step.script());
                } catch (RecipeExecutor.FillRequiresRedoException failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    throw RecipeFailures.categorized(
                            RunResults.FailureType.SCRIPT_CONTRACT,
                            "ordinary script "
                                    + step.script()
                                    + " failed: "
                                    + RecipeFailures.message(failure),
                            failure);
                }
            }
            projection.requireComplete();
            inputAdapter.requireComplete();
            return new RecipeStaging.Target(
                    List.of(target),
                    transactionClosure,
                    view.finalizedGenerations(generations),
                    view.existingClassChanges(),
                    outcomes.snapshot());
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw new TargetTransactionCancelled(outcomes.snapshot(), cancelled);
        } catch (LargeActionTransactionValidator.Conflict mismatch) {
            throw mismatch;
        } catch (Exception failure) {
            throw new TargetTransactionFailure(
                    outcomes.snapshot(),
                    RecipeFailures.detail(failure),
                    RecipeFailures.originalCause(failure));
        }
    }

    private boolean executeModelStep(
            ExecutionScopeCodec.TargetRegion target,
            SourceInputPlan.TargetPlanningResult source,
            OutputIndex outputIndex,
            Set<ExecutionScopeCodec.OutputKey> closure,
            StagedRegionView view,
            ModuleRecipe.Step step,
            RecipeScopeResolver.TargetSelection selection,
            Set<String> invokedModels,
            RecipeStaging.Projection projection,
            List<CandidateGeneration> generations,
            RecipeOutcomes.Tracker outcomes,
            BooleanSupplier shouldStop)
            throws IOException, InferenceException, RecipeFailures.Categorized {
        InferenceIdentityCodec.ModelStepIdentity producer =
                new InferenceIdentityCodec.ModelStepIdentity(
                        plan.module().module().moduleRef(), step.model());
        boolean redo = plan.action() instanceof ActionSelectionCodec.Redo;
        boolean crossModeReplacement =
                redo
                        && RecipeScopeResolver.hasCurrentCrossModeReplacement(
                                outputIndex, closure, producer, target.key());
        List<SourceInputPlan.PlannedModelInput> plannedInputs = new ArrayList<>();
        List<PathObject> sourceParents = new ArrayList<>();

        if (!step.isRegionGated()) {
            if (!selection.broad()) {
                throw new IllegalArgumentException("field model requires a region target");
            }
            ExecutionScopeCodec.OutputKey output =
                    new ExecutionScopeCodec.OutputKey(
                            producer, new ExecutionScopeCodec.FieldScope(target.key()));
            if (!redo && outputIndex.contains(output)) {
                inputAdapter.verifyEmptyStep(step, List.of());
                outcomes.skipped(output, RunResults.ScopeOutcomeType.SKIPPED_PRESENT);
                return false;
            }
            plannedInputs.add(inputAdapter.planFieldInput(target, source, output));
        } else {
            Map<String, PathObject> eligible =
                    view.eligibleParents(plan.module().module().moduleRef(), step).stream()
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            object -> object.getID().toString(),
                                            object -> object,
                                            (left, right) -> {
                                                throw new IllegalArgumentException(
                                                        "eligible parent UUIDs must be unique");
                                            },
                                            LinkedHashMap::new));
            List<String> selectedParentIds =
                    selection.broad()
                            ? eligible.keySet().stream().sorted().toList()
                            : selection.parentIds().stream().sorted().toList();
            for (String parentId : selectedParentIds) {
                ExecutionScopeCodec.OutputKey output =
                        new ExecutionScopeCodec.OutputKey(
                                producer,
                                new ExecutionScopeCodec.ParentScope(target.key(), parentId));
                if (!redo && outputIndex.contains(output)) {
                    outcomes.skipped(output, RunResults.ScopeOutcomeType.SKIPPED_PRESENT);
                    continue;
                }
                PathObject parent = eligible.get(parentId);
                if (parent == null) {
                    outcomes.unresolvedParent(
                            target, producer, parentId, output, redo, outputIndex, view);
                    continue;
                }
                plannedInputs.add(
                        inputAdapter.planParentInput(
                                target,
                                source,
                                producer,
                                parentId,
                                NativeRoiGeometry.convert(parent.getROI())));
                sourceParents.add(parent);
            }
            if (selection.broad()) {
                outcomes.broadRedoClears(
                        target, producer, redo, outputIndex, closure, view, eligible.keySet());
                if (eligible.isEmpty() && !outcomes.hasProducer(producer, target.key())) {
                    if (!redo && !invokedModels.contains(step.parentModel())) {
                        RunResults.FailureDetail failure =
                                new RunResults.FailureDetail(
                                        RunResults.FailureType.PREREQUISITE,
                                        "Fill cannot resolve parents for "
                                                + step.model()
                                                + " without running prerequisite "
                                                + step.parentModel());
                        outcomes.blocked(producer, target.key(), failure);
                        throw new RecipeExecutor.BlockedPrerequisiteException(failure.message());
                    }
                    outcomes.noParents(producer, target.key());
                    inputAdapter.verifyEmptyStep(step, List.copyOf(sourceParents));
                    return false;
                }
            }
        }

        if (plannedInputs.isEmpty()) {
            inputAdapter.verifyEmptyStep(step, List.copyOf(sourceParents));
            return false;
        }
        List<? extends RecipeModelInputAdapter.PreparedInput> prepared;
        try {
            RecipeExecutor.AvailableSource availableSource = requiredSource(target.key());
            boolean removeOverlappingDuplicates =
                    plan.action() instanceof ActionSelectionCodec.Fill fill
                            ? fill.removeOverlappingSameClassDuplicates()
                            : ((ActionSelectionCodec.Redo) plan.action())
                                    .removeOverlappingSameClassDuplicates();
            prepared =
                    inputAdapter.prepareStep(
                            step,
                            List.copyOf(plannedInputs),
                            List.copyOf(sourceParents),
                            availableSource,
                            view.candidatePreservationPopulation(removeOverlappingDuplicates));
            projection.plan(prepared);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var detail =
                    new RunResults.FailureDetail(
                            RunResults.FailureType.CANCELLED, "Inference preparation interrupted");
            for (var input : plannedInputs)
                outcomes.cancelledBeforeAcceptance(input.output(), detail);
            var cancelled = new java.util.concurrent.CancellationException(detail.message());
            cancelled.initCause(interrupted);
            throw cancelled;
        } catch (IOException | InferenceException failure) {
            RunResults.FailureDetail detail = RecipeFailures.detail(failure);
            failPlannedInputs(plannedInputs, outcomes, detail);
            throw RecipeFailures.categorized(detail.type(), detail.message(), failure);
        } catch (RuntimeException failure) {
            RunResults.FailureDetail detail = RecipeFailures.detail(failure);
            failPlannedInputs(plannedInputs, outcomes, detail);
            throw failure;
        }
        try {
            requireProjectionFits(outputIndex, closure, projection);
        } catch (IllegalArgumentException failure) {
            RunResults.FailureDetail detail =
                    new RunResults.FailureDetail(
                            RunResults.FailureType.RESOURCE_LIMIT,
                            "generation-store projection exceeds the captured client limit: "
                                    + RecipeFailures.message(failure));
            failPreparedInputs(prepared, 0, outcomes, detail);
            throw RecipeFailures.categorized(detail.type(), detail.message(), failure);
        }
        for (int inputIndex = 0; inputIndex < prepared.size(); inputIndex++) {
            RecipeModelInputAdapter.PreparedInput input = prepared.get(inputIndex);
            final CandidateGeneration generation;
            try {
                generation = inputAdapter.execute(input, shouldStop);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                RunResults.FailureDetail failure =
                        new RunResults.FailureDetail(
                                RunResults.FailureType.CANCELLED,
                                "finite inference interrupted before acceptance");
                cancelPreparedInputs(prepared, inputIndex, outcomes, failure);
                java.util.concurrent.CancellationException cancelled =
                        new java.util.concurrent.CancellationException(failure.message());
                cancelled.initCause(interrupted);
                throw cancelled;
            } catch (ModelGatewayExecutor.AcceptedFiniteCallCancellation cancellation) {
                RunResults.FailureDetail failure =
                        new RunResults.FailureDetail(
                                RunResults.FailureType.CANCELLED,
                                RecipeFailures.message(cancellation));
                outcomes.cancelledAfterAcceptance(
                        input.plannedInput().output(), cancellation.execution(), failure);
                cancelPreparedInputs(prepared, inputIndex + 1, outcomes, failure);
                throw cancellation;
            } catch (ModelGatewayExecutor.AcceptedFiniteCallFailure accepted) {
                RunResults.FailureDetail failure =
                        RecipeFailures.accepted(accepted.originalFailure());
                if (failure.type() == RunResults.FailureType.CANCELLED) {
                    java.util.concurrent.CancellationException cancelled =
                            new java.util.concurrent.CancellationException(failure.message());
                    cancelled.initCause(accepted.originalFailure());
                    ModelGatewayExecutor.AcceptedFiniteCallCancellation cancellation =
                            new ModelGatewayExecutor.AcceptedFiniteCallCancellation(
                                    accepted.execution(), cancelled);
                    outcomes.cancelledAfterAcceptance(
                            input.plannedInput().output(), accepted.execution(), failure);
                    cancelPreparedInputs(prepared, inputIndex + 1, outcomes, failure);
                    throw cancellation;
                }
                outcomes.failedAfterAcceptance(
                        input.plannedInput().output(), accepted.execution(), failure);
                failPreparedInputs(prepared, inputIndex + 1, outcomes, failure);
                throw RecipeFailures.categorized(
                        failure.type(), failure.message(), accepted.originalFailure());
            } catch (java.util.concurrent.CancellationException cancellation) {
                RunResults.FailureDetail failure =
                        new RunResults.FailureDetail(
                                RunResults.FailureType.CANCELLED,
                                RecipeFailures.message(cancellation));
                cancelPreparedInputs(prepared, inputIndex, outcomes, failure);
                throw cancellation;
            } catch (RecipeFailures.Categorized failure) {
                failPreparedInputs(prepared, inputIndex, outcomes, failure.failure());
                throw failure;
            } catch (IOException | InferenceException failure) {
                RunResults.FailureDetail detail = RecipeFailures.detail(failure);
                failPreparedInputs(prepared, inputIndex, outcomes, detail);
                throw RecipeFailures.categorized(detail.type(), detail.message(), failure);
            } catch (RuntimeException failure) {
                RunResults.FailureDetail detail = RecipeFailures.detail(failure);
                failPreparedInputs(prepared, inputIndex, outcomes, detail);
                throw failure;
            }
            try {
                requireRunning(shouldStop);
                projection.complete(generation);
                generations.add(generation);
                view.addGeneration(generation);
                outcomes.generation(
                        generation,
                        redo
                                && (outputIndex.contains(generation.output())
                                        || (inputIndex == 0 && crossModeReplacement)));
            } catch (java.util.concurrent.CancellationException cancellation) {
                RunResults.FailureDetail failure =
                        new RunResults.FailureDetail(
                                RunResults.FailureType.CANCELLED,
                                RecipeFailures.message(cancellation));
                outcomes.cancelledAfterAcceptance(
                        input.plannedInput().output(), generation.execution(), failure);
                cancelPreparedInputs(prepared, inputIndex + 1, outcomes, failure);
                throw cancellation;
            } catch (RuntimeException failure) {
                RunResults.FailureDetail detail = RecipeFailures.localStaging(failure);
                if (!outcomes.containsOutput(input.plannedInput().output())) {
                    outcomes.failedAfterAcceptance(
                            input.plannedInput().output(), generation.execution(), detail);
                }
                failPreparedInputs(prepared, inputIndex + 1, outcomes, detail);
                throw RecipeFailures.categorized(detail.type(), detail.message(), failure);
            }
        }
        return true;
    }

    private static void failPlannedInputs(
            List<SourceInputPlan.PlannedModelInput> inputs,
            RecipeOutcomes.Tracker outcomes,
            RunResults.FailureDetail failure) {
        for (SourceInputPlan.PlannedModelInput input : inputs) {
            if (!outcomes.containsOutput(input.output())) {
                outcomes.failedBeforeAcceptance(input.output(), failure);
            }
        }
    }

    private static void failPreparedInputs(
            List<? extends RecipeModelInputAdapter.PreparedInput> inputs,
            int first,
            RecipeOutcomes.Tracker outcomes,
            RunResults.FailureDetail failure) {
        for (int index = first; index < inputs.size(); index++) {
            ExecutionScopeCodec.OutputKey output = inputs.get(index).plannedInput().output();
            if (!outcomes.containsOutput(output)) {
                outcomes.failedBeforeAcceptance(output, failure);
            }
        }
    }

    private static void cancelPreparedInputs(
            List<? extends RecipeModelInputAdapter.PreparedInput> inputs,
            int first,
            RecipeOutcomes.Tracker outcomes,
            RunResults.FailureDetail failure) {
        for (int index = first; index < inputs.size(); index++) {
            ExecutionScopeCodec.OutputKey output = inputs.get(index).plannedInput().output();
            if (!outcomes.containsOutput(output)) {
                outcomes.cancelledBeforeAcceptance(output, failure);
            }
        }
    }

    private void requireScriptCalibration() throws RecipeFailures.Categorized {
        var calibration = inputs.sourceRevision().pixelSource().calibration();
        try {
            ScriptCalibrationGate.requireSupported(calibration.mppX(), calibration.mppY());
        } catch (IllegalArgumentException failure) {
            throw RecipeFailures.categorized(
                    RunResults.FailureType.CALIBRATION,
                    "ordinary script calibration failed: " + RecipeFailures.message(failure),
                    failure);
        }
    }

    private void requireProjectionFits(
            OutputIndex outputIndex,
            Set<ExecutionScopeCodec.OutputKey> replacementClosure,
            RecipeStaging.Projection projection) {
        GenerationStoreCodec.GenerationStore current =
                ImageDataStateCodec.readGenerationStore(workingImage)
                        .orElseGet(GenerationStoreCodec::emptyStore);
        var retained =
                outputIndex.currentProvenance().stream()
                        .filter(provenance -> !replacementClosure.contains(provenance.output()))
                        .toList();
        ImageDataStateCodec.requireGenerationStoreProjectionFits(
                current,
                retained,
                projection.stagedStores(),
                projection.plannedReservations(),
                plan.clientBuild().limits());
    }

    private Map<ModuleRecipe.Step, Set<String>> protectedChildParents(
            List<ModuleRecipe.Step> steps,
            int scriptPosition,
            ExecutionScopeCodec.TargetRegion target,
            OutputIndex outputIndex,
            StagedRegionView view) {
        Map<ModuleRecipe.Step, Set<String>> result = new LinkedHashMap<>();
        for (int index = scriptPosition + 1; index < steps.size(); index++) {
            ModuleRecipe.Step child = steps.get(index);
            if (!child.isRegionGated()) {
                continue;
            }
            Set<String> eligible =
                    view.eligibleParents(plan.module().module().moduleRef(), child).stream()
                            .map(object -> object.getID().toString())
                            .collect(java.util.stream.Collectors.toSet());
            InferenceIdentityCodec.ModelStepIdentity producer =
                    new InferenceIdentityCodec.ModelStepIdentity(
                            plan.module().module().moduleRef(), child.model());
            Set<String> protectedIds = new LinkedHashSet<>();
            for (ExecutionScopeCodec.OutputKey output :
                    outputIndex.materialization(producer).currentOutputs()) {
                if (output.scope() instanceof ExecutionScopeCodec.ParentScope parent
                        && parent.region().equals(target.key())
                        && eligible.contains(parent.parentId())) {
                    protectedIds.add(parent.parentId());
                }
            }
            if (!protectedIds.isEmpty()) {
                result.put(child, Set.copyOf(protectedIds));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void requireProtectedChildrenRemainEligible(
            Map<ModuleRecipe.Step, Set<String>> protectedChildren,
            StagedRegionView view,
            String scriptFilename) {
        for (Map.Entry<ModuleRecipe.Step, Set<String>> entry : protectedChildren.entrySet()) {
            Set<String> after =
                    view
                            .eligibleParents(plan.module().module().moduleRef(), entry.getKey())
                            .stream()
                            .map(object -> object.getID().toString())
                            .collect(java.util.stream.Collectors.toSet());
            if (!after.containsAll(entry.getValue())) {
                throw new RecipeExecutor.FillRequiresRedoException(
                        "ordinary script "
                                + scriptFilename
                                + " would invalidate existing child output for "
                                + entry.getKey().model());
            }
        }
    }

    private RecipeExecutor.AvailableSource requiredSource(ExecutionScopeCodec.RegionKey target) {
        RecipeExecutor.SourceProvision provision = inputs.sourceProvisions().get(target);
        if (!(provision instanceof RecipeExecutor.AvailableSource available)) {
            throw new RecipeExecutor.BlockedPrerequisiteException(
                    "resolved model input has no image source");
        }
        return available;
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("recipe execution cancelled");
        }
    }

    /** Complete target-local work observed before a transaction-level failure discarded it. */
    static final class TargetTransactionFailure extends Exception {

        private final List<RunResults.ScopeWorkResult> outcomes;
        private final RunResults.FailureDetail failure;

        private TargetTransactionFailure(
                List<RunResults.ScopeWorkResult> outcomes,
                RunResults.FailureDetail failure,
                Exception cause) {
            super(Objects.requireNonNull(cause, "cause"));
            this.outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        List<RunResults.ScopeWorkResult> outcomes() {
            return outcomes;
        }

        RunResults.FailureDetail failure() {
            return failure;
        }

        Exception originalCause() {
            return (Exception) getCause();
        }
    }

    /** Complete target-local work observed before cancellation interrupted staging. */
    static final class TargetTransactionCancelled
            extends java.util.concurrent.CancellationException {

        private final List<RunResults.ScopeWorkResult> outcomes;

        TargetTransactionCancelled(
                List<RunResults.ScopeWorkResult> outcomes,
                java.util.concurrent.CancellationException cause) {
            super(RecipeFailures.message(cause));
            this.outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
            initCause(Objects.requireNonNull(cause, "cause"));
        }

        List<RunResults.ScopeWorkResult> outcomes() {
            return outcomes;
        }
    }
}
