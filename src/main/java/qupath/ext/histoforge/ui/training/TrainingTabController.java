package qupath.ext.histoforge.ui.training;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.TrainingDatasetGatewayClient;
import qupath.ext.histoforge.client.TrainingRunGatewayClient;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.data.GroupManager;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.training.TrainingAssignment;
import qupath.ext.histoforge.training.TrainingDatasetUploader;
import qupath.ext.histoforge.training.TrainingDatasetWorkflow;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder.ProjectPlan;
import qupath.ext.histoforge.training.TrainingProjectIndex;
import qupath.ext.histoforge.training.TrainingProjectIndex.SplitUnit;
import qupath.ext.histoforge.training.TrainingRunDraft;
import qupath.ext.histoforge.training.TrainingSplitReadiness;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.InformationButton;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.util.Errors;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.Project;

/** Coordinates project capture, split editing, and submission from the Train tab. */
public final class TrainingTabController {

    private static final Logger logger = LoggerFactory.getLogger(TrainingTabController.class);

    @FXML private ComboBox<String> moduleComboBox;
    @FXML private ComboBox<String> modelComboBox;
    @FXML private VBox sourceDetailsBox;
    @FXML private Label availabilityLabel;
    @FXML private Label projectLabel;
    @FXML private Button refreshProjectButton;
    @FXML private VBox resultBox;
    @FXML private VBox settingsBox;
    @FXML private Spinner<Integer> validationPercentSpinner;
    @FXML private ComboBox<SplitUnit> splitUnitComboBox;
    @FXML private Button generateSplitButton;
    @FXML private HBox splitGuidanceBox;
    @FXML private Label splitGuidanceLabel;
    @FXML private Hyperlink assignGroupsLink;
    @FXML private HBox assignmentHeadingBox;
    @FXML private Label assignmentStateLabel;
    @FXML private Label trainingAssignmentLabel;
    @FXML private TableView<TrainingAssignmentTable.DisplayRow> trainingAssignmentTable;
    @FXML private VBox validationAssignmentBox;
    @FXML private Label validationAssignmentLabel;
    @FXML private TableView<TrainingAssignmentTable.DisplayRow> validationAssignmentTable;
    @FXML private Button manageDatasetsButton;
    @FXML private Button exportDatasetButton;
    @FXML private Button startTrainingButton;
    @FXML private VBox progressBox;
    @FXML private Label progressLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Button cancelButton;

    private QuPathGUI qupath;
    private GatewayTransport transport;
    private ModuleGatewayClient moduleClient;
    private Supplier<ClientBuildIdentity> clientBuildSupplier;
    private ImageLifecycle imageLifecycle;
    private TrainingAssignmentTable assignmentView;
    private TrainingModelPicker modelPicker;
    private ProjectPlan capturedProject;
    private ProjectSignature capturedProjectSignature;
    private boolean capturedProjectStale;
    private long projectCaptureGeneration;
    private TrainingAssignment assignment;
    private boolean assignmentOutdated;
    private boolean active;
    private boolean cloudUpdating;
    private TrainingOperationRunner operationRunner;
    private TrainingRunSubmission submission;
    private TrainingRunForm runForm;
    private Runnable showJobs;
    private Runnable showProjectGroupAssignment;

