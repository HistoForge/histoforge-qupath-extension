package qupath.ext.histoforge.ui.project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.util.Duration;
import qupath.ext.histoforge.client.DurableModelRunExecutor;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.GatewayErrorCodec.Code;
import qupath.ext.histoforge.contract.ModelRunStatusCodec;
import qupath.ext.histoforge.large.HistoricalActionRecord;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.pipeline.BatchOrchestrator.BatchPhase;
import qupath.ext.histoforge.pipeline.BatchOrchestrator.BatchProgress;
import qupath.ext.histoforge.pipeline.ImageSourceProgress;
import qupath.ext.histoforge.pipeline.PersistedActionRecovery;
import qupath.ext.histoforge.pipeline.PreparedImageBudget;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.ui.jobs.JobRow;
import qupath.ext.histoforge.util.Errors;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/**
 * Current-project inference display. Existing batch/recovery owners execute actions; this class
 * only observes their events and reads saved records or cloud status. Observable state belongs to
 * the FX thread. It never submits a run or changes an image hierarchy.
 */
public final class ProjectInferenceJobs {

    private final QuPathGUI qupath;
    private final Supplier<GatewayTransport> transport;
    private final Supplier<ModuleGatewayClient> client;
    private final Supplier<ClientBuildIdentity> build;
    private final ObservableList<JobRow.Inference> rows = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper problem = new ReadOnlyStringWrapper("");
    private final Map<String, LargeActionHandle> handles = new LinkedHashMap<>();
    private List<HistoricalActionRecord> historical = List.of();
    private final Map<Object, Map<String, InferenceJobRows.Live>> liveByProject =
            new IdentityHashMap<>();
    private Map<String, InferenceJobRows.Live> live = new LinkedHashMap<>();
    private final Map<String, InferenceJobRows.Cloud> cloud = new HashMap<>();
    private Set<String> locallyAvailableRuns = Set.of();
    private final PauseTransition nextPoll = new PauseTransition(Duration.seconds(2));
    private Project<BufferedImage> project;
    private String projectId;
    private ClientBuildIdentity runningBuild;
    private long generation;
    private boolean loading;
    private boolean pending;
    private boolean inspectCloud;

    ProjectInferenceJobs(
            QuPathGUI qupath,
            Supplier<GatewayTransport> transport,
            Supplier<ModuleGatewayClient> client,
            Supplier<ClientBuildIdentity> build) {
        this.qupath = qupath;
        this.transport = transport;
        this.client = client;
        this.build = build;
        nextPoll.setOnFinished(event -> refresh(false));
    }

    public ObservableList<JobRow.Inference> rows() {
        return rows;
    }

    public ReadOnlyStringProperty problemProperty() {
        return problem.getReadOnlyProperty();
    }

    /** Local discovery works offline; only the authenticated status requests need a connection. */
    public void setCloudInspectionAvailable(boolean available) {
        inspectCloud = available;
        refresh();
    }

    public void refresh() {
        refresh(true);
    }

    private void refresh(boolean discover) {
        nextPoll.stop();
        Project<BufferedImage> selected = qupath.getProject();
        if (project != selected) {
            generation++;
            project = selected;
            handles.clear();
            historical = List.of();
            cloud.clear();
            locallyAvailableRuns = Set.of();
            rows.clear();
            live = liveByProject.computeIfAbsent(selected, ignored -> new LinkedHashMap<>());
            problem.set("");
        }
        if (project == null || project.getPath() == null) return;
        projectId = project.getPath().toAbsolutePath().normalize().toString();
        try {
            runningBuild = build.get();
        } catch (RuntimeException failure) {
            problem.set(Errors.message(failure));
            return;
        }
        if (loading) {
            pending = true;
            return;
        }
        loading = true;
        long expected = generation;
        Project<BufferedImage> capturedProject = project;
        ClientBuildIdentity capturedBuild = runningBuild;
        List<LargeActionHandle> captured = List.copyOf(handles.values());
        List<HistoricalActionRecord> capturedHistory = historical;
        Map<String, InferenceJobRows.Live> capturedLive = Map.copyOf(live);
        Map<String, InferenceJobRows.Cloud> previousCloud = Map.copyOf(cloud);
        boolean queryCloud = inspectCloud;
        Thread.startVirtualThread(
                () -> {
                    List<LargeActionHandle> found = captured;
                    List<HistoricalActionRecord> foundHistory = capturedHistory;
                    Map<String, InferenceJobRows.Cloud> observations = new HashMap<>();
                    Set<String> available = new HashSet<>();
                    String failure = "";
                    try {
                        var root = ProjectRunCoordinator.recoveryRoot(capturedProject);
                        if (Files.exists(root)) {
                            var store = new LargeActionHandleStore(root, capturedBuild);
                            PreparedImageBudget.observeRecoveryFiles(store);
                            if (discover) {
                                var records = store.scanRecords();
                                found = records.currentHandles();
                                foundHistory = records.historicalActions();
                            }
                            for (var handle : found)
                                available.addAll(store.locallyAvailableRuns(handle));
                            if (queryCloud)
                                observeCloud(
                                        store, found, capturedLive, previousCloud, observations);
                        } else if (discover) {
                            found = List.of();
                            foundHistory = List.of();
                        }
                    } catch (Exception error) {
                        failure = Errors.message(error);
                    }
                    List<LargeActionHandle> result = found;
                    List<HistoricalActionRecord> historyResult = foundHistory;
                    String error = failure;
                    Platform.runLater(
                            () -> {
                                loading = false;
                                if (project == capturedProject
                                        && qupath.getProject() == capturedProject
                                        && generation == expected) {
                                    if (error.isEmpty()) {
                                        if (discover) {
                                            handles.clear();
                                            historical = historyResult;
                                            result.forEach(
                                                    h -> handles.put(h.handleGenerationId(), h));
                                        }
                                        cloud.putAll(observations);
                                        locallyAvailableRuns = Set.copyOf(available);
                                    }
                                    problem.set(error);
                                    render();
                                } else pending = true;
                                if (pending) {
                                    pending = false;
                                    refresh();
                                } else if (error.isEmpty() && needsCloudPoll())
                                    nextPoll.playFromStart();
                            });
                });
    }

