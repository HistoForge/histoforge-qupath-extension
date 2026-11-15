package qupath.ext.histoforge.ui.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import qupath.ext.histoforge.client.DeploymentStore;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelRunObservation;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.modules.ModuleActivationValidator;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.pipeline.ImageActionAttempt;
import qupath.ext.histoforge.pipeline.ImageActionExecutor;
import qupath.ext.histoforge.pipeline.ImageActionRegistry;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.pipeline.PersistedActionRecovery;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.platform.ClientBuildIdentityProvider;
import qupath.ext.histoforge.ui.project.PrivateGatewayRecoveryFixture.GuiSession;
import qupath.ext.histoforge.ui.project.PrivateGatewayRecoveryFixture.LoadedProject;
import qupath.lib.projects.ProjectImageEntry;

/** Opt-in real gateway proof of abrupt loss, new-process recovery, and abandonment. */
public final class PrivateGatewayRecoveryQualification {

    private static final String MODULE_REF = "histoforge/tubule_semantic";
    private static final String MODULE_COMMIT = "9cddd5431d8f2c02f559c28bc264ce9054c26e9c";
    private static final int EXPECTED_HALT_EXIT = 86;
    private static final int WATCHER_FAILURE_EXIT = 87;
    private static final Duration WATCH_DEADLINE = Duration.ofMinutes(20);

    private PrivateGatewayRecoveryQualification() {}

