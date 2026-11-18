package qupath.ext.histoforge.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.histoforge.cloud.S3ObjectSnapshot;
import qupath.ext.histoforge.cloud.S3SlideImageServer;
import qupath.ext.histoforge.contract.OriginalReaderSettingsCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.bioformats.BioFormatsImageServer;
import qupath.lib.images.servers.bioformats.BioFormatsServerBuilder;
import qupath.lib.images.servers.openslide.OpenslideImageServer;
import qupath.lib.images.servers.openslide.OpenslideServerBuilder;
import qupath.lib.regions.RegionRequest;

class ImageSourceCaptureTest {

    @TempDir Path temporaryDirectory;

    @Test
    void freezesAdapterDescriptorSpellings() {
        assertEquals(
                "8c2d319c318a13f7b99229996f1bc0f6a4e1083072aab077f56d9f5fcfc9ff60",
                ImageSourceCapture.bioFormatsDescriptorDigest(
                        "file:///slides/specimen%20one.ome.tif",
                        "OME-TIFF",
                        "BioFormatsImageServer: file:///slides/specimen%20one.ome.tif,"
                                + "--series 2,--dims XYZCT",
                        2));
        assertNotEquals(
                ImageSourceCapture.bioFormatsDescriptorDigest(
                        "file:///slides/specimen%20one.ome.tif",
                        "OME-TIFF",
                        "BioFormatsImageServer: file:///slides/specimen%20one.ome.tif,"
                                + "--series 2,--dims XYZCT",
                        2),
                ImageSourceCapture.bioFormatsDescriptorDigest(
                        "file:///slides/specimen%20one.ome.tif",
                        "OME-TIFF",
                        "BioFormatsImageServer: file:///slides/specimen%20one.ome.tif,"
                                + "--series 2,--dims XYCZT",
                        2));
        assertEquals(
                "441ee040b10b4140f3bb07d13993dd60d68d202c4472fa9c1a02e7bfe3e9665a",
                ImageSourceCapture.s3DescriptorDigest(
                        "study-bucket", "slides/specimen one.tif", 0));
        assertEquals(
                "dcec690da0f3198119ef3628c4cca8d8dedc3a47ecae88deb0d92ea0af5fed95",
                ImageSourceCapture.openSlideDescriptorDigest(
                        "file:///slides/generic%20pyramid.tif",
                        "generic-tiff", new SourceIdentityCodec.IntRect(0, 0, 513, 385), 0));
        assertNotEquals(
                ImageSourceCapture.openSlideDescriptorDigest(
                        "file:///slides/generic%20pyramid.tif",
                        "generic-tiff", new SourceIdentityCodec.IntRect(0, 0, 513, 385), 0),
                ImageSourceCapture.openSlideDescriptorDigest(
                        "file:///slides/generic%20pyramid.tif",
                        "generic-tiff", new SourceIdentityCodec.IntRect(10, 20, 500, 360), 0));
    }