    private void observeCloud(
            LargeActionHandleStore store,
            List<LargeActionHandle> found,
            Map<String, InferenceJobRows.Live> capturedLive,
            Map<String, InferenceJobRows.Cloud> previous,
            Map<String, InferenceJobRows.Cloud> observations)
            throws Exception {
        DurableModelRunExecutor reader = null;
        for (LargeActionHandle handle : found) {
            var active =
                    capturedLive.get(
                            InferenceJobRows.imageKey(handle.actionId(), handle.imageId()));
            if (!InferenceJobRows.needsCloudObservation(handle)
                    || active != null
                            && (active.phase() == BatchPhase.RUNNING
                                    || active.phase() == BatchPhase.QUEUED)) continue;
            Set<String> pendingRuns =
                    handle.steps().stream()
                            .flatMap(s -> s.runs().stream())
                            .filter(InferenceJobRows::needsCloudObservation)
                            .map(r -> r.modelRunId())
                            .filter(id -> !previous.containsKey(id) || !previous.get(id).ended())
                            .collect(Collectors.toSet());
            if (pendingRuns.isEmpty()) continue;
            try {
                if (!transport.get().matchesCurrentDeployment(handle.module().deploymentId()))
                    throw new IOException("This analysis belongs to another cloud backend.");
                if (reader == null)
                    reader =
                            new DurableModelRunExecutor(
                                    transport.get(), client.get().capabilities());
                for (var run : store.inspectModelRuns(handle)) {
                    String id = run.request().modelRunId();
                    if (!pendingRuns.contains(id)) continue;
                    try {
                        var limits =
                                handle.module()
                                        .activatedModels()
                                        .get(run.stepId())
                                        .capabilities()
                                        .durableRun()
                                        .orElseThrow()
                                        .run();
                        var observed = reader.inspect(run.request(), limits);
                        observations.put(
                                id,
                                new InferenceJobRows.Cloud(
                                        Optional.of(observed.status()), Optional.empty()));
                    } catch (Exception error) {
                        boolean absent =
                                error instanceof InferenceException remote
                                        && remote.gatewayError()
                                                .filter(
                                                        e ->
                                                                e.code() == Code.MODEL_RUN_NOT_FOUND
                                                                        || e.code()
                                                                                == Code
                                                                                        .MODEL_RUN_EXPIRED)
                                                .isPresent();
                        observations.put(
                                id,
                                new InferenceJobRows.Cloud(
                                        Optional.empty(),
                                        absent
                                                ? Optional.empty()
                                                : Optional.of(Errors.message(error)),
                                        absent));
                    }
                }
            } catch (Exception error) {
                pendingRuns.stream()
                        .forEach(
                                id ->
                                        observations.put(
                                                id,
                                                new InferenceJobRows.Cloud(
                                                        Optional.empty(),
                                                        Optional.of(Errors.message(error)))));
            }
        }
    }

