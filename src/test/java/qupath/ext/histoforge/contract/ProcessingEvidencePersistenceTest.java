package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.projects.Projects;

class ProcessingEvidencePersistenceTest {

    @TempDir Path temporaryDirectory;

    @Test
    void oldAndNewDurableRecordsKeepExactCanonicalBytesAcrossRealSaveReopen() throws Exception {
        var project =
                Projects.createProject(
                        Files.createDirectory(temporaryDirectory.resolve("project")).toFile(),
                        BufferedImage.class);
        Path source = temporaryDirectory.resolve("source.png");
        assertTrue(
                ImageIO.write(
                        new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB),
                        "PNG",
                        source.toFile()));
        var variants =
                List.of(
                        Optional.<ProcessingEvidenceCodec.ProcessingEvidence>empty(),
                        Optional.of(new ProcessingEvidenceCodec.FiniteProcessing("a".repeat(64))),
                        Optional.of(
                                new ProcessingEvidenceCodec.GlobalDecodeProcessing(
                                        "b".repeat(64), "c".repeat(64))),
                        Optional.of(
                                new ProcessingEvidenceCodec.CoreHaloProcessing(
                                        "d".repeat(64), "e".repeat(64), 1024, 768, 0)));
        try (var server = ImageServers.buildServer(source.toUri())) {
            var entry = project.addImage(server.getBuilder());
            for (var processing : variants) {
                var json = GenerationStoreCodec.toJson(GenerationStoreTestData.validStore());
                var record =
                        json.getAsJsonObject("current_generation_records")
                                .getAsJsonObject(GenerationStoreTestData.GENERATION_ID);
                record.add(
                        "execution",
                        BackendExecutionCodec.toJson(
                                new BackendExecutionCodec.DurableRun(
                                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "f".repeat(64))));
                processing.ifPresent(
                        value ->
                                record.add(
                                        "processing_evidence",
                                        ProcessingEvidenceCodec.toJson(value)));
                String originalBytes =
                        new String(
                                CanonicalCodec.canonicalBytes(json),
                                java.nio.charset.StandardCharsets.UTF_8);
                var store = GenerationStoreCodec.parseGenerationStore(json);
                assertEquals(originalBytes, ImageDataStateCodec.encodeGenerationStore(store));
                assertEquals(
                        processing,
                        store.currentGenerationRecords()
                                .get(GenerationStoreTestData.GENERATION_ID)
                                .processingEvidence());
                assertEquals(processing.isPresent(), record.has("processing_evidence"));
                for (int cycle = 0; cycle < 2; cycle++) {
                    try (var imageData = entry.readImageData()) {
                        if (cycle == 0) ImageDataStateCodec.writeGenerationStore(imageData, store);
                        assertEquals(
                                originalBytes,
                                imageData.getProperty(HistoforgePersistenceKeys.GENERATION_STORE));
                        assertEquals(
                                store,
                                ImageDataStateCodec.readGenerationStore(imageData).orElseThrow());
                        entry.saveImageData(imageData);
                    }
                }
                try (var reopened = entry.readImageData()) {
                    assertEquals(
                            originalBytes,
                            reopened.getProperty(HistoforgePersistenceKeys.GENERATION_STORE));
                    assertEquals(
                            store, ImageDataStateCodec.readGenerationStore(reopened).orElseThrow());
                }
            }
        }
    }

    @Test
    void finiteCallCannotClaimDurableProcessingAndEmptyResultStillHasNoRecord() {
        var record = GenerationEvidenceCodec.toJson(GenerationStoreTestData.generationRecord());
        assertFalse(record.has("processing_evidence"));
        var processing =
                new ProcessingEvidenceCodec.CoreHaloProcessing(
                        "a".repeat(64), "b".repeat(64), 1024, 768, 96);
        record.add("processing_evidence", ProcessingEvidenceCodec.toJson(processing));
        assertThrows(
                IllegalArgumentException.class,
                () -> GenerationEvidenceCodec.parseGenerationRecord(record));
        record.add(
                "execution",
                BackendExecutionCodec.toJson(
                        new BackendExecutionCodec.DurableRun(
                                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "f".repeat(64))));
        var counts = record.getAsJsonObject("counts");
        for (var name : List.copyOf(counts.keySet())) counts.addProperty(name, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> GenerationEvidenceCodec.parseGenerationRecord(record));
    }
}