    @Test
    void capturesAdmittedOpenSlideOriginalAndReadsBackendParityWindows() throws Exception {
        JsonObject contract = openSlideContract();
        JsonObject sourceContract = contract.getAsJsonObject("source_file");
        Path fixture =
                Path.of(
                        ImageSourceCaptureTest.class
                                .getResource("/contracts/openslide/generic-tiff-v1.tif")
                                .toURI());
        Path file = temporaryDirectory.resolve("generic-tiff-v1.tif");
        Files.copy(fixture, file);
        assertEquals(sourceContract.get("sha256").getAsString(), fileDigest(file));
        assertEquals(sourceContract.get("size_bytes").getAsLong(), Files.size(file));

        ImageSourceCapture.CapturedSource captured;
        try (var server = new OpenslideImageServer(file.toUri())) {
            ImageSourceCapture.CapturedSource unprepared = new ImageSourceCapture().capture(server);
            assertThrows(IllegalStateException.class, unprepared::eligibleOriginal);
            captured = unprepared.prepare();

            assertEquals(
                    sourceContract.get("source_adapter_id").getAsString(),
                    captured.identity().sourceAdapterId());
            JsonObject levelZero = contract.getAsJsonArray("levels").get(0).getAsJsonObject();
            assertEquals(levelZero.get("width").getAsInt(), captured.identity().width());
            assertEquals(levelZero.get("height").getAsInt(), captured.identity().height());
            assertEquals(
                    sourceContract.get("mpp_x").getAsDouble(),
                    captured.identity().calibration().mppX());
            assertEquals(
                    sourceContract.get("mpp_y").getAsDouble(),
                    captured.identity().calibration().mppY());
            assertEquals(file.toRealPath(), captured.unchangedOriginalPath().orElseThrow());
            assertEquals(Files.size(file), captured.eligibleOriginal().orElseThrow().totalBytes());
            assertEquals(contract.getAsJsonArray("levels").size(), captured.levels().size());
            for (int index = 0; index < captured.levels().size(); index++) {
                JsonObject level = contract.getAsJsonArray("levels").get(index).getAsJsonObject();
                assertEquals(
                        level.get("resolution_level").getAsInt(),
                        captured.levels().get(index).resolutionLevel());
                assertEquals(
                        level.get("width").getAsInt(),
                        captured.levels().get(index).dimensions().width());
                assertEquals(
                        level.get("height").getAsInt(),
                        captured.levels().get(index).dimensions().height());
                assertEquals(
                        level.get("qupath_downsample").getAsDouble(),
                        captured.levels().get(index).pixelsToLevelZero().scaleX());
            }

            for (var element : contract.getAsJsonArray("window_cases")) {
                JsonObject window = element.getAsJsonObject();
                JsonObject rectangle = window.getAsJsonObject("pixel_rect");
                BufferedImage pixels =
                        server.readRegion(
                                region(
                                        server,
                                        captured.levels()
                                                .get(window.get("resolution_level").getAsInt())
                                                .pixelsToLevelZero()
                                                .scaleX(),
                                        rectangle.get("x").getAsInt(),
                                        rectangle.get("y").getAsInt(),
                                        rectangle.get("width").getAsInt(),
                                        rectangle.get("height").getAsInt()));
                assertEquals(window.get("rgb_sha256").getAsString(), rgbDigest(pixels));
            }
            captured.requireCurrent();
        }

        Files.write(file, new byte[] {0}, java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, captured::requireCurrent);
    }

    @Test
    void capturesExplicitUncroppedOpenSlideBounds() throws Exception {
        Path file = temporaryDirectory.resolve("bounded.tif");
        Files.write(file, new byte[] {1});
        OpenslideImageServer server = mock(OpenslideImageServer.class);
        ImageServerMetadata metadata =
                metadata(
                        100,
                        80,
                        new ImageServerMetadata.ImageResolutionLevel.Builder(100, 80)
                                .addFullResolutionLevel()
                                .build());
        stubOpenSlideSource(server, metadata, file, "--no-crop");
        when(server.dumpMetadata())
                .thenReturn(
                        """
                        {
                          "openslide.vendor": "generic-tiff",
                          "openslide.level[0].width": "100",
                          "openslide.level[0].height": "80",
                          "openslide.bounds-x": "10",
                          "openslide.bounds-y": "10",
                          "openslide.bounds-width": "50",
                          "openslide.bounds-height": "40"
                        }
                        """);

        ImageSourceCapture.CapturedSource captured = completeMockCapture(server);
        OriginalReaderSettingsCodec.OpenSlide settings = openSlideSettings(captured);
        assertEquals(100, captured.identity().width());
        assertEquals(80, captured.identity().height());
        assertEquals(0, settings.cropX());
        assertEquals(0, settings.cropY());
    }

    @Test
    void capturesOpenSlideBackgroundColor() throws Exception {
        Path file = temporaryDirectory.resolve("white-background.tif");
        Files.write(file, new byte[] {1});
        OpenslideImageServer server = mock(OpenslideImageServer.class);
        ImageServerMetadata metadata =
                metadata(
                        100,
                        80,
                        new ImageServerMetadata.ImageResolutionLevel.Builder(100, 80)
                                .addFullResolutionLevel()
                                .build());
        stubOpenSlideSource(server, metadata, file);
        when(server.dumpMetadata())
                .thenReturn(
                        """
                        {
                          "openslide.vendor": "generic-tiff",
                          "openslide.level[0].width": "100",
                          "openslide.level[0].height": "80",
                          "openslide.background-color": "ffffff"
                        }
                        """);

        assertEquals(
                List.of(255, 255, 255),
                openSlideSettings(completeMockCapture(server)).backgroundRgb());
    }

