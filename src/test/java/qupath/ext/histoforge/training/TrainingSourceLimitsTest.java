package qupath.ext.histoforge.training;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.SourceArtifactLimits;

class TrainingSourceLimitsTest {

    private static final long GIB = 1L << 30;

    @ParameterizedTest
    @MethodSource("sourceLimitCases")
    void trainingKeepsItsOwnCapsAndRespectsSmallerGatewayAndClientLimits(
            long gatewayBytes,
            long localBytes,
            long expectedExportBytes,
            long expectedSourceBytes) {
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

        var limits = TrainingProjectDatasetBuilder.sourceLimits(gateway, client);

        assertEquals(expectedExportBytes, limits.maxDerivedSourceArtifactBytes());
        assertEquals(expectedSourceBytes, limits.maxSourceArtifactBytes());
        assertEquals(gateway.maxCompleteDecodePixels(), limits.maxCompleteDecodePixels());
    }

    private static Stream<Arguments> sourceLimitCases() {
        return Stream.of(
                Arguments.of(32 * GIB, 16 * GIB, GIB, 8 * GIB),
                Arguments.of(4 * GIB, 16 * GIB, GIB, 4 * GIB),
                Arguments.of(GIB / 2, 16 * GIB, GIB / 2, GIB / 2),
                Arguments.of(32 * GIB, GIB / 4, GIB / 4, 8 * GIB));
    }
}
