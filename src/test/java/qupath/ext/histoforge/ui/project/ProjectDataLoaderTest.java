package qupath.ext.histoforge.ui.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.data.ProjectInferenceScope;
import qupath.ext.histoforge.data.ProjectMetricCacheLoader;
import qupath.ext.histoforge.platform.LocalResourceEnvelope;
import qupath.lib.projects.Project;

/** Background project-data ownership without JavaFX or network work. */
class ProjectDataLoaderTest {

    @Test
    @SuppressWarnings("unchecked")
    void returnsOneCompleteSnapshotThroughTheUiDispatcher() throws Exception {
        Project<BufferedImage> project = mock(Project.class);
        when(project.getImageList()).thenReturn(List.of());
        ProjectMetricCacheLoader cache = mock(ProjectMetricCacheLoader.class);
        when(cache.attachExactResults(
                        any(), anyList(), eq(LocalResourceEnvelope.FOURFOLD), eq(true), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ProjectDataLoader loader = new ProjectDataLoader(cache, Runnable::run);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<ProjectDataLoader.Result> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();

        loader.loadSummary(
                new ProjectDataLoader.Request(
                        List.of(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        LocalResourceEnvelope.FOURFOLD,
                        new ProjectInferenceScope(
                                ProjectInferenceScope.Mode.WHOLE_SLIDE, Optional.empty())),
                () -> false,
                value -> {
                    result.set(value);
                    complete.countDown();
                },
                error -> {
                    failure.set(error);
                    complete.countDown();
                });

        assertTrue(complete.await(2, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(List.of(), result.get().summary().getImageSummaries());
        assertEquals(List.of(), result.get().modules());
        verify(cache, never()).attachExactResults(any(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancellationStopsBeforeProjectOrCacheWork() throws Exception {
        Project<BufferedImage> project = mock(Project.class);
        ProjectMetricCacheLoader cache = mock(ProjectMetricCacheLoader.class);
        ProjectDataLoader loader = new ProjectDataLoader(cache, Runnable::run);
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();

        loader.loadSummary(
                new ProjectDataLoader.Request(
                        List.of(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        LocalResourceEnvelope.SAFE,
                        ProjectInferenceScope.defaults()),
                () -> true,
                ignored -> complete.countDown(),
                error -> {
                    failure.set(error);
                    complete.countDown();
                });

        assertTrue(complete.await(2, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, failure.get());
        verify(project, never()).getImageList();
        verify(cache, never()).attachExactResults(any(), anyList(), any(), anyBoolean(), any());
    }
}
