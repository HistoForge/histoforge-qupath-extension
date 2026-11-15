package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Runs the shared schema-1 action commit witness contract. */
class ImageDataStateCodecTest {

    @Test
    void sharedWitnessFixtureHasExactPropertyBytesAndDigest() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(1, fixture.get("schemaVersion").getAsInt());
        assertEquals(
                HistoforgePersistenceKeys.ACTION_COMMIT_WITNESS,
                fixture.get("propertyKey").getAsString());

        for (JsonElement item : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = item.getAsJsonObject();
            var witness = ImageDataStateCodec.parseActionCommitWitness(testCase.get("input"));
            byte[] encoded = ImageDataStateCodec.canonicalBytes(witness);

            assertEquals(
                    testCase.getAsJsonObject("input").get("action_id").getAsString(),
                    witness.actionId());
            assertEquals(
                    testCase.getAsJsonObject("input").get("handle_generation_id").getAsString(),
                    witness.handleGenerationId());
            assertEquals(
                    testCase.get("canonical").getAsString(),
                    new String(encoded, StandardCharsets.UTF_8),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("sha256").getAsString(),
                    CanonicalCodec.sha256(encoded),
                    testCase.get("name").getAsString());
            assertEquals(
                    testCase.get("canonical").getAsString(),
                    ImageDataStateCodec.encodeActionCommitWitness(witness));
            assertEquals(
                    witness,
                    ImageDataStateCodec.decodeActionCommitWitnessProperty(
                            testCase.get("canonical").getAsString()));
        }
    }

    @Test
    void sharedInvalidWitnessMutationsFailClosed() throws IOException {
        JsonObject fixture = fixture();
        JsonObject base =
                fixture.getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("input");

        for (JsonElement item : fixture.getAsJsonArray("invalidMutations")) {
            JsonObject mutation = item.getAsJsonObject();
            JsonObject invalid = base.deepCopy();
            String field = mutation.getAsJsonArray("path").get(0).getAsString();
            if (mutation.get("operation").getAsString().equals("remove")) {
                invalid.remove(field);
            } else {
                invalid.add(field, mutation.get("value").deepCopy());
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ImageDataStateCodec.parseActionCommitWitness(invalid),
                    mutation.get("name").getAsString());
        }
    }

    @Test
    void propertyLoadRequiresACanonicalString() throws IOException {
        String canonical =
                fixture()
                        .getAsJsonArray("cases")
                        .get(0)
                        .getAsJsonObject()
                        .get("canonical")
                        .getAsString();

        assertThrows(
                IllegalArgumentException.class,
                () -> ImageDataStateCodec.decodeActionCommitWitnessProperty(new Object()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ImageDataStateCodec.decodeActionCommitWitnessProperty(" " + canonical));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ImageDataStateCodec.decodeActionCommitWitnessProperty(
                                canonical.substring(0, canonical.length() - 1)
                                        + ",\"schema_version\":1}"));
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                ImageDataStateCodecTest.class.getResourceAsStream(
                        "/contracts/action-commit-witness-v1.json")) {
            if (stream == null) {
                throw new IOException("missing action commit witness fixture");
            }
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
