package qupath.ext.histoforge.ui.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.client.ModuleRef;

class InstallDialogTest {

    private static final ModuleRef.HuggingFaceSource SOURCE =
            ModuleRef.parseHuggingFaceSource("histoforge/leydig@0123456789abcdef").orElseThrow();

    @Test
    void cancellationProducesNoInstallRequest() {
        AtomicInteger confirmations = new AtomicInteger();

        var request =
                InstallDialog.authorizedRequest(
                        SOURCE,
                        ignored -> {
                            confirmations.incrementAndGet();
                            return false;
                        });

        assertTrue(request.isEmpty());
        assertEquals(1, confirmations.get());
    }

    @Test
    void acceptanceProducesOneExactInstallRequest() {
        AtomicInteger confirmations = new AtomicInteger();

        var request =
                InstallDialog.authorizedRequest(
                                SOURCE,
                                ignored -> {
                                    confirmations.incrementAndGet();
                                    return true;
                                })
                        .orElseThrow();

        assertEquals(1, confirmations.get());
        assertEquals("histoforge/leydig", request.moduleRef());
        assertEquals("0123456789abcdef", request.huggingFaceRevision());
    }

    @Test
    void warningExplainsModuleCapabilities() {
        String warning = InstallDialog.trustWarning(SOURCE);

        assertTrue(warning.contains(SOURCE.toString()));
        assertTrue(warning.contains("code"));
        assertTrue(warning.contains("files"));
        assertTrue(warning.contains("project data"));
        assertTrue(warning.contains("internet"));
        assertTrue(warning.contains("QuPath"));
        assertFalse(warning.contains("Groovy"));
    }
}
