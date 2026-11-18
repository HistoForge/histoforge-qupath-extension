package qupath.ext.histoforge.ui.app;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.ConnectionProbe;
import qupath.ext.histoforge.client.DeploymentStore;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.contract.AdminJobCodec.JobPhase;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.pipeline.ImageActionRegistry;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.platform.ClientBuildIdentityProvider;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;
import qupath.ext.histoforge.roi.HierarchyListener;
import qupath.ext.histoforge.ui.HistoforgePrefs;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.HistoforgeTooltip;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.ui.jobs.JobRow;
import qupath.ext.histoforge.ui.jobs.JobsPoller;
import qupath.ext.histoforge.ui.jobs.JobsTabController;
import qupath.ext.histoforge.ui.modules.ModulesTabController;
import qupath.ext.histoforge.ui.project.ProjectTabController;
import qupath.ext.histoforge.ui.training.TrainingTabController;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

/**
 * Controller for the HistoForge window. Hosts a four-tab layout (Project · Modules · Train · Jobs)
 * beneath a header (connection chip + Settings gear) and an offline banner.
 *
 * <p>Project and Jobs remain available without a configured backend so retained analyses can be
 * inspected and saved. Cloud-only panes are initialized when a connection is configured. Live
 * reachability is probed off the FX thread and shown by the header chip and inline banner.
 *
 * <p>MainController owns the shared back-end state: the {@link ModuleGatewayClient}, the shared
 * authenticated {@link GatewayTransport}, the {@link ModulesRegistry}, and the application-scoped
 * image-action and metric-cache owners. It keeps only the policy for a finished job while {@link
 * JobsPoller} owns fetching and change detection.
 */
