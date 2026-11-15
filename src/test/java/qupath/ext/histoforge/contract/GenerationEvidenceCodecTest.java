package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** Pins closed backend-execution references and generation-count semantics. */
class GenerationEvidenceCodecTest {

    @Test
    void backendExecutionVariantsAreClosedAndExact() {
        BackendExecutionCodec.BackendExecutionRef finite =
                new BackendExecutionCodec.FiniteCall("fc-fixture");
        BackendExecutionCodec.BackendExecutionRef durable =
                new BackendExecutionCodec.DurableRun(
                        "88888888-8888-4888-8888-888888888888", GenerationStoreTestData.hex('8'));

        assertEquals(finite, BackendExecutionCodec.parse(BackendExecutionCodec.toJson(finite)));
        assertEquals(durable, BackendExecutionCodec.parse(BackendExecutionCodec.toJson(durable)));
        assertEquals("finite-call", BackendExecutionCodec.toJson(finite).get("type").getAsString());
        assertEquals(
                "durable-run", BackendExecutionCodec.toJson(durable).get("type").getAsString());

        JsonObject unknownField = BackendExecutionCodec.toJson(finite);
        unknownField.addProperty("extra", true);
        assertThrows(
                IllegalArgumentException.class, () -> BackendExecutionCodec.parse(unknownField));

        JsonObject unknownType = BackendExecutionCodec.toJson(finite);
        unknownType.addProperty("type", "other");
        assertThrows(
                IllegalArgumentException.class, () -> BackendExecutionCodec.parse(unknownType));
    }

    @Test
    void countSemanticsAndValidEmptyNonPersistenceFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationEvidenceCodec.GenerationCounts(2, 3, 0, 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationEvidenceCodec.GenerationCounts(3, 3, 1, 3));

        GenerationEvidenceCodec.GenerationRecord record =
                GenerationStoreTestData.generationRecord();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GenerationStoreTestData.copyRecordWithCounts(
                                record,
                                false,
                                new GenerationEvidenceCodec.GenerationCounts(5, 4, 1, 3)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GenerationStoreTestData.copyRecordWithCounts(
                                record,
                                true,
                                new GenerationEvidenceCodec.GenerationCounts(0, 0, 0, 0)));
    }
}
