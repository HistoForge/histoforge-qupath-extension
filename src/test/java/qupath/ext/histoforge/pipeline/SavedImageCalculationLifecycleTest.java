package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.spatial.SourcePlanningInputs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Save, capture and conflict behavior when a calculation usually needs no editable image. */
class SavedImageCalculationLifecycleTest {
    private static final String IMAGE_ID = "calculation-image";
    private static final ExecutionLimitsCodec.ClientExecutionLimits LIMITS =
            new ExecutionLimitsCodec.ClientExecutionLimits(100, 100, 100, 100, 100, 100, 100, 100);
    private static final SourceIdentityCodec.ImageSourceIdentity SOURCE =
            new SourceIdentityCodec.ImageSourceIdentity(
                    "fixture.reader",
                    "a".repeat(64),
                    Optional.empty(),
                    100,
                    80,
                    1,
                    1,
                    new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
    private final QuPathGUI qupath = mock(QuPathGUI.class);
    private final Project<BufferedImage> project = mock(Project.class);
    private final ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
    private final QuPathViewer viewer = mock(QuPathViewer.class);
    private final ImageData<BufferedImage> live = mock(ImageData.class);
    private final ImageData<BufferedImage> stored = mock(ImageData.class);
    private final AtomicReference<ImageData<BufferedImage>> visible = new AtomicReference<>(live);
    private final AtomicBoolean onFx = new AtomicBoolean();
    private final AtomicReference<IOException> sourceFailure = new AtomicReference<>();
    private final AtomicInteger sourceCaptures = new AtomicInteger();
    private final AtomicInteger revisionCaptures = new AtomicInteger();
    private final Map<ImageData<?>, RasterInputCodec.ImageRevisionToken> revisions =
            new IdentityHashMap<>();
    private ImageLifecycle lifecycle;

    @BeforeEach
    void setUp() throws Exception {
        when(entry.getID()).thenReturn(IMAGE_ID);
        when(qupath.getProject()).thenReturn(project);
        when(project.getEntry(any(ImageData.class))).thenReturn(entry);
        when(viewer.getImageData()).thenAnswer(ignored -> visible.get());
        when(entry.readImageData())
                .thenAnswer(
                        ignored -> {
                            assertFalse(onFx.get());
                            return stored;
                        });
        doAnswer(
                        ignored -> {
                            assertTrue(onFx.get());
                            return null;
                        })
                .when(entry)
                .saveImageData(any());
        revisions.put(live, revision('b'));
        revisions.put(stored, revision('b'));
        lifecycle =
                new ImageLifecycle(
                        qupath,
                        () -> List.of(viewer),
                        operation -> {
                            assertTrue(onFx.compareAndSet(false, true));
                            try {
                                operation.run();
                            } finally {
                                onFx.set(false);
                            }
                        },
                        (id, digest, image, limits) -> {
                            revisionCaptures.incrementAndGet();
                            return revisions.get(image);
                        },
                        server -> {
                            sourceCaptures.incrementAndGet();
                            return source();
                        });
    }

    @Test
    void savesBeforeLiveCaptureWithoutLoadingOrClosingAnImage() throws Exception {
        var captured =
                lifecycle.captureSavedImage(
                        entry,
                        IMAGE_ID,
                        LIMITS,
                        image -> true,
                        (image, revision, source) -> {
                            assertTrue(onFx.get());
                            assertSame(live, image);
                            try {
                                verify(entry).saveImageData(live);
                            } catch (IOException failure) {
                                throw new AssertionError(failure);
                            }
                            return "detached input";
                        });
        assertEquals("detached input", captured.value());
        assertEquals(
                ImageLifecycle.ImageDataMaterialization.LIVE_VIEWER, captured.materialization());
        verify(entry, never()).readImageData();
        verify(live, never()).close();
    }

    @Test
    void unsupportedLiveRepresentationUsesAndClosesTheSavedImage() throws Exception {
        var captured =
                lifecycle.captureSavedImage(
                        entry,
                        IMAGE_ID,
                        LIMITS,
                        image -> false,
                        (image, revision, source) -> {
                            assertFalse(onFx.get());
                            assertSame(stored, image);
                            return "saved input";
                        });
        assertEquals(
                ImageLifecycle.ImageDataMaterialization.STORED_QPDATA, captured.materialization());
        var order = inOrder(entry);
        order.verify(entry).saveImageData(live);
        order.verify(entry).readImageData();
        verify(stored).close();
        verify(live, never()).close();
    }

    @Test
    void preparationSaveFailurePreventsCaptureAndStoredLoad() throws Exception {
        doThrow(new IOException("save failed")).when(entry).saveImageData(live);
        var failure =
                assertThrows(
                        IOException.class,
                        () ->
                                lifecycle.captureSavedImage(
                                        entry,
                                        IMAGE_ID,
                                        LIMITS,
                                        image -> {
                                            assertTrue(onFx.get());
                                            return true;
                                        },
                                        (image, revision, source) -> {
                                            fail("capture after failed save");
                                            return "unused";
                                        }));
        assertEquals("save failed", failure.getMessage());
        assertEquals(0, revisionCaptures.get());
        verify(entry, never()).readImageData();
        verify(live, never()).close();
    }

    @Test
    void irrelevantLiveImageSkipsSourceAndRevisionWithoutLoadingOrClosing() throws Exception {
        var result =
                lifecycle.captureReadOnlyIf(
                        entry,
                        IMAGE_ID,
                        LIMITS,
                        image -> {
                            assertTrue(onFx.get());
                            assertSame(live, image);
                            return false;
                        },
                        () -> {},
                        (image, revision, source) -> fail("skipped capture"));
        assertTrue(result.isEmpty());
        assertEquals(0, sourceCaptures.get());
        assertEquals(0, revisionCaptures.get());
        verify(entry, never()).readImageData();
        verify(entry, never()).saveImageData(any());
        verify(live, never()).close();
    }

    @Test
    void irrelevantStoredImageSkipsSourceAndRevisionAndCloses() throws Exception {
        visible.set(null);
        var result =
                lifecycle.captureReadOnlyIf(
                        entry,
                        IMAGE_ID,
                        LIMITS,
                        image -> {
                            assertFalse(onFx.get());
                            assertSame(stored, image);
                            return false;
                        },
                        () -> {},
                        (image, revision, source) -> fail("skipped capture"));
        assertTrue(result.isEmpty());
        assertEquals(0, sourceCaptures.get());
        assertEquals(0, revisionCaptures.get());
        verify(stored).close();
        verify(entry, never()).saveImageData(any());
    }

    @Test
    void acceptedConditionalCaptureKeepsExactRevisionAndOwnerThread() throws Exception {
        for (boolean isLive : List.of(true, false)) {
            visible.set(isLive ? live : null);
            int before = revisionCaptures.get();
            var result =
                    lifecycle
                            .captureReadOnlyIf(
                                    entry,
                                    IMAGE_ID,
                                    LIMITS,
                                    image -> {
                                        assertEquals(isLive, onFx.get());
                                        return true;
                                    },
                                    () -> {},
                                    (image, revision, source) -> {
                                        assertEquals(isLive, onFx.get());
                                        assertEquals(expected(), revision);
                                        assertEquals(before + 1, revisionCaptures.get());
                                        return "detached";
                                    })
                            .orElseThrow();
            assertEquals(expected(), result.sourceRevision());
            assertEquals("detached", result.value());
        }
        verify(stored).close();
        verify(live, never()).close();
        verify(entry, never()).saveImageData(any());
    }

    @Test
    void eligibilityIsRecheckedAfterLiveSourcePreparation() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        var result =
                lifecycle.captureReadOnlyIf(
                        entry,
                        IMAGE_ID,
                        LIMITS,
                        image -> {
                            assertTrue(onFx.get());
                            return checks.incrementAndGet() == 1;
                        },
                        () -> {},
                        (image, revision, source) -> fail("ROI was removed"));
        assertTrue(result.isEmpty());
        assertEquals(2, checks.get());
        assertEquals(0, revisionCaptures.get());
        verify(entry, never()).readImageData();
    }