public class MainController {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);

    // Header + banner live above the swapped content slot (child index 2 of root).
    @FXML private VBox root;
    @FXML private Label connectionProgress;
    @FXML private Label connectionChip;
    @FXML private HBox connectionBanner;
    @FXML private Label bannerLabel;
    @FXML private Button bannerButton;
    @FXML private Button settingsButton;
    @FXML private Label jobsBadge;

    private QuPathGUI qupath;
    private HierarchyListener hierarchyListener;
    private Stage window;

    private Node projectPane;
    private ProjectTabController projectTabController;
    private TrainingTabController trainingTabController;
    private ModulesTabController modulesTabController;
    private TabPane tabPane;
    private Tab jobsTab; // held so the header badge can jump to it
    private Tab modulesTab;
    private Tab trainTab;

    // Shared owners survive connection changes; authenticated clients are created on first use.
    private GatewayTransport transport;
    private ModuleGatewayClient moduleClient;
    private ModulesRegistry modulesRegistry;
    private JobsPoller jobsPoller;
    private ImageActionRegistry imageActionRegistry;
    private ImageLifecycle imageLifecycle;
    private MetricsEngine metricsEngine;
    private ClientBuildIdentity packagedClientBuild;
    private SettingsDialog settingsDialog;
    private SetupPane setupPane;
    private long connectionProbeGeneration;
    private boolean cloudUpdating;

    @FXML
    private void initialize() {
        settingsButton.setOnAction(e -> openSettings());
        connectionProgress.managedProperty().bind(connectionProgress.visibleProperty());
        connectionChip.getStyleClass().add(HistoforgeStyles.CONNECTION_INDICATOR);
        connectionChip.setOnMouseClicked(e -> openSettings());
        HistoforgeStyles.setSemantic(jobsBadge, HistoforgeStyles.BUSY);
        HistoforgeStyles.setClickable(jobsBadge, true);
        jobsBadge.setTooltip(new HistoforgeTooltip("Active jobs: click to view"));
        jobsBadge.setOnMouseClicked(e -> selectJobsTab());
    }

    /**
     * Wire dependencies after FXML loading. Called by {@link
     * qupath.ext.histoforge.HistoforgeExtension} once the window has been built.
     */
    public void init(QuPathGUI qupath, HierarchyListener hierarchyListener, Stage window) {
        this.qupath = qupath;
        this.hierarchyListener = hierarchyListener;
        this.window = window;
        window.addEventHandler(
                WindowEvent.WINDOW_HIDDEN,
                event -> {
                    if (setupPane != null) setupPane.clearToken();
                });
        window.addEventHandler(
                WindowEvent.WINDOW_SHOWN,
                event -> {
                    if (projectTabController != null) projectTabController.refreshInference();
                    if (jobsTab != null && jobsTab.isSelected()) jobsPoller.refresh();
                });

        showTabs();
        probeConnectionAsync();
    }

    /** Show the main tabs and select Project. */
    public void selectProjectTab() {
        showTabs();
        if (tabPane != null) {
            tabPane.getSelectionModel().select(0);
        }
    }

    // ── Panel gate ──────────────────────────────────────────────────

    /** Show (lazy-build) the TabPane, swapping out any setup pane. */
    private void showTabs() {
        if (setupPane != null) {
            setupPane.clearToken();
            setupPane = null;
        }
        if (tabPane == null) {
            buildTabPane();
        }
        if (tabPane == null) {
            return; // load failed; error already logged
        }
        setContent(tabPane);
        window.setMinWidth(700);
        if (window.getWidth() < 700) {
            window.setWidth(700);
        }
    }

    /** Show the onboarding/reconnect pane in place of the tabs. */
    private void showSetup() {
        setChip(HistoforgeStyles.NEUTRAL, "Not connected");
        hideBanner();
        if (setupPane != null) setupPane.clearToken();
        setupPane = new SetupPane(this::onSetupConnected);
        Button back = new Button("Back to Project and Jobs");
        back.setOnAction(
                event -> {
                    showTabs();
                    probeConnectionAsync();
                });
        ScrollPane setupScroll = new ScrollPane(setupPane.getNode());
        setupScroll.setPadding(Insets.EMPTY);
        setupScroll.setFitToWidth(true);
        setupScroll.setFitToHeight(true);
        VBox content = new VBox(8, back, setupScroll);
        VBox.setMargin(back, new Insets(8, 16, 0, 16));
        VBox.setVgrow(setupScroll, Priority.ALWAYS);
        setContent(content);
    }

    private void onSetupConnected() {
        showTabs();
        probeConnectionAsync();
    }

    /** Replace the content slot (root child 2), keeping the header + banner above it. */
    private void setContent(Node content) {
        VBox.setVgrow(content, Priority.ALWAYS);
        var children = root.getChildren();
        if (children.size() > 2) {
            if (children.get(2) == content) return;
            children.set(2, content);
        } else {
            children.add(content);
        }
    }

    // ── Connection state (chip + banner) ────────────────────────────

    /** Probe reachability off the FX thread, then render the chip/banner. */
    private void probeConnectionAsync() {
        if (cloudUpdating) return;
        initializeCloudTabs();
        ModuleGatewayClient connected = moduleClient;
        long generation = ++connectionProbeGeneration;
        renderChecking();
        Thread.ofVirtual()
                .start(
                        () -> {
                            DeploymentStore store = new DeploymentStore();
                            ConnectionProbe.Result status =
                                    connected == null
                                            ? ConnectionProbe.probe(store)
                                            : ConnectionProbe.probe(store, connected);
                            Platform.runLater(
                                    () -> {
                                        if (generation == connectionProbeGeneration) {
                                            applyConnectionStatus(status);
                                        }
                                    });
                        });
    }

    private void applyConnectionStatus(ConnectionProbe.Result result) {
        if (projectTabController != null)
            projectTabController.setInferenceInspectionAvailable(result.inspectionSupported());
        switch (result.status()) {
            case CONNECTED -> renderConnected();
            case UNREACHABLE -> renderUnreachable();
            case AUTHENTICATION_REQUIRED -> renderAuthenticationRequired();
            case INCOMPATIBLE -> renderIncompatible(result);
            case IDENTITY_MISMATCH -> {
                setChip(HistoforgeStyles.ERROR, "Connection identity changed");
                showBanner(
                        "Connection identity changed. Reconnect in Settings.",
                        "Settings",
                        this::openSettings);
            }
            case RESTART_REQUIRED -> renderRestartRequired();
            case NOT_CONFIGURED -> {
                setChip(HistoforgeStyles.NEUTRAL, "Cloud backend not configured");
                showBanner(
                        "Connect to run new analyses. Project results remain available.",
                        "Set up cloud",
                        this::showSetup);
            }
        }
        // A release floor blocks new work, not inspection/cancellation of existing work.
        // This requires a complete, identity-bound, schema-compatible handshake.
        if (result.inspectionSupported()) {
            if (jobsPoller != null) jobsPoller.start();
            refreshModules();
        }
    }

    /**
     * Re-fetch the installed modules into the shared registry off the FX thread, now that the
     * gateway is reachable. Runs on every (re)connect; a failed fetch keeps the previous list, and
     * the registry's change notification re-renders the Project tab once the modules land.
     */
    private void refreshModules() {
        if (modulesRegistry == null) {
            return;
        }
        modulesRegistry.refresh();
    }

    // ── Jobs ────────────────────────────────────────────────────────

    /** Header {@code ⤓ N} badge: the active-job count, hidden when nothing is in flight. */
    private void updateJobsBadge() {
        long active = jobsPoller.jobs().stream().filter(JobRow::isRunning).count();
        long attention = jobsPoller.jobs().stream().filter(JobRow::needsAttention).count();
        jobsBadge.setText(
                (active > 0 ? "⤓ " + active : "") + (attention > 0 ? "  ⚠ " + attention : ""));
        jobsBadge.setVisible(active + attention > 0);
        jobsBadge.setManaged(active + attention > 0);
    }

    /**
     * What to do about a job the {@link JobsPoller} saw finish (once per job; jobs already finished
     * at the first poll are adopted silently): a failure is logged and notified while its typed
     * diagnostic remains available in the Jobs row. Every terminal mutation outcome then refreshes
     * the registry off the FX thread because a failed or ambiguous operation may still have crossed
     * a complete module replacement boundary.
     */
    private void onJobDone(JobRow job) {
        if (job.failure().isPresent()) {
            String message = job.failure().orElseThrow().message();
            logger.error("{} {} ended {}: {}", job.type(), job.name(), job.status(), message);
            String title =
                    job instanceof JobRow.Admin admin && admin.job().phase() == JobPhase.PARTIAL
                            ? "Job partially completed"
                            : "Job failed";
            Dialogs.showErrorNotification(title, job.type() + " " + job.name() + ": " + message);
        }
        refreshModules();
    }

    private void selectJobsTab() {
        showTabs();
        if (projectTabController != null) projectTabController.refreshInference();
        if (tabPane != null && jobsTab != null) {
            if (jobsTab.isSelected()) {
                jobsPoller.refresh();
            } else {
                tabPane.getSelectionModel().select(jobsTab); // selection requests the refresh
            }
        }
    }

    private void selectModule(String ref) {
        if (tabPane != null && modulesTabController != null) {
            modulesTabController.showModule(ref);
            tabPane.getSelectionModel().select(modulesTab);
        }
    }

    private void renderChecking() {
        setChip(HistoforgeStyles.NEUTRAL, "Checking connection…");
        connectionProgress.setVisible(true);
    }

    private void renderConnected() {
        setChip(HistoforgeStyles.SUCCESS, "Connected");
        hideBanner();
    }

    private void renderUnreachable() {
        setChip(HistoforgeStyles.WARNING, "Backend not reachable");
        showBanner("Backend not reachable.", "Retry", this::probeConnectionAsync);
    }

    private void renderAuthenticationRequired() {
        setChip(HistoforgeStyles.ERROR, "Gateway credential rejected");
        showBanner("Gateway credential expired or was revoked.", "Reconnect", this::showSetup);
    }

    private void renderIncompatible(ConnectionProbe.Result result) {
        setChip(HistoforgeStyles.ERROR, "Cloud backend is not compatible");
        showBanner(result.compatibility().orElseThrow().message(), "Settings", this::openSettings);
    }

    private void renderRestartRequired() {
        setChip(HistoforgeStyles.ERROR, "Connection changed: restart QuPath");
        showBanner(
                "The configured gateway changed. Restart QuPath to use the new connection.",
                "Settings",
                this::openSettings);
    }

    private void setChip(String semanticStyle, String tooltip) {
        connectionProgress.setVisible(false);
        HistoforgeStyles.setSemantic(connectionChip, semanticStyle);
        HistoforgeStyles.setClickable(connectionChip, true);
        connectionChip.setStyle("-fx-font-size: 14;");
        connectionChip.setTooltip(new HistoforgeTooltip(tooltip));
    }

    private void openSettings() {
        if (settingsDialog == null) {
            settingsDialog =
                    new SettingsDialog(
                            window,
                            () -> moduleClient,
                            this::onSettingsConnectionChecked,
                            this::stopLocalWorkForUpdate,
                            this::finishCloudUpdate);
        }
        settingsDialog.show();
    }

    /** Reuse Settings' authenticated result without starting a second connection check. */
    private void onSettingsConnectionChecked(ConnectionProbe.Result result) {
        if (cloudUpdating) return;
        initializeCloudTabs();
        connectionProbeGeneration++;
        applyConnectionStatus(result);
    }

    private CompletableFuture<Void> stopLocalWorkForUpdate(String deploymentId) {
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        Platform.runLater(
                () -> {
                    cloudUpdating = true;
                    connectionProbeGeneration++;
                    if (tabPane != null) tabPane.setDisable(true);
                    bannerButton.setDisable(true);
                    setChip(HistoforgeStyles.BUSY, "Updating cloud backend…");
                    try {
                        CompletableFuture.allOf(
                                        projectTabController == null
                                                ? CompletableFuture.completedFuture(null)
                                                : projectTabController.cancelForCloudUpdate(
                                                        deploymentId),
                                        trainingTabController == null
                                                ? CompletableFuture.completedFuture(null)
                                                : trainingTabController.cancelForCloudUpdate())
                                .whenComplete(
                                        (ignored, failure) -> {
                                            if (failure == null) stopped.complete(null);
                                            else stopped.completeExceptionally(failure);
                                        });
                    } catch (RuntimeException failure) {
                        stopped.completeExceptionally(failure);
                    }
                });
        return stopped;
    }

    private void finishCloudUpdate() {
        if (!cloudUpdating) return;
        cloudUpdating = false;
        if (projectTabController != null) projectTabController.finishCloudUpdate();
        if (trainingTabController != null) trainingTabController.finishCloudUpdate();
        if (tabPane != null) tabPane.setDisable(false);
        bannerButton.setDisable(false);
    }

    private void showBanner(String text, String buttonText, Runnable action) {
        bannerLabel.setText(text);
        bannerButton.setText(buttonText);
        bannerButton.setOnAction(e -> action.run());
        connectionBanner.setVisible(true);
        connectionBanner.setManaged(true);
    }

    private void hideBanner() {
        connectionBanner.setVisible(false);
        connectionBanner.setManaged(false);
    }

    // ── TabPane assembly ────────────────────────────────────────────

    /** Build the four-tab layout on first use. Each tab loads its separate controller from FXML. */
    private void buildTabPane() {
        // These owners are local. Connecting later must not replace an active recovery owner.
        if (modulesRegistry == null) {
            modulesRegistry = new ModulesRegistry(this::requireModuleClient);
            imageActionRegistry = new ImageActionRegistry();
            imageLifecycle = new ImageLifecycle(qupath);
            metricsEngine = new MetricsEngine();
            jobsPoller =
                    new JobsPoller(
                            this::requireModuleClient,
                            this::requireTransport,
                            this::onJobDone,
                            this::refreshModules);
            jobsPoller.jobs().addListener((ListChangeListener<JobRow>) c -> updateJobsBadge());
        }

        loadProjectPane();
        if (projectPane == null) {
            return;
        }

        Tab projectTab = new Tab("Project", projectPane);

        modulesTab = new Tab("Modules", placeholder("Connect to manage cloud modules."));

        trainTab = new Tab("Train", placeholder("Connect to train models."));
        trainTab.setOnSelectionChanged(
                event -> {
                    if (trainingTabController != null) {
                        trainingTabController.setActive(trainTab.isSelected());
                    }
                });

        jobsTab = new Tab("Jobs", loadJobsPane());
        jobsTab.setOnSelectionChanged(
                event -> {
                    if (jobsTab.isSelected()) {
                        jobsPoller.refresh();
                        projectTabController.refreshInference();
                    }
                });

        tabPane = new TabPane(projectTab, modulesTab, trainTab, jobsTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    }

    /** Initialize cloud-only panes without rebuilding local Project/Jobs state. */
    private void initializeCloudTabs() {
        if (tabPane == null || new DeploymentStore().load().isEmpty()) return;
        requireModuleClient();
        if (modulesTabController == null) modulesTab.setContent(loadModulesPane());
        if (trainingTabController == null) {
            trainTab.setContent(loadTrainingPane());
            if (trainingTabController != null)
                trainingTabController.setActive(trainTab.isSelected());
        }
    }

    private synchronized GatewayTransport requireTransport() {
        if (transport == null) transport = new GatewayTransport(new DeploymentStore());
        return transport;
    }

    private synchronized ModuleGatewayClient requireModuleClient() {
        if (moduleClient == null) moduleClient = new ModuleGatewayClient(requireTransport());
        return moduleClient;
    }

    /** Explain why a cloud-only tab is unavailable before backend setup. */
    private static Node placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add(HistoforgeStyles.MUTED);
        StackPane pane = new StackPane(label);
        pane.setAlignment(Pos.CENTER);
        return pane;
    }

    /** Load the Project tab's content from FXML and wire its controller. */
    private void loadProjectPane() {
        try {
            FXMLLoader loader =
                    new FXMLLoader(ProjectTabController.class.getResource("project_tab.fxml"));
            loader.setClassLoader(getClass().getClassLoader());
            projectPane = loader.load();
            projectTabController = loader.getController();
            projectTabController.init(
                    qupath,
                    this::requireTransport,
                    this::requireModuleClient,
                    modulesRegistry,
                    this::requireClientBuild,
                    imageActionRegistry,
                    imageLifecycle,
                    metricsEngine,
                    this::selectJobsTab);
            projectTabController.setOnBatchStateChanged(
                    () -> {
                        boolean running = projectTabController.isBatchRunning();
                        hierarchyListener.setBatchRunning(running);
                        if (!running && trainingTabController != null) {
                            trainingTabController.invalidateProjectCapture();
                        }
                    });
            hierarchyListener.setOnHierarchyChanged(
                    () -> {
                        projectTabController.onHierarchyChanged();
                        if (trainingTabController != null) {
                            trainingTabController.invalidateProjectCapture();
                        }
                    });
        } catch (IOException e) {
            logger.error("Failed to load Project pane", e);
        }
    }

    /** Load the Modules tab's content from FXML and wire its controller. */
    private Node loadModulesPane() {
        try {
            FXMLLoader loader =
                    new FXMLLoader(ModulesTabController.class.getResource("modules_tab.fxml"));
            loader.setClassLoader(getClass().getClassLoader());
            Node pane = loader.load();
            ModulesTabController controller = loader.getController();
            modulesTabController = controller;
            controller.init(
                    qupath,
                    moduleClient,
                    modulesRegistry,
                    this::selectJobsTab,
                    jobsPoller::refresh);
            return pane;
        } catch (IOException e) {
            logger.error("Failed to load Modules pane", e);
            return placeholder("Modules failed to load");
        }
    }

    /** Load the Jobs tab's content from FXML and wire its controller. */
    private Node loadJobsPane() {
        try {
            FXMLLoader loader =
                    new FXMLLoader(JobsTabController.class.getResource("jobs_tab.fxml"));
            loader.setClassLoader(getClass().getClassLoader());
            Node pane = loader.load();
            JobsTabController controller = loader.getController();
            controller.init(
                    this::requireModuleClient,
                    this::requireTransport,
                    jobsPoller,
                    modulesRegistry,
                    this::selectModule,
                    projectTabController);
            return pane;
        } catch (IOException e) {
            logger.error("Failed to load Jobs pane", e);
            return placeholder("Jobs failed to load");
        }
    }

    /** Load the Train tab and share the same catalog, transport, and image lifecycle owners. */
    private Node loadTrainingPane() {
        try {
            FXMLLoader loader =
                    new FXMLLoader(TrainingTabController.class.getResource("training_tab.fxml"));
            loader.setClassLoader(getClass().getClassLoader());
            Node pane = loader.load();
            trainingTabController = loader.getController();
            trainingTabController.init(
                    qupath,
                    transport,
                    moduleClient,
                    modulesRegistry,
                    this::requireClientBuild,
                    imageLifecycle,
                    this::selectJobsTab,
                    this::selectProjectGroupAssignment);
            return pane;
        } catch (IOException e) {
            logger.error("Failed to load Train pane", e);
            return placeholder("Training failed to load");
        }
    }

    private void selectProjectGroupAssignment() {
        selectProjectTab();
        if (projectTabController != null) {
            projectTabController.focusGroupAssignment();
        }
    }

    /** Capture the packaged build once and snapshot the current explicit envelope for each call. */
    private ClientBuildIdentity requireClientBuild() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("client build must be captured on the FX thread");
        }
        return requireClientBuild(HistoforgePrefs.localResourceEnvelope());
    }

    /** Derive an identity from an envelope already captured by its FX-thread owner. */
    private synchronized ClientBuildIdentity requireClientBuild(LocalResourceEnvelope envelope) {
        if (packagedClientBuild == null) {
            packagedClientBuild = ClientBuildIdentityProvider.capture();
        }
        return ClientBuildIdentityProvider.withLocalResourceEnvelope(packagedClientBuild, envelope);
    }
}
