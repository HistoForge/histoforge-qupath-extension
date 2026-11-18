package qupath.ext.histoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import qupath.lib.gui.QuPathGUI;

class SupportedQuPathHostTest {

    @Test
    void loadsFromTheExpectedArtifactAgainstTheSelectedHost() throws Exception {
        String expectedHost = System.getProperty("histoforge.test.qupath.version");
        assertTrue(
                expectedHost.equals("0.6.0") || expectedHost.equals("0.7.0"),
                "Gradle must supply an exact supported QuPath host version");
        assertEquals(expectedHost, QuPathGUI.getVersion().toString());
        assertEquals("0.6.0", new HistoforgeExtension().getQuPathVersion().toString());

        String expectedArtifact = System.getProperty("histoforge.test.distribution.artifact", "");
        if (!expectedArtifact.isBlank()) {
            assertEquals(
                    Path.of(expectedArtifact).toRealPath(), codeSource(HistoforgeExtension.class));
        }
    }

    private static Path codeSource(Class<?> type) throws IOException, URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toRealPath();
    }
}