    @Test
    void capturesFractionalOpenSlideNativeLevelMapping() throws Exception {
        Path file = temporaryDirectory.resolve("irregular-pyramid.tif");
        Files.write(file, new byte[] {1});
        OpenslideImageServer server = mock(OpenslideImageServer.class);
        ImageServerMetadata metadata =
                metadata(
                        100,
                        80,
                        new ImageServerMetadata.ImageResolutionLevel.Builder(100, 80)
                                .addFullResolutionLevel()
                                .addLevel(1.5, 67, 53)
                                .build());
        stubOpenSlideSource(server, metadata, file);
        when(server.dumpMetadata())
                .thenReturn(
                        """
                        {
                          "openslide.vendor": "generic-tiff",
                          "openslide.level[0].width": "100",
                          "openslide.level[0].height": "80"
                        }
                        """);

        OriginalReaderSettingsCodec.Level level =
                openSlideSettings(completeMockCapture(server)).levels().get(1);
        assertEquals(1, level.resolutionLevel());
        assertEquals(1, level.nativeResolutionIndex());
        assertEquals(
                new SourceIdentityCodec.ScaleTranslate2D(1.5, 1.5, 0, 0),
                level.pixelsToLevelZero());
    }

    @Test
    void capturesSourcePlaneCountWithoutReplacingItWithTheSelectedPlane() throws Exception {
        Path file = temporaryDirectory.resolve("multiplane.tif");
        Files.write(file, new byte[] {1});
        OpenslideImageServer server = mock(OpenslideImageServer.class);
        ImageServerMetadata metadata =
                metadata(
                        100,
                        80,
                        new ImageServerMetadata.ImageResolutionLevel.Builder(100, 80)
                                .addFullResolutionLevel()
                                .build());
        stubOpenSlideSource(server, metadata, file);
        when(server.nZSlices()).thenReturn(2);
        when(server.dumpMetadata())
                .thenReturn(
                        """
                        {
                          "openslide.vendor": "generic-tiff",
                          "openslide.level[0].width": "100",
                          "openslide.level[0].height": "80"
                        }
                        """);

        ImageSourceCapture.CapturedSource captured = completeMockCapture(server);
        assertEquals(2, captured.identity().zSlices());
        assertEquals(1, captured.identity().timepoints());
        openSlideSettings(captured);
    }

    @Test
    void capturesAperioUsingTheActiveOpenSlideReader() throws Exception {
        Path file = temporaryDirectory.resolve("aperio.tif");
        Files.write(file, new byte[] {1});
        OpenslideImageServer server = mock(OpenslideImageServer.class);
        ImageServerMetadata metadata =
                metadata(
                        100,
                        80,
                        new ImageServerMetadata.ImageResolutionLevel.Builder(100, 80)
                                .addFullResolutionLevel()
                                .build());
        stubOpenSlideSource(server, metadata, file);
        when(server.dumpMetadata())
                .thenReturn(
                        """
                        {
                          "openslide.vendor": "aperio",
                          "openslide.level[0].width": "100",
                          "openslide.level[0].height": "80"
                        }
                        """);

        ImageSourceCapture.CapturedSource captured = completeMockCapture(server);
        assertEquals(
                ImageSourceCapture.OPENSLIDE_LOCAL_ADAPTER_ID,
                captured.identity().sourceAdapterId());
        assertEquals(file.toRealPath(), captured.unchangedOriginalPath().orElseThrow());
        openSlideSettings(captured);
    }

