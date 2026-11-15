package qupath.ext.histoforge.ui.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import org.junit.jupiter.api.Test;

class CloudSetupProgressTest {
    @Test
    void deploymentAndVerificationAreDifferentStages() {
        assertEquals(
                "Deploying backend…",
                CloudSetupProgress.statusMessage("Deploying the bundled cloud backend…"));
        assertEquals(
                "Checking backend connection…",
                CloudSetupProgress.statusMessage("Verifying the updated cloud backend…"));
        assertEquals(
                "Waiting for active work to finish…",
                CloudSetupProgress.statusMessage(
                        "Waiting for module installation, publication and final saves…"));
        assertEquals(
                "Finish Modal login in your browser…",
                CloudSetupProgress.statusMessage(
                        "No Modal credentials found. Opening a browser to log in..."));
        assertNull(CloudSetupProgress.statusMessage("Building image im-fixture"));
    }

    @Test
    void onlyAnOfficialModalTokenFlowBecomesTheVisibleLoginLink() {
        var url = "https://modal.com/token-flow/tf-fixture";
        assertEquals(URI.create(url), CloudSetupProgress.loginUri("  " + url + "  "));
        for (String other :
                new String[] {
                    "https://example.org/token-flow/tf-fixture",
                    "http://modal.com/token-flow/tf-fixture",
                    "https://modal.com@evil.example/token-flow/tf-fixture",
                    "https://modal.com/token-flow/not a uri",
                    "https://modal.com/apps/workspace/main",
                    "local paths and other diagnostic output"
                }) {
            assertNull(CloudSetupProgress.loginUri(other), other);
        }
    }
}
