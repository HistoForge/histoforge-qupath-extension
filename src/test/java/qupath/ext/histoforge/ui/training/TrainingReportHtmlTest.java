package qupath.ext.histoforge.ui.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingReportLimits;
import qupath.ext.histoforge.contract.TrainingReportCodec;
import qupath.ext.histoforge.contract.TrainingReportTypes.ModuleTrainingReportResponse;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingReportResponse;
import qupath.ext.histoforge.training.TrainingFormFixtures;

class TrainingReportHtmlTest {

    private static final TrainingReportLimits LIMITS =
            new TrainingReportLimits(32, 1_000, 128, 4_096, 65_536, 16, 4_096, 8 * 1024 * 1024);

    @Test
    void samplingExplanationAndExactProfileAreRetained() throws Exception {
        var fixture = TrainingFormFixtures.fixture("training-oversampling-v1.json");
        for (var value : fixture.getAsJsonArray("cases")) {
            var example = value.getAsJsonObject();
            var reportValue = fixture.getAsJsonObject("report").getAsJsonObject("value").deepCopy();
            boolean enabled = example.getAsJsonObject("settings").get("oversample").getAsBoolean();
            reportValue.getAsJsonObject("trainer_settings").addProperty("oversample", enabled);
            reportValue
                    .getAsJsonObject("trainer_configuration")
                    .add("sampler", example.get("sampler"));
            var report = TrainingReportCodec.parseReport(reportValue, LIMITS);
            String html =
                    TrainingReportHtml.render(
                            new TrainingReportResponse(
                                    1, CanonicalCodec.canonicalDigest(reportValue), report));
            assertTrue(html.contains("Field sampling"), html);
            assertTrue(
                    html.contains(
                            enabled
                                    ? "Rare-class oversampling; N draws with replacement for N planned fields"
                                    : "Full coverage; every planned field once per epoch"),
                    html);
            assertTrue(
                    html.contains(
                            example.getAsJsonObject("sampler").get("profile_id").getAsString()),
                    html);
        }
    }

