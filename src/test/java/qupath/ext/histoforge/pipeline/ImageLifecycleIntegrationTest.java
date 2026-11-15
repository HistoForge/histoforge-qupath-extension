package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.spatial.SourcePlanningInputs;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

class ImageLifecycleIntegrationTest {

    private static final SourceIdentityCodec.ImageSourceIdentity SOURCE_IDENTITY =
            new SourceIdentityCodec.ImageSourceIdentity(
                    "fixture.reader",
                    "a".repeat(64),
                    Optional.empty(),
                    32,
                    24,
                    1,
                    1,
                    new SourceIdentityCodec.PixelCalibration(1, 1));
    private static final String SOURCE_DIGEST =
            SourceIdentityCodec.imageSourceIdentityDigest(SOURCE_IDENTITY);

    @TempDir Path temporaryDirectory;

    @Test
    void separateWorkingImagePersistsThroughPublicQuPathSaveAndReload() throws Exception {
        Path source = temporaryDirectory.resolve("source.png");
        assertTrue(
                ImageIO.write(
                        new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB),
                        "PNG",
                        source.toFile()));
        var project =
                Projects.createProject(
                        Files.createDirectory(temporaryDirectory.resolve("project")).toFile(),
                        BufferedImage.class);
        ProjectImageEntry<BufferedImage> entry;
        try (var server = ImageServers.buildServer(source.toUri())) {
            entry = project.addImage(server.getBuilder());
        }
        assertNotNull(entry);
        try (ImageData<BufferedImage> initial = entry.readImageData()) {
            entry.saveImageData(initial);
        }

        QuPathGUI qupath = mock(QuPathGUI.class);
        when(qupath.getProject()).thenReturn(project);
        when(qupath.getImageData()).thenReturn(null);
        ImageLifecycle lifecycle =
                new ImageLifecycle(
                        qupath,
                        List::of,
                        Runnable::run,
                        ImageDataRevisionDigester::capture,
                        ignored -> capturedSource());

        ImageLifecycle.WorkingImage working =
                lifecycle.openWorkingImage(
                        entry, entry.getID(), PlanningTestData.clientBuild().limits());
        try (ImageData<BufferedImage> independentlyLoaded = entry.readImageData()) {
            assertNotSame(independentlyLoaded, working.imageData());
        }
        working.imageData().setProperty("histoforge.test", "saved-value");

        ImageLifecycle.CommitResult result = lifecycle.commit(working);

        assertTrue(result.viewerRefreshFailure().isEmpty());
        try (ImageData<BufferedImage> reloaded = entry.readImageData()) {
            assertEquals("saved-value", reloaded.getProperty("histoforge.test"));
            assertEquals(
                    result.savedRevision(),
                    ImageDataRevisionDigester.capture(
                            entry.getID(),
                            SOURCE_DIGEST,
                            reloaded,
                            PlanningTestData.clientBuild().limits()));
        }
    }

    private static ImageSourceCapture.CapturedSource capturedSource() {
        var identity = new SourceIdentityCodec.ScaleTranslate2D(1, 1, 0, 0);
        return new ImageSourceCapture.CapturedSource(
                SOURCE_IDENTITY,
                0,
                identity,
                List.of(
                        new SourcePlanningInputs.SourceLevel(
                                0, new SourceViewGeometry.Dimensions(32, 24), identity)),
                expected -> {});
    }
}
