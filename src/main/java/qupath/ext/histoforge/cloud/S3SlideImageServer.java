package qupath.ext.histoforge.cloud;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import qupath.lib.images.servers.AbstractImageServer;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerMetadata.ImageResolutionLevel;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.TileRequestManager;
import qupath.lib.regions.RegionRequest;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Reads a pinned S3 TIFF through range requests. S3TiffPyramid parses and decodes its tiles;
 * QuPath's tileable server assembles requested regions and owns the successful pixel cache.
 */
public class S3SlideImageServer extends AbstractImageServer<BufferedImage> {

    private final URI uri;
    private final String bucket;
    private final String key;
    private final S3ObjectSnapshot objectSnapshot;
    private final ImageServerMetadata originalMetadata;
    private final List<S3TiffPyramid.Level> levels;
    private volatile TileReader tileReader;

    public S3SlideImageServer(URI uri, S3Client s3, String bucket, String key) throws IOException {
        super(BufferedImage.class);
        this.uri = uri;
        this.bucket = bucket;
        this.key = key;
        objectSnapshot = S3ObjectSnapshot.capture(s3, bucket, key);
        S3TiffPyramid pyramid = S3TiffPyramid.read(s3, bucket, key, objectSnapshot);
        levels = pyramid.levels();
        var largest = levels.getFirst();
        int width = Math.toIntExact(largest.width());
        int height = Math.toIntExact(largest.height());
        var resolutions = new ImageResolutionLevel.Builder(width, height);
        levels.forEach(
                level ->
                        resolutions.addLevel(
                                Math.toIntExact(level.width()), Math.toIntExact(level.height())));
        var metadata =
                new ImageServerMetadata.Builder()
                        .width(width)
                        .height(height)
                        .pixelType(PixelType.UINT8)
                        .rgb(true)
                        .channels(ImageChannel.getDefaultRGBChannels())
                        .preferredTileSize(largest.tileWidth(), largest.tileHeight())
                        .levels(resolutions.build());
        if (pyramid.micronsPerPixel() > 0)
            metadata.pixelSizeMicrons(pyramid.micronsPerPixel(), pyramid.micronsPerPixel());
        originalMetadata = metadata.build();
        tileReader = new TileReader();
    }

    @Override
    public BufferedImage readRegion(RegionRequest request) throws IOException {
        TileReader reader = tileReader;
        try {
            return reader.readRegion(request);
        } catch (IOException failure) {
            // QuPath 0.6/0.7 can retain failed prefetched tiles and replay their exceptions on
            // other threads. Discard only that request bookkeeping, not the open slide or its
            // successful cache. The next read can then use renewed credentials.
            synchronized (this) {
                if (tileReader == reader) {
                    tileReader = new TileReader();
                }
            }
            throw failure;
        }
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return originalMetadata;
    }

    @Override
    protected ServerBuilder<BufferedImage> createServerBuilder() {
        return DefaultImageServerBuilder.createInstance(
                S3ImageServerBuilder.class, getMetadata(), uri);
    }

    @Override
    protected String createID() {
        return getClass().getName() + ": " + uri.toString();
    }

    @Override
    public String getServerType() {
        return "S3 Cloud Slide";
    }

    @Override
    public Collection<URI> getURIs() {
        return Collections.singletonList(uri);
    }

    /** Immutable S3 identity shared by TIFF bootstrap, tile reads, and source provenance. */
    public S3ObjectSnapshot getObjectSnapshot() {
        return objectSnapshot;
    }

    /** Require the exact captured object version to remain readable before scientific commit. */
    public void requireCurrentSource() throws IOException {
        AwsCredentialManager manager = AwsCredentialManager.getInstance();
        S3Client s3 = manager == null ? null : manager.getS3Client();
        if (s3 == null) {
            throw new IOException("AWS credentials not available");
        }
        objectSnapshot.requireCurrent(s3, bucket, key);
    }

    /** Native tile assembly with replaceable request state and the same pinned slide bytes. */
    private final class TileReader extends AbstractTileableImageServer {

        @Override
        protected BufferedImage readTile(TileRequest request) throws IOException {
            int levelIndex = request.getLevel();
            if (levelIndex >= 0 && levelIndex < levels.size()) {
                var level = levels.get(levelIndex);
                int index = level.tileIndex(request.getTileX(), request.getTileY());
                if (index >= 0
                        && index < level.offsets().length
                        && level.byteCounts()[index] != 0) {
                    var manager = AwsCredentialManager.getInstance();
                    S3Client client = manager == null ? null : manager.getS3Client();
                    if (client == null) throw new IOException("AWS credentials not available");
                    try {
                        BufferedImage decoded =
                                S3TiffPyramid.decodeTile(
                                        client, bucket, key, objectSnapshot, level, index);
                        if (decoded == null)
                            throw new IOException("Tile bytes could not be decoded as an image");
                        return decoded;
                    } catch (Exception failure) {
                        throw new IOException(
                                "Failed to read tile at level "
                                        + levelIndex
                                        + " from s3://"
                                        + bucket
                                        + "/"
                                        + key
                                        + ": "
                                        + failure.getMessage(),
                                failure);
                    }
                }
            }
            return getEmptyTile(request.getTileWidth(), request.getTileHeight());
        }

        @Override
        public ImageServerMetadata getOriginalMetadata() {
            return S3SlideImageServer.this.getOriginalMetadata();
        }

        @Override
        public ImageServerMetadata getMetadata() {
            return S3SlideImageServer.this.getMetadata();
        }

        @Override
        public TileRequestManager getTileRequestManager() {
            // The outer server keeps the pyramid's spatial index across failed reads.
            return S3SlideImageServer.this.getTileRequestManager();
        }

        @Override
        protected Map<RegionRequest, BufferedImage> getCache() {
            return S3SlideImageServer.this.getCache();
        }

        @Override
        protected String createID() {
            return S3SlideImageServer.this.getPath();
        }

        @Override
        public String getServerType() {
            return S3SlideImageServer.this.getServerType();
        }

        @Override
        public Collection<URI> getURIs() {
            return S3SlideImageServer.this.getURIs();
        }

        @Override
        protected ServerBuilder<BufferedImage> createServerBuilder() {
            return S3SlideImageServer.this.getBuilder();
        }
    }
}
