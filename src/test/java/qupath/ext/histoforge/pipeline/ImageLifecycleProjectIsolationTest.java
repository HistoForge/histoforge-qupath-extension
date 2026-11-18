package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.spatial.SourcePlanningInputs;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

/** Real temporary QuPath entries deliberately collide on their project-local numeric ID. */
class ImageLifecycleProjectIsolationTest {
    @TempDir Path directory;

    private final QuPathGUI qupath = mock(QuPathGUI.class);
    private final QuPathViewer viewer = mock(QuPathViewer.class);
    private final AtomicReference<ImageData<BufferedImage>> visible = new AtomicReference<>();
    private ImageLifecycle lifecycle;
    private Fixture requested;
    private Path source;

    @BeforeEach
    void setUp() throws Exception {
        source = image("source", 0x112233);
        requested = project("requested", source);
        when(qupath.getProject()).thenReturn(requested.project());
        when(viewer.getImageData()).thenAnswer(ignored -> visible.get());
        lifecycle =
                new ImageLifecycle(
                        qupath,
                        () -> List.of(viewer),
                        Runnable::run,
                        ImageDataRevisionDigester::capture,
                        ignored -> capturedSource());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openingAfterProjectSwitchNeverSavesTheForeignViewer(boolean sameSource) throws Exception {
        Fixture foreign = project("foreign", sameSource ? source : image("other", 0x445566));
        assertEquals(requested.entry().getID(), foreign.entry().getID());
        byte[] requestedBefore = savedBytes(requested);
        byte[] foreignBefore = savedBytes(foreign);
        var requestedUris = List.copyOf(requested.entry().getURIs());
        try (var live = foreign.entry().readImageData()) {
            show(foreign.project(), live);
            live.setProperty("unsaved", "foreign edits");
            try (var working = open()) {
                assertEquals("requested", working.imageData().getProperty("fixture.project"));
                assertEquals(requestedUris, List.copyOf(working.imageData().getServer().getURIs()));
            }
            assertSame(live, visible.get());
            assertSame(foreign.entry(), foreign.project().getEntry(live));
        }
        assertArrayEquals(requestedBefore, savedBytes(requested));
        assertArrayEquals(foreignBefore, savedBytes(foreign));
        verify(viewer, never()).setImageData(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"read-only", "saved-live", "saved-stored"})
    void calculationAndTrainingCaptureIgnoreTheForeignViewer(String mode) throws Exception {
        Fixture foreign = project("foreign", source);
        byte[] requestedBefore = savedBytes(requested);
        byte[] foreignBefore = savedBytes(foreign);
        try (var live = foreign.entry().readImageData()) {
            show(foreign.project(), live);
            ImageLifecycle.ReadOnlyCaptureOperation<Object> operation =
                    (data, revision, capturedSource) -> data.getProperty("fixture.project");
            var captured =
                    mode.equals("read-only")
                            ? lifecycle.captureReadOnly(
                                    requested.entry(),
                                    requested.entry().getID(),
                                    limits(),
                                    operation)
                            : lifecycle.captureSavedImage(
                                    requested.entry(),
                                    requested.entry().getID(),
                                    limits(),
                                    data -> mode.equals("saved-live"),
                                    operation);
            assertEquals("requested", captured.value());
            assertEquals(
                    ImageLifecycle.ImageDataMaterialization.STORED_QPDATA,
                    captured.materialization());
            assertSame(live, visible.get());
        }
        assertArrayEquals(requestedBefore, savedBytes(requested));
        assertArrayEquals(foreignBefore, savedBytes(foreign));
    }

    @Test
    void explicitSaveRejectsAnEntryFromAnotherProjectBeforeWriting() throws Exception {
        Fixture foreign = project("foreign", source);
        byte[] before = savedBytes(requested);
        try (var live = foreign.entry().readImageData()) {
            show(foreign.project(), live);
            assertThrows(
                    IllegalStateException.class,
                    () -> lifecycle.saveCurrentViewerImage(requested.entry(), live));
        }
        assertArrayEquals(before, savedBytes(requested));
    }

    @Test
    void switchBeforeCommitSavesOnlyTheRequestedEntryAndDoesNotNavigate() throws Exception {
        Fixture foreign = project("foreign", source);
        byte[] foreignBefore = savedBytes(foreign);
        try (var working = open();
                var live = foreign.entry().readImageData()) {
            working.imageData().setProperty("committed", "requested result");
            show(foreign.project(), live);
            var result = lifecycle.commit(working);
            assertFalse(result.viewerWasTarget());
            assertFalse(result.viewerAdoptedWorkingImage());
            assertTrue(result.viewerRefreshFailure().isEmpty());
            assertSame(live, visible.get());
            var reopened =
                    ProjectIO.loadProject(
                            requested.project().getPath().toFile(), BufferedImage.class);
            try (var saved = reopened.getImageList().getFirst().readImageData()) {
                assertEquals("requested result", saved.getProperty("committed"));
                assertEquals(
                        result.savedRevision(),
                        ImageDataRevisionDigester.capture(
                                requested.entry().getID(),
                                result.savedRevision().imageSourceDigest(),
                                saved,
                                limits()));
            }
        }
        assertArrayEquals(foreignBefore, savedBytes(foreign));
        verify(viewer, never()).setImageData(any());
    }

    @Test
    void noChangeCompletionUsesRequestedStoredRevisionAfterProjectSwitch() throws Exception {
        Fixture foreign = project("foreign", source);
        byte[] requestedBefore = savedBytes(requested);
        byte[] foreignBefore = savedBytes(foreign);
        try (var working = open();
                var live = foreign.entry().readImageData()) {
            show(foreign.project(), live);
            assertEquals(working.baseRevision(), lifecycle.finishUnchanged(working));
            assertEquals(
                    working.baseRevision(),
                    lifecycle.verifyUnchanged(
                            requested.entry(),
                            requested.entry().getID(),
                            limits(),
                            working.sourceRevision()));
        }
        assertArrayEquals(requestedBefore, savedBytes(requested));
        assertArrayEquals(foreignBefore, savedBytes(foreign));
    }

    @Test
    void reopenedSameProjectEntryStillUsesUnsavedLiveState() throws Exception {
        var reopened =
                ProjectIO.loadProject(requested.project().getPath().toFile(), BufferedImage.class);
        var reloadedEntry = reopened.getImageList().getFirst();
        assertNotSame(requested.entry(), reloadedEntry);
        assertEquals(requested.entry().getEntryPath(), reloadedEntry.getEntryPath());
        byte[] before = savedBytes(requested);
        try (var live = reloadedEntry.readImageData()) {
            show(reopened, live);
            live.setProperty("unsaved", "same project edits");
            var captured =
                    lifecycle.captureReadOnly(
                            requested.entry(),
                            requested.entry().getID(),
                            limits(),
                            (data, revision, prepared) -> data.getProperty("unsaved"));
            assertEquals(
                    ImageLifecycle.ImageDataMaterialization.LIVE_VIEWER,
                    captured.materialization());
            assertEquals("same project edits", captured.value());
        }
        assertArrayEquals(before, savedBytes(requested));
    }

    @Test
    void trainingReopensSavedPixelsAndCalibrationWithoutReadingAnnotationsAgain() throws Exception {
        try (var data = requested.entry().readImageData()) {
            var server = data.getServer();
            server.setMetadata(
                    new ImageServerMetadata.Builder(server.getMetadata())
                            .pixelSizeMicrons(0.25, 0.5)
                            .build());
            requested.entry().saveImageData(data);
        }
        requested.project().syncChanges();
        byte[] before = savedBytes(requested);
        var reopened =
                ProjectIO.loadProject(requested.project().getPath().toFile(), BufferedImage.class);
        var entry = spy(reopened.getImageList().getFirst());
        int annotations =
                lifecycle.readProjectAnnotations(
                        entry, () -> {}, data -> data.getHierarchy().getAllObjects(false).size());
        assertEquals(1, annotations);
        var captured =
                lifecycle.captureReadOnly(
                        entry,
                        entry.getID(),
                        limits(),
                        (data, revision, prepared) -> data.getServer().getPixelCalibration());
        assertEquals(0.25, captured.value().getPixelWidthMicrons());
        assertEquals(0.5, captured.value().getPixelHeightMicrons());

        try (var session =
                lifecycle.openStoredSourceSession(
                        entry, entry.getID(), captured.sourceRevision())) {
            var server = session.server();
            assertEquals(captured.value(), server.getPixelCalibration());
            assertEquals(
                    0x112233,
                    server.readRegion(RegionRequest.createInstance(server.getPath(), 1, 0, 0, 1, 1))
                                    .getRGB(0, 0)
                            & 0xFFFFFF);
        }
        verify(entry, times(2)).readImageData();
        assertArrayEquals(before, savedBytes(requested));
    }

    private void show(Project<BufferedImage> project, ImageData<BufferedImage> data) {
        when(qupath.getProject()).thenReturn(project);
        visible.set(data);
    }

    private ImageLifecycle.WorkingImage open() throws Exception {
        return lifecycle.openWorkingImage(requested.entry(), requested.entry().getID(), limits());
    }

    private static qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits
            limits() {
        return PlanningTestData.clientBuild().limits();
    }

    private Path image(String name, int rgb) throws Exception {
        Path path = directory.resolve(name + ".png");
        var image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb);
        assertTrue(ImageIO.write(image, "PNG", path.toFile()));
        return path;
    }

