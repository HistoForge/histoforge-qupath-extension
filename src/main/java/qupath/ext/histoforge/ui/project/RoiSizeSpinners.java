package qupath.ext.histoforge.ui.project;

import java.util.function.Supplier;
import javafx.beans.property.IntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.util.StringConverter;
import qupath.ext.histoforge.roi.RoiManager;
import qupath.ext.histoforge.roi.RoiToolHandler;
import qupath.fx.utils.FXUtils;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.PixelCalibration;

/**
 * The Project tab's two ROI default-size spinners (width/height) plus the px/µm/mm unit selector.
 * Keeps the displayed value (in the chosen unit) and the backing pixel preferences ({@link
 * RoiToolHandler#defaultWidthProperty} / {@link RoiToolHandler#defaultHeightProperty}) in sync,
 * rebuilding the spinner factories whenever the unit or viewer image changes. Physical units are
 * offered only for images with valid micrometre calibration; unavailable units never label pixel
 * values.
 *
 * <p>The FXML nodes are injected into the controller and handed in here. The supplied image is the
 * one open in QuPath's viewer, not the selected project-table row.
 */
final class RoiSizeSpinners {

    private final Spinner<Double> widthSpinner;
    private final Spinner<Double> heightSpinner;
    private final ComboBox<RoiToolHandler.SizeUnit> unitCombo;
    private final Label warningLabel;
    private final Supplier<? extends ImageData<?>> imageDataSupplier;

    /** Suppresses feedback loops during programmatic spinner ↔ pixel-property sync. */
    private boolean updating;

    private boolean settingUnit;

    private ChangeListener<Number> widthPixelListener;
    private ChangeListener<Number> heightPixelListener;

    RoiSizeSpinners(
            Spinner<Double> widthSpinner,
            Spinner<Double> heightSpinner,
            ComboBox<RoiToolHandler.SizeUnit> unitCombo,
            Label warningLabel,
            Supplier<? extends ImageData<?>> imageDataSupplier) {
        this.widthSpinner = widthSpinner;
        this.heightSpinner = heightSpinner;
        this.unitCombo = unitCombo;
        this.warningLabel = warningLabel;
        this.imageDataSupplier = imageDataSupplier;

        unitCombo
                .valueProperty()
                .addListener(
                        (obs, oldUnit, newUnit) -> {
                            if (newUnit != null && !settingUnit) {
                                RoiToolHandler.defaultUnitProperty.set(newUnit.name());
                                refresh();
                            }
                        });

        // Commit spinner editor text on focus loss — once per spinner, persists across factory
        // swaps (the listener watches the spinner, not the factory).
        widthSpinner
                .focusedProperty()
                .addListener(
                        (obs, wasFocused, isFocused) -> {
                            if (!isFocused) widthSpinner.increment(0);
                        });
        heightSpinner
                .focusedProperty()
                .addListener(
                        (obs, wasFocused, isFocused) -> {
                            if (!isFocused) heightSpinner.increment(0);
                        });

        refresh();
    }

    /** Rebuild for the current image's pixel calibration (call when the viewer image changes). */
    void refresh() {
        ImageData<?> imageData = imageDataSupplier.get();
        boolean imageOpen = imageData != null;
        PixelCalibration calibration =
                imageOpen ? imageData.getServer().getPixelCalibration() : null;
        boolean calibrated = hasUsableMicronSize(calibration);
        RoiToolHandler.SizeUnit unit = calibrated ? preferredUnit() : RoiToolHandler.SizeUnit.PX;

        settingUnit = true;
        try {
            unitCombo
                    .getItems()
                    .setAll(
                            calibrated
                                    ? RoiToolHandler.SizeUnit.values()
                                    : new RoiToolHandler.SizeUnit[] {RoiToolHandler.SizeUnit.PX});
            unitCombo.setValue(unit);
        } finally {
            settingUnit = false;
        }

        widthSpinner.setDisable(!imageOpen);
        heightSpinner.setDisable(!imageOpen);
        unitCombo.setDisable(!imageOpen || !calibrated);
        warningLabel.setText(
                !imageOpen
                        ? "Open an image to set ROI size"
                        : "This image has no pixel calibration — sizes are in px");
        warningLabel.setVisible(!calibrated);
        warningLabel.setManaged(!calibrated);

        rebuild(
                unit,
                calibrated ? calibration.getPixelWidthMicrons() : 1.0,
                calibrated ? calibration.getPixelHeightMicrons() : 1.0);
    }

