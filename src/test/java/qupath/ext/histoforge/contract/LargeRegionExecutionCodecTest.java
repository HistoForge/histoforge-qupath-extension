package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LargeRegionExecutionCodecTest {

    @Test
    void sharedFixtureValidatesProfilesAndPlansExactly() throws IOException {
        for (var element : fixture().getAsJsonArray("profiles")) {
            JsonObject testCase = element.getAsJsonObject();
            var profile = LargeRegionProfileCodec.parse(testCase.get("input"));
            assertExact(
                    testCase,
                    LargeRegionProfileCodec.toJson(profile),
                    testCase.get("name").getAsString());
            assertEquals(
                    profile,
                    LargeRegionProfileCodec.parse(LargeRegionProfileCodec.toJson(profile)));
        }

        for (var element : fixture().getAsJsonArray("plans")) {
            JsonObject testCase = element.getAsJsonObject();
            var plan = ModelExecutionPlanCodec.parse(testCase.get("input"));
            assertExact(
                    testCase,
                    ModelExecutionPlanCodec.toJson(plan),
                    testCase.get("name").getAsString());
            assertEquals(plan, ModelExecutionPlanCodec.parse(ModelExecutionPlanCodec.toJson(plan)));
        }
    }

    @Test
    void parsedWindowCollectionsAreImmutable() throws IOException {
        var corePlan =
                (ModelExecutionPlanCodec.CoreHaloPlan)
                        ModelExecutionPlanCodec.parse(planCase(2).get("input"));
        assertThrows(UnsupportedOperationException.class, () -> corePlan.windows().clear());
    }

    @Test
    void automaticCoreHaloPlannerReproducesTheSharedFourWindowPlan() throws IOException {
        var profile = LargeRegionProfileCodec.parse(profileCase(1).get("input"));
        var expected = ModelExecutionPlanCodec.parse(planCase(2).get("input"));
        var raster =
                new RasterInputCodec.RasterDescriptor(
                        1800,
                        1300,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        1.0,
                        RasterInputCodec.ResamplingType.BILINEAR,
                        RasterInputCodec.RasterPixelFormat.SRGB_UINT8);
        var expectedProfile = Optional.of(profile);

        assertEquals(4, ModelExecutionPlanCodec.unitCount(expectedProfile, raster));
        assertEquals(
                expected,
                ModelExecutionPlanCodec.buildExpected(
                        expectedProfile, raster, "5".repeat(64), 4, java.util.Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelExecutionPlanCodec.buildExpected(
                                expectedProfile,
                                raster,
                                "5".repeat(64),
                                3,
                                java.util.Optional.empty()));
    }

    @Test
    void customHaloPinsTheResolvedValueEvenWhenClippedWindowsMatch() throws IOException {
        var profile = LargeRegionProfileCodec.parse(profileCase(1).get("input"));
        var expectedProfile = Optional.of(profile);
        var raster =
                new RasterInputCodec.RasterDescriptor(
                        1800,
                        1300,
                        new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                        1.0,
                        RasterInputCodec.ResamplingType.BILINEAR,
                        RasterInputCodec.RasterPixelFormat.SRGB_UINT8);
        var zero =
                (ModelExecutionPlanCodec.CoreHaloPlan)
                        ModelExecutionPlanCodec.buildExpected(
                                expectedProfile, raster, "5".repeat(64), 4, Optional.of(0));
        assertEquals(0, zero.resolvedHaloPx());
        zero.windows().forEach(window -> assertEquals(window.coreRect(), window.inputRect()));
        var large =
                (ModelExecutionPlanCodec.CoreHaloPlan)
                        ModelExecutionPlanCodec.buildExpected(
                                expectedProfile, raster, "5".repeat(64), 4, Optional.of(2000));
        var larger =
                (ModelExecutionPlanCodec.CoreHaloPlan)
                        ModelExecutionPlanCodec.buildExpected(
                                expectedProfile,
                                raster,
                                "5".repeat(64),
                                4,
                                Optional.of(Integer.MAX_VALUE));
        assertEquals(large.windows(), larger.windows());
        assertNotEquals(large.planDigest(), larger.planDigest());
        assertEquals(profile, LargeRegionProfileCodec.parse(profileCase(1).get("input")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelExecutionPlanCodec.buildExpected(
                                Optional.empty(), raster, "5".repeat(64), 4, Optional.of(0)));
        var global = Optional.of(LargeRegionProfileCodec.parse(profileCase(0).get("input")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ModelExecutionPlanCodec.buildExpected(
                                global, raster, "5".repeat(64), 4, Optional.of(0)));
    }

    @Test
    void profileShapesAndSelfDigestsFailClosed() throws IOException {
        JsonObject invalid = profileCase(0).getAsJsonObject("input").deepCopy();
        invalid.addProperty("validation_evidence_digest", "9".repeat(64));
        JsonObject wrongDigest = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> LargeRegionProfileCodec.parse(wrongDigest));

        invalid = profileCase(1).getAsJsonObject("input").deepCopy();
        invalid.addProperty("core_width_px", 0);
        JsonObject zeroWidth = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> LargeRegionProfileCodec.parse(zeroWidth));

        invalid = profileCase(1).getAsJsonObject("input").deepCopy();
        invalid.addProperty("halo_px", -1);
        JsonObject negativeHalo = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> LargeRegionProfileCodec.parse(negativeHalo));

        invalid = profileCase(1).getAsJsonObject("input").deepCopy();
        invalid.addProperty("core_width_px", 2048);
        JsonObject staleCoreProfileDigest = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> LargeRegionProfileCodec.parse(staleCoreProfileDigest));

        invalid = profileCase(0).getAsJsonObject("input").deepCopy();
        invalid.addProperty("extra", true);
        JsonObject extraField = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> LargeRegionProfileCodec.parse(extraField));

        invalid = profileCase(0).getAsJsonObject("input").deepCopy();
        invalid.addProperty("type", "tiled");
        JsonObject unknownType = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> LargeRegionProfileCodec.parse(unknownType));
    }

    @Test
    void coreWindowsRequireCanonicalCompleteContainedCover() throws IOException {
        JsonObject corePlan = planCase(2).getAsJsonObject("input");

        JsonObject invalid = corePlan.deepCopy();
        windows(invalid).get(1).getAsJsonObject().addProperty("unit_id", 0);
        JsonObject duplicateUnit = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> ModelExecutionPlanCodec.parse(duplicateUnit));

        invalid = corePlan.deepCopy();
        JsonObject core = windows(invalid).get(1).getAsJsonObject().getAsJsonObject("core_rect");
        core.addProperty("x", core.get("x").getAsInt() + 1);
        core.addProperty("width", core.get("width").getAsInt() - 1);
        JsonObject columnGap = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> ModelExecutionPlanCodec.parse(columnGap));

        invalid = corePlan.deepCopy();
        for (int index = 2; index < windows(invalid).size(); index++) {
            core = windows(invalid).get(index).getAsJsonObject().getAsJsonObject("core_rect");
            core.addProperty("y", core.get("y").getAsInt() + 1);
            core.addProperty("height", core.get("height").getAsInt() - 1);
        }
        JsonObject rowGap = invalid;
        assertThrows(IllegalArgumentException.class, () -> ModelExecutionPlanCodec.parse(rowGap));

        invalid = corePlan.deepCopy();
        JsonObject input = windows(invalid).get(0).getAsJsonObject().getAsJsonObject("input_rect");
        input.addProperty("x", 1);
        input.addProperty("width", 1119);
        JsonObject doesNotContain = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelExecutionPlanCodec.parse(doesNotContain));

        invalid = corePlan.deepCopy();
        windows(invalid)
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("input_rect")
                .addProperty("width", 1801);
        JsonObject outsideTarget = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> ModelExecutionPlanCodec.parse(outsideTarget));

        invalid = corePlan.deepCopy();
        invalid.add("windows", new JsonArray());
        JsonObject empty = invalid;
        assertThrows(IllegalArgumentException.class, () -> ModelExecutionPlanCodec.parse(empty));

        invalid = corePlan.deepCopy();
        windows(invalid)
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("core_rect")
                .addProperty("x", Integer.MAX_VALUE);
        JsonObject overflowingEndpoint = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelExecutionPlanCodec.parse(overflowingEndpoint));

        invalid = corePlan.deepCopy();
        JsonElement first = windows(invalid).get(0).deepCopy();
        JsonElement second = windows(invalid).get(1).deepCopy();
        windows(invalid).set(0, second);
        windows(invalid).set(1, first);
        windows(invalid).get(0).getAsJsonObject().addProperty("unit_id", 0);
        windows(invalid).get(1).getAsJsonObject().addProperty("unit_id", 1);
        JsonObject noncanonicalOrder = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelExecutionPlanCodec.parse(noncanonicalOrder));

        assertThrows(
                IllegalArgumentException.class,
                () -> ModelExecutionPlanCodec.parse(tJunctionPlan(false)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelExecutionPlanCodec.parse(tJunctionPlan(true)));
    }

    @Test
    void planShapesAndSelfDigestsFailClosed() throws IOException {
        JsonObject invalid = planCase(0).getAsJsonObject("input").deepCopy();
        invalid.addProperty("preprocessing_context_digest", "6".repeat(64));
        JsonObject wrongDigest = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> ModelExecutionPlanCodec.parse(wrongDigest));

        invalid = planCase(1).getAsJsonObject("input").deepCopy();
        invalid.addProperty("type", "global");
        JsonObject unknownType = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> ModelExecutionPlanCodec.parse(unknownType));

        invalid = planCase(0).getAsJsonObject("input").deepCopy();
        invalid.addProperty("profile_digest", "7".repeat(64));
        JsonObject extraField = invalid;
        assertThrows(
                IllegalArgumentException.class, () -> ModelExecutionPlanCodec.parse(extraField));

        invalid = planCase(2).getAsJsonObject("input").deepCopy();
        for (int rowStart : new int[] {0, 2}) {
            JsonObject left =
                    windows(invalid).get(rowStart).getAsJsonObject().getAsJsonObject("core_rect");
            JsonObject right =
                    windows(invalid)
                            .get(rowStart + 1)
                            .getAsJsonObject()
                            .getAsJsonObject("core_rect");
            left.addProperty("width", 1023);
            right.addProperty("x", 1023);
            right.addProperty("width", 777);
        }
        JsonObject staleWindowDigest = invalid;
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelExecutionPlanCodec.parse(staleWindowDigest));
    }

    private static JsonObject tJunctionPlan(boolean rotated) throws IOException {
        JsonObject plan = planCase(2).getAsJsonObject("input").deepCopy();
        JsonArray replacements = new JsonArray();
        int[][] rectangles =
                rotated
                        ? new int[][] {{0, 0, 2, 1}, {0, 1, 1, 1}, {1, 1, 1, 1}}
                        : new int[][] {{0, 0, 1, 2}, {1, 0, 1, 1}, {1, 1, 1, 1}};
        for (int index = 0; index < rectangles.length; index++) {
            int[] rectangle = rectangles[index];
            JsonObject window = new JsonObject();
            window.addProperty("unit_id", index);
            window.add("core_rect", rectangle(rectangle));
            window.add("input_rect", rectangle(rectangle));
            replacements.add(window);
        }
        plan.add("windows", replacements);
        return plan;
    }

    private static JsonObject rectangle(int[] values) {
        JsonObject rectangle = new JsonObject();
        rectangle.addProperty("x", values[0]);
        rectangle.addProperty("y", values[1]);
        rectangle.addProperty("width", values[2]);
        rectangle.addProperty("height", values[3]);
        return rectangle;
    }

    private static JsonArray windows(JsonObject plan) {
        return plan.getAsJsonArray("windows");
    }

    private static void assertExact(JsonObject testCase, JsonObject value, String name) {
        byte[] encoded = CanonicalCodec.canonicalBytes(value);
        assertEquals(
                testCase.get("canonical").getAsString(),
                new String(encoded, StandardCharsets.UTF_8),
                name);
        assertEquals(testCase.get("sha256").getAsString(), CanonicalCodec.sha256(encoded), name);
    }

    private static JsonObject profileCase(int index) throws IOException {
        return fixture().getAsJsonArray("profiles").get(index).getAsJsonObject();
    }

    private static JsonObject planCase(int index) throws IOException {
        return fixture().getAsJsonArray("plans").get(index).getAsJsonObject();
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream input =
                LargeRegionExecutionCodecTest.class
                        .getClassLoader()
                        .getResourceAsStream("contracts/large-region-execution-v1.json")) {
            if (input == null) {
                throw new IOException("missing large-region execution fixture");
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