    @Test
    void denseReportLabelsRegionsAndOmitsInstanceOnlySetting() throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/training-dense-v1.json")) {
            var fixture =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
            var limits =
                    qupath.ext.histoforge.contract.TrainingLimitsCodec.parse(
                            fixture.get("trainingApiLimits"));
            var response =
                    qupath.ext.histoforge.contract.TrainingRunReportCodec.parse(
                            fixture.getAsJsonObject("report_response").get("value"), limits);
            String html = TrainingReportHtml.render(response);
            assertTrue(html.contains("Annotated regions"), html);
            assertFalse(html.contains("Minimum detected object area"), html);
            assertTrue(html.contains("Validation pixel macro F1"), html);
        }
    }

    @Test
    void runSnapshotsDoNotInventAModuleIdentityAndKeepFailureDetails() throws Exception {
        for (String state : List.of("RUNNING", "FAILED", "CANCELLED")) {
            JsonObject value = fixture().getAsJsonObject("response").getAsJsonObject("report");
            value.addProperty("state", state);
            value.addProperty("result_display_name", "<script>unsafe</script>");
            value.remove("model_selection");
            if ("FAILED".equals(state)) {
                var failure = new JsonObject();
                failure.addProperty("schema_version", 1);
                failure.addProperty("code", "RUNNER_FAILED");
                failure.addProperty("message", "Worker stopped <details>");
                value.add("failure", failure);
            }
            var report = TrainingReportCodec.parseReport(value, LIMITS);
            String digest =
                    CanonicalCodec.canonicalDigest(TrainingReportCodec.toJson(report, LIMITS));
            var response = new TrainingReportResponse(4, digest, report);
            String html = TrainingReportHtml.render(response);
            assertTrue(
                    html.contains(
                            "Snapshot through epoch 3. Open latest details in Jobs to refresh."));
            assertTrue(html.contains(report.runId()));
            assertTrue(html.contains(digest));
            assertTrue(html.contains("&lt;script&gt;unsafe&lt;/script&gt;"));
            assertFalse(html.contains("Module content digest"));
            assertFalse(html.contains("Immutable final report"));
            assertFalse(html.contains("workspace/run-"));
            assertFalse(html.contains("<script>"));
            assertTrue(
                    html.contains(
                            "Selected checkpoint</dt><dd>"
                                    + ("RUNNING".equals(state) ? "Pending" : "Not selected")
                                    + "</dd>"));
            if ("FAILED".equals(state)) {
                assertTrue(html.contains("Worker stopped &lt;details&gt;"));
            }
            Path file = TrainingReportViewer.write(response);
            try {
                assertTrue(Files.readString(file).contains(digest));
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void rendersEveryDeclaredMetricAndNoExternalResources() throws Exception {
        ModuleTrainingReportResponse report = response();

        String html = TrainingReportHtml.render(report);

        assertTrue(html.contains("Training loss"));
        assertTrue(html.contains("Validation mSA"));
        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("0.5 µm/px"));
        assertTrue(html.contains("192 µm per side"));
        assertTrue(html.contains("15 model-grid pixels"));
        assertTrue(html.contains("H&amp;E"));
        assertTrue(html.contains("Right-angle rotations, Flips"));
        assertFalse(html.contains("Customized H&amp;E"));
        assertTrue(html.contains("Immutable final report through epoch 3."));
        assertTrue(html.contains("<dt>Training time</dt><dd>30s</dd>"));
        assertTrue(html.contains("Training GPU"));
        assertTrue(html.contains("Result inference GPU"));
        assertTrue(html.contains("A100"));
        assertTrue(html.contains("Resolved trainer configuration"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));
        assertFalse(html.contains("Authorization"));
    }

    @Test
    void rendersResultFormsWithTheirUserFacingLabels() throws Exception {
        for (var entry :
                Map.of(
                                "complete_source_workflow",
                                "Copy module and replace this model",
                                "selected_model_only",
                                "Create a standalone module with this model")
                        .entrySet()) {
            JsonObject fixture = fixture();
            JsonObject responseValue = fixture.getAsJsonObject("response");
            JsonObject reportValue = responseValue.getAsJsonObject("report").deepCopy();
            reportValue.addProperty("result_form", entry.getKey());
            var report = TrainingReportCodec.parseReport(reportValue, LIMITS);

            String html =
                    TrainingReportHtml.render(
                            new TrainingReportResponse(
                                    1,
                                    CanonicalCodec.canonicalDigest(
                                            TrainingReportCodec.toJson(report, LIMITS)),
                                    report));

            assertTrue(html.contains(entry.getValue()));
            assertFalse(html.contains(entry.getKey()));
        }
    }

    @Test
    void escapesAllUserAndReportTextBeforeHtmlRendering() throws Exception {
        JsonObject fixture = fixture();
        JsonObject responseValue = fixture.getAsJsonObject("response");
        JsonObject reportValue = responseValue.getAsJsonObject("report").deepCopy();
        reportValue.addProperty("result_display_name", "<script>alert('name')</script>");
        reportValue
                .getAsJsonArray("messages")
                .get(0)
                .getAsJsonObject()
                .addProperty("message", "<img src=x onerror=alert('message')>");
        reportValue
                .getAsJsonObject("trainer_configuration")
                .getAsJsonObject("augmentation")
                .getAsJsonObject("parameters")
                .addProperty("base_profile_display_name", "<b>H&E</b>");
        var report = TrainingReportCodec.parseReport(reportValue, LIMITS);
        var identity =
                InferenceIdentityCodec.parseModuleContentIdentity(
                        responseValue.get("module_identity"));
        ModuleTrainingReportResponse changed =
                new ModuleTrainingReportResponse(
                        identity,
                        CanonicalCodec.canonicalDigest(TrainingReportCodec.toJson(report, LIMITS)),
                        report);

        String html = TrainingReportHtml.render(changed);

        assertFalse(html.contains("<script>alert"));
        assertFalse(html.contains("<img src=x"));
        assertTrue(html.contains("&lt;script&gt;alert(&#39;name&#39;)&lt;/script&gt;"));
        assertTrue(html.contains("&lt;img src=x onerror=alert(&#39;message&#39;)&gt;"));
        assertTrue(html.contains("&lt;b&gt;H&amp;E&lt;/b&gt;"));
    }

    @Test
    void labelsAChangedOperatorSelectionAsCustomized() throws Exception {
        JsonObject fixture = fixture();
        JsonObject responseValue = fixture.getAsJsonObject("response");
        JsonObject reportValue = responseValue.getAsJsonObject("report").deepCopy();
        reportValue
                .getAsJsonObject("augmentation")
                .getAsJsonArray("enabled_operator_ids")
                .remove(1);
        reportValue
                .getAsJsonObject("trainer_configuration")
                .getAsJsonObject("augmentation")
                .getAsJsonObject("parameters")
                .getAsJsonArray("enabled_operators")
                .remove(1);
        var report = TrainingReportCodec.parseReport(reportValue, LIMITS);
        var identity =
                InferenceIdentityCodec.parseModuleContentIdentity(
                        responseValue.get("module_identity"));

        String html =
                TrainingReportHtml.render(
                        new ModuleTrainingReportResponse(
                                identity,
                                CanonicalCodec.canonicalDigest(
                                        TrainingReportCodec.toJson(report, LIMITS)),
                                report));

        assertTrue(html.contains("Customized H&amp;E"));
        assertTrue(html.contains("Right-angle rotations"));
    }

    @Test
    void noValidationReportOmitsValidationPanelsAndStatesThatNoHoldoutWasUsed() throws Exception {
        JsonObject fixture = fixture();
        JsonObject responseValue = fixture.getAsJsonObject("response");
        JsonObject reportValue = responseValue.getAsJsonObject("report").deepCopy();
        var noValidation = new JsonObject();
        noValidation.addProperty("type", "none");
        reportValue.add("split_plan", noValidation);
        reportValue.remove("validation_counts");
        for (var epoch : reportValue.getAsJsonArray("epochs")) {
            epoch.getAsJsonObject().getAsJsonArray("metrics").remove(1);
        }
        var selection = new JsonObject();
        selection.addProperty("type", "final-weights");
        selection.addProperty("reason", "no_validation");
        selection.addProperty("epoch", 3);
        selection.addProperty("selected_weight_digest", "d".repeat(64));
        reportValue.add("model_selection", selection);
        var report = TrainingReportCodec.parseReport(reportValue, LIMITS);
        var identity =
                InferenceIdentityCodec.parseModuleContentIdentity(
                        responseValue.get("module_identity"));

        String html =
                TrainingReportHtml.render(
                        new ModuleTrainingReportResponse(
                                identity,
                                CanonicalCodec.canonicalDigest(
                                        TrainingReportCodec.toJson(report, LIMITS)),
                                report));

        assertTrue(html.contains("No held-out validation"), html);
        assertFalse(html.contains("Validation mSA"), html);
        assertFalse(html.contains("No observation recorded yet"), html);
    }

    @Test
    void writesACompleteLocalSnapshot() throws Exception {
        Path file = TrainingReportViewer.write(response());
        try {
            String html = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(html.startsWith("<!doctype html>"));
            assertTrue(html.endsWith("</html>"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void metricChartHandlesTheFullFiniteBinary64Range() {
        String html =
                TrainingMetricChart.render(
                        List.of(
                                new TrainingMetricChart.Point(1, -1e308),
                                new TrainingMetricChart.Point(2, 1e308)),
                        2);

        assertTrue(html.contains("<polyline"));
        assertFalse(html.contains("NaN"));
        assertFalse(html.contains("Infinity"));
    }

    @Test
    void metricChartUsesCompactRealAxisLabels() {
        String html =
                TrainingMetricChart.render(
                        List.of(
                                new TrainingMetricChart.Point(1, 0.12345678901234567),
                                new TrainingMetricChart.Point(2, 0.9876543210987654)),
                        2);

        assertTrue(html.contains(">0.1235<"), html);
        assertTrue(html.contains(">0.9877<"), html);
        assertFalse(html.contains("0.12345678901234567"), html);
    }

    @Test
    void groupsCompatibleValidationSeriesOnOneFixedScaleChart() throws Exception {
        JsonObject responseValue = fixture().getAsJsonObject("response");
        JsonObject reportValue = responseValue.getAsJsonObject("report").deepCopy();
        JsonObject macroF1 =
                reportValue
                        .getAsJsonObject("metric_policy")
                        .getAsJsonArray("series")
                        .get(1)
                        .getAsJsonObject()
                        .deepCopy();
        macroF1.addProperty("series_id", "validation_macro_f1");
        macroF1.addProperty("display_name", "Validation macro-F1");
        macroF1.addProperty("definition", "Macro-averaged validation F1.");
        reportValue.getAsJsonObject("metric_policy").getAsJsonArray("series").add(macroF1);
        for (var epoch : reportValue.getAsJsonArray("epochs")) {
            JsonObject value = new JsonObject();
            value.addProperty("series_id", "validation_macro_f1");
            value.addProperty(
                    "value", 0.55 + epoch.getAsJsonObject().get("epoch").getAsInt() / 10.0);
            epoch.getAsJsonObject().getAsJsonArray("metrics").add(value);
        }
        var report = TrainingReportCodec.parseReport(reportValue, LIMITS);

        String html =
                TrainingReportHtml.render(
                        new TrainingReportResponse(
                                4,
                                CanonicalCodec.canonicalDigest(
                                        TrainingReportCodec.toJson(report, LIMITS)),
                                report));

        assertTrue(html.contains("Validation metrics"), html);
        assertTrue(html.contains("Validation macro-F1"), html);
        assertTrue(html.contains("series-1"), html);
        assertEquals(2, html.split("<svg", -1).length - 1, html);
    }

    @Test
    void metricsUseCompactLabelsButRetainExactValuesInDetails() throws Exception {
        JsonObject responseValue = fixture().getAsJsonObject("response");
        JsonObject reportValue = responseValue.getAsJsonObject("report").deepCopy();
        for (var epoch : reportValue.getAsJsonArray("epochs")) {
            int number = epoch.getAsJsonObject().get("epoch").getAsInt();
            epoch.getAsJsonObject()
                    .getAsJsonArray("metrics")
                    .get(1)
                    .getAsJsonObject()
                    .addProperty("value", number == 1 ? 0.61234567 : 0.71234567);
        }
        reportValue
                .getAsJsonArray("epochs")
                .get(2)
                .getAsJsonObject()
                .getAsJsonArray("metrics")
                .get(0)
                .getAsJsonObject()
                .addProperty("value", -0.0000123456);
        reportValue.getAsJsonObject("model_selection").addProperty("value", 0.71234567);
        var report = TrainingReportCodec.parseReport(reportValue, LIMITS);

        String html =
                TrainingReportHtml.render(
                        new TrainingReportResponse(
                                5,
                                CanonicalCodec.canonicalDigest(
                                        TrainingReportCodec.toJson(report, LIMITS)),
                                report));

        assertTrue(html.contains("0.712 · epoch 3"), html);
        assertTrue(html.contains("-1.235e-05 · epoch 3"), html);
        assertTrue(html.contains("0.71234567"), html);
        assertTrue(html.contains("-1.23456E-5"), html);
    }

    @Test
    void oneEpochChartRendersAVisiblePoint() {
        String html =
                TrainingMetricChart.render(List.of(new TrainingMetricChart.Point(1, -0.25)), 3);

        assertTrue(html.contains("<circle"), html);
    }

    @Test
    void reportUsesLightResponsiveLayoutAndLocalSectionLinks() throws Exception {
        String html = TrainingReportHtml.render(response());

        assertTrue(html.contains("color-scheme: light;"));
        assertFalse(html.contains("prefers-color-scheme:dark"));
        assertFalse(html.contains("prefers-color-scheme: dark"));
        assertTrue(html.contains("body { margin: 0; background: #fff; }"));
        assertTrue(html.contains("class=\"run-summary\""));
        assertTrue(html.contains("class=\"metric-grid\""));
        assertTrue(html.contains("data-state=\"SUCCEEDED\""));
        for (String section : List.of("metrics", "dataset", "configuration", "technical")) {
            assertTrue(html.contains("href=\"#" + section + "\""));
            assertTrue(html.contains("id=\"" + section + "\""));
        }
        assertFalse(html.contains("<link"));
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("@import"));
    }

    @Test
    void failedRunExplainsItsFailureBeforeTheCharts() throws Exception {
        JsonObject value = fixture().getAsJsonObject("response").getAsJsonObject("report");
        value.addProperty("state", "FAILED");
        value.remove("model_selection");
        var failure = new JsonObject();
        failure.addProperty("schema_version", 1);
        failure.addProperty("code", "RUNNER_FAILED");
        failure.addProperty("message", "Worker ran out of memory");
        value.add("failure", failure);
        var report = TrainingReportCodec.parseReport(value, LIMITS);
        String html =
                TrainingReportHtml.render(
                        new TrainingReportResponse(
                                1, CanonicalCodec.canonicalDigest(value), report));

        assertTrue(html.contains("data-state=\"FAILED\""));
        assertTrue(
                html.indexOf("Worker ran out of memory") < html.indexOf("<section id=\"metrics\""));
    }

    @Test
    void emptyRunShowsPendingValuesWithoutInventingAChartOrCheckpoint() throws Exception {
        JsonObject value = fixture().getAsJsonObject("response").getAsJsonObject("report");
        value.addProperty("state", "QUEUED");
        value.remove("model_selection");
        value.add("epochs", new com.google.gson.JsonArray());
        var report = TrainingReportCodec.parseReport(value, LIMITS);
        String html =
                TrainingReportHtml.render(
                        new TrainingReportResponse(
                                1, CanonicalCodec.canonicalDigest(value), report));

        assertTrue(html.contains("Snapshot through epoch 0"));
        assertTrue(html.contains("Selected checkpoint</dt><dd>Pending</dd>"));
        assertTrue(html.contains("No validation values recorded"));
        assertTrue(html.contains("No values recorded"));
        assertFalse(html.contains("<svg"));
    }

    @Test
    void cancelledEmptyRunDoesNotSuggestMetricsAreStillPending() throws Exception {
        JsonObject value = fixture().getAsJsonObject("response").getAsJsonObject("report");
        value.addProperty("state", "CANCELLED");
        value.remove("model_selection");
        value.add("epochs", new com.google.gson.JsonArray());
        var report = TrainingReportCodec.parseReport(value, LIMITS);
        String html =
                TrainingReportHtml.render(
                        new TrainingReportResponse(
                                1, CanonicalCodec.canonicalDigest(value), report));

        assertTrue(html.contains("Selected checkpoint</dt><dd>Not selected</dd>"));
        assertTrue(html.contains("Not recorded"));
        assertFalse(html.contains("Pending"));
        assertFalse(html.contains("<svg"));
    }

    private static ModuleTrainingReportResponse response() throws IOException {
        JsonObject value = fixture().getAsJsonObject("response");
        return TrainingReportCodec.parseModuleResponse(
                value,
                LIMITS,
                InferenceIdentityCodec.parseModuleContentIdentity(value.get("module_identity")),
                value.get("report_digest").getAsString());
    }

    private static JsonObject fixture() throws IOException {
        try (var input =
                TrainingReportHtmlTest.class.getResourceAsStream(
                        "/contracts/module-training-report-v1.json")) {
            if (input == null) {
                throw new IOException("missing module training report fixture");
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
