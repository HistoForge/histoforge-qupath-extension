package qupath.ext.histoforge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static qupath.ext.histoforge.spatial.OriginalSourceTestData.readerSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.OriginalWsi;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactStartRequest;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceContainer;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceLevelRequirement;
import qupath.ext.histoforge.contract.SourceIdentityCodec;

class SourcePublicationQualificationTest {

    @TempDir Path temporary;

    @Test
    void failedConnectionWritesCanonicalPrivacySafeReceipt() throws Exception {
        Path credential = temporary.resolve("private-credential.json");
        Files.writeString(credential, "{}");
        Path source = temporary.resolve("patient-slide-name.tif");
        Files.write(source, new byte[] {1, 2, 3, 4});
        Files.setLastModifiedTime(source, FileTime.fromMillis(1234));
        SourceArtifactStartRequest start = startFor(source);
        Path startFile = temporary.resolve("private-start.json");
        Files.write(startFile, CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(start)));
        Path output = temporary.resolve("evidence.json");

        assertThrows(
                IllegalStateException.class,
                () ->
                        SourcePublicationQualification.main(
                                new String[] {
                                    credential.toString(),
                                    source.toString(),
                                    startFile.toString(),
                                    CanonicalCodec.sha256(new byte[] {1, 2, 3, 4}),
                                    output.toString()
                                }));

        byte[] bytes = Files.readAllBytes(output);
        var evidence = CanonicalCodec.parseJson(bytes).getAsJsonObject();
        String claimed = evidence.remove("evidence_digest").getAsString();
        assertEquals(CanonicalCodec.canonicalDigest(evidence), claimed);
        assertTrue(
                evidence.has("qualification_probe"),
                () ->
                        evidence.has("failure_message")
                                ? evidence.get("failure_message").getAsString()
                                : evidence.toString());
        var probe = evidence.getAsJsonObject("qualification_probe");
        assertEquals(
                CanonicalCodec.canonicalDigest(probe.getAsJsonObject("input_manifest")),
                probe.get("input_manifest_digest").getAsString());
        assertEquals(
                CanonicalCodec.canonicalDigest(probe.getAsJsonObject("compiled_class_file_set")),
                probe.get("compiled_class_file_set_digest").getAsString());
        assertFalse(evidence.get("success").getAsBoolean());
        assertFalse(evidence.get("source_released").getAsBoolean());
        String encoded = new String(bytes);
        assertFalse(encoded.contains(source.toString()));
        assertFalse(encoded.contains(source.getFileName().toString()));
        assertFalse(encoded.contains(credential.toString()));
        assertFalse(encoded.contains(startFile.toString()));
    }

    private static SourceArtifactStartRequest startFor(Path source) throws Exception {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "qupath.openslide.local",
                        "0".repeat(64),
                        Optional.of(
                                new SourceIdentityCodec.LocalFile(
                                        Files.size(source),
                                        Files.getLastModifiedTime(source).toMillis(),
                                        Optional.empty())),
                        2,
                        2,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.22, 0.22));
        return new SourceArtifactStartRequest(
                SourceArtifactCodec.SCHEMA_VERSION,
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                SourceContainer.SLIDE_FILE,
                Files.size(source),
                4,
                new OriginalWsi(
                        identity,
                        0,
                        new SourceIdentityCodec.ScaleTranslate2D(1.0, 1.0, 0.0, 0.0),
                        source.getFileName().toString(),
                        readerSettings(identity, 1),
                        List.of()),
                List.of(new SourceLevelRequirement(0, 0, 2, 2)));
    }
}
