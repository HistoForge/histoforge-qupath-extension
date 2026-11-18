package qupath.ext.histoforge.ui.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.InferenceIdentityCodec.ModuleContentIdentity;
import qupath.ext.histoforge.contract.ModelOutputCodec.ModelOutput;
import qupath.ext.histoforge.contract.ModuleCatalogTypes.InstalledModuleDescriptor;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingReportLimits;
import qupath.ext.histoforge.contract.TrainingReportCodec;
import qupath.ext.histoforge.contract.TrainingReportTypes.TrainingReport;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.modules.ModelSpec;
import qupath.ext.histoforge.modules.ModuleRecipe;

/** Exact and hostile-text evidence for the generated initial Hugging Face README. */
class PublishCardTest {

    private static final TrainingReportLimits LIMITS =
            new TrainingReportLimits(32, 1_000, 128, 4_096, 65_536, 16, 4_096, 8 * 1024 * 1024);

    @Test
    void render_matchesFrozenTrainedModuleCard() throws Exception {
        TrainingReport report = reportFixture();
        var module = trainedModule(report);

        String card = PublishCard.render(module, "alice/my-leydig", Optional.of(report));

        assertEquals(resource("/contracts/trained-module-card-v1.md"), card);
        assertFalse(
                card.contains("rat testis"),
                "Source descriptions are not claims about this result");
    }

    @Test
    void render_ordinaryModuleOmitsTrainingOnlySectionsAndMarksMissingFields() {
        var model = new ModelSpec("dinov3-adapter-hovernet", "A10G", null, null, null, null, null);
        var recipe =
                new ModuleRecipe(
                        1,
                        "M",
                        null,
                        null,
                        null,
                        Map.of("m", model),
                        List.of(new ModuleRecipe.Step("m", null, null, null)));

        String card = PublishCard.render(installed("owner/module", recipe), "owner/module");

        assertTrue(card.contains("# M · HistoForge module"), card);
        assertTrue(card.contains("**Output:** Not specified"), card);
        assertTrue(card.contains("**Classes:** Not specified"), card);
        assertTrue(card.contains("**Input:** Not specified"), card);
        assertTrue(card.contains("**Base encoder:** Not specified"), card);
        assertFalse(card.contains("**Dataset:**"), card);
        assertFalse(card.contains("**Validation:**"), card);
        assertFalse(card.contains("**Source:**"), card);
        assertFalse(card.contains("license:"), card);
        assertFalse(card.contains("**License:**"), card);
    }

    @Test
    void render_requiresTheDeclaredTrainingReportAndEscapesRecipeText() throws Exception {
        TrainingReport report = reportFixture();
        InstalledModule trained = trainedModule(report);
        assertThrows(
                IllegalArgumentException.class, () -> PublishCard.render(trained, "alice/model"));

        var model = trained.recipe().models().get("leydig_nuclear");
        var hostileRecipe =
                new ModuleRecipe(
                        2,
                        "Unsafe <title> *here*",
                        "First line\n# injected heading",
                        null,
                        null,
                        Map.of("leydig_nuclear", model),
                        trained.recipe().steps());

        String card = PublishCard.render(installed("alice/model", hostileRecipe), "alice/model");

        assertTrue(card.contains("Unsafe \\<title\\> \\*here\\*"), card);
        assertTrue(card.contains("First line \\# injected heading"), card);
        assertFalse(card.contains("\n# injected heading"), card);
    }

    @Test
    void render_escapesEntitiesAndBlockSyntaxInRecipeText() throws Exception {
        TrainingReport report = reportFixture();
        var model = trainedModule(report).recipe().models().get("leydig_nuclear");
        var recipe =
                new ModuleRecipe(
                        2,
                        "A &copy; model",
                        "- not a generated list",
                        null,
                        null,
                        Map.of("leydig_nuclear", model),
                        List.of(new ModuleRecipe.Step("leydig_nuclear", null, null, null)));

        String card = PublishCard.render(installed("alice/model", recipe), "alice/model");

        assertTrue(card.contains("A \\&copy; model"), card);
        assertTrue(card.contains("\\- not a generated list"), card);
        assertFalse(card.contains("A &copy; model"), card);
        assertFalse(card.contains("\n- not a generated list"), card);
    }

