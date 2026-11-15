package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.roi.AnnotationRoiSelection;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.util.HistoforgeKeys;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;

class AnnotationRoiPreparationTest {
    private final ImageLifecycle lifecycle = mock(ImageLifecycle.class);
    private final ImageLifecycle.WorkingImage before = mock(ImageLifecycle.WorkingImage.class);
    private final ImageLifecycle.WorkingImage saved = mock(ImageLifecycle.WorkingImage.class);
    private final ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
    private ImageData<BufferedImage> image;
    private ImageActionExecutor.Request request;
    private PathObject boundary;

    @BeforeEach
    void prepare() throws Exception {
        image =
                RecipeExecutorTestData.imageData(
                        "annotation-preparation", RecipeExecutorTestData.ROI_A);
        boundary = image.getHierarchy().getAnnotationObjects().iterator().next();
        boundary.getMetadata().remove(HistoforgeKeys.ROI_TYPE);
        boundary.setPathClass(PathClass.fromString("Tissue"));
        boundary.setName("Section 1");
        boundary.setLocked(false);
        when(entry.getID()).thenReturn(RecipeExecutorTestData.IMAGE_ID);
        when(before.imageData()).thenReturn(image);
        when(saved.imageData()).thenReturn(image);
        when(saved.sourceRevision()).thenReturn(RecipeExecutorTestData.sourceRevision());
        when(lifecycle.openWorkingImage(any(), any(), any())).thenReturn(before, saved);
        when(lifecycle.commit(before))
                .thenReturn(
                        new ImageLifecycle.CommitResult(
                                RecipeExecutorTestData.sourceRevision().imageDataRevision(),
                                false,
                                false,
                                Optional.empty()));
        request =
                new ImageActionExecutor.Request(
                        RecipeExecutorTestData.ACTION_ID,
                        RecipeExecutorTestData.clientBuild(1_000_000),
                        entry,
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)),
                        Optional.of("Tissue"));
    }

    @AfterEach
    void close() throws Exception {
        image.close();
    }

    @Test
    void locksBeforeSavingAndReturnsOnlyTheReopenedImage() throws Exception {
        var geometry = boundary.getROI().getGeometry().copy();
        try (var prepared = AnnotationRoiPreparation.open(lifecycle, request, () -> false)) {
            assertSame(saved, prepared.working());
            assertTrue(boundary.isLocked());
            assertEquals(RecipeExecutorTestData.ROI_A, boundary.getID().toString());
            assertEquals("Tissue", boundary.getPathClass().toString());
            assertEquals("Section 1", boundary.getName());
            assertTrue(geometry.equalsExact(boundary.getROI().getGeometry()));
            assertFalse(RoiRegistry.isAdministrativeRoi(boundary));
            var order = inOrder(lifecycle);
            order.verify(lifecycle).openWorkingImage(any(), any(), any());
            order.verify(lifecycle).commit(before);
            order.verify(lifecycle).openWorkingImage(any(), any(), any());
        }
        verify(saved).close();
        boundary.setLocked(false);
        assertFalse(boundary.isLocked(), "Native Unlock must remain usable");
        assertEquals(
                Set.of(boundary.getID().toString()),
                AnnotationRoiSelection.ids(image.getHierarchy(), "Tissue"));
    }

    @Test
    void alreadyLockedBoundariesAvoidPreparationSave() throws Exception {
        boundary.setLocked(true);
        try (var prepared = AnnotationRoiPreparation.open(lifecycle, request, () -> false)) {
            assertSame(before, prepared.working());
        }
        verify(lifecycle, never()).commit(any());
        verify(lifecycle, times(1)).openWorkingImage(any(), any(), any());
    }

    @Test
    void metricsNeverLockOrSave() throws Exception {
        var metric =
                new ImageActionExecutor.Request(
                        request.actionId(),
                        request.clientBuild(),
                        entry,
                        RecipeExecutorTestData.fieldMetricOnlyModule(),
                        new ActionSelectionCodec.Metrics(
                                new ActionSelectionCodec.RoiRegions(
                                        Set.of(RecipeExecutorTestData.ROI_A))),
                        Optional.of("Tissue"));
        try (var ignored = AnnotationRoiPreparation.open(lifecycle, metric, () -> false)) {
            assertFalse(boundary.isLocked());
        }
        verify(lifecycle, never()).commit(any());
    }

    @Test
    void saveFailurePreventsCaptureAndDoesNotAttemptReopen() throws Exception {
        when(lifecycle.commit(before)).thenThrow(new IOException("disk unavailable"));
        assertThrows(
                IOException.class,
                () -> AnnotationRoiPreparation.open(lifecycle, request, () -> false));
        verify(lifecycle, times(1)).openWorkingImage(any(), any(), any());
        verify(before).close();
    }

    @Test
    void viewerFailureAfterSuccessfulSaveDoesNotUnlockOrDispatch() throws Exception {
        when(lifecycle.commit(before))
                .thenReturn(
                        new ImageLifecycle.CommitResult(
                                RecipeExecutorTestData.sourceRevision().imageDataRevision(),
                                true,
                                false,
                                Optional.of(new IOException("viewer failed"))));
        assertThrows(
                IOException.class,
                () -> AnnotationRoiPreparation.open(lifecycle, request, () -> false));
        assertTrue(boundary.isLocked());
        verify(lifecycle, times(1)).openWorkingImage(any(), any(), any());
    }

    @Test
    void cancellationAfterSaveLeavesLocksAndDoesNotReopen() throws Exception {
        var stop = new AtomicBoolean();
        when(lifecycle.commit(before))
                .thenAnswer(
                        call -> {
                            stop.set(true);
                            return new ImageLifecycle.CommitResult(
                                    RecipeExecutorTestData.sourceRevision().imageDataRevision(),
                                    false,
                                    false,
                                    Optional.empty());
                        });
        assertThrows(
                CancellationException.class,
                () -> AnnotationRoiPreparation.open(lifecycle, request, stop::get));
        assertTrue(boundary.isLocked());
        verify(lifecycle, times(1)).openWorkingImage(any(), any(), any());
    }

    @Test
    void invalidSelectionFailsBeforeLocksOrSave() throws Exception {
        boundary.setPathClass(PathClass.fromString("Other"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AnnotationRoiPreparation.open(lifecycle, request, () -> false));
        assertFalse(boundary.isLocked());
        verify(lifecycle, never()).commit(any());
    }
}
