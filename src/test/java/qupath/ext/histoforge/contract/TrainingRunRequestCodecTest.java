package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TrainingRunRequestCodecTest {

    @Test
    void roundTripsNoValidationAndRejectsUnknownFields() throws Exception {
        JsonObject fixture = fixture();
        var limits = TrainingLimitsCodec.parse(fixture.get("trainingApiLimits"));
        JsonObject request =
                fixture.getAsJsonObject("runRequest").getAsJsonObject("value").deepCopy();
        request.add("split_plan", JsonParser.parseString("{\"type\":\"none\"}"));

        var parsed = TrainingRunRequestCodec.parse(request, limits);
        assertEquals(15, parsed.minInstancePixels());
        assertArrayEquals(
                CanonicalCodec.canonicalBytes(request),
                CanonicalCodec.canonicalBytes(TrainingRunRequestCodec.toJson(parsed, limits)));

        JsonObject zero = fixture.getAsJsonObject("runRequest").getAsJsonObject("value").deepCopy();
        zero.addProperty("min_instance_pixels", 0);
        var parsedZero = TrainingRunRequestCodec.parse(zero, limits);
        assertEquals(0, parsedZero.minInstancePixels());
        assertArrayEquals(
                CanonicalCodec.canonicalBytes(zero),
                CanonicalCodec.canonicalBytes(TrainingRunRequestCodec.toJson(parsedZero, limits)));

        request.getAsJsonObject("split_plan").add("units", JsonParser.parseString("[]"));
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunRequestCodec.parse(request, limits));

        JsonObject invalid =
                fixture.getAsJsonObject("runRequest").getAsJsonObject("value").deepCopy();
        invalid.addProperty("min_instance_pixels", -1);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunRequestCodec.parse(invalid, limits));
        invalid.addProperty("min_instance_pixels", 1.5);
        assertThrows(
                IllegalArgumentException.class,
                () -> TrainingRunRequestCodec.parse(invalid, limits));
    }

    private static JsonObject fixture() throws Exception {
        try (var stream =
                        TrainingRunRequestCodecTest.class.getResourceAsStream(
                                "/contracts/training-datasets-v1.json");
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
