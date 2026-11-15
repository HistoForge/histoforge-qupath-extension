package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;

/** Runs the exact schema-1 object-provenance persistence contract. */
class ProvenanceCodecTest {

    @Test
    void sharedFixtureHasExactMetadataBytesAndDigest() throws IOException {
        JsonObject fixture = fixture();
        assertEquals(ProvenanceCodec.SCHEMA_VERSION, fixture.get("schemaVersion").getAsInt());
        assertEquals(
                HistoforgePersistenceKeys.OBJECT_PROVENANCE,
                fixture.get("metadataKey").getAsString());
        assertEquals("histoforge:generation-store", HistoforgePersistenceKeys.GENERATION_STORE);
        assertEquals(
                "histoforge:action-commit-witness",
                HistoforgePersistenceKeys.ACTION_COMMIT_WITNESS);

        for (JsonElement item : fixture.getAsJsonArray("cases")) {
            JsonObject testCase = item.getAsJsonObject();
            var provenance = ProvenanceCodec.parse(testCase.get("input"));
            byte[] encoded = ProvenanceCodec.canonicalBytes(provenance);
            String canonical = testCase.get("canonical").getAsString();

            assertEquals(
                    testCase.getAsJsonObject("input").get("output_generation_id").getAsString(),
                    provenance.outputGenerationId());
            assertEquals(
                    testCase.getAsJsonObject("input").get("output"),
                    ExecutionScopeCodec.toJson(provenance.output()));
            assertEquals(canonical, new String(encoded, StandardCharsets.UTF_8));
            assertEquals(testCase.get("sha256").getAsString(), CanonicalCodec.sha256(encoded));
            assertEquals(canonical, ProvenanceCodec.encodeMetadataValue(provenance));
            assertEquals(provenance, ProvenanceCodec.decodeMetadataValue(canonical));
        }
    }

    @Test
    void invalidShapesFailClosed() throws IOException {
        JsonObject fixture = fixture();
        JsonObject base =
                fixture.getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("input");

        for (JsonElement item : fixture.getAsJsonArray("invalidMutations")) {
            JsonObject mutation = item.getAsJsonObject();
            JsonObject invalid = base.deepCopy();
            String field = mutation.get("field").getAsString();
            if (mutation.get("operation").getAsString().equals("remove")) {
                invalid.remove(field);
            } else {
                invalid.add(field, mutation.get("value").deepCopy());
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProvenanceCodec.parse(invalid),
                    mutation.get("name").getAsString());
        }
    }

    @Test
    void metadataMapDistinguishesAbsentFromPresentNullAndWritesCanonically() throws IOException {
        var testCase = fixture().getAsJsonArray("cases").get(0).getAsJsonObject();
        var provenance = ProvenanceCodec.parse(testCase.get("input"));
        String canonical = testCase.get("canonical").getAsString();
        var object = PathObjects.createDetectionObject(ROIs.createRectangleROI(0, 0, 10, 10, null));

        assertTrue(ProvenanceCodec.readMetadata(object.getMetadata()).isEmpty());
        object.getMetadata().put(HistoforgePersistenceKeys.OBJECT_PROVENANCE, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> ProvenanceCodec.readMetadata(object.getMetadata()));

        ProvenanceCodec.writeMetadata(object.getMetadata(), provenance);
        assertEquals(
                canonical, object.getMetadata().get(HistoforgePersistenceKeys.OBJECT_PROVENANCE));
        assertEquals(provenance, ProvenanceCodec.readMetadata(object.getMetadata()).orElseThrow());

        assertThrows(
                IllegalArgumentException.class,
                () -> ProvenanceCodec.decodeMetadataValue(" " + canonical));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ProvenanceCodec.decodeMetadataValue(
                                canonical.substring(0, canonical.length() - 1)
                                        + ",\"schema_version\":1}"));
    }

    @Test
    void operationReaderSharesOnlyIdenticalTextAndAlwaysReadsCurrentMetadata() throws IOException {
        var input = fixture().getAsJsonArray("cases").get(0).getAsJsonObject();
        var expected = ProvenanceCodec.parse(input.get("input"));
        String text = input.get("canonical").getAsString();
        String key = HistoforgePersistenceKeys.OBJECT_PROVENANCE;
        var metadata = new HashMap<String, String>();
        var reader = new ProvenanceCodec.MetadataReader();
        assertTrue(reader.read(metadata).isEmpty());
        metadata.put(key, text);
        var first = reader.read(metadata).orElseThrow();
        assertEquals(expected, first);
        assertSame(first, reader.read(Map.of(key, new String(text))).orElseThrow());

        for (String invalid : new String[] {null, "not-json", " " + text, text + " "}) {
            metadata.put(key, invalid);
            assertThrows(IllegalArgumentException.class, () -> reader.read(metadata));
            assertThrows(IllegalArgumentException.class, () -> reader.read(metadata));
        }
        var changed =
                new ProvenanceCodec.ObjectProvenance(
                        1, expected.output(), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        ProvenanceCodec.writeMetadata(metadata, changed);
        assertEquals(changed, reader.read(metadata).orElseThrow());
        metadata.remove(key);
        assertTrue(reader.read(metadata).isEmpty());
        metadata.put(key, text);
        assertSame(first, reader.read(metadata).orElseThrow());
        assertNotSame(first, new ProvenanceCodec.MetadataReader().read(metadata).orElseThrow());
    }

    @Test
    void operationReaderBoundsRetentionWithoutRejectingValidInput() throws IOException {
        var input = fixture().getAsJsonArray("cases").get(0).getAsJsonObject();
        var first = ProvenanceCodec.parse(input.get("input"));
        var second =
                new ProvenanceCodec.ObjectProvenance(
                        1, first.output(), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        String text = ProvenanceCodec.encodeMetadataValue(first);
        var metadata = Map.of(HistoforgePersistenceKeys.OBJECT_PROVENANCE, text);
        var other =
                Map.of(
                        HistoforgePersistenceKeys.OBJECT_PROVENANCE,
                        ProvenanceCodec.encodeMetadataValue(second));
        for (var reader :
                new ProvenanceCodec.MetadataReader[] {
                    new ProvenanceCodec.MetadataReader(1, Integer.MAX_VALUE),
                    new ProvenanceCodec.MetadataReader(10, text.length())
                }) {
            var cached = reader.read(metadata).orElseThrow();
            var uncached = reader.read(other).orElseThrow();
            assertEquals(second, uncached);
            assertNotSame(uncached, reader.read(other).orElseThrow());
            assertSame(cached, reader.read(metadata).orElseThrow());
        }
        var tooSmall = new ProvenanceCodec.MetadataReader(1, text.length() - 1);
        assertEquals(first, tooSmall.read(metadata).orElseThrow());
        assertNotSame(tooSmall.read(metadata).orElseThrow(), tooSmall.read(metadata).orElseThrow());
    }

    private static JsonObject fixture() throws IOException {
        try (InputStream stream =
                ProvenanceCodecTest.class.getResourceAsStream(
                        "/contracts/object-provenance-v1.json")) {
            if (stream == null) {
                throw new IOException("missing object provenance fixture");
            }
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
