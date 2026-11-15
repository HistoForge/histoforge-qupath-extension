package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingRunTypes.TrainingRunRecord;

class TrainingRunContractFixtureTest {

    @Test
    void freezesEveryTrainingRunWireValueAcrossJavaAndPython() throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        JsonObject acceptedFixture = fixture.getAsJsonObject("accepted");
        var accepted = TrainingRunCodec.parseAccepted(acceptedFixture.get("value"));
        assertEquals(
                acceptedFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingRunCodec.toJson(accepted)));

        Map<String, TrainingRunRecord> records = new LinkedHashMap<>();
        fixture.getAsJsonArray("records")
                .forEach(
                        element -> {
                            JsonObject recordFixture = element.getAsJsonObject();
                            TrainingRunRecord record =
                                    TrainingRunCodec.parseRecord(
                                            recordFixture.get("value"), limits);
                            assertEquals(
                                    recordFixture.get("sha256").getAsString(),
                                    CanonicalCodec.canonicalDigest(
                                            TrainingRunCodec.toJson(record, limits)));
                            records.put(recordFixture.get("name").getAsString(), record);
                        });

        assertEquals(
                accepted,
                TrainingRunCodec.validateAcceptance(
                        records.get("queued").request(), acceptedFixture.get("value"), limits));
        assertEquals("FINALIZING", records.get("finalizing").state());
        assertFalse(records.get("finalizing").result().isPresent());
        assertEquals("CANCELLED", records.get("cancelled").state());

        JsonObject pageFixture = fixture.getAsJsonObject("page");
        var page = TrainingRunPageCodec.parsePage(pageFixture.get("value"), limits);
        assertEquals(
                pageFixture.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(TrainingRunPageCodec.toJson(page, limits)));
        assertEquals(page.items().get(1).createdAt(), page.items().get(2).createdAt());
        assertTrue(page.items().get(1).runId().compareTo(page.items().get(2).runId()) < 0);

        JsonObject reportMeta = fixture.getAsJsonObject("report_response");
        JsonObject moduleReport = fixture("/contracts/module-training-report-v1.json");
        JsonObject response = new JsonObject();
        response.addProperty("report_revision", reportMeta.get("report_revision").getAsInt());
        response.addProperty("report_digest", reportMeta.get("report_digest").getAsString());
        response.add(
                "report",
                moduleReport.getAsJsonObject("response").getAsJsonObject("report").deepCopy());
        var parsedResponse =
                TrainingRunReportCodec.validateForRun(records.get("succeeded"), response, limits);
        assertEquals(
                reportMeta.get("sha256").getAsString(),
                CanonicalCodec.canonicalDigest(
                        TrainingRunReportCodec.toJson(parsedResponse, limits)));
    }

    @Test
    void rejectsCrossFieldAndOrderingDrift() throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");

        JsonObject failed =
                fixture.getAsJsonArray("records")
                        .get(2)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        failed.getAsJsonObject("result").addProperty("type", "cancelled");
        assertThrows(
                IllegalArgumentException.class, () -> TrainingRunCodec.parseRecord(failed, limits));

        JsonObject oversizedFailure =
                fixture.getAsJsonArray("records")
                        .get(2)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        oversizedFailure
                .getAsJsonObject("result")
                .getAsJsonObject("error")
                .addProperty("message", "x".repeat(4097));
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunCodec.parseRecord(oversizedFailure, limits));

        JsonObject oversizedModelId =
                fixture.getAsJsonArray("records")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        oversizedModelId
                .getAsJsonObject("request")
                .addProperty("selected_model_id", "a".repeat(129));
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunCodec.parseRecord(oversizedModelId, limits));

        JsonObject page = fixture.getAsJsonObject("page").getAsJsonObject("value").deepCopy();
        var items = page.getAsJsonArray("items");
        var first = items.remove(0);
        items.add(first);
        assertThrows(
                IllegalArgumentException.class, () -> TrainingRunPageCodec.parsePage(page, limits));

        JsonObject duplicateIdPage =
                fixture.getAsJsonObject("page").getAsJsonObject("value").deepCopy();
        var duplicateItems = duplicateIdPage.getAsJsonArray("items");
        duplicateItems
                .get(1)
                .getAsJsonObject()
                .addProperty(
                        "run_id",
                        duplicateItems.get(0).getAsJsonObject().get("run_id").getAsString());
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunPageCodec.parsePage(duplicateIdPage, limits));

        JsonObject incompleteSuccess =
                fixture.getAsJsonObject("page").getAsJsonObject("value").deepCopy();
        for (var item : incompleteSuccess.getAsJsonArray("items")) {
            JsonObject summary = item.getAsJsonObject();
            if ("SUCCEEDED".equals(summary.get("state").getAsString())) {
                summary.getAsJsonObject("progress").addProperty("completed_epochs", 0);
            }
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunPageCodec.parsePage(incompleteSuccess, limits));

        JsonObject accepted =
                fixture.getAsJsonObject("accepted").getAsJsonObject("value").deepCopy();
        accepted.addProperty("request_digest", "f".repeat(64));
        TrainingRunRecord queued =
                TrainingRunCodec.parseRecord(
                        fixture.getAsJsonArray("records").get(0).getAsJsonObject().get("value"),
                        limits);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunCodec.validateAcceptance(queued.request(), accepted, limits));
    }

    @Test
    void rejectsReportContentOrSelectionThatDiffersFromItsRun() throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        TrainingRunRecord succeeded =
                TrainingRunCodec.parseRecord(
                        fixture.getAsJsonArray("records").get(1).getAsJsonObject().get("value"),
                        limits);
        JsonObject reportMeta = fixture.getAsJsonObject("report_response");
        JsonObject report =
                fixture("/contracts/module-training-report-v1.json")
                        .getAsJsonObject("response")
                        .getAsJsonObject("report")
                        .deepCopy();

        JsonObject changedContent = response(reportMeta, report.deepCopy());
        changedContent
                .getAsJsonObject("report")
                .addProperty("result_display_name", "Different result");
        refreshReportDigest(changedContent);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunReportCodec.validateForRun(succeeded, changedContent, limits));

        JsonObject changedResultGpu = response(reportMeta, report.deepCopy());
        changedResultGpu.getAsJsonObject("report").addProperty("result_inference_gpu", "L40S");
        refreshReportDigest(changedResultGpu);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunReportCodec.validateForRun(succeeded, changedResultGpu, limits));

        JsonObject changedArea = response(reportMeta, report.deepCopy());
        changedArea.getAsJsonObject("report").addProperty("min_instance_pixels", 0);
        refreshReportDigest(changedArea);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunReportCodec.validateForRun(succeeded, changedArea, limits));

        JsonObject changedSelection = response(reportMeta, report.deepCopy());
        changedSelection
                .getAsJsonObject("report")
                .getAsJsonObject("model_selection")
                .addProperty("selected_weight_digest", "f".repeat(64));
        refreshReportDigest(changedSelection);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunReportCodec.validateForRun(succeeded, changedSelection, limits));
    }

    @Test
    void acceptsAReportThatAdvancedAfterTheEarlierRunRead() throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        JsonObject earlier =
                fixture.getAsJsonArray("records")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        earlier.addProperty("state", "RUNNING");
        earlier.addProperty("report_revision", 1);
        earlier.getAsJsonObject("progress").addProperty("completed_epochs", 0);
        earlier.remove("finalization");
        earlier.remove("result");
        TrainingRunRecord running = TrainingRunCodec.parseRecord(earlier, limits);

        JsonObject reportMeta = fixture.getAsJsonObject("report_response");
        JsonObject response =
                response(
                        reportMeta,
                        fixture("/contracts/module-training-report-v1.json")
                                .getAsJsonObject("response")
                                .getAsJsonObject("report")
                                .deepCopy());

        assertEquals(
                reportMeta.get("report_revision").getAsInt(),
                TrainingRunReportCodec.validateForRun(running, response, limits).reportRevision());
    }

    @Test
    void rejectsAChangedSuccessReportAfterFinalizationWasObserved() throws Exception {
        TrainingApiLimits limits = limits();
        JsonObject fixture = fixture("/contracts/training-runs-v1.json");
        JsonObject observed =
                fixture.getAsJsonArray("records")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("value")
                        .deepCopy();
        observed.addProperty("state", "FINALIZING");
        observed.addProperty("report_revision", 4);
        observed.remove("result");
        TrainingRunRecord finalizing = TrainingRunCodec.parseRecord(observed, limits);
        JsonObject reportMeta = fixture.getAsJsonObject("report_response");
        JsonObject report =
                fixture("/contracts/module-training-report-v1.json")
                        .getAsJsonObject("response")
                        .getAsJsonObject("report")
                        .deepCopy();
        report.getAsJsonArray("messages")
                .get(0)
                .getAsJsonObject()
                .addProperty("message", "Different but internally valid report.");
        JsonObject response = response(reportMeta, report);
        refreshReportDigest(response);
        TrainingRunReportCodec.parse(response, limits);

        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunReportCodec.validateForRun(finalizing, response, limits));
    }

    private static JsonObject response(JsonObject metadata, JsonObject report) {
        JsonObject response = new JsonObject();
        response.addProperty("report_revision", metadata.get("report_revision").getAsInt());
        response.addProperty("report_digest", metadata.get("report_digest").getAsString());
        response.add("report", report);
        return response;
    }

    private static void refreshReportDigest(JsonObject response) {
        response.addProperty(
                "report_digest",
                CanonicalCodec.canonicalDigest(response.getAsJsonObject("report")));
    }

    private static TrainingApiLimits limits() throws Exception {
        return TrainingLimitsCodec.parse(
                fixture("/contracts/training-datasets-v1.json").get("trainingApiLimits"));
    }

    private static JsonObject fixture(String resource) throws Exception {
        try (var stream = TrainingRunContractFixtureTest.class.getResourceAsStream(resource);
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