    @Test
    void activeOpenSlideIccConversionRemainsPreparedOnly() throws Exception {
        Path file = temporaryDirectory.resolve("icc.tif");
        Files.write(file, new byte[] {1});
        OpenslideImageServer server = mock(OpenslideImageServer.class);
        ImageServerMetadata metadata =
                metadata(
                        100,
                        80,
                        new ImageServerMetadata.ImageResolutionLevel.Builder(100, 80)
                                .addFullResolutionLevel()
                                .build());
        stubOpenSlideSource(server, metadata, file, "--icc-profile");
        when(server.dumpMetadata())
                .thenReturn(
                        """
                {"openslide.vendor":"generic-tiff",
                 "openslide.level[0].width":"100","openslide.level[0].height":"80"}
                """);

        ImageSourceCapture.CapturedSource captured = completeMockCapture(server);

        assertEquals(
                ImageSourceCapture.OPENSLIDE_LOCAL_ADAPTER_ID,
                captured.identity().sourceAdapterId());
        assertTrue(captured.eligibleOriginal().isEmpty());
        assertTrue(captured.unchangedOriginalPath().isEmpty());
        captured.requireCurrent();
    }

    @Test
    void rejectsBioFormatsCustomDimensionSettingsBeforeDiscovery() throws Exception {
        Path file = temporaryDirectory.resolve("custom-dimensions.ome.tif");
        Files.write(file, new byte[] {1});
        BioFormatsImageServer server = mock(BioFormatsImageServer.class);
        ImageServerMetadata metadata =
                metadata(
                        100,
                        80,
                        new ImageServerMetadata.ImageResolutionLevel.Builder(100, 80)
                                .addFullResolutionLevel()
                                .build());
        stubSource(server, metadata);
        when(server.getFile()).thenReturn(file.toFile());
        when(server.getSeries()).thenReturn(0);
        when(server.getFormat()).thenReturn("OME-TIFF");
        when(server.getPath())
                .thenReturn("BioFormatsImageServer: " + file.toUri() + ",--dims XYZCT");
        when(server.getBuilder())
                .thenReturn(
                        DefaultImageServerBuilder.createInstance(
                                BioFormatsServerBuilder.class,
                                metadata,
                                file.toUri(),
                                "--series",
                                "0",
                                "--dims",
                                "XYZCT"));

        IOException error =
                assertThrows(IOException.class, () -> new ImageSourceCapture().capture(server));

        assertTrue(error.getMessage().contains("custom Bio-Formats"));
    }

    @Test
    void capturesBioFormatsFileVersionAndReaderDownsamples() throws Exception {
        Path file = temporaryDirectory.resolve("specimen.ome.tif");
        Files.write(file, new byte[] {1, 2, 3, 4});
        BioFormatsImageServer server = mock(BioFormatsImageServer.class);
        ImageServerMetadata metadata =
                metadata(
                        10_001,
                        8_001,
                        new ImageServerMetadata.ImageResolutionLevel.Builder(10_001, 8_001)
                                .addFullResolutionLevel()
                                .addLevel(5_001, 4_001)
                                .build());
        stubSource(server, metadata);
        when(server.getFile()).thenReturn(file.toFile());
        when(server.getSeries()).thenReturn(2);
        when(server.getFormat()).thenReturn("OME-TIFF");
        when(server.getBuilder())
                .thenReturn(
                        DefaultImageServerBuilder.createInstance(
                                BioFormatsServerBuilder.class,
                                metadata,
                                file.toUri(),
                                "--series",
                                "2"));
        when(server.getPath())
                .thenReturn(
                        "BioFormatsImageServer: " + file.toUri().toASCIIString() + ",--series 2");

        ImageSourceCapture.CapturedSource captured = completeMockCapture(server);

        assertEquals(
                ImageSourceCapture.BIOFORMATS_LOCAL_ADAPTER_ID,
                captured.identity().sourceAdapterId());
        assertInstanceOf(
                SourceIdentityCodec.LocalFile.class,
                captured.identity().contentVersion().orElseThrow());
        assertEquals(2, captured.seriesIndex());
        assertEquals(2, captured.levels().size());
        assertEquals(2.0, metadata.getLevel(1).getDownsample());
        assertEquals(2.0, captured.levels().get(1).pixelsToLevelZero().scaleX());
        assertEquals(5_001, captured.levels().get(1).dimensions().width());
        captured.requireCurrent();

        String sourceDigest = SourceIdentityCodec.imageSourceIdentityDigest(captured.identity());
        var revision =
                new RasterInputCodec.ImageRevisionToken("image-a", sourceDigest, "a".repeat(64));
        assertEquals(captured.identity(), captured.bind(revision).sourceRevision().pixelSource());
        var anotherIdentity =
                new SourceIdentityCodec.ImageSourceIdentity(
                        captured.identity().sourceAdapterId(),
                        "f".repeat(64),
                        captured.identity().contentVersion(),
                        captured.identity().width(),
                        captured.identity().height(),
                        captured.identity().zSlices(),
                        captured.identity().timepoints(),
                        captured.identity().calibration());
        assertThrows(
                IllegalArgumentException.class,
                () -> captured.revisionVerifier().requireCurrent(anotherIdentity));

        Files.write(file, new byte[] {1, 2, 3, 4, 5});
        assertThrows(IOException.class, captured::requireCurrent);
    }

