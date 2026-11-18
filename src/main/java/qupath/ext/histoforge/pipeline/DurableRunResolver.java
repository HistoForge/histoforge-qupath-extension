package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import qupath.ext.histoforge.client.DurableModelRunExecutor.CompleteResultReader;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelRunObservation;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.ModelRunPublicationCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ModelRunStatusCodec;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes;
import qupath.ext.histoforge.large.RunImageSources;
import qupath.ext.histoforge.modules.ModuleSnapshot;

/** Resolves one durable request through initial submission or one explicit recovery attempt. */
final class DurableRunResolver {

    /** Terminal durable work that remains eligible for one explicit replacement run. */
    static final class RecoverableTerminalException extends Exception {

        private final ModelRunStatusCodec.Phase phase;
        private final Optional<GatewayErrorCodec.Error> failure;

        RecoverableTerminalException(
                ModelRunStatusCodec.Phase phase,
                Optional<GatewayErrorCodec.Error> failure,
                String message) {
            super(message);
            this.phase = Objects.requireNonNull(phase, "phase");
            this.failure = Objects.requireNonNull(failure, "failure");
            if (phase != ModelRunStatusCodec.Phase.PARTIAL
                    && phase != ModelRunStatusCodec.Phase.TIMED_OUT
                    && !(phase == ModelRunStatusCodec.Phase.FAILED && failure.isPresent())) {
                throw new IllegalArgumentException(
                        "only partial, timed-out or reported image-provisioning failures are"
                                + " replaceable");
            }
            if (phase == ModelRunStatusCodec.Phase.TIMED_OUT && failure.isPresent()) {
                throw new IllegalArgumentException("TIMED_OUT cannot carry failure");
            }
        }

        ModelRunStatusCodec.Phase phase() {
            return phase;
        }

        Optional<GatewayErrorCodec.Error> failure() {
            return failure;
        }
    }

    /** Submit one request whose local journal reference is already durable. */
    @FunctionalInterface
    interface RunSubmission {
        ModelRunObservation.Terminal execute(
                ModelRunRequestCodec.ModelRunRequest request,
                ModuleSnapshot.ModelSnapshot model,
                BooleanSupplier shouldStop,
                CompleteResultReader completeResults,
                DurableStepJournal journal,
                int shardIndex)
                throws IOException, InterruptedException, InferenceException;
    }

    /** Observe one retained request without replaying its start operation. */
    @FunctionalInterface
    interface RetainedRunObservation {
        ModelRunObservation.Terminal await(
                ModelRunRequestCodec.ModelRunRequest request,
                ModuleSnapshot.ModelSnapshot model,
                BooleanSupplier shouldStop,
                CompleteResultReader completeResults,
                DurableStepJournal journal,
                int shardIndex)
                throws IOException, InterruptedException, InferenceException;
    }

    private final DurableStepJournal journal;
    private final RunSubmission submissions;
    private final RetainedRunObservation retainedObservations;
    private final Supplier<String> modelRunIds;
    private final Set<String> issuedModelRunIds = new LinkedHashSet<>();

    DurableRunResolver(
            LargeActionHandleStore.ResumableAction action,
            DurableStepJournal journal,
            RunSubmission submissions,
            RetainedRunObservation retainedObservations,
            Supplier<String> modelRunIds) {
        LargeActionHandleStore.ResumableAction retained = Objects.requireNonNull(action, "action");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.retainedObservations = retainedObservations;
        this.modelRunIds = Objects.requireNonNull(modelRunIds, "modelRunIds");
        retained.authorizedRuns()
                .forEach(value -> issuedModelRunIds.add(value.request().modelRunId()));
    }

    boolean recoveryEnabled() {
        return retainedObservations != null;
    }

    String nextModelRunId() {
        String value = CanonicalCodec.requireCanonicalUuid(modelRunIds.get(), "model_run_id");
        if (!issuedModelRunIds.add(value)) {
            throw new IllegalArgumentException(
                    "durable action model-run IDs must be globally unique");
        }
        return value;
    }

