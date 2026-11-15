package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.ModelRunStatusCodec;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.lib.projects.ProjectImageEntry;

/** Explicit inspection, resume, and abandonment of persisted per-target recovery handles. */
public final class PersistedActionRecovery implements AutoCloseable {

    private final LargeActionHandleStore handles;
    private final Optional<RecoverableActionRecovery> executor;
    private final Optional<ImageMetricsExecutor> metricsExecutor;
    private final Optional<RecoveryCloudServices> cloud;

    /** Create a non-dispatching catalog that can inspect or abandon retained handles. */
    public PersistedActionRecovery(LargeActionHandleStore handles) {
        this.handles = Objects.requireNonNull(handles, "handles");
        executor = Optional.empty();
        metricsExecutor = Optional.empty();
        cloud = Optional.empty();
    }

    PersistedActionRecovery(
            LargeActionHandleStore handles,
            RecoverableImageActionExecutor executor,
            ImageMetricsExecutor metricsExecutor) {
        this.handles = Objects.requireNonNull(handles, "handles");
        this.executor = Optional.of(Objects.requireNonNull(executor, "executor").recovery());
        this.metricsExecutor =
                Optional.of(Objects.requireNonNull(metricsExecutor, "metricsExecutor"));
        cloud = Optional.empty();
    }

    /**
     * Recover from the saved module, requests and results. Cloud services are opened only when
     * unfinished work or missing result files require them.
     */
    public PersistedActionRecovery(
            LargeActionHandleStore handles,
            ImageActionRegistry registry,
            ImageLifecycle lifecycle,
            ClientBuildIdentity build,
            Supplier<GatewayTransport> connection,
            String deploymentId,
            int writerThreads,
            MetricsEngine metrics,
            Consumer<ModelRunStatusCodec.Status> onStatus,
            Consumer<ImageSourceProgress> onSourceProgress) {
        this.handles = Objects.requireNonNull(handles);
        var services =
                new RecoveryCloudServices(
                        connection,
                        deploymentId,
                        build,
                        handles,
                        writerThreads,
                        onStatus,
                        onSourceProgress);
        cloud = Optional.of(services);
        executor =
                Optional.of(
                        new RecoverableActionRecovery(
                                registry,
                                lifecycle,
                                handles,
                                services::sources,
                                new LargeActionCommitCoordinator(handles, lifecycle),
                                (action, plan, inputs, image, delivery, stop) ->
                                        DurableRecipeDispatcher.resume(
                                                action,
                                                plan,
                                                inputs,
                                                image,
                                                handles,
                                                services::runs,
                                                delivery,
                                                stop),
                                (action, stop) ->
                                        LargeActionRecipeReconstructor.prepare(
                                                action, handles, services::runs, stop),
                                new RecoverableActionAttemptResolver(handles)));
        metricsExecutor = Optional.of(new ImageMetricsExecutor(registry, lifecycle, metrics));
    }

