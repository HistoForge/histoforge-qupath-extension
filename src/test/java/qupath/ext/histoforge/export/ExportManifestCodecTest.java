package qupath.ext.histoforge.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.GenerationStoreTestData;
import qupath.ext.histoforge.export.ExportTypes.ExportImageEvidence;
import qupath.ext.histoforge.export.ExportTypes.ExportManifest;
import qupath.ext.histoforge.export.ExportTypes.ExportModuleEvidence;
import qupath.ext.histoforge.export.ExportTypes.ProvenanceAuditSummary;

/** Golden canonical identity and strict self-digest validation for export evidence. */
class ExportManifestCodecTest {

    private static final String FIXTURE = "/contracts/export-manifest-v1.json";

    @Test
    void versionOneGoldenManifestRoundTripsThroughExactCanonicalBytes() throws Exception {
        Fixture fixture = fixture();
        ExportManifest parsed =
                ExportManifestCodec.parse(CanonicalCodec.parseJson(fixture.canonical()));
        byte[] encoded = ExportManifestCodec.canonicalBytes(parsed);

        assertEquals(fixture.schemaVersion(), parsed.schemaVersion());
        assertEquals(fixture.canonical(), new String(encoded, StandardCharsets.UTF_8));
        assertEquals(fixture.sha256(), CanonicalCodec.sha256(encoded));
        assertArrayEquals(encoded, ExportManifestCodec.canonicalBytes(parsed));
        assertEquals(1, parsed.schemaVersion());
        assertEquals(3, ExportTestData.manifest().schemaVersion());
    }

    @Test
    void previousAndCurrentEvidenceVersionsRoundTripWithoutRecomputingInputDigests()
            throws Exception {
        var historical = ExportManifestCodec.parse(CanonicalCodec.parseJson(fixture().canonical()));
        for (var manifest : List.of(withVersion(2, historical), ExportTestData.manifest())) {
            byte[] bytes = ExportManifestCodec.canonicalBytes(manifest);
            var parsed = ExportManifestCodec.parse(CanonicalCodec.parseJson(bytes));
            assertEquals(manifest, parsed);
            assertArrayEquals(bytes, ExportManifestCodec.canonicalBytes(parsed));
        }
    }

    @Test
    void rejectsGenerationEvidenceWhoseSelfDigestWasChanged() throws Exception {
        JsonObject value = CanonicalCodec.parseJson(fixture().canonical()).getAsJsonObject();
        value.getAsJsonArray("images")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("modules")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("generation_evidence")
                .addProperty("evidence_digest", "9".repeat(64));

        assertThrows(IllegalArgumentException.class, () -> ExportManifestCodec.parse(value));
    }

    @Test
    void nonemptyGenerationEvidenceRoundTripsThroughTheManifest() throws Exception {
        GenerationStoreCodec.GenerationStore store = generationStore();
        String missingGenerationId = "55555555-5555-4555-8555-555555555555";
        ExportManifest manifest = withGenerationEvidence(store, Set.of(missingGenerationId));

        byte[] encoded = ExportManifestCodec.canonicalBytes(manifest);
        ExportManifest parsed = ExportManifestCodec.parse(CanonicalCodec.parseJson(encoded));
        var parsedGeneration = parsed.images().getFirst().modules().getFirst().generationEvidence();

        assertArrayEquals(encoded, ExportManifestCodec.canonicalBytes(parsed));
        assertEquals(store.sourceRevisions(), parsedGeneration.sourceRevisions());
        assertEquals(store.targetRegions(), parsedGeneration.targetRegions());
        assertEquals(store.clientBuilds(), parsedGeneration.clientBuilds());
        assertEquals(store.backendIdentities(), parsedGeneration.backendIdentities());
        assertEquals(
                List.copyOf(store.currentGenerationRecords().values()),
                parsedGeneration.linkedGenerationRecords());
        assertEquals(Set.of(missingGenerationId), parsedGeneration.missingGenerationRecordIds());

        var legacy = withVersion(1, manifest);
        byte[] legacyBytes = ExportManifestCodec.canonicalBytes(legacy);
        var legacyParsed = ExportManifestCodec.parse(CanonicalCodec.parseJson(legacyBytes));
        assertEquals(1, legacyParsed.schemaVersion());
        assertArrayEquals(legacyBytes, ExportManifestCodec.canonicalBytes(legacyParsed));
        assertEquals(
                parsedGeneration,
                legacyParsed.images().getFirst().modules().getFirst().generationEvidence());
    }