    private static RoiToolHandler.SizeUnit preferredUnit() {
        try {
            return RoiToolHandler.SizeUnit.valueOf(RoiToolHandler.defaultUnitProperty.get());
        } catch (IllegalArgumentException e) {
            return RoiToolHandler.SizeUnit.PX;
        }
    }

    static boolean hasUsableMicronSize(PixelCalibration calibration) {
        return calibration != null
                && calibration.hasPixelSizeMicrons()
                && Double.isFinite(calibration.getPixelWidthMicrons())
                && calibration.getPixelWidthMicrons() > 0
                && Double.isFinite(calibration.getPixelHeightMicrons())
                && calibration.getPixelHeightMicrons() > 0;
    }

    private void rebuild(RoiToolHandler.SizeUnit unit, double widthUmPerPx, double heightUmPerPx) {
        double scale = unit == RoiToolHandler.SizeUnit.MM ? 1.0 / 1000.0 : 1.0;
        double widthFactor = unit == RoiToolHandler.SizeUnit.PX ? 1.0 : widthUmPerPx * scale;
        double heightFactor = unit == RoiToolHandler.SizeUnit.PX ? 1.0 : heightUmPerPx * scale;
        int decimals = unit.getDecimals();

        updating = true;
        try {
            widthPixelListener =
                    wireSpinner(
                            widthSpinner,
                            RoiToolHandler.defaultWidthProperty,
                            widthPixelListener,
                            widthFactor,
                            decimals);
            heightPixelListener =
                    wireSpinner(
                            heightSpinner,
                            RoiToolHandler.defaultHeightProperty,
                            heightPixelListener,
                            heightFactor,
                            decimals);
        } finally {
            updating = false;
        }
    }

    /**
     * Create a {@link SpinnerValueFactory.DoubleSpinnerValueFactory} that displays values in the
     * given unit (factor × pixels) and syncs bidirectionally with the backing {@code pixelProp}.
     *
     * @return the new pixel-property listener (caller stores it for cleanup on next rebuild)
     */
    private ChangeListener<Number> wireSpinner(
            Spinner<Double> spinner,
            IntegerProperty pixelProp,
            ChangeListener<Number> oldPixelListener,
            double factor,
            int decimals) {

        if (oldPixelListener != null) pixelProp.removeListener(oldPixelListener);
        var values =
                new SpinnerValueFactory.DoubleSpinnerValueFactory(
                        RoiManager.MIN_SIZE * factor,
                        RoiManager.MAX_SIZE * factor,
                        pixelProp.get() * factor,
                        100 * factor);
        values.setConverter(
                new StringConverter<>() {
                    @Override
                    public String toString(Double number) {
                        return number == null ? "" : String.format("%." + decimals + "f", number);
                    }

                    @Override
                    public Double fromString(String text) {
                        try {
                            return text == null ? values.getValue() : Double.parseDouble(text);
                        } catch (NumberFormatException invalid) {
                            return values.getValue();
                        }
                    }
                });
        spinner.setValueFactory(values);
        values.valueProperty()
                .addListener(
                        (property, previous, current) -> {
                            if (updating || current == null) return;
                            int pixels = (int) Math.round(current / factor);
                            pixelProp.set(
                                    Math.clamp(pixels, RoiManager.MIN_SIZE, RoiManager.MAX_SIZE));
                        });
        ChangeListener<Number> listener =
                (property, previous, current) -> {
                    if (updating) return;
                    updating = true;
                    try {
                        values.setValue(current.intValue() * factor);
                    } finally {
                        updating = false;
                    }
                };
        pixelProp.addListener(listener);
        FXUtils.resetSpinnerNullToPrevious(spinner);
        return listener;
    }
}
