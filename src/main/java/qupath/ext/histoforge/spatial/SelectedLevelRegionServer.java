package qupath.ext.histoforge.spatial;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.regions.RegionRequest;

/**
 * Exact one-level RGB view over a rectangle of one native QuPath pyramid level.
 *
 * <p>{@code RegionRequest} cannot identify arbitrary native-level pixel edges when pyramid
 * dimensions are rounded. This server instead reads the source server's own {@link TileRequest}s,
 * whose level-local coordinates are exact, and exposes the selected rectangle at downsample 1 for
 * QuPath's public image writers. It never interpolates or closes the caller-owned source server.
 */
final class SelectedLevelRegionServer extends AbstractTileableImageServer {

    private static final int OUTPUT_TILE_SIZE = 512;

    private final ImageServer<BufferedImage> source;
    private final int sourceLevel;
    private final SourceIdentityCodec.ImagePlaneIndex sourcePlane;
    private final SourceIdentityCodec.IntRect sourceRect;
    private final NavigableMap<Integer, List<TileRequest>> sourceTileRows;
    private final ImageServerMetadata metadata;
    private TiffRgbReadback tiffReadback;

    SelectedLevelRegionServer(
            ImageServer<BufferedImage> source,
            int sourceLevel,
            SourceViewGeometry.Dimensions sourceLevelDimensions,
            SourceIdentityCodec.ImagePlaneIndex sourcePlane,
            SourceIdentityCodec.IntRect sourceRect,
            SourceIdentityCodec.PixelCalibration selectedLevelCalibration)
            throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        this.sourceLevel = sourceLevel;
        this.sourcePlane = Objects.requireNonNull(sourcePlane, "sourcePlane");
        this.sourceRect = Objects.requireNonNull(sourceRect, "sourceRect");
        Objects.requireNonNull(sourceLevelDimensions, "sourceLevelDimensions");
        Objects.requireNonNull(selectedLevelCalibration, "selectedLevelCalibration");

