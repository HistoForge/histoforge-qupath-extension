package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TrainingDenseContractFixtureTest {
    @Test
    void denseRequestReportAndRunMatchPythonWithTrueAbsence() throws Exception {
        var fixture = fixture();
        var limits = TrainingLimitsCodec.parse(fixture.get("trainingApiLimits"));
        var request =
                TrainingRunRequestCodec.parse(
                        fixture.getAsJsonObject("request").get("value"), limits);
        assertNull(request.minInstancePixels());
        var encoded = TrainingRunRequestCodec.toJson(request, limits);
        assertFalse(encoded.has("min_instance_pixels"));
        assertEquals(
                fixture.getAsJsonObject("request").get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(encoded));
        var report =
                TrainingReportCodec.parseReport(
                        fixture.getAsJsonObject("report").get("value"), limits.report());
        assertNull(report.minInstancePixels());
        assertEquals(
                fixture.getAsJsonObject("report").get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(
                        TrainingReportCodec.toJson(report, limits.report())));
        var run = TrainingRunCodec.parseRecord(fixture.getAsJsonObject("run").get("value"), limits);
        assertEquals(
                fixture.getAsJsonObject("run").get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingRunCodec.toJson(run, limits)));
        var response =
                TrainingRunReportCodec.validateForRun(
                        run, fixture.getAsJsonObject("report_response").get("value"), limits);
        assertEquals(
                fixture.getAsJsonObject("report_response").get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingRunReportCodec.toJson(response, limits)));
    }

    @Test
    void nullIsNotAbsenceAndDenseThresholdCannotLeakFromAnotherModel() throws Exception {
        var fixture = fixture();
        var limits = TrainingLimitsCodec.parse(fixture.get("trainingApiLimits"));
        var request = fixture.getAsJsonObject("request").getAsJsonObject("value").deepCopy();
        request.add("min_instance_pixels", JsonNull.INSTANCE);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunRequestCodec.parse(request, limits));
        var report = fixture.getAsJsonObject("report").getAsJsonObject("value").deepCopy();
        report.add("min_instance_pixels", JsonNull.INSTANCE);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingReportCodec.parseReport(report, limits.report()));
        report.addProperty("min_instance_pixels", 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingReportCodec.parseReport(report, limits.report()));
        assertNull(TrainingRunRequestCodec.requireInstanceThreshold(null, "dense_classification"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunRequestCodec.requireInstanceThreshold(0, "dense_classification"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingRunRequestCodec.requireInstanceThreshold(
                                null, "instance_segmentation"));
        assertEquals(
                0, TrainingRunRequestCodec.requireInstanceThreshold(0, "instance_segmentation"));
    }

    @Test
    void unsupportedTasksDoNotInheritInstanceThresholds() {
        for (String outputType : new String[] {"region_classification", "future-task"}) {
            for (Integer value : new Integer[] {null, 0}) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TrainingRunRequestCodec.requireInstanceThreshold(value, outputType));
            }
        }
        assertEquals(
                0,
                TrainingRunRequestCodec.requireInstanceThreshold(
                        0, "instance_segmentation_and_classification"));
    }

    private static JsonObject fixture() throws Exception {
        try (var stream =
                TrainingDenseContractFixtureTest.class.getResourceAsStream(
                        "/contracts/training-dense-v1.json")) {
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