    /** Close only services actually opened; cleanup never needs a cloud connection. */
    @Override
    public void close() {
        cloud.ifPresent(RecoveryCloudServices::close);
        try {
            handles.cleanupSavedFiles();
        } catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger(PersistedActionRecovery.class)
                    .warn("Could not remove saved analysis recovery files", failure);
        }
    }

    /** Stable display and lookup identity for one retained nonterminal target handle. */
    public record Item(
            String actionId,
            String handleGenerationId,
            String imageId,
            String moduleRef,
            ModuleContentIdentity module,
            String deploymentId,
            ClientBuildIdentity clientBuild,
            boolean wholeSlide,
            LargeActionPhase phase,
            Instant createdAt) {
        public Item {
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            handleGenerationId =
                    CanonicalCodec.requireCanonicalUuid(handleGenerationId, "handle_generation_id");
            imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
            moduleRef = CanonicalCodec.requireNonblank(moduleRef, "module_ref");
            module = Objects.requireNonNull(module, "module");
            if (!module.moduleRef().toString().equals(moduleRef)) {
                throw new IllegalArgumentException("module_ref does not match module identity");
            }
            deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
            clientBuild = Objects.requireNonNull(clientBuild, "clientBuild");
            phase = Objects.requireNonNull(phase, "phase");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            if (phase.terminal()) {
                throw new IllegalArgumentException("recovery item must be nonterminal");
            }
        }
    }

    /** One explicit recovery attempt either completed or retained a new paused owner. */
    public sealed interface Attempt permits Completed, Paused {}

    /** Terminal persisted-state truth for the recovered target. */
    public record Completed(RunResults.ImageRunResult result) implements Attempt {
        public Completed {
            result = Objects.requireNonNull(result, "result");
        }
    }

    /** Single-use same-process ownership returned when explicit recovery remains incomplete. */
    public static final class Paused implements Attempt {

        private final PersistedActionRecovery owner;
        private final RecoverableActionAttempt.Suspended delegate;

        private Paused(PersistedActionRecovery owner, RecoverableActionAttempt.Suspended delegate) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        public Item item() {
            return PersistedActionRecovery.item(delegate.lastObservedHandle());
        }

        public String failureMessage() {
            Throwable failure = delegate.failure();
            String message = failure.getMessage();
            return message == null || message.isBlank()
                    ? failure.getClass().getSimpleName()
                    : message;
        }
    }

    /** List retained nonterminal handles without dispatching or mutating any of them. */
    public List<Item> unresolved() throws IOException {
        return handles.list().stream()
                .filter(handle -> !handle.phase().terminal())
                .map(PersistedActionRecovery::item)
                .toList();
    }

    /** Explicitly resume one persisted handle after independently reloading its current state. */
    public Attempt resume(
            Item item, ProjectImageEntry<BufferedImage> entry, BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        LargeActionHandle current = requireCurrent(item);
        RecoverableActionAttempt attempt =
                executor()
                        .resume(
                                current.actionId(),
                                current.handleGenerationId(),
                                Objects.requireNonNull(entry, "entry"),
                                Objects.requireNonNull(shouldStop, "shouldStop"));
        return wrap(attempt, current, entry, shouldStop);
    }

    /** Explicitly retry one same-process paused recovery attempt. */
    public Attempt resume(
            Paused paused, ProjectImageEntry<BufferedImage> entry, BooleanSupplier shouldStop)
            throws IOException, InferenceException {
        Paused current = requireOwner(paused);
        LargeActionHandle handle = current.delegate.lastObservedHandle();
        return wrap(
                executor()
                        .resume(
                                current.delegate,
                                Objects.requireNonNull(entry, "entry"),
                                Objects.requireNonNull(shouldStop, "shouldStop")),
                handle,
                entry,
                shouldStop);
    }

    /** Atomically abandon one persisted handle, including a handle from an older client build. */
    public void abandon(Item item) throws IOException {
        LargeActionHandle current = requireCurrent(item);
        handles.abandon(current.actionId(), current.handleGenerationId());
    }

    /** Retry durable abandonment of a same-process paused recovery attempt. */
    public void abandon(Paused paused) throws IOException {
        requireOwner(paused).delegate.abandon();
    }

    private LargeActionHandle requireCurrent(Item supplied) throws IOException {
        Item expected = Objects.requireNonNull(supplied, "item");
        LargeActionHandle handle = handles.load(expected.actionId(), expected.handleGenerationId());
        if (handle.phase().terminal() || !item(handle).equals(expected)) {
            throw new IllegalStateException("persisted recovery item is no longer current");
        }
        return handle;
    }

    private Paused requireOwner(Paused paused) {
        Paused value = Objects.requireNonNull(paused, "paused");
        if (value.owner != this) {
            throw new IllegalArgumentException("paused recovery belongs to another owner");
        }
        return value;
    }

    private Attempt wrap(
            RecoverableActionAttempt attempt,
            LargeActionHandle handle,
            ProjectImageEntry<BufferedImage> entry,
            BooleanSupplier stop) {
        return switch (Objects.requireNonNull(attempt, "attempt")) {
            case RecoverableActionAttempt.Completed completed ->
                    new Completed(finishMetrics(completed, handle, entry, stop));
            case RecoverableActionAttempt.Suspended suspended -> new Paused(this, suspended);
        };
    }

    private RunResults.ImageRunResult finishMetrics(
            RecoverableActionAttempt.Completed completed,
            LargeActionHandle handle,
            ProjectImageEntry<BufferedImage> entry,
            BooleanSupplier stop) {
        var models = completed.result();
        var targets =
                List.of(
                        new ImageActionAggregation.TargetResult(
                                List.of(handle.target()),
                                models,
                                completed.automaticContinuationAllowed()));
        if (handle.module().metrics().isEmpty()
                || !ImageActionAggregation.allowsAutomaticContinuation(targets)
                || models.outcome() == RunResults.ImageOutcomeType.FAILED
                || models.outcome() == RunResults.ImageOutcomeType.CONFLICT
                || models.outcome() == RunResults.ImageOutcomeType.CANCELLED) {
            return models;
        }
        // A retained handle owns one exact target. Metrics recapture its saved hierarchy, not
        // the discarded working image or an inference result that would need redispatching.
        var work =
                switch (handle.action()) {
                    case ActionSelectionCodec.Fill fill -> fill.work();
                    case ActionSelectionCodec.Redo redo -> redo.work();
                    case ActionSelectionCodec.Metrics ignored ->
                            throw new IllegalStateException("Metrics has no recovery handle");
                };
        var regions =
                switch (work) {
                    case ActionSelectionCodec.AllModels all -> all.regions();
                    case ActionSelectionCodec.SelectedModels selected -> selected.regions();
                };
        var source =
                new ImageActionExecutor.Request(
                        handle.actionId(),
                        handle.clientBuild(),
                        entry,
                        handle.module(),
                        handle.action());
        var result =
                metricsExecutor
                        .orElseThrow()
                        .execute(
                                ImageMetricsExecutor.request(source, regions),
                                stop,
                                handle.selectionRegions());
        return ImageActionAggregation.reduce(handle.imageId(), targets, Optional.of(result));
    }

    private RecoverableActionRecovery executor() {
        return executor.orElseThrow(
                () ->
                        new IllegalStateException(
                                "this recovery catalog was opened without an execution owner"));
    }

    /** Capture the lookup identity used by explicit recovery from the current saved record. */
    public static Item item(LargeActionHandle handle) {
        LargeActionHandle value = Objects.requireNonNull(handle, "handle");
        ModuleContentIdentity module = value.module().module();
        return new Item(
                value.actionId(),
                value.handleGenerationId(),
                value.imageId(),
                module.moduleRef().toString(),
                module,
                value.module().deploymentId(),
                value.clientBuild(),
                value.target().key() instanceof ExecutionScopeCodec.Slide,
                value.phase(),
                value.createdAt());
    }
}
