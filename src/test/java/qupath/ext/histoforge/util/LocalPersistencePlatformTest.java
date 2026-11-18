package qupath.ext.histoforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cross-platform evidence for the private local-state publication boundary. */
class LocalPersistencePlatformTest {

    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @Test
    void readersObserveOnlyCompleteGenerationsOnTheDeclaredHost(@TempDir Path fallbackRoot)
            throws Exception {
        String evidenceOutput = System.getenv("HISTOFORGE_PLATFORM_EVIDENCE_OUTPUT");
        boolean declaredMatrixRun = evidenceOutput != null && !evidenceOutput.isBlank();
        Path root =
                declaredMatrixRun
                        ? Files.createTempDirectory(
                                Path.of(System.getProperty("user.home")),
                                ".histoforge-persistence-")
                        : fallbackRoot;
        try {
            Path state = root.resolve("private-state.json");
            String generationA =
                    "{\"generation\":\"A\",\"payload\":\"" + "α".repeat(262_144) + "\"}";
            String generationB =
                    "{\"generation\":\"B\",\"payload\":\"" + "β".repeat(262_145) + "\"}";
            SecureFileStore.writeOwnerOnly(state, generationA);

            Set<String> observed = new HashSet<>();
            observed.add(Files.readString(state, StandardCharsets.UTF_8));
            CountDownLatch writerStarted = new CountDownLatch(1);
            CountDownLatch readerObserved = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            Future<?> writer =
                    executor.submit(
                            () -> {
                                SecureFileStore.writeOwnerOnly(state, generationB);
                                writerStarted.countDown();
                                if (!readerObserved.await(30, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "concurrent local-state reader did not start");
                                }
                                for (int index = 0; index < 31; index++) {
                                    SecureFileStore.writeOwnerOnly(
                                            state, index % 2 == 0 ? generationA : generationB);
                                }
                                return null;
                            });
            int concurrentReads = 0;
            int readsWhileWriterActive = 0;
            long readDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            try {
                assertTrue(
                        writerStarted.await(30, TimeUnit.SECONDS),
                        "atomic-publication writer did not start");
                try {
                    assertTrue(!writer.isDone(), "writer finished before the concurrent read");
                    String value = Files.readString(state, StandardCharsets.UTF_8);
                    assertTrue(
                            value.equals(generationA) || value.equals(generationB),
                            "reader observed a partial or mixed generation");
                    observed.add(value);
                    concurrentReads++;
                    readsWhileWriterActive++;
                } finally {
                    readerObserved.countDown();
                }
                while (!writer.isDone() || concurrentReads < 32) {
                    assertTrue(
                            System.nanoTime() < readDeadline,
                            "atomic-publication writer did not complete before the read deadline");
                    boolean writerActiveBeforeRead = !writer.isDone();
                    String value = Files.readString(state, StandardCharsets.UTF_8);
                    assertTrue(
                            value.equals(generationA) || value.equals(generationB),
                            "reader observed a partial or mixed generation");
                    observed.add(value);
                    concurrentReads++;
                    if (writerActiveBeforeRead || !writer.isDone()) {
                        // "Active" means the writer task had not completed across this read; it
                        // does not claim that the read overlapped the native rename call itself.
                        readsWhileWriterActive++;
                    }
                }
                writer.get(60, TimeUnit.SECONDS);
                observed.add(Files.readString(state, StandardCharsets.UTF_8));
            } finally {
                executor.shutdownNow();
                assertTrue(
                        executor.awaitTermination(60, TimeUnit.SECONDS),
                        "atomic-publication writer did not terminate");
            }

            assertEquals(Set.of(generationA, generationB), observed);
            assertTrue(concurrentReads >= 32);
            assertTrue(readsWhileWriterActive >= 1);
            if (state.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertEquals(OWNER_ONLY_PERMISSIONS, Files.getPosixFilePermissions(state));
            }

            if (declaredMatrixRun) {
                writeAndVerifyEvidence(
                        root, Path.of(evidenceOutput), concurrentReads, readsWhileWriterActive);
            }
        } finally {
            if (declaredMatrixRun) {
                deleteTree(root);
            }
        }
    }

    private static void writeAndVerifyEvidence(
            Path stateRoot, Path output, int concurrentReads, int readsWhileWriterActive)
            throws Exception {
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        String javaVersion = System.getProperty("java.version");
        String javaRuntimeVersion = System.getProperty("java.runtime.version");
        String javaVendor = System.getProperty("java.vendor");
        FileStore fileStore = Files.getFileStore(stateRoot);

        assertTrue(osName.startsWith(requiredEnvironment("HISTOFORGE_EXPECTED_OS_PREFIX")));
        assertEquals(requiredEnvironment("HISTOFORGE_EXPECTED_OS_ARCH"), osArch);
        assertEquals(requiredEnvironment("HISTOFORGE_EXPECTED_JAVA_VERSION"), javaVersion);
        assertEquals(
                requiredEnvironment("HISTOFORGE_EXPECTED_JAVA_RUNTIME_VERSION"),
                javaRuntimeVersion);
        assertEquals(requiredEnvironment("HISTOFORGE_EXPECTED_JAVA_VENDOR"), javaVendor);
        assertEquals(
                requiredEnvironment("HISTOFORGE_EXPECTED_FILESYSTEM_TYPE").toLowerCase(),
                fileStore.type().toLowerCase());

        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema_version", 1);
        evidence.addProperty("runner_label", requiredEnvironment("HISTOFORGE_RUNNER_LABEL"));
        addOptional(evidence, "runner_image_os", System.getenv("ImageOS"));
        addOptional(evidence, "runner_image_version", System.getenv("ImageVersion"));
        if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
            evidence.addProperty("github_run_id", requiredEnvironment("GITHUB_RUN_ID"));
            evidence.addProperty("github_run_attempt", requiredEnvironment("GITHUB_RUN_ATTEMPT"));
            evidence.addProperty("github_sha", requiredEnvironment("GITHUB_SHA"));
        } else {
            addOptional(evidence, "github_run_id", System.getenv("GITHUB_RUN_ID"));
            addOptional(evidence, "github_run_attempt", System.getenv("GITHUB_RUN_ATTEMPT"));
            addOptional(evidence, "github_sha", System.getenv("GITHUB_SHA"));
        }
        evidence.addProperty("os_name", osName);
        evidence.addProperty("os_version", osVersion);
        evidence.addProperty("os_arch", osArch);
        evidence.addProperty("java_version", javaVersion);
        evidence.addProperty("java_runtime_version", javaRuntimeVersion);
        evidence.addProperty("java_vendor", javaVendor);
        evidence.addProperty("filesystem_name", fileStore.name());
        evidence.addProperty("filesystem_type", fileStore.type());
        evidence.addProperty(
                "posix_attributes",
                stateRoot.getFileSystem().supportedFileAttributeViews().contains("posix"));
        evidence.addProperty("concurrent_complete_reads", concurrentReads);
        evidence.addProperty("reads_while_writer_active", readsWhileWriterActive);

        Path absoluteOutput = output.toAbsolutePath().normalize();
        Files.createDirectories(absoluteOutput.getParent());
        Files.writeString(
                absoluteOutput,
                new GsonBuilder().setPrettyPrinting().create().toJson(evidence)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for a declared matrix run");
        }
        return value;
    }

    private static void addOptional(JsonObject object, String field, String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(field, value);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
