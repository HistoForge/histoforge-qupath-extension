package qupath.ext.histoforge.platform;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageData.ImageType;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.Projects;

/** Opt-in probe for the public live-viewer lifecycle on supported QuPath hosts. */
public final class QuPathViewerLifecycleProbe {

    private static final String REVIEWED_QUPATH_06_SOURCE_COMMIT =
            "cc7a0a9c8ca8e2264ad9f6c0ae4faff59bd66f1d";
    private static final String PROBE_PROPERTY = "histoforge:phase0-viewer-lifecycle";
    private static final Path PROBE_SOURCE =
            Path.of(
                    "src/test/java/qupath/ext/histoforge/platform/"
                            + "QuPathViewerLifecycleProbe.java");

    private QuPathViewerLifecycleProbe() {}

    /** Run the live probe and write one retained JSON evidence record. */
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one evidence-output path");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Path temporaryRoot = null;
        int exitCode = 0;
        try {
            temporaryRoot = Files.createTempDirectory("histoforge-phase0-viewer-");
            isolateUserState(temporaryRoot);
            startJavaFx();

            JsonObject evidence = runProbe(temporaryRoot);
            evidence.addProperty(
                    "evidence_digest", sha256(CanonicalCodec.canonicalBytes(evidence)));

            Files.createDirectories(output.getParent());
            Files.writeString(
                    output,
                    new GsonBuilder()
                                    .setPrettyPrinting()
                                    .disableHtmlEscaping()
                                    .create()
                                    .toJson(evidence)
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            System.out.println(
                    "PHASE0_QUPATH_VIEWER_LIFECYCLE_JSON="
                            + new String(
                                    CanonicalCodec.canonicalBytes(evidence),
                                    StandardCharsets.UTF_8));
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            exitCode = 1;
        } finally {
            try {
                exitJavaFx();
            } catch (Throwable ignored) {
                // The toolkit may not have started; preserve the original failure.
            }
            if (temporaryRoot != null) {
                try {
                    deleteTree(temporaryRoot);
                } catch (IOException cleanupFailure) {
                    cleanupFailure.printStackTrace(System.err);
                    exitCode = 1;
                }
            }
        }
        System.exit(exitCode);
    }

