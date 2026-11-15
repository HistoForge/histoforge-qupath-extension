package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class ImageLifecycleTest {

    private static final String IMAGE_ID = "image-a";
    private static final SourceIdentityCodec.ImageSourceIdentity SOURCE_IDENTITY =
            sourceIdentity("fixture.reader", "a".repeat(64));
    private static final String SOURCE_DIGEST =
            SourceIdentityCodec.imageSourceIdentityDigest(SOURCE_IDENTITY);
    private static final ExecutionLimitsCodec.ClientExecutionLimits LIMITS =
            new ExecutionLimitsCodec.ClientExecutionLimits(10, 10, 10, 10, 10, 10, 10, 10);

    private final QuPathGUI qupath = mock(QuPathGUI.class);
    private final Project<BufferedImage> project = mock(Project.class);
    private final ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
    private final ProjectImageEntry<BufferedImage> otherEntry = mock(ProjectImageEntry.class);
    private final QuPathViewer viewer = mock(QuPathViewer.class);
    private final QuPathViewer otherViewer = mock(QuPathViewer.class);
    private final ImageData<BufferedImage> live = imageData();
    private final ImageData<BufferedImage> otherLive = imageData();
    private final ImageData<BufferedImage> working = imageData();
    private final ImageData<BufferedImage> stored = imageData();
    private final AtomicReference<ImageData<BufferedImage>> active = new AtomicReference<>();
    private final AtomicReference<List<QuPathViewer>> openViewers =
            new AtomicReference<>(List.of(viewer));
    private final AtomicBoolean insideFxBoundary = new AtomicBoolean();
    private final Map<ImageData<?>, RasterInputCodec.ImageRevisionToken> revisions =
            new IdentityHashMap<>();
    private final AtomicInteger fxCalls = new AtomicInteger();
    private final AtomicReference<SourceIdentityCodec.ImageSourceIdentity> sourceIdentity =
            new AtomicReference<>(SOURCE_IDENTITY);
    private final AtomicReference<IOException> sourceFailure = new AtomicReference<>();
    private Runnable sourceVerificationObserver = () -> {};

    private ImageLifecycle lifecycle;

    @BeforeEach
    void setUp() throws Exception {
        when(entry.getID()).thenReturn(IMAGE_ID);
        when(otherEntry.getID()).thenReturn("image-b");
        when(qupath.getProject()).thenReturn(project);
        when(qupath.getImageData()).thenAnswer(ignored -> active.get());
        when(qupath.getViewer()).thenReturn(viewer);
        when(viewer.getImageData()).thenAnswer(ignored -> active.get());
        when(otherViewer.getImageData()).thenReturn(otherLive);
        doAnswer(
                        invocation -> {
                            assertTrue(insideFxBoundary.get());
                            return null;
                        })
                .when(entry)
                .saveImageData(any());
        when(project.getEntry(any(ImageData.class)))
                .thenAnswer(
                        invocation -> invocation.getArgument(0) == otherLive ? otherEntry : entry);
        revisions.put(live, token('b'));
        revisions.put(otherLive, token('c'));
        revisions.put(working, token('b'));
        revisions.put(stored, token('b'));
        lifecycle =
                new ImageLifecycle(
                        qupath,
                        openViewers::get,
                        runnable -> {
                            fxCalls.incrementAndGet();
                            assertTrue(insideFxBoundary.compareAndSet(false, true));
                            try {
                                runnable.run();
                            } finally {
                                insideFxBoundary.set(false);
                            }
                        },
                        (imageId, sourceDigest, imageData, limits) -> {
                            assertEquals(IMAGE_ID, imageId);
                            assertSame(LIMITS, limits);
                            RasterInputCodec.ImageRevisionToken revision = revisions.get(imageData);
                            if (revision == null) {
                                throw new IllegalStateException("unexpected ImageData");
                            }
                            return new RasterInputCodec.ImageRevisionToken(
                                    imageId, sourceDigest, revision.imageDataContentDigest());
                        },
                        ignored -> capturedSource(sourceIdentity.get(), sourceFailure));
    }

    @Test
    void liveTargetIsSavedBeforeSeparateWorkingImageIsRead() throws Exception {
        active.set(live);
        when(entry.readImageData())
                .thenAnswer(
                        ignored -> {
                            assertFalse(insideFxBoundary.get());
                            return working;
                        });

        ImageLifecycle.WorkingImage opened = open();

        assertSame(working, opened.imageData());
        assertEquals(token('b'), opened.baseRevision());
        var order = inOrder(entry);
        order.verify(entry).saveImageData(live);
        order.verify(entry).readImageData();
        assertEquals(1, fxCalls.get());
        opened.close();
        verify(working).close();
    }

    @Test
    void explicitViewerSaveUsesTheLifecycleFxBoundary() throws Exception {
        active.set(live);

        lifecycle.saveCurrentViewerImage(entry, live);

        verify(entry).saveImageData(live);
        assertEquals(1, fxCalls.get());
    }

    @Test
    void entriesWithoutStoragePathsCannotMatchByLocalIdAlone() throws Exception {
        when(otherEntry.getID()).thenReturn(IMAGE_ID);
        when(entry.readImageData()).thenReturn(working);
        active.set(otherLive);

        try (var opened = open()) {
            assertSame(working, opened.imageData());
        }

        verify(entry, never()).saveImageData(any());
        verify(otherLive, never()).close();
    }

    @Test
    void viewerReplacementTransfersOwnershipAndClosesTheDisplacedImage() throws Exception {
        active.set(live);
        doAnswer(
                        invocation -> {
                            assertTrue(insideFxBoundary.get());
                            active.set(invocation.getArgument(0));
                            return null;
                        })
                .when(viewer)
                .setImageData(working);

        Optional<Throwable> cleanupFailure = lifecycle.replaceCurrentViewerImage(working);

        assertTrue(cleanupFailure.isEmpty());
        assertSame(working, active.get());
        verify(working, never()).close();
        verify(live).close();
        assertEquals(1, fxCalls.get());
    }

    @Test
    void failedViewerReplacementClosesTheUnadoptedCandidate() throws Exception {
        active.set(live);
        doThrow(new IOException("display failed")).when(viewer).setImageData(working);

        IOException failure =
                assertThrows(IOException.class, () -> lifecycle.replaceCurrentViewerImage(working));

        assertEquals("display failed", failure.getMessage());
        assertSame(live, active.get());
        verify(working).close();
        verify(live, never()).close();
    }

    @Test
    void failedViewerReplacementRestoresTheImageQuPathDisplaced() throws Exception {
        active.set(live);
        doAnswer(
                        invocation -> {
                            ImageData<BufferedImage> requested = invocation.getArgument(0);
                            if (requested == working) {
                                active.set(null);
                                throw new IOException("display failed");
                            }
                            active.set(requested);
                            return null;
                        })
                .when(viewer)
                .setImageData(any());

        IOException failure =
                assertThrows(IOException.class, () -> lifecycle.replaceCurrentViewerImage(working));

        assertEquals("display failed", failure.getMessage());
        assertSame(live, active.get());
        verify(viewer).setImageData(live);
        verify(working).close();
        verify(live, never()).close();
    }

    @Test
    void viewerReplacementReportsOldImageCleanupWithoutClosingTheAdoptedCandidate()
            throws Exception {
        active.set(live);
        doAnswer(
                        invocation -> {
                            active.set(invocation.getArgument(0));
                            return null;
                        })
                .when(viewer)
                .setImageData(working);
        doThrow(new IOException("old close failed")).when(live).close();

        Optional<Throwable> cleanupFailure = lifecycle.replaceCurrentViewerImage(working);

        assertEquals("old close failed", cleanupFailure.orElseThrow().getMessage());
        assertSame(working, active.get());
        verify(working, never()).close();
    }

    @Test
    void storedRecoveryCaptureIgnoresUnsavedViewerState() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(stored);

        var capture =
                lifecycle.captureStoredReadOnly(
                        entry,
                        IMAGE_ID,
                        LIMITS,
                        (imageData, ignored, ignoredSource) -> {
                            assertSame(stored, imageData);
                            return "stored";
                        });

        assertEquals(
                ImageLifecycle.ImageDataMaterialization.STORED_QPDATA, capture.materialization());
        assertEquals("stored", capture.value());
        assertEquals(0, fxCalls.get());
        verify(entry, never()).saveImageData(any());
        verify(stored).close();
    }

    @Test
    void explicitViewerSaveRejectsAStaleImagePair() throws Exception {
        active.set(live);

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> lifecycle.saveCurrentViewerImage(entry, working));

        assertEquals(
                "entry and ImageData are no longer the current viewer target", error.getMessage());
        verify(entry, never()).saveImageData(any());
        assertEquals(1, fxCalls.get());
    }

    @Test
    void readOnlyCaptureUsesTheLiveViewerWithoutSavingOrClosingIt() throws Exception {
        active.set(live);

        ImageLifecycle.ReadOnlyImageCapture<String> captured =
                lifecycle.captureReadOnly(
                        entry,
                        IMAGE_ID,
                        LIMITS,
                        (imageData, sourceRevision, ignoredSource) -> {
                            assertTrue(insideFxBoundary.get());
                            assertSame(live, imageData);
                            assertEquals(token('b'), sourceRevision.imageDataRevision());
                            return "detached";
                        });

        assertEquals(
                ImageLifecycle.ImageDataMaterialization.LIVE_VIEWER, captured.materialization());
        assertEquals(token('b'), captured.sourceRevision().imageDataRevision());
        assertEquals("detached", captured.value());
        verify(entry, never()).saveImageData(any());
        verify(entry, never()).readImageData();
        verify(live, never()).close();
        assertEquals(2, fxCalls.get());
    }

    @Test
    void readOnlyCaptureLoadsAndClosesStoredImageWithoutSaving() throws Exception {
        active.set(otherLive);
        when(entry.readImageData()).thenReturn(stored);

        ImageLifecycle.ReadOnlyImageCapture<String> captured =
                lifecycle.captureReadOnly(
                        entry,
                        IMAGE_ID,
                        LIMITS,
                        (imageData, sourceRevision, ignoredSource) -> {
                            assertFalse(insideFxBoundary.get());
                            assertSame(stored, imageData);
                            assertEquals(token('b'), sourceRevision.imageDataRevision());
                            return "stored-detached";
                        });

        assertEquals(
                ImageLifecycle.ImageDataMaterialization.STORED_QPDATA, captured.materialization());
        assertEquals("stored-detached", captured.value());
        verify(entry, never()).saveImageData(any());
        verify(stored).close();
        verify(otherLive, never()).close();
        assertEquals(1, fxCalls.get());
    }

    @Test
    void liveCaptureChecksSourceAfterTheCallbackOffFxAndPropagatesVerificationFailure()
            throws Exception {
        active.set(live);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger verifications = new AtomicInteger();
        sourceVerificationObserver =
                () -> {
                    assertFalse(insideFxBoundary.get());
                    assertEquals(callbacks.get(), verifications.incrementAndGet());
                };
        ImageLifecycle.ReadOnlyCaptureOperation<String> capture =
                (imageData, revision, source) -> {
                    assertTrue(insideFxBoundary.get());
                    assertSame(live, imageData);
                    callbacks.incrementAndGet();
                    return "detached";
                };

        assertEquals(
                "detached", lifecycle.captureReadOnly(entry, IMAGE_ID, LIMITS, capture).value());
        IOException failure = new IOException("source changed after live capture");
        sourceFailure.set(failure);
        assertSame(
                failure,
                assertThrows(
                        IOException.class,
                        () -> lifecycle.captureReadOnly(entry, IMAGE_ID, LIMITS, capture)));

        assertEquals(2, callbacks.get());
        assertEquals(2, verifications.get());
        verify(entry, never()).saveImageData(any());
        verify(live, never()).close();
    }

    @Test
    void readOnlyStoredCaptureFailureClosesTheTemporaryImage() throws Exception {
        active.set(otherLive);
        when(entry.readImageData()).thenReturn(stored);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                lifecycle.captureReadOnly(
                                        entry,
                                        IMAGE_ID,
                                        LIMITS,
                                        (imageData, sourceRevision, ignoredSource) -> {
                                            throw new IllegalArgumentException("capture failed");
                                        }));

        assertEquals("capture failed", error.getMessage());
        verify(entry, never()).saveImageData(any());
        verify(stored).close();
    }

    @Test
    void readOnlyCaptureFailsWhenPixelSourceChangesBeforeReturn() throws Exception {
        active.set(otherLive);
        when(entry.readImageData()).thenReturn(stored);
        sourceFailure.set(new IOException("source changed"));

        IOException error =
                assertThrows(
                        IOException.class,
                        () ->
                                lifecycle.captureReadOnly(
                                        entry,
                                        IMAGE_ID,
                                        LIMITS,
                                        (imageData, sourceRevision, ignoredSource) -> "discarded"));

        assertEquals("source changed", error.getMessage());
        verify(entry, never()).saveImageData(any());
        verify(stored).close();
    }

    @Test
    void storedSourceSessionMatchesCapturedPixelsWithoutSavingViewerState() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(stored);

        try (var session =
                lifecycle.openStoredSourceSession(
                        entry,
                        IMAGE_ID,
                        new RasterInputCodec.ActionSourceRevision(token('b'), SOURCE_IDENTITY))) {
            assertSame(stored.getServer(), session.server());
            assertEquals(SOURCE_IDENTITY, session.sourceRevision().pixelSource());
            assertEquals(session.sourceRevision(), session.sourcePyramid().sourceRevision());
            assertTrue(session.eligibleOriginalSource().isEmpty());
            assertTrue(session.unchangedOriginalPath().isEmpty());
        }

        verify(entry, never()).saveImageData(any());
        verify(stored).close();
        assertEquals(0, fxCalls.get());
    }

    @Test
    void storedSourceSessionRejectsDifferentPixelsAndClosesItsImage() throws Exception {
        when(entry.readImageData()).thenReturn(stored);
        SourceIdentityCodec.ImageSourceIdentity expected =
                sourceIdentity("fixture.other-reader", "f".repeat(64));

        IOException error =
                assertThrows(
                        IOException.class,
                        () ->
                                lifecycle.openStoredSourceSession(
                                        entry,
                                        IMAGE_ID,
                                        new RasterInputCodec.ActionSourceRevision(
                                                token('b', expected), expected)));

        assertEquals("stored pixel source differs from the training capture", error.getMessage());
        verify(stored).close();
    }

    @Test
    void openBindsTheCapturedPixelSourceAndReaderPyramid() throws Exception {
        when(entry.readImageData()).thenReturn(working);

        try (var opened = open()) {
            assertEquals(SOURCE_IDENTITY, opened.sourceRevision().pixelSource());
            assertEquals(opened.baseRevision(), opened.sourceRevision().imageDataRevision());
            assertEquals(opened.sourceRevision(), opened.sourcePyramid().sourceRevision());
            assertEquals(1, opened.sourcePyramid().levels().size());
        }
    }

    @Test
    void sourceVersionFailurePreventsSaveAndViewerReplacement() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        sourceFailure.set(new IOException("source changed"));

        IOException error = assertThrows(IOException.class, () -> lifecycle.commit(opened));

        assertEquals("source changed", error.getMessage());
        verify(entry, times(1)).saveImageData(any());
        verify(viewer, never()).setImageData(any());
        verify(working).close();
    }

    @Test
    void authoritativeSourceSubstitutionIsARevisionConflict() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        SourceIdentityCodec.ImageSourceIdentity replacement =
                sourceIdentity("fixture.other-reader", "b".repeat(64));
        sourceIdentity.set(replacement);

        ImageLifecycle.RevisionConflictException error =
                assertThrows(
                        ImageLifecycle.RevisionConflictException.class,
                        () -> lifecycle.commit(opened));

        assertTrue(error.getMessage().contains("authoritative ImageData changed"));
        assertFalse(
                error.expected().imageSourceDigest().equals(error.actual().imageSourceDigest()));
        verify(entry, times(1)).saveImageData(any());
        verify(viewer, never()).setImageData(any());
        verify(working).close();
    }

    @Test
    void failedLiveEditSynchronizationDoesNotOpenAWorkingImage() throws Exception {
        active.set(live);
        doAnswer(
                        ignored -> {
                            assertTrue(insideFxBoundary.get());
                            throw new IOException("live save failed");
                        })
                .when(entry)
                .saveImageData(live);

        IOException error = assertThrows(IOException.class, this::open);

        assertEquals("live save failed", error.getMessage());
        verify(entry, never()).readImageData();
    }

    @Test
    void storedTargetLoadsWithoutSavingAnUnrelatedViewer() throws Exception {
        active.set(otherLive);
        when(entry.readImageData()).thenReturn(working);

        try (var opened = open()) {
            assertSame(working, opened.imageData());
        }

        verify(entry, never()).saveImageData(any());
        verify(otherLive, never()).close();
    }

    @Test
    void revisionConflictClosesWorkingImageWithoutSaveOrViewerReplacement() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.put(live, token('d'));
        revisions.put(working, token('e'));

        ImageLifecycle.RevisionConflictException error =
                assertThrows(
                        ImageLifecycle.RevisionConflictException.class,
                        () -> lifecycle.commit(opened));

        assertEquals(token('b'), error.expected());
        assertEquals(token('d'), error.actual());
        verify(entry, times(1)).saveImageData(any());
        verify(viewer, never()).setImageData(any());
        verify(working).close();
    }

    @Test
    void unchangedActionRevalidatesAndClosesWithoutAnotherSave() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();

        RasterInputCodec.ImageRevisionToken result = lifecycle.finishUnchanged(opened);

        assertEquals(token('b'), result);
        verify(entry, times(1)).saveImageData(any());
        verify(viewer, never()).setImageData(any());
        verify(working).close();
    }

    @Test
    void noChangeFinalizationRejectsAnUnexpectedWorkingMutation() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.put(working, token('e'));

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> lifecycle.finishUnchanged(opened));

        assertEquals("working ImageData changed during a no-change action", error.getMessage());
        verify(entry, times(1)).saveImageData(any());
        verify(viewer, never()).setImageData(any());
        verify(working).close();
    }

    @Test
    void noChangeFinalizationRejectsAConcurrentAuthoritativeEdit() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.put(live, token('d'));

        ImageLifecycle.RevisionConflictException error =
                assertThrows(
                        ImageLifecycle.RevisionConflictException.class,
                        () -> lifecycle.finishUnchanged(opened));

        assertEquals(token('b'), error.expected());
        assertEquals(token('d'), error.actual());
        verify(entry, times(1)).saveImageData(any());
        verify(viewer, never()).setImageData(any());
        verify(working).close();
    }

    @Test
    void cleanupFailureDoesNotHideTheRevisionConflict() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.put(live, token('d'));
        doThrow(new IOException("close failed")).when(working).close();

        ImageLifecycle.RevisionConflictException error =
                assertThrows(
                        ImageLifecycle.RevisionConflictException.class,
                        () -> lifecycle.commit(opened));

        assertEquals(token('d'), error.actual());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("failed to close working ImageData", error.getSuppressed()[0].getMessage());
    }

    @Test
    void invalidWorkingStateFailsBeforeSaveOrViewerReplacement() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.remove(working);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> lifecycle.commit(opened));

        assertEquals("unexpected ImageData", error.getMessage());
        verify(entry, times(1)).saveImageData(any());
        verify(viewer, never()).setImageData(any());
        verify(working).close();
    }

    @Test
    void targetThatBecomesLiveDuringStoredCheckUsesTheLiveRevision() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        when(entry.readImageData())
                .thenAnswer(
                        ignored -> {
                            assertFalse(insideFxBoundary.get());
                            if (reads.getAndIncrement() == 0) {
                                return working;
                            }
                            active.set(live);
                            return stored;
                        });
        var opened = open();
        revisions.put(live, token('d'));

        ImageLifecycle.RevisionConflictException error =
                assertThrows(
                        ImageLifecycle.RevisionConflictException.class,
                        () -> lifecycle.commit(opened));

        assertEquals(token('b'), error.expected());
        assertEquals(token('d'), error.actual());
        verify(entry, never()).saveImageData(any());
        verify(viewer, never()).setImageData(any());
        verify(stored).close();
        verify(working).close();
    }

    @Test
    void postSaveRevisionPrecedesViewerReplacementAndAdoptionTransfersOwnership() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.put(working, token('e'));
        doAnswer(
                        invocation -> {
                            assertTrue(insideFxBoundary.get());
                            if (invocation.getArgument(0) == working) {
                                revisions.put(working, token('f'));
                            }
                            return null;
                        })
                .when(entry)
                .saveImageData(any());
        doAnswer(
                        invocation -> {
                            assertTrue(insideFxBoundary.get());
                            active.set(invocation.getArgument(0));
                            return null;
                        })
                .when(viewer)
                .setImageData(working);

        ImageLifecycle.CommitResult result = lifecycle.commit(opened);

        assertEquals(token('f'), result.savedRevision());
        assertTrue(result.viewerWasTarget());
        assertTrue(result.viewerAdoptedWorkingImage());
        assertTrue(result.viewerRefreshFailure().isEmpty());
        var order = inOrder(entry, viewer);
        order.verify(entry).saveImageData(live);
        order.verify(entry).saveImageData(working);
        order.verify(viewer).setImageData(working);
        verify(working, never()).close();
        verify(live).close();
        opened.close();
        verify(working, never()).close();
        assertThrows(IllegalStateException.class, opened::imageData);
    }

    @Test
    void inactiveTargetViewerSuppliesEditsAndReceivesTheCommittedWorkingImage() throws Exception {
        active.set(live);
        when(qupath.getViewer()).thenReturn(otherViewer);
        when(qupath.getImageData()).thenReturn(otherLive);
        openViewers.set(List.of(otherViewer, viewer));
        when(entry.readImageData()).thenReturn(working);
        revisions.put(live, token('d'));
        doAnswer(
                        invocation -> {
                            assertTrue(insideFxBoundary.get());
                            if (invocation.getArgument(0) == live) {
                                revisions.put(working, token('d'));
                            }
                            return null;
                        })
                .when(entry)
                .saveImageData(any());

        var opened = open();
        assertEquals(token('d'), opened.baseRevision());
        revisions.put(working, token('e'));
        doAnswer(
                        invocation -> {
                            assertTrue(insideFxBoundary.get());
                            active.set(working);
                            return null;
                        })
                .when(viewer)
                .setImageData(working);

        ImageLifecycle.CommitResult result = lifecycle.commit(opened);

        assertTrue(result.viewerWasTarget());
        assertTrue(result.viewerAdoptedWorkingImage());
        assertSame(otherLive, otherViewer.getImageData());
        verify(otherViewer, never()).setImageData(any());
        verify(live).close();
        verify(working, never()).close();
    }

    @Test
    void multipleLiveMaterializationsFailBeforeProjectIo() throws Exception {
        QuPathViewer duplicateViewer = mock(QuPathViewer.class);
        when(duplicateViewer.getImageData()).thenReturn(stored);
        openViewers.set(List.of(viewer, duplicateViewer));
        active.set(live);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::open);

        assertEquals("project entry is open in more than one QuPath viewer", error.getMessage());
        verify(entry, never()).saveImageData(any());
        verify(entry, never()).readImageData();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void navigationPreservesOtherViewerAndClosesDetachedWorkingImage(boolean sameLocalId)
            throws Exception {
        active.set(live);
        AtomicInteger reads = new AtomicInteger();
        when(entry.readImageData())
                .thenAnswer(
                        ignored -> {
                            assertFalse(insideFxBoundary.get());
                            return reads.getAndIncrement() == 0 ? working : stored;
                        });
        var opened = open();
        revisions.put(working, token('e'));
        if (sameLocalId) {
            when(otherEntry.getID()).thenReturn(IMAGE_ID);
            // Equal scientific state does not establish project-entry ownership.
            revisions.put(otherLive, token('b'));
        }
        active.set(otherLive);

        ImageLifecycle.CommitResult result = lifecycle.commit(opened);

        assertFalse(result.viewerWasTarget());
        assertFalse(result.viewerAdoptedWorkingImage());
        assertTrue(result.viewerRefreshFailure().isEmpty());
        assertSame(otherLive, active.get());
        verify(viewer, never()).setImageData(any());
        verify(working).close();
        verify(stored).close();
    }

    @Test
    void cleanupFailureAfterSaveIsReturnedWithoutErasingPersistenceTruth() throws Exception {
        active.set(otherLive);
        AtomicInteger reads = new AtomicInteger();
        when(entry.readImageData())
                .thenAnswer(ignored -> reads.getAndIncrement() == 0 ? working : stored);
        var opened = open();
        revisions.put(working, token('e'));
        doThrow(new IOException("close failed")).when(working).close();

        ImageLifecycle.CommitResult result = lifecycle.commit(opened);

        assertEquals(token('e'), result.savedRevision());
        assertFalse(result.viewerWasTarget());
        assertFalse(result.viewerAdoptedWorkingImage());
        assertEquals(
                "failed to close working ImageData",
                result.viewerRefreshFailure().orElseThrow().getMessage());
        verify(entry).saveImageData(working);
        verify(viewer, never()).setImageData(any());
        verify(stored).close();
    }

    @Test
    void thrownSaveNeverAttemptsViewerReplacementAndClosesWorkingImage() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.put(working, token('e'));
        doAnswer(
                        invocation -> {
                            assertTrue(insideFxBoundary.get());
                            if (invocation.getArgument(0) == working) {
                                throw new IOException("save failed");
                            }
                            return null;
                        })
                .when(entry)
                .saveImageData(any());

        IOException error = assertThrows(IOException.class, () -> lifecycle.commit(opened));

        assertEquals("save failed", error.getMessage());
        verify(viewer, never()).setImageData(any());
        verify(working).close();
        assertSame(live, active.get());
    }

    @Test
    void viewerRefreshFailureAfterSaveClosesCopyWhenViewerDidNotAdoptIt() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.put(working, token('e'));
        doThrow(new IOException("display failed")).when(viewer).setImageData(working);

        ImageLifecycle.CommitResult result = lifecycle.commit(opened);

        assertTrue(result.viewerWasTarget());
        assertFalse(result.viewerAdoptedWorkingImage());
        assertEquals("display failed", result.viewerRefreshFailure().orElseThrow().getMessage());
        verify(working).close();
        verify(live, never()).close();
        assertSame(live, active.get());
    }

    @Test
    void unsatisfiedLinkErrorAfterPartialAdoptionStillTransfersOwnership() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.put(working, token('e'));
        doAnswer(
                        invocation -> {
                            assertTrue(insideFxBoundary.get());
                            active.set(working);
                            throw new UnsatisfiedLinkError("display failed after adoption");
                        })
                .when(viewer)
                .setImageData(working);

        ImageLifecycle.CommitResult result = lifecycle.commit(opened);

        assertTrue(result.viewerWasTarget());
        assertTrue(result.viewerAdoptedWorkingImage());
        assertTrue(result.viewerRefreshFailure().isPresent());
        assertTrue(result.viewerRefreshFailure().orElseThrow() instanceof UnsatisfiedLinkError);
        verify(working, never()).close();
        verify(live).close();
    }

    @Test
    void fatalErrorAfterPartialAdoptionIsRethrownAfterOwnershipTransfer() throws Exception {
        active.set(live);
        when(entry.readImageData()).thenReturn(working);
        var opened = open();
        revisions.put(working, token('e'));
        doAnswer(
                        invocation -> {
                            assertTrue(insideFxBoundary.get());
                            active.set(working);
                            throw new AssertionError("fatal display failure after adoption");
                        })
                .when(viewer)
                .setImageData(working);

        AssertionError error = assertThrows(AssertionError.class, () -> lifecycle.commit(opened));

        assertEquals("fatal display failure after adoption", error.getMessage());
        assertSame(working, active.get());
        verify(working, never()).close();
        verify(live).close();
    }

    @Test
    void wrongProjectIdentityFailsBeforeQuPathIo() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> lifecycle.openWorkingImage(entry, "other", LIMITS));
        verify(entry, never()).readImageData();
    }

    private ImageLifecycle.WorkingImage open() throws IOException {
        return lifecycle.openWorkingImage(entry, IMAGE_ID, LIMITS);
    }

    private static RasterInputCodec.ImageRevisionToken token(char contentDigit) {
        return token(contentDigit, SOURCE_IDENTITY);
    }

    private static RasterInputCodec.ImageRevisionToken token(
            char contentDigit, SourceIdentityCodec.ImageSourceIdentity identity) {
        return new RasterInputCodec.ImageRevisionToken(
                IMAGE_ID,
                SourceIdentityCodec.imageSourceIdentityDigest(identity),
                String.valueOf(contentDigit).repeat(64));
    }

    private ImageSourceCapture.CapturedSource capturedSource(
            SourceIdentityCodec.ImageSourceIdentity identity,
            AtomicReference<IOException> failure) {
        var identityTransform = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        return new ImageSourceCapture.CapturedSource(
                identity,
                0,
                identityTransform,
                List.of(
                        new SourcePlanningInputs.SourceLevel(
                                0,
                                new SourceViewGeometry.Dimensions(
                                        identity.width(), identity.height()),
                                identityTransform)),
                expected -> {
                    sourceVerificationObserver.run();
                    IOException error = failure.get();
                    if (error != null) {
                        throw error;
                    }
                });
    }

    private static SourceIdentityCodec.ImageSourceIdentity sourceIdentity(
            String adapterId, String descriptorDigest) {
        return new SourceIdentityCodec.ImageSourceIdentity(
                adapterId,
                descriptorDigest,
                Optional.empty(),
                100,
                80,
                1,
                1,
                new SourceIdentityCodec.PixelCalibration(0.25, 0.25));
    }

    @SuppressWarnings("unchecked")
    private static ImageData<BufferedImage> imageData() {
        return mock(ImageData.class);
    }
}
