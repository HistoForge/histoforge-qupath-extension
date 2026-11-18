package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.regions.RegionRequest;

class TiffRgbReadbackTest {

    @TempDir Path directory;

    @Test
    void completeReadbackIncludesEdgeTilesWithoutReadingSourceAgain() throws Exception {
        try (var source = SourceArtifactWriterTestData.sourceServer();
                var view = view(source, 1, 600, 550)) {
            var readback = view.recordTiffRgb();
            Path output = directory.resolve("exact.ome.tif");
            write(view, output);
            int reads = source.regionReads();
            assertTrue(reads > 0);
            readback.requireExactCopy(output);
            assertEquals(reads, source.regionReads());
            assertEquals(4, view.getTileRequestManager().getTileRequestsForLevel(0).size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "unexpected", "wrong-size"})
    void recordingFailuresRemainVisibleEvenIfWriterSuppressesThem(String failure) throws Exception {
        try (var source = SourceArtifactWriterTestData.sourceServer();
                var view = view(source, 1, 600, 550)) {
            var readback = new TiffRgbReadback(view);
            var tiles = new ArrayList<>(view.getTileRequestManager().getTileRequestsForLevel(0));
            RegionRequest first = tiles.getFirst().getRegionRequest();
            BufferedImage image = view.readRegion(first);
            for (var tile : tiles) {
                readback.record(tile.getRegionRequest(), view.readRegion(tile.getRegionRequest()));
            }
            assertThrows(
                    IOException.class,
                    () -> {
                        switch (failure) {
                            case "duplicate" -> readback.record(first, image);
                            case "unexpected" ->
                                    readback.record(
                                            RegionRequest.createInstance(
                                                    "another-server",
                                                    1.0,
                                                    first.getX(),
                                                    first.getY(),
                                                    first.getWidth(),
                                                    first.getHeight(),
                                                    0,
                                                    0),
                                            image);
                            case "wrong-size" ->
                                    readback.record(
                                            first,
                                            new BufferedImage(
                                                    first.getWidth() - 1,
                                                    first.getHeight(),
                                                    BufferedImage.TYPE_INT_RGB));
                            default -> throw new AssertionError(failure);
                        }
                    });
            IOException error =
                    assertThrows(
                            IOException.class,
                            () -> readback.requireExactCopy(directory.resolve("not-opened.tif")));
            assertTrue(error.getMessage().contains("recording failed"));
        }
    }

    @Test
    void missingTileFailsBeforeReadback() throws Exception {
        try (var source = SourceArtifactWriterTestData.sourceServer();
                var view = view(source, 1, 600, 550)) {
            var readback = new TiffRgbReadback(view);
            var first = view.getTileRequestManager().getTileRequestsForLevel(0).iterator().next();
            readback.record(first.getRegionRequest(), view.readRegion(first.getRegionRequest()));
            IOException error =
                    assertThrows(
                            IOException.class,
                            () -> readback.requireExactCopy(directory.resolve("not-opened.tif")));
            assertTrue(error.getMessage().contains("every expected tile"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedOrCoordinateSwappedPixelsFail(boolean swapTiles) throws Exception {
        try (var source = SourceArtifactWriterTestData.sourceServer();
                var view = view(source, 0, 1024, 1024)) {
            Path output = directory.resolve("unchanged.ome.tif");
            write(view, output);
            var readback = new TiffRgbReadback(view);
            var tiles = new ArrayList<>(view.getTileRequestManager().getTileRequestsForLevel(0));
            for (int i = 0; i < tiles.size(); i++) {
                RegionRequest request = tiles.get(i).getRegionRequest();
                int from = swapTiles && i < 2 ? 1 - i : i;
                BufferedImage image = view.readRegion(tiles.get(from).getRegionRequest());
                if (!swapTiles && i == 0) {
                    image.setRGB(0, 0, image.getRGB(0, 0) ^ 1);
                }
                readback.record(request, image);
            }
            IOException error =
                    assertThrows(IOException.class, () -> readback.requireExactCopy(output));
            assertTrue(error.getMessage().contains("pixels differ"));
        }
    }

    @Test
    void truncatedOutputFailsFullReadback() throws Exception {
        try (var source = SourceArtifactWriterTestData.sourceServer();
                var view = view(source, 1, 600, 550)) {
            var readback = view.recordTiffRgb();
            Path output = directory.resolve("truncated.ome.tif");
            write(view, output);
            try (var file = FileChannel.open(output, StandardOpenOption.WRITE)) {
                file.truncate(file.size() / 2);
            }
            assertThrows(IOException.class, () -> readback.requireExactCopy(output));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disposableViewsDoNotUseOrClearTheViewerCacheOrCloseSource(boolean fail) throws Exception {
        var previousCache = ImageServerProvider.getCache(BufferedImage.class);
        var cache = new ConcurrentHashMap<RegionRequest, BufferedImage>();
        ImageServerProvider.setCache(cache, BufferedImage.class);
        try (var source = SourceArtifactWriterTestData.sourceServer()) {
            var sourceTile =
                    source.getTileRequestManager().getTileRequestsForLevel(1).iterator().next();
            source.readRegion(sourceTile.getRegionRequest());
            BufferedImage retained = cache.get(sourceTile.getRegionRequest());
            assertNotNull(retained);
            try (var view = view(source, 1, 600, 550)) {
                var readback = view.recordTiffRgb();
                Path output = directory.resolve("cache.ome.tif");
                write(view, output);
                if (fail) {
                    readback.recordFailure(new IOException("injected suppressed worker error"));
                    assertThrows(IOException.class, () -> readback.requireExactCopy(output));
                } else {
                    readback.requireExactCopy(output);
                }
                assertTrue(
                        cache.keySet().stream()
                                .allMatch(key -> key.getPath().equals(source.getPath())));
                assertSame(retained, cache.get(sourceTile.getRegionRequest()));
            }
            assertFalse(source.closed());
            assertNotNull(source.readRegion(sourceTile.getRegionRequest()));
            assertSame(cache, ImageServerProvider.getCache(BufferedImage.class));
        } finally {
            ImageServerProvider.setCache(previousCache, BufferedImage.class);
        }
    }

    private static SelectedLevelRegionServer view(
            SourceArtifactWriterTestData.DeterministicPyramidServer source,
            int level,
            int width,
            int height)
            throws IOException {
        var resolution = source.getMetadata().getLevel(level);
        return new SelectedLevelRegionServer(
                source,
                level,
                new SourceViewGeometry.Dimensions(resolution.getWidth(), resolution.getHeight()),
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                new SourceIdentityCodec.IntRect(0, 0, width, height),
                new SourceIdentityCodec.PixelCalibration(
                        level == 0 ? 0.25 : 0.5, level == 0 ? 0.25 : 0.75));
    }

    private static void write(SelectedLevelRegionServer view, Path path) throws Exception {
        TileRequest first =
                view.getTileRequestManager().getTileRequestsForLevel(0).iterator().next();
        var series =
                new OMEPyramidWriter.Builder(view)
                        .downsamples(1.0)
                        .tileSize(first.getTileWidth(), first.getTileHeight())
                        .compression(OMEPyramidWriter.CompressionType.ZLIB)
                        .channelsInterleaved()
                        .bigTiff()
                        .zSlice(0)
                        .timePoint(0)
                        .parallelize(4)
                        .build();
        OMEPyramidWriter.createWriter(series).writeImage(path.toString());
    }
}
