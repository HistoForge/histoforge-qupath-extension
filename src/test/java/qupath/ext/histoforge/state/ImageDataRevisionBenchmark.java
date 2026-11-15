package qupath.ext.histoforge.state;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

/** Opt-in Phase 0 benchmark for semantic hierarchy capture and public QuPath persistence. */
public final class ImageDataRevisionBenchmark {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final String IMAGE_SOURCE_DIGEST = "a".repeat(64);
    private static final String PROVENANCE_VALUE =
            "{\"schema_version\":1,\"module_ref\":\"histoforge/spermatogenesis\","
                    + "\"producer\":\"germ_cell\",\"output_generation_id\":"
                    + "\"00000000-0000-4000-8000-000000000000\",\"scope\":\"parent\"}";

    private ImageDataRevisionBenchmark() {}

    /** Run one explicit hierarchy scale and print one canonical evidence record. */
    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        Path directory = Files.createTempDirectory("histoforge-phase0-image-data-");
        try {
            JsonObject result = run(directory, config);
            System.out.println(
                    "PHASE0_IMAGE_DATA_BENCHMARK_JSON="
                            + new String(
                                    CanonicalCodec.canonicalBytes(result), StandardCharsets.UTF_8));
        } finally {
            deleteTree(directory);
        }
    }

    private static JsonObject run(Path directory, Config config) throws Exception {
        Path source = directory.resolve("source.png");
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        if (!ImageIO.write(image, "PNG", source.toFile())) {
            throw new IOException("PNG writer unavailable");
        }

        Path projectDirectory = Files.createDirectory(directory.resolve("project"));
        var project = Projects.createProject(projectDirectory.toFile(), BufferedImage.class);
        ProjectImageEntry<BufferedImage> entry;
        try (var server = ImageServers.buildServer(source.toUri())) {
            entry = project.addImage(server.getBuilder());
        }

        resetHeapPeaks();
        long buildStarted = System.nanoTime();
        BenchmarkHierarchy hierarchy;
        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            imageData.setImageType(ImageType.BRIGHTFIELD_H_E);
            hierarchy = buildDetachedHierarchy(config);
            long buildNanos = System.nanoTime() - buildStarted;

            long applyStarted = System.nanoTime();
            imageData.getHierarchy().getRootObject().addChildObjects(hierarchy.parents());
            for (int index = 0; index < hierarchy.parents().size(); index++) {
                hierarchy.parents().get(index).addChildObjects(hierarchy.children().get(index));
            }
            imageData.getHierarchy().fireHierarchyChangedEvent(ImageDataRevisionBenchmark.class);
            long applyNanos = System.nanoTime() - applyStarted;

            long captureStarted = System.nanoTime();
            var captured =
                    ImageDataRevisionDigester.capture(
                            "benchmark-image", IMAGE_SOURCE_DIGEST, imageData, unlimitedLimits());
            long captureNanos = System.nanoTime() - captureStarted;
            var captureSize = ImageDataRevisionDigester.measureCaptureSize(imageData);
            String beforePreimage =
                    canonicalText(ImageDataRevisionDigester.canonicalContentPreimage(imageData));

            long saveStarted = System.nanoTime();
            entry.saveImageData(imageData);
            long saveNanos = System.nanoTime() - saveStarted;
            var afterSave =
                    ImageDataRevisionDigester.capture(
                            "benchmark-image", IMAGE_SOURCE_DIGEST, imageData, unlimitedLimits());
            String afterSavePreimage =
                    canonicalText(ImageDataRevisionDigester.canonicalContentPreimage(imageData));

            long reloadStarted = System.nanoTime();
            try (ImageData<BufferedImage> reloaded = entry.readImageData()) {
                var reloadedCapture =
                        ImageDataRevisionDigester.capture(
                                "benchmark-image",
                                IMAGE_SOURCE_DIGEST,
                                reloaded,
                                unlimitedLimits());
                if (!captured.equals(reloadedCapture)) {
                    throw new IllegalStateException(
                            "semantic revision changed after save/reload: before="
                                    + captured.imageDataContentDigest()
                                    + ", after_save="
                                    + afterSave.imageDataContentDigest()
                                    + ", reloaded="
                                    + reloadedCapture.imageDataContentDigest()
                                    + ", before_preimage="
                                    + beforePreimage
                                    + ", after_save_preimage="
                                    + afterSavePreimage
                                    + ", reloaded_preimage="
                                    + canonicalText(
                                            ImageDataRevisionDigester.canonicalContentPreimage(
                                                    reloaded)));
                }
                if (!captureSize.equals(ImageDataRevisionDigester.measureCaptureSize(reloaded))) {
                    throw new IllegalStateException("resource totals changed after save/reload");
                }
            }
            long reloadNanos = System.nanoTime() - reloadStarted;

            JsonObject result = environment();
            result.addProperty("schema_version", 1);
            result.add("config", config.toJson());
            JsonObject totals = new JsonObject();
            totals.addProperty("objects", captureSize.objects());
            totals.addProperty("geometry_vertices", captureSize.geometryVertices());
            totals.addProperty("measurements", captureSize.measurements());
            totals.addProperty("text_utf8_bytes", captureSize.textUtf8Bytes());
            totals.addProperty("project_regular_file_bytes", regularFileBytes(projectDirectory));
            result.add("totals", totals);
            JsonObject timing = new JsonObject();
            timing.addProperty("build_millis", millis(buildNanos));
            timing.addProperty("apply_millis", millis(applyNanos));
            timing.addProperty("capture_millis", millis(captureNanos));
            timing.addProperty("save_millis", millis(saveNanos));
            timing.addProperty("reload_and_capture_millis", millis(reloadNanos));
            result.add("timing", timing);
            JsonObject memory = new JsonObject();
            memory.addProperty("maximum_heap_bytes", Runtime.getRuntime().maxMemory());
            memory.addProperty("peak_heap_pool_bytes", peakHeapBytes());
            result.add("memory", memory);
            result.addProperty("image_data_content_digest", captured.imageDataContentDigest());
            return result;
        }
    }

    private static BenchmarkHierarchy buildDetachedHierarchy(Config config) {
        List<PathObject> parents = new ArrayList<>(config.parentCount());
        List<List<PathObject>> children = new ArrayList<>(config.parentCount());
        PathClass parentClass = PathClass.fromString("Tubule (Cross Section)");
        List<PathClass> childClasses =
                List.of(
                        PathClass.fromString("Sertoli"),
                        PathClass.fromString("Gonocyte"),
                        PathClass.fromString("Spermatogonia"),
                        PathClass.fromString("Spermatocyte"),
                        PathClass.fromString("Round Spermatid"),
                        PathClass.fromString("Elongated Spermatid"));
        double[][] parentOffsets = polygonOffsets(64, 4.0);
        double[][] childOffsets = polygonOffsets(config.childVertices(), 0.35);

        for (int parentIndex = 0; parentIndex < config.parentCount(); parentIndex++) {
            double centerX = 8.0 + (parentIndex % 100) * 0.4;
            double centerY = 8.0 + ((parentIndex / 100) % 100) * 0.4;
            PathObject parent =
                    PathObjects.createAnnotationObject(
                            polygon(centerX, centerY, parentOffsets), parentClass);
            parent.setID(new UUID(0x1000_0000_0000_0000L, parentIndex + 1L));
            parent.getMetadata().put("histoforge:provenance", PROVENANCE_VALUE);
            addMeasurements(parent, config.measurementsPerObject(), parentIndex);
            parents.add(parent);

            List<PathObject> oneParent = new ArrayList<>(config.childrenPerParent());
            for (int childIndex = 0; childIndex < config.childrenPerParent(); childIndex++) {
                double childX = centerX + ((childIndex % 10) - 4.5) * 0.6;
                double childY = centerY + ((childIndex / 10) - 4.5) * 0.6;
                PathObject child =
                        PathObjects.createDetectionObject(
                                polygon(childX, childY, childOffsets),
                                childClasses.get(childIndex % childClasses.size()));
                child.setID(
                        new UUID(
                                0x2000_0000_0000_0000L | parentIndex,
                                ((long) parentIndex << 32) | Integer.toUnsignedLong(childIndex)));
                child.getMetadata().put("histoforge:provenance", PROVENANCE_VALUE);
                addMeasurements(child, config.measurementsPerObject(), childIndex);
                oneParent.add(child);
            }
            children.add(oneParent);
        }
        return new BenchmarkHierarchy(parents, children);
    }

    private static void addMeasurements(PathObject object, int count, int salt) {
        for (int index = 0; index < count; index++) {
            object.getMeasurementList().put("Measurement " + index, salt + index / 10.0);
        }
    }

    private static qupath.lib.roi.interfaces.ROI polygon(
            double centerX, double centerY, double[][] offsets) {
        List<Point2> points = new ArrayList<>(offsets.length);
        for (double[] offset : offsets) {
            points.add(new Point2(centerX + offset[0], centerY + offset[1]));
        }
        return ROIs.createPolygonROI(points, ImagePlane.getDefaultPlane());
    }

    private static double[][] polygonOffsets(int vertices, double radius) {
        double[][] result = new double[vertices][2];
        for (int index = 0; index < vertices; index++) {
            double angle = 2.0 * Math.PI * index / vertices;
            result[index][0] = radius * Math.cos(angle);
            result[index][1] = radius * Math.sin(angle);
        }
        return result;
    }

    private static ClientExecutionLimits unlimitedLimits() {
        return new ClientExecutionLimits(
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER);
    }

    private static JsonObject environment() {
        JsonObject result = new JsonObject();
        result.addProperty("os_name", System.getProperty("os.name"));
        result.addProperty("os_version", System.getProperty("os.version"));
        result.addProperty("os_arch", System.getProperty("os.arch"));
        result.addProperty("java_version", System.getProperty("java.version"));
        result.addProperty("available_processors", Runtime.getRuntime().availableProcessors());
        return result;
    }

    private static String canonicalText(com.google.gson.JsonElement value) {
        return new String(CanonicalCodec.canonicalBytes(value), StandardCharsets.UTF_8);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static long regularFileBytes(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            long total = 0;
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) {
                    total = Math.addExact(total, Files.size(path));
                }
            }
            return total;
        }
    }

    private static void resetHeapPeaks() {
        ManagementFactory.getMemoryPoolMXBeans().forEach(pool -> pool.resetPeakUsage());
    }

    private static long peakHeapBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private record Config(
            int parentCount, int childrenPerParent, int childVertices, int measurementsPerObject) {

        static Config parse(String[] args) {
            if (args.length != 4) {
                throw new IllegalArgumentException(
                        "expected parentCount childrenPerParent childVertices measurementsPerObject");
            }
            return new Config(
                    positiveInt(args[0], "parentCount"),
                    positiveInt(args[1], "childrenPerParent"),
                    atLeast(args[2], "childVertices", 3),
                    atLeast(args[3], "measurementsPerObject", 0));
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("parent_count", parentCount);
            result.addProperty("children_per_parent", childrenPerParent);
            result.addProperty("parent_vertices", 64);
            result.addProperty("child_vertices", childVertices);
            result.addProperty("measurements_per_object", measurementsPerObject);
            result.addProperty(
                    "provenance_utf8_bytes",
                    PROVENANCE_VALUE.getBytes(StandardCharsets.UTF_8).length);
            return result;
        }

        private static int positiveInt(String value, String name) {
            return atLeast(value, name, 1);
        }

        private static int atLeast(String value, String name, int minimum) {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum) {
                throw new IllegalArgumentException(name + " must be at least " + minimum);
            }
            return parsed;
        }
    }

    private record BenchmarkHierarchy(List<PathObject> parents, List<List<PathObject>> children) {}
}