    @Test
    void cancellationBeforeEventualLiveCaptureDoesNotHashTheHierarchy() {
        AtomicInteger checks = new AtomicInteger();
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () ->
                        lifecycle.captureReadOnlyIf(
                                entry,
                                IMAGE_ID,
                                LIMITS,
                                image -> true,
                                () -> {
                                    assertTrue(onFx.get());
                                    if (checks.incrementAndGet() == 2)
                                        throw new java.util.concurrent.CancellationException();
                                },
                                (image, revision, source) -> fail("cancelled capture")));
        assertEquals(0, revisionCaptures.get());
    }

    @Test
    void failingStoredConditionStillClosesTheImage() throws Exception {
        visible.set(null);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        lifecycle.captureReadOnlyIf(
                                entry,
                                IMAGE_ID,
                                LIMITS,
                                image -> {
                                    throw new IllegalArgumentException("bad ROI");
                                },
                                () -> {},
                                (image, revision, source) -> fail("invalid capture")));
        verify(stored).close();
        assertEquals(0, sourceCaptures.get());
        assertEquals(0, revisionCaptures.get());
    }

    @Test
    void failingLiveAdmissionSkipsSourceAndRevisionWithoutClosingTheViewer() throws Exception {
        var rejected = new IllegalArgumentException("oversized training ROI");
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                lifecycle.captureReadOnlyIf(
                                        entry,
                                        IMAGE_ID,
                                        LIMITS,
                                        image -> {
                                            assertTrue(onFx.get());
                                            throw rejected;
                                        },
                                        () -> {},
                                        (image, revision, source) -> fail("rejected capture")));
        assertSame(rejected, failure);
        assertEquals(0, sourceCaptures.get());
        assertEquals(0, revisionCaptures.get());
        verify(entry, never()).readImageData();
        verify(entry, never()).saveImageData(any());
        verify(live, never()).close();
    }

    @Test
    void admissionFailureAfterSourcePreparationStillPreventsRevisionCapture() {
        var checks = new AtomicInteger();
        var rejected = new IllegalArgumentException("ROI became oversized");
        assertSame(
                rejected,
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                lifecycle.captureReadOnlyIf(
                                        entry,
                                        IMAGE_ID,
                                        LIMITS,
                                        image -> {
                                            if (checks.incrementAndGet() == 2) throw rejected;
                                            return true;
                                        },
                                        () -> {},
                                        (image, revision, source) -> fail("changed capture"))));
        assertEquals(2, checks.get());
        assertEquals(1, sourceCaptures.get());
        assertEquals(0, revisionCaptures.get());
    }

    @Test
    void storedCalculationDoesNotSaveAnImage() throws Exception {
        visible.set(null);
        var captured =
                lifecycle.captureSavedImage(
                        entry,
                        IMAGE_ID,
                        LIMITS,
                        image -> {
                            fail("no live image");
                            return false;
                        },
                        (image, revision, source) -> "stored input");
        assertEquals("stored input", captured.value());
        verify(entry, never()).saveImageData(any());
        verify(stored).close();
    }

    @Test
    void lateMatchingLoadDoesNotSaveNewViewerEdits() throws Exception {
        revisions.put(live, revision('c'));
        try (var working =
                lifecycle.openMatchingWorkingImage(entry, IMAGE_ID, LIMITS, expected())) {
            assertSame(stored, working.imageData());
            var failure =
                    assertThrows(
                            ImageLifecycle.RevisionConflictException.class,
                            () -> lifecycle.commit(working));
            assertEquals(revision('b'), failure.expected());
            assertEquals(revision('c'), failure.actual());
        }
        verify(entry, never()).saveImageData(any());
        verify(viewer, never()).setImageData(any());
        verify(stored).close();
    }

    @Test
    void changedStoredImageCannotBecomeTheBaselineOfOldCalculations() throws Exception {
        revisions.put(stored, revision('c'));
        var failure =
                assertThrows(
                        ImageLifecycle.RevisionConflictException.class,
                        () ->
                                lifecycle.openMatchingWorkingImage(
                                        entry, IMAGE_ID, LIMITS, expected()));
        assertEquals(revision('c'), failure.actual());
        verify(stored).close();
        verify(entry, never()).saveImageData(any());
    }

    @Test
    void unchangedLiveImageIsVerifiedWithoutLoadingSavingOrClosing() throws Exception {
        assertEquals(revision('b'), lifecycle.verifyUnchanged(entry, IMAGE_ID, LIMITS, expected()));
        verify(entry, never()).readImageData();
        verify(entry, never()).saveImageData(any());
        verify(live, never()).close();
    }

    @Test
    void editDuringCalculationRejectsPublication() throws Exception {
        revisions.put(live, revision('c'));
        assertThrows(
                ImageLifecycle.RevisionConflictException.class,
                () -> lifecycle.verifyUnchanged(entry, IMAGE_ID, LIMITS, expected()));
        verify(entry, never()).readImageData();
        verify(entry, never()).saveImageData(any());
    }

    @Test
    void navigationDuringStoredVerificationUsesNewlyVisibleEdits() throws Exception {
        visible.set(null);
        revisions.put(live, revision('c'));
        when(entry.readImageData())
                .thenAnswer(
                        ignored -> {
                            assertFalse(onFx.get());
                            visible.set(live);
                            return stored;
                        });
        var failure =
                assertThrows(
                        ImageLifecycle.RevisionConflictException.class,
                        () -> lifecycle.verifyUnchanged(entry, IMAGE_ID, LIMITS, expected()));
        assertEquals(revision('c'), failure.actual());
        verify(stored).close();
        verify(entry, never()).saveImageData(any());
    }

    @Test
    void changedPixelSourceRejectsUnchangedPublication() throws Exception {
        sourceFailure.set(new IOException("pixels changed"));
        var failure =
                assertThrows(
                        IOException.class,
                        () -> lifecycle.verifyUnchanged(entry, IMAGE_ID, LIMITS, expected()));
        assertEquals("pixels changed", failure.getMessage());
        verify(entry, never()).saveImageData(any());
    }

    private RasterInputCodec.ActionSourceRevision expected() {
        return new RasterInputCodec.ActionSourceRevision(revision('b'), SOURCE);
    }

    private static RasterInputCodec.ImageRevisionToken revision(char digit) {
        return new RasterInputCodec.ImageRevisionToken(
                IMAGE_ID,
                SourceIdentityCodec.imageSourceIdentityDigest(SOURCE),
                String.valueOf(digit).repeat(64));
    }

    private ImageSourceCapture.CapturedSource source() {
        var transform = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        return new ImageSourceCapture.CapturedSource(
                SOURCE,
                0,
                transform,
                List.of(
                        new SourcePlanningInputs.SourceLevel(
                                0, new SourceViewGeometry.Dimensions(100, 80), transform)),
                expected -> {
                    if (sourceFailure.get() != null) throw sourceFailure.get();
                });
    }
}
