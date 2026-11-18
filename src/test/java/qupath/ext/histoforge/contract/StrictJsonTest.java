package qupath.ext.histoforge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrictJsonTest {

    @Test
    void exactObjectAndStringPreserveTheFullDiagnosticPath() {
        JsonObject value = new JsonObject();
        value.addProperty("name", 7);

        JsonObject exact = StrictJson.requireExactObject(value, Set.of("name"), "entry");
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> StrictJson.requiredString(exact, "name", "entry"));

        assertEquals("entry.name must be a string", failure.getMessage());
    }

    @Test
    void exactObjectRejectsMissingOrUnknownFields() {
        JsonObject value = new JsonObject();
        value.addProperty("unexpected", "value");

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> StrictJson.requireExactObject(value, Set.of("name"), "entry"));

        assertEquals("entry has missing or unknown fields", failure.getMessage());
    }
}