    /** Run exactly one process boundary; interruption modes terminate through Runtime.halt(86). */
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "expected MODE CREDENTIAL SOURCE EXPECTED_SOURCE_SHA256 WORKSPACE");
        }
        String mode = args[0];
        Path credential = Path.of(args[1]).toAbsolutePath().normalize();
        Path source = Path.of(args[2]).toAbsolutePath().normalize();
        String sourceSha256 = CanonicalCodec.requireSha256(args[3], "expected_source_sha256");
        Path root = Path.of(args[4]).toAbsolutePath().normalize();

        switch (mode) {
            case "prepare" -> PrivateGatewayRecoveryFixture.prepare(source, sourceSha256, root);
            case "interrupt-resume", "interrupt-abandon" -> {
                requireWorkspace(root);
                PrivateGatewayRecoveryFixture.isolateUserState(root, credential);
                executeUntilAbruptHalt(mode, root);
            }
            case "resume" -> {
                requireWorkspace(root);
                PrivateGatewayRecoveryFixture.isolateUserState(root, credential);
                resume(root);
            }
            case "abandon" -> {
                requireWorkspace(root);
                PrivateGatewayRecoveryFixture.isolateUserState(root, credential);
                abandon(root);
            }
            default ->
                    throw new IllegalArgumentException("unsupported qualification mode: " + mode);
        }
    }

    private static void executeUntilAbruptHalt(String operation, Path root) throws Exception {
        long started = System.nanoTime();
        ClientBuildIdentity build = ClientBuildIdentityProvider.capture();
        try (GuiSession gui = PrivateGatewayRecoveryFixture.openGui(root)) {
            ProjectImageEntry<BufferedImage> entry = gui.loaded().entry();
            JsonObject hierarchyBefore =
                    PrivateGatewayRecoveryFixture.hierarchySummary(entry, build);
            GatewayContext gateway = captureGateway();
            LargeActionHandleStore handles = handles(gui.loaded(), build);
            String actionId = UUID.randomUUID().toString();
            ActionSelectionCodec.ImageAction action = wholeSlideRedo();
            ModuleSnapshot module =
                    ModuleActivationValidator.activate(
                            gateway.capabilities().deploymentId(), gateway.module(), action);
            ImageActionExecutor.Request request =
                    new ImageActionExecutor.Request(actionId, build, entry, module, action);

            ProjectActionRuntimeFactory factory =
                    new ProjectActionRuntimeFactory(
                            gateway::transport,
                            new ImageActionRegistry(),
                            new ImageLifecycle(gui.qupath()),
                            new MetricsEngine());
            Thread watcher =
                    startWatcher(
                            operation,
                            root,
                            started,
                            actionId,
                            build,
                            entry,
                            hierarchyBefore,
                            handles,
                            gateway);
            try (ProjectActionRuntime runtime =
                    factory.open(gateway.capabilities(), build, handles)) {
                ImageActionAttempt result = runtime.coordinator().execute(request, () -> false);
                watcher.interrupt();
                JsonObject evidence = PrivateGatewayRecoveryFixture.baseEvidence(operation, build);
                evidence.addProperty("success", false);
                evidence.addProperty(
                        "failure",
                        "action returned before the qualification process was abruptly halted");
                evidence.addProperty("returned_attempt", result.getClass().getSimpleName());
                evidence.add("hierarchy_before", hierarchyBefore);
                evidence.addProperty(
                        "elapsed_seconds", PrivateGatewayRecoveryFixture.elapsedSeconds(started));
                PrivateGatewayRecoveryFixture.writeEvidence(
                        PrivateGatewayRecoveryFixture.evidencePath(root, operation), evidence);
                throw new IllegalStateException(
                        "real action returned before abrupt interruption was observed");
            }
        }
    }

    private static Thread startWatcher(
            String operation,
            Path root,
            long started,
            String actionId,
            ClientBuildIdentity build,
            ProjectImageEntry<BufferedImage> entry,
            JsonObject hierarchyBefore,
            LargeActionHandleStore handles,
            GatewayContext gateway) {
        Thread watcher =
                Thread.ofPlatform()
                        .daemon(true)
                        .name("histoforge-phase7-abrupt-interruption")
                        .unstarted(
                                () -> {
                                    try {
                                        observeThenHalt(
                                                operation,
                                                root,
                                                started,
                                                actionId,
                                                build,
                                                entry,
                                                hierarchyBefore,
                                                handles,
                                                gateway);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    } catch (Throwable failure) {
                                        try {
                                            JsonObject evidence =
                                                    PrivateGatewayRecoveryFixture.baseEvidence(
                                                            operation, build);
                                            evidence.addProperty("success", false);
                                            evidence.addProperty(
                                                    "watcher_failure_type",
                                                    failure.getClass().getName());
                                            evidence.addProperty(
                                                    "watcher_failure_message",
                                                    safeMessage(failure, root));
                                            evidence.addProperty(
                                                    "elapsed_seconds",
                                                    PrivateGatewayRecoveryFixture.elapsedSeconds(
                                                            started));
                                            PrivateGatewayRecoveryFixture.writeEvidence(
                                                    PrivateGatewayRecoveryFixture.evidencePath(
                                                            root, operation),
                                                    evidence);
                                        } catch (Throwable evidenceFailure) {
                                            evidenceFailure.printStackTrace(System.err);
                                        }
                                        Runtime.getRuntime().halt(WATCHER_FAILURE_EXIT);
                                    }
                                });
        watcher.start();
        return watcher;
    }

    private static void observeThenHalt(
            String operation,
            Path root,
            long started,
            String actionId,
            ClientBuildIdentity build,
            ProjectImageEntry<BufferedImage> entry,
            JsonObject hierarchyBefore,
            LargeActionHandleStore handles,
            GatewayContext gateway)
            throws Exception {
        long deadline = System.nanoTime() + WATCH_DEADLINE.toNanos();
        DurableModelRunExecutor observer =
                new DurableModelRunExecutor(gateway.transport(), gateway.capabilities());
        while (System.nanoTime() < deadline) {
            Optional<LargeActionHandle> candidate =
                    handles.list().stream()
                            .filter(handle -> handle.actionId().equals(actionId))
                            .filter(handle -> !handle.phase().terminal())
                            .findFirst();
            if (candidate.isPresent()) {
                LargeActionHandle handle = candidate.orElseThrow();
                if (handle.source().flatMap(source -> source.sealedArtifact()).isPresent()
                        && !handle.steps().isEmpty()) {
                    LargeActionHandleStore.ResumableAction resumable =
                            handles.requireResumable(
                                    handle.actionId(), handle.handleGenerationId());
                    for (LargeActionHandleStore.AuthorizedModelRun run :
                            resumable.authorizedRuns()) {
                        ModuleSnapshot.ModelSnapshot model =
                                handle.module().activatedModels().get(run.stepId());
                        var adopted =
                                observer.adopt(
                                        run.request(),
                                        model.capabilities(),
                                        model.largeRegionProfile(),
                                        model.output().emittedClasses());
                        final ModelRunObservation observation;
                        try {
                            observation = observer.poll(adopted);
                        } catch (InferenceException notYetAccepted) {
                            if (notYetAccepted
                                    .gatewayError()
                                    .map(GatewayErrorCodec.Error::code)
                                    .filter(
                                            code ->
                                                    code
                                                            == GatewayErrorCodec.Code
                                                                    .MODEL_RUN_NOT_FOUND)
                                    .isPresent()) {
                                continue;
                            }
                            throw notYetAccepted;
                        }
                        LargeActionHandle latest =
                                handles.load(handle.actionId(), handle.handleGenerationId());
                        PrivateGatewayRecoveryFixture.require(
                                !latest.phase().terminal(),
                                "action became terminal before abrupt halt");
                        JsonObject hierarchyAtHalt =
                                PrivateGatewayRecoveryFixture.hierarchySummary(entry, build);
                        PrivateGatewayRecoveryFixture.require(
                                hierarchyAtHalt.equals(hierarchyBefore),
                                "project hierarchy changed before durable action commit");

                        JsonObject evidence =
                                PrivateGatewayRecoveryFixture.baseEvidence(operation, build);
                        evidence.addProperty("success", true);
                        evidence.addProperty("expected_process_exit", EXPECTED_HALT_EXIT);
                        evidence.addProperty("action_id", latest.actionId());
                        evidence.addProperty("handle_generation_id", latest.handleGenerationId());
                        evidence.addProperty("handle_phase_at_halt", latest.phase().name());
                        evidence.addProperty(
                                "source_artifact_id", latest.source().orElseThrow().artifactId());
                        evidence.addProperty(
                                "source_start_request_digest",
                                latest.source().orElseThrow().startRequestFile().sha256());
                        evidence.addProperty("model_step_id", run.stepId());
                        evidence.addProperty("model_run_id", run.request().modelRunId());
                        evidence.addProperty(
                                "model_run_request_digest",
                                ModelRunRequestCodec.requestDigest(run.request()));
                        evidence.addProperty(
                                "backend_phase_at_halt", observation.status().phase().name());
                        evidence.addProperty(
                                "completed_unit_count", observation.status().completedUnitCount());
                        evidence.addProperty(
                                "total_unit_count", observation.status().totalUnitCount());
                        evidence.add("gateway", gatewaySummary(gateway));
                        evidence.add("module", moduleSummary(gateway.module()));
                        evidence.add("hierarchy_before", hierarchyBefore);
                        evidence.add("hierarchy_at_halt", hierarchyAtHalt);
                        evidence.addProperty(
                                "elapsed_seconds",
                                PrivateGatewayRecoveryFixture.elapsedSeconds(started));
                        PrivateGatewayRecoveryFixture.writeEvidence(
                                PrivateGatewayRecoveryFixture.evidencePath(root, operation),
                                evidence);
                        Runtime.getRuntime().halt(EXPECTED_HALT_EXIT);
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("no accepted durable model run appeared before deadline");
    }

    private static void resume(Path root) throws Exception {
        long started = System.nanoTime();
        ClientBuildIdentity build = ClientBuildIdentityProvider.capture();
        JsonObject interruption =
                PrivateGatewayRecoveryFixture.readEvidence(
                        PrivateGatewayRecoveryFixture.evidencePath(root, "interrupt-resume"));
        try (GuiSession gui = PrivateGatewayRecoveryFixture.openGui(root)) {
            GatewayContext gateway = captureGateway();
            LargeActionHandleStore handles = handles(gui.loaded(), build);
            PersistedActionRecovery.Item item = requireOneInterrupted(handles, interruption);
            ProjectActionRuntimeFactory factory =
                    new ProjectActionRuntimeFactory(
                            gateway::transport,
                            new ImageActionRegistry(),
                            new ImageLifecycle(gui.qupath()),
                            new MetricsEngine());

            PersistedActionRecovery.Attempt attempt;
            try (ProjectActionRuntime runtime =
                    factory.open(gateway.capabilities(), build, handles)) {
                attempt =
                        runtime.coordinator()
                                .persistedRecovery()
                                .resume(item, gui.loaded().entry(), () -> false);
                for (int retry = 0;
                        attempt instanceof PersistedActionRecovery.Paused paused && retry < 3;
                        retry++) {
                    System.err.println("Recovery attempt paused: " + paused.failureMessage());
                    Thread.sleep(2_000);
                    attempt =
                            runtime.coordinator()
                                    .persistedRecovery()
                                    .resume(paused, gui.loaded().entry(), () -> false);
                }
            }
            String pausedDetail =
                    attempt instanceof PersistedActionRecovery.Paused paused
                            ? ": " + paused.failureMessage()
                            : "";
            PrivateGatewayRecoveryFixture.require(
                    attempt instanceof PersistedActionRecovery.Completed,
                    "explicit recovery remained paused after bounded retries" + pausedDetail);
            RunResults.ImageRunResult result =
                    ((PersistedActionRecovery.Completed) attempt).result();
            PrivateGatewayRecoveryFixture.require(
                    result.outcome() == RunResults.ImageOutcomeType.SUCCEEDED,
                    "recovered action did not produce a saved successful result");
            PrivateGatewayRecoveryFixture.require(
                    result.hierarchySaved(), "recovered action did not save hierarchy output");
            LargeActionHandle terminal = handles.load(item.actionId(), item.handleGenerationId());
            PrivateGatewayRecoveryFixture.require(
                    terminal.phase() == LargeActionPhase.COMMITTED,
                    "recovered handle is not COMMITTED");

            JsonObject hierarchyAfter =
                    PrivateGatewayRecoveryFixture.hierarchySummary(gui.loaded().entry(), build);
            JsonObject hierarchyBefore = interruption.getAsJsonObject("hierarchy_before");
            PrivateGatewayRecoveryFixture.require(
                    !hierarchyAfter.equals(hierarchyBefore),
                    "successful recovery did not change persisted image state");
            PrivateGatewayRecoveryFixture.require(
                    hierarchyAfter.get("detection_count").getAsInt() > 0,
                    "successful recovery produced no persisted detections");

            JsonObject evidence = PrivateGatewayRecoveryFixture.baseEvidence("resume", build);
            evidence.addProperty("success", true);
            evidence.addProperty("action_id", item.actionId());
            evidence.addProperty("handle_generation_id", item.handleGenerationId());
            evidence.addProperty("terminal_handle_phase", terminal.phase().name());
            evidence.add("gateway", gatewaySummary(gateway));
            evidence.add("module", moduleSummary(gateway.module()));
            evidence.add("hierarchy_before", hierarchyBefore);
            evidence.add("hierarchy_after", hierarchyAfter);
            evidence.add("result", resultSummary(result));
            evidence.addProperty(
                    "elapsed_seconds", PrivateGatewayRecoveryFixture.elapsedSeconds(started));
            PrivateGatewayRecoveryFixture.writeEvidence(
                    PrivateGatewayRecoveryFixture.evidencePath(root, "resume"), evidence);
        }
    }

    private static void abandon(Path root) throws Exception {
        long started = System.nanoTime();
        ClientBuildIdentity build = ClientBuildIdentityProvider.capture();
        JsonObject interruption =
                PrivateGatewayRecoveryFixture.readEvidence(
                        PrivateGatewayRecoveryFixture.evidencePath(root, "interrupt-abandon"));
        LoadedProject loaded = PrivateGatewayRecoveryFixture.load(root);
        LargeActionHandleStore handles = handles(loaded, build);
        PersistedActionRecovery recovery = new PersistedActionRecovery(handles);
        PersistedActionRecovery.Item item = requireOneInterrupted(handles, interruption);
        JsonObject hierarchyBefore = interruption.getAsJsonObject("hierarchy_before");
        recovery.abandon(item);
        LargeActionHandle terminal = handles.load(item.actionId(), item.handleGenerationId());
        PrivateGatewayRecoveryFixture.require(
                terminal.phase() == LargeActionPhase.CANCELLED,
                "abandoned handle is not CANCELLED");
        JsonObject hierarchyAfter =
                PrivateGatewayRecoveryFixture.hierarchySummary(loaded.entry(), build);
        PrivateGatewayRecoveryFixture.require(
                hierarchyAfter.equals(hierarchyBefore),
                "abandonment changed the persisted QuPath image");

        JsonObject evidence = PrivateGatewayRecoveryFixture.baseEvidence("abandon", build);
        evidence.addProperty("success", true);
        evidence.addProperty("action_id", item.actionId());
        evidence.addProperty("handle_generation_id", item.handleGenerationId());
        evidence.addProperty("terminal_handle_phase", terminal.phase().name());
        evidence.addProperty("remote_cancellation_requested", false);
        evidence.addProperty("remote_cleanup_owner", "isolated qualification deployment teardown");
        evidence.add("hierarchy_before", hierarchyBefore);
        evidence.add("hierarchy_after", hierarchyAfter);
        evidence.addProperty(
                "elapsed_seconds", PrivateGatewayRecoveryFixture.elapsedSeconds(started));
        PrivateGatewayRecoveryFixture.writeEvidence(
                PrivateGatewayRecoveryFixture.evidencePath(root, "abandon"), evidence);
    }

    private static PersistedActionRecovery.Item requireOneInterrupted(
            LargeActionHandleStore handles, JsonObject interruption) throws IOException {
        List<PersistedActionRecovery.Item> unresolved =
                new PersistedActionRecovery(handles).unresolved();
        PrivateGatewayRecoveryFixture.require(
                unresolved.size() == 1,
                "qualification project must have exactly one unresolved handle");
        PersistedActionRecovery.Item item = unresolved.getFirst();
        PrivateGatewayRecoveryFixture.require(
                item.actionId().equals(interruption.get("action_id").getAsString()),
                "unresolved action differs from interruption receipt");
        PrivateGatewayRecoveryFixture.require(
                item.handleGenerationId()
                        .equals(interruption.get("handle_generation_id").getAsString()),
                "unresolved handle generation differs from interruption receipt");
        return item;
    }

    private static LargeActionHandleStore handles(LoadedProject loaded, ClientBuildIdentity build)
            throws IOException {
        return new LargeActionHandleStore(
                ProjectRunCoordinator.recoveryRoot(loaded.project()), build);
    }

    private static GatewayContext captureGateway()
            throws IOException, InterruptedException, InferenceException {
        GatewayTransport transport = new GatewayTransport(new DeploymentStore());
        ModuleGatewayClient.CatalogCapture catalog =
                new ModuleGatewayClient(transport).captureModuleCatalog();
        InstalledModuleDescriptor module =
                catalog.modules().stream()
                        .filter(
                                descriptor ->
                                        descriptor
                                                .identity()
                                                .moduleRef()
                                                .toString()
                                                .equals(MODULE_REF))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "qualification module is not installed"));
        PrivateGatewayRecoveryFixture.require(
                module.identity().resolvedCommitSha().orElseThrow().equals(MODULE_COMMIT),
                "qualification module resolved to another commit");
        return new GatewayContext(transport, catalog.capabilities(), module);
    }

    private static ActionSelectionCodec.ImageAction wholeSlideRedo() {
        return new ActionSelectionCodec.Redo(
                new ActionSelectionCodec.AllModels(
                        new ActionSelectionCodec.SlideRegion(new ActionSelectionCodec.FullSlide())),
                false);
    }

    private static JsonObject gatewaySummary(GatewayContext gateway) {
        JsonObject value = new JsonObject();
        value.addProperty("deployment_id", gateway.capabilities().deploymentId());
        value.addProperty("auth_revision", gateway.capabilities().authRevision());
        value.add(
                "gateway_build", BuildIdentityCodec.toJson(gateway.capabilities().gatewayBuild()));
        value.addProperty("runner_registry_digest", gateway.capabilities().runnerRegistryDigest());
        return value;
    }

    private static JsonObject moduleSummary(InstalledModuleDescriptor module) {
        JsonObject value = new JsonObject();
        value.addProperty("module_ref", module.identity().moduleRef().toString());
        value.addProperty(
                "resolved_commit_sha", module.identity().resolvedCommitSha().orElseThrow());
        value.addProperty("content_digest", module.identity().contentDigest());
        value.addProperty("recipe_digest", module.identity().recipeDigest());
        return value;
    }

    private static JsonObject resultSummary(RunResults.ImageRunResult result) {
        JsonObject value = new JsonObject();
        value.addProperty("image_id", result.imageId());
        value.addProperty("outcome", result.outcome().name());
        value.addProperty("hierarchy_saved", result.hierarchySaved());
        value.addProperty("scope_count", result.scopes().size());
        JsonArray scopes = new JsonArray();
        result.scopes()
                .forEach(
                        scope -> {
                            JsonObject item = new JsonObject();
                            item.addProperty("type", scope.type().name());
                            item.addProperty("hierarchy_saved", scope.hierarchySaved());
                            scope.execution()
                                    .ifPresent(
                                            execution ->
                                                    item.addProperty(
                                                            "execution_type",
                                                            execution.getClass().getSimpleName()));
                            scope.failure()
                                    .ifPresent(
                                            failure ->
                                                    item.addProperty(
                                                            "failure_type", failure.type().name()));
                            scopes.add(item);
                        });
        value.add("scopes", scopes);
        value.addProperty("warning_count", result.warnings().size());
        value.addProperty("image_failure_count", result.imageFailures().size());
        return value;
    }

    private static String safeMessage(Throwable failure, Path... privatePaths) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String sanitized = message.replace(System.getProperty("user.home"), "<private-home>");
        for (Path path : privatePaths) {
            sanitized = sanitized.replace(path.toString(), "<private-path>");
        }
        return sanitized;
    }

    private static void requireWorkspace(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(
                        PrivateGatewayRecoveryFixture.evidencePath(root, "prepare"),
                        LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("qualification workspace is not prepared");
        }
    }

    private record GatewayContext(
            GatewayTransport transport,
            GatewayCapabilities capabilities,
            InstalledModuleDescriptor module) {
        private GatewayContext {
            transport = Objects.requireNonNull(transport, "transport");
            capabilities = Objects.requireNonNull(capabilities, "capabilities");
            module = Objects.requireNonNull(module, "module");
        }
    }
}
