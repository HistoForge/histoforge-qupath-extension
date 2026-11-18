package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;

/** Pins normalized generation evidence, contextual membership, and immutability. */
class GenerationStoreCodecTest {

    @Test
    void precisionSpecificBuildChangesProvenanceIdentityWithoutChangingHistoricalEncoding() {
        var original = GenerationStoreTestData.validStore();
        var historical = GenerationStoreCodec.toJson(original);
        var selected = historical.deepCopy();
        var identities = selected.getAsJsonObject("backend_identities");
        String oldDigest = identities.keySet().iterator().next();
        var backend = identities.remove(oldDigest).getAsJsonObject();
        backend.getAsJsonObject("runner_build").addProperty("manifest_digest", "f".repeat(64));
        String selectedDigest =
                InferenceIdentityCodec.backendModelIdentityDigest(
                        InferenceIdentityCodec.parseBackendModelIdentity(backend));
        assertNotEquals(oldDigest, selectedDigest);
        identities.add(selectedDigest, backend);
        selected.getAsJsonObject("current_generation_records")
                .entrySet()
                .forEach(
                        entry ->
                                entry.getValue()
                                        .getAsJsonObject()
                                        .addProperty("backend_identity_digest", selectedDigest));
        var parsed = GenerationStoreCodec.parseGenerationStore(selected);
        assertEquals(selected, GenerationStoreCodec.toJson(parsed));
        assertEquals(
                historical,
                GenerationStoreCodec.toJson(GenerationStoreCodec.parseGenerationStore(historical)));
        assertEquals(original, GenerationStoreCodec.parseGenerationStore(historical));
    }

    @Test
    void normalizedStoreRoundTripsCanonically() {
        GenerationStoreCodec.GenerationStore store = GenerationStoreTestData.validStore();
        byte[] canonical = GenerationStoreCodec.canonicalBytes(store);

        assertEquals(2, store.schemaVersion());
        assertEquals(
                store,
                GenerationStoreCodec.parseGenerationStore(CanonicalCodec.parseJson(canonical)));
        assertEquals(
                new String(canonical, java.nio.charset.StandardCharsets.UTF_8),
                new String(
                        CanonicalCodec.canonicalBytes(GenerationStoreCodec.toJson(store)),
                        StandardCharsets.UTF_8));
    }

    @Test
    void capturedAndMixedStoresRequireVersionTwoAndRetainExactBindings() {
        var captured = GenerationStoreTestData.capturedStore();
        var sealed = withVersion(1, GenerationStoreTestData.validStore());
        var mixed = GenerationStoreCodec.merge(List.of(sealed, captured));

        assertEquals(2, mixed.schemaVersion());
        assertEquals(2, mixed.currentGenerationRecords().size());
        assertEquals(
                mixed,
                GenerationStoreCodec.parseGenerationStore(GenerationStoreCodec.toJson(mixed)));
        var sealedRecord =
                mixed.currentGenerationRecords().get(GenerationStoreTestData.GENERATION_ID);
        assertEquals(
                sealed.currentGenerationRecords().get(GenerationStoreTestData.GENERATION_ID),
                sealedRecord);
        assertFalse(
                GenerationEvidenceCodec.toJson(sealedRecord.inputEvidence())
                        .has("source_bindings"));
        assertEquals(
                captured.currentGenerationRecords()
                        .get(GenerationStoreTestData.SECOND_GENERATION_ID),
                mixed.currentGenerationRecords().get(GenerationStoreTestData.SECOND_GENERATION_ID));

        assertThrows(IllegalArgumentException.class, () -> withVersion(1, captured));
        assertThrows(IllegalArgumentException.class, () -> withVersion(1, mixed));
        var mislabeled = GenerationStoreCodec.toJson(mixed);
        mislabeled.addProperty("schema_version", 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> GenerationStoreCodec.parseGenerationStore(mislabeled));
    }

    @Test
    void newAndMutatedStoresUseCurrentVersionWithoutChangingRetainedRecords() {
        var legacy = withVersion(1, GenerationStoreTestData.validStore());
        var merged = GenerationStoreCodec.merge(List.of(legacy));
        assertEquals(2, GenerationStoreCodec.emptyStore().schemaVersion());
        assertEquals(2, merged.schemaVersion());
        assertEquals(legacy.currentGenerationRecords(), merged.currentGenerationRecords());
        var record = legacy.currentGenerationRecords().values().iterator().next();
        var swept =
                GenerationStoreCodec.markAndSweep(
                                legacy,
                                List.of(
                                        GenerationStoreTestData.provenance(
                                                record.output(), record.outputGenerationId())))
                        .store();
        assertEquals(2, swept.schemaVersion());
        assertEquals(legacy.currentGenerationRecords(), swept.currentGenerationRecords());
        assertEquals(1, legacy.schemaVersion());
    }