        requireSupportedSource(source, sourceLevel, sourceLevelDimensions, sourcePlane, sourceRect);
        this.sourceTileRows = sourceTileRows(source, sourceLevel, sourcePlane, sourceRect);
        requireCompleteCoverage(sourceTileRows, sourceRect);
        this.metadata =
                new ImageServerMetadata.Builder()
                        .width(sourceRect.width())
                        .height(sourceRect.height())
                        .sizeZ(1)
                        .sizeT(1)
                        .pixelType(PixelType.UINT8)
                        .channels(ImageChannel.getDefaultRGBChannels())
                        .rgb(true)
                        .pixelSizeMicrons(
                                selectedLevelCalibration.mppX(), selectedLevelCalibration.mppY())
                        .preferredTileSize(OUTPUT_TILE_SIZE, OUTPUT_TILE_SIZE)
                        .levelsFromDownsamples(1.0)
                        .name("HistoForge exact source-level region")
                        .build();
    }

    /** Begin recording the exact RGB tiles supplied to the TIFF writer. */
    TiffRgbReadback recordTiffRgb() {
        if (tiffReadback != null) {
            throw new IllegalStateException("TIFF recording already started");
        }
        tiffReadback = new TiffRgbReadback(this);
        return tiffReadback;
    }

    @Override
    protected Map<RegionRequest, BufferedImage> getCache() {
        // This disposable export view must not occupy the viewer's shared cache.
        // Reads through the caller-owned source retain its ordinary cache policy.
        return null;
    }

    @Override
    public BufferedImage readRegion(RegionRequest request) throws IOException {
        try {
            BufferedImage image = super.readRegion(request);
            if (tiffReadback != null) {
                tiffReadback.record(request, image);
            }
            return image;
        } catch (IOException | RuntimeException error) {
            if (tiffReadback != null) {
                tiffReadback.recordFailure(error);
            }
            throw error;
        }
    }

    @Override
    protected BufferedImage readTile(TileRequest outputTile) throws IOException {
        if (outputTile.getLevel() != 0 || outputTile.getZ() != 0 || outputTile.getT() != 0) {
            throw new IOException("derived source server received an unsupported tile request");
        }
        int sourceX = Math.addExact(sourceRect.x(), outputTile.getTileX());
        int sourceY = Math.addExact(sourceRect.y(), outputTile.getTileY());
        int sourceRight = Math.addExact(sourceX, outputTile.getTileWidth());
        int sourceBottom = Math.addExact(sourceY, outputTile.getTileHeight());
        BufferedImage result =
                new BufferedImage(
                        outputTile.getTileWidth(),
                        outputTile.getTileHeight(),
                        BufferedImage.TYPE_INT_RGB);

        long coveredPixels = 0;
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            var firstRow = sourceTileRows.floorEntry(sourceY);
            if (firstRow == null) {
                firstRow = sourceTileRows.ceilingEntry(sourceY);
            }
            if (firstRow == null) {
                throw new IOException("source tile manager does not cover the requested region");
            }
            for (var row : sourceTileRows.tailMap(firstRow.getKey(), true).entrySet()) {
                if (row.getKey() >= sourceBottom) {
                    break;
                }
                for (TileRequest sourceTile : intersectingTiles(row.getValue(), sourceX)) {
                    if (sourceTile.getTileX() >= sourceRight) {
                        break;
                    }
                    int left = Math.max(sourceX, sourceTile.getTileX());
                    int top = Math.max(sourceY, sourceTile.getTileY());
                    int right =
                            Math.min(
                                    sourceRight,
                                    Math.addExact(
                                            sourceTile.getTileX(), sourceTile.getTileWidth()));
                    int bottom =
                            Math.min(
                                    sourceBottom,
                                    Math.addExact(
                                            sourceTile.getTileY(), sourceTile.getTileHeight()));
                    if (left >= right || top >= bottom) {
                        continue;
                    }

                    BufferedImage sourceImage = source.readRegion(sourceTile.getRegionRequest());
                    if (sourceImage == null
                            || sourceImage.getWidth() != sourceTile.getTileWidth()
                            || sourceImage.getHeight() != sourceTile.getTileHeight()) {
                        throw new IOException(
                                "source server did not return the exact requested native tile");
                    }
                    int destinationLeft = left - sourceX;
                    int destinationTop = top - sourceY;
                    int sourceLeft = left - sourceTile.getTileX();
                    int sourceTop = top - sourceTile.getTileY();
                    int width = right - left;
                    int height = bottom - top;
                    boolean drawn =
                            graphics.drawImage(
                                    sourceImage,
                                    destinationLeft,
                                    destinationTop,
                                    destinationLeft + width,
                                    destinationTop + height,
                                    sourceLeft,
                                    sourceTop,
                                    sourceLeft + width,
                                    sourceTop + height,
                                    null);
                    if (!drawn) {
                        throw new IOException(
                                "source tile pixels were not available synchronously");
                    }
                    coveredPixels =
                            Math.addExact(coveredPixels, Math.multiplyExact((long) width, height));
                }
            }
        } finally {
            graphics.dispose();
        }
        long expectedPixels =
                Math.multiplyExact((long) outputTile.getTileWidth(), outputTile.getTileHeight());
        if (coveredPixels != expectedPixels) {
            throw new IOException(
                    "source tile manager does not exactly cover the requested region");
        }
        return result;
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }

    @Override
    protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
        return null;
    }

    @Override
    protected String createID() {
        return getClass().getName() + ":" + source.getPath() + ":" + sourceLevel + ":" + sourceRect;
    }

    @Override
    public String getServerType() {
        return "HistoForge exact native-level region";
    }

    @Override
    public Collection<URI> getURIs() {
        return source.getURIs();
    }

    private static void requireSupportedSource(
            ImageServer<BufferedImage> source,
            int sourceLevel,
            SourceViewGeometry.Dimensions expectedDimensions,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.IntRect rectangle) {
        if (!source.isRGB()
                || source.getPixelType() != PixelType.UINT8
                || source.nChannels() != 3) {
            throw new IllegalArgumentException("derived source capture requires uint8 RGB pixels");
        }
        if (sourceLevel < 0 || sourceLevel >= source.nResolutions()) {
            throw new IllegalArgumentException("source resolution level is unavailable");
        }
        var level = source.getMetadata().getLevel(sourceLevel);
        if (level.getWidth() != expectedDimensions.width()
                || level.getHeight() != expectedDimensions.height()) {
            throw new IllegalArgumentException(
                    "source resolution dimensions do not match the captured plan");
        }
        if (plane.z() >= source.nZSlices() || plane.t() >= source.nTimepoints()) {
            throw new IllegalArgumentException("source plane is unavailable");
        }
        if ((long) rectangle.x() + rectangle.width() > expectedDimensions.width()
                || (long) rectangle.y() + rectangle.height() > expectedDimensions.height()) {
            throw new IllegalArgumentException("source rectangle lies outside the selected level");
        }
    }

    private static NavigableMap<Integer, List<TileRequest>> sourceTileRows(
            ImageServer<BufferedImage> source,
            int sourceLevel,
            SourceIdentityCodec.ImagePlaneIndex plane,
            SourceIdentityCodec.IntRect rectangle) {
        NavigableMap<Integer, List<TileRequest>> rows = new TreeMap<>();
        for (TileRequest tile :
                source.getTileRequestManager().getTileRequestsForLevel(sourceLevel)) {
            if (tile.getZ() == plane.z()
                    && tile.getT() == plane.t()
                    && intersects(tile, rectangle)) {
                rows.computeIfAbsent(tile.getTileY(), ignored -> new ArrayList<>()).add(tile);
            }
        }
        for (List<TileRequest> row : rows.values()) {
            row.sort(Comparator.comparingInt(TileRequest::getTileX));
        }
        NavigableMap<Integer, List<TileRequest>> immutable = new TreeMap<>();
        rows.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return java.util.Collections.unmodifiableNavigableMap(immutable);
    }

    private static void requireCompleteCoverage(
            NavigableMap<Integer, List<TileRequest>> rows, SourceIdentityCodec.IntRect rectangle)
            throws IOException {
        long covered = 0;
        for (List<TileRequest> row : rows.values()) {
            for (TileRequest tile : row) {
                int width =
                        Math.min(
                                        Math.addExact(rectangle.x(), rectangle.width()),
                                        Math.addExact(tile.getTileX(), tile.getTileWidth()))
                                - Math.max(rectangle.x(), tile.getTileX());
                int height =
                        Math.min(
                                        Math.addExact(rectangle.y(), rectangle.height()),
                                        Math.addExact(tile.getTileY(), tile.getTileHeight()))
                                - Math.max(rectangle.y(), tile.getTileY());
                if (width > 0 && height > 0) {
                    covered = Math.addExact(covered, Math.multiplyExact((long) width, height));
                }
            }
        }
        long expected = Math.multiplyExact((long) rectangle.width(), rectangle.height());
        if (covered != expected) {
            throw new IOException("source tile manager does not exactly cover the selected region");
        }
    }

    private static List<TileRequest> intersectingTiles(List<TileRequest> row, int sourceX) {
        int low = 0;
        int high = row.size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            TileRequest tile = row.get(middle);
            if ((long) tile.getTileX() + tile.getTileWidth() <= sourceX) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return row.subList(low, row.size());
    }

    private static boolean intersects(TileRequest tile, SourceIdentityCodec.IntRect rectangle) {
        return tile.getTileX() < (long) rectangle.x() + rectangle.width()
                && (long) tile.getTileX() + tile.getTileWidth() > rectangle.x()
                && tile.getTileY() < (long) rectangle.y() + rectangle.height()
                && (long) tile.getTileY() + tile.getTileHeight() > rectangle.y();
    }
}