    private static JsonObject runProbe(Path temporaryRoot) throws Exception {
        ProjectFixture fixture = createProject(temporaryRoot);
        QuPathGUI qupath =
                onFx(
                        () -> {
                            QuPathGUI instance = QuPathGUI.createHiddenInstance();
                            showTransparentStage(instance);
                            return instance;
                        });
        onFx(
                () -> {
                    qupath.setProject(fixture.project());
                    return null;
                });

        ImageData<BufferedImage> firstLive = null;
        ImageData<BufferedImage> secondLive = null;
        ImageData<BufferedImage> reloadedFirst = null;
        TrackedWorkingCopy navigatedWorking = null;
        TrackedWorkingCopy activeWorking = null;
        try {
            firstLive = openAndSynchronize(qupath, fixture.first());
            navigatedWorking = captureWorking(fixture.first(), "saved-while-viewer-on-second");
            require(firstLive != navigatedWorking.imageData(), "working copy aliases live image");
            require(
                    firstLive.getServer() != navigatedWorking.imageData().getServer(),
                    "working copy aliases live server");

            secondLive = openAndSynchronize(qupath, fixture.second());
            close(firstLive);
            firstLive = null;
            ImageData<BufferedImage> secondAtCommit = secondLive;
            TrackedWorkingCopy navigatedWorkingAtCommit = navigatedWorking;

            CommitObservation navigated =
                    onFx(
                            () ->
                                    saveThenRefreshStillActiveViewer(
                                            qupath, fixture.first(), navigatedWorkingAtCommit));
            require(!navigated.viewerWasTarget(), "navigated scenario saw target as active");
            require(!navigated.replacementAttempted(), "navigated scenario attempted replacement");
            require(!navigated.viewerAdoptedWorking(), "navigated scenario adopted working copy");
            require(
                    onFx(qupath::getImageData) == secondAtCommit,
                    "background commit navigated away from the user's active image");
            require(navigatedWorking.closeCalls() == 1, "detached working server was not closed");
            requirePersistedProperty(fixture.first(), "saved-while-viewer-on-second");

            reloadedFirst = openAndSynchronize(qupath, fixture.first());
            close(secondLive);
            secondLive = null;
            activeWorking = captureWorking(fixture.first(), "saved-and-installed-in-viewer");
            require(
                    reloadedFirst != activeWorking.imageData(),
                    "active working copy aliases viewer image");
            require(
                    reloadedFirst.getServer() != activeWorking.imageData().getServer(),
                    "active working copy aliases viewer server");
            TrackedWorkingCopy activeWorkingAtCommit = activeWorking;

            CommitObservation active =
                    onFx(
                            () ->
                                    saveThenRefreshStillActiveViewer(
                                            qupath, fixture.first(), activeWorkingAtCommit));
            require(active.viewerWasTarget(), "active scenario did not resolve target viewer");
            require(active.replacementAttempted(), "active scenario skipped replacement");
            require(active.replacementError() == null, "active replacement threw unexpectedly");
            require(active.viewerAdoptedWorking(), "viewer did not adopt exact working copy");
            require(
                    onFx(qupath::getImageData) == activeWorking.imageData(),
                    "viewer does not hold exact committed working ImageData");
            require(activeWorking.closeCalls() == 0, "viewer-owned server closed during commit");
            requirePersistedProperty(fixture.first(), "saved-and-installed-in-viewer");

            close(reloadedFirst);
            reloadedFirst = null;
            onFx(
                    () -> {
                        qupath.getViewer().setImageData(null);
                        return null;
                    });
            require(onFx(qupath::getImageData) == null, "viewer did not release working ImageData");
            activeWorking.close();
            require(activeWorking.closeCalls() == 1, "released viewer server was not closed once");

            JsonObject result = environment();
            result.addProperty("schema_version", 1);
            result.addProperty("captured_at", Instant.now().toString());
            // The historical source review is not the identity of every tested host.
            // environment() and the GUI JAR hash below identify the actual runtime.
            result.addProperty(
                    "reviewed_qupath_06_source_commit", REVIEWED_QUPATH_06_SOURCE_COMMIT);
            result.addProperty("probe_source_sha256", sha256(Files.readAllBytes(PROBE_SOURCE)));
            result.addProperty("probe_class_sha256", classDigest(QuPathViewerLifecycleProbe.class));
            result.addProperty("qupath_gui_jar_sha256", codeSourceDigest(QuPathGUI.class));
            JsonObject navigatedResult = navigated.toJson(navigatedWorking.closeCalls());
            navigatedResult.addProperty("separate_working_image", true);
            navigatedResult.addProperty("separate_working_server", true);
            navigatedResult.addProperty("other_viewer_image_preserved_exactly", true);
            navigatedResult.addProperty("persisted_property_round_trip", true);
            result.add("navigated_away", navigatedResult);
            JsonObject activeResult = active.toJson(activeWorking.closeCalls());
            activeResult.addProperty("separate_working_image", true);
            activeResult.addProperty("separate_working_server", true);
            activeResult.addProperty("persisted_property_round_trip", true);
            activeResult.addProperty("viewer_released_before_close", true);
            result.add("still_active", activeResult);
            return result;
        } finally {
            closeQuietly(firstLive);
            closeQuietly(secondLive);
            closeQuietly(reloadedFirst);
            closeQuietly(navigatedWorking);
            closeQuietly(activeWorking);
            try {
                onFx(
                        () -> {
                            if (qupath.getViewer().getImageData() != null) {
                                qupath.getViewer().setImageData(null);
                            }
                            qupath.setProject(null);
                            closeStage(qupath);
                            return null;
                        });
            } catch (Exception cleanupFailure) {
                cleanupFailure.printStackTrace(System.err);
            }
        }
    }

    /**
     * Exercise the exact public host cut that Phase 3 must implement. This is probe code, not a
     * production lifecycle implementation: save first, then conditionally replace, then reread the
     * viewer before deciding who closes the working server.
     */
    private static CommitObservation saveThenRefreshStillActiveViewer(
            QuPathGUI qupath, ProjectImageEntry<BufferedImage> entry, TrackedWorkingCopy working)
            throws Exception {
        require(isFxApplicationThread(), "save/refresh did not run on the FX thread");
        List<String> events = new ArrayList<>();
        events.add("save-start");
        entry.saveImageData(working.imageData());
        events.add("save-returned");

        boolean viewerWasTarget = isViewerOnEntry(qupath, entry);
        boolean replacementAttempted = false;
        String replacementError = null;
        if (viewerWasTarget && qupath.getImageData() != working.imageData()) {
            replacementAttempted = true;
            events.add("viewer-replacement-start");
            try {
                qupath.getViewer().setImageData(working.imageData());
                events.add("viewer-replacement-returned");
            } catch (Exception failure) {
                replacementError = failure.getClass().getName() + ": " + failure.getMessage();
                events.add("viewer-replacement-threw");
            }
        }

        events.add("viewer-reread");
        boolean viewerAdoptedWorking = qupath.getImageData() == working.imageData();
        if (!viewerAdoptedWorking) {
            working.close();
            events.add("working-close");
        }
        return new CommitObservation(
                viewerWasTarget,
                replacementAttempted,
                viewerAdoptedWorking,
                replacementError,
                working.closeCalls(),
                List.copyOf(events));
    }

