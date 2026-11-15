package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.data.MetricTypes.MetricImageSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricInputDraft;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectType;
import qupath.ext.histoforge.data.MetricTypes.MetricPreparation;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;
import qupath.ext.histoforge.spatial.HierarchyDelta;

/** Parent-repair selection uses only the requested UUIDs and excludes failed metric regions. */
class MetricParentNormalizationSelectionTest {

    @Test
    void emptyAssignmentsDoNotVisitTheSnapshotOrEvaluation() {
        MetricPreparation preparation = mock(MetricPreparation.class);
        MetricsEngine.Evaluation evaluation = mock(MetricsEngine.Evaluation.class);
        when(preparation.parentNormalization()).thenReturn(List.of());

        assertEquals(
                List.of(), MetricsEngine.successfulParentNormalization(preparation, evaluation));

        verify(preparation, never()).snapshot();
        verifyNoInteractions(evaluation);
    }

    @Test
    void sparseRepairUsesTheUuidSortedPopulationWithoutScanningEveryObject() {
        AtomicInteger objectReads = new AtomicInteger();
        List<MetricObjectSnapshot> objects =
                new AbstractList<>() {
                    @Override
                    public MetricObjectSnapshot get(int index) {
                        objectReads.incrementAndGet();
                        return object(index + 1);
                    }

                    @Override
                    public int size() {
                        return 4096;
                    }
                };
        MetricRegionSnapshot region = mock(MetricRegionSnapshot.class);
        when(region.objects()).thenReturn(objects);
        when(region.target()).thenReturn(MetricTestData.target(MetricTestData.ROI_A));
        MetricInputDraft input = mock(MetricInputDraft.class);
        when(input.regions()).thenReturn(List.of(region));
        var assignment = assignment(4000);
        var preparation =
                new MetricPreparation(
                        new MetricImageSnapshot(input, MetricTestData.revision('1')),
                        List.of(assignment));
        var evaluatedInput = preparation(MetricTestData.region(MetricTestData.ROI_A));
        var evaluation =
                new MetricsEngine((script, target, imageId, mppX, mppY) -> Map.of("count", 1))
                        .evaluate(evaluatedInput, Map.of(), false, () -> false);

        assertEquals(
                List.of(assignment),
                MetricsEngine.successfulParentNormalization(preparation, evaluation));
        assertTrue(objectReads.get() <= 13, "repair lookup must be logarithmic in the population");
    }

    @Test
    void failedRegionsContributeNoRepairsAndSuccessfulRepairOrderIsPreserved() {
        var preparation =
                preparation(
                        new MetricRegionSnapshot(
                                MetricTestData.target(MetricTestData.ROI_A),
                                List.of(object(3), object(1))),
                        new MetricRegionSnapshot(
                                MetricTestData.target(MetricTestData.ROI_B), List.of(object(2))));
        preparation =
                new MetricPreparation(
                        preparation.snapshot(),
                        List.of(assignment(3), assignment(2), assignment(1)));
        var failed = new ExecutionScopeCodec.Roi(MetricTestData.ROI_B);
        var evaluation =
                new MetricsEngine(
                                (script, region, imageId, mppX, mppY) ->
                                        region.target().key().equals(failed)
                                                ? Map.of("invalid", 1)
                                                : Map.of("count", 1))
                        .evaluate(preparation, Map.of(), false, () -> false);

        assertEquals(
                List.of(assignment(1), assignment(3)),
                MetricsEngine.successfulParentNormalization(preparation, evaluation));
        assertEquals(1, evaluation.regionFailures().size());
    }

    @Test
    void missingOrAmbiguousRepairObjectsFailClosed() {
        for (var regions :
                List.of(
                        List.of(MetricTestData.region(MetricTestData.ROI_A)),
                        List.of(
                                new MetricRegionSnapshot(
                                        MetricTestData.target(MetricTestData.ROI_A),
                                        List.of(object(1))),
                                new MetricRegionSnapshot(
                                        MetricTestData.target(MetricTestData.ROI_B),
                                        List.of(object(1)))))) {
            var input = preparation(regions.toArray(MetricRegionSnapshot[]::new));
            var preparation = new MetricPreparation(input.snapshot(), List.of(assignment(1)));
            var evaluation =
                    new MetricsEngine((script, region, imageId, mppX, mppY) -> Map.of("count", 1))
                            .evaluate(preparation, Map.of(), false, () -> false);

            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    MetricsEngine.successfulParentNormalization(
                                            preparation, evaluation));
            assertEquals(
                    regions.size() == 1
                            ? "parent normalization references an uncaptured metric object"
                            : "metric object occurs in multiple selected regions",
                    failure.getMessage());
        }
    }

    private static MetricPreparation preparation(MetricRegionSnapshot... regions) {
        return MetricTestData.preparation(
                MetricTestData.clientBuild(10, 20),
                MetricTestData.script(
                        "return [count: 1]",
                        MetricTypesTest.scalar("count", MetricTypes.AggregationType.SUM)),
                MetricTestData.revision('1'),
                regions);
    }

    private static MetricObjectSnapshot object(int suffix) {
        return new MetricObjectSnapshot(
                MetricTestData.objectId(suffix),
                MetricObjectType.DETECTION,
                "Cell",
                MetricTestData.square(1, 1, 5, 5),
                Optional.empty());
    }

    private static HierarchyDelta.ParentAssignment assignment(int suffix) {
        return new HierarchyDelta.ParentAssignment(
                MetricTestData.objectId(suffix), Optional.empty());
    }
}