    /**
     * Bind events to their original project so a project switch cannot move work into another UI.
     */
    LargeActionHandleStore store(Project<BufferedImage> owner, ClientBuildIdentity identity)
            throws IOException {
        var store =
                new LargeActionHandleStore(
                        ProjectRunCoordinator.recoveryRoot(owner),
                        identity,
                        handle ->
                                Platform.runLater(
                                        () -> {
                                            if (project != owner || qupath.getProject() != owner)
                                                return;
                                            generation++;
                                            handles.put(handle.handleGenerationId(), handle);
                                            render();
                                            refresh(false);
                                        }));
        PreparedImageBudget.observeRecoveryFiles(store);
        return store;
    }

    void observedStatus(Object owner, ModelRunStatusCodec.Status status) {
        Platform.runLater(
                () -> {
                    if (project != owner || qupath.getProject() != owner) return;
                    cloud.put(
                            status.modelRunId(),
                            new InferenceJobRows.Cloud(Optional.of(status), Optional.empty()));
                    render();
                });
    }

    /** Upload workers report bytes; the FX thread updates only their owning image's live row. */
    void observedSourceProgress(Object owner, ImageSourceProgress progress) {
        Platform.runLater(
                () -> {
                    var snapshots = liveByProject.get(owner);
                    if (snapshots == null) return;
                    String key = InferenceJobRows.imageKey(progress.actionId(), progress.imageId());
                    snapshots.computeIfPresent(
                            key,
                            (ignored, current) -> current.withSourceProgress(progress.transfer()));
                    if (project == owner && qupath.getProject() == owner) render();
                });
    }

    /** Confirmed ended by cancellation acknowledgement or an already validated COMPLETE result. */
    void observedStops(Object owner, List<String> runIds) {
        if (project != owner || qupath.getProject() != owner || runIds.isEmpty()) return;
        generation++;
        runIds.forEach(
                id ->
                        cloud.put(
                                id,
                                new InferenceJobRows.Cloud(
                                        Optional.empty(), Optional.empty(), true)));
        render();
    }

    void progress(Object owner, String actionId, String moduleRef, BatchProgress progress) {
        liveByProject
                .computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                .put(
                        InferenceJobRows.imageKey(actionId, progress.imageId()),
                        new InferenceJobRows.Live(
                                actionId,
                                progress.imageId(),
                                progress.imageName(),
                                moduleRef,
                                progress.phase(),
                                progress.result(),
                                progress.suspensionFailure(),
                                Instant.now(),
                                false));
        if (project != owner || qupath.getProject() != owner) return;
        generation++;
        render();
        if (progress.phase() == BatchPhase.SUSPENDED || progress.phase() == BatchPhase.TERMINAL)
            refresh();
    }

    void recovery(
            Object owner,
            PersistedActionRecovery.Item item,
            String imageName,
            BatchPhase phase,
            Optional<RunResults.ImageRunResult> result,
            Optional<String> failure) {
        liveByProject
                .computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                .put(
                        InferenceJobRows.imageKey(item.actionId(), item.imageId()),
                        new InferenceJobRows.Live(
                                item.actionId(),
                                item.imageId(),
                                imageName,
                                item.moduleRef(),
                                phase,
                                result,
                                failure,
                                Instant.now(),
                                true));
        if (project != owner || qupath.getProject() != owner) return;
        generation++;
        render();
        if (phase != BatchPhase.RUNNING) refresh();
    }

    /**
     * A failed batch future has no further progress events; stop showing its local work as live.
     */
    void batchFailed(Object owner, String actionId, Throwable failure) {
        var snapshots = liveByProject.get(owner);
        if (snapshots == null) return;
        snapshots.replaceAll(
                (key, current) ->
                        current.actionId().equals(actionId)
                                        && current.phase() != BatchPhase.TERMINAL
                                ? new InferenceJobRows.Live(
                                        current.actionId(),
                                        current.imageId(),
                                        current.imageName(),
                                        current.moduleRef(),
                                        BatchPhase.TERMINAL,
                                        current.result(),
                                        Optional.of(Errors.message(failure)),
                                        Instant.now(),
                                        false)
                                : current);
        if (project != owner || qupath.getProject() != owner) return;
        generation++;
        render();
        refresh();
    }

    JobRow.Inference current(JobRow.Inference row) {
        requireCurrent(row);
        return rows.stream()
                .filter(r -> r.key().equals(row.key()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "This analysis is no longer listed. Refresh Jobs."));
    }

    void forget(Object owner, JobRow.Inference row) {
        var snapshots = liveByProject.get(owner);
        if (snapshots != null)
            snapshots.remove(InferenceJobRows.imageKey(row.actionId(), row.imageId()));
        if (project != owner || qupath.getProject() != owner) return;
        generation++;
        handles.values()
                .removeIf(
                        h ->
                                h.actionId().equals(row.actionId())
                                        && h.imageId().equals(row.imageId()));
        render();
    }

