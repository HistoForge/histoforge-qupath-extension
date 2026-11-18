package qupath.ext.histoforge.ui.project;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.dto.ModuleInfo;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.data.GroupManager;
import qupath.ext.histoforge.data.MetricImageResolver;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.data.ProjectInferenceScope;
import qupath.ext.histoforge.data.ProjectInferenceScope.Mode;
import qupath.ext.histoforge.data.ProjectMetadataEditor;
import qupath.ext.histoforge.data.ProjectMetricCacheLoader;
import qupath.ext.histoforge.data.ProjectSummaryData;
import qupath.ext.histoforge.data.ProjectSummaryData.ImageSummary;
import qupath.ext.histoforge.export.ExportTypes.ExportOverwritePolicy;
import qupath.ext.histoforge.export.MetricsExporter;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.pipeline.ImageActionRegistry;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;
import qupath.ext.histoforge.roi.RoiToolHandler;
import qupath.ext.histoforge.ui.HistoforgePrefs;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.HistoforgeTooltip;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.ui.jobs.InferenceJobActions;
import qupath.ext.histoforge.ui.jobs.JobRow;
import qupath.ext.histoforge.ui.modules.InstallDialog;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.objects.classes.PathClass;

/** Presents project snapshots and routes explicit user actions to their lifecycle owners. */
public class ProjectTabController implements InferenceJobActions {
    private static final Logger logger = LoggerFactory.getLogger(ProjectTabController.class);

    @FXML private Button setModuleButton;
    @FXML private Button inferenceSettingsButton;
    @FXML private Button assignGroupButton;
    @FXML private Button runButton;
    @FXML private Button redoButton;
    @FXML private Button metricsButton;
    @FXML private ComboBox<Mode> regionModeComboBox;
    @FXML private ComboBox<String> annotationClassComboBox;
    @FXML private MenuButton stepsPickerButton;
    @FXML private Button exportButton;
    @FXML private Button saveAndRefreshButton;
    @FXML private ProgressIndicator loadingIndicator;
    @FXML private VBox emptyStateBox;
    @FXML private Label emptyStateLabel;
    @FXML private SplitPane mainSplitPane;
    @FXML private TableView<ImageRowModel> imageTable;
    @FXML private VBox progressBox;
    @FXML private Label progressLabel;
    @FXML private ProgressBar progressBar;
    @FXML private VBox groupMetricsBox;
    @FXML private Label statusLabel;

    // Draw-ROI default-size strip (relocated from the old Image tab).
    @FXML private Spinner<Double> defaultWidthSpinner;
    @FXML private Spinner<Double> defaultHeightSpinner;
    @FXML private ComboBox<RoiToolHandler.SizeUnit> roiUnitComboBox;
    @FXML private Label roiUnitWarningLabel;

    private QuPathGUI qupath;
    private Supplier<ModuleGatewayClient> moduleClient;
    private ModulesRegistry registry;
    private ImageLifecycle imageLifecycle;
    private Function<LocalResourceEnvelope, ClientBuildIdentity> clientBuildFactory;
    private ProjectDataLoader projectDataLoader;
    private ProjectRefreshSession refreshSession;
    private ProjectExportCoordinator exportCoordinator;
    private ProjectSummaryData summaryData;
    private Map<String, ModuleInfo> modulesById = new LinkedHashMap<>();
    private final Map<String, ImageSummary> summariesByImageId = new LinkedHashMap<>();

    /** Images whose metrics require an exact current cache hit before being considered fresh. */
    private final Set<String> metricInvalidatedImageIds = new java.util.LinkedHashSet<>();

    private final ProjectMetricsTableBuilder metricsTableBuilder = new ProjectMetricsTableBuilder();
    private ProjectRunCoordinator runCoordinator;
    private RoiSizeSpinners roiSizeSpinners;
    private StepsPicker stepsPicker;
    private boolean batchRunning;
    private boolean exportRunning;
    private boolean saveRefreshRunning;
    private boolean dataLoading;
    private InferenceScopeControls inferenceScopeControls;

    /** Fences background Project-tab snapshots after a newer load or live hierarchy edit. */
    private volatile long loadGeneration;

    private long displayedSummaryGeneration;

    /** Cancels an export only when the user switches to another QuPath project. */
    private volatile long projectGeneration;

    private Runnable onBatchStateChanged;
    private MenuButton selectMenu;
    private Runnable openJobs;