    @Test
    void capturedAndMixedExportEvidenceRequireVersionTwo() throws Exception {
        var captured = GenerationStoreTestData.capturedStore();
        var mixed = GenerationStoreCodec.merge(List.of(generationStore(), captured));
        for (var store : List.of(captured, mixed)) {
            var manifest = withGenerationEvidence(store, Set.of());
            byte[] canonical = ExportManifestCodec.canonicalBytes(manifest);
            var parsed = ExportManifestCodec.parse(CanonicalCodec.parseJson(canonical));
            assertEquals(3, parsed.schemaVersion());
            assertEquals(
                    2,
                    ExportManifestCodec.parse(ExportManifestCodec.toJson(withVersion(2, manifest)))
                            .schemaVersion());
            assertArrayEquals(canonical, ExportManifestCodec.canonicalBytes(parsed));
            assertEquals(
                    manifest.images().getFirst().modules().getFirst().generationEvidence(),
                    parsed.images().getFirst().modules().getFirst().generationEvidence());
            assertThrows(IllegalArgumentException.class, () -> withVersion(1, manifest));
            var mislabeled = ExportManifestCodec.toJson(manifest);
            mislabeled.addProperty("schema_version", 1);
            assertThrows(
                    IllegalArgumentException.class, () -> ExportManifestCodec.parse(mislabeled));
        }
    }

    @Test
    void unsupportedManifestVersionsFailClosed() {
        var manifest = ExportTestData.manifest();
        for (int version : new int[] {0, 4}) {
            assertThrows(IllegalArgumentException.class, () -> withVersion(version, manifest));
            var value = ExportManifestCodec.toJson(manifest);
            value.addProperty("schema_version", version);
            assertThrows(IllegalArgumentException.class, () -> ExportManifestCodec.parse(value));
        }
    }

    @Test
    void rejectsUnknownManifestFields() throws Exception {
        JsonObject value = CanonicalCodec.parseJson(fixture().canonical()).getAsJsonObject();
        value.addProperty("extra", true);

        assertThrows(IllegalArgumentException.class, () -> ExportManifestCodec.parse(value));
    }

    private static ExportManifest withVersion(int version, ExportManifest manifest) {
        return new ExportManifest(
                version,
                manifest.exportId(),
                manifest.createdAt(),
                manifest.clientBuild(),
                manifest.claim(),
                manifest.images());
    }

    private static ExportManifest withGenerationEvidence(
            GenerationStoreCodec.GenerationStore store, Set<String> missing) {
        var generation = ExportManifestCodec.generationEvidence(store, missing);
        ExportManifest base = ExportTestData.manifest();
        ExportImageEvidence baseImage = base.images().getFirst();
        ExportModuleEvidence baseModule = baseImage.modules().getFirst();
        ExportModuleEvidence module =
                new ExportModuleEvidence(
                        baseModule.metricInputDigest(),
                        baseModule.module(),
                        baseModule.metricsScriptDigest(),
                        baseModule.regions(),
                        new ProvenanceAuditSummary(2, 1),
                        generation);
        return new ExportManifest(
                base.schemaVersion(),
                base.exportId(),
                base.createdAt(),
                base.clientBuild(),
                base.claim(),
                List.of(
                        new ExportImageEvidence(
                                baseImage.capturedAt(),
                                baseImage.materialization(),
                                baseImage.sourceRevision(),
                                baseImage.studyFields(),
                                List.of(module))));
    }

    private static Fixture fixture() throws IOException {
        try (var stream = ExportManifestCodecTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IOException("missing fixture " + FIXTURE);
            }
            return new Gson()
                    .fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), Fixture.class);
        }
    }

    private static GenerationStoreCodec.GenerationStore generationStore() throws IOException {
        try (var stream =
                ExportManifestCodecTest.class.getResourceAsStream(
                        "/contracts/generation-store-v1.json")) {
            if (stream == null) {
                throw new IOException("missing generation-store-v1.json");
            }
            Fixture fixture =
                    new Gson()
                            .fromJson(
                                    new InputStreamReader(stream, StandardCharsets.UTF_8),
                                    Fixture.class);
            return GenerationStoreCodec.parseGenerationStore(
                    CanonicalCodec.parseJson(fixture.canonical()));
        }
    }

    private record Fixture(int schemaVersion, String canonical, String sha256) {}
}
