package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Runs the shared schema-1 gateway error, HTTP status, and retry contract. */
class GatewayErrorCodecTest {

    @Test
    void sharedValidErrorsMatchExactBytesDigestsAndRoundTrip() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());

        for (JsonElement item : fixture.getAsJsonArray("validCases")) {
            JsonObject testCase = item.getAsJsonObject();
            GatewayErrorCodec.Error error = GatewayErrorCodec.parse(testCase.get("value"));
            JsonObject value = GatewayErrorCodec.toJson(error);
            byte[] encoded = CanonicalCodec.canonicalBytes(value);

            assertEquals(
                    testCase.get("canonical").getAsString(),
                    new String(encoded, StandardCharsets.UTF_8),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    CanonicalCodec.sha256(encoded),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("httpStatus").getAsInt(),
                    GatewayErrorCodec.httpStatus(error.code()),
                    testCase.get("name").getAsString());
            assertEquals(error, GatewayErrorCodec.parse(value), testCase.get("name").getAsString());
        }
    }

    @Test
    void sharedHttpMappingIsExhaustive() throws IOException {
        Set<GatewayErrorCodec.Code> actual = EnumSet.noneOf(GatewayErrorCodec.Code.class);
        for (JsonElement item : fixture().getAsJsonArray("httpMappings")) {
            JsonObject mapping = item.getAsJsonObject();
            int status = mapping.get("httpStatus").getAsInt();
            for (JsonElement codeValue : mapping.getAsJsonArray("codes")) {
                GatewayErrorCodec.Code code =
                        GatewayErrorCodec.Code.valueOf(codeValue.getAsString());
                assertEquals(status, GatewayErrorCodec.httpStatus(code));
                actual.add(code);
            }
        }
        assertEquals(EnumSet.allOf(GatewayErrorCodec.Code.class), actual);
    }

    @Test
    void sharedAutomaticRetryPolicyIsExact() throws IOException {
        Set<GatewayErrorCodec.Code> expected = new HashSet<>();
        for (JsonElement item : fixture().getAsJsonArray("automaticRetryCodes")) {
            expected.add(GatewayErrorCodec.Code.valueOf(item.getAsString()));
        }
        Set<GatewayErrorCodec.Code> actual = EnumSet.noneOf(GatewayErrorCodec.Code.class);
        for (GatewayErrorCodec.Code code : GatewayErrorCodec.Code.values()) {
            if (GatewayErrorCodec.isAutomaticallyRetryable(code)) {
                actual.add(code);
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void sharedInvalidErrorsFailClosed() throws IOException {
        for (JsonElement item : fixture().getAsJsonArray("invalidCases")) {
            JsonObject testCase = item.getAsJsonObject();
            assertThrows(
                    RuntimeException.class,
                    () -> GatewayErrorCodec.parse(testCase.get("value")),
                    testCase.get("name").getAsString());
        }
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                GatewayErrorCodecTest.class.getResourceAsStream(
                        "/contracts/gateway-errors-v1.json")) {
            if (stream == null) {
                throw new IOException("missing gateway-error fixture");
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