    /** Connects the loaded view to the active QuPath session and shared services. */
    public void init(
            QuPathGUI qupath,
            Supplier<GatewayTransport> transport,
            Supplier<ModuleGatewayClient> moduleClient,
            ModulesRegistry registry,
            Function<LocalResourceEnvelope, ClientBuildIdentity> clientBuildFactory,
            ImageActionRegistry actionRegistry,
            ImageLifecycle imageLifecycle,
            MetricsEngine metricsEngine,
            Runnable openJobs) {
        this.qupath = qupath;
        this.moduleClient = moduleClient;
        this.registry = registry;
        this.imageLifecycle = imageLifecycle;
        this.clientBuildFactory =
                java.util.Objects.requireNonNull(clientBuildFactory, "clientBuildFactory");
        this.openJobs = java.util.Objects.requireNonNull(openJobs, "openJobs");
        MetricImageResolver metricResolver =
                new MetricImageResolver(actionRegistry, imageLifecycle, metricsEngine);
        ProjectMetricCacheLoader metricCacheLoader =
                new ProjectMetricCacheLoader(clientBuildFactory, metricsEngine, metricResolver);
        this.projectDataLoader = new ProjectDataLoader(metricCacheLoader, Platform::runLater);
        this.refreshSession = new ProjectRefreshSession(projectDataLoader, imageTable::getScene);
        imageTable
                .sceneProperty()
                .addListener(
                        (observable, previous, current) -> {
                            if (previous != null && current == null) invalidateMetricPresentation();
                        });
        this.exportCoordinator = new ProjectExportCoordinator(new MetricsExporter(metricResolver));
        selectMenu = ImageTableColumns.install(imageTable, this::installMissingModule);
        roiSizeSpinners =
                new RoiSizeSpinners(
                        defaultWidthSpinner,
                        defaultHeightSpinner,
                        roiUnitComboBox,
                        roiUnitWarningLabel,
                        qupath::getImageData);
        // The HistoForge ROI tool is global; refresh the size spinners' calibration whenever the
        // viewer's image changes so µm/mm conversions track the open slide.
        qupath.imageDataProperty()
                .addListener(
                        (obs, oldData, newData) -> {
                            if (roiSizeSpinners != null) {
                                roiSizeSpinners.refresh();
                            }
                        });
        // Clear old entries immediately on a project switch so actions cannot target them.
        qupath.projectProperty().addListener((obs, oldP, newP) -> onProjectChanged());

        var placeholder = new javafx.scene.control.Label("No data loaded.");
        placeholder.getStyleClass().add(HistoforgeStyles.MUTED);
        imageTable.setPlaceholder(placeholder);

        inferenceScopeControls =
                new InferenceScopeControls(
                        regionModeComboBox, annotationClassComboBox, this::onRegionModeChanged);
        restoreInferenceScope();

        stepsPicker = new StepsPicker(stepsPickerButton);
        runCoordinator =
                new ProjectRunCoordinator(
                        qupath,
                        transport,
                        moduleClient,
                        registry,
                        this::currentClientBuild,
                        actionRegistry,
                        imageLifecycle,
                        metricsEngine,
                        imageTable,
                        runButton,
                        progressBox,
                        progressBar,
                        progressLabel,
                        this::getCheckedRows,
                        stepsPicker::selectedModelIds,
                        this::selectedRegionMode,
                        this::setBatchRunning,
                        this::updateStatusBar,
                        this::loadProjectData,
                        openJobs);
        inferenceRows()
                .addListener(
                        (ListChangeListener<JobRow.Inference>)
                                change -> {
                                    imageTable
                                            .getItems()
                                            .forEach(
                                                    row ->
                                                            runCoordinator
                                                                    .jobs()
                                                                    .latestStatus(row.entry.getID())
                                                                    .ifPresent(
                                                                            row.lastBatchStatus
                                                                                    ::set));
                                    refreshActionControls();
                                });
        inferenceProblem().addListener(obs -> refreshActionControls());
        refreshInference();

        // Re-render when the shared module registry changes (e.g. a completed install or delete),
        // so the Project tab's module columns update without a manual refresh. The registry mutates
        // on the FX thread, so this listener fires on the FX thread.
        registry.asObservable()
                .addListener(
                        (ListChangeListener<InstalledModule>)
                                c -> {
                                    refreshInference();
                                    loadProjectData();
                                });

        refreshActionControls();
        loadProjectData();
    }

    /**
     * Open the installer for a row whose assigned module isn't installed, pre-filled with the ref
     * (from the "⚠ … not installed" Module cell). {@link InstallDialog} validates the ref; a
     * completed install refreshes the registry, which re-renders the row as runnable.
     */
    private void installMissingModule(String ref) {
        try {
            InstallDialog.show(qupath, moduleClient.get(), ref, openJobs);
        } catch (RuntimeException failure) {
            Dialogs.showErrorMessage("Module installation unavailable", describeError(failure));
        }
    }

    /**
     * Discards old project state before starting the asynchronous replacement load. An intervening
     * user action therefore sees no selected images from the previous project.
     */
    private void onProjectChanged() {
        if (runCoordinator != null) refreshInference();
        restoreInferenceScope();
        projectGeneration++;
        metricInvalidatedImageIds.clear();
        summaryData = null;
        imageTable.getItems().clear();
        refreshActionControls();
        loadProjectData();
    }

    /** Reload the project snapshot in the background — no network; modules from the registry. */
    public void loadProjectData() {
        loadProjectData(null);
    }

