package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.data.MetricPopulationCapture.ObjectFields;
import qupath.ext.histoforge.data.MetricPopulationCapture.Region;
import qupath.ext.histoforge.data.MetricTypes.AggregationType;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectType;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricType;

/** Within-region and cross-region identity validation remain separate and unconditional. */
class MetricPopulationPreparationTest {
    private static final BuildIdentityCodec.ClientBuildIdentity BUILD =
            MetricTestData.clientBuild(10, 20);
    private static final MetricScriptSnapshot SCRIPT =
            MetricTestData.script(
                    "return [count: 1]",
                    new MetricDefinition(
                            "count",
                            "Count",
                            MetricType.SCALAR,
                            Optional.empty(),
                            0,
                            AggregationType.SUM));

    @Test
    void noRegionsStillFailsTheInputContract() {
        assertEquals(
                "metric regions must be nonempty",
                assertThrows(IllegalArgumentException.class, () -> capture(List.of()).prepare())
                        .getMessage());
    }

    @Test
    void oneEmptyRegionStillProducesAnEmptySnapshot() {
        var actual = capture(List.of(region(MetricTestData.ROI_A, List.of()))).prepare();
        assertTrue(actual.preparation().parentNormalization().isEmpty());
        assertTrue(actual.attributedObjects().isEmpty());
        assertEquals(
                List.of(MetricTestData.region(MetricTestData.ROI_A)),
                actual.preparation().snapshot().metricInput().regions());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void duplicatesWithinARegionStillFailWithOneOrSeveralRegions(int regionCount) {
        var object = object(1, MetricTestData.ROI_A);
        var duplicate =
                region(
                        MetricTestData.ROI_A,
                        List.of(object, object(2, MetricTestData.ROI_A), object));
        var regions =
                regionCount == 1
                        ? List.of(duplicate)
                        : List.of(duplicate, region(MetricTestData.ROI_B, List.of()));
        var failure =
                assertThrows(IllegalArgumentException.class, () -> capture(regions).prepare());
        assertEquals(
                regionCount == 1
                        ? "region node UUIDs must be unique"
                        : "one metric preparation cannot select an object into multiple regions",
                failure.getMessage());
    }

    @Test
    void duplicateSelectionAcrossRegionsStillFailsBeforeSecondRegionPreparation() {
        var regions =
                List.of(
                        region(MetricTestData.ROI_A, List.of(object(1, MetricTestData.ROI_A))),
                        region(MetricTestData.ROI_B, List.of(object(1, MetricTestData.ROI_B))));
        assertEquals(
                "one metric preparation cannot select an object into multiple regions",
                assertThrows(IllegalArgumentException.class, () -> capture(regions).prepare())
                        .getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void validRegionsKeepExactNormalizedInputAndParentAssignments(int regionCount) {
        var first =
                region(
                        MetricTestData.ROI_A,
                        List.of(object(2, MetricTestData.ROI_A), object(1, MetricTestData.ROI_A)));
        var second = region(MetricTestData.ROI_B, List.of(object(3, MetricTestData.ROI_B)));
        var regions = regionCount == 1 ? List.of(first) : List.of(first, second);
        var expectedRegions =
                regions.stream()
                        .map(
                                region ->
                                        new MetricRegionSnapshot(
                                                region.target(),
                                                region.objects().stream()
                                                        .map(
                                                                object ->
                                                                        new MetricObjectSnapshot(
                                                                                object.objectId(),
                                                                                object.objectType(),
                                                                                object.pathClass(),
                                                                                object.geometry()
                                                                                        .copy(),
                                                                                Optional.empty()))
                                                        .toList()))
                        .toArray(MetricRegionSnapshot[]::new);
        var expected =
                MetricTestData.preparation(
                        BUILD, SCRIPT, source().imageDataRevision(), expectedRegions);
        var actual = capture(regions).prepare();
        assertEquals(expected, actual.preparation());
        assertEquals(
                expected.snapshot().metricInput().metricInputDigest(),
                actual.preparation().snapshot().metricInput().metricInputDigest());
        assertTrue(actual.attributedObjects().isEmpty());
    }

    private static MetricPopulationCapture capture(List<Region> regions) {
        return new MetricPopulationCapture(BUILD, source(), MetricTestData.MODULE, SCRIPT, regions);
    }

    private static RasterInputCodec.ActionSourceRevision source() {
        var source =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "metric-test",
                        "7".repeat(64),
                        Optional.empty(),
                        100,
                        100,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
        return new RasterInputCodec.ActionSourceRevision(
                new RasterInputCodec.ImageRevisionToken(
                        MetricTestData.IMAGE_ID,
                        SourceIdentityCodec.imageSourceIdentityDigest(source),
                        "8".repeat(64)),
                source);
    }

    private static Region region(String id, List<ObjectFields> objects) {
        UUID parent = UUID.fromString(id);
        return new Region(
                MetricTestData.target(id), objects, Optional.of(parent), Set.of(parent), Map.of());
    }

    private static ObjectFields object(int suffix, String region) {
        return new ObjectFields(
                MetricTestData.objectId(suffix),
                MetricObjectType.DETECTION,
                "Cell",
                MetricObjectGeometry.copyOf(MetricTestData.square(suffix, 1, suffix + 1, 2)),
                Optional.of(UUID.fromString(region)));
    }
}
