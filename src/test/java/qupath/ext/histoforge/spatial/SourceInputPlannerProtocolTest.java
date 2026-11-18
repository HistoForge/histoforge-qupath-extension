package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.SourceInputPlan.ExecutionProtocol;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CapturedSourcePyramid;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.CropModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.FieldModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.ParentCandidate;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SelectedModel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourceLevel;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.TargetWork;

/** Executes the shared finite-versus-recoverable source-planning fixture. */
class SourceInputPlannerProtocolTest {

    @Test
    void sharedFixturePinsEverySpatialSelectionRule() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());
        FixtureContext context = context(fixture);

        for (JsonElement value : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = value.getAsJsonObject();
            assertEquals(
                    expected(testCase),
                    select(testCase, context),
                    testCase.get("name").getAsString());
        }
    }

    @Test
    void parentCountCannotSelectTheProtocol() throws IOException {
        JsonObject fixture = fixture();
        FixtureContext context = context(fixture);
        JsonObject testCase =
                namedCase(fixture, "small-current-parents-and-excluded-large-objects-stay-finite")
                        .deepCopy();
        JsonArray parents =
                testCase.getAsJsonObject("cropWork")
                        .getAsJsonObject("child-current")
                        .getAsJsonArray("currentParents");
        JsonElement template = parents.get(0);
        for (int index = 0; index < 500; index++) {
            parents.add(template.deepCopy());
        }

        assertEquals(ExecutionProtocol.FINITE, select(testCase, context));
    }

    @Test
    void possibleUpstreamParentBoundsDoNotDependOnSelectedModelListOrder() throws IOException {
        JsonObject fixture = fixture();
        FixtureContext context = context(fixture);
        JsonObject testCase =
                namedCase(fixture, "possible-upstream-parent-crop-overflow").deepCopy();
        JsonArray selected = testCase.getAsJsonArray("selectedModels");
        JsonArray reversed = new JsonArray();
        for (int index = selected.size() - 1; index >= 0; index--) {
            reversed.add(selected.get(index).deepCopy());
        }
        testCase.add("selectedModels", reversed);

        assertEquals(ExecutionProtocol.RECOVERABLE, select(testCase, context));
    }

    private static ExecutionProtocol select(JsonObject testCase, FixtureContext context) {
        var region = ExecutionScopeCodec.parseRegionKey(testCase.get("region"));
        var geometry =
                CanonicalGeometryCodec.parseGeoJson(testCase.getAsJsonObject("targetGeometry"));
        var target =
                new ExecutionScopeCodec.TargetRegion(
                        region,
                        geometry,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        CanonicalGeometryCodec.canonicalDigest(geometry));
        return SourceInputPlanner.plan(
                        context.source(),
                        List.of(
                                new TargetWork(
                                        target,
                                        selectedModels(
                                                testCase, context.models(), context.moduleRef()))),
                        Optional.empty(),
                        new SourcePlanningInputs.SourcePlanningLimits(
                                32_000_000, 1L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024))
                .targets()
                .getFirst()
                .sourcePlan()
                .map(SourceInputPlan.TargetSourcePlan::protocol)
                .orElse(ExecutionProtocol.FINITE);
    }

    private static ExecutionProtocol expected(JsonObject testCase) {
        return ExecutionProtocol.valueOf(
                testCase.get("expectedProtocol").getAsString().toUpperCase(java.util.Locale.ROOT));
    }

    private static FixtureContext context(JsonObject fixture) {
        ModuleRef moduleRef = ModuleRef.parse(fixture.get("moduleRef").getAsString()).orElseThrow();
        JsonObject source = fixture.getAsJsonObject("source");
        var sourceIdentity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "protocol-fixture",
                        "0".repeat(64),
                        Optional.empty(),
                        source.get("width").getAsInt(),
                        source.get("height").getAsInt(),
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(
                                source.get("mppX").getAsDouble(),
                                source.get("mppY").getAsDouble()));
        var revision =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                "protocol-fixture",
                                SourceIdentityCodec.imageSourceIdentityDigest(sourceIdentity),
                                "1".repeat(64)),
                        sourceIdentity);
        var pyramid =
                new CapturedSourcePyramid(
                        revision,
                        0,
                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0),
                        List.of(
                                new SourceLevel(
                                        0,
                                        new SourceViewGeometry.Dimensions(
                                                sourceIdentity.width(), sourceIdentity.height()),
                                        new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0))));
        return new FixtureContext(moduleRef, pyramid, fixture.getAsJsonObject("models"));
    }

    private static List<SelectedModel> selectedModels(
            JsonObject testCase, JsonObject models, ModuleRef moduleRef) {
        List<SelectedModel> result = new ArrayList<>();
        JsonObject cropWork = testCase.getAsJsonObject("cropWork");
        for (JsonElement selected : testCase.getAsJsonArray("selectedModels")) {
            String modelName = selected.getAsString();
            JsonObject value = models.getAsJsonObject(modelName);
            var producer = producer(moduleRef, value.get("stepId").getAsString());
            double targetMpp = value.get("targetMpp").getAsDouble();
            var limits =
                    ExecutionLimitsCodec.parseTargetRasterLimits(
                            value.get("finiteTargetRasterLimits"));
            if ("field".equals(value.get("type").getAsString())) {
                result.add(
                        new FieldModel(
                                producer,
                                targetMpp,
                                Optional.empty(),
                                limits,
                                true,
                                Set.of(
                                        SourceArtifactCodec.SourceContainer.PNG,
                                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY)));
            } else {
                JsonObject selectedCropWork = cropWork.getAsJsonObject(modelName);
                result.add(
                        new CropModel(
                                producer,
                                targetMpp,
                                limits,
                                true,
                                Set.of(
                                        SourceArtifactCodec.SourceContainer.PNG,
                                        SourceArtifactCodec.SourceContainer.TIFF_FAMILY),
                                value.get("paddingModelPx").getAsInt(),
                                producer(moduleRef, value.get("parentStepId").getAsString()),
                                strings(value.getAsJsonArray("parentClasses")),
                                strings(value.getAsJsonArray("scriptVisibleClasses")),
                                selectedCropWork.get("discoverParentsAcrossRegion").getAsBoolean(),
                                parents(
                                        selectedCropWork.getAsJsonArray("currentParents"),
                                        moduleRef)));
            }
        }
        return List.copyOf(result);
    }

    private static List<ParentCandidate> parents(JsonArray values, ModuleRef moduleRef) {
        List<ParentCandidate> result = new ArrayList<>();
        for (JsonElement element : values) {
            JsonObject value = element.getAsJsonObject();
            Optional<String> currentPathClass =
                    value.has("pathClass")
                            ? Optional.of(value.get("pathClass").getAsString())
                            : Optional.empty();
            Optional<InferenceIdentityCodec.ModelStepIdentity> producer =
                    value.has("producerStepId")
                            ? Optional.of(
                                    producer(moduleRef, value.get("producerStepId").getAsString()))
                            : Optional.empty();
            result.add(
                    new ParentCandidate(
                            currentPathClass,
                            producer,
                            CanonicalGeometryCodec.parseGeoJson(
                                    value.getAsJsonObject("geometry"))));
        }
        return List.copyOf(result);
    }

    private static InferenceIdentityCodec.ModelStepIdentity producer(
            ModuleRef moduleRef, String stepId) {
        return new InferenceIdentityCodec.ModelStepIdentity(moduleRef, stepId);
    }

    private static Set<String> strings(JsonArray values) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement value : values) {
            result.add(value.getAsString());
        }
        return Set.copyOf(result);
    }

    private static JsonObject namedCase(JsonObject fixture, String name) {
        for (JsonElement value : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = value.getAsJsonObject();
            if (name.equals(testCase.get("name").getAsString())) {
                return testCase;
            }
        }
        throw new IllegalArgumentException("missing fixture case " + name);
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream input =
                SourceInputPlannerProtocolTest.class
                        .getClassLoader()
                        .getResourceAsStream("contracts/execution-protocol-v1.json")) {
            if (input == null) {
                throw new IOException("missing execution-protocol fixture");
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private record FixtureContext(
            ModuleRef moduleRef, CapturedSourcePyramid source, JsonObject models) {}
}
