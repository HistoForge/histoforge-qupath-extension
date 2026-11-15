package qupath.ext.histoforge.ui.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import qupath.lib.images.servers.PixelCalibration;

class RoiSizeSpinnersTest {

    @Test
    void pixelUnitsAreNotMistakenForMicrometres() {
        assertFalse(RoiSizeSpinners.hasUsableMicronSize(null));
        assertFalse(RoiSizeSpinners.hasUsableMicronSize(PixelCalibration.getDefaultInstance()));
    }

    @Test
    void independentlyCalibratedAxesSupportPhysicalRoiSizes() {
        var calibration = new PixelCalibration.Builder().pixelSizeMicrons(0.2, 0.4).build();
        assertTrue(RoiSizeSpinners.hasUsableMicronSize(calibration));
    }
}
