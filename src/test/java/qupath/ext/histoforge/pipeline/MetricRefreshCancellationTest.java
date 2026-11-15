package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.*;
import qupath.ext.histoforge.data.MetricImageResolver;
import qupath.ext.histoforge.data.MetricsEngine;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

class MetricRefreshCancellationTest {
    @Test
    @SuppressWarnings("unchecked")
    void cancellationAfterLeaseAcquisitionPreventsCaptureAndReleasesLease() throws Exception {
        var entry = (ProjectImageEntry<BufferedImage>) mock(ProjectImageEntry.class);
        when(entry.getID()).thenReturn("image");
        var registry = new ImageActionRegistry();
        var lifecycle = mock(ImageLifecycle.class);
        var resolver = new MetricImageResolver(registry, lifecycle, new MetricsEngine());
        var started = new CountDownLatch(1);
        var stopped = new AtomicBoolean();
        var checks = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> pending;
            try (var lease = registry.acquireLease("image")) {
                pending =
                        executor.submit(
                                () ->
                                        resolver.resolve(
                                                entry,
                                                PlanningTestData.clientBuild(),
                                                selections(),
                                                MetricImageResolver.CapturePurpose.PROJECT_CACHE,
                                                false,
                                                () -> {
                                                    checks.incrementAndGet();
                                                    boolean cancelled = stopped.get();
                                                    started.countDown();
                                                    return cancelled;
                                                }));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                stopped.set(true);
            }
            var failure =
                    assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(2, checks.get());
            verifyNoInteractions(lifecycle);
            executor.submit(
                            () -> {
                                try (var lease = registry.acquireLease("image")) {
                                    return true;
                                }
                            })
                    .get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerThreadCancellationPrecedesRevisionAndStoredOpening() throws Exception {
        var entry = (ProjectImageEntry<BufferedImage>) mock(ProjectImageEntry.class);
        when(entry.getID()).thenReturn("image");
        var revision = mock(ImageLifecycle.RevisionCapture.class);
        var source = mock(ImageLifecycle.SourceCapture.class);
        var stopped = new AtomicBoolean();
        var lifecycle =
                new ImageLifecycle(
                        mock(QuPathGUI.class),
                        List::of,
                        continuation -> {
                            stopped.set(true);
                            continuation.run();
                        },
                        revision,
                        source);
        assertThrows(
                CancellationException.class,
                () ->
                        lifecycle.captureReadOnly(
                                entry,
                                "image",
                                PlanningTestData.clientBuild().limits(),
                                () -> {
                                    if (stopped.get()) throw new CancellationException();
                                },
                                (image, token, captured) -> {
                                    throw new AssertionError("capture ran");
                                }));
        verify(entry, never()).readImageData();
        verifyNoInteractions(revision, source);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancellationAfterStoredOpeningClosesWithoutSavingOrCapturing() throws Exception {
        var entry = (ProjectImageEntry<BufferedImage>) mock(ProjectImageEntry.class);
        when(entry.getID()).thenReturn("image");
        var stored = (ImageData<BufferedImage>) mock(ImageData.class);
        var stopped = new AtomicBoolean();
        when(entry.readImageData())
                .thenAnswer(
                        call -> {
                            stopped.set(true);
                            return stored;
                        });
        var revision = mock(ImageLifecycle.RevisionCapture.class);
        var source = mock(ImageLifecycle.SourceCapture.class);
        var lifecycle =
                new ImageLifecycle(
                        mock(QuPathGUI.class), List::of, Runnable::run, revision, source);
        assertThrows(
                CancellationException.class,
                () ->
                        lifecycle.captureReadOnly(
                                entry,
                                "image",
                                PlanningTestData.clientBuild().limits(),
                                () -> {
                                    if (stopped.get()) throw new CancellationException();
                                },
                                (image, token, captured) -> {
                                    throw new AssertionError("capture ran");
                                }));
        verify(stored).close();
        verify(entry, never()).saveImageData(any());
        verifyNoInteractions(revision, source);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectCacheStillValidatesGenerationStoreThroughRealRevisionCapture() throws Exception {
        var entry = (ProjectImageEntry<BufferedImage>) mock(ProjectImageEntry.class);
        when(entry.getID()).thenReturn("image");
        var image = new ImageData<BufferedImage>(mock(qupath.lib.images.servers.ImageServer.class));
        var source = mock(ImageSourceCapture.CapturedSource.class);
        when(source.prepare()).thenReturn(source);
        when(source.identity())
                .thenReturn(
                        new SourceIdentityCodec.ImageSourceIdentity(
                                "test",
                                "a".repeat(64),
                                Optional.empty(),
                                200,
                                100,
                                1,
                                1,
                                new SourceIdentityCodec.PixelCalibration(1.0, 1.0)));
        var qupath = mock(QuPathGUI.class);
        var project = (Project<BufferedImage>) mock(Project.class);
        var viewer = mock(QuPathViewer.class);
        when(qupath.getProject()).thenReturn(project);
        when(project.getEntry(image)).thenReturn(entry);
        when(viewer.getImageData()).thenReturn(image);
        var lifecycle =
                new ImageLifecycle(
                        qupath,
                        () -> List.of(viewer),
                        Runnable::run,
                        ImageDataRevisionDigester::capture,
                        ignored -> source);
        var resolver =
                new MetricImageResolver(new ImageActionRegistry(), lifecycle, new MetricsEngine());
        for (String corrupt :
                List.of(
                        "not-json",
                        "x"
                                .repeat(
                                        (int)
                                                        PlanningTestData.clientBuild()
                                                                .limits()
                                                                .maxGenerationStoreUtf8Bytes()
                                                + 1))) {
            image.setProperty(HistoforgePersistenceKeys.GENERATION_STORE, corrupt);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            resolver.resolve(
                                    entry,
                                    PlanningTestData.clientBuild(),
                                    selections(),
                                    MetricImageResolver.CapturePurpose.PROJECT_CACHE,
                                    false,
                                    () -> false));
        }
        verify(entry, never()).saveImageData(any());
    }

    private static List<MetricImageResolver.MetricSelection> selections() {
        return List.of(
                new MetricImageResolver.MetricSelection(
                        PlanningTestData.module(Set.of(), true),
                        new ActionSelectionCodec.RoiRegions(Set.of(PlanningTestData.ROI_A))));
    }
}