    @Test
    void unsupportedStoreVersionsFailClosed() {
        for (int version : new int[] {0, 3}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> withVersion(version, GenerationStoreTestData.validStore()));
        }
    }

    @Test
    void normalizedKeysReferencesAndObjectOwnershipFailClosed() {
        GenerationStoreCodec.GenerationStore store = GenerationStoreTestData.validStore();
        GenerationEvidenceCodec.GenerationRecord record =
                GenerationStoreTestData.generationRecord();

        Map<String, RasterInputCodec.ActionSourceRevision> wrongSourceKey =
                new LinkedHashMap<>(store.sourceRevisions());
        RasterInputCodec.ActionSourceRevision source = wrongSourceKey.values().iterator().next();
        wrongSourceKey.clear();
        wrongSourceKey.put(GenerationStoreTestData.hex('f'), source);
        assertInvalidStore(
                wrongSourceKey,
                store.targetRegions(),
                store.clientBuilds(),
                store.backendIdentities(),
                store.currentGenerationRecords());

        GenerationEvidenceCodec.CommittedInputEvidence missingSource =
                GenerationStoreTestData.copyEvidence(
                        record.inputEvidence(),
                        GenerationStoreTestData.hex('f'),
                        record.inputEvidence().targetRegionDigest(),
                        Optional.empty(),
                        record.inputEvidence().sourceView(),
                        record.inputEvidence().rasterTransform());
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        record.output(),
                        missingSource));

        GenerationEvidenceCodec.CommittedInputEvidence missingTarget =
                GenerationStoreTestData.copyEvidence(
                        record.inputEvidence(),
                        record.inputEvidence().sourceRevisionDigest(),
                        GenerationStoreTestData.hex('f'),
                        Optional.empty(),
                        record.inputEvidence().sourceView(),
                        record.inputEvidence().rasterTransform());
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        record.output(),
                        missingTarget));

        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecordWithIdentityDigests(
                        record, GenerationStoreTestData.hex('f'), record.backendIdentityDigest()));
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecordWithIdentityDigests(
                        record, record.clientBuildDigest(), GenerationStoreTestData.hex('f')));

        ExecutionScopeCodec.OutputKey wrongScope =
                new ExecutionScopeCodec.OutputKey(
                        record.output().producer(),
                        new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        wrongScope,
                        record.inputEvidence()));

        ExecutionScopeCodec.OutputKey wrongProducer =
                new ExecutionScopeCodec.OutputKey(
                        new InferenceIdentityCodec.ModelStepIdentity(
                                record.output().producer().moduleRef(), "another_model"),
                        record.output().scope());
        assertInvalidRecord(
                store,
                GenerationStoreTestData.copyRecord(
                        record,
                        GenerationStoreTestData.GENERATION_ID,
                        wrongProducer,
                        record.inputEvidence()));

        assertInvalidStore(
                store.sourceRevisions(),
                store.targetRegions(),
                store.clientBuilds(),
                store.backendIdentities(),
                Map.of(GenerationStoreTestData.SECOND_GENERATION_ID, record));

        RasterInputCodec.ActionSourceRevision original =
                store.sourceRevisions().values().iterator().next();
        RasterInputCodec.ActionSourceRevision unreferenced =
                new RasterInputCodec.ActionSourceRevision(
                        new RasterInputCodec.ImageRevisionToken(
                                original.imageDataRevision().imageId(),
                                original.imageDataRevision().imageSourceDigest(),
                                GenerationStoreTestData.hex('f')),
                        original.pixelSource());
        Map<String, RasterInputCodec.ActionSourceRevision> extraSources =
                new LinkedHashMap<>(store.sourceRevisions());
        extraSources.put(RasterInputCodec.actionSourceRevisionDigest(unreferenced), unreferenced);
        assertInvalidStore(
                extraSources,
                store.targetRegions(),
                store.clientBuilds(),
                store.backendIdentities(),
                store.currentGenerationRecords());
    }

    @Test
    void mapsAndTargetGeometryAreDetachedAndImmutable() {
        GenerationStoreCodec.GenerationStore store = GenerationStoreTestData.validStore();
        assertThrows(
                UnsupportedOperationException.class,
                () -> store.currentGenerationRecords().clear());

        ExecutionScopeCodec.TargetRegion target = store.targetRegions().values().iterator().next();
        Geometry detached = target.geometrySlide();
        detached.apply((CoordinateFilter) coordinate -> coordinate.x = 999);

        assertEquals(
                target.geometryDigest(),
                CanonicalGeometryCodec.canonicalDigest(target.geometrySlide()));
        assertNotEquals(999, target.geometrySlide().getCoordinate().getX());
    }

    private static void assertInvalidRecord(
            GenerationStoreCodec.GenerationStore store,
            GenerationEvidenceCodec.GenerationRecord record) {
        assertInvalidStore(
                store.sourceRevisions(),
                store.targetRegions(),
                store.clientBuilds(),
                store.backendIdentities(),
                Map.of(record.outputGenerationId(), record));
    }

    private static GenerationStoreCodec.GenerationStore withVersion(
            int version, GenerationStoreCodec.GenerationStore store) {
        return new GenerationStoreCodec.GenerationStore(
                version,
                store.sourceRevisions(),
                store.targetRegions(),
                store.clientBuilds(),
                store.backendIdentities(),
                store.currentGenerationRecords());
    }

    private static void assertInvalidStore(
            Map<String, RasterInputCodec.ActionSourceRevision> sources,
            Map<String, ExecutionScopeCodec.TargetRegion> targets,
            Map<String, BuildIdentityCodec.ClientBuildIdentity> clients,
            Map<String, InferenceIdentityCodec.BackendModelIdentity> backends,
            Map<String, GenerationEvidenceCodec.GenerationRecord> records) {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GenerationStoreCodec.GenerationStore(
                                1, sources, targets, clients, backends, records));
    }
}
