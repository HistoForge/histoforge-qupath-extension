package qupath.ext.histoforge.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;

/** Native project/save fixture with fixed source capture and substituted source publication. */
final class AnnotationRoiWorkflowFixture {
    final Project<BufferedImage> project;
    final ProjectImageEntry<BufferedImage> entry;
    final ImageLifecycle lifecycle;
    final ImageActionRegistry registry = new ImageActionRegistry();
    final AtomicInteger publications = new AtomicInteger();
    final ImageActionExecutor.Request request;
    private final ModelGatewayExecutor gateway;
    private final Path directory;
    private final Geometry ring =
            new WKTReader()
                    .read(
                            "POLYGON ((0 0,100 0,100 100,0 100,0 0),(20 20,20 40,40 40,40 20,20 20))");
    private final Geometry multipart =
            new WKTReader()
                    .read(
                            "MULTIPOLYGON (((128 0,148 0,148 20,128 20,128 0)),((180 60,220 60,220 100,180 100,180 60)))");

    AnnotationRoiWorkflowFixture(Path directory) throws Exception {
        this(directory, mock(ModelGatewayExecutor.class));
    }

    AnnotationRoiWorkflowFixture(Path directory, ModelGatewayExecutor gateway) throws Exception {
        this.directory = directory;
        this.gateway = gateway;
        Path png = directory.resolve("source.png");
        assertTrue(
                ImageIO.write(
                        new BufferedImage(256, 128, BufferedImage.TYPE_INT_RGB),
                        "PNG",
                        png.toFile()));
        project =
                Projects.createProject(
                        Files.createDirectory(directory.resolve("project")).toFile(),
                        BufferedImage.class);
        try (var server = ImageServers.buildServer(png.toUri())) {
            entry = project.addImage(server.getBuilder());
        }
        try (var image = entry.readImageData()) {
            image.updateServerMetadata(
                    new qupath.lib.images.servers.ImageServerMetadata.Builder(
                                    image.getServer().getMetadata())
                            .pixelSizeMicrons(1, 1)
                            .build());
            for (var id : List.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B)) {
                var boundary =
                        PathObjects.createAnnotationObject(
                                GeometryTools.geometryToROI(
                                        id.equals(RecipeExecutorTestData.ROI_A) ? ring : multipart,
                                        ImagePlane.getDefaultPlane()),
                                PathClass.fromString("Parent"));
                boundary.setID(UUID.fromString(id));
                boundary.setName("Boundary " + id);
                boundary.setLocked(false);
                image.getHierarchy().addObject(boundary);
            }
            entry.saveImageData(image);
        }
        var qupath = mock(QuPathGUI.class);
        when(qupath.getProject()).thenReturn(project);
        var pyramid = RecipeExecutorTestData.capturedSourcePyramid();
        lifecycle =
                spy(
                        new ImageLifecycle(
                                qupath,
                                List::of,
                                Runnable::run,
                                ImageDataRevisionDigester::capture,
                                image ->
                                        new ImageSourceCapture.CapturedSource(
                                                pyramid.sourceRevision().pixelSource(),
                                                pyramid.seriesIndex(),
                                                pyramid.levelZeroPixelsToSlide(),
                                                pyramid.levels(),
                                                expected -> {})));
        // Deliberately stale preview: capture must re-resolve both native annotations.
        request =
                new ImageActionExecutor.Request(
                        RecipeExecutorTestData.ACTION_ID,
                        RecipeExecutorTestData.clientBuild(1_000_000),
                        entry,
                        RecipeExecutorTestData.fieldModule(),
                        RecipeExecutorTestData.allModels(
                                false, Set.of(RecipeExecutorTestData.ROI_A)),
                        Optional.of("Parent"));
        // Only source publication/release is substituted, including when gateway is a real spy.
        doAnswer(
                        invocation -> {
                            try (var saved = entry.readImageData()) {
                                assertBoundaries(saved, true);
                            }
                            publications.incrementAndGet();
                            SourceArtifactWriter.PreparedSourceArtifact source =
                                    invocation.getArgument(0);
                            assertTrue(Files.size(source.path()) > 0);
                            var start = source.startRequest();
                            return new SourceArtifactPublicationCodec.SourceArtifactSealed(
                                    SourceArtifactCodec.SCHEMA_VERSION,
                                    new SourceArtifactPublicationCodec.SourceArtifactRef(
                                            start.artifactId(),
                                            start.creationActionId(),
                                            SourceArtifactCodec.startRequestDigest(start),
                                            "2".repeat(64),
                                            "3".repeat(64),
                                            start.container()),
                                    Instant.ofEpochMilli(System.currentTimeMillis()));
                        })
                .when(gateway)
                .publishSource(any(), any(), any());
        doNothing().when(gateway).releaseSource(any());
    }

    ImageActionExecutor executor(RecipeExecutor.InputRunner runner) {
        return new ImageActionExecutor(
                registry,
                lifecycle,
                RecipeExecutorTestData.sourcePlanningLimits(),
                new RecipeExecutor(runner, () -> UUID.randomUUID().toString(), Clock.systemUTC()),
                new FiniteSourceProvisioner(
                        gateway,
                        writer(),
                        () -> UUID.randomUUID().toString(),
                        directory.resolve("sources")));
    }

    ImageActionExecutor executor() {
        return new ImageActionExecutor(
                registry,
                lifecycle,
                gateway,
                writer(),
                RecipeExecutorTestData.sourcePlanningLimits());
    }

    private SourceArtifactWriter writer() {
        return new SourceArtifactWriter(
                request.clientBuild().extension(), 1024, 1, 1L << 30, 1L << 30);
    }

    void assertBoundaries(ImageData<?> image, boolean locked) {
        var rois = RoiRegistry.fromHierarchy(image.getHierarchy());
        for (var id : List.of(RecipeExecutorTestData.ROI_A, RecipeExecutorTestData.ROI_B)) {
            var boundary = rois.findRoi(id).orElseThrow();
            assertEquals(locked, boundary.isLocked());
            assertFalse(RoiRegistry.isAdministrativeRoi(boundary));
            assertEquals("Parent", boundary.getPathClass().toString());
            assertEquals("Boundary " + id, boundary.getName());
            assertTrue(
                    boundary.getROI()
                            .getGeometry()
                            .equalsTopo(
                                    id.equals(RecipeExecutorTestData.ROI_A) ? ring : multipart));
        }
    }
}
