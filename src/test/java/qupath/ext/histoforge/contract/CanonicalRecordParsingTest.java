package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalRecordParsingTest {

    @Test
    void singleParsedTreeMatchesTheExistingCanonicalEncoder() {
        for (String raw :
                List.of(
                        "null",
                        "true",
                        "[]",
                        "{}",
                        "{\"z\":1,\"a\":[0.000001,1e-7,1e20,1e21,-0,5e-324,1.7976931348623157e308]}",
                        "{\"🧪\":\"\\b\\f\\n\\r\\t\\u0000\\\\\\\"\",\"a\":\"é\"}",
                        "[9007199254740992,9007199254740993,333333333.33333329]")) {
            byte[] canonical = CanonicalCodec.canonicalBytes(raw);
            assertArrayEquals(
                    canonical,
                    CanonicalCodec.canonicalBytes(CanonicalCodec.parseCanonicalJson(canonical)));
        }
        byte[] deepest = ("[".repeat(255) + "0" + "]".repeat(255)).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(
                deepest, CanonicalCodec.canonicalBytes(CanonicalCodec.parseCanonicalJson(deepest)));
    }

    @Test
    void noncanonicalOrPermissiveSpellingsCannotSurviveExactReencoding() {
        for (String raw :
                List.of(
                        "",
                        " ",
                        "null null",
                        "{}{}",
                        "{} ",
                        "/*comment*/{}",
                        "{a:1}",
                        "{'a':1}",
                        "[1,]",
                        "[01]",
                        "[+1]",
                        "[NaN]",
                        "[Infinity]",
                        "[1e999]",
                        "[1e-999]",
                        "[9007199254740993]",
                        "[-0]",
                        "[1.0]",
                        "[1E-7]",
                        "[1e+0]",
                        "{\"x\":null,\"x\":1}",
                        "{\"x\":1,\"x\":1}",
                        "{\"outer\":{\"x\":null,\"x\":null}}",
                        "{\"x\":1,\"\\u0078\":1}",
                        "{\"b\":1,\"a\":2}",
                        "\"\\u0061\"",
                        "\"\\/\"",
                        "\"\\ud800\"",
                        "\"\\udc00\"",
                        "\"unescaped\tcontrol\"",
                        "\ufeff{}")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CanonicalCodec.parseCanonicalJson(raw.getBytes(StandardCharsets.UTF_8)),
                    raw);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CanonicalCodec.parseCanonicalJson(
                                new byte[] {'"', (byte) 0xc0, (byte) 0xaf, '"'}));
        String tooDeep = "[".repeat(256) + "0" + "]".repeat(256);
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalCodec.parseCanonicalJson(tooDeep.getBytes(StandardCharsets.UTF_8)));
    }
}