    DurableRecipeModelInputAdapter.RecoveredRun submitInitial(
            String stepId,
            DurableRequestSharder.PlannedShard shard,
            ModuleSnapshot.ModelSnapshot model,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    RecipeFailures.Categorized {
        ModelRunObservation.Terminal terminal =
                submissions.execute(
                        shard.request(),
                        model,
                        shouldStop,
                        CompleteModelResults.retaining(
                                journal,
                                stepId,
                                shard.shardIndex(),
                                shard.request(),
                                model,
                                shouldStop),
                        journal,
                        shard.shardIndex());
        recordTerminalManifest(stepId, shard.shardIndex(), terminal);
        return completed(stepId, shard.shardIndex(), terminal);
    }

    DurableRecipeModelInputAdapter.RecoveredRun resumeRetained(
            String stepId,
            DurableRequestSharder.PlannedShard shard,
            ModuleSnapshot.ModelSnapshot model,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    RecipeFailures.Categorized {
        if (retainedObservations == null) {
            throw new IllegalStateException("retained durable work requires explicit recovery");
        }
        var local =
                CompleteModelResults.readRetained(
                        journal.currentAction(),
                        journal.authorizedRun(stepId, shard.shardIndex()),
                        journal.resultFiles(),
                        shouldStop);
        if (local.isPresent()) {
            CompleteModelResults.recordLocalCompletion(journal, local.orElseThrow());
            return local.orElseThrow();
        }
        ModelRunObservation.Terminal terminal;
        try {
            terminal =
                    retainedObservations.await(
                            shard.request(),
                            model,
                            shouldStop,
                            CompleteModelResults.retaining(
                                    journal,
                                    stepId,
                                    shard.shardIndex(),
                                    shard.request(),
                                    model,
                                    shouldStop),
                            journal,
                            shard.shardIndex());
        } catch (InferenceException missing) {
            if (!requiresFreshReplacement(missing)) {
                throw missing;
            }
            return submitReplacement(stepId, shard, model, Optional.empty(), shouldStop);
        }
        requireRetainedManifest(stepId, shard.shardIndex(), terminal);
        if (terminal.complete()) {
            return completed(stepId, shard.shardIndex(), terminal);
        }
        if (terminal.status().phase() == ModelRunStatusCodec.Phase.PARTIAL) {
            return submitReplacement(
                    stepId,
                    shard,
                    model,
                    Optional.of(
                            new ModelRunRequestCodec.ModelRunResumeSource(
                                    shard.request().modelRunId(),
                                    terminal.manifest().orElseThrow().manifestDigest())),
                    shouldStop);
        }
        if (terminal.status().phase() == ModelRunStatusCodec.Phase.TIMED_OUT
                || imageProvisioningFailed(stepId, shard.shardIndex(), terminal)) {
            return submitReplacement(stepId, shard, model, Optional.empty(), shouldStop);
        }
        return completed(stepId, shard.shardIndex(), terminal);
    }

    private DurableRecipeModelInputAdapter.RecoveredRun submitReplacement(
            String stepId,
            DurableRequestSharder.PlannedShard shard,
            ModuleSnapshot.ModelSnapshot model,
            Optional<ModelRunRequestCodec.ModelRunResumeSource> resumeFrom,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    RecipeFailures.Categorized {
        if (shouldStop.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException(
                    "durable recipe recovery cancelled before replacement submission");
        }
        ModelRunRequestCodec.ModelRunRequest prior = shard.request();
        ModelRunRequestCodec.ModelRunRequest replacement =
                new ModelRunRequestCodec.ModelRunRequest(
                        prior.schemaVersion(),
                        prior.actionId(),
                        nextModelRunId(),
                        prior.producer(),
                        prior.expectedBackend(),
                        prior.expectedProfile(),
                        prior.inputs(),
                        prior.resultBudget(),
                        Objects.requireNonNull(resumeFrom, "resumeFrom"),
                        prior.haloPxOverride());
        journal.replaceRun(stepId, shard.shardIndex(), replacement);
        ModelRunObservation.Terminal terminal =
                submissions.execute(
                        replacement,
                        model,
                        shouldStop,
                        CompleteModelResults.retaining(
                                journal,
                                stepId,
                                shard.shardIndex(),
                                replacement,
                                model,
                                shouldStop),
                        journal,
                        shard.shardIndex());
        recordTerminalManifest(stepId, shard.shardIndex(), terminal);
        return completed(stepId, shard.shardIndex(), terminal);
    }

    private DurableRecipeModelInputAdapter.RecoveredRun completed(
            String stepId, int shardIndex, ModelRunObservation.Terminal terminal)
            throws InferenceException, RecipeFailures.Categorized {
        if (!terminal.complete())
            rejectIncompleteTerminal(
                    terminal, imageProvisioningFailed(stepId, shardIndex, terminal));
        return new DurableRecipeModelInputAdapter.RecoveredRun(
                journal.authorizedRun(stepId, shardIndex),
                terminal.manifest().orElseThrow(),
                CompleteModelResults.retainedInputs(
                        journal.currentAction().handle(), terminal.manifest().orElseThrow(),
                        terminal.completedInputs(), journal.resultFiles()));
    }

    private void requireRetainedManifest(
            String stepId, int shardIndex, ModelRunObservation.Terminal terminal)
            throws IOException {
        LargeActionTypes.ModelRunRef retained = retainedRun(stepId, shardIndex);
        Optional<String> observed =
                terminal.manifest().map(ModelRunPublicationCodec.Manifest::manifestDigest);
        if (retained.resultManifestDigest().isPresent()
                && !retained.resultManifestDigest().equals(observed)) {
            throw new IllegalStateException(
                    "observed durable terminal manifest differs from the retained handle");
        }
        if (retained.resultManifestDigest().isEmpty() && observed.isPresent()) {
            journal.recordManifest(stepId, shardIndex, observed.orElseThrow());
        }
    }

    private void recordTerminalManifest(
            String stepId, int shardIndex, ModelRunObservation.Terminal terminal)
            throws IOException {
        if (terminal.manifest().isPresent()) {
            journal.recordManifest(
                    stepId, shardIndex, terminal.manifest().orElseThrow().manifestDigest());
        }
    }

    private LargeActionTypes.ModelRunRef retainedRun(String stepId, int shardIndex) {
        LargeActionTypes.StepRunRef step =
                journal.currentAction().handle().steps().stream()
                        .filter(value -> value.stepId().equals(stepId))
                        .findFirst()
                        .orElseThrow();
        if (shardIndex >= step.runs().size()
                || step.runs().get(shardIndex).shardIndex() != shardIndex) {
            throw new IllegalStateException("retained durable shard no longer exists");
        }
        return step.runs().get(shardIndex);
    }

    private static boolean requiresFreshReplacement(InferenceException failure) {
        return failure.gatewayError()
                .map(GatewayErrorCodec.Error::code)
                .filter(
                        code ->
                                code == GatewayErrorCodec.Code.MODEL_RUN_NOT_FOUND
                                        || code == GatewayErrorCodec.Code.MODEL_RUN_EXPIRED)
                .isPresent();
    }

    private boolean imageProvisioningFailed(
            String step, int shard, ModelRunObservation.Terminal terminal) {
        return terminal.status().phase() == ModelRunStatusCodec.Phase.FAILED
                && journal.run(step, shard)
                        .imageSources()
                        .flatMap(RunImageSources::failureReport)
                        .filter(report -> terminal.status().failure().equals(Optional.of(report)))
                        .isPresent();
    }

    private static void rejectIncompleteTerminal(
            ModelRunObservation.Terminal terminal, boolean imageFailure)
            throws InferenceException, RecipeFailures.Categorized {
        String message =
                "durable model run ended in "
                        + terminal.status().phase()
                        + " before step completion";
        switch (terminal.status().phase()) {
            case CANCELLED -> throw new java.util.concurrent.CancellationException(message);
            case PARTIAL, TIMED_OUT -> {
                var failure = terminal.status().failure();
                RunResults.FailureType type = RunResults.FailureType.TIMEOUT;
                if (failure.isPresent()) {
                    var codedFailure = new InferenceException(0, failure.orElseThrow());
                    type = RecipeFailures.accepted(codedFailure).type();
                    message += ": " + codedFailure.getMessage();
                    message +=
                            switch (failure.orElseThrow().code()) {
                                case INSUFFICIENT_CONTEXT, RESULT_LIMIT_EXCEEDED ->
                                        ". Resume uses the same captured settings. Changing the"
                                                + " halo or output limits requires a new action.";
                                default -> "";
                            };
                }
                // Preserve recovery eligibility alongside the actual backend failure cause.
                throw RecipeFailures.categorized(
                        type,
                        message,
                        new RecoverableTerminalException(
                                terminal.status().phase(), failure, message));
            }
            case FAILED -> {
                var coded = new InferenceException(0, terminal.status().failure().orElseThrow());
                if (!imageFailure) throw coded;
                throw RecipeFailures.categorized(
                        RecipeFailures.accepted(coded).type(),
                        coded.getMessage(),
                        new RecoverableTerminalException(
                                terminal.status().phase(),
                                terminal.status().failure(),
                                coded.getMessage()));
            }
            case COMPLETE ->
                    throw new IllegalArgumentException(
                            "complete durable model run cannot be rejected");
            case QUEUED, RUNNING ->
                    throw new IllegalArgumentException(
                            "active durable model run cannot be a terminal observation");
        }
    }
}
