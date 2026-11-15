package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.TrainingTaskCodec.VariableFieldProfile;

class TrainingTaskCodecTest {

    @Test
    void variableFieldV2RoundTripsTheExactOuterBorder() {
        JsonObject encoded = variableFieldProfile();

        VariableFieldProfile parsed =
                (VariableFieldProfile) TrainingTaskCodec.parseFieldProfile(encoded);

        assertEquals(8, parsed.outerArtificialPaddingModelPx());
        assertEquals(encoded, TrainingTaskCodec.toJson(parsed));
    }

    @Test
    void variableFieldV2RequiresTheExactOuterBorder() {
        for (int invalid : new int[] {0, 7, 9}) {
            JsonObject encoded = variableFieldProfile();
            encoded.addProperty("outerArtificialPaddingModelPx", invalid);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TrainingTaskCodec.parseFieldProfile(encoded));
        }

        JsonObject missing = variableFieldProfile();
        missing.remove("outerArtificialPaddingModelPx");
        assertThrows(
                IllegalArgumentException.class, () -> TrainingTaskCodec.parseFieldProfile(missing));

        JsonObject booleanValue = variableFieldProfile();
        booleanValue.addProperty("outerArtificialPaddingModelPx", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingTaskCodec.parseFieldProfile(booleanValue));
    }

    @Test
    void legacyVariableFieldPlannerIsNotAnAdmittedModuleContract() {
        JsonObject encoded = variableFieldProfile();
        encoded.addProperty("planner", "variable-field-v1");

        assertThrows(
                IllegalArgumentException.class, () -> TrainingTaskCodec.parseFieldProfile(encoded));
    }

    private static JsonObject variableFieldProfile() {
        return JsonParser.parseString(
                        """
                        {
                          "planner":"variable-field-v2",
                          "minSideModelPx":256,
                          "maxSideModelPx":1024,
                          "preferredTileSideModelPx":768,
                          "alignmentModelPx":16,
                          "outerArtificialPaddingModelPx":8,
                          "smallRoiContext":true,
                          "tileOverlap":0.25,
                          "aggregation":"mean-logits-v1",
                          "preprocessing":"brightfield-rgb-v1"
                        }
                        """)
                .getAsJsonObject();
    }
}
