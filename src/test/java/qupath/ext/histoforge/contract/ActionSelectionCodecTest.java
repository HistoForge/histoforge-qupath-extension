package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Runs the shared schema-1 action, scope, and output-key contract. */
class ActionSelectionCodecTest {

    @Test
    void sharedFixtureNormalizesActionsAndOutputKeysExactly() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        for (JsonElement item : fixture.getAsJsonArray("actions")) {
            JsonObject testCase = item.getAsJsonObject();
            var action = ActionSelectionCodec.parseImageAction(testCase.get("input"));
            assertExact(
                    testCase,
                    ActionSelectionCodec.canonicalBytes(action),
                    testCase.get("name").getAsString());
            assertEquals(
                    action,
                    ActionSelectionCodec.parseImageAction(ActionSelectionCodec.toJson(action)));
        }

        for (JsonElement item : fixture.getAsJsonArray("outputKeys")) {
            JsonObject testCase = item.getAsJsonObject();
            var output = ExecutionScopeCodec.parseOutputKey(testCase.get("input"));
            assertExact(
                    testCase,
                    ExecutionScopeCodec.canonicalBytes(output),
                    testCase.get("name").getAsString());
            assertEquals(
                    output, ExecutionScopeCodec.parseOutputKey(ExecutionScopeCodec.toJson(output)));
        }
    }

    @Test
    void parsedCollectionsAreNormalizedAndImmutable() throws IOException {
        JsonObject input = actionCase(1).getAsJsonObject("input");
        var action = (ActionSelectionCodec.Redo) ActionSelectionCodec.parseImageAction(input);
        var work = (ActionSelectionCodec.SelectedModels) action.work();
        var regions = (ActionSelectionCodec.RoiRegions) work.regions();

        assertEquals(
                java.util.List.of(
                        "11111111-1111-4111-8111-111111111111",
                        "22222222-2222-4222-8222-222222222222"),
                regions.roiIds().stream().toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> regions.roiIds().add("99999999-9999-4999-8999-999999999999"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> work.steps().add(work.steps().getFirst()));
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        work.steps()
                                .getFirst()
                                .targets()
                                .selectors()
                                .add(new ActionSelectionCodec.SlideTarget()));
    }

    @Test
    void regionAndTargetMembershipFailClosed() throws IOException {
        JsonObject selected = actionCase(1).getAsJsonObject("input");

        JsonObject invalid = selected.deepCopy();
        invalid.getAsJsonObject("work").getAsJsonObject("regions").add("roi_ids", new JsonArray());
        JsonObject emptyRois = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(emptyRois));

        invalid = selected.deepCopy();
        JsonArray roiIds =
                invalid.getAsJsonObject("work")
                        .getAsJsonObject("regions")
                        .getAsJsonArray("roi_ids");
        roiIds.add(roiIds.get(0).deepCopy());
        JsonObject duplicateRoi = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(duplicateRoi));

        invalid = selected.deepCopy();
        invalid.getAsJsonObject("work").add("steps", new JsonArray());
        JsonObject emptySteps = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(emptySteps));

        invalid = selected.deepCopy();
        JsonArray steps = invalid.getAsJsonObject("work").getAsJsonArray("steps");
        steps.add(steps.get(0).deepCopy());
        JsonObject duplicateStep = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(duplicateStep));

        invalid = selected.deepCopy();
        JsonArray selectors = selectors(invalid, 0);
        selectors.add(selectors.get(0).deepCopy());
        JsonObject duplicateSelector = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(duplicateSelector));

        invalid = selected.deepCopy();
        selectors(invalid, 0)
                .get(0)
                .getAsJsonObject()
                .addProperty("roi_id", "99999999-9999-4999-8999-999999999999");
        JsonObject outside = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(outside));

        invalid = selected.deepCopy();
        JsonArray slideTarget = new JsonArray();
        slideTarget.add(JsonParser.parseString("{\"type\":\"slide-target\"}"));
        selectors(invalid, 0).remove(0);
        selectors(invalid, 0).remove(0);
        invalid.getAsJsonObject("work")
                .getAsJsonArray("steps")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("targets")
                .add("selectors", slideTarget);
        JsonObject mixedMode = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(mixedMode));

        JsonObject slide = actionCase(2).getAsJsonObject("input").deepCopy();
        JsonArray roiTarget = new JsonArray();
        roiTarget.add(
                JsonParser.parseString(
                        "{\"type\":\"roi-target\",\"roi_id\":"
                                + "\"11111111-1111-4111-8111-111111111111\"}"));
        slide.getAsJsonObject("work")
                .getAsJsonArray("steps")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("targets")
                .add("selectors", roiTarget);
        assertThrows(
                IllegalArgumentException.class, () -> ActionSelectionCodec.parseImageAction(slide));

        invalid = selected.deepCopy();
        selectors(invalid, 1)
                .get(1)
                .getAsJsonObject()
                .add("region", JsonParser.parseString("{\"type\":\"slide\"}"));
        JsonObject slideParentInRois = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(slideParentInRois));
    }

    @Test
    void closedActionAndOutputShapesFailClosed() throws IOException {
        JsonObject fill = actionCase(0).getAsJsonObject("input");

        JsonObject invalid = fill.deepCopy();
        invalid.addProperty("type", "run");
        JsonObject unknownAction = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(unknownAction));

        invalid = fill.deepCopy();
        invalid.addProperty("remove_overlapping_same_class_duplicates", 1);
        JsonObject numericBoolean = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(numericBoolean));

        invalid = fill.deepCopy();
        invalid.addProperty("extra", true);
        JsonObject extra = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> ActionSelectionCodec.parseImageAction(extra));

        JsonObject metrics = actionCase(3).getAsJsonObject("input").deepCopy();
        metrics.addProperty("remove_overlapping_same_class_duplicates", true);
        JsonObject metricsWithToggle = metrics;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(metricsWithToggle));

        metrics = actionCase(3).getAsJsonObject("input").deepCopy();
        metrics.getAsJsonObject("targets")
                .getAsJsonObject("region")
                .add("annotation_ids", new JsonArray());
        JsonObject emptyAnnotations = metrics;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(emptyAnnotations));

        metrics = actionCase(3).getAsJsonObject("input").deepCopy();
        JsonArray annotationIds =
                metrics.getAsJsonObject("targets")
                        .getAsJsonObject("region")
                        .getAsJsonArray("annotation_ids");
        annotationIds.add(annotationIds.get(0).deepCopy());
        JsonObject duplicateAnnotations = metrics;
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionSelectionCodec.parseImageAction(duplicateAnnotations));

        JsonObject output = outputCase(0).getAsJsonObject("input").deepCopy();
        output.getAsJsonObject("scope").addProperty("type", "crop");
        JsonObject invalidScope = output;
        assertThrows(
                IllegalArgumentException.class,
                () -> ExecutionScopeCodec.parseOutputKey(invalidScope));

        output = outputCase(0).getAsJsonObject("input").deepCopy();
        output.getAsJsonObject("producer").addProperty("step_id", "not-kebab");
        JsonObject invalidStep = output;
        assertThrows(
                IllegalArgumentException.class,
                () -> ExecutionScopeCodec.parseOutputKey(invalidStep));
    }

    private static JsonArray selectors(JsonObject action, int step) {
        return action.getAsJsonObject("work")
                .getAsJsonArray("steps")
                .get(step)
                .getAsJsonObject()
                .getAsJsonObject("targets")
                .getAsJsonArray("selectors");
    }

    private static void assertExact(JsonObject testCase, byte[] encoded, String name) {
        assertEquals(
                testCase.get("canonical").getAsString(),
                new String(encoded, StandardCharsets.UTF_8),
                name);
        assertEquals(testCase.get("sha256").getAsString(), CanonicalCodec.sha256(encoded), name);
    }

    private static JsonObject actionCase(int index) throws IOException {
        return fixture().getAsJsonArray("actions").get(index).getAsJsonObject();
    }

    private static JsonObject outputCase(int index) throws IOException {
        return fixture().getAsJsonArray("outputKeys").get(index).getAsJsonObject();
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream input =
                ActionSelectionCodecTest.class
                        .getClassLoader()
                        .getResourceAsStream("contracts/action-selection-v1.json")) {
            if (input == null) {
                throw new IOException("missing action-selection fixture");
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
