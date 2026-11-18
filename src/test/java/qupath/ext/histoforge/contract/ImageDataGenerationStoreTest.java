package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Pins the exact bounded canonical String stored in the QuPath ImageData property. */
class ImageDataGenerationStoreTest {

    @Test
    void committedVersionOneFixtureRoundTripsThroughTheBoundedProperty() throws IOException {
        Fixture fixture = fixture();
        GenerationStoreCodec.GenerationStore store =
                ImageDataStateCodec.decodeGenerationStoreProperty(
                        fixture.canonical(), GenerationStoreTestData.clientBuild().limits());
        String encoded =
                ImageDataStateCodec.encodeGenerationStore(
                        store, GenerationStoreTestData.clientBuild().limits());

        assertEquals(fixture.canonical(), encoded);
        assertEquals(
                fixture.sha256(), CanonicalCodec.sha256(encoded.getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                store,
                ImageDataStateCodec.decodeGenerationStoreProperty(
                        fixture.canonical(), GenerationStoreTestData.clientBuild().limits()));
        assertEquals(1, fixture.schemaVersion());
        assertEquals(1, store.schemaVersion());
        assertEquals(
                GenerationStoreTestData.validStore().currentGenerationRecords(),
                store.currentGenerationRecords());
        assertEquals(HistoforgePersistenceKeys.GENERATION_STORE, fixture.propertyKey());
    }

    @Test
    void capturedVersionTwoRoundTripsThroughTheBoundedProperty() {
        var store = GenerationStoreTestData.capturedStore();
        String canonical =
                ImageDataStateCodec.encodeGenerationStore(
                        store, GenerationStoreTestData.clientBuild().limits());
        var restored =
                ImageDataStateCodec.decodeGenerationStoreProperty(
                        canonical, GenerationStoreTestData.clientBuild().limits());
        assertEquals(2, restored.schemaVersion());
        assertEquals(store, restored);
        assertEquals(
                canonical,
                ImageDataStateCodec.encodeGenerationStore(
                        restored, GenerationStoreTestData.clientBuild().limits()));
    }

    @Test
    void propertyTypeCanonicalityAndExactUtf8CapFailClosed() {
        GenerationStoreCodec.GenerationStore store = GenerationStoreTestData.validStore();
        String canonical =
                ImageDataStateCodec.encodeGenerationStore(
                        store, GenerationStoreTestData.clientBuild().limits());
        long bytes = canonical.getBytes(StandardCharsets.UTF_8).length;

        assertEquals(
                canonical,
                ImageDataStateCodec.encodeGenerationStore(
                        store, GenerationStoreTestData.limitsWithStoreCap(bytes)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ImageDataStateCodec.encodeGenerationStore(
                                store, GenerationStoreTestData.limitsWithStoreCap(bytes - 1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ImageDataStateCodec.decodeGenerationStoreProperty(
                                new Object(), GenerationStoreTestData.limitsWithStoreCap(bytes)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ImageDataStateCodec.decodeGenerationStoreProperty(
                                " " + canonical, GenerationStoreTestData.clientBuild().limits()));
    }

    private static Fixture fixture() throws IOException {
        try (var stream =
                ImageDataGenerationStoreTest.class.getResourceAsStream(
                        "/contracts/generation-store-v1.json")) {
            if (stream == null) {
                throw new IOException("missing generation-store-v1.json");
            }
            JsonObject value = CanonicalCodec.parseJson(stream.readAllBytes()).getAsJsonObject();
            return new Fixture(
                    value.get("schemaVersion").getAsInt(),
                    value.get("propertyKey").getAsString(),
                    value.get("canonical").getAsString(),
                    value.get("sha256").getAsString());
        }
    }

    private record Fixture(
            int schemaVersion, String propertyKey, String canonical, String sha256) {}
}
