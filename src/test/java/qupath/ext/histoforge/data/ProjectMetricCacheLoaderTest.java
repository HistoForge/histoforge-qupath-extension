package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;

class ProjectMetricCacheLoaderTest {

    @Test
    @SuppressWarnings("unchecked")
    void emptyProjectionDoesNotRequirePackagedScientificIdentity() {
        ProjectSummaryData summary = mock(ProjectSummaryData.class);
        ProjectSummaryData withoutMetrics = mock(ProjectSummaryData.class);
        when(summary.getImageSummaries()).thenReturn(List.of());
        when(summary.withoutMetrics()).thenReturn(withoutMetrics);
        Function<LocalResourceEnvelope, ClientBuildIdentity> clientBuildFactory =
                mock(Function.class);
        ProjectMetricCacheLoader loader =
                new ProjectMetricCacheLoader(
                        clientBuildFactory,
                        mock(MetricsEngine.class),
                        mock(MetricImageResolver.class));

        ProjectSummaryData result =
                loader.attachExactResults(
                        summary, List.of(), LocalResourceEnvelope.FOURFOLD, false, () -> false);

        assertSame(withoutMetrics, result);
        verifyNoInteractions(clientBuildFactory);
    }

    @Test
    void projectsTheExplicitRoiOrWholeSlideMetricRegion() {
        ProjectSummaryData.ImageSummary image = mock(ProjectSummaryData.ImageSummary.class);
        when(image.roiIds()).thenReturn(Set.of("11111111-1111-4111-8111-111111111111"));

        assertInstanceOf(
                ActionSelectionCodec.RoiRegions.class,
                ProjectMetricCacheLoader.regions(image, false));
        var slide =
                assertInstanceOf(
                        ActionSelectionCodec.SlideRegion.class,
                        ProjectMetricCacheLoader.regions(image, true));
        assertInstanceOf(ActionSelectionCodec.FullSlide.class, slide.region());
    }
}
