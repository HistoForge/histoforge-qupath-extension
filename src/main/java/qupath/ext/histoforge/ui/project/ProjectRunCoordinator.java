package qupath.ext.histoforge.ui.project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.data.ProjectInferenceScope.Mode;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.pipeline.ImageActionRegistry;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.pipeline.PersistedActionRecovery;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.ui.jobs.JobRow;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/** Captures one explicit Project-tab action and owns its bounded process-local batch lifetime. */
final class ProjectRunCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(ProjectRunCoordinator.class);

    enum RequestedAction {
        FILL,
        REDO,
        METRICS;

        String displayName() {
            return switch (this) {
                case FILL -> "Fill";
                case REDO -> "Redo";
                case METRICS -> "Metrics";
            };
        }
    }

    private final QuPathGUI qupath;
    private final ModulesRegistry registry;
    private final Supplier<ClientBuildIdentity> clientBuildSupplier;
    private final Supplier<List<ImageRowModel>> checkedRows;
    private final Supplier<List<String>> selectedModels;
    private final Supplier<Mode> selectedRegionMode;
    private final Map<String, LatestResult> latestResults = new HashMap<>();
    private final ProjectBatchSession batchSession;
    private final ProjectRecoverySession recoverySession;
    private final ProjectBatchLauncher batchLauncher;
    private final ProjectActionRuntimeFactory runtimeFactory;
    private final ProjectInferenceJobs jobs;
    private final Supplier<GatewayTransport> transport;
    private final Supplier<ModuleGatewayClient> client;
    private final Runnable openJobs;
    private final Runnable onIdle;
    private CompletableFuture<Void> storedAction = CompletableFuture.completedFuture(null);

    private Object latestResultsProject;
    private boolean cloudUpdating;

    ProjectRunCoordinator(
            QuPathGUI qupath,
            Supplier<GatewayTransport> transport,
            Supplier<ModuleGatewayClient> client,
            ModulesRegistry registry,
            Supplier<ClientBuildIdentity> clientBuildSupplier,
            ImageActionRegistry actionRegistry,
            ImageLifecycle imageLifecycle,
            MetricsEngine metricsEngine,
            TableView<ImageRowModel> imageTable,
            Button fillButton,
            VBox progressBox,
            ProgressBar progressBar,
            Label progressLabel,
            Supplier<List<ImageRowModel>> checkedRows,
            Supplier<List<String>> selectedModels,
            Supplier<Mode> selectedRegionMode,
            Consumer<Boolean> setBusy,
            Runnable onIdle,
            Consumer<Runnable> reload,
            Runnable openJobs) {
        this.qupath = Objects.requireNonNull(qupath, "qupath");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transport = transport;
        this.client = client;
        this.openJobs = openJobs;
        this.onIdle = onIdle;
        this.clientBuildSupplier =
                Objects.requireNonNull(clientBuildSupplier, "clientBuildSupplier");
        this.checkedRows = Objects.requireNonNull(checkedRows, "checkedRows");
        this.selectedModels = Objects.requireNonNull(selectedModels, "selectedModels");
        this.selectedRegionMode = Objects.requireNonNull(selectedRegionMode, "selectedRegionMode");
        jobs = new ProjectInferenceJobs(qupath, transport, client, clientBuildSupplier);
        this.batchSession =
                new ProjectBatchSession(
                        qupath,
                        imageTable,
                        fillButton,
                        progressBox,
                        progressBar,
                        progressLabel,
                        setBusy,
                        onIdle,
                        this::startFill,
                        reload,
                        this::rememberLatestResult);
        this.recoverySession =
                new ProjectRecoverySession(
                        qupath,
                        imageTable,
                        fillButton,
                        progressBox,
                        progressBar,
                        progressLabel,
                        setBusy,
                        onIdle,
                        this::startFill,
                        reload,
                        this::rememberLatestResult);
        runtimeFactory =
                new ProjectActionRuntimeFactory(
                        Objects.requireNonNull(transport, "transport"),
                        Objects.requireNonNull(actionRegistry, "actionRegistry"),
                        Objects.requireNonNull(imageLifecycle, "imageLifecycle"),
                        Objects.requireNonNull(metricsEngine, "metricsEngine"));
        batchSession.connectJobs(
                jobs, openJobs, (owner, actionId) -> stop(owner, actionId, Optional.empty()));
        recoverySession.connectJobs(
                jobs,
                openJobs,
                (owner, item) -> stop(owner, item.actionId(), Optional.of(item.imageId())));
        this.batchLauncher = new ProjectBatchLauncher(registry, runtimeFactory, batchSession, jobs);
    }

    ProjectInferenceJobs jobs() {
        return jobs;
    }

    void reconnect(JobRow.Inference row) {
        try {
            jobs.current(row).requireMutable();
            if (cloudUpdating || storedActionRunning()) return;
            if (batchSession.owns(row.actionId(), row.imageId())) {
                batchSession.resume(row.imageId());
                return;
            }
            if (recoverySession.owns(row.actionId(), row.imageId())) {
                recoverySession.resume();
                return;
            }
            if (batchSession.active() || recoverySession.active())
                throw new IllegalStateException(
                        "Wait for the current local analysis to finish first.");
            if (!row.reconnectUnavailable().isEmpty())
                throw new IllegalStateException(row.reconnectUnavailable());
            var project = qupath.getProject();
            var build = clientBuildSupplier.get();
            var handles = jobs.store(project, build);
            var item =
                    new PersistedActionRecovery(handles)
                            .unresolved().stream()
                                    .filter(
                                            i ->
                                                    i.actionId().equals(row.actionId())
                                                            && i.imageId().equals(row.imageId()))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "This analysis no longer has unfinished targets. Refresh Jobs."));
            var permission = ProjectPersistedRecoveryGate.availability(item, build);
            if (!permission.canResume())
                throw new IllegalStateException(permission.unavailableReason());
            var entry =
                    project.getImageList().stream()
                            .filter(e -> e.getID().equals(item.imageId()))
                            .findFirst()
                            .orElseThrow();
            var recovery =
                    runtimeFactory.openRecovery(
                            build,
                            handles,
                            item.deploymentId(),
                            status -> jobs.observedStatus(project, status),
                            progress -> jobs.observedSourceProgress(project, progress));
            try {
                recoverySession.start(recovery, item, entry, project);
            } catch (RuntimeException failure) {
                recovery.close();
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            Dialogs.showErrorMessage("Cannot continue analysis", describe(failure));
        }
    }

    void stop(JobRow.Inference row) {
        try {
            jobs.current(row).requireMutable();
            stop(
                    qupath.getProject(),
                    row.actionId(),
                    batchSession.owns(row.actionId(), row.imageId())
                            ? Optional.empty()
                            : Optional.of(row.imageId()));
        } catch (RuntimeException failure) {
            Dialogs.showErrorMessage("Cannot stop analysis", describe(failure));
        }
    }

    /** Both Project and Jobs buttons stop the captured owner, even after a project switch. */
    @SuppressWarnings("unchecked")
    private void stop(Object owner, String actionId, Optional<String> imageId) {
        try {
            if (cloudUpdating || storedActionRunning()) return;
            boolean batch = imageId.isEmpty();
            boolean recovery = imageId.filter(id -> recoverySession.owns(actionId, id)).isPresent();
            if (!Dialogs.showYesNoDialog(
                    batch ? "Cancel batch" : "Stop analysis",
                    batch
                            ? "Stop running work and discard the remaining batch queue? Saved results will be kept."
                            : "Stop unfinished work for this image? Saved results will be kept."))
                return;
            var project = (Project<BufferedImage>) owner;
            var handles = jobs.store(project, clientBuildSupplier.get());
            CompletableFuture<Void> stopped =
                    batch
                            ? batchSession.cancelForCloudUpdate()
                            : recovery
                                    ? recoverySession.cancelForCloudUpdate()
                                    : CompletableFuture.completedFuture(null);
            var completion = new CompletableFuture<Void>();
            storedAction = completion;
            stopped.whenComplete(
                    (ignored, failure) ->
                            Thread.startVirtualThread(
                                    () -> {
                                        Throwable problem = failure;
                                        List<String> stoppedRuns = List.of();
                                        try {
                                            if (problem == null)
                                                stoppedRuns =
                                                        RecordedAnalysisActions.stop(
                                                                handles, actionId, imageId,
                                                                transport, client);
                                        } catch (Exception error) {
                                            problem = error;
                                        }
                                        Throwable outcome = problem;
                                        List<String> confirmed = stoppedRuns;
                                        Platform.runLater(
                                                () -> {
                                                    completeStoredAction(completion, outcome);
                                                    jobs.observedStops(project, confirmed);
                                                    jobs.refresh();
                                                    onIdle.run();
                                                    if (outcome != null)
                                                        Dialogs.showErrorMessage(
                                                                "Cloud cancellation not confirmed",
                                                                describe(outcome));
                                                });
                                    }));
        } catch (IOException | RuntimeException failure) {
            Dialogs.showErrorMessage("Cannot stop analysis", describe(failure));
        }
    }

    void clearHistory(JobRow.Inference row) {
        try {
            var current = jobs.current(row);
            current.requireMutable();
            if (!current.canClearHistory()
                    || cloudUpdating
                    || storedActionRunning()
                    || batchSession.active()
                    || recoverySession.active()) return;
            if (!Dialogs.showYesNoDialog(
                    "Clear analysis history",
                    "Remove this completed analysis from Jobs? Saved QuPath objects and original images will be kept."))
                return;
            var owner = qupath.getProject();
            var handles = jobs.store(owner, clientBuildSupplier.get());
            var completion = new CompletableFuture<Void>();
            storedAction = completion;
            Thread.startVirtualThread(
                    () -> {
                        Exception problem = null;
                        try {
                            RecordedAnalysisActions.clear(handles, row.actionId(), row.imageId());
                        } catch (IOException | RuntimeException failure) {
                            problem = failure;
                        }
                        Exception outcome = problem;
                        Platform.runLater(
                                () -> {
                                    completeStoredAction(completion, outcome);
                                    if (outcome == null) jobs.forget(owner, row);
                                    jobs.refresh();
                                    onIdle.run();
                                    if (outcome != null)
                                        Dialogs.showErrorMessage(
                                                "Could not clear analysis history",
                                                describe(outcome));
                                });
                    });
        } catch (IOException | RuntimeException failure) {
            Dialogs.showErrorMessage("Could not clear analysis history", describe(failure));
        }
    }

    void startFill() {
        start(RequestedAction.FILL);
    }

    private boolean storedActionRunning() {
        return !storedAction.isDone();
    }

    private static void completeStoredAction(
            CompletableFuture<Void> completion, Throwable failure) {
        if (failure == null) completion.complete(null);
        else completion.completeExceptionally(failure);
    }

    void startRedo() {
        start(RequestedAction.REDO);
    }

    void startMetrics() {
        start(RequestedAction.METRICS);
    }

    CompletableFuture<Void> cancelForCloudUpdate(String deploymentId) {
        cloudUpdating = true;
        Project<BufferedImage> project = qupath.getProject();
        CompletableFuture<Void> sessions =
                CompletableFuture.allOf(
                        storedActionRunning()
                                ? storedAction
                                : CompletableFuture.completedFuture(null),
                        batchSession.cancelForCloudUpdate(),
                        recoverySession.cancelForCloudUpdate());
        if (project == null || project.getPath() == null) return sessions;
        final Path root;
        final ClientBuildIdentity build;
        try {
            root = recoveryRoot(project);
            build = clientBuildSupplier.get();
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        // A retained, paused target may have no live session after reopening QuPath.
        // Reuse explicit abandonment after live owners release their reservations.
        return sessions.thenRunAsync(
                () -> {
                    try {
                        if (java.nio.file.Files.notExists(root)) return;
                        var catalog =
                                new PersistedActionRecovery(
                                        new LargeActionHandleStore(root, build));
                        for (var item : catalog.unresolved()) {
                            if (item.deploymentId().equals(deploymentId)) catalog.abandon(item);
                        }
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                });
    }

    void finishCloudUpdate() {
        cloudUpdating = false;
    }

    Optional<RunResults.ImageRunResult> latestResult(String imageId, String moduleRef) {
        if (imageId == null || moduleRef == null) {
            return Optional.empty();
        }
        Object projectIdentity = qupath.getProject();
        if (projectIdentity == null) {
            selectLatestResultsProject(null);
            return Optional.empty();
        }
        selectLatestResultsProject(projectIdentity);
        Optional<ModuleContentIdentity> currentModule =
                registry.get(moduleRef)
                        .map(InstalledModule::descriptor)
                        .map(descriptor -> descriptor.identity());
        LatestResult latest = latestResults.get(imageId);
        return currentModule
                .filter(identity -> latest != null && identity.equals(latest.module()))
                .map(ignored -> latest.result());
    }

    private void start(RequestedAction requestedAction) {
        if (cloudUpdating
                || storedActionRunning()
                || batchSession.active()
                || recoverySession.active()) {
            return;
        }
        List<ImageRowModel> rows = selectedRows();
        if (rows == null) {
            return;
        }
        try {
            Project<BufferedImage> project =
                    Objects.requireNonNull(qupath.getProject(), "current QuPath project");
            Object projectIdentity = project;
            selectLatestResultsProject(projectIdentity);
            Mode regionMode =
                    Objects.requireNonNull(selectedRegionMode.get(), "selected region mode");
            ClientBuildIdentity clientBuild = clientBuildSupplier.get();
            LargeActionHandleStore handles = jobs.store(project, clientBuild);
            var records = handles.scanRecords();
            if (rows.stream().anyMatch(row -> records.hasUnfinishedHistory(row.entry.getID()))) {
                jobs.refresh();
                openJobs.run();
                Dialogs.showWarningNotification(
                        "Unfinished historical analysis",
                        "This image has unfinished analysis from an older client build. Use that original build to finish or retire it before starting new work.");
                return;
            }
            PersistedActionRecovery recoveryCatalog = new PersistedActionRecovery(handles);
            if (!ProjectPersistedRecoveryGate.selectedUnresolved(rows, recoveryCatalog.unresolved())
                    .isEmpty()) {
                jobs.refresh();
                openJobs.run();
                Dialogs.showWarningNotification(
                        "Unfinished analysis",
                        "Continue or stop this image's unfinished analysis in Jobs before starting new work.");
                return;
            }
            if (!validateRows(rows, regionMode)) {
                return;
            }
            if (requestedAction == RequestedAction.METRICS
                    && rows.stream()
                            .map(row -> registry.get(row.moduleId).orElseThrow())
                            .anyMatch(module -> module.metricsScript() == null)) {
                Dialogs.showWarningNotification(
                        "Metrics",
                        "Every selected image needs an installed module that defines metrics.groovy.");
                return;
            }
            List<String> modelIds =
                    requestedAction == RequestedAction.METRICS ? List.of() : selectedModels.get();
            if (!modelIds.isEmpty() && commonModule(rows).isEmpty()) {
                Dialogs.showWarningNotification(
                        requestedAction.toString(),
                        "Specific models can be selected only when every image uses the same"
                                + " module.");
                return;
            }
            if (requestedAction == RequestedAction.REDO
                    && !confirmRedo(rows, modelIds, regionMode)) {
                return;
            }
            batchLauncher.launch(
                    rows, modelIds, requestedAction, regionMode, project, clientBuild, handles);
        } catch (IOException | RuntimeException failure) {
            logger.error("Could not start HistoForge action", failure);
            Dialogs.showErrorMessage(
                    "Could not start " + requestedAction.displayName(), describe(failure));
        }
    }

    private List<ImageRowModel> selectedRows() {
        List<ImageRowModel> rows = List.copyOf(checkedRows.get());
        if (rows.isEmpty()) {
            Dialogs.showWarningNotification("HistoForge", "Select images first.");
            return null;
        }
        return rows;
    }

    private boolean validateRows(List<ImageRowModel> rows, Mode regionMode) {
        List<ImageRowModel> withoutRois = rowsWithoutRequiredRois(rows, regionMode);
        if (!withoutRois.isEmpty()) {
            Dialogs.showWarningNotification(
                    "HistoForge", missingRegionsMessage(withoutRois, regionMode));
            return false;
        }
        List<ImageRowModel> unavailable =
                rows.stream()
                        .filter(row -> row.moduleId == null || registry.get(row.moduleId).isEmpty())
                        .toList();
        if (!unavailable.isEmpty()) {
            Dialogs.showWarningNotification(
                    "HistoForge",
                    unavailable.size()
                            + " image(s) need an installed module. Use Set Module or install the"
                            + " assigned reference.");
            return false;
        }
        return true;
    }

    static List<ImageRowModel> rowsWithoutRequiredRois(List<ImageRowModel> rows, Mode regionMode) {
        Objects.requireNonNull(rows, "rows");
        return Objects.requireNonNull(regionMode, "regionMode").wholeSlide()
                ? List.of()
                : rows.stream().filter(row -> row.roiIds.isEmpty()).toList();
    }

    static String missingRegionsMessage(List<ImageRowModel> rows, Mode mode) {
        String selection =
                mode == Mode.ANNOTATIONS_BY_CLASS
                        ? "area annotations of class \""
                                + rows.getFirst().annotationClass.orElse("(choose a class)")
                                + "\""
                        : "HistoForge ROIs";
        return "No "
                + selection
                + " in the last project refresh for:\n"
                + rows.stream()
                        .map(row -> row.entry.getImageName())
                        .collect(java.util.stream.Collectors.joining("\n"))
                + "\nIf annotations changed, use Save Project & Refresh. No whole-slide processing will be substituted.";
    }

    private boolean confirmRedo(List<ImageRowModel> rows, List<String> modelIds, Mode regionMode) {
        String selection =
                modelIds.isEmpty() ? "all selected module models" : String.join(", ", modelIds);
        String scope = regionMode.wholeSlide() ? "the whole slide" : "the selected ROIs";
        String slideWarning =
                regionMode.wholeSlide()
                        ? " Any hierarchy edit after capture makes this whole-slide transaction"
                                + " conflict; HistoForge does not lock the image. If current"
                                + " selected-model output is ROI-scoped, successful Redo replaces"
                                + " that coverage with the whole-slide result."
                        : "";
        return Dialogs.showYesNoDialog(
                "Redo model output",
                "Redo "
                        + selection
                        + " on "
                        + scope
                        + " for "
                        + rows.size()
                        + " image(s)? Existing output attributed to those models—including model"
                        + " output that was hand-edited—will be replaced only after replacement"
                        + " work succeeds. Unattributed manual objects are preserved."
                        + slideWarning);
    }

    private Optional<InstalledModule> commonModule(List<ImageRowModel> rows) {
        String ref = null;
        for (ImageRowModel row : rows) {
            if (ref == null) {
                ref = row.moduleId;
            } else if (!ref.equals(row.moduleId)) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(ref).flatMap(registry::get);
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    static Path recoveryRoot(Project<?> project) throws IOException {
        Path projectFile = Objects.requireNonNull(project, "project").getPath();
        if (projectFile == null) {
            throw new IOException(
                    "Save the QuPath project before starting recoverable HistoForge work.");
        }
        Path normalized = projectFile.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null || normalized.getFileName() == null) {
            throw new IOException("QuPath project path has no stable parent directory");
        }
        return parent.resolve("." + normalized.getFileName() + ".histoforge").resolve("recovery");
    }

    private void rememberLatestResult(
            Object projectIdentity,
            String imageId,
            ModuleContentIdentity moduleIdentity,
            RunResults.ImageRunResult result) {
        if (qupath.getProject() != projectIdentity) {
            return;
        }
        selectLatestResultsProject(projectIdentity);
        latestResults.put(
                Objects.requireNonNull(imageId, "imageId"),
                new LatestResult(moduleIdentity, result));
    }

    private void selectLatestResultsProject(Object projectIdentity) {
        if (latestResultsProject != projectIdentity) {
            latestResults.clear();
            latestResultsProject = projectIdentity;
        }
    }

    private record LatestResult(ModuleContentIdentity module, RunResults.ImageRunResult result) {
        private LatestResult {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(result, "result");
        }
    }
}
