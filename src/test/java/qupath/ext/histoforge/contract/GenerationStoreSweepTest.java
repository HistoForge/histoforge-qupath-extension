package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pins mark-and-sweep against the complete current attributed-object population. */
class GenerationStoreSweepTest {

    @Test
    void sweepKeepsOnlyReferencedClosureAndReportsMissingIds() {
        GenerationStoreCodec.GenerationStore store = GenerationStoreTestData.validStore();
        GenerationEvidenceCodec.GenerationRecord first = GenerationStoreTestData.generationRecord();
        GenerationEvidenceCodec.GenerationRecord second =
                GenerationStoreTestData.copyRecord(
                        first,
                        GenerationStoreTestData.SECOND_GENERATION_ID,
                        first.output(),
                        first.inputEvidence());
        Map<String, GenerationEvidenceCodec.GenerationRecord> records =
                new LinkedHashMap<>(store.currentGenerationRecords());
        records.put(second.outputGenerationId(), second);
        GenerationStoreCodec.GenerationStore twoRecords =
                new GenerationStoreCodec.GenerationStore(
                        1,
                        store.sourceRevisions(),
                        store.targetRegions(),
                        store.clientBuilds(),
                        store.backendIdentities(),
                        records);

        GenerationStoreCodec.SweepResult result =
                GenerationStoreCodec.markAndSweep(
                        twoRecords,
                        List.of(
                                GenerationStoreTestData.provenance(
                                        first.output(), GenerationStoreTestData.GENERATION_ID),
                                GenerationStoreTestData.provenance(
                                        first.output(),
                                        GenerationStoreTestData.MISSING_GENERATION_ID)));

        assertEquals(
                Set.of(GenerationStoreTestData.GENERATION_ID),
                result.store().currentGenerationRecords().keySet());
        assertEquals(1, result.store().sourceRevisions().size());
        assertEquals(1, result.store().targetRegions().size());
        assertEquals(1, result.store().clientBuilds().size());
        assertEquals(1, result.store().backendIdentities().size());
        assertEquals(
                Set.of(GenerationStoreTestData.MISSING_GENERATION_ID),
                result.missingGenerationRecordIds());

        GenerationStoreCodec.SweepResult empty =
                GenerationStoreCodec.markAndSweep(twoRecords, List.of());
        assertTrue(empty.store().currentGenerationRecords().isEmpty());
        assertTrue(empty.store().sourceRevisions().isEmpty());
        assertTrue(empty.store().targetRegions().isEmpty());
        assertTrue(empty.store().clientBuilds().isEmpty());
        assertTrue(empty.store().backendIdentities().isEmpty());
    }

    @Test
    void sweepRejectsObjectWhoseOutputDisagreesWithItsGeneration() {
        GenerationStoreCodec.GenerationStore store = GenerationStoreTestData.validStore();
        GenerationEvidenceCodec.GenerationRecord record =
                GenerationStoreTestData.generationRecord();
        ExecutionScopeCodec.OutputKey wrongOutput =
                new ExecutionScopeCodec.OutputKey(
                        record.output().producer(),
                        new ExecutionScopeCodec.FieldScope(new ExecutionScopeCodec.Slide()));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GenerationStoreCodec.markAndSweep(
                                store,
                                List.of(
                                        GenerationStoreTestData.provenance(
                                                wrongOutput,
                                                GenerationStoreTestData.GENERATION_ID))));
    }
}
