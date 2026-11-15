package qupath.ext.histoforge.source;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.writers.PngWriter;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.regions.RegionRequest;

/** Opt-in Phase 0 benchmark for QuPath's public source-capture writers. */
public final class SourceCaptureBenchmark {

    private static final int TILE_SIZE = 512;
    private static final double SOURCE_MPP_X = 0.25;
    private static final double SOURCE_MPP_Y = 0.25;
    // Reader-level transforms are covered by the backend's exact rounded-dimension fixture. This
    // transport benchmark keeps one level so its generated stand-in is accepted by that reader
    // without treating QuPath's generated sub-IFD metadata as scanner-adapter evidence.
    private static final double[] ORIGINAL_DOWNSAMPLES = {1.0};
    private static final int CAPTURE_SOURCE_LEVEL = 0;
    private static final double CAPTURE_DOWNSAMPLE = ORIGINAL_DOWNSAMPLES[CAPTURE_SOURCE_LEVEL];
    private static final int WRITER_THREADS = 4;
    private static final int HASH_BUFFER_BYTES = 8 * 1024 * 1024;

    private SourceCaptureBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "expected output, original width/height, small size, and large size");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        int originalWidth = positive(args[1], "original width");
        int originalHeight = positive(args[2], "original height");
        int smallSize = positive(args[3], "small size");
        int largeSize = positive(args[4], "large size");
        requireGeometry(originalWidth, originalHeight, smallSize, largeSize);
        prepareEmptyDirectory(output);

        Path original = output.resolve("original-lossless.ome.tif");
        Path smallPng = output.resolve("small-region.png");
        Path largeTiff = output.resolve("large-region.ome.tif");

        JsonObject originalGeneration;
        try (var synthetic = new DeterministicRgbServer(originalWidth, originalHeight)) {
            long originalPixels = pyramidPixels(originalWidth, originalHeight);
            originalGeneration =
                    measureFile(
                            "original_generation",
                            original,
                            originalPixels,
                            () -> writeOriginal(synthetic, original));
        }

        JsonObject result = new JsonObject();
        result.addProperty("schema_version", 1);
        result.addProperty("captured_at", Instant.now().toString());
        result.add("host", hostEvidence());
        result.add("benchmark_class_manifest", classManifest(SourceCaptureBenchmark.class));
        result.add("qupath_core", codeSourceEvidence(ImageServer.class));
        result.add("qupath_bioformats", codeSourceEvidence(OMEPyramidWriter.class));
        result.addProperty("tile_size", TILE_SIZE);
        result.addProperty("writer_threads", WRITER_THREADS);
        result.add("original_generation", originalGeneration);

        try (ImageServer<BufferedImage> server =
                ImageServerProvider.buildServer(original.toString(), BufferedImage.class)) {
            verifyOriginalServer(server, originalWidth, originalHeight);
            result.addProperty("source_server_type", server.getServerType());
            result.add("source_calibration", sourceCalibration(server));
            result.add("source_levels", levels(server));

            int smallX = TILE_SIZE;
            int smallY = TILE_SIZE;
            int largeX = TILE_SIZE * 2;
            int largeY = TILE_SIZE * 2;
            JsonArray captures = new JsonArray();
            captures.add(capturePng(server, smallPng, smallX, smallY, smallSize, smallSize));
            captures.add(captureOmeTiff(server, largeTiff, largeX, largeY, largeSize, largeSize));
            result.add("captures", captures);
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String json = gson.toJson(result) + System.lineSeparator();
        Files.writeString(
                output.resolve("capture.json"),
                json,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        System.out.println("PHASE0_SOURCE_CAPTURE_JSON=" + gson.toJson(result));
    }

    private static JsonObject capturePng(
            ImageServer<BufferedImage> server, Path output, int x, int y, int width, int height)
            throws Exception {
        RegionRequest request =
                RegionRequest.createInstance(
                        server.getPath(),
                        CAPTURE_DOWNSAMPLE,
                        levelZeroCoordinate(x),
                        levelZeroCoordinate(y),
                        levelZeroCoordinate(width),
                        levelZeroCoordinate(height),
                        0,
                        0);
        JsonObject result =
                measureFile(
                        "small_png",
                        output,
                        (long) width * height,
                        () -> new PngWriter().writeImage(server, request, output.toString()));
        addRegion(result, "PNG", x, y, width, height);
        return result;
    }

    private static JsonObject captureOmeTiff(
            ImageServer<BufferedImage> server, Path output, int x, int y, int width, int height)
            throws Exception {
        JsonObject result =
                measureFile(
                        "large_ome_bigtiff",
                        output,
                        (long) width * height,
                        () -> {
                            var series =
                                    new OMEPyramidWriter.Builder(server)
                                            .region(
                                                    levelZeroCoordinate(x),
                                                    levelZeroCoordinate(y),
                                                    levelZeroCoordinate(width),
                                                    levelZeroCoordinate(height))
                                            .downsamples(CAPTURE_DOWNSAMPLE)
                                            .tileSize(TILE_SIZE)
                                            .compression(OMEPyramidWriter.CompressionType.ZLIB)
                                            .channelsInterleaved()
                                            .bigTiff()
                                            .zSlice(0)
                                            .timePoint(0)
                                            .parallelize(WRITER_THREADS)
                                            .build();
                            OMEPyramidWriter.createWriter(series).writeImage(output.toString());
                        });
        addRegion(result, "TIFF_FAMILY", x, y, width, height);
        return result;
    }

    private static void writeOriginal(ImageServer<BufferedImage> server, Path output)
            throws Exception {
        var series =
                new OMEPyramidWriter.Builder(server)
                        .downsamples(ORIGINAL_DOWNSAMPLES)
                        .tileSize(TILE_SIZE)
                        .uncompressed()
                        .channelsInterleaved()
                        .bigTiff()
                        .zSlice(0)
                        .timePoint(0)
                        .parallelize(WRITER_THREADS)
                        .build();
        OMEPyramidWriter.createWriter(series).writeImage(output.toString());
    }

    private static JsonObject measureFile(
            String name, Path path, long pixels, CheckedRunnable operation) throws Exception {
        PeakHeapSampler sampler = new PeakHeapSampler();
        long started = System.nanoTime();
        sampler.start();
        try {
            operation.run();
            forceFile(path);
        } finally {
            sampler.close();
        }
        double encodeSeconds = secondsSince(started);
        long hashStarted = System.nanoTime();
        String digest = sha256(path);
        double hashSeconds = secondsSince(hashStarted);

        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("path_file_name", path.getFileName().toString());
        result.addProperty("pixels", pixels);
        result.addProperty("encoded_bytes", Files.size(path));
        result.addProperty("sha256", digest);
        result.addProperty("encode_and_force_seconds", encodeSeconds);
        result.addProperty("hash_seconds", hashSeconds);
        result.addProperty("encode_pixels_per_second", pixels / encodeSeconds);
        result.addProperty("peak_observed_heap_bytes", sampler.peakBytes());
        return result;
    }

    private static void addRegion(
            JsonObject result, String container, int x, int y, int width, int height) {
        result.addProperty("container", container);
        result.addProperty("source_resolution_level", CAPTURE_SOURCE_LEVEL);
        JsonObject region = new JsonObject();
        region.addProperty("x", x);
        region.addProperty("y", y);
        region.addProperty("width", width);
        region.addProperty("height", height);
        result.add("source_rect_at_level", region);
        JsonObject mapping = new JsonObject();
        mapping.addProperty("scale_x", CAPTURE_DOWNSAMPLE);
        mapping.addProperty("scale_y", CAPTURE_DOWNSAMPLE);
        mapping.addProperty("translate_x", 0.0);
        mapping.addProperty("translate_y", 0.0);
        result.add("source_level_pixels_to_level_zero", mapping);
    }

    private static JsonArray levels(ImageServer<BufferedImage> server) {
        JsonArray levels = new JsonArray();
        for (int index = 0; index < server.nResolutions(); index++) {
            var level = server.getMetadata().getLevel(index);
            JsonObject value = new JsonObject();
            value.addProperty("series_index", 0);
            value.addProperty("resolution_level", index);
            value.addProperty("width", level.getWidth());
            value.addProperty("height", level.getHeight());
            value.addProperty("downsample", server.getDownsampleForResolution(index));
            levels.add(value);
        }
        return levels;
    }

    private static JsonObject sourceCalibration(ImageServer<BufferedImage> server) {
        JsonObject calibration = new JsonObject();
        calibration.addProperty("mpp_x", server.getPixelCalibration().getPixelWidthMicrons());
        calibration.addProperty("mpp_y", server.getPixelCalibration().getPixelHeightMicrons());
        return calibration;
    }

    private static JsonObject hostEvidence() {
        Runtime runtime = Runtime.getRuntime();
        JsonObject host = new JsonObject();
        host.addProperty("os_name", System.getProperty("os.name"));
        host.addProperty("os_version", System.getProperty("os.version"));
        host.addProperty("os_arch", System.getProperty("os.arch"));
        host.addProperty("java_version", System.getProperty("java.version"));
        host.addProperty("available_processors", runtime.availableProcessors());
        host.addProperty("max_heap_bytes", runtime.maxMemory());
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            host.addProperty("physical_memory_bytes", extended.getTotalMemorySize());
        }
        return host;
    }

    private static JsonObject codeSourceEvidence(Class<?> type) throws Exception {
        URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path path = Path.of(location);
        JsonObject value = new JsonObject();
        value.addProperty("file_name", path.getFileName().toString());
        value.addProperty("implementation_version", type.getPackage().getImplementationVersion());
        if (Files.isRegularFile(path)) {
            value.addProperty("sha256", sha256(path));
        }
        return value;
    }

    private static JsonObject classManifest(Class<?> root) throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        collectClasses(root, classes);
        classes.sort(Comparator.comparing(Class::getName));
        JsonArray entries = new JsonArray();
        for (Class<?> type : classes) {
            JsonObject entry = new JsonObject();
            entry.addProperty("binary_name", type.getName());
            entry.addProperty("sha256", classSha256(type));
            entries.add(entry);
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema_version", 1);
        manifest.add("classes", entries);
        manifest.addProperty("manifest_digest", CanonicalCodec.canonicalDigest(manifest));
        return manifest;
    }

    private static void collectClasses(Class<?> type, List<Class<?>> classes) {
        classes.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectClasses(nested, classes);
        }
    }

    private static String classSha256(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = type.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing class resource: " + resource);
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
    }

    private static void verifyOriginalServer(
            ImageServer<BufferedImage> server, int width, int height) {
        if (server.getWidth() != width
                || server.getHeight() != height
                || server.nResolutions() != ORIGINAL_DOWNSAMPLES.length
                || !server.isRGB()
                || server.nChannels() != 3
                || server.getPixelCalibration().getPixelWidthMicrons() != SOURCE_MPP_X
                || server.getPixelCalibration().getPixelHeightMicrons() != SOURCE_MPP_Y) {
            throw new IllegalStateException("reopened original does not match benchmark source");
        }
        for (int index = 0; index < ORIGINAL_DOWNSAMPLES.length; index++) {
            if (server.getDownsampleForResolution(index) != ORIGINAL_DOWNSAMPLES[index]) {
                throw new IllegalStateException(
                        "reopened original has an unexpected pyramid downsample");
            }
        }
    }

    private static void requireGeometry(
            int originalWidth, int originalHeight, int smallSize, int largeSize) {
        if (originalWidth % TILE_SIZE != 0 || originalHeight % TILE_SIZE != 0) {
            throw new IllegalArgumentException("original dimensions must be tile-aligned");
        }
        if (largeSize <= smallSize
                || levelZeroCoordinate(TILE_SIZE * 2L + largeSize) > originalWidth
                || levelZeroCoordinate(TILE_SIZE * 2L + largeSize) > originalHeight) {
            throw new IllegalArgumentException("capture regions do not fit the source");
        }
    }

    private static int levelZeroCoordinate(long sourceLevelCoordinate) {
        double value = sourceLevelCoordinate * CAPTURE_DOWNSAMPLE;
        if (!Double.isFinite(value) || value != Math.rint(value) || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("source-level coordinate has no exact int mapping");
        }
        return (int) value;
    }

    private static long pyramidPixels(int width, int height) {
        long result = 0;
        for (double downsample : ORIGINAL_DOWNSAMPLES) {
            result += (long) Math.ceil(width / downsample) * (long) Math.ceil(height / downsample);
        }
        return result;
    }

    private static int positive(String raw, String name) {
        int value = Integer.parseInt(raw);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static void prepareEmptyDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                throw new IOException("benchmark output directory must be empty: " + directory);
            }
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static double secondsSince(long started) {
        return (System.nanoTime() - started) / 1_000_000_000.0;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class PeakHeapSampler implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicLong peak = new AtomicLong();
        private Thread thread;

        void start() {
            running.set(true);
            thread = new Thread(this::sample, "histoforge-phase0-heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        long peakBytes() {
            return peak.get();
        }

        private void sample() {
            Runtime runtime = Runtime.getRuntime();
            while (running.get()) {
                peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), Math::max);
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
                thread.join();
            }
        }
    }

    private static final class DeterministicRgbServer extends AbstractTileableImageServer {
        private final ImageServerMetadata metadata;

        DeterministicRgbServer(int width, int height) {
            metadata =
                    new ImageServerMetadata.Builder()
                            .width(width)
                            .height(height)
                            .pixelType(PixelType.UINT8)
                            .channels(ImageChannel.getDefaultRGBChannels())
                            .rgb(true)
                            .pixelSizeMicrons(SOURCE_MPP_X, SOURCE_MPP_Y)
                            .preferredTileSize(TILE_SIZE, TILE_SIZE)
                            .levelsFromDownsamples(ORIGINAL_DOWNSAMPLES)
                            .build();
        }

        @Override
        protected BufferedImage readTile(TileRequest request) {
            int width = request.getTileWidth();
            int height = request.getTileHeight();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] bytes = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            int offset = 0;
            for (int y = 0; y < height; y++) {
                int sourceY = request.getTileY() + y;
                for (int x = 0; x < width; x++) {
                    int mixed = mix(request.getTileX() + x, sourceY, request.getLevel());
                    bytes[offset++] = (byte) mixed;
                    bytes[offset++] = (byte) (mixed >>> 8);
                    bytes[offset++] = (byte) (mixed >>> 16);
                }
            }
            return image;
        }

        private static int mix(int x, int y, int level) {
            int value = x * 0x1f123bb5 ^ y * 0x5f356495 ^ (level + 1) * 0x6c8e9cf5;
            value ^= value >>> 16;
            value *= 0x7feb352d;
            value ^= value >>> 15;
            value *= 0x846ca68b;
            return value ^ value >>> 16;
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
            return getClass().getName() + ":" + metadata.getWidth() + "x" + metadata.getHeight();
        }

        @Override
        public String getServerType() {
            return "HistoForge deterministic Phase 0 RGB source";
        }

        @Override
        public Collection<URI> getURIs() {
            return List.of();
        }
    }
}
