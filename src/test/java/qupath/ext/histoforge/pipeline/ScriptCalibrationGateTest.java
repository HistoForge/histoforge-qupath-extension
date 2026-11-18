package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.SourceIdentityCodec;

/** Checks square-pixel calibration before scripts receive image measurements. */
class ScriptCalibrationGateTest {

    @Test
    void fixturePinsThePolicyBoundary() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());
        assertEquals(
                fixture.get("squareMppRelativeTolerance").getAsDouble(),
                ScriptCalibrationGate.SQUARE_MPP_RELATIVE_TOLERANCE);
        for (JsonElement element : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = element.getAsJsonObject();
            double mppX = testCase.get("mppX").getAsDouble();
            double mppY = testCase.get("mppY").getAsDouble();
            var calibration = new SourceIdentityCodec.PixelCalibration(mppX, mppY);
            assertEquals(
                    testCase.get("relativeDifference").getAsDouble(),
                    ScriptCalibrationGate.relativeDifference(calibration),
                    testCase.get("name").getAsString());

            if (testCase.get("accepted").getAsBoolean()) {
                assertEquals(calibration, ScriptCalibrationGate.requireSupported(mppX, mppY));
            } else {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ScriptCalibrationGate.requireSupported(mppX, mppY),
                        testCase.get("name").getAsString());
            }
        }
    }

    @Test
    void nonpositiveOrNonfiniteCalibrationFailsBeforeScriptBinding() {
        for (double invalid :
                new double[] {
                    0.0d, -0.25d, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ScriptCalibrationGate.requireSupported(invalid, 0.25d));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ScriptCalibrationGate.requireSupported(0.25d, invalid));
        }
    }

    private static JsonObject fixture() throws IOException {
        return JsonParser.parseString(
                        new String(
                                resourceBytes("contracts/script-calibration-v1.json"),
                                StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static byte[] resourceBytes(String name) throws IOException {
        try (InputStream input =
                ScriptCalibrationGateTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("missing test resource: " + name);
            }
            return input.readAllBytes();
        }
    }
}
