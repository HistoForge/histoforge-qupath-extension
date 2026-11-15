package qupath.ext.histoforge.spatial;

import static qupath.ext.histoforge.spatial.RealWsiBenchmarkSupport.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.writers.ome.OMEPyramidWriter;

/** Opt-in benchmark of canonical OME-BigTIFF materialization from a real WSI-scale source. */
public final class RealWsiCaptureBenchmark {

    private static final int TILE_SIZE = 512;
    private static final int WRITER_THREADS = 4;
    private static final String LARGE_CASE = "large-10000";
    private static final String HALF_CASE = "half-slide";
    private static final String FULL_CASE = "full-slide";

    private RealWsiCaptureBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "expected input WSI, empty output directory, resolution level, and case list");
        }
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        int resolutionLevel = nonnegative(args[2], "resolution level");
        Set<String> selectedCases = cases(args[3]);
        if (!Files.isRegularFile(input)) {
            throw new IOException("input WSI is not a regular file: " + input);
        }
        prepareEmptyDirectory(output);

        BasicFileAttributes before = attributes(input);
        JsonObject sourceHash = measureHash(input);
        OpenedServer metadataOpen = open(input);
        SourceDescription source;
        try (ImageServer<BufferedImage> server = metadataOpen.server()) {
            source = describe(server, resolutionLevel);
        }

        JsonObject result = new JsonObject();
        result.addProperty("schema_version", 1);
        result.addProperty("captured_at", Instant.now().toString());
        result.addProperty("benchmark", "histoforge-real-wsi-capture");
        result.add("host", hostEvidence());
        result.add(
                "benchmark_class_manifest",
                classManifest(RealWsiCaptureBenchmark.class, RealWsiBenchmarkSupport.class));
        result.add("qupath_core", codeSourceEvidence(ImageServer.class));
        result.add("qupath_bioformats", codeSourceEvidence(OMEPyramidWriter.class));
        result.addProperty("tile_size", TILE_SIZE);
        result.addProperty("writer_threads", WRITER_THREADS);
        result.addProperty("source_file_name", input.getFileName().toString());
        result.addProperty("source_path_digest", sha256(input.toUri().toString().getBytes()));
        result.addProperty("source_bytes", before.size());
        result.addProperty(
                "source_last_modified_epoch_millis", before.lastModifiedTime().toMillis());
        result.addProperty("source_file_key", String.valueOf(before.fileKey()));
        result.addProperty("source_open_seconds", metadataOpen.seconds());
        result.add("source_hash", sourceHash);
        result.addProperty("source_server_type", source.serverType());
        result.addProperty("source_server_class", source.serverClass().getName());
        result.add("source_reader", codeSourceEvidence(source.serverClass()));
        result.add("openslide_runtime", runtimeArtifacts("openslide-"));
        result.addProperty("source_server_path_digest", sha256(source.serverPath().getBytes()));
        result.addProperty("source_width", source.width());
        result.addProperty("source_height", source.height());
        result.addProperty("source_z_slices", source.zSlices());
        result.addProperty("source_timepoints", source.timepoints());
        result.add("source_calibration", source.calibration());
        result.add("source_levels", source.levels());
        result.addProperty("selected_resolution_level", resolutionLevel);
        result.addProperty("selected_level_width", source.selectedDimensions().width());
        result.addProperty("selected_level_height", source.selectedDimensions().height());
        result.addProperty("selected_level_downsample", source.selectedDownsample());
        result.addProperty(
                "selected_level_mpp_x",
                source.calibration().get("mpp_x").getAsDouble() * source.selectedDownsample());
        result.addProperty(
                "selected_level_mpp_y",
                source.calibration().get("mpp_y").getAsDouble() * source.selectedDownsample());

        JsonArray conversions = new JsonArray();
        for (RegionCase region : regions(source.selectedDimensions(), selectedCases)) {
            conversions.add(benchmarkRegion(input, output, resolutionLevel, source, region));
        }
        result.add("conversions", conversions);

        BasicFileAttributes after = attributes(input);
        if (!stable(before, after)) {
            throw new IOException("input WSI changed during the benchmark");
        }
        result.addProperty("source_stable", true);

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(
                output.resolve("real-wsi-capture.json"),
                gson.toJson(result) + System.lineSeparator(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println("PHASE0_REAL_WSI_CAPTURE_JSON=" + gson.toJson(result));
    }

    private static JsonObject benchmarkRegion(
            Path input,
            Path output,
            int resolutionLevel,
            SourceDescription expected,
            RegionCase region)
            throws Exception {
        JsonObject decode = measureDecode(input, resolutionLevel, expected, region.rectangle());
        Path artifact = output.resolve(region.name() + ".ome.tif");

        OpenedServer conversionOpen = open(input);
        JsonObject value = new JsonObject();
        value.addProperty("name", region.name());
        value.add("source_rect_at_level", rectangle(region.rectangle()));
        value.addProperty("pixels", pixels(region.rectangle()));
        value.addProperty("source_open_seconds", conversionOpen.seconds());
        value.add("decode_only", decode);
        try (ImageServer<BufferedImage> source = conversionOpen.server()) {
            requireSameSource(expected, source, resolutionLevel);
            try (SelectedLevelRegionServer exact =
                    exactSource(source, resolutionLevel, expected, region.rectangle())) {
                var readback = exact.recordTiffRgb();
                PeakHeapSampler encodeHeap = new PeakHeapSampler();
                long encodeStarted = System.nanoTime();
                encodeHeap.start();
                try {
                    writeTiledTiff(exact, artifact);
                    forceFile(artifact);
                } finally {
                    encodeHeap.close();
                }
                double encodeSeconds = secondsSince(encodeStarted);

                PeakHeapSampler verificationHeap = new PeakHeapSampler();
                long verificationStarted = System.nanoTime();
                verificationHeap.start();
                try {
                    readback.requireExactCopy(artifact);
                } finally {
                    verificationHeap.close();
                }
                double verificationSeconds = secondsSince(verificationStarted);

                JsonObject outputHash = measureHash(artifact);
                double reopenSeconds = requireReopens(artifact, region.rectangle());
                long encodedBytes = Files.size(artifact);
                value.addProperty("path_file_name", artifact.getFileName().toString());
                value.addProperty("container", "TIFF_FAMILY");
                value.addProperty("encoded_bytes", encodedBytes);
                value.addProperty(
                        "uncompressed_rgb_bytes",
                        Math.multiplyExact(pixels(region.rectangle()), 3L));
                value.addProperty(
                        "encoded_bytes_per_source_pixel",
                        encodedBytes / (double) pixels(region.rectangle()));
                value.addProperty("encode_and_force_seconds", encodeSeconds);
                value.addProperty(
                        "encode_pixels_per_second", pixels(region.rectangle()) / encodeSeconds);
                value.addProperty("encode_peak_observed_heap_bytes", encodeHeap.peakBytes());
                value.addProperty("rgb_verification_seconds", verificationSeconds);
                value.addProperty("rgb_verification_method", "writer-tile-sha256-full-readback");
                value.addProperty(
                        "verification_peak_observed_heap_bytes", verificationHeap.peakBytes());
                value.add("output_hash", outputHash);
                value.addProperty("output_reopen_seconds", reopenSeconds);
                value.addProperty(
                        "complete_materialization_seconds",
                        conversionOpen.seconds()
                                + encodeSeconds
                                + verificationSeconds
                                + outputHash.get("seconds").getAsDouble()
                                + reopenSeconds);
            }
        }
        return value;
    }

    private static JsonObject measureDecode(
            Path input,
            int resolutionLevel,
            SourceDescription expected,
            SourceIdentityCodec.IntRect rectangle)
            throws Exception {
        OpenedServer opened = open(input);
        PeakHeapSampler heap = new PeakHeapSampler();
        long started = System.nanoTime();
        long decodedPixels = 0;
        long tileCount = 0;
        long sampleChecksum = 0;
        heap.start();
        try (ImageServer<BufferedImage> source = opened.server()) {
            requireSameSource(expected, source, resolutionLevel);
            try (SelectedLevelRegionServer exact =
                    exactSource(source, resolutionLevel, expected, rectangle)) {
                for (TileRequest tile : exact.getTileRequestManager().getTileRequestsForLevel(0)) {
                    BufferedImage image = exact.readRegion(tile.getRegionRequest());
                    if (image == null
                            || image.getWidth() != tile.getTileWidth()
                            || image.getHeight() != tile.getTileHeight()) {
                        throw new IOException("source decoder returned an incomplete native tile");
                    }
                    decodedPixels =
                            Math.addExact(
                                    decodedPixels,
                                    Math.multiplyExact((long) image.getWidth(), image.getHeight()));
                    tileCount++;
                    sampleChecksum =
                            Long.rotateLeft(sampleChecksum, 7)
                                    ^ image.getRGB(0, 0)
                                    ^ image.getRGB(image.getWidth() - 1, image.getHeight() - 1);
                }
            }
        } finally {
            heap.close();
        }
        if (decodedPixels != pixels(rectangle)) {
            throw new IOException("decode-only pass did not cover the exact source rectangle");
        }
        double decodeSeconds = secondsSince(started);
        JsonObject result = new JsonObject();
        result.addProperty("source_open_seconds", opened.seconds());
        result.addProperty("decode_seconds", decodeSeconds);
        result.addProperty("decoded_pixels", decodedPixels);
        result.addProperty("tile_count", tileCount);
        result.addProperty("sample_checksum", Long.toUnsignedString(sampleChecksum, 16));
        result.addProperty("pixels_per_second", decodedPixels / decodeSeconds);
        result.addProperty("peak_observed_heap_bytes", heap.peakBytes());
        return result;
    }

    private static SelectedLevelRegionServer exactSource(
            ImageServer<BufferedImage> source,
            int resolutionLevel,
            SourceDescription expected,
            SourceIdentityCodec.IntRect rectangle)
            throws IOException {
        double downsample = source.getDownsampleForResolution(resolutionLevel);
        var sourceCalibration = source.getPixelCalibration();
        var selectedCalibration =
                new SourceIdentityCodec.PixelCalibration(
                        sourceCalibration.getPixelWidthMicrons() * downsample,
                        sourceCalibration.getPixelHeightMicrons() * downsample);
        return new SelectedLevelRegionServer(
                source,
                resolutionLevel,
                expected.selectedDimensions(),
                new SourceIdentityCodec.ImagePlaneIndex(0, 0),
                rectangle,
                selectedCalibration);
    }

    private static void writeTiledTiff(ImageServer<BufferedImage> source, Path output)
            throws IOException {
        var series =
                new OMEPyramidWriter.Builder(source)
                        .downsamples(1.0)
                        .tileSize(TILE_SIZE)
                        .compression(OMEPyramidWriter.CompressionType.ZLIB)
                        .channelsInterleaved()
                        .bigTiff()
                        .zSlice(0)
                        .timePoint(0)
                        .parallelize(WRITER_THREADS)
                        .build();
        try {
            OMEPyramidWriter.createWriter(series).writeImage(output.toString());
        } catch (Exception error) {
            if (error instanceof IOException ioError) {
                throw ioError;
            }
            throw new IOException("failed to encode benchmark OME-BigTIFF", error);
        }
    }

    private static double requireReopens(Path artifact, SourceIdentityCodec.IntRect rectangle)
            throws Exception {
        long started = System.nanoTime();
        try (ImageServer<BufferedImage> copy =
                ImageServerProvider.buildServer(artifact.toString(), BufferedImage.class)) {
            if (copy.getWidth() != rectangle.width()
                    || copy.getHeight() != rectangle.height()
                    || copy.nResolutions() != 1
                    || !copy.isRGB()
                    || copy.getPixelType() != PixelType.UINT8
                    || copy.nChannels() != 3) {
                throw new IOException("benchmark output does not reopen as one uint8 RGB image");
            }
        }
        return secondsSince(started);
    }

    private static List<RegionCase> regions(
            SourceViewGeometry.Dimensions dimensions, Set<String> selectedCases) {
        int width = dimensions.width();
        int height = dimensions.height();
        List<RegionCase> available = new ArrayList<>();
        int square = Math.min(10_000, Math.min(width, height));
        available.add(
                new RegionCase(
                        LARGE_CASE,
                        new SourceIdentityCodec.IntRect(
                                (width - square) / 2, (height - square) / 2, square, square)));
        SourceIdentityCodec.IntRect half =
                width >= height
                        ? new SourceIdentityCodec.IntRect(0, 0, (width + 1) / 2, height)
                        : new SourceIdentityCodec.IntRect(0, 0, width, (height + 1) / 2);
        available.add(new RegionCase(HALF_CASE, half));
        available.add(
                new RegionCase(FULL_CASE, new SourceIdentityCodec.IntRect(0, 0, width, height)));

        List<RegionCase> result = new ArrayList<>();
        Set<SourceIdentityCodec.IntRect> rectangles = new LinkedHashSet<>();
        for (RegionCase region : available) {
            if (selectedCases.contains(region.name()) && rectangles.add(region.rectangle())) {
                result.add(region);
            }
        }
        return List.copyOf(result);
    }

    private static Set<String> cases(String raw) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : raw.split(",", -1)) {
            if (!Set.of(LARGE_CASE, HALF_CASE, FULL_CASE).contains(value) || !result.add(value)) {
                throw new IllegalArgumentException("invalid or duplicate benchmark case: " + value);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("benchmark case list must be nonempty");
        }
        return Set.copyOf(result);
    }

    private static JsonObject rectangle(SourceIdentityCodec.IntRect rectangle) {
        JsonObject value = new JsonObject();
        value.addProperty("x", rectangle.x());
        value.addProperty("y", rectangle.y());
        value.addProperty("width", rectangle.width());
        value.addProperty("height", rectangle.height());
        return value;
    }

    private static long pixels(SourceIdentityCodec.IntRect rectangle) {
        return Math.multiplyExact((long) rectangle.width(), rectangle.height());
    }

    private record RegionCase(String name, SourceIdentityCodec.IntRect rectangle) {}
}
