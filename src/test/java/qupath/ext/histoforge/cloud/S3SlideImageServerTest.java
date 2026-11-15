package qupath.ext.histoforge.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.TiffParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3SlideImageServerTest {

    private AwsCredentialManager previousManager;
    private Map<RegionRequest, BufferedImage> previousCache;
    private AwsCredentialManager credentials;

    @BeforeEach
    void useIsolatedCredentialsAndTileCache() {
        previousManager = AwsCredentialManager.getInstance();
        previousCache = ImageServerProvider.getCache(BufferedImage.class);
        credentials = mock(AwsCredentialManager.class);
        AwsCredentialManager.setInstance(credentials);
        ImageServerProvider.setCache(new ConcurrentHashMap<>(), BufferedImage.class);
    }

    @AfterEach
    void restoreApplicationGlobals() {
        AwsCredentialManager.setInstance(previousManager);
        ImageServerProvider.setCache(previousCache, BufferedImage.class);
    }

    @Test
    void reloginRetriesFailedPrefetchedTilesWithoutReplacingOpenImage() throws Exception {
        S3Client initialClient = tileClient();
        S3Client renewedClient = tileClient();
        try (var server = slide(initialClient)) {
            var imageData = new ImageData<BufferedImage>(server);
            var annotation =
                    PathObjects.createAnnotationObject(
                            ROIs.createRectangleROI(1, 2, 3, 4, ImagePlane.getDefaultPlane()));
            imageData.getHierarchy().addObject(annotation);
            assertTrue(imageData.isChanged());
            var updatedMetadata =
                    new ImageServerMetadata.Builder(server.getMetadata())
                            .pixelSizeMicrons(0.25, 0.25)
                            .build();
            server.setMetadata(updatedMetadata);
            var snapshot = server.getObjectSnapshot();
            var tileIndex = server.getTileRequestManager();
            var builder = server.getBuilder();
            String path = server.getPath();
            var full = fullImage(server);
            var laterTile =
                    List.copyOf(server.getTileRequestManager().getTileRequests(full))
                            .get(1)
                            .getRegionRequest();

            assertReadFailure(server, full, "AWS credentials not available");
            when(credentials.getS3Client()).thenReturn(renewedClient);

            BufferedImage pixels = readOnNewWorker(server, laterTile);

            assertEquals(16, pixels.getWidth());
            assertEquals(16, pixels.getHeight());
            assertSame(server, imageData.getServer());
            assertSame(
                    annotation, imageData.getHierarchy().getAnnotationObjects().iterator().next());
            assertTrue(imageData.isChanged());
            assertSame(updatedMetadata, server.getMetadata());
            assertSame(snapshot, server.getObjectSnapshot());
            assertSame(tileIndex, server.getTileRequestManager());
            assertSame(builder, server.getBuilder());
            assertEquals(path, server.getPath());
            verify(initialClient, never()).getObjectAsBytes(any(GetObjectRequest.class));
            ArgumentCaptor<GetObjectRequest> request =
                    ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(renewedClient).getObjectAsBytes(request.capture());
            assertEquals("version-7", request.getValue().versionId());
            verify(renewedClient, never()).headObject(any(HeadObjectRequest.class));
        }
    }

    @Test
    void temporaryS3FailureIsReportedAndNextReadCanRecover() throws Exception {
        S3Client unavailable = mock(S3Client.class);
        when(unavailable.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(
                        S3Exception.builder()
                                .statusCode(503)
                                .message("temporarily unavailable")
                                .build());
        try (var server = slide(tileClient())) {
            when(credentials.getS3Client()).thenReturn(unavailable);
            var full = fullImage(server);
            var laterTile =
                    List.copyOf(server.getTileRequestManager().getTileRequests(full))
                            .get(1)
                            .getRegionRequest();

            assertReadFailure(server, full, "temporarily unavailable");
            S3Client renewed = tileClient();
            when(credentials.getS3Client()).thenReturn(renewed);

            assertEquals(16, readOnNewWorker(server, laterTile).getWidth());
        }
    }

    @Test
    void recoveryKeepsSuccessfulTilesInTheNativeCache() throws Exception {
        S3Client client = tileClient();
        try (var server = slide(client)) {
            var tiles =
                    List.copyOf(server.getTileRequestManager().getTileRequests(fullImage(server)));
            var cachedTile = tiles.getFirst();
            var failingTile = tiles.get(1).getRegionRequest();
            when(credentials.getS3Client()).thenReturn(client);
            BufferedImage before = readOnNewWorker(server, cachedTile.getRegionRequest());
            when(credentials.getS3Client()).thenReturn(null);

            assertReadFailure(server, failingTile, "AWS credentials not available");

            BufferedImage after = readOnNewWorker(server, cachedTile.getRegionRequest());
            assertEquals(before.getRGB(0, 0), after.getRGB(0, 0));
            verify(client).getObjectAsBytes(any(GetObjectRequest.class));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {3, 1024})
    void invalidOrTruncatedBytesAreAnErrorNotACachedEmptyTile(int length) throws Exception {
        S3Client invalid = tileClient();
        when(invalid.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(
                                GetObjectResponse.builder().build(), new byte[length]));
        S3Client valid = tileClient();
        try (var server = slide(valid)) {
            var request =
                    server.getTileRequestManager()
                            .getTileRequests(fullImage(server))
                            .iterator()
                            .next()
                            .getRegionRequest();
            when(credentials.getS3Client()).thenReturn(invalid);

            assertReadFailure(
                    server,
                    request,
                    length == 1024 ? "could not be decoded" : "Incomplete S3 tile response");
            when(credentials.getS3Client()).thenReturn(valid);

            assertEquals(16, readOnNewWorker(server, request).getWidth());
            verify(valid).getObjectAsBytes(any(GetObjectRequest.class));
        }
    }

    @Test
    void sourceVersionRejectionRemainsAnErrorOnEveryAttempt() throws Exception {
        S3Client client = tileClient();
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(
                        S3Exception.builder()
                                .statusCode(412)
                                .message("source version changed")
                                .build());
        when(credentials.getS3Client()).thenReturn(client);
        try (var server = slide(client)) {
            var request =
                    server.getTileRequestManager()
                            .getTileRequests(fullImage(server))
                            .iterator()
                            .next()
                            .getRegionRequest();

            assertReadFailure(server, request, "source version changed");
            assertReadFailure(server, request, "source version changed");

            var requests = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(client, times(2)).getObjectAsBytes(requests.capture());
            assertTrue(
                    requests.getAllValues().stream()
                            .allMatch(r -> "version-7".equals(r.versionId())));
        }
    }

    @Test
    void lateFailureDoesNotDiscardAReplacementReaderWithAnActiveTileRead() throws Exception {
        S3Client expired = tileClient();
        S3Client renewed = tileClient();
        var bothFailedReadsStarted = new CountDownLatch(2);
        var releaseFirstFailure = new CountDownLatch(1);
        var releaseLateFailure = new CountDownLatch(1);
        var healthyReadStarted = new CountDownLatch(1);
        var releaseHealthyRead = new CountDownLatch(1);
        try (var server = slide(expired);
                var workers = Executors.newFixedThreadPool(3)) {
            var first = RegionRequest.createInstance(server.getPath(), 1, 0, 0, 16, 16);
            var late = RegionRequest.createInstance(server.getPath(), 1, 16, 0, 16, 16);
            var healthy = RegionRequest.createInstance(server.getPath(), 1, 32, 0, 32, 16);
            var healthyTiles = List.copyOf(server.getTileRequestManager().getTileRequests(healthy));
            var prefetched = healthyTiles.get(0).getRegionRequest();
            var blocked = healthyTiles.get(1).getRegionRequest();
            String blockedRange =
                    "bytes="
                            + blocked.getX() / 16 * 1024
                            + "-"
                            + (blocked.getX() / 16 * 1024 + 1023);
            when(expired.getObjectAsBytes(any(GetObjectRequest.class)))
                    .thenAnswer(
                            call -> {
                                bothFailedReadsStarted.countDown();
                                GetObjectRequest request = call.getArgument(0);
                                var release =
                                        request.range().equals("bytes=0-1023")
                                                ? releaseFirstFailure
                                                : releaseLateFailure;
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                throw S3Exception.builder()
                                        .statusCode(403)
                                        .message("expired credentials")
                                        .build();
                            });
            var successfulBytes = renewed.getObjectAsBytes(GetObjectRequest.builder().build());
            org.mockito.Mockito.clearInvocations(renewed);
            when(renewed.getObjectAsBytes(any(GetObjectRequest.class)))
                    .thenAnswer(
                            call -> {
                                GetObjectRequest request = call.getArgument(0);
                                if (request.range().equals(blockedRange)) {
                                    healthyReadStarted.countDown();
                                    assertTrue(releaseHealthyRead.await(5, TimeUnit.SECONDS));
                                }
                                return successfulBytes;
                            });
            when(credentials.getS3Client()).thenReturn(expired);
            try {
                var firstResult = workers.submit(() -> server.readRegion(first));
                var lateResult = workers.submit(() -> server.readRegion(late));
                assertTrue(bothFailedReadsStarted.await(5, TimeUnit.SECONDS));
                releaseFirstFailure.countDown();
                assertThrows(ExecutionException.class, () -> firstResult.get(5, TimeUnit.SECONDS));

                when(credentials.getS3Client()).thenReturn(renewed);
                var healthyResult = workers.submit(() -> server.readRegion(healthy));
                assertTrue(healthyReadStarted.await(5, TimeUnit.SECONDS));
                releaseLateFailure.countDown();
                assertThrows(ExecutionException.class, () -> lateResult.get(5, TimeUnit.SECONDS));

                // The first healthy tile is prefetched but not yet cached. It must remain available
                // through the replacement reader, even after the old reader's second failure.
                assertEquals(16, readOnNewWorker(server, prefetched).getWidth());
                verify(renewed, times(2)).getObjectAsBytes(any(GetObjectRequest.class));
                releaseHealthyRead.countDown();
                assertEquals(32, healthyResult.get(5, TimeUnit.SECONDS).getWidth());
            } finally {
                releaseFirstFailure.countDown();
                releaseLateFailure.countDown();
                releaseHealthyRead.countDown();
            }
        }
    }

    private static void assertReadFailure(
            S3SlideImageServer server, RegionRequest request, String text) {
        var failure =
                assertThrows(ExecutionException.class, () -> readOnNewWorker(server, request));
        var cause = assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(cause.getMessage().contains(text), cause::getMessage);
    }

    private static BufferedImage readOnNewWorker(S3SlideImageServer server, RegionRequest request)
            throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            return worker.submit(() -> server.readRegion(request)).get(5, TimeUnit.SECONDS);
        }
    }

    private static RegionRequest fullImage(S3SlideImageServer server) {
        return RegionRequest.createInstance(server.getPath(), 1, 0, 0, 64, 16);
    }

    private static S3Client tileClient() throws IOException {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "JPEG", output));
        S3Client client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(
                        ResponseBytes.fromByteArray(
                                GetObjectResponse.builder().build(),
                                java.util.Arrays.copyOf(output.toByteArray(), 1024)));
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(
                        HeadObjectResponse.builder()
                                .contentLength(4096L)
                                .versionId("version-7")
                                .build());
        return client;
    }

    /** Keep real tile reads and native assembly; replace only remote TIFF metadata parsing. */
    private static S3SlideImageServer slide(S3Client client) throws IOException {
        var ifd = new IFD();
        ifd.put(IFD.IMAGE_WIDTH, 64);
        ifd.put(IFD.IMAGE_LENGTH, 16);
        ifd.put(IFD.TILE_WIDTH, 16);
        ifd.put(IFD.TILE_LENGTH, 16);
        ifd.put(IFD.TILE_OFFSETS, new long[] {0, 1024, 2048, 3072});
        ifd.put(IFD.TILE_BYTE_COUNTS, new long[] {1024, 1024, 1024, 1024});
        var ifds = new IFDList();
        ifds.add(ifd);
        try (var parser =
                mockConstruction(
                        TiffParser.class,
                        (mock, context) -> when(mock.getMainIFDs()).thenReturn(ifds))) {
            return new S3SlideImageServer(
                    URI.create("histoforge-cloud://slides/" + UUID.randomUUID()),
                    client,
                    "slides",
                    "test.tif");
        }
    }
}