    /**
     * Apply the summary first, then validate measurements. Completion is display-only: actual image
     * actions must release their resources and busy state before requesting this refresh.
     */
    public void loadProjectData(Runnable onComplete) {
        long generation = ++loadGeneration;
        var project = qupath.getProject();
        if (project == null) {
            refreshSession.cancel();
            showEmptyState("No project open.");
            showLoading(false);
            fireOnComplete(onComplete);
            return;
        }

        showLoading(true);

        refreshSession.request(
                () -> {
                    // This factory runs only when the preceding worker has finished. Pending
                    // requests retain no hierarchy or raw provenance capture.
                    if (!isCurrentLoad(generation, project)) throw new CancellationException();
                    var viewerData = qupath.getImageData();
                    var viewerEntry = viewerData == null ? null : project.getEntry(viewerData);
                    var viewerCapture =
                            viewerEntry == null
                                    ? null
                                    : ProjectSummaryData.captureViewerHierarchy(
                                            viewerData.getHierarchy(), selectedScope());
                    return new ProjectDataLoader.Request(
                            project.getImageList().stream()
                                    .map(ProjectSummaryData.ImageMetadata::capture)
                                    .toList(),
                            Optional.ofNullable(viewerCapture),
                            Optional.ofNullable(viewerEntry),
                            List.copyOf(registry.asObservable()),
                            HistoforgePrefs.localResourceEnvelope(),
                            selectedScope());
                },
                result -> {
                    if (!isCurrentLoad(generation, project)) return;
                    try {
                        modulesById.clear();
                        for (ModuleInfo module : result.modules()) {
                            modulesById.put(module.id(), module);
                        }
                        registerModuleClasses(result.modules());
                        summaryData = result.summary();
                        inferenceScopeControls.refreshClasses(summaryData.annotationClasses());
                        populateUI();
                        displayedSummaryGeneration = generation;
                        groupMetricsBox
                                .getChildren()
                                .setAll(new Label("Checking current measurements…"));
                    } finally {
                        showLoading(false);
                    }
                },
                measured -> {
                    if (!isCurrentLoad(generation, project)) return;
                    summaryData = measured;
                    buildMetricsPane();
                    updateStatusBar();
                },
                failure -> {
                    if (!isCurrentLoad(generation, project)) return;
                    logger.error("Failed to load project data", failure);
                    if (displayedSummaryGeneration == generation) {
                        summaryData = summaryData.withoutMetrics();
                        groupMetricsBox
                                .getChildren()
                                .setAll(
                                        new Label(
                                                "Could not validate measurements: "
                                                        + describeError(failure)));
                    } else {
                        showLoadFailure(failure);
                    }
                    showLoading(false);
                },
                () -> {
                    if (isCurrentLoad(generation, project)) showLoading(false);
                    fireOnComplete(onComplete);
                });
    }

    /**
     * Preserve nonmetric display facts on a transient reload failure, never stale metric values.
     */
    private void showLoadFailure(Exception failure) {
        if (summaryData == null || summaryData.getImageSummaries().isEmpty()) {
            showEmptyState("Failed to load: " + describeError(failure));
            return;
        }
        summaryData = summaryData.withoutMetrics();
        buildMetricsPane();
        for (ImageRowModel row : imageTable.getItems()) {
            ImageSummary summary = summariesByImageId.get(row.entry.getID());
            row.status.set(outputPresence(summary, row.moduleId, selectedRegionMode()));
            row.lastBatchStatus.set(
                    ProjectRowStatus.latest(
                            runCoordinator.latestResult(row.entry.getID(), row.moduleId)));
        }
        statusLabel.setText(
                "⚠ Could not reload project data — showing last snapshot. Click Save Project &"
                        + " Refresh to retry. ("
                        + describeError(failure)
                        + ")");
    }

    /**
     * Format an exception for user-facing error messages. Many JDK / network exceptions have a
     * {@code null} message (e.g. {@code ConnectException} after a server crash), which would
     * otherwise produce strings like {@code "Failed to load: null"}.
     */
    private static String describeError(Throwable t) {
        String msg = t.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : t.getClass().getSimpleName();
    }

    /** A display callback failure must not escape into the action that requested the refresh. */
    private static void fireOnComplete(Runnable onComplete) {
        if (onComplete == null) return;
        try {
            onComplete.run();
        } catch (Exception e) {
            logger.error("loadProjectData completion callback threw", e);
        }
    }

    public boolean isBatchRunning() {
        return batchRunning;
    }

    public CompletableFuture<Void> cancelForCloudUpdate(String deploymentId) {
        return runCoordinator.cancelForCloudUpdate(deploymentId);
    }

    public void finishCloudUpdate() {
        runCoordinator.finishCloudUpdate();
    }

    public void setOnBatchStateChanged(Runnable callback) {
        this.onBatchStateChanged = callback;
    }

    /** Focus the existing group-assignment action after navigation from Train. */
    public void focusGroupAssignment() {
        assignGroupButton.requestFocus();
    }

