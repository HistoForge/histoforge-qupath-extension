package qupath.ext.histoforge.ui.training;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingTaskCodec.FixedFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingFieldProfile;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;
import qupath.ext.histoforge.training.TrainingFieldSizes;
import qupath.ext.histoforge.training.TrainingFieldSizes.Unit;

/** Inline model-view controls; conversion changes presentation, not the resolved pixel sizes. */
final class TrainingFieldControls extends VBox {
    private final Runnable changed;
    private final ComboBox<Unit> unit = new ComboBox<>();
    private final TextField minimum = sideField("minimumView");
    private final TextField maximum = sideField("maximumView");
    private final TextField preferred = sideField("largerRoiView");
    private final CheckBox context =
            new CheckBox("Include nearby tissue around small training ROIs");
    private ModelTrainingCapability capability;
    private double mpp;
    private Unit displayUnit = Unit.MICROMETRES;
    private boolean updating;
    private final Label fixedSize = new Label();

    TrainingFieldControls(Runnable changed) {
        super(6);
        this.changed = changed;
        unit.setId("viewUnits");
        unit.getItems().setAll(Unit.values());
        unit.setValue(displayUnit);
        unit.setTooltip(
                TrainingRunForm.tooltip(
                        "Show ROI and tile sizes in micrometres or pixels at the model's resolution. Switching units does not change the sizes used for training."));
        unit.setOnAction(event -> changeUnit());
        context.setId("smallRoiContext");
        context.selectedProperty().addListener((ignored, old, value) -> fieldsChanged());
        for (TextField field : new TextField[] {minimum, maximum, preferred}) {
            field.textProperty().addListener((ignored, old, value) -> fieldsChanged());
        }
    }

    void select(ModelTrainingCapability capability, double mpp) {
        updating = true;
        this.capability = capability;
        this.mpp = mpp;
        displayUnit = Unit.MICROMETRES;
        unit.setValue(displayUnit);
        getChildren().clear();
        GridPane grid = TrainingRunForm.grid();
        if (capability.defaultFieldProfile() instanceof VariableFieldProfile defaults) {
            showSizes(defaults);
            grid.addRow(
                    0,
                    new Label("Minimum ROI side"),
                    new HBox(
                            8,
                            TrainingRunForm.withInfo(
                                    minimum,
                                    "Smallest image width and height used for training. Short ROI sides are extended with nearby tissue when enabled, or padded. Sizes are rounded to values supported by the model."),
                            unit));
            grid.addRow(
                    1,
                    new Label("Maximum ROI side"),
                    TrainingRunForm.withInfo(
                            maximum,
                            "Largest ROI width or height processed as one image. Larger ROIs are split into overlapping tiles. Lower values can create more tiles."));
            grid.addRow(
                    2,
                    new Label("Tile size"),
                    TrainingRunForm.withInfo(
                            preferred,
                            "Width and height of overlapping training tiles within larger ROIs. Larger tiles show more surrounding tissue and use more memory. Tiles at the edge may be smaller."));
        } else {
            var defaults = (FixedFieldProfile) capability.defaultFieldProfile();
            fixedSize.setText(describe(defaults.sideModelPx()) + " (fixed)");
            grid.addRow(
                    0,
                    new Label("Tile size"),
                    TrainingRunForm.withInfo(
                            fixedSize,
                            "This model uses a fixed image size. Larger ROIs are split into tiles; smaller images are padded to that size."));
        }
        context.setSelected(
                capability.defaultFieldProfile() instanceof VariableFieldProfile variable
                        ? variable.smallRoiContext()
                        : ((FixedFieldProfile) capability.defaultFieldProfile()).smallRoiContext());
        getChildren().add(grid);
        if (capability.smallRoiContextEditable()) {
            HBox contextRow =
                    TrainingRunForm.withInfo(
                            context,
                            "Include nearby tissue for context when either side of a training ROI is shorter "
                                    + "than the Minimum ROI side. Only pixels inside the training ROI count "
                                    + "when scoring predictions.");
            getChildren().add(contextRow);
        }
        updating = false;
    }

    /** Resolution changes physical coverage, not a valid edited model-pixel profile. */
    void updateResolution(double value) {
        if (value == mpp) return;
        updating = true;
        TrainingFieldProfile pixels = null;
        try {
            pixels = value();
        } catch (IllegalArgumentException ignored) {
            /* Preserve incomplete text for correction. */
        }
        mpp = value;
        if (pixels instanceof VariableFieldProfile variable) showSizes(variable);
        if (pixels instanceof FixedFieldProfile fixed)
            fixedSize.setText(describe(fixed.sideModelPx()) + " (fixed)");
        updating = false;
    }

    /** Return only capability-permitted changes; fixed profiles remain byte-for-byte unchanged. */
    TrainingFieldProfile value() {
        if (capability.defaultFieldProfile() instanceof FixedFieldProfile) {
            return capability.defaultFieldProfile();
        }
        return TrainingFieldSizes.resolve(
                capability,
                mpp,
                displayUnit,
                minimum.getText(),
                maximum.getText(),
                preferred.getText(),
                context.isSelected());
    }

    private void changeUnit() {
        if (updating || capability == null || unit.getValue() == displayUnit) return;
        updating = true;
        try {
            var pixels = (VariableFieldProfile) value();
            displayUnit = unit.getValue();
            showSizes(pixels);
        } catch (IllegalArgumentException error) {
            unit.setValue(displayUnit);
        } finally {
            updating = false;
        }
        fieldsChanged();
    }

    private void showSizes(VariableFieldProfile profile) {
        minimum.setText(TrainingFieldSizes.display(profile.minSideModelPx(), displayUnit, mpp));
        maximum.setText(TrainingFieldSizes.display(profile.maxSideModelPx(), displayUnit, mpp));
        preferred.setText(
                TrainingFieldSizes.display(profile.preferredTileSideModelPx(), displayUnit, mpp));
    }

    private void fieldsChanged() {
        if (updating || capability == null) return;
        changed.run();
    }

    private String describe(int side) {
        return side
                + " model px ("
                + TrainingFieldSizes.display(side, Unit.MICROMETRES, mpp)
                + " µm)";
    }

    private static TextField sideField(String id) {
        TextField field = new TextField();
        field.setId(id);
        field.setPrefColumnCount(6);
        field.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return field;
    }
}