    /** Attach the real scene graph without presenting an interactive window to the user. */
    private static void showTransparentStage(QuPathGUI qupath) throws Exception {
        Object stage = QuPathGUI.class.getMethod("getStage").invoke(qupath);
        stage.getClass().getMethod("setOpacity", double.class).invoke(stage, 0.0);
        stage.getClass().getMethod("show").invoke(stage);
    }

    private static void closeStage(QuPathGUI qupath) throws Exception {
        Object stage = QuPathGUI.class.getMethod("getStage").invoke(qupath);
        stage.getClass().getMethod("close").invoke(stage);
    }

    private static ImageData<BufferedImage> openAndSynchronize(
            QuPathGUI qupath, ProjectImageEntry<BufferedImage> entry) throws Exception {
        return onFx(
                () -> {
                    require(qupath.openImageEntry(entry), "QuPath did not open " + entry.getID());
                    ImageData<BufferedImage> imageData = qupath.getImageData();
                    require(imageData != null, "opened viewer has no ImageData");
                    require(
                            isViewerOnEntry(qupath, entry),
                            "viewer opened the wrong project entry");
                    entry.saveImageData(imageData);
                    imageData.setChanged(false);
                    return imageData;
                });
    }

    private static TrackedWorkingCopy captureWorking(
            ProjectImageEntry<BufferedImage> entry, String propertyValue) throws IOException {
        ImageData<BufferedImage> working = entry.readImageData();
        working.setProperty(PROBE_PROPERTY, propertyValue);
        return new TrackedWorkingCopy(working);
    }

    private static void requirePersistedProperty(
            ProjectImageEntry<BufferedImage> entry, String expected) throws Exception {
        try (ImageData<BufferedImage> persisted = entry.readImageData()) {
            require(
                    expected.equals(persisted.getProperty(PROBE_PROPERTY)),
                    "saved working-copy property did not round-trip");
        }
    }

    private static boolean isViewerOnEntry(
            QuPathGUI qupath, ProjectImageEntry<BufferedImage> expected) {
        ImageData<BufferedImage> active = qupath.getImageData();
        Project<BufferedImage> project = qupath.getProject();
        if (active == null || project == null) {
            return false;
        }
        ProjectImageEntry<BufferedImage> actual = project.getEntry(active);
        return actual != null && actual.getID().equals(expected.getID());
    }

    private static ProjectFixture createProject(Path root) throws Exception {
        Path projectDirectory = Files.createDirectory(root.resolve("project"));
        Path firstImage = writeImage(root.resolve("first.png"), 64, 48, 0x5A3412);
        Path secondImage = writeImage(root.resolve("second.png"), 48, 40, 0x126A5A);
        Project<BufferedImage> project =
                Projects.createProject(projectDirectory.toFile(), BufferedImage.class);
        ProjectImageEntry<BufferedImage> first = addImage(project, firstImage, "first");
        ProjectImageEntry<BufferedImage> second = addImage(project, secondImage, "second");
        initializeEntry(first);
        initializeEntry(second);
        project.syncChanges();
        return new ProjectFixture(project, first, second);
    }

    private static void initializeEntry(ProjectImageEntry<BufferedImage> entry) throws Exception {
        try (ImageData<BufferedImage> imageData = entry.readImageData()) {
            imageData.setImageType(ImageType.BRIGHTFIELD_H_E);
            entry.saveImageData(imageData);
        }
    }

    private static ProjectImageEntry<BufferedImage> addImage(
            Project<BufferedImage> project, Path source, String name) throws Exception {
        ProjectImageEntry<BufferedImage> entry;
        try (var server = ImageServers.buildServer(source.toUri())) {
            entry = project.addImage(server.getBuilder());
        }
        require(entry != null, "project rejected " + source);
        entry.setImageName(name);
        return entry;
    }

