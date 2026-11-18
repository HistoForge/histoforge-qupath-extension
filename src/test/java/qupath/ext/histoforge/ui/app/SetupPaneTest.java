package qupath.ext.histoforge.ui.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.cli.CliException;

class SetupPaneTest {

    @Test
    void deploymentFallbackRequiresConfirmedGatewayAbsence() {
        for (CliException.Type type : CliException.Type.values()) {
            assertEquals(
                    type == CliException.Type.GATEWAY_NOT_FOUND,
                    SetupPane.shouldDeployAfterConnectFailure(type));
        }
    }
}
