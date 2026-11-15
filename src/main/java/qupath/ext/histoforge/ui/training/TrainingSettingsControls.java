package qupath.ext.histoforge.ui.training;

import static qupath.ext.histoforge.contract.TrainingCapabilityCodec.EQUAL_INSTANCE_FLOW_WEIGHTING;
import static qupath.ext.histoforge.contract.TrainingCapabilityCodec.PIXEL_FLOW_WEIGHTING;

import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.StringConverter;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingGpuChoice;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.TrainingSettings;

/**
 * Inline editor for the capability-backed training settings, independent of result-model settings.
 */
final class TrainingSettingsControls {
    private final GridPane node = TrainingRunForm.grid();
    private final ComboBox<TrainingGpuChoice> gpu = new ComboBox<>();
    private final TextField batch = numberField("trainingBatchSize");
    private final TextField epochs = numberField("trainingEpochs");
    private final TextField workers = numberField("dataLoadingWorkers");
    private final TextField accumulation = numberField("gradientAccumulation");
    private final ComboBox<String> warmupMode = new ComboBox<>();
    private final TextField warmup = numberField("warmupEpochs");
    private final CheckBox balanceFlow = new CheckBox("Balance flow loss across objects");
    private final CheckBox oversample = new CheckBox("Oversample rare-class fields");
    private final Runnable changed;
    private ModelTrainingCapability capability;
    private boolean updating;

    TrainingSettingsControls(Runnable changed) {
        this.changed = changed;
        gpu.setId("trainingGpu");
        gpu.setConverter(
                new StringConverter<>() {
                    @Override
                    public String toString(TrainingGpuChoice value) {
                        return value == null ? "" : value.displayName();
                    }

                    @Override
                    public TrainingGpuChoice fromString(String text) {
                        throw new UnsupportedOperationException("selection is not editable");
                    }
                });
        warmupMode.setId("warmupMode");
        warmupMode.getItems().setAll("Automatic", "Custom");
        balanceFlow.setId("balanceFlowLoss");
        oversample.setId("oversampleTrainingFields");
        for (TextField field : new TextField[] {batch, epochs, workers, accumulation, warmup}) {
            field.textProperty().addListener((ignored, old, value) -> edited());
        }
        gpu.setOnAction(event -> edited());
        warmupMode.setOnAction(event -> edited());
        balanceFlow.selectedProperty().addListener((ignored, old, value) -> edited());
        oversample.selectedProperty().addListener((ignored, old, value) -> edited());
    }

    GridPane node() {
        return node;
    }

