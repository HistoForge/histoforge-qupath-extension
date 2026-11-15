package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CanonicalCodecTest {

    @Test
    void incrementalSha256HashesExactBytesWithoutFramingAndPropagatesFailures() {
        byte[] bytes = "a😀b\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                CanonicalCodec.sha256(bytes),
                CanonicalCodec.sha256Incrementally(
                        digest -> {
                            for (byte value : bytes) {
                                digest.update(value);
                            }
                        }));
        assertEquals(
                CanonicalCodec.sha256(new byte[0]),
                CanonicalCodec.sha256Incrementally(digest -> {}));
        IllegalStateException failure = new IllegalStateException("encoding failed");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                CanonicalCodec.sha256Incrementally(
                                        digest -> {
                                            digest.update(bytes);
                                            throw failure;
                                        })));
    }

    @Test
    void sharedFixturesMatchExactBytesAndDigests() throws IOException {
        JsonObject fixture = resourceJson("/contracts/canonical-json-v1.json");
        assertEquals(1, fixture.get("schemaVersion").getAsInt());
        for (var value : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = value.getAsJsonObject();
            byte[] encoded = CanonicalCodec.canonicalBytes(testCase.get("input").getAsString());
            assertEquals(
                    testCase.get("canonical").getAsString(),
                    new String(encoded, StandardCharsets.UTF_8),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    CanonicalCodec.sha256(encoded),
                    testCase.get("name").getAsString());
            assertArrayEquals(
                    encoded,
                    CanonicalCodec.canonicalBytes(
                            JsonParser.parseString(testCase.get("input").getAsString())),
                    testCase.get("name").getAsString());
        }
    }

    @Test
    void parsedTreesMatchTextEncodingAcrossNumericRepresentations() {
        List<Number> numbers =
                List.of(
                        0.0,
                        -0.0,
                        Float.valueOf(1.1f),
                        Float.MIN_VALUE,
                        Float.MAX_VALUE,
                        Double.MIN_VALUE,
                        -Double.MIN_VALUE,
                        Double.MIN_NORMAL,
                        Double.MAX_VALUE,
                        -Double.MAX_VALUE,
                        Math.nextDown(1e-6),
                        Math.nextUp(1e21),
                        1e-6,
                        1e-7,
                        1e20,
                        1e21,
                        Long.MAX_VALUE,
                        new BigInteger("9007199254740993"),
                        new BigDecimal("333333333.33333329"),
                        new BigDecimal("1e-400"),
                        new NumericToken("1.1"));
        for (Number number : numbers) {
            assertParsedEncodingMatchesText(new JsonPrimitive(number));
        }
        Random random = new Random(8785);
        for (int index = 0; index < 2048; index++) {
            double value = Double.longBitsToDouble(random.nextLong());
            if (Double.isFinite(value)) {
                assertParsedEncodingMatchesText(new JsonPrimitive(value));
            }
            float floatValue = Float.intBitsToFloat(random.nextInt());
            if (Float.isFinite(floatValue)) {
                assertParsedEncodingMatchesText(new JsonPrimitive(floatValue));
            }
        }
    }

    @Test
    void parsedTreesPreserveStringsMemberOrderAndScalarTypes() {
        StringBuilder controls = new StringBuilder();
        for (char character = 0; character < 0x20; character++) {
            controls.append(character);
        }
        JsonObject object = new JsonObject();
        object.addProperty("\ue000", "private use");
        object.addProperty("😀", "non-BMP");
        object.addProperty("A", controls + "\"\\/<>=&\u2028\u2029😀");
        JsonArray array = new JsonArray();
        array.add(JsonNull.INSTANCE);
        array.add(true);
        array.add(false);
        array.add(new JsonObject());
        array.add(new JsonArray());
        array.add(object);
        assertParsedEncodingMatchesText(array);
        assertParsedEncodingMatchesText(JsonNull.INSTANCE);
        assertParsedEncodingMatchesText(new JsonPrimitive(true));
        assertParsedEncodingMatchesText(new JsonPrimitive(false));
        assertParsedEncodingMatchesText(new JsonPrimitive(""));
    }

    @Test
    void parsedTreesRejectMalformedNumbersAndUnicode() {
        for (String token :
                List.of("NaN", "Infinity", "1e400", "01", "+1", "1.", ".1", "0x1", "1,2")) {
            JsonPrimitive value = new JsonPrimitive(new NumericToken(token));
            assertThrows(
                    IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes(value));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CanonicalCodec.canonicalBytes(value.toString()));
        }
        for (String invalid : List.of("\ud800", "\udc00", "\ud800x")) {
            JsonPrimitive value = new JsonPrimitive(invalid);
            assertThrows(
                    IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes(value));
            JsonObject object = new JsonObject();
            object.addProperty(invalid, "value");
            assertThrows(
                    IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes(object));
        }
        for (Number invalid :
                List.of(
                        Double.NaN,
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                        Float.NaN,
                        Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CanonicalCodec.canonicalBytes(new JsonPrimitive(invalid)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalCodec.canonicalBytes((JsonElement) null));
    }

    @Test
    void parsedTreesPreserveTheRawParsersNestingLimit() {
        for (boolean arrays : List.of(true, false)) {
            JsonElement value = JsonNull.INSTANCE;
            for (int depth = 1; depth <= 256; depth++) {
                if (arrays) {
                    JsonArray parent = new JsonArray();
                    parent.add(value);
                    value = parent;
                } else {
                    JsonObject parent = new JsonObject();
                    parent.add("child", value);
                    value = parent;
                }
                if (depth == 255) {
                    assertParsedEncodingMatchesText(value);
                }
            }
            JsonElement tooDeep = value;
            assertThrows(
                    IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes(tooDeep));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CanonicalCodec.canonicalBytes(tooDeep.toString()));
        }
    }

    private static void assertParsedEncodingMatchesText(JsonElement value) {
        assertArrayEquals(
                CanonicalCodec.canonicalBytes(value.toString()),
                CanonicalCodec.canonicalBytes(value));
    }

    /** Test that encoding follows the JSON token even if Number.doubleValue() differs. */
    private static final class NumericToken extends Number {

        private final String token;

        private NumericToken(String token) {
            this.token = token;
        }

        @Override
        public int intValue() {
            return 0;
        }

        @Override
        public long longValue() {
            return 0;
        }

        @Override
        public float floatValue() {
            return 0;
        }

        @Override
        public double doubleValue() {
            return 0;
        }

        @Override
        public String toString() {
            return token;
        }
    }

    @Test
    void rejectsDuplicateMembersAtAnyDepth() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalCodec.canonicalBytes("{\"a\":1,\"a\":2}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalCodec.canonicalBytes("{\"nested\":{\"a\":1,\"a\":2}}"));
    }

    @Test
    void rejectsValuesOutsideTheJcsDomain() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalCodec.canonicalBytes("{\"n\":1e400}"));
        assertThrows(
                IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes("{\"n\":NaN}"));
        assertThrows(
                IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes("{\"a\":1,}"));
        assertThrows(
                IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes("{'a':1}"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes("1 2"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes("1,2"));
        assertThrows(
                IllegalArgumentException.class, () -> CanonicalCodec.canonicalBytes("\"\\ud800\""));
    }

    private static JsonObject resourceJson(String name) throws IOException {
        try (var stream = CanonicalCodecTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("missing test resource " + name);
            }
            return CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
        }
    }
}