    @FXML
    private void initialize() {
        validationPercentSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 99, 20));
        validationPercentSpinner.setEditable(true);
        splitUnitComboBox.getItems().setAll(SplitUnit.values());
        splitUnitComboBox.getSelectionModel().select(SplitUnit.IMAGE);
        assignmentView =
                new TrainingAssignmentTable(
                        trainingAssignmentTable,
                        validationAssignmentTable,
                        trainingAssignmentLabel,
                        validationAssignmentLabel,
                        validationAssignmentBox,
                        this::moveRows);
        InformationButton assignmentInfo =
                new InformationButton(
                        "Drag one or more rows between Training and Validation. Inside each "
                                + "training ROI, pixels not covered by a selected class contribute "
                                + "to training as background. Nearby tissue added as context is "
                                + "input only and does not contribute to training.");
        assignmentInfo.setId("assignmentInfo");
        assignmentHeadingBox.getChildren().add(1, assignmentInfo);
        validationPercentSpinner
                .valueProperty()
                .addListener((ignored, previous, current) -> markAssignmentOutdated());
        splitUnitComboBox
                .valueProperty()
                .addListener((ignored, previous, current) -> markAssignmentOutdated());
        cancelButton.setOnAction(event -> cancelActiveOperation());
        runForm =
                new TrainingRunForm(
                        this::updateStartState,
                        () -> invalidateProjectCapture("Training configuration changed."));
        resultBox.getChildren().setAll(runForm.resultNode());
        sourceDetailsBox.getChildren().setAll(runForm.sourceNode());
        sourceDetailsBox.managedProperty().bind(runForm.sourceNode().managedProperty());
        settingsBox.getChildren().setAll(runForm.settingsNode());
        operationRunner = new TrainingOperationRunner(ignored -> renderState());
        submission = new TrainingRunSubmission(ignored -> renderState());
        renderState();
    }

    /** Wire application-scoped owners after FXML loading. */
    public void init(
            QuPathGUI qupath,
            GatewayTransport transport,
            ModuleGatewayClient moduleClient,
            ModulesRegistry registry,
            Supplier<ClientBuildIdentity> clientBuildSupplier,
            ImageLifecycle imageLifecycle,
            Runnable showJobs,
            Runnable showProjectGroupAssignment) {
        this.qupath = Objects.requireNonNull(qupath, "qupath");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.moduleClient = Objects.requireNonNull(moduleClient, "moduleClient");
        this.clientBuildSupplier =
                Objects.requireNonNull(clientBuildSupplier, "clientBuildSupplier");
        this.imageLifecycle = Objects.requireNonNull(imageLifecycle, "imageLifecycle");
        this.showJobs = Objects.requireNonNull(showJobs, "showJobs");
        this.showProjectGroupAssignment =
                Objects.requireNonNull(showProjectGroupAssignment, "showProjectGroupAssignment");

        modelPicker =
                new TrainingModelPicker(
                        moduleComboBox,
                        modelComboBox,
                        Objects.requireNonNull(registry, "registry"),
                        this::selectionChanged);
        qupath.projectProperty()
                .addListener(
                        (ignored, previous, current) -> {
                            cancelActiveOperation();
                            projectCaptureGeneration++;
                            capturedProject = null;
                            capturedProjectSignature = null;
                            capturedProjectStale = false;
                            assignment = null;
                            assignmentOutdated = false;
                            runForm.resetAnnotationClasses();
                            if (active) {
                                refreshProject();
                            } else {
                                renderState();
                            }
                        });
        modelPicker.refresh();
    }

    /** Start or stop Train-tab-owned automatic project refreshes. */
    public void setActive(boolean active) {
        this.active = active;
        if (active) {
            Project<BufferedImage> project = currentProject();
            if (capturedProject == null) {
                refreshProject();
            } else if (!Objects.equals(capturedProjectSignature, projectSignature(project))) {
                invalidateProjectCapture();
            } else {
                renderState();
            }
        }
    }

    public CompletableFuture<Void> cancelForCloudUpdate() {
        cloudUpdating = true;
        renderState();
        return CompletableFuture.allOf(
                operationRunner.cancelAndAwait(), submission.awaitSubmission());
    }

    public void finishCloudUpdate() {
        cloudUpdating = false;
        renderState();
    }

    /** Mark cached hierarchy-derived data stale without reopening any project image. */
    public void invalidateProjectCapture() {
        invalidateProjectCapture("Training project changed.");
    }

    private void invalidateProjectCapture(String reason) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> invalidateProjectCapture(reason));
            return;
        }
        projectCaptureGeneration++;
        if (selectedModelId() == null && capturedProject == null) {
            return;
        }
        if (capturedProject != null || capturedProjectSignature != null) {
            capturedProjectStale = true;
        }
        if (assignment != null) {
            assignmentOutdated = true;
        }
        availabilityLabel.setText(
                runForm.isTargetValid()
                        ? reason + " Refresh before using the split."
                        : "Correct the training form before refreshing.");
        HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.WARNING);
        renderState();
    }

    @FXML
    private void handleRefreshProject() {
        refreshProject();
    }

    private void refreshProject() {
        if (isRunning()
                || selectedModule() == null
                || selectedModelId() == null
                || !runForm.isTargetValid()) {
            renderState();
            return;
        }
        Project<BufferedImage> project = currentProject();
        if (project == null) {
            projectCaptureGeneration++;
            capturedProject = null;
            capturedProjectSignature = null;
            capturedProjectStale = false;
            renderState();
            return;
        }
        long generation = ++projectCaptureGeneration;
        ProjectSignature signature = projectSignature(project);
        capturedProjectStale = capturedProject != null;
        availabilityLabel.setText("Reading the current training project...");
        HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
        PreparedSelection selected =
                prepareSelection(project, runForm.targetTask(), runForm.classInitialization());
        startOperation(
                "Reading training project...",
                (cancelled, progress) ->
                        selected.builder()
                                .captureIfRoisPresent(
                                        project, cancelled, item -> progress.accept(adapt(item))),
                captured -> {
                    if (generation != projectCaptureGeneration
                            || project != currentProject()
                            || !signature.equals(projectSignature(project))) {
                        capturedProjectStale = capturedProject != null;
                        if (assignment != null) {
                            assignmentOutdated = true;
                        }
                        availabilityLabel.setText(
                                "The project or training configuration changed while being read. Refresh again.");
                        HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.WARNING);
                        renderState();
                        return;
                    }
                    capturedProject = captured.orElse(null);
                    capturedProjectSignature = signature;
                    capturedProjectStale = false;
                    if (captured.isEmpty()) {
                        assignment = null;
                        assignmentOutdated = false;
                        runForm.showAnnotationClasses(java.util.Set.of());
                        availabilityLabel.setText(
                                TrainingProjectDatasetBuilder.NO_TRAINING_ROIS_GUIDANCE);
                        HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
                        renderState();
                        return;
                    }
                    ProjectPlan plan = captured.orElseThrow();
                    runForm.showAnnotationClasses(
                            plan.images().stream()
                                    .flatMap(image -> image.capture().annotationClasses().stream())
                                    .collect(java.util.stream.Collectors.toSet()));
                    if (assignment != null) {
                        try {
                            assignment =
                                    assignment.refresh(
                                            TrainingProjectIndex.from(
                                                    plan, assignment.projectIndex().unit()));
                            assignmentOutdated = !assignmentParametersMatch();
                        } catch (IllegalArgumentException | IllegalStateException changed) {
                            assignmentOutdated = true;
                        }
                    }
                    availabilityLabel.setText(
                            "Project annotations are ready for split generation.");
                    HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.SUCCESS);
                    renderState();
                });
    }

    @FXML
    private void handleGenerateSplit() {
        TrainingSplitReadiness readiness = splitReadiness();
        if (!readiness.ready() || capturedProject == null || capturedProjectStale) {
            return;
        }
        SplitUnit unit = splitUnitComboBox.getValue();
        int percent = validationPercentSpinner.getValue();
        RandomGenerator random = new java.util.Random();
        TrainingProjectIndex index = TrainingProjectIndex.from(capturedProject, unit);
        assignment = TrainingAssignment.generate(index, percent, random);
        assignmentOutdated = false;
        renderState();
    }

    @FXML
    private void handleAssignGroups() {
        showProjectGroupAssignment.run();
    }

    @FXML
    private void handleExportDataset() {
        if (!canUseAssignment()) {
            return;
        }
        Project<BufferedImage> project = currentProject();
        PreparedSelection selected =
                prepareSelection(project, runForm.targetTask(), runForm.classInitialization());
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export training dataset");
        chooser.setInitialFileName("histoforge-training-dataset.zip");
        chooser.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter("HistoForge training dataset", "*.zip"));
        File parent = projectDirectory(project);
        if (parent != null && parent.isDirectory()) {
            chooser.setInitialDirectory(parent);
        }
        File destination = chooser.showSaveDialog(qupath.getStage());
        if (destination == null) {
            return;
        }
        TrainingAssignment selectedAssignment = assignment;
        startOperation(
                "Preparing training data...",
                (cancelled, progress) ->
                        selected.workflow()
                                .export(
                                        destination.toPath(),
                                        project,
                                        selectedAssignment,
                                        cancelled,
                                        progress),
                result -> {
                    assignment = result.assignment();
                    Dialogs.showInfoNotification(
                            "Training dataset exported",
                            result.paths().archive().getFileName()
                                    + " and "
                                    + result.paths().splitSidecar().getFileName()
                                    + " were written.");
                    renderState();
                });
    }

    @FXML
    private void handleStartTraining() {
        if (isRunning() || !canUseAssignment() || !runForm.isValid()) return;
        Project<BufferedImage> project = currentProject();
        TrainingRunDraft draft = runForm.draft();
        TrainingAssignment selectedAssignment = assignment;
        PreparedSelection selected =
                prepareSelection(project, draft.task(), draft.classInitialization());
        var runClient = new TrainingRunGatewayClient(transport, draft.source().gateway());
        long seed = RandomGenerator.getDefault().nextLong(1L << 53);
        startOperation(
                "Preparing training data...",
                (cancelled, progress) ->
                        selected.workflow()
                                .upload(project, selectedAssignment, cancelled, progress),
                uploaded -> {
                    assignment = uploaded.assignment();
                    progressLabel.setText(
                            "Starting cloud training. Cancellation will be available in Jobs.");
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    try {
                        submission.start(
                                runClient,
                                draft.request(uploaded, seed),
                                accepted -> {
                                    availabilityLabel.setText(
                                            "Training started. Follow progress in Jobs.");
                                    HistoforgeStyles.setSemantic(
                                            availabilityLabel, HistoforgeStyles.SUCCESS);
                                    showJobs.run();
                                },
                                this::submissionFailed);
                    } catch (IllegalArgumentException error) {
                        handleOperationFailure(error);
                    }
                });
    }

    private void submissionFailed(Throwable error) {
        if (TrainingRunSubmission.definitelyRejected(error)) {
            handleOperationFailure(error);
            return;
        }
        logger.warn("training submission response was not confirmed", error);
        String message = TrainingRunSubmission.unconfirmedFailureMessage(error);
        availabilityLabel.setText(message);
        HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.WARNING);
        showJobs.run();
        Dialogs.showErrorMessage("Training submission", message);
    }

    @FXML
    private void handleManageDatasets() {
        if (isRunning()) {
            return;
        }
        manageDatasetsButton.setDisable(true);
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                var capabilities = moduleClient.capabilities();
                                var client =
                                        new TrainingDatasetGatewayClient(transport, capabilities);
                                Platform.runLater(
                                        () -> {
                                            manageDatasetsButton.setDisable(false);
                                            if (cloudUpdating) return;
                                            new TrainingDatasetManagerDialog(
                                                            qupath.getStage(), client)
                                                    .show();
                                        });
                            } catch (Exception error) {
                                logger.warn("open training dataset manager failed", error);
                                Platform.runLater(
                                        () -> {
                                            manageDatasetsButton.setDisable(false);
                                            Dialogs.showErrorMessage(
                                                    "Uploaded training data",
                                                    Errors.message(error));
                                        });
                            }
                        });
    }

    private void selectionChanged() {
        cancelActiveOperation();
        projectCaptureGeneration++;
        capturedProject = null;
        capturedProjectSignature = null;
        capturedProjectStale = false;
        assignment = null;
        assignmentOutdated = false;
        runForm.select(selectedModule(), selectedModelId());
        if (selectedModelId() != null && active) {
            availabilityLabel.setText("Reading the current training project...");
            HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
            refreshProject();
        } else if (selectedModelId() != null) {
            availabilityLabel.setText("Open the Train tab to read the training project.");
            HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
        } else if (modelPicker.hasTrainableModules()) {
            availabilityLabel.setText("Select a model to train.");
            HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
        } else {
            availabilityLabel.setText(
                    "No installed model is available for training on this backend.");
            HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
        }
        renderState();
    }

    private void moveRows(List<Integer> unitIndices, String destination) {
        if (assignment == null || assignmentOutdated || isRunning()) {
            return;
        }
        assignment = assignment.move(unitIndices, destination);
        renderState();
    }

    private void markAssignmentOutdated() {
        if (assignment != null) {
            assignmentOutdated = true;
        }
        renderState();
    }

    private void renderState() {
        Project<BufferedImage> project = currentProject();
        if (project == null) {
            projectLabel.setText("No QuPath project is open.");
        } else if (capturedProject == null) {
            projectLabel.setText(projectName(project));
        } else {
            projectLabel.setText(
                    projectName(project)
                            + "    "
                            + capturedProject.images().size()
                            + " images, "
                            + capturedProject.roiCount()
                            + " ROIs"
                            + (capturedProjectStale ? "    Outdated" : ""));
        }

        if (assignment == null) {
            assignmentStateLabel.setText("No split generated");
            HistoforgeStyles.setSemantic(assignmentStateLabel, HistoforgeStyles.NEUTRAL);
        } else if (assignmentOutdated) {
            assignmentStateLabel.setText("Outdated");
            HistoforgeStyles.setSemantic(assignmentStateLabel, HistoforgeStyles.WARNING);
        } else if (!assignment.isValid()) {
            assignmentStateLabel.setText(
                    "Training and validation must both contain at least one row.");
            HistoforgeStyles.setSemantic(assignmentStateLabel, HistoforgeStyles.ERROR);
        } else if (assignment.customized()) {
            assignmentStateLabel.setText("Custom split");
            HistoforgeStyles.setSemantic(assignmentStateLabel, HistoforgeStyles.NEUTRAL);
        } else {
            assignmentStateLabel.setText("Generated split");
            HistoforgeStyles.setSemantic(assignmentStateLabel, HistoforgeStyles.SUCCESS);
        }
        int validationPercent =
                validationPercentSpinner.getValue() == null
                        ? 0
                        : validationPercentSpinner.getValue();
        assignmentView.show(assignment, validationPercent);
        assignmentView.setDisabled(isRunning() || assignmentOutdated);

        boolean selectable = selectedModule() != null && selectedModelId() != null;
        boolean hasProject = project != null;
        TrainingSplitReadiness splitReadiness =
                selectable && hasProject
                        ? splitReadiness()
                        : new TrainingSplitReadiness(false, "", false);
        splitGuidanceLabel.setText(splitReadiness.guidance());
        splitGuidanceBox.setVisible(!splitReadiness.guidance().isEmpty());
        splitGuidanceBox.setManaged(!splitReadiness.guidance().isEmpty());
        assignGroupsLink.setVisible(splitReadiness.offerGroupAssignment());
        assignGroupsLink.setManaged(splitReadiness.offerGroupAssignment());
        if (modelPicker == null) {
            moduleComboBox.setDisable(true);
            modelComboBox.setDisable(true);
        } else {
            modelPicker.setDisabled(isRunning());
        }
        validationPercentSpinner.setDisable(isRunning() || !selectable || !hasProject);
        splitUnitComboBox.setDisable(isRunning() || !selectable || !hasProject);
        refreshProjectButton.setDisable(
                isRunning() || !selectable || !hasProject || !runForm.isTargetValid());
        generateSplitButton.setDisable(
                isRunning()
                        || !selectable
                        || !hasProject
                        || capturedProject == null
                        || capturedProjectStale
                        || !splitReadiness.ready());
        exportDatasetButton.setDisable(isRunning() || !canUseAssignment());
        manageDatasetsButton.setDisable(isRunning());
        runForm.setDisabled(isRunning());
        updateStartState();
        cancelButton.setDisable(submission != null && submission.isRunning());
        progressBox.setVisible(isRunning());
        progressBox.setManaged(isRunning());
    }

    private TrainingSplitReadiness splitReadiness() {
        String targetError = runForm.targetValidationMessage();
        if (!targetError.isEmpty()) {
            return new TrainingSplitReadiness(false, "Before refreshing: " + targetError, false);
        }
        if (capturedProject == null || capturedProjectStale) {
            if (capturedProject == null
                    && capturedProjectSignature != null
                    && !capturedProjectStale) {
                return new TrainingSplitReadiness(
                        false, TrainingProjectDatasetBuilder.NO_TRAINING_ROIS_GUIDANCE, false);
            }
            return new TrainingSplitReadiness(
                    false, "Refresh the training project before generating a split.", false);
        }
        if (splitUnitComboBox.getValue() == null || validationPercentSpinner.getValue() == null) {
            return new TrainingSplitReadiness(false, "", false);
        }
        return TrainingSplitReadiness.evaluate(
                capturedProject, splitUnitComboBox.getValue(), validationPercentSpinner.getValue());
    }

    private void updateStartState() {
        startTrainingButton.setDisable(
                isRunning() || !canUseAssignment() || runForm == null || !runForm.isValid());
    }

    private boolean canUseAssignment() {
        return assignment != null
                && !assignmentOutdated
                && !capturedProjectStale
                && assignment.isValid()
                && currentProject() != null
                && selectedModule() != null
                && selectedModelId() != null
                && runForm != null
                && runForm.isTargetValid();
    }

    private PreparedSelection prepareSelection(
            Project<BufferedImage> project, TrainingTask task, Initialization initialization) {
        InstalledModule module = Objects.requireNonNull(selectedModule(), "selected module");
        String modelId = Objects.requireNonNull(selectedModelId(), "selected model");
        TrainingProjectDatasetBuilder builder =
                TrainingProjectDatasetBuilder.forModel(
                        imageLifecycle,
                        module,
                        modelId,
                        task,
                        initialization,
                        clientBuildSupplier.get());
        TrainingDatasetGatewayClient datasetClient =
                new TrainingDatasetGatewayClient(transport, module.gateway());
        TrainingDatasetWorkflow workflow =
                new TrainingDatasetWorkflow(
                        builder,
                        new TrainingDatasetUploader(datasetClient, builder.apiLimits()),
                        temporaryDirectory(project));
        return new PreparedSelection(builder, workflow);
    }

    private boolean assignmentParametersMatch() {
        return assignment != null
                && splitUnitComboBox.getValue() == assignment.projectIndex().unit()
                && validationPercentSpinner.getValue() != null
                && validationPercentSpinner.getValue() == assignment.requestedValidationPercent();
    }

    private InstalledModule selectedModule() {
        return modelPicker == null ? null : modelPicker.selectedModule();
    }

    private String selectedModelId() {
        return modelPicker == null ? null : modelPicker.selectedModelId();
    }

    private Project<BufferedImage> currentProject() {
        return qupath == null ? null : qupath.getProject();
    }

    private static Path temporaryDirectory(Project<BufferedImage> project) {
        if (project != null && project.getPath() != null && project.getPath().getParent() != null) {
            return project.getPath().getParent();
        }
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    private static File projectDirectory(Project<BufferedImage> project) {
        return project == null || project.getPath() == null
                ? null
                : project.getPath().toFile().getParentFile();
    }

    private static String projectName(Project<BufferedImage> project) {
        if (project.getPath() == null || project.getPath().getFileName() == null) {
            return "Current training project";
        }
        return project.getPath().getFileName().toString();
    }

    /** Cheap project metadata identity; this never opens an image or reads {@code .qpdata}. */
    private static ProjectSignature projectSignature(Project<BufferedImage> project) {
        if (project == null) {
            return null;
        }
        return new ProjectSignature(
                project.getImageList().stream()
                        .map(
                                entry ->
                                        new ProjectEntrySignature(
                                                entry.getID(),
                                                entry.getImageName(),
                                                Objects.toString(GroupManager.getGroup(entry), "")))
                        .toList());
    }

    private <T> void startOperation(
            String initialMessage,
            TrainingOperationRunner.Operation<T> operation,
            Consumer<T> onSuccess) {
        if (isRunning()) {
            return;
        }
        progressLabel.setText(initialMessage);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        operationRunner.start(
                operation, this::showProgress, onSuccess, this::handleOperationFailure);
    }

    private void showProgress(TrainingDatasetWorkflow.Progress progress) {
        progressLabel.setText(
                switch (progress.phase()) {
                    case CAPTURING ->
                            "Reading "
                                    + progress.detail()
                                    + " ("
                                    + progress.completed()
                                    + "/"
                                    + progress.total()
                                    + ")";
                    case ENCODING ->
                            "Preparing "
                                    + progress.detail()
                                    + " ("
                                    + progress.completed()
                                    + "/"
                                    + progress.total()
                                    + ")";
                    case UPLOADING ->
                            "Uploading training data ("
                                    + formatBytes(progress.completed())
                                    + " / "
                                    + formatBytes(progress.total())
                                    + ")";
                });
        progressBar.setProgress((double) progress.completed() / progress.total());
    }

    private void handleOperationFailure(Throwable error) {
        if (error instanceof CancellationException || error instanceof InterruptedException) {
            availabilityLabel.setText("Training data preparation was cancelled.");
            HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
            return;
        }
        logger.warn("training data operation failed", error);
        availabilityLabel.setText(Errors.message(error));
        HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.ERROR);
        Dialogs.showErrorMessage("Training data", Errors.message(error));
    }

    private void cancelActiveOperation() {
        operationRunner.cancel();
    }

    private boolean isRunning() {
        return cloudUpdating
                || (operationRunner != null && operationRunner.isRunning())
                || (submission != null && submission.isRunning());
    }

    private static TrainingDatasetWorkflow.Progress adapt(
            TrainingProjectDatasetBuilder.Progress progress) {
        return new TrainingDatasetWorkflow.Progress(
                progress.phase() == TrainingProjectDatasetBuilder.Phase.CAPTURING
                        ? TrainingDatasetWorkflow.Phase.CAPTURING
                        : TrainingDatasetWorkflow.Phase.ENCODING,
                progress.completed(),
                progress.total(),
                progress.imageName());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int index = -1;
        do {
            value /= 1024.0;
            index++;
        } while (value >= 1024.0 && index < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.2f %s", value, units[index]);
    }

    private record PreparedSelection(
            TrainingProjectDatasetBuilder builder, TrainingDatasetWorkflow workflow) {}

    private record ProjectSignature(List<ProjectEntrySignature> entries) {
        private ProjectSignature {
            entries = List.copyOf(entries);
        }
    }

    private record ProjectEntrySignature(String id, String name, String group) {}
}
