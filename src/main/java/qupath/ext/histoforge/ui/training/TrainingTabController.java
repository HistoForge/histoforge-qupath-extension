package qupath.ext.histoforge.ui.training;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.GatewayTransport;
import qupath.ext.histoforge.client.ModuleGatewayClient;
import qupath.ext.histoforge.client.TrainingDatasetGatewayClient;
import qupath.ext.histoforge.client.TrainingRunGatewayClient;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.TrainingClassSchema;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.data.GroupManager;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.training.TrainingAssignment;
import qupath.ext.histoforge.training.TrainingClassPlan;
import qupath.ext.histoforge.training.TrainingDatasetUploader;
import qupath.ext.histoforge.training.TrainingDatasetWorkflow;
import qupath.ext.histoforge.training.TrainingOutlineOverlaps;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder;
import qupath.ext.histoforge.training.TrainingProjectIndex;
import qupath.ext.histoforge.training.TrainingProjectIndex.SplitUnit;
import qupath.ext.histoforge.training.TrainingProjectPreview;
import qupath.ext.histoforge.training.TrainingRunDraft;
import qupath.ext.histoforge.training.TrainingSplitReadiness;
import qupath.ext.histoforge.ui.HistoforgeStyles;
import qupath.ext.histoforge.ui.InformationButton;
import qupath.ext.histoforge.ui.ModulesRegistry;
import qupath.ext.histoforge.util.Errors;
import qupath.ext.histoforge.util.FxRunner;
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
    @FXML private VBox annotationBox;
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
    private TrainingProjectPreview preview;
    private ProjectSignature previewSignature;
    private boolean previewStale;
    private boolean readingPreview;
    private long previewGeneration;
    private TrainingProjectIndex previewRois;
    private String previewProblem = "";
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
        availabilityLabel.visibleProperty().bind(availabilityLabel.textProperty().isNotEmpty());
        availabilityLabel.managedProperty().bind(availabilityLabel.visibleProperty());
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
        runForm = new TrainingRunForm(this::updateStartState, this::updatePreviewCounts);
        resultBox.getChildren().setAll(runForm.resultNode());
        annotationBox.getChildren().setAll(runForm.annotationNode());
        sourceDetailsBox.getChildren().setAll(runForm.sourceNode());
        sourceDetailsBox.managedProperty().bind(runForm.sourceNode().managedProperty());
        settingsBox.getChildren().setAll(runForm.settingsNode());
        operationRunner =
                new TrainingOperationRunner(
                        running -> {
                            if (!running) readingPreview = false;
                            renderState();
                        });
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
        qupath.projectProperty().addListener((ignored, previous, current) -> projectChanged());
        modelPicker.refresh();
    }

    /** Start or stop Train-tab-owned automatic project refreshes. */
    public void setActive(boolean active) {
        this.active = active;
        if (active) {
            Project<BufferedImage> project = currentProject();
            if (previewSignature == null) {
                refreshProject();
            } else if (!Objects.equals(previewSignature, projectSignature(project))) {
                invalidateProjectCapture();
            } else {
                renderState();
            }
        }
    }

    private void projectChanged() {
        var stopped = operationRunner.cancelAndAwait();
        long generation = ++previewGeneration;
        preview = null;
        previewSignature = null;
        previewStale = false;
        assignment = null;
        assignmentOutdated = false;
        runForm.forgetAnnotationClasses();
        availabilityLabel.setText("");
        updatePreviewCounts();
        stopped.thenRun(
                () -> {
                    if (active && generation == previewGeneration) refreshProject();
                });
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
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::invalidateProjectCapture);
            return;
        }
        previewGeneration++;
        if (preview == null && previewSignature == null && !readingPreview) return;
        markPreviewOutdated();
    }

    private void markPreviewOutdated() {
        previewStale = true;
        if (assignment != null) {
            assignmentOutdated = true;
        }
        availabilityLabel.setText("Training project changed.");
        HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.WARNING);
        renderState();
    }

    @FXML
    private void handleRefreshProject() {
        refreshProject();
    }

    private void refreshProject() {
        Project<BufferedImage> project = currentProject();
        if (isRunning() || selectedModule() == null || project == null) {
            renderState();
            return;
        }
        long generation = ++previewGeneration;
        ProjectSignature signature = projectSignature(project);
        previewStale = previewSignature != null;
        if (assignment != null) assignmentOutdated = true;
        readingPreview = true;
        availabilityLabel.setText("Reading the current training project...");
        HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
        startOperation(
                "Reading training project...",
                (cancelled, progress) ->
                        TrainingProjectPreview.read(project, imageLifecycle, cancelled, progress),
                captured -> {
                    if (generation != previewGeneration
                            || project != currentProject()
                            || !signature.equals(projectSignature(project))) {
                        markPreviewOutdated();
                        return;
                    }
                    preview = captured.orElse(null);
                    previewSignature = signature;
                    previewStale = false;
                    if (captured.isEmpty()) {
                        assignment = null;
                        assignmentOutdated = false;
                        // The project is the same. Keep its Train as choices for the next read.
                        if (selectedModelId() != null) runForm.hideAnnotationClasses();
                    } else if (selectedModelId() != null) {
                        runForm.showAnnotationClasses(preview.annotationClasses());
                    }
                    showPreviewAvailability();
                    updatePreviewCounts();
                });
    }

    private void showPreviewAvailability() {
        if (selectedModelId() == null) return;
        availabilityLabel.setText(
                preview == null
                        ? TrainingProjectDatasetBuilder.NO_TRAINING_ROIS_GUIDANCE
                        : "Training annotations loaded.");
        HistoforgeStyles.setSemantic(
                availabilityLabel,
                preview == null ? HistoforgeStyles.NEUTRAL : HistoforgeStyles.SUCCESS);
    }

    private void updatePreviewCounts() {
        previewRois = null;
        previewProblem = "";
        if (preview != null && !previewStale && runForm.isTargetValid()) {
            var limits = selectedModule().gateway().trainingApiLimits().dataset();
            try {
                previewRois = preview.roiIndex(runForm.targetTask(), limits);
            } catch (IllegalArgumentException error) {
                previewProblem = error.getMessage();
            }
        }
        if (assignment != null) {
            assignmentOutdated = true;
            if (previewRois != null && assignmentParametersMatch()) {
                try {
                    assignment =
                            assignment.refresh(
                                    previewRois.forUnit(assignment.projectIndex().unit()));
                    assignmentOutdated = false;
                } catch (IllegalArgumentException | IllegalStateException changed) {
                    // A changed set of split units needs a new assignment.
                }
            }
        }
        renderState();
    }

    @FXML
    private void handleGenerateSplit() {
        TrainingSplitReadiness readiness = splitReadiness();
        if (isRunning() || !readiness.ready()) {
            return;
        }
        SplitUnit unit = splitUnitComboBox.getValue();
        int percent = validationPercentSpinner.getValue();
        RandomGenerator random = new java.util.Random();
        TrainingProjectIndex index = previewRois.forUnit(unit);
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
        TrainingDatasetWorkflow workflow =
                prepareWorkflow(runForm.targetTask(), runForm.classInitialization());
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
                        workflow.export(
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
        TrainingDatasetWorkflow workflow =
                prepareWorkflow(draft.task(), draft.classInitialization());
        var runClient = new TrainingRunGatewayClient(transport, draft.source().gateway());
        long seed = RandomGenerator.getDefault().nextLong(1L << 53);
        startOperation(
                "Preparing training data...",
                (cancelled, progress) ->
                        workflow.upload(project, selectedAssignment, cancelled, progress),
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
        if (!readingPreview) operationRunner.cancel();
        assignment = null;
        assignmentOutdated = false;
        runForm.select(selectedModule(), selectedModelId());
        if (selectedModelId() != null) {
            if (preview != null) {
                runForm.showAnnotationClasses(preview.annotationClasses());
            } else if (previewSignature != null) {
                runForm.hideAnnotationClasses();
            }
            if (previewSignature == null && !readingPreview) {
                if (active) {
                    refreshProject();
                } else {
                    availabilityLabel.setText("Open the Train tab to read the training project.");
                    HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
                }
            } else if (!readingPreview && !previewStale) {
                showPreviewAvailability();
            }
        } else if (modelPicker.hasTrainableModules()) {
            availabilityLabel.setText("Select a model to train.");
            HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
        } else {
            availabilityLabel.setText(
                    "No installed model is available for training on this backend.");
            HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
        }
        updatePreviewCounts();
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
        } else if (preview == null) {
            projectLabel.setText(projectName(project));
        } else {
            projectLabel.setText(
                    projectName(project)
                            + "    "
                            + preview.imageCount()
                            + " images, "
                            + preview.roiCount()
                            + " ROIs"
                            + (previewStale ? "    Outdated" : ""));
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
        } else if (!assignment.trainingHasUsedObjects()) {
            // Short enough for the heading row; the Used column below shows which rows have some.
            assignmentStateLabel.setText("No used objects in Training rows.");
            HistoforgeStyles.setSemantic(assignmentStateLabel, HistoforgeStyles.ERROR);
        } else if (!newClassWithoutExamples().isEmpty()) {
            assignmentStateLabel.setText(newClassWithoutExamples());
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
        boolean editingBlocked = cloudUpdating || (isRunning() && !readingPreview);
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
            modelPicker.setDisabled(editingBlocked);
        }
        validationPercentSpinner.setDisable(isRunning() || !selectable || !hasProject);
        splitUnitComboBox.setDisable(isRunning() || !selectable || !hasProject);
        refreshProjectButton.setDisable(isRunning() || !selectable || !hasProject);
        generateSplitButton.setDisable(
                isRunning() || !selectable || !hasProject || !splitReadiness.ready());
        exportDatasetButton.setDisable(isRunning() || !canUseAssignment());
        manageDatasetsButton.setDisable(isRunning());
        runForm.setDisabled(editingBlocked);
        updateStartState();
        cancelButton.setDisable(submission != null && submission.isRunning());
        progressBox.setVisible(isRunning());
        progressBox.setManaged(isRunning());
    }

    private TrainingSplitReadiness splitReadiness() {
        if (readingPreview) return new TrainingSplitReadiness(false, "", false);
        if (preview == null || previewStale) {
            if (preview == null && previewSignature != null && !previewStale) {
                return new TrainingSplitReadiness(
                        false, TrainingProjectDatasetBuilder.NO_TRAINING_ROIS_GUIDANCE, false);
            }
            return new TrainingSplitReadiness(
                    false, "Refresh to update the training examples and split counts.", false);
        }
        if (previewRois == null) {
            return new TrainingSplitReadiness(
                    false,
                    previewProblem.isEmpty() ? runForm.targetValidationMessage() : previewProblem,
                    false);
        }
        if (splitUnitComboBox.getValue() == null || validationPercentSpinner.getValue() == null) {
            return new TrainingSplitReadiness(false, "", false);
        }
        return TrainingSplitReadiness.evaluate(
                previewRois, splitUnitComboBox.getValue(), validationPercentSpinner.getValue());
    }

    private void updateStartState() {
        startTrainingButton.setDisable(
                isRunning() || !canUseAssignment() || runForm == null || !runForm.isValid());
    }

    private boolean canUseAssignment() {
        return assignment != null
                && !assignmentOutdated
                && !previewStale
                && previewRois != null
                && assignment.isValid()
                && currentProject() != null
                && selectedModule() != null
                && selectedModelId() != null
                && runForm != null
                && runForm.isTargetValid()
                && runForm.isClassSetupTrainable()
                && assignment
                        .trainingExamplesProblem(runForm.classesWithoutLearnedWeights())
                        .isEmpty();
    }

    /**
     * A note for the split heading when a class without learned weights has no object to learn
     * from, or an empty string. It names where the objects are missing, because moving rows to
     * Training helps only when a Validation row has some.
     */
    private String newClassWithoutExamples() {
        if (!runForm.isTargetValid()) return "";
        List<String> newClasses = runForm.classesWithoutLearnedWeights();
        List<String> nowhere = assignment.classesWithoutObjects(newClasses);
        if (!nowhere.isEmpty()) {
            return "No "
                    + TrainingClassPlan.sentenceList(nowhere, "or")
                    + " objects inside training ROIs.";
        }
        List<String> missing = assignment.classesWithoutTrainingObjects(newClasses);
        return missing.isEmpty()
                ? ""
                : "No "
                        + TrainingClassPlan.sentenceList(missing, "or")
                        + " objects in Training rows.";
    }

    private TrainingDatasetWorkflow prepareWorkflow(
            TrainingTask task, Initialization initialization) {
        Project<BufferedImage> project = currentProject();
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
        return new TrainingDatasetWorkflow(
                builder,
                new TrainingDatasetUploader(datasetClient, builder.apiLimits()),
                temporaryDirectory(project),
                TrainingClassSchema.classesWithoutLearnedWeights(
                        module.recipe().models().get(modelId).output(),
                        task.modelOutput(),
                        initialization),
                this::confirmOutlineTrims);
    }

    private boolean confirmOutlineTrims(
            TrainingOutlineOverlaps.Review review, BooleanSupplier cancelled) {
        var accepted = new AtomicBoolean();
        FxRunner.runOnFx(
                () -> {
                    if (cancelled.getAsBoolean() || progressBox.getScene() == null) return;
                    var dialog = outlineTrimDialog(progressBox.getScene().getWindow(), review);
                    accepted.set(dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES);
                });
        return accepted.get()
                && !cancelled.getAsBoolean()
                && !Thread.currentThread().isInterrupted();
    }

    static Dialog<ButtonType> outlineTrimDialog(
            Window owner, TrainingOutlineOverlaps.Review review) {
        var message =
                new Label(
                        String.format(
                                Locale.ROOT,
                                "Training outlines overlap on %s near x=%.2f, y=%.2f.%n%n"
                                        + "Trim %d small %s in the training copy and try the upload again?",
                                review.imageName(),
                                review.x(),
                                review.y(),
                                review.count(),
                                review.count() == 1 ? "overlap" : "overlaps"));
        message.setWrapText(true);
        message.setPrefWidth(440);
        message.setMinHeight(Region.USE_PREF_SIZE);
        var decline = new ButtonType("No", ButtonBar.ButtonData.CANCEL_CLOSE);
        var dialog =
                Dialogs.builder()
                        .alertType(Alert.AlertType.NONE)
                        .title("Trim small training overlaps?")
                        .owner(owner)
                        .content(message)
                        .buttons(ButtonType.YES, decline)
                        .prefWidth(480)
                        .resizable()
                        .build();
        dialog.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
        ((Button) dialog.getDialogPane().lookupButton(decline)).setDefaultButton(true);
        return dialog;
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
                    case REVIEWING_LABELS -> progress.detail();
                });
        progressBar.setProgress(
                progress.phase() == TrainingDatasetWorkflow.Phase.REVIEWING_LABELS
                        ? ProgressBar.INDETERMINATE_PROGRESS
                        : (double) progress.completed() / progress.total());
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
        if (!operationRunner.isRunning()) return;
        operationRunner.cancel();
        availabilityLabel.setText(
                readingPreview
                        ? "Training project read cancelled."
                        : "Training data preparation cancelled.");
        HistoforgeStyles.setSemantic(availabilityLabel, HistoforgeStyles.NEUTRAL);
    }

    private boolean isRunning() {
        return cloudUpdating
                || (operationRunner != null && operationRunner.isRunning())
                || (submission != null && submission.isRunning());
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

    private record ProjectSignature(List<ProjectEntrySignature> entries) {
        private ProjectSignature {
            entries = List.copyOf(entries);
        }
    }

    private record ProjectEntrySignature(String id, String name, String group) {}
}
