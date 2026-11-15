package qupath.ext.histoforge.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import qupath.ext.histoforge.roi.RoiManager;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;

class ThumbnailRendererContractTest {
    @Test
    void overlayUsesFullImageCoordinatesAndNeverChangesCachedPixels() {
        var base = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        int[] before = base.getRGB(0, 0, 100, 80, null, 0, 100);
        assertSame(base, ThumbnailRenderer.overlay(base, List.of(), 1000, 800));
        var result =
                ThumbnailRenderer.overlay(
                        base,
                        List.of(new ThumbnailRenderer.RoiOverlay(200, 200, 400, 300)),
                        1000,
                        800);
        assertNotSame(base, result);
        assertArrayEquals(before, base.getRGB(0, 0, 100, 80, null, 0, 100));
        assertEquals(0xff000000 | RoiManager.HISTOFORGE_ROI_COLOR, result.getRGB(30, 20));
        assertEquals(0xff000000, result.getRGB(30, 30));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cachedThumbnailDoesNotOpenPixelServer() throws Exception {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        ServerBuilder<BufferedImage> builder = mock(ServerBuilder.class);
        when(entry.getThumbnail())
                .thenReturn(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB));
        when(entry.getServerBuilder()).thenReturn(builder);
        when(builder.getMetadata())
                .thenReturn(
                        Optional.of(
                                new ImageServerMetadata.Builder().width(200).height(200).build()));
        assertNotNull(
                ThumbnailRenderer.generateWsiThumbnail(
                        entry, List.<ThumbnailRenderer.RoiOverlay>of()));
        verify(builder, never()).build();
        verify(entry, never()).setThumbnail(any());
    }

    @ParameterizedTest
    @CsvSource({"4096,3072,2", "1024,768,1", "2048,4096,2"})
    @SuppressWarnings("unchecked")
    void missingThumbnailReadsFullImageCachesBaseAndClosesServer(
            int width, int height, double downsample) throws Exception {
        ProjectImageEntry<BufferedImage> entry = mock(ProjectImageEntry.class);
        ServerBuilder<BufferedImage> builder = mock(ServerBuilder.class);
        ImageServer<BufferedImage> server = mock(ImageServer.class);
        when(entry.getServerBuilder()).thenReturn(builder);
        when(builder.build()).thenReturn(server);
        when(builder.getMetadata())
                .thenReturn(
                        Optional.of(
                                new ImageServerMetadata.Builder()
                                        .width(width)
                                        .height(height)
                                        .build()));
        when(server.getWidth()).thenReturn(width);
        when(server.getHeight()).thenReturn(height);
        when(server.getPath()).thenReturn("synthetic-thumbnail-source");
        var base = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        when(server.readRegion(any(RegionRequest.class))).thenReturn(base);

        var result =
                ThumbnailRenderer.generateWsiThumbnail(
                        entry,
                        List.of(new ThumbnailRenderer.RoiOverlay(0, 0, width / 2.0, height / 2.0)));

        assertNotNull(result);
        var request = ArgumentCaptor.forClass(RegionRequest.class);
        verify(server).readRegion(request.capture());
        assertEquals("synthetic-thumbnail-source", request.getValue().getPath());
        assertEquals(downsample, request.getValue().getDownsample());
        assertEquals(0, request.getValue().getX());
        assertEquals(0, request.getValue().getY());
        assertEquals(width, request.getValue().getWidth());
        assertEquals(height, request.getValue().getHeight());
        verify(entry).setThumbnail(same(base));
        verify(server).close();
        assertEquals(0xff000000, base.getRGB(16, 0));
        assertEquals(
                0xff000000 | RoiManager.HISTOFORGE_ROI_COLOR,
                result.getPixelReader().getArgb(16, 0));
    }
}