    void requireCurrent(JobRow.Inference row) {
        if (project != qupath.getProject() || !row.projectId().equals(projectId))
            throw new IllegalStateException("Open the analysis's original project first.");
    }

    boolean blocks(String imageId) {
        return !problem.get().isEmpty()
                || historical.stream()
                        .anyMatch(
                                record ->
                                        record.imageId().equals(imageId)
                                                && !record.phase().terminal())
                || handles.values().stream()
                        .anyMatch(h -> h.imageId().equals(imageId) && !h.phase().terminal());
    }

    Optional<String> latestStatus(String imageId) {
        return rows.stream()
                .filter(r -> r.imageId().equals(imageId))
                .findFirst()
                .map(JobRow.Inference::status);
    }

    private boolean needsCloudPoll() {
        return inspectCloud
                && handles.values().stream()
                        .filter(InferenceJobRows::needsCloudObservation)
                        .filter(
                                h -> {
                                    var current =
                                            live.get(
                                                    InferenceJobRows.imageKey(
                                                            h.actionId(), h.imageId()));
                                    return current == null
                                            || current.phase() != BatchPhase.RUNNING
                                                    && current.phase() != BatchPhase.QUEUED;
                                })
                        .flatMap(h -> h.steps().stream())
                        .flatMap(s -> s.runs().stream())
                        .filter(InferenceJobRows::needsCloudObservation)
                        .map(r -> cloud.get(r.modelRunId()))
                        .anyMatch(c -> c != null && c.running());
    }

    private void render() {
        if (project == null) return;
        Map<String, List<LargeActionHandle>> groups = new LinkedHashMap<>();
        Map<String, List<HistoricalActionRecord>> historyGroups = new LinkedHashMap<>();
        historical.forEach(
                record ->
                        historyGroups
                                .computeIfAbsent(
                                        InferenceJobRows.imageKey(
                                                record.actionId(), record.imageId()),
                                        ignored -> new ArrayList<>())
                                .add(record));
        handles.values()
                .forEach(
                        h ->
                                groups.computeIfAbsent(
                                                InferenceJobRows.imageKey(
                                                        h.actionId(), h.imageId()),
                                                ignored -> new ArrayList<>())
                                        .add(h));
        live.keySet().forEach(key -> groups.computeIfAbsent(key, ignored -> new ArrayList<>()));
        historyGroups
                .keySet()
                .forEach(key -> groups.computeIfAbsent(key, ignored -> new ArrayList<>()));
        Map<String, String> imageNames = new HashMap<>();
        project.getImageList().forEach(e -> imageNames.put(e.getID(), e.getImageName()));
        List<JobRow.Inference> projected = new ArrayList<>();
        groups.forEach(
                (key, targets) -> {
                    var current = live.get(key);
                    var old = historyGroups.getOrDefault(key, List.of());
                    String imageId =
                            current != null
                                    ? current.imageId()
                                    : !targets.isEmpty()
                                            ? targets.getFirst().imageId()
                                            : old.getFirst().imageId();
                    String imageName =
                            imageNames.getOrDefault(
                                    imageId,
                                    current != null
                                            ? current.imageName()
                                            : "Image " + imageId + " (removed from project)");
                    String unavailable =
                            imageNames.containsKey(imageId)
                                    ? ""
                                    : "The image was removed from this project.";
                    for (var target : targets) {
                        if (target.phase().terminal()) continue;
                        var permission =
                                ProjectPersistedRecoveryGate.availability(
                                        PersistedActionRecovery.item(target), runningBuild);
                        if (!permission.canResume()) {
                            unavailable = permission.unavailableReason();
                            break;
                        }
                    }
                    Optional<JobRow.Inference> active =
                            targets.isEmpty() && current == null
                                    ? Optional.empty()
                                    : Optional.of(
                                            InferenceJobRows.row(
                                                    projectId,
                                                    imageName,
                                                    targets,
                                                    Optional.ofNullable(current),
                                                    cloud,
                                                    locallyAvailableRuns,
                                                    unavailable));
                    projected.add(
                            old.isEmpty()
                                    ? active.orElseThrow()
                                    : InferenceJobRows.historicalRow(
                                            projectId, imageName, old, active));
                });
        projected.sort(
                Comparator.comparing(JobRow.Inference::updatedAt)
                        .reversed()
                        .thenComparing(JobRow.Inference::key));
        if (!rows.equals(projected)) rows.setAll(projected);
    }
}