    @Test
    void render_distinguishesNoValidationFromAnUnavailableValidationMetric() throws Exception {
        JsonObject noValidationValue = reportFixtureValue();
        var noValidationPlan = new JsonObject();
        noValidationPlan.addProperty("type", "none");
        noValidationValue.add("split_plan", noValidationPlan);
        noValidationValue.remove("validation_counts");
        for (var epoch : noValidationValue.getAsJsonArray("epochs")) {
            epoch.getAsJsonObject().getAsJsonArray("metrics").remove(1);
        }
        var finalWeights = new JsonObject();
        finalWeights.addProperty("type", "final-weights");
        finalWeights.addProperty("reason", "no_validation");
        finalWeights.addProperty("epoch", 3);
        finalWeights.addProperty("selected_weight_digest", "d".repeat(64));
        noValidationValue.add("model_selection", finalWeights);
        TrainingReport noValidation = TrainingReportCodec.parseReport(noValidationValue, LIMITS);
        assertTrue(
                PublishCard.render(
                                trainedModule(noValidation),
                                "alice/model",
                                Optional.of(noValidation))
                        .contains("**Validation:** Not performed"));

        JsonObject unavailableValue = reportFixtureValue();
        for (var epoch : unavailableValue.getAsJsonArray("epochs")) {
            epoch.getAsJsonObject().getAsJsonArray("metrics").remove(1);
        }
        var unavailableMetric = new JsonObject();
        unavailableMetric.addProperty("series_id", "validation-instance-msa.v1");
        unavailableMetric.addProperty("reason", "No reference objects were present.");
        unavailableValue.getAsJsonArray("unavailable_metrics").add(unavailableMetric);
        finalWeights = finalWeights.deepCopy();
        finalWeights.addProperty("reason", "primary_metric_unavailable");
        unavailableValue.add("model_selection", finalWeights);
        TrainingReport unavailable = TrainingReportCodec.parseReport(unavailableValue, LIMITS);
        assertTrue(
                PublishCard.render(
                                trainedModule(unavailable), "alice/model", Optional.of(unavailable))
                        .contains("**Validation:** No reference objects were present."));
    }

    private static InstalledModule trainedModule(TrainingReport report) throws IOException {
        var output = new ModelOutput("instance_segmentation", List.of("Leydig cell"), List.of());
        var input = new ModelSpec.InputSpec("field", 0.5, null, null, null);
        var backboneWeights =
                new ModelSpec.WeightsSpec("referenced", null, "MahmoodLab/UNI2-h", "abc123");
        var backbone =
                new ModelSpec("vit-encoder", "A10G", backboneWeights, null, null, null, null);
        var model =
                new ModelSpec("dinov3-adapter-cellpose", "L4", null, input, output, null, backbone);
        JsonObject root = reportFixtureRoot();
        var recipe =
                new ModuleRecipe(
                        2,
                        report.resultDisplayName(),
                        "Detects Leydig nuclei on rat testis H&E.",
                        "histoforge/leydig@1111111111111111111111111111111111111111",
                        root.get("report_digest").getAsString(),
                        Map.of("leydig_nuclear", model),
                        List.of(new ModuleRecipe.Step("leydig_nuclear", null, null, null)));
        return installed("workspace/run-44444444-4444-4444-8444-444444444444", recipe);
    }

    private static TrainingReport reportFixture() throws IOException {
        return TrainingReportCodec.parseReport(reportFixtureValue(), LIMITS);
    }

    private static JsonObject reportFixtureValue() throws IOException {
        return reportFixtureRoot().getAsJsonObject("response").getAsJsonObject("report").deepCopy();
    }

    private static JsonObject reportFixtureRoot() throws IOException {
        return JsonParser.parseString(resource("/contracts/module-training-report-v1.json"))
                .getAsJsonObject();
    }

    private static String resource(String path) throws IOException {
        try (var input = PublishCardTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("missing fixture: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InstalledModule installed(String ref, ModuleRecipe recipe) {
        ModuleRef moduleRef = ModuleRef.parse(ref).orElseThrow();
        InstalledModuleDescriptor descriptor = mock(InstalledModuleDescriptor.class);
        when(descriptor.identity())
                .thenReturn(
                        new ModuleContentIdentity(
                                moduleRef, Optional.empty(), "1".repeat(64), "2".repeat(64)));
        when(descriptor.typedRecipe()).thenReturn(recipe);
        when(descriptor.scripts()).thenReturn(Map.of());
        return new InstalledModule(mock(GatewayCapabilities.class), descriptor);
    }
}
