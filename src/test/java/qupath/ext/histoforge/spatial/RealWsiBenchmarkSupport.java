package qupath.ext.histoforge.spatial;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.SourceViewGeometry;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.PixelType;

/** File, runtime, source-metadata, and resource-measurement support for the real-WSI probe. */
final class RealWsiBenchmarkSupport {

    private static final int HASH_BUFFER_BYTES = 8 * 1024 * 1024;

    private RealWsiBenchmarkSupport() {}

    static SourceDescription describe(ImageServer<BufferedImage> server, int selectedLevel) {
        requireSupported(server, selectedLevel);
        JsonArray levels = new JsonArray();
        for (int index = 0; index < server.nResolutions(); index++) {
            var level = server.getMetadata().getLevel(index);
            JsonObject value = new JsonObject();
            value.addProperty("resolution_level", index);
            value.addProperty("width", level.getWidth());
            value.addProperty("height", level.getHeight());
            value.addProperty("downsample", server.getDownsampleForResolution(index));
            levels.add(value);
        }
        JsonObject calibration = new JsonObject();
        calibration.addProperty("mpp_x", server.getPixelCalibration().getPixelWidthMicrons());
        calibration.addProperty("mpp_y", server.getPixelCalibration().getPixelHeightMicrons());
        var selected = server.getMetadata().getLevel(selectedLevel);
        return new SourceDescription(
                server.getServerType(),
                server.getClass(),
                server.getPath(),
                server.getWidth(),
                server.getHeight(),
                server.nZSlices(),
                server.nTimepoints(),
                calibration,
                levels,
                new SourceViewGeometry.Dimensions(selected.getWidth(), selected.getHeight()),
                server.getDownsampleForResolution(selectedLevel));
    }

    static void requireSameSource(
            SourceDescription expected, ImageServer<BufferedImage> source, int selectedLevel) {
        requireSupported(source, selectedLevel);
        var level = source.getMetadata().getLevel(selectedLevel);
        if (source.getWidth() != expected.width()
                || source.getHeight() != expected.height()
                || source.nZSlices() != expected.zSlices()
                || source.nTimepoints() != expected.timepoints()
                || level.getWidth() != expected.selectedDimensions().width()
                || level.getHeight() != expected.selectedDimensions().height()
                || Double.compare(
                                source.getDownsampleForResolution(selectedLevel),
                                expected.selectedDownsample())
                        != 0) {
            throw new IllegalStateException("reopened source metadata changed");
        }
    }

    static OpenedServer open(Path input) throws Exception {
        long started = System.nanoTime();
        ImageServer<BufferedImage> server =
                ImageServerProvider.buildServer(input.toString(), BufferedImage.class);
        return new OpenedServer(server, secondsSince(started));
    }

    static JsonObject measureHash(Path path) throws Exception {
        long started = System.nanoTime();
        String digest = sha256(path);
        JsonObject value = new JsonObject();
        value.addProperty("sha256", digest);
        value.addProperty("seconds", secondsSince(started));
        return value;
    }

    static String sha256(Path path) throws Exception {
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

    static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    static JsonObject hostEvidence() {
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

    static JsonObject codeSourceEvidence(Class<?> type) throws Exception {
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

    static JsonArray runtimeArtifacts(String fileNamePrefix) throws Exception {
        List<Path> paths = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Path.of(entry);
            if (Files.isRegularFile(path)
                    && path.getFileName().toString().startsWith(fileNamePrefix)) {
                paths.add(path);
            }
        }
        paths.sort(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()));
        JsonArray result = new JsonArray();
        for (Path path : paths) {
            JsonObject value = new JsonObject();
            value.addProperty("file_name", path.getFileName().toString());
            value.addProperty("sha256", sha256(path));
            result.add(value);
        }
        return result;
    }

    static JsonObject classManifest(Class<?>... roots) throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        for (Class<?> root : roots) {
            collectClasses(root, classes);
        }
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

    static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class);
    }

    static boolean stable(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && String.valueOf(before.fileKey()).equals(String.valueOf(after.fileKey()));
    }

    static void prepareEmptyDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                throw new IOException("benchmark output directory must be empty: " + directory);
            }
        }
    }

    static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static int nonnegative(String raw, String name) {
        int value = Integer.parseInt(raw);
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
        return value;
    }

    static double secondsSince(long started) {
        return (System.nanoTime() - started) / 1_000_000_000.0;
    }

    private static void requireSupported(ImageServer<BufferedImage> server, int selectedLevel) {
        if (selectedLevel >= server.nResolutions()
                || !server.isRGB()
                || server.getPixelType() != PixelType.UINT8
                || server.nChannels() != 3
                || server.nZSlices() < 1
                || server.nTimepoints() < 1
                || !Double.isFinite(server.getPixelCalibration().getPixelWidthMicrons())
                || !Double.isFinite(server.getPixelCalibration().getPixelHeightMicrons())
                || server.getPixelCalibration().getPixelWidthMicrons() <= 0
                || server.getPixelCalibration().getPixelHeightMicrons() <= 0) {
            throw new IllegalArgumentException(
                    "benchmark requires a calibrated uint8 RGB WSI and an available level");
        }
    }

    record OpenedServer(ImageServer<BufferedImage> server, double seconds) {}

    record SourceDescription(
            String serverType,
            Class<?> serverClass,
            String serverPath,
            int width,
            int height,
            int zSlices,
            int timepoints,
            JsonObject calibration,
            JsonArray levels,
            SourceViewGeometry.Dimensions selectedDimensions,
            double selectedDownsample) {}

    static final class PeakHeapSampler implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicLong peak = new AtomicLong();
        private Thread thread;

        void start() {
            running.set(true);
            thread = new Thread(this::sample, "histoforge-real-wsi-heap-sampler");
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
}
