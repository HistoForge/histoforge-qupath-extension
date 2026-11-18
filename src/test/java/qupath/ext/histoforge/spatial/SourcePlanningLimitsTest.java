package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.SourceArtifactLimits;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;

class SourcePlanningLimitsTest {

    @ParameterizedTest
    @CsvSource({
        "34359738368, 17179869184, 17179869184",
        "8589934592, 17179869184, 8589934592",
        "34359738368, 1073741824, 1073741824",
        "1025, 17179869184, 1025"
    })
    void inferenceExportCeilingIsIndependentOfOriginalSizeComparison(
            long gatewayBytes, long localBytes, long expectedExportBytes) {
        var gateway =
                new SourceArtifactLimits(
                        131072,
                        gatewayBytes,
                        4096,
                        8388608,
                        16,
                        16,
                        500000,
                        500000,
                        32000000,
                        3600,
                        86400);
        var client = new ClientExecutionLimits(1, 1, 1, 1, 1, 1, 1, localBytes);

        var limits = SourcePlanningLimits.forInference(gateway, client);

        assertEquals(expectedExportBytes, limits.maxDerivedSourceArtifactBytes());
        assertEquals(gatewayBytes, limits.maxSourceArtifactBytes());
        assertEquals(32000000, limits.maxCompleteDecodePixels());
    }
}