    void select(ModelTrainingCapability capability) {
        updating = true;
        this.capability = capability;
        var defaults = capability.defaultTrainingSettings();
        gpu.getItems().setAll(capability.trainingGpus());
        gpu.setValue(
                capability.trainingGpus().stream()
                        .filter(value -> value.trainingGpuId().equals(defaults.trainingGpuId()))
                        .findFirst()
                        .orElseThrow());
        batch.setText(Integer.toString(defaults.trainingBatchSize()));
        epochs.setText(Integer.toString(defaults.totalEpochs()));
        workers.setText(Long.toString(defaults.dataLoadingWorkers()));
        accumulation.setText(Integer.toString(defaults.gradientAccumulationSteps()));
        warmupMode.setValue("auto".equals(defaults.warmupMode()) ? "Automatic" : "Custom");
        warmup.setText(Integer.toString(defaults.warmupEpochs()));
        balanceFlow.setSelected(EQUAL_INSTANCE_FLOW_WEIGHTING.equals(defaults.flowLossWeighting()));
        balanceFlow.setDisable(capability.flowLossWeightingOptions().size() < 2);
        oversample.setSelected(Boolean.TRUE.equals(defaults.oversample()));

        node.getChildren().clear();
        int row = 0;
        node.addRow(
                row++,
                new Label("Training GPU"),
                TrainingRunForm.withInfo(
                        gpu,
                        "GPU used for this training run. The inference GPU is set separately."));
        node.addRow(
                row++,
                new Label("Training batch size"),
                TrainingRunForm.withInfo(
                        batch,
                        "Number of image regions processed together on the GPU. Larger batches use more GPU memory; "
                                + "use a smaller batch if the GPU runs out of memory. Different-sized regions are padded, "
                                + "which can affect training even though padding is not scored. Allowed: "
                                + capability.trainingBatchSizeRange().minimum()
                                + "–"
                                + capability.trainingBatchSizeRange().maximum()
                                + "."));
        node.addRow(
                row++,
                new Label("Epochs"),
                TrainingRunForm.withInfo(
                        epochs,
                        "Number of training cycles. More cycles take longer and cost more, without guaranteeing "
                                + "better results. With oversampling off, each cycle uses every planned image region once. Allowed: "
                                + capability.totalEpochsRange().minimum()
                                + "–"
                                + capability.totalEpochsRange().maximum()
                                + "."));
        var warmupControls = new HBox(6, warmupMode, warmup);
        warmupControls.setAlignment(Pos.CENTER_LEFT);
        node.addRow(
                row++,
                new Label("Warmup epochs"),
                TrainingRunForm.withInfo(
                        warmupControls,
                        "Gradually increase the learning rate (the scale of model updates) at the start, then reduce it. "
                                + "Automatic divides the epoch count by "
                                + capability.automaticWarmup().divisor()
                                + ", rounds down, and uses at most "
                                + capability.automaticWarmup().maximumEpochs()
                                + " epochs. Custom accepts 0 to epochs − 1; 0 skips warmup."));
        node.addRow(
                row++,
                new Label("Data-loading workers"),
                TrainingRunForm.withInfo(
                        workers,
                        "Number of CPU processes preparing training images in the cloud. More can reduce waiting "
                                + "for images, but use more cloud memory. Allowed: "
                                + capability.dataLoadingWorkersRange().minimum()
                                + "–"
                                + capability.dataLoadingWorkersRange().maximum()
                                + "."));
        node.addRow(
                row++,
                new Label("Gradient accumulation"),
                TrainingRunForm.withInfo(
                        accumulation,
                        "Batches combined before each model update. This uses more image regions per update "
                                + "without storing them all on the GPU. A full update uses batch size × accumulation regions. Allowed: "
                                + capability.gradientAccumulationStepsRange().minimum()
                                + "–"
                                + capability.gradientAccumulationStepsRange().maximum()
                                + "."));
        if (!capability.flowLossWeightingOptions().isEmpty()) {
            node.addRow(
                    row++,
                    new Label("Flow loss"),
                    TrainingRunForm.withInfo(
                            balanceFlow,
                            "Gives pixels from small annotated objects more weight and pixels from large objects less "
                                    + "weight when learning to separate objects. Consider this when object sizes vary widely. "
                                    + "It does not balance classes or change the other training losses."));
        }
        if (defaults.oversample() != null) {
            node.addRow(
                    row,
                    new Label("Sampling"),
                    TrainingRunForm.withInfo(
                            oversample,
                            "Sample image regions containing objects from classes with less annotated area more often. "
                                    + "Each epoch uses the same number of samples, but may repeat or skip regions; regions without objects may become "
                                    + "very rare. Off uses every region once. Validation and class weighting stay unchanged."));
        }
        updating = false;
        updateWarmupControl();
    }

    TrainingSettings value() {
        int total = integer(epochs, "epochs");
        boolean automatic = "Automatic".equals(warmupMode.getValue());
        long workerCount;
        try {
            workerCount = Long.parseLong(workers.getText().trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Enter a whole number for data-loading workers.", error);
        }
        return new TrainingSettings(
                gpu.getValue().trainingGpuId(),
                integer(batch, "training batch size"),
                workerCount,
                integer(accumulation, "gradient accumulation"),
                total,
                automatic ? "auto" : "custom",
                automatic
                        ? TrainingCapabilityCodec.automaticWarmupEpochs(
                                total, capability.automaticWarmup())
                        : integer(warmup, "warmup epochs"),
                capability.flowLossWeightingOptions().isEmpty()
                        ? null
                        : balanceFlow.isSelected()
                                ? EQUAL_INSTANCE_FLOW_WEIGHTING
                                : PIXEL_FLOW_WEIGHTING,
                capability.defaultTrainingSettings().oversample() == null
                        ? null
                        : oversample.isSelected());
    }

    private void edited() {
        if (updating || capability == null) return;
        updateWarmupControl();
        changed.run();
    }

    private void updateWarmupControl() {
        updating = true;
        boolean automatic = "Automatic".equals(warmupMode.getValue());
        warmup.setDisable(automatic);
        try {
            if (automatic) {
                warmup.setText(
                        Integer.toString(
                                TrainingCapabilityCodec.automaticWarmupEpochs(
                                        integer(epochs, "epochs"), capability.automaticWarmup())));
            }
        } catch (IllegalArgumentException error) {
            warmup.clear();
        } finally {
            updating = false;
        }
    }

    private static int integer(TextField field, String label) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter a whole number for " + label + ".", error);
        }
    }

    private static TextField numberField(String id) {
        var field = new TextField();
        field.setId(id);
        field.setPrefColumnCount(6);
        field.setMaxWidth(Region.USE_PREF_SIZE);
        return field;
    }
}
