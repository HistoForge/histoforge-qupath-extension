package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.regions.RegionRequest;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Real TIFF directories and JPEG tiles, with only the S3 transport replaced. */
class S3TiffPixelsTest {
    private AwsCredentialManager previousManager;
    private Map<RegionRequest, BufferedImage> previousCache;
    private AwsCredentialManager manager;

    @BeforeEach
    void isolateHostState() {
        previousManager = AwsCredentialManager.getInstance();
        previousCache = ImageServerProvider.getCache(BufferedImage.class);
        manager = mock(AwsCredentialManager.class);
        AwsCredentialManager.setInstance(manager);
        ImageServerProvider.setCache(new ConcurrentHashMap<>(), BufferedImage.class);
    }

    @AfterEach
    void restoreHostState() {
        AwsCredentialManager.setInstance(previousManager);
        ImageServerProvider.setCache(previousCache, BufferedImage.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void readsExactPixelsAtEveryLevelAndKeepsCalibration(int unit) throws Exception {
        // Deliberately not in pyramid order. Calibration belongs to the first directory.
        var fixture = tiff(unit, 32, 64, 16);
        S3Client s3 = client(fixture.bytes());
        when(manager.getS3Client()).thenReturn(s3);
        try (var server =
                new S3SlideImageServer(
                        URI.create("histoforge-cloud://slides/" + UUID.randomUUID()),
                        s3,
                        "slides",
                        "test.tif")) {
            assertEquals(64, server.getWidth());
            assertEquals(3, server.nResolutions());
            for (int index : new int[] {1, 0, 2}) {
                BufferedImage expected = fixture.images().get(index);
                double downsample = 64.0 / expected.getWidth();
                var request =
                        RegionRequest.createInstance(server.getPath(), downsample, 0, 0, 64, 64);
                assertPixels(expected, server.readRegion(request));
                var tiles = server.getTileRequestManager().getTileRequests(request);
                for (var tile : tiles) {
                    assertPixels(
                            expected.getSubimage(tile.getTileX(), tile.getTileY(), 16, 16),
                            server.readRegion(tile.getRegionRequest()));
                }
            }
            var calibration = server.getPixelCalibration();
            if (unit == 1) assertFalse(calibration.hasPixelSizeMicrons());
            else assertEquals(unit == 2 ? 2.54 : 1.0, calibration.getPixelWidthMicrons(), 1e-12);
            assertEquals("histoforge-cloud", server.getURIs().iterator().next().getScheme());
        }
        assertPixels(
                fixture.images().get(2), new S3ThumbnailFetcher().fetch(s3, "slides", "test.tif"));
    }

    @Test
    void thumbnailStitchesTheSmallestDirectoryAndResizesOnlyAboveHeightLimit() throws Exception {
        var fixture = tiff(3, 64, 32);
        assertPixels(
                fixture.images().get(1),
                new S3ThumbnailFetcher().fetch(client(fixture.bytes()), "slides", "test.tif"));
        var large = tiff(3, 320, 160);
        BufferedImage thumbnail =
                new S3ThumbnailFetcher().fetch(client(large.bytes()), "slides", "test.tif");
        assertEquals(120, thumbnail.getWidth());
        assertEquals(120, thumbnail.getHeight());
        BufferedImage expected = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        var graphics = expected.createGraphics();
        try {
            graphics.drawImage(large.images().get(1), 0, 0, 120, 120, null);
        } finally {
            graphics.dispose();
        }
        assertPixels(expected, thumbnail);
    }

    @Test
    void parserFailureDoesNotProduceAThumbnail() {
        assertNull(new S3ThumbnailFetcher().fetch(client(new byte[64]), "slides", "test.tif"));
    }

    private static void assertPixels(BufferedImage expected, BufferedImage actual) {
        assertNotNull(actual);
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        int w = expected.getWidth(), h = expected.getHeight();
        assertArrayEquals(
                expected.getRGB(0, 0, w, h, null, 0, w), actual.getRGB(0, 0, w, h, null, 0, w));
    }

    private static S3Client client(byte[] bytes) {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(
                        HeadObjectResponse.builder()
                                .contentLength((long) bytes.length)
                                .versionId("fixed-version")
                                .build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenAnswer(
                        call -> {
                            GetObjectRequest request = call.getArgument(0);
                            assertEquals("fixed-version", request.versionId());
                            assertEquals("slides", request.bucket());
                            assertEquals("test.tif", request.key());
                            String[] endpoints =
                                    request.range().substring("bytes=".length()).split("-");
                            int start = Integer.parseInt(endpoints[0]),
                                    end = Integer.parseInt(endpoints[1]);
                            assertTrue(start >= 0 && end >= start && end < bytes.length);
                            return ResponseBytes.fromByteArray(
                                    GetObjectResponse.builder().build(),
                                    Arrays.copyOfRange(bytes, start, end + 1));
                        });
        return s3;
    }

    private record Fixture(byte[] bytes, List<BufferedImage> images) {}

    /** Classic little-endian TIFF, with full JPEG codestreams as used by the cloud reader. */
    private static Fixture tiff(int unit, int... sizes) throws Exception {
        var bytes = ByteBuffer.allocate(4 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        var images = new ArrayList<BufferedImage>();
        int previousNext = -1;
        for (int size : sizes) {
            if (previousNext >= 0) bytes.putInt(previousNext, bytes.position());
            int count = (size / 16) * (size / 16);
            int directory = bytes.position(), tags = 13;
            bytes.putShort((short) tags);
            bytes.position(directory + 2 + 12 * tags);
            previousNext = bytes.position();
            bytes.putInt(0);
            int offsets = bytes.position();
            bytes.position(offsets + 4 * count);
            int lengths = bytes.position();
            bytes.position(lengths + 4 * count);
            int bits = bytes.position();
            bytes.putShort((short) 8).putShort((short) 8).putShort((short) 8);
            int resolution = bytes.position();
            bytes.putInt(10_000).putInt(1);
            BufferedImage expected = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            var graphics = expected.createGraphics();
            try {
                for (int i = 0; i < count; i++) {
                    BufferedImage tile = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < 16; y++)
                        for (int x = 0; x < 16; x++)
                            tile.setRGB(
                                    x,
                                    y,
                                    ((i * 31 + x * 7) & 255) << 16
                                            | ((size + y * 13) & 255) << 8
                                            | ((i + x + y) & 255));
                    var jpeg = new ByteArrayOutputStream();
                    assertTrue(ImageIO.write(tile, "JPEG", jpeg));
                    byte[] encoded = jpeg.toByteArray();
                    bytes.putInt(offsets + i * 4, bytes.position());
                    bytes.putInt(lengths + i * 4, encoded.length);
                    bytes.put(encoded);
                    graphics.drawImage(
                            ImageIO.read(new ByteArrayInputStream(encoded)),
                            (i % (size / 16)) * 16,
                            (i / (size / 16)) * 16,
                            null);
                }
            } finally {
                graphics.dispose();
            }
            int end = bytes.position();
            bytes.position(directory + 2);
            tag(bytes, 256, 4, 1, size);
            tag(bytes, 257, 4, 1, size);
            tag(bytes, 258, 3, 3, bits);
            tag(bytes, 259, 3, 1, 7);
            tag(bytes, 262, 3, 1, 6);
            tag(bytes, 277, 3, 1, 3);
            tag(bytes, 282, 5, 1, resolution);
            tag(bytes, 284, 3, 1, 1);
            tag(bytes, 296, 3, 1, unit);
            tag(bytes, 322, 4, 1, 16);
            tag(bytes, 323, 4, 1, 16);
            tag(bytes, 324, 4, count, count == 1 ? bytes.getInt(offsets) : offsets);
            tag(bytes, 325, 4, count, count == 1 ? bytes.getInt(lengths) : lengths);
            bytes.position(end);
            images.add(expected);
        }
        return new Fixture(Arrays.copyOf(bytes.array(), bytes.position()), images);
    }

    private static void tag(ByteBuffer bytes, int tag, int type, int count, int value) {
        bytes.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
    }
}
