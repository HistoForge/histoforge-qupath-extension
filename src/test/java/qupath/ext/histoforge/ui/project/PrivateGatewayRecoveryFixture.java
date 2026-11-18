package qupath.ext.histoforge.ui.project;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.platform.ClientBuildIdentityProvider;
import qupath.ext.histoforge.qualification.QualificationProbeIdentity;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.ext.histoforge.util.SecureFileStore;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.CroppedImageServer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImageRegion;
import qupath.lib.regions.RegionRequest;

/** Real-pixel disposable QuPath project and durable qualification evidence support. */
final class PrivateGatewayRecoveryFixture {

    static final int CROP_X = 66_442;
    static final int CROP_Y = 197_386;
    static final int CROP_WIDTH = 2_000;
    static final int CROP_HEIGHT = 2_000;

    private static final int TILE_SIZE = 512;
    private static final int WRITER_THREADS = 4;

    private PrivateGatewayRecoveryFixture() {}

    record LoadedProject(Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry) {}

    record GuiSession(QuPathGUI qupath, LoadedProject loaded) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            onFx(
                    () -> {
                        if (qupath.getImageData() != null) {
                            qupath.getViewer().setImageData(null);
                        }
                        qupath.setProject(null);
                        Object stage = QuPathGUI.class.getMethod("getStage").invoke(qupath);
                        stage.getClass().getMethod("close").invoke(stage);
                        return null;
                    });
            Class.forName("javafx.application.Platform").getMethod("exit").invoke(null);
        }
    }

    static void prepare(Path source, String expectedSourceSha256, Path root) throws Exception {
        requireRegularFile(source, "source WSI");
        if (!Files.isDirectory(root)) {
            throw new IOException("qualification root must already exist");
        }
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IOException("qualification root must be empty");
            }
        }

        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class);
        long started = System.nanoTime();
        String sourceSha256 = sha256(source);
        if (!sourceSha256.equals(
                CanonicalCodec.requireSha256(expectedSourceSha256, "expected_source_sha256"))) {
            throw new IOException("source WSI digest differs from the retained benchmark");
        }

        Path sourceDirectory = Files.createDirectory(root.resolve("source"));
        Path crop = sourceDirectory.resolve("qualification.ome.tif");
        CropEvidence cropEvidence = writeAndVerifyCrop(source, crop);
        BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class);
        require(stable(before, after), "source WSI changed during crop materialization");

        Path projectDirectory = Files.createDirectory(root.resolve("project"));
        Project<BufferedImage> project =
                Projects.createProject(projectDirectory.toFile(), BufferedImage.class);
        ProjectImageEntry<BufferedImage> entry;
        try (ImageServer<BufferedImage> server = ImageServers.buildServer(crop.toUri())) {
            entry = project.addImage(server.getBuilder());
        }
        require(entry != null, "QuPath rejected the bounded real-pixel source");
        entry.setImageName("private-gateway-recovery-qualification");
        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            imageData.setImageType(ImageType.BRIGHTFIELD_H_E);
            entry.saveImageData(imageData);
        }
        project.syncChanges();
        require(project.getPath() != null, "prepared project has no stable path");

        ClientBuildIdentity build = ClientBuildIdentityProvider.capture();
        JsonObject evidence = baseEvidence("prepare", build);
        evidence.addProperty("source_sha256", sourceSha256);
        evidence.addProperty("source_bytes", before.size());
        evidence.addProperty("source_stable", true);
        evidence.addProperty("crop_x", CROP_X);
        evidence.addProperty("crop_y", CROP_Y);
        evidence.addProperty("crop_width", CROP_WIDTH);
        evidence.addProperty("crop_height", CROP_HEIGHT);
        evidence.addProperty("crop_bytes", Files.size(crop));
        evidence.addProperty("crop_sha256", sha256(crop));
        evidence.addProperty("crop_rgb_sha256", cropEvidence.rgbSha256());
        evidence.addProperty("crop_mpp_x", cropEvidence.mppX());
        evidence.addProperty("crop_mpp_y", cropEvidence.mppY());
        evidence.addProperty("crop_exact_rgb_verified", true);
        evidence.addProperty("project_entry_id", entry.getID());
        evidence.add("initial_image_state", hierarchySummary(entry, build));
        evidence.addProperty("elapsed_seconds", elapsedSeconds(started));
        writeEvidence(evidencePath(root, "prepare"), evidence);
    }

    static void isolateUserState(Path root, Path credential) throws IOException {
        requireRegularFile(credential, "deployment credential");
        Path home = Files.createDirectories(root.resolve("home"));
        Path preferences = Files.createDirectories(root.resolve("preferences"));
        Path isolatedCredential = home.resolve(".histoforge/deployment.json");
        SecureFileStore.writeOwnerOnly(
                isolatedCredential, Files.readString(credential, StandardCharsets.UTF_8));
        System.setProperty("user.home", home.toString());
        System.setProperty("java.util.prefs.userRoot", preferences.toString());
        System.setProperty("qupath.startup.script", "block");
    }

    static LoadedProject load(Path root) throws IOException {
        // Project entries contain the ImageServerBuilder interface. Initialize QuPath's
        // polymorphic builder adapter before ProjectIO asks Gson to deserialize that interface.
        ImageServers.getServerBuilderFactory();
        Path projectPath;
        try (var paths = Files.list(root.resolve("project"))) {
            List<Path> projects =
                    paths.filter(
                                    path ->
                                            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                                    && path.getFileName()
                                                            .toString()
                                                            .endsWith(".qpproj"))
                            .toList();
            if (projects.size() != 1) {
                throw new IOException("qualification fixture must contain exactly one project");
            }
            projectPath = projects.getFirst();
        }
        Project<BufferedImage> project =
                ProjectIO.loadProject(projectPath.toUri(), BufferedImage.class);
        if (project.getImageList().size() != 1) {
            throw new IOException("qualification project must contain exactly one image");
        }
        return new LoadedProject(project, project.getImageList().getFirst());
    }

    static GuiSession openGui(Path root) throws Exception {
        startJavaFx();
        LoadedProject loaded = load(root);
        QuPathGUI qupath =
                onFx(
                        () -> {
                            QuPathGUI instance = QuPathGUI.createHiddenInstance();
                            Object stage = QuPathGUI.class.getMethod("getStage").invoke(instance);
                            stage.getClass()
                                    .getMethod("setOpacity", double.class)
                                    .invoke(stage, 0.0);
                            stage.getClass().getMethod("show").invoke(stage);
                            instance.setProject(loaded.project());
                            return instance;
                        });
        return new GuiSession(qupath, loaded);
    }

    static JsonObject hierarchySummary(
            ProjectImageEntry<BufferedImage> entry, ClientBuildIdentity build) throws Exception {
        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            ImageSourceCapture.CapturedSource source =
                    new ImageSourceCapture().capture(imageData.getServer());
            String sourceDigest = SourceIdentityCodec.imageSourceIdentityDigest(source.identity());
            var revision =
                    ImageDataRevisionDigester.capture(
                            entry.getID(), sourceDigest, imageData, build.limits());
            List<PathObject> objects = imageData.getHierarchy().getFlattenedObjectList(null);
            Map<String, Integer> classes = new LinkedHashMap<>();
            objects.stream()
                    .sorted(Comparator.comparing(PrivateGatewayRecoveryFixture::className))
                    .forEach(object -> classes.merge(className(object), 1, Math::addExact));

            JsonObject value = new JsonObject();
            value.addProperty("image_source_digest", sourceDigest);
            value.addProperty("image_data_content_digest", revision.imageDataContentDigest());
            value.addProperty(
                    "hierarchy_digest",
                    ImageDataRevisionDigester.hierarchySemanticDigest(imageData.getHierarchy()));
            value.addProperty("object_count", objects.size());
            value.addProperty(
                    "detection_count", imageData.getHierarchy().getDetectionObjects().size());
            value.addProperty(
                    "annotation_count", imageData.getHierarchy().getAnnotationObjects().size());
            JsonObject classCounts = new JsonObject();
            classes.forEach(classCounts::addProperty);
            value.add("class_counts", classCounts);
            return value;
        }
    }

    static JsonObject baseEvidence(String operation, ClientBuildIdentity build) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", 1);
        value.addProperty("probe", "histoforge-private-gateway-recovery-qualification");
        value.addProperty("operation", operation);
        value.addProperty("captured_at", Instant.now().toString());
        value.add("qualification_probe", QualificationProbeIdentity.capture());
        value.add("client_build", BuildIdentityCodec.toJson(build));
        value.addProperty("qupath_version", String.valueOf(QuPathGUI.getVersion()));
        value.addProperty("java_version", Runtime.version().toString());
        value.addProperty("os_arch", System.getProperty("os.arch"));
        return value;
    }

    static Path evidencePath(Path root, String operation) throws IOException {
        Path evidence = Files.createDirectories(root.resolve("evidence"));
        return evidence.resolve(operation + ".json");
    }

    static void writeEvidence(Path output, JsonObject evidence) throws IOException {
        JsonObject value = evidence.deepCopy();
        if (value.has("evidence_digest")) {
            throw new IllegalArgumentException("evidence already contains a digest");
        }
        value.addProperty("evidence_digest", CanonicalCodec.canonicalDigest(value));
        String encoded =
                new String(CanonicalCodec.canonicalBytes(value), StandardCharsets.UTF_8) + "\n";
        SecureFileStore.writeOwnerOnly(output, encoded);
        require(readEvidence(output).equals(value), "published evidence failed exact reread");
    }

    static JsonObject readEvidence(Path input) throws IOException {
        byte[] encoded = Files.readAllBytes(input);
        JsonObject value;
        try {
            value = CanonicalCodec.parseJson(encoded).getAsJsonObject();
        } catch (IllegalArgumentException failure) {
            throw new IOException("qualification evidence is invalid", failure);
        }
        if (!value.has("evidence_digest")) {
            throw new IOException("qualification evidence lacks its digest");
        }
        String claimed = value.get("evidence_digest").getAsString();
        JsonObject preimage = value.deepCopy();
        preimage.remove("evidence_digest");
        if (!claimed.equals(CanonicalCodec.canonicalDigest(preimage))) {
            throw new IOException("qualification evidence digest differs");
        }
        byte[] canonical = CanonicalCodec.canonicalBytes(value);
        byte[] expected = Arrays.copyOf(canonical, canonical.length + 1);
        expected[expected.length - 1] = '\n';
        if (!Arrays.equals(encoded, expected)) {
            throw new IOException("qualification evidence is not canonical JSON plus newline");
        }
        return value;
    }

    static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(8 * 1024 * 1024);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static double elapsedSeconds(long started) {
        return (System.nanoTime() - started) / 1_000_000_000.0;
    }

    private static CropEvidence writeAndVerifyCrop(Path input, Path output) throws Exception {
        double mppX;
        double mppY;
        try (ImageServer<BufferedImage> source =
                        ImageServerProvider.buildServer(input.toString(), BufferedImage.class);
                CroppedImageServer crop =
                        new CroppedImageServer(
                                source,
                                ImageRegion.createInstance(
                                        CROP_X, CROP_Y, CROP_WIDTH, CROP_HEIGHT, 0, 0))) {
            require(source.getWidth() >= CROP_X + CROP_WIDTH, "crop exceeds source width");
            require(source.getHeight() >= CROP_Y + CROP_HEIGHT, "crop exceeds source height");
            mppX = source.getPixelCalibration().getPixelWidthMicrons();
            mppY = source.getPixelCalibration().getPixelHeightMicrons();
            require(Double.isFinite(mppX) && mppX > 0, "source mpp_x is invalid");
            require(Double.isFinite(mppY) && mppY > 0, "source mpp_y is invalid");
            writeTiff(crop, output);
        }
        forceFile(output);

        String rgbDigest;
        try (ImageServer<BufferedImage> source =
                        ImageServerProvider.buildServer(input.toString(), BufferedImage.class);
                ImageServer<BufferedImage> copy =
                        ImageServerProvider.buildServer(output.toString(), BufferedImage.class)) {
            require(copy.getWidth() == CROP_WIDTH, "crop copy width differs");
            require(copy.getHeight() == CROP_HEIGHT, "crop copy height differs");
            require(copy.nResolutions() == 1, "crop copy is not one-level");
            require(copy.isRGB(), "crop copy is not RGB");
            require(
                    Double.compare(copy.getPixelCalibration().getPixelWidthMicrons(), mppX) == 0,
                    "crop copy mpp_x differs");
            require(
                    Double.compare(copy.getPixelCalibration().getPixelHeightMicrons(), mppY) == 0,
                    "crop copy mpp_y differs");
            rgbDigest = requireExactRgb(source, copy);
        }
        return new CropEvidence(mppX, mppY, rgbDigest);
    }

    private static void writeTiff(ImageServer<BufferedImage> source, Path output)
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
        } catch (Exception failure) {
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw new IOException("failed to encode bounded OME-BigTIFF", failure);
        }
    }

    private static String requireExactRgb(
            ImageServer<BufferedImage> source, ImageServer<BufferedImage> copy) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer rgb = ByteBuffer.allocate(TILE_SIZE * TILE_SIZE * 3);
        for (int y = 0; y < CROP_HEIGHT; y += TILE_SIZE) {
            int height = Math.min(TILE_SIZE, CROP_HEIGHT - y);
            for (int x = 0; x < CROP_WIDTH; x += TILE_SIZE) {
                int width = Math.min(TILE_SIZE, CROP_WIDTH - x);
                BufferedImage expected =
                        source.readRegion(
                                RegionRequest.createInstance(
                                        source.getPath(),
                                        1.0,
                                        CROP_X + x,
                                        CROP_Y + y,
                                        width,
                                        height,
                                        0,
                                        0));
                BufferedImage actual =
                        copy.readRegion(
                                RegionRequest.createInstance(
                                        copy.getPath(), 1.0, x, y, width, height, 0, 0));
                require(expected != null && actual != null, "crop verification tile is absent");
                require(
                        expected.getWidth() == width
                                && expected.getHeight() == height
                                && actual.getWidth() == width
                                && actual.getHeight() == height,
                        "crop verification tile dimensions differ");
                rgb.clear();
                for (int row = 0; row < height; row++) {
                    for (int column = 0; column < width; column++) {
                        int left = expected.getRGB(column, row);
                        int right = actual.getRGB(column, row);
                        require((left & 0x00ff_ffff) == (right & 0x00ff_ffff), "crop RGB differs");
                        rgb.put((byte) (left >>> 16));
                        rgb.put((byte) (left >>> 8));
                        rgb.put((byte) left);
                    }
                }
                rgb.flip();
                digest.update(rgb);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void startJavaFx() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Class<?> platform = Class.forName("javafx.application.Platform");
        Runnable callback =
                () -> {
                    try {
                        platform.getMethod("setImplicitExit", boolean.class).invoke(null, false);
                        started.complete(null);
                    } catch (Throwable failure) {
                        started.completeExceptionally(failure);
                    }
                };
        platform.getMethod("startup", Runnable.class).invoke(null, callback);
        started.get(60, TimeUnit.SECONDS);
    }

    static <T> T onFx(Callable<T> operation) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable callback =
                () -> {
                    try {
                        result.complete(operation.call());
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                };
        Class.forName("javafx.application.Platform")
                .getMethod("runLater", Runnable.class)
                .invoke(null, callback);
        try {
            return result.get(120, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private static void requireRegularFile(Path path, String field) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(field + " must be a regular file");
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static boolean stable(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && String.valueOf(before.fileKey()).equals(String.valueOf(after.fileKey()));
    }

    private static String className(PathObject object) {
        return object.getPathClass() == null ? "<unclassified>" : object.getPathClass().getName();
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record CropEvidence(double mppX, double mppY, String rgbSha256) {}
}
