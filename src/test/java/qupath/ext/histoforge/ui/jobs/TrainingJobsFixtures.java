package qupath.ext.histoforge.ui.jobs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.TrainingRunPageCodec;

/** Uses the checked-in cross-language fixtures; no second production-shaped schema. */
final class TrainingJobsFixtures {

    private TrainingJobsFixtures() {}

    static GatewayCapabilities capabilities() throws IOException {
        return GatewayCapabilitiesCodec.parseGatewayCapabilities(
                fixture("gateway-capabilities-v1.json")
                        .getAsJsonArray("capabilityCases")
                        .get(0)
                        .getAsJsonObject()
                        .get("value"));
    }

    static JsonObject page() throws IOException {
        return fixture("training-runs-v1.json").getAsJsonObject("page").getAsJsonObject("value");
    }

    static JobRow.Training row(String state) throws IOException {
        JsonObject template = page().getAsJsonArray("items").get(2).getAsJsonObject().deepCopy();
        template.addProperty("state", state);
        template.remove("result");
        template.remove("primary_metric");
        int completed =
                "QUEUED".equals(state)
                        ? 0
                        : "SUCCEEDED".equals(state) || "FINALIZING".equals(state) ? 3 : 2;
        template.getAsJsonObject("progress").addProperty("completed_epochs", completed);
        if ("SUCCEEDED".equals(state) || "CANCELLED".equals(state)) {
            var result = new JsonObject();
            result.addProperty("type", "SUCCEEDED".equals(state) ? "succeeded" : "cancelled");
            if ("CANCELLED".equals(state)) {
                result.addProperty("report_digest", "c".repeat(64));
            }
            template.add("result", result);
        } else if ("FAILED".equals(state)) {
            template.add(
                    "result",
                    page().getAsJsonArray("items").get(0).getAsJsonObject().get("result"));
        }
        return new JobRow.Training(
                TrainingRunPageCodec.parseSummary(template, capabilities().trainingApiLimits()));
    }

    static JsonObject fixture(String filename) throws IOException {
        try (var input = TrainingJobsFixtures.class.getResourceAsStream("/contracts/" + filename)) {
            if (input == null) {
                throw new IOException("missing fixture: " + filename);
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