    @Test
    void capturesS3SnapshotButAdvertisesOnlyProvenLevelZero() throws Exception {
        S3SlideImageServer server = mock(S3SlideImageServer.class);
        ImageServerMetadata metadata =
                metadata(
                        6_000,
                        4_000,
                        new ImageServerMetadata.ImageResolutionLevel.Builder(6_000, 4_000)
                                .addLevel(1.0, 6_000, 4_000)
                                .addLevel(2.0, 3_000, 2_000)
                                .build());
        stubSource(server, metadata);
        when(server.getURIs())
                .thenReturn(
                        List.of(
                                URI.create(
                                        "histoforge-cloud://study-bucket/slides/specimen%20one.tif")));
        when(server.getObjectSnapshot())
                .thenReturn(
                        new S3ObjectSnapshot(
                                1_000_000, new SourceIdentityCodec.S3VersionId("version-7")));

        ImageSourceCapture.CapturedSource captured = new ImageSourceCapture().capture(server);

        assertEquals(ImageSourceCapture.S3_TIFF_ADAPTER_ID, captured.identity().sourceAdapterId());
        assertEquals(
                "441ee040b10b4140f3bb07d13993dd60d68d202c4472fa9c1a02e7bfe3e9665a",
                captured.identity().credentialFreeDescriptorDigest());
        assertEquals(1, captured.levels().size());
        assertEquals(0, captured.levels().getFirst().resolutionLevel());
        captured.requireCurrent();
        verify(server).requireCurrentSource();
    }

    @Test
    void rejectsUnownedImageServerBeforePlanning() {
        @SuppressWarnings("unchecked")
        ImageServer<BufferedImage> server = mock(ImageServer.class);
        when(server.isRGB()).thenReturn(true);
        when(server.getPixelType()).thenReturn(PixelType.UINT8);
        when(server.nChannels()).thenReturn(3);
        when(server.getBuilder())
                .thenReturn(
                        DefaultImageServerBuilder.createInstance(
                                OpenslideServerBuilder.class,
                                URI.create("file:///slides/unsupported.tif")));

        assertThrows(IOException.class, () -> new ImageSourceCapture().capture(server));
    }

    @Test
    void rejectsUnsupportedPixelsAndNonlocalBioFormatsSources() {
        @SuppressWarnings("unchecked")
        ImageServer<BufferedImage> unsupported = mock(ImageServer.class);
        when(unsupported.isRGB()).thenReturn(true);
        when(unsupported.getPixelType()).thenReturn(PixelType.UINT16);
        when(unsupported.nChannels()).thenReturn(3);
        assertThrows(IOException.class, () -> new ImageSourceCapture().capture(unsupported));

        BioFormatsImageServer nonlocal = mock(BioFormatsImageServer.class);
        when(nonlocal.isRGB()).thenReturn(true);
        when(nonlocal.getPixelType()).thenReturn(PixelType.UINT8);
        when(nonlocal.nChannels()).thenReturn(3);
        when(nonlocal.getFile()).thenReturn(null);
        assertThrows(IOException.class, () -> new ImageSourceCapture().capture(nonlocal));
    }