    private Fixture project(String name, Path pixels) throws Exception {
        var project =
                Projects.createProject(
                        Files.createDirectory(directory.resolve(name)).toFile(),
                        BufferedImage.class);
        ProjectImageEntry<BufferedImage> entry;
        try (var server = ImageServers.buildServer(pixels.toUri())) {
            entry = project.addImage(server.getBuilder());
        }
        try (var data = entry.readImageData()) {
            data.setProperty("fixture.project", name);
            var object =
                    PathObjects.createDetectionObject(
                            ROIs.createRectangleROI(3, 4, 5, 6, ImagePlane.getDefaultPlane()),
                            PathClass.fromString("Cell"));
            object.getMeasurementList().put("fixture measurement", 30.0);
            data.getHierarchy().addObject(object);
            entry.saveImageData(data);
        }
        project.syncChanges();
        return new Fixture(project, entry);
    }

    private static byte[] savedBytes(Fixture fixture) throws Exception {
        return Files.readAllBytes(fixture.entry().getEntryPath().resolve("data.qpdata"));
    }

    private static ImageSourceCapture.CapturedSource capturedSource() {
        var identity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        "fixture.reader",
                        "a".repeat(64),
                        Optional.empty(),
                        32,
                        24,
                        1,
                        1,
                        new SourceIdentityCodec.PixelCalibration(1, 1));
        var transform = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        return new ImageSourceCapture.CapturedSource(
                identity,
                0,
                transform,
                List.of(
                        new SourcePlanningInputs.SourceLevel(
                                0, new SourceViewGeometry.Dimensions(32, 24), transform)),
                expected -> {});
    }

    private record Fixture(
            Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry) {}
}
