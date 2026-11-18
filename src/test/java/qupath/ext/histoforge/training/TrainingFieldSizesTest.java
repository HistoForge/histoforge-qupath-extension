package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.training.TrainingFieldSizes.Unit;

class TrainingFieldSizesTest {
    @Test
    void roundsToNearestAlignmentWithHalfwayUpwardInBothUnits() throws Exception {
        var cap = TrainingFormFixtures.capability(true);
        var physical =
                TrainingFieldSizes.resolve(
                        cap, 0.22, Unit.MICROMETRES, "28.16", "225.28", "29.92", true);
        var pixels =
                TrainingFieldSizes.resolve(
                        cap, 0.22, Unit.MODEL_PIXELS, "128", "1024", "136", true);
        assertEquals(pixels, physical);
        assertEquals(144, physical.preferredTileSideModelPx());
        assertEquals(
                128,
                TrainingFieldSizes.resolve(
                                cap,
                                0.22,
                                Unit.MICROMETRES,
                                "28.16",
                                "225.28",
                                "29.91999999",
                                false)
                        .preferredTileSideModelPx());
    }

    @Test
    void preservesEveryAlignedValueAcrossUnitSwitches() throws Exception {
        var cap = TrainingFormFixtures.capability(true);
        for (double mpp : new double[] {0.22, 0.5, 0.123456789}) {
            for (int pixels = 128; pixels <= 1024; pixels += 16) {
                String text = TrainingFieldSizes.display(pixels, Unit.MICROMETRES, mpp);
                var value =
                        TrainingFieldSizes.resolve(
                                cap, mpp, Unit.MICROMETRES, text, text, text, true);
                assertEquals(pixels, value.minSideModelPx());
                assertEquals(pixels, value.maxSideModelPx());
                assertEquals(pixels, value.preferredTileSideModelPx());
            }
        }
    }

    @Test
    void rejectsUnsupportedResolvedSizesAndOrderingWithoutClamping() throws Exception {
        var cap = TrainingFormFixtures.capability(true);
        for (String side : new String[] {"0", "-1", "NaN", "Infinity", "", "119", "1032", "1e20"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            TrainingFieldSizes.resolve(
                                    cap, 0.22, Unit.MODEL_PIXELS, side, side, side, true),
                    side);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingFieldSizes.resolve(
                                cap, 0.22, Unit.MODEL_PIXELS, "384", "1024", "256", true));
        // The entered size is permitted to round to an allowed boundary; it is not clamped.
        assertEquals(
                1024,
                TrainingFieldSizes.resolve(cap, 0.22, Unit.MODEL_PIXELS, "128", "1031", "768", true)
                        .maxSideModelPx());
    }
}