    private static ImageServerMetadata metadata(
            int width,
            int height,
            java.util.List<ImageServerMetadata.ImageResolutionLevel> levels) {
        return new ImageServerMetadata.Builder()
                .width(width)
                .height(height)
                .sizeZ(1)
                .sizeT(1)
                .pixelType(PixelType.UINT8)
                .channels(ImageChannel.getDefaultRGBChannels())
                .rgb(true)
                .pixelSizeMicrons(0.25, 0.5)
                .levels(levels)
                .build();
    }

    private static void stubSource(
            ImageServer<BufferedImage> source, ImageServerMetadata metadata) {
        when(source.isRGB()).thenReturn(true);
        when(source.getPixelType()).thenReturn(PixelType.UINT8);
        when(source.nChannels()).thenReturn(3);
        when(source.getWidth()).thenReturn(metadata.getWidth());
        when(source.getHeight()).thenReturn(metadata.getHeight());
        when(source.nZSlices()).thenReturn(1);
        when(source.nTimepoints()).thenReturn(1);
        when(source.nResolutions()).thenReturn(metadata.nLevels());
        when(source.getMetadata()).thenReturn(metadata);
        PixelCalibration calibration =
                new PixelCalibration.Builder().pixelSizeMicrons(0.25, 0.5).build();
        when(source.getPixelCalibration()).thenReturn(calibration);
    }

    private static void stubOpenSlideSource(
            OpenslideImageServer server, ImageServerMetadata metadata, Path file, String... args) {
        stubSource(server, metadata);
        when(server.getURIs()).thenReturn(List.of(file.toUri()));
        when(server.getBuilder())
                .thenReturn(
                        DefaultImageServerBuilder.createInstance(
                                OpenslideServerBuilder.class, metadata, file.toUri(), args));
    }

    /** Mocks test host capture facts; the real OpenSlide fixture above exercises discovery. */
    private static ImageSourceCapture.CapturedSource completeMockCapture(
            ImageServer<BufferedImage> server) throws IOException {
        ImageSourceCapture.CapturedSource captured = new ImageSourceCapture().capture(server);
        ImageSourceCapture.LocalSource local = captured.localSource().orElseThrow();
        SlideFileInventory inventory =
                SlideFileInventory.capture(local.entry(), List.of(local.entry()), Optional.empty());
        return new ImageSourceCapture.CapturedSource(
                captured.identity(),
                captured.seriesIndex(),
                captured.levelZeroPixelsToSlide(),
                captured.levels(),
                captured.revisionVerifier(),
                Optional.of(
                        new ImageSourceCapture.LocalSource(
                                local.entry(),
                                local.settings(),
                                Optional.of(inventory),
                                Optional.empty())));
    }

    private static OriginalReaderSettingsCodec.OpenSlide openSlideSettings(
            ImageSourceCapture.CapturedSource captured) {
        var original = captured.eligibleOriginal().orElseThrow();
        assertEquals(SourceArtifactCodec.SourceContainer.SLIDE_FILE, original.container());
        return assertInstanceOf(
                OriginalReaderSettingsCodec.OpenSlide.class, original.readerSettings());
    }

    private static RegionRequest region(
            ImageServer<BufferedImage> server,
            double downsample,
            int x,
            int y,
            int width,
            int height) {
        return RegionRequest.createInstance(
                server.getPath(),
                downsample,
                (int) Math.round(x * downsample),
                (int) Math.round(y * downsample),
                (int) Math.round(width * downsample),
                (int) Math.round(height * downsample),
                0,
                0);
    }

    private static String rgbDigest(BufferedImage image) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(rgbBytes(image));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fileDigest(Path path) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static JsonObject openSlideContract() throws Exception {
        Path path =
                Path.of(
                        ImageSourceCaptureTest.class
                                .getResource("/contracts/openslide-original-v1.json")
                                .toURI());
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static byte[] rgbBytes(BufferedImage image) {
        byte[] bytes = new byte[image.getWidth() * image.getHeight() * 3];
        int index = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                bytes[index++] = (byte) (rgb >>> 16);
                bytes[index++] = (byte) (rgb >>> 8);
                bytes[index++] = (byte) rgb;
            }
        }
        return bytes;
    }
}
