package qupath.ext.histoforge.spatial;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.bioformats.BioFormatsImageServer;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

/**
 * Verify every saved TIFF tile against the RGB bytes supplied to its writer.
 *
 * <p>Only tile requests and SHA-256 digests survive encoding; no source images are retained or read
 * again. Recording failures remain sticky because QuPath's OME writer can suppress worker errors.
 */
final class TiffRgbReadback {

    private final int width;
    private final int height;
    private final Map<RegionRequest, byte[]> digests = new LinkedHashMap<>();
    private IOException recordingFailure;

    TiffRgbReadback(ImageServer<BufferedImage> view) {
        width = view.getWidth();
        height = view.getHeight();
        for (TileRequest tile : view.getTileRequestManager().getTileRequestsForLevel(0)) {
            digests.put(tile.getRegionRequest(), null);
        }
    }

    void record(RegionRequest request, BufferedImage image) throws IOException {
        try {
            byte[] digest = rgbDigest(request, image);
            synchronized (this) {
                if (!digests.containsKey(request)) {
                    throw new IOException("TIFF writer requested an unexpected tile");
                }
                if (digests.get(request) != null) {
                    throw new IOException("TIFF writer requested the same tile twice");
                }
                digests.put(request, digest);
            }
        } catch (IOException error) {
            recordFailure(error);
            throw error;
        }
    }

    synchronized void recordFailure(Exception error) {
        if (recordingFailure == null) {
            recordingFailure = new IOException("derived TIFF source recording failed", error);
        }
    }

    private synchronized Map<RegionRequest, byte[]> completeDigests() throws IOException {
        if (recordingFailure != null) {
            throw recordingFailure;
        }
        if (digests.isEmpty() || digests.containsValue(null)) {
            throw new IOException("TIFF writer did not read every expected tile");
        }
        return new LinkedHashMap<>(digests);
    }

    void requireExactCopy(Path artifact) throws IOException {
        Map<RegionRequest, byte[]> expected = completeDigests();
        try (var copy = new BioFormatsImageServer(artifact.toUri())) {
            if (copy.getWidth() != width
                    || copy.getHeight() != height
                    || copy.nResolutions() != 1
                    || copy.nZSlices() != 1
                    || copy.nTimepoints() != 1
                    || !copy.isRGB()
                    || copy.getPixelType() != PixelType.UINT8
                    || copy.nChannels() != 3) {
                throw new IOException("derived TIFF does not reopen as one exact uint8 RGB image");
            }
            for (var entry : expected.entrySet()) {
                RegionRequest request = entry.getKey();
                var tile =
                        TileRequest.createInstance(
                                copy,
                                0,
                                ImageRegion.createInstance(
                                        request.getX(),
                                        request.getY(),
                                        request.getWidth(),
                                        request.getHeight(),
                                        0,
                                        0));
                // Public native-tile reads bypass QuPath's shared viewer cache.
                BufferedImage actual = copy.readTile(tile);
                if (!MessageDigest.isEqual(entry.getValue(), rgbDigest(request, actual))) {
                    throw new IOException(
                            "derived TIFF pixels differ at tile "
                                    + request.getX()
                                    + ","
                                    + request.getY());
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("failed to read back derived TIFF", error);
        }
    }

    private static byte[] rgbDigest(RegionRequest request, BufferedImage image) throws IOException {
        int width = request.getWidth();
        int height = request.getHeight();
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            throw new IOException("derived TIFF returned an incomplete decoded tile");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        int[] pixels = new int[width];
        byte[] rgb = new byte[Math.multiplyExact(width, 3)];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, pixels, 0, width);
            for (int x = 0; x < width; x++) {
                rgb[3 * x] = (byte) (pixels[x] >>> 16);
                rgb[3 * x + 1] = (byte) (pixels[x] >>> 8);
                rgb[3 * x + 2] = (byte) pixels[x];
            }
            digest.update(rgb);
        }
        return digest.digest();
    }
}