    /** Hide process-local values immediately after any finalized live hierarchy edit. */
    public void invalidateMetricPresentation() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::invalidateMetricPresentation);
            return;
        }
        // A result captured before this edit may already be queued on the FX thread. Fencing the
        // data-load generation prevents that older callback from restoring stale values after this
        // method has hidden them.
        loadGeneration++;
        if (refreshSession != null) refreshSession.cancel();
        showLoading(false);
        if (summaryData != null) {
            summaryData = summaryData.withoutMetrics();
            buildMetricsPane();
        }
    }

    /** Hide metrics affected by a finalized live hierarchy edit and keep that need visible. */
    public void onHierarchyChanged() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::onHierarchyChanged);
            return;
        }
        if (summaryData != null) {
            // The presentation is invalidated as one project-level snapshot. Remember every
            // displayed metric owner as needing recomputation so edits to another image cannot
            // disappear behind the first invalidation.
            summaryData.cachedMetrics().stream()
                    .map(ProjectSummaryData.CachedImageMetrics::imageId)
                    .forEach(metricInvalidatedImageIds::add);
        }
        invalidateMetricPresentation();
        updateStatusBar();
    }

    /** Hide old-scope metrics before projecting or loading the newly selected scope. */
    void onRegionModeChanged() {
        if (qupath != null && qupath.getProject() != null) {
            try {
                selectedScope().save(qupath.getProject());
            } catch (IOException | RuntimeException failure) {
                showIncompleteProjectSave("inference scope", failure);
            }
        }
        invalidateMetricPresentation();
        refreshOutputStatus();
        updateStatusBar();
        loadProjectData();
    }

    private void populateUI() {
        if (summaryData.getImageSummaries().isEmpty()) {
            metricInvalidatedImageIds.clear();
            showEmptyState("No images in project.");
            return;
        }

        // Entry IDs survive refreshes and do not depend on row order or display names.
        Set<String> previouslySelected =
                imageTable.getItems().stream()
                        .filter(r -> r.selected.get())
                        .map(r -> r.entry.getID())
                        .collect(Collectors.toSet());

        emptyStateBox.setVisible(false);
        emptyStateBox.setManaged(false);
        mainSplitPane.setVisible(true);
        mainSplitPane.setManaged(true);

        var rows = new ArrayList<ImageRowModel>();
        summariesByImageId.clear();
        for (ImageSummary img : summaryData.getImageSummaries()) {
            summariesByImageId.put(img.entry().getID(), img);
            String moduleId = img.metadata().moduleRef();
            ModuleInfo module = moduleId != null ? modulesById.get(moduleId) : null;
            // A ref assigned but absent from the installed set (incl. a stale bare id): flag it in
            // the Module cell (click to install, or re-assign via Set Module). The Run gate is
            // what keeps a not-installed module out of a batch.
            boolean missing = moduleId != null && module == null;

            String outputPresence = outputPresence(img, moduleId, selectedRegionMode());
            String latestResult =
                    runCoordinator
                            .jobs()
                            .latestStatus(img.entry().getID())
                            .orElseGet(
                                    () ->
                                            ProjectRowStatus.latest(
                                                    runCoordinator.latestResult(
                                                            img.entry().getID(), moduleId)));

            var row =
                    new ImageRowModel(
                            img.entry(),
                            img.group(),
                            module != null ? module.name() : null,
                            moduleId,
                            img.roiIds(),
                            outputPresence,
                            latestResult,
                            img.thumbnail(),
                            missing ? moduleId : null,
                            summaryData.scope().selectedClass());
            row.selected.set(previouslySelected.contains(img.entry().getID()));
            row.selected.addListener((o, ov, nv) -> updateStatusBar());
            rows.add(row);
        }
        imageTable.getItems().setAll(rows);

        reconcileMetricInvalidations();

        updateSelectMenuGroups();
        buildMetricsPane();
        updateStatusBar();
    }

    /** Keep the two global choices, then append one choice per currently displayed group. */
    private void updateSelectMenuGroups() {
        var choices = selectMenu.getItems();
        if (choices.size() > 2) choices.remove(2, choices.size());
        var counts =
                imageTable.getItems().stream()
                        .collect(
                                Collectors.groupingBy(
                                        ProjectTabController::resolveGroupName,
                                        java.util.TreeMap::new,
                                        Collectors.counting()));
        if (counts.isEmpty()) return;
        selectMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        counts.forEach(
                (name, count) -> {
                    var item = new MenuItem(name + " (" + count + ")");
                    item.setOnAction(event -> selectGroup(name));
                    selectMenu.getItems().add(item);
                });
    }

    private void selectGroup(String groupName) {
        imageTable
                .getItems()
                .forEach(row -> row.selected.set(groupName.equals(resolveGroupName(row))));
    }

    private static String resolveGroupName(ImageRowModel row) {
        String group = row.group.get();
        return group == null || group.equals("—") ? GroupManager.UNGROUPED : group;
    }

    private void buildMetricsPane() {
        groupMetricsBox.getChildren().clear();
        Node content = metricsTableBuilder.build(summaryData);
        if (content != null) {
            groupMetricsBox.getChildren().add(content);
        }
    }

    @FXML
    private void handleInferenceSettings() {
        try {
            InferenceSettingsDialog.show(qupath, registry);
        } catch (RuntimeException failure) {
            logger.error("Could not open inference settings", failure);
            Dialogs.showErrorMessage("Inference settings", describeError(failure));
        }
    }

    /**
     * Assign a module to the currently checked images. This only updates {@code histoforge:module}
     * metadata and row display state, and persists through {@link ProjectMetadataEditor}. It does
     * not start inference or touch existing per-ROI data.
     *
     * <p>Per-ROI data changes only after an explicit Fill, Redo, or Metrics action commits. Merely
     * changing the assigned module never modifies any hierarchy object.
     */
    @FXML
    private void handleSetModule() {
        List<ImageRowModel> checked = getCheckedRows();
        if (checked.isEmpty()) {
            Dialogs.showWarningNotification("Set Module", "Select images first.");
            return;
        }

        // Label by "name — ref", not name alone: display names are not unique (forking a module
        // keeps its name, so histoforge/leydig and alice/leydig both read "Leydig"). The ref is
        // the map key, so each label is unique and the selection resolves to exactly one module.
        Map<String, ModuleInfo> byLabel = new LinkedHashMap<>();
        for (ModuleInfo m : modulesById.values()) {
            byLabel.put(m.name() + " — " + m.id(), m);
        }
        if (byLabel.isEmpty()) {
            Dialogs.showWarningNotification("Set Module", "No modules available.");
            return;
        }
        List<String> labels = List.copyOf(byLabel.keySet());
        var dialog = new javafx.scene.control.ChoiceDialog<>(labels.get(0), labels);
        dialog.setTitle("Set Module");
        dialog.setHeaderText(
                "Choose a module for "
                        + checked.size()
                        + " selected "
                        + (checked.size() == 1 ? "image" : "images"));
        dialog.setContentText("Module:");
        dialog.showAndWait()
                .ifPresent(
                        selectedLabel -> {
                            ModuleInfo module = byLabel.get(selectedLabel);
                            if (module == null) return;
                            invalidateMetricPresentation();

                            final Optional<Throwable> persistenceFailure;
                            try {
                                persistenceFailure =
                                        ProjectMetadataEditor.setAndSync(
                                                qupath.getProject(),
                                                checked.stream().map(row -> row.entry).toList(),
                                                HistoforgeKeys.MODULE,
                                                module.id());
                            } catch (RuntimeException failure) {
                                logger.error("Failed to assign module", failure);
                                Dialogs.showErrorMessage(
                                        "Set Module Failed",
                                        "Could not apply the module assignment: "
                                                + describeError(failure));
                                return;
                            }

                            for (ImageRowModel row : checked) {
                                row.moduleId = module.id();
                                // The picked module is installed, so the row is no longer missing;
                                // clear before moduleName.set() re-renders the Module cell.
                                row.missingModuleRef = null;
                                row.moduleName.set(module.name());
                                row.status.set(
                                        outputPresence(
                                                summariesByImageId.get(row.entry.getID()),
                                                module.id(),
                                                selectedRegionMode()));
                                row.lastBatchStatus.set(
                                        ProjectRowStatus.latest(
                                                runCoordinator.latestResult(
                                                        row.entry.getID(), module.id())));
                            }
                            reconcileMetricInvalidations();
                            updateStatusBar();
                            persistenceFailure.ifPresent(
                                    failure ->
                                            showIncompleteProjectSave(
                                                    "module assignment", failure));
                            loadProjectData();
                        });
    }

    @FXML
    private void handleAssignGroup() {
        List<ImageRowModel> checked = getCheckedRows();
        if (checked.isEmpty()) {
            Dialogs.showWarningNotification("Assign Group", "Select images first.");
            return;
        }

        var project = qupath.getProject();
        var existing = GroupManager.getAllGroups(project);
        var dialog = new TextInputDialog(existing.stream().findFirst().orElse(""));
        dialog.setTitle("Assign Group");
        dialog.setHeaderText("Group name for " + checked.size() + " image(s)");
        dialog.setContentText("Group:");
        var answer = dialog.showAndWait();
        if (answer.isEmpty()) return;
        invalidateMetricPresentation();
        final GroupManager.Assignment assignment;
        try {
            assignment =
                    GroupManager.assignGroup(
                            qupath.getProject(),
                            checked.stream().map(row -> row.entry).toList(),
                            answer.get());
        } catch (RuntimeException failure) {
            logger.error("Failed to apply group assignment", failure);
            Dialogs.showErrorMessage(
                    "Assign Group Failed",
                    "Could not apply the group assignment: " + describeError(failure));
            return;
        }
        checked.forEach(row -> row.group.set(assignment.group()));
        loadProjectData();
        assignment
                .persistenceFailure()
                .ifPresent(failure -> showIncompleteProjectSave("group assignment", failure));
    }

    private void showIncompleteProjectSave(String change, Throwable failure) {
        logger.error("Project save did not complete after {}", change, failure);
        Dialogs.showErrorMessage(
                "Project Save Incomplete",
                "The "
                        + change
                        + " remains in this project session, but QuPath did not confirm the project save. Use Save Project & Refresh to retry: "
                        + describeError(failure));
    }

    @FXML
    private void handleFill() {
        if (!scopeReady() && !batchRunning) return;
        runCoordinator.startFill();
    }

    @FXML
    private void handleRedo() {
        if (!scopeReady()) return;
        runCoordinator.startRedo();
    }

    @FXML
    private void handleMetrics() {
        if (!scopeReady()) return;
        runCoordinator.startMetrics();
    }

    @FXML
    private void handleExport() {
        if (exportRunning || !scopeReady()) {
            return;
        }
        var project = qupath.getProject();
        List<ImageRowModel> checked = getCheckedRows();
        if (project == null || checked.isEmpty()) {
            Dialogs.showWarningNotification("Export", "Select images first.");
            return;
        }

        List<ProjectExportCoordinator.ImageIntent> intents = new ArrayList<>();
        for (ImageRowModel row : checked) {
            InstalledModule module =
                    row.moduleId == null ? null : registry.get(row.moduleId).orElse(null);
            if (module == null) {
                Dialogs.showWarningNotification(
                        "Export", "Every selected image needs an installed module.");
                return;
            }
            if (!selectedRegionMode().wholeSlide() && row.roiIds.isEmpty()) {
                Dialogs.showWarningNotification(
                        "Export",
                        ProjectRunCoordinator.missingRegionsMessage(
                                List.of(row), selectedRegionMode()));
                return;
            }
            if (module.metricsScript() == null) {
                Dialogs.showWarningNotification(
                        "Export", module.name() + " does not define metrics.groovy.");
                return;
            }
            intents.add(
                    new ProjectExportCoordinator.ImageIntent(
                            row.entry.getID(),
                            ProjectBatchLauncher.regions(row, selectedRegionMode()),
                            resolveGroupName(row),
                            module,
                            row.annotationClass));
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Project Metrics");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel files", "*.xlsx"));
        if (project.getPath() != null) {
            File directory = project.getPath().toFile().getParentFile();
            if (directory != null && directory.isDirectory()) {
                chooser.setInitialDirectory(directory);
            }
        }
        chooser.setInitialFileName("metrics.xlsx");

        File file = chooser.showSaveDialog(imageTable.getScene().getWindow());
        if (file == null) {
            return;
        }
        boolean replace = file.exists();
        if (replace
                && !Dialogs.showYesNoDialog(
                        "Replace export?",
                        file.getName() + " already exists. Replace it atomically?")) {
            return;
        }

        long generation = projectGeneration;
        final ClientBuildIdentity clientBuild;
        try {
            clientBuild = currentClientBuild();
        } catch (RuntimeException failure) {
            logger.error("Could not capture the HistoForge client build", failure);
            Dialogs.showErrorMessage("Export Failed", describeError(failure));
            return;
        }
        setExportRunning(true);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                var artifact =
                                        exportCoordinator.export(
                                                project,
                                                clientBuild,
                                                file.toPath(),
                                                replace
                                                        ? ExportOverwritePolicy.REPLACE_EXISTING
                                                        : ExportOverwritePolicy.FAIL_IF_EXISTS,
                                                intents,
                                                () -> generation != projectGeneration);
                                Platform.runLater(
                                        () -> {
                                            setExportRunning(false);
                                            Dialogs.showInfoNotification(
                                                    "Export",
                                                    "Exported "
                                                            + artifact.sizeBytes()
                                                            + " bytes to "
                                                            + file.getName());
                                        });
                            } catch (CancellationException cancelled) {
                                Platform.runLater(
                                        () -> {
                                            setExportRunning(false);
                                            Dialogs.showWarningNotification(
                                                    "Export", "Export cancelled.");
                                        });
                            } catch (IOException | RuntimeException failure) {
                                logger.error("Export failed", failure);
                                Platform.runLater(
                                        () -> {
                                            setExportRunning(false);
                                            Dialogs.showErrorMessage(
                                                    "Export Failed", describeError(failure));
                                        });
                            }
                        });
    }

    /**
     * Save the viewer's image data through the shared lifecycle owner and reload the project
     * snapshot. The save writes {@code .qpdata} (hierarchy serialization, not WSI pixels) and stays
     * on the FX thread so it cannot race the live hierarchy against concurrent viewer edits.
     */
    @FXML
    private void handleSaveAndRefresh() {
        var project = qupath.getProject();
        if (project == null) {
            loadProjectData(); // handles null → showEmptyState
            return;
        }

        var viewerData = qupath.getImageData();
        var entry = (viewerData != null) ? project.getEntry(viewerData) : null;

        setSaveRefreshRunning(true);

        try {
            if (entry != null) {
                imageLifecycle.saveCurrentViewerImage(entry, viewerData);
            }
        } catch (Exception e) {
            logger.error("Failed to save viewer image data", e);
            Dialogs.showErrorMessage(
                    "Save failed", "Could not save viewer image: " + describeError(e));
            setSaveRefreshRunning(false);
            return;
        }
        try {
            project.syncChanges();
        } catch (IOException | RuntimeException failure) {
            logger.error("Failed to sync project changes", failure);
            Dialogs.showErrorMessage(
                    "Project save failed",
                    "Image data was saved, but the project could not be synchronized: "
                            + describeError(failure));
            setSaveRefreshRunning(false);
            return;
        }
        setSaveRefreshRunning(false);
        loadProjectData();
    }

    private List<ImageRowModel> getCheckedRows() {
        return imageTable.getItems().stream().filter(r -> r.selected.get()).toList();
    }

    /** Snapshot the current FX-owned preference into one immutable scientific identity. */
    private ClientBuildIdentity currentClientBuild() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("client build must be captured on the FX thread");
        }
        return clientBuildFactory.apply(HistoforgePrefs.localResourceEnvelope());
    }

    private static String outputPresence(ImageSummary summary, String moduleRef, Mode regionMode) {
        if (summary == null) {
            return "—";
        }
        if (regionMode.wholeSlide()) {
            return ProjectRowStatus.slideOutputPresence(
                    moduleRef != null && summary.hasSlideOutput(moduleRef),
                    summary.outputStateInvalid());
        }
        int present = moduleRef == null ? 0 : summary.outputRoiCount(moduleRef);
        return ProjectRowStatus.outputPresence(
                present, summary.roiCount(), summary.outputStateInvalid());
    }

    private void refreshOutputStatus() {
        if (summaryData == null) {
            return;
        }
        Mode mode = selectedRegionMode();
        for (ImageRowModel row : imageTable.getItems()) {
            row.status.set(
                    outputPresence(summariesByImageId.get(row.entry.getID()), row.moduleId, mode));
        }
    }

    /**
     * Remove invalidations only when their image or metrics-capable assignment no longer exists.
     */
    private void reconcileMetricInvalidations() {
        Set<String> retained =
                ProjectMetricsActionGate.retainedInvalidations(
                        metricInvalidatedImageIds, imageTable.getItems(), registry::get);
        metricInvalidatedImageIds.clear();
        metricInvalidatedImageIds.addAll(retained);
    }

    /** Exact current-scope cache hits whose module still matches the row's current assignment. */
    private Set<String> currentMetricImageIds() {
        if (summaryData == null) {
            return Set.of();
        }
        Map<String, String> assignedModules =
                imageTable.getItems().stream()
                        .collect(
                                Collectors.toMap(
                                        row -> row.entry.getID(),
                                        row -> row.moduleId == null ? "" : row.moduleId));
        return summaryData.cachedMetrics().stream()
                .filter(
                        metric ->
                                metric.module()
                                        .moduleRef()
                                        .toString()
                                        .equals(assignedModules.get(metric.imageId())))
                .map(ProjectSummaryData.CachedImageMetrics::imageId)
                .collect(Collectors.toSet());
    }

    private Mode selectedRegionMode() {
        return selectedScope().mode();
    }

    private ProjectInferenceScope selectedScope() {
        return inferenceScopeControls.selected();
    }

    private void restoreInferenceScope() {
        inferenceScopeControls.restore(
                qupath.getProject() == null
                        ? ProjectInferenceScope.defaults()
                        : ProjectInferenceScope.read(qupath.getProject()));
    }

    private boolean scopeReady() {
        return !dataLoading
                && summaryData != null
                && summaryData.scope().equals(selectedScope())
                && (selectedRegionMode() != Mode.ANNOTATIONS_BY_CLASS
                        || selectedScope().selectedClass().isPresent());
    }

    /**
     * Register each module's classifications in QuPath's sidebar so the class names are available
     * for display/selection. Idempotent; runs on the FX thread (called from the load lambda).
     */
    private void registerModuleClasses(List<ModuleInfo> modules) {
        var available = qupath.getAvailablePathClasses();
        for (ModuleInfo module : modules) {
            for (String name : module.classifications()) {
                PathClass pc = PathClass.fromString(name);
                if (!available.contains(pc)) {
                    available.add(pc);
                }
            }
        }
    }

    private void setBatchRunning(boolean running) {
        if (running && !batchRunning) {
            // A model or Metrics action may publish a new hierarchy. Hide the previous exact
            // values before work starts so a later reload failure cannot leave them presented as
            // current.
            invalidateMetricPresentation();
        }
        this.batchRunning = running;
        refreshActionControls();
        if (onBatchStateChanged != null) onBatchStateChanged.run();
    }

    /** Keep explicit image actions and project metadata changes out of one export transaction. */
    private void setExportRunning(boolean running) {
        if (running && !exportRunning) invalidateMetricPresentation();
        exportRunning = running;
        refreshActionControls();
    }

    private void setSaveRefreshRunning(boolean running) {
        if (running && !saveRefreshRunning) invalidateMetricPresentation();
        saveRefreshRunning = running;
        refreshActionControls();
    }

    private void showEmptyState(String message) {
        // A successful reload of an emptied project must not leave hidden, actionable rows from
        // the previous snapshot behind the empty-state panel.
        imageTable.getItems().clear();
        summariesByImageId.clear();
        emptyStateLabel.setText(message);
        emptyStateBox.setVisible(true);
        emptyStateBox.setManaged(true);
        mainSplitPane.setVisible(false);
        mainSplitPane.setManaged(false);
        groupMetricsBox.getChildren().clear();
        statusLabel.setText("");
        refreshActionControls();
    }

    private void showLoading(boolean loading) {
        dataLoading = loading;
        loadingIndicator.setVisible(loading);
        loadingIndicator.setManaged(loading);
        refreshActionControls();
    }

    /** Whether an async load still belongs to the same current QuPath project and request. */
    private boolean isCurrentLoad(long generation, Object project) {
        return generation == loadGeneration && qupath.getProject() == project;
    }

    /**
     * Status bar — project totals and explicit selection only. Output presence is intentionally not
     * an execution gate: Fill may skip present outputs for cost, while Redo and Metrics remain
     * explicit user actions.
     */
    private void updateStatusBar() {
        if (summaryData == null) {
            refreshActionControls();
            return;
        }
        int totalImages = summaryData.getImageSummaries().size();
        int totalRois = summaryData.getProjectRois().size();
        String text = totalImages + " images · " + totalRois + " ROIs";

        List<ImageRowModel> checked = getCheckedRows();
        if (!checked.isEmpty()) {
            if (selectedRegionMode().wholeSlide()) {
                text +=
                        "  |  "
                                + checked.size()
                                + " selected · whole slide · hierarchy edits will conflict";
            } else {
                int selectedRois = checked.stream().mapToInt(row -> row.roiCount).sum();
                text += "  |  " + checked.size() + " selected · " + selectedRois + " ROIs";
            }
        }
        if (!ProjectMetricsActionGate.outstandingRecomputations(
                        metricInvalidatedImageIds,
                        imageTable.getItems(),
                        currentMetricImageIds(),
                        selectedRegionMode().wholeSlide(),
                        registry::get)
                .isEmpty()) {
            text += "  |  ⚠ Metrics need recomputation";
        }
        statusLabel.setText(text);

        refreshActionControls();
    }

    /** Project the current operation and selection state into the toolbar controls. */
    private void refreshActionControls() {
        if (stepsPicker == null || imageTable == null) {
            return;
        }
        List<ImageRowModel> checked = getCheckedRows();
        boolean unresolved =
                runCoordinator != null
                        && checked.stream()
                                .anyMatch(row -> runCoordinator.jobs().blocks(row.entry.getID()));
        ActionAvailability availability =
                actionAvailability(
                        batchRunning, exportRunning, saveRefreshRunning, !checked.isEmpty());

        setModuleButton.setDisable(!availability.generalActionsEnabled());
        inferenceSettingsButton.setDisable(
                !availability.generalActionsEnabled() || qupath.getProject() == null);
        assignGroupButton.setDisable(!availability.generalActionsEnabled());
        saveAndRefreshButton.setDisable(!availability.generalActionsEnabled());
        regionModeComboBox.setDisable(!availability.generalActionsEnabled());
        annotationClassComboBox.setDisable(!availability.generalActionsEnabled());

        redoButton.setDisable(
                !availability.selectedActionsEnabled() || !scopeReady() || unresolved);
        ProjectMetricsActionGate.Availability metricsAvailability =
                ProjectMetricsActionGate.evaluate(
                        !availability.generalActionsEnabled() || !scopeReady(),
                        checked,
                        registry::get);
        exportButton.setDisable(!metricsAvailability.enabled());
        metricsButton.setDisable(!metricsAvailability.enabled() || unresolved);
        exportButton.setTooltip(actionTooltip(metricsAvailability.explanation()));
        metricsButton.setTooltip(
                actionTooltip(
                        unresolved
                                ? "Unfinished analysis — open Jobs to continue or stop it."
                                : metricsAvailability.explanation()));
        if (!batchRunning) {
            // While a batch is active, its session owns this button as Cancel batch or Open Jobs.
            runButton.setDisable(
                    !availability.selectedActionsEnabled() || !scopeReady() || unresolved);
            runButton.setTooltip(
                    unresolved
                            ? actionTooltip(
                                    "Unfinished analysis — open Jobs to continue or stop it.")
                            : actionTooltip(
                                    "Add missing results for the selected models. Existing results from those models may be kept "
                                            + "to avoid rerunning them; this does not check their completeness or accuracy. "
                                            + "Use Redo to replace them."));
        }

        stepsPicker.update(
                commonModuleAcrossChecked(checked), !availability.generalActionsEnabled());
    }

    private static HistoforgeTooltip actionTooltip(String text) {
        var tooltip = new HistoforgeTooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        return tooltip;
    }

    static ActionAvailability actionAvailability(
            boolean batchRunning,
            boolean exportRunning,
            boolean saveRefreshRunning,
            boolean hasSelection) {
        boolean busy = batchRunning || exportRunning || saveRefreshRunning;
        return new ActionAvailability(!busy, !busy && hasSelection);
    }

    record ActionAvailability(boolean generalActionsEnabled, boolean selectedActionsEnabled) {}

    @Override
    public ObservableList<JobRow.Inference> inferenceRows() {
        return runCoordinator.jobs().rows();
    }

    @Override
    public ReadOnlyStringProperty inferenceProblem() {
        return runCoordinator.jobs().problemProperty();
    }

    @Override
    public void refreshInference() {
        runCoordinator.jobs().refresh();
    }

    @Override
    public void reconnect(JobRow.Inference row) {
        runCoordinator.reconnect(row);
    }

    @Override
    public void stop(JobRow.Inference row) {
        runCoordinator.stop(row);
    }

    @Override
    public void clearHistory(JobRow.Inference row) {
        runCoordinator.clearHistory(row);
    }

    public void setInferenceInspectionAvailable(boolean available) {
        runCoordinator.jobs().setCloudInspectionAvailable(available);
    }

    /**
     * The one installed module every checked row is assigned to, or {@code null} if there are no
     * checked rows, at least one is missing an assignment, they span multiple modules, or the
     * shared module isn't installed. Drives the steps picker's enabled state.
     */
    private InstalledModule commonModuleAcrossChecked(List<ImageRowModel> checked) {
        if (checked.isEmpty()) {
            return null;
        }
        String firstRef = null;
        for (ImageRowModel row : checked) {
            if (row.moduleId == null) {
                return null;
            }
            if (firstRef == null) {
                firstRef = row.moduleId;
            } else if (!firstRef.equals(row.moduleId)) {
                return null;
            }
        }
        return registry.get(firstRef).orElse(null);
    }
}
