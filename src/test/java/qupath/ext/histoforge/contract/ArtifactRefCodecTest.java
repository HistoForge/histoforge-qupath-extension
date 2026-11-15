package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArtifactRefCodecTest {

    @Test
    void acceptsEmptyAndEncodedRepresentations() {
        var empty =
                new ArtifactRefCodec.ArtifactRef(
                        "99999999-9999-4999-8999-999999999999",
                        "application/geo+json-seq",
                        Optional.empty(),
                        0,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        var encoded =
                new ArtifactRefCodec.ArtifactRef(
                        "99999999-9999-4999-8999-999999999998",
                        "application/geo+json-seq",
                        Optional.of("gzip"),
                        20,
                        "a".repeat(64));

        assertEquals(empty, ArtifactRefCodec.parse(ArtifactRefCodec.toJson(empty)));
        assertEquals(encoded, ArtifactRefCodec.parse(ArtifactRefCodec.toJson(encoded)));
    }

    @Test
    void rejectsNoncanonicalOrUnknownFields() {
        JsonObject value = validReference();
        value.addProperty("media_type", "Application/JSON");
        assertRejected(value);

        value = validReference();
        value.addProperty("content_encoding", "GZip");
        assertRejected(value);

        value = validReference();
        value.addProperty("size_bytes", -1);
        assertRejected(value);

        value = validReference();
        value.addProperty("path", "/v1/artifacts/anything");
        assertRejected(value);

        value = validReference();
        value.add("content_encoding", null);
        assertRejected(value);
    }

    private static JsonObject validReference() {
        JsonObject value = new JsonObject();
        value.addProperty("artifact_id", "99999999-9999-4999-8999-999999999999");
        value.addProperty("media_type", "application/json");
        value.addProperty("size_bytes", 12);
        value.addProperty("sha256", "a".repeat(64));
        return value;
    }

    private static void assertRejected(JsonObject value) {
        assertThrows(IllegalArgumentException.class, () -> ArtifactRefCodec.parse(value));
    }
}