    private static Path writeImage(Path output, int width, int height, int salt)
            throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = (x * 17 + y * 3 + salt) & 0xFF;
                int green = (x * 5 + y * 19 + (salt >>> 8)) & 0xFF;
                int blue = (x * 11 + y * 7 + (salt >>> 16)) & 0xFF;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        require(ImageIO.write(image, "PNG", output.toFile()), "PNG writer unavailable");
        return output;
    }

    private static void isolateUserState(Path root) throws IOException {
        Path home = Files.createDirectories(root.resolve("home"));
        Path preferences = Files.createDirectories(root.resolve("preferences"));
        System.setProperty("user.home", home.toString());
        System.setProperty("java.util.prefs.userRoot", preferences.toString());
        System.setProperty("qupath.startup.script", "block");
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

    private static <T> T onFx(Callable<T> operation) throws Exception {
        if (isFxApplicationThread()) {
            return operation.call();
        }
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

    private static boolean isFxApplicationThread() throws Exception {
        return (boolean)
                Class.forName("javafx.application.Platform")
                        .getMethod("isFxApplicationThread")
                        .invoke(null);
    }

    private static void exitJavaFx() throws Exception {
        Class.forName("javafx.application.Platform").getMethod("exit").invoke(null);
    }

    private static JsonObject environment() {
        JsonObject result = new JsonObject();
        result.addProperty("os_name", System.getProperty("os.name"));
        result.addProperty("os_version", System.getProperty("os.version"));
        result.addProperty("os_arch", System.getProperty("os.arch"));
        result.addProperty("java_version", System.getProperty("java.version"));
        result.addProperty("java_vendor", System.getProperty("java.vendor"));
        result.addProperty("qupath_version", String.valueOf(QuPathGUI.getVersion()));
        return result;
    }

    private static String classDigest(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("class bytes unavailable for " + type.getName());
            }
            return sha256(input.readAllBytes());
        }
    }

    private static String codeSourceDigest(Class<?> type) throws Exception {
        Path path =
                Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                        .toAbsolutePath()
                        .normalize();
        require(Files.isRegularFile(path), "code source is not a regular file: " + path);
        return sha256(Files.readAllBytes(path));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void close(ImageData<BufferedImage> imageData) throws Exception {
        if (imageData != null) {
            imageData.close();
        }
    }

    private static void closeQuietly(ImageData<BufferedImage> imageData) {
        if (imageData != null) {
            try {
                imageData.close();
            } catch (Exception ignored) {
                // Preserve the primary probe result.
            }
        }
    }

    private static void closeQuietly(TrackedWorkingCopy working) {
        if (working != null && working.closeCalls() == 0) {
            try {
                working.close();
            } catch (Exception ignored) {
                // Preserve the primary probe result.
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class TrackedWorkingCopy {
        private final ImageData<BufferedImage> imageData;
        private int closeCalls;

        private TrackedWorkingCopy(ImageData<BufferedImage> imageData) {
            this.imageData = imageData;
        }

        private ImageData<BufferedImage> imageData() {
            return imageData;
        }

        private int closeCalls() {
            return closeCalls;
        }

        private void close() throws Exception {
            require(closeCalls == 0, "working ImageData closed more than once");
            closeCalls++;
            imageData.close();
        }
    }

    private record ProjectFixture(
            Project<BufferedImage> project,
            ProjectImageEntry<BufferedImage> first,
            ProjectImageEntry<BufferedImage> second) {}

    private record CommitObservation(
            boolean viewerWasTarget,
            boolean replacementAttempted,
            boolean viewerAdoptedWorking,
            String replacementError,
            int workingCloseCallsAfterCommit,
            List<String> events) {

        private JsonObject toJson(int closeCalls) {
            JsonObject result = new JsonObject();
            result.addProperty("save_returned", true);
            result.addProperty("viewer_was_target", viewerWasTarget);
            result.addProperty("replacement_attempted", replacementAttempted);
            result.addProperty("viewer_adopted_exact_working_copy", viewerAdoptedWorking);
            if (replacementError != null) {
                result.addProperty("replacement_error", replacementError);
            }
            result.addProperty("working_close_calls_after_commit", workingCloseCallsAfterCommit);
            result.addProperty("working_close_calls_after_release", closeCalls);
            JsonArray eventArray = new JsonArray();
            events.forEach(eventArray::add);
            result.add("events", eventArray);
            return result;
        }
    }
}
