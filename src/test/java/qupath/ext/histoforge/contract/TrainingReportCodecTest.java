package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingReportLimits;
import qupath.ext.histoforge.contract.TrainingReportTypes.ModuleTrainingReportResponse;

class TrainingReportCodecTest {

    private static final TrainingReportLimits LIMITS =
            new TrainingReportLimits(32, 1_000, 128, 4_096, 65_536, 16, 4_096, 8 * 1024 * 1024);

    @Test
    void sharedFixtureRoundTripsAndMatchesPythonDigests() throws Exception {
        JsonObject fixture = fixture();
        JsonObject value = fixture.getAsJsonObject("response");
        ModuleContentIdentity expected =
                InferenceIdentityCodec.parseModuleContentIdentity(value.get("module_identity"));

        ModuleTrainingReportResponse response =
                TrainingReportCodec.parseModuleResponse(
                        value, LIMITS, expected, fixture.get("report_digest").getAsString());

        assertEquals(fixture.get("report_digest").getAsString(), response.reportDigest());
        assertEquals(value, TrainingReportCodec.toJson(response, LIMITS));
        assertEquals(
                fixture.get("response_sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingReportCodec.toJson(response, LIMITS)));
        assertEquals("Fine-tuned Leydig workflow", response.report().resultDisplayName());
        assertEquals("A100", response.report().resultInferenceGpu());
        assertEquals("real", response.report().metricPolicy().series().get(0).valueDomain());
        assertEquals(
                "unit_interval", response.report().metricPolicy().series().get(1).valueDomain());
        assertEquals(15, response.report().minInstancePixels());
        assertEquals(2, response.report().metricPolicy().series().size());
        assertEquals(3, response.report().epochs().size());
    }

    @Test
    void responseRejectsWrongDigestIdentityAndUnknownFields() throws Exception {
        JsonObject fixture = fixture();
        JsonObject value = fixture.getAsJsonObject("response");
        String expectedDigest = fixture.get("report_digest").getAsString();
        ModuleContentIdentity expected =
                InferenceIdentityCodec.parseModuleContentIdentity(value.get("module_identity"));

        JsonObject wrongDigest = value.deepCopy();
        wrongDigest.addProperty("report_digest", "0".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingReportCodec.parseModuleResponse(
                                wrongDigest, LIMITS, expected, "0".repeat(64)));

        ModuleContentIdentity wrongIdentity =
                new ModuleContentIdentity(
                        new qupath.ext.histoforge.client.ModuleRef("owner", "other"),
                        java.util.Optional.empty(),
                        "e".repeat(64),
                        "f".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingReportCodec.parseModuleResponse(
                                value, LIMITS, wrongIdentity, expectedDigest));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingReportCodec.parseModuleResponse(
                                value, LIMITS, expected, "0".repeat(64)));

        JsonObject unknown = value.deepCopy();
        unknown.addProperty("extra", true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TrainingReportCodec.parseModuleResponse(
                                unknown, LIMITS, expected, expectedDigest));
    }

    @Test
    void reportRejectsInvalidSelectionAndNegotiatedByteCeiling() throws Exception {
        JsonObject report = fixture().getAsJsonObject("response").getAsJsonObject("report");
        JsonObject wrongSelection = report.deepCopy();
        wrongSelection.getAsJsonObject("model_selection").addProperty("epoch", 999);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingReportCodec.parseReport(wrongSelection, LIMITS));

        TrainingReportLimits tiny =
                new TrainingReportLimits(32, 1_000, 128, 4_096, 65_536, 16, 4_096, 32);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingReportCodec.parseReport(report, tiny));

        JsonObject invalidArea = report.deepCopy();
        invalidArea.addProperty("min_instance_pixels", -1);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingReportCodec.parseReport(invalidArea, LIMITS));

        JsonObject invalidUnitMetric = report.deepCopy();
        invalidUnitMetric
                .getAsJsonArray("epochs")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("metrics")
                .get(1)
                .getAsJsonObject()
                .addProperty("value", 1.01);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingReportCodec.parseReport(invalidUnitMetric, LIMITS));
    }

    @Test
    void reportBindsAugmentationPresentationToTheFrozenSelection() throws Exception {
        JsonObject report =
                fixture().getAsJsonObject("response").getAsJsonObject("report").deepCopy();
        JsonObject parameters =
                report.getAsJsonObject("trainer_configuration")
                        .getAsJsonObject("augmentation")
                        .getAsJsonObject("parameters");
        parameters.addProperty("base_profile_id", "pas-v1");
        JsonObject wrongProfile = report;
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingReportCodec.parseReport(wrongProfile, LIMITS));

        report = fixture().getAsJsonObject("response").getAsJsonObject("report").deepCopy();
        parameters =
                report.getAsJsonObject("trainer_configuration")
                        .getAsJsonObject("augmentation")
                        .getAsJsonObject("parameters");
        parameters.getAsJsonArray("enabled_operators").remove(1);
        JsonObject mismatchedOperators = report;
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingReportCodec.parseReport(mismatchedOperators, LIMITS));
    }

    private static JsonObject fixture() throws IOException {
        try (var input =
                TrainingReportCodecTest.class.getResourceAsStream(
                        "/contracts/module-training-report-v1.json")) {
            if (input == null) {
                throw new IOException("missing module training report fixture");
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
