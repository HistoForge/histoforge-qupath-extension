package qupath.ext.histoforge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.ZipFile;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.TrainingDatasetCodec;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
import qupath.ext.histoforge.platform.ClientBuildIdentityProvider;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PreparedTrainingDataset;
import qupath.ext.histoforge.training.TrainingDatasetUploader;

/** Opt-in measurement of the packaged Train-tab uploader; never starts training or deletes data. */
public final class TrainingDatasetTransferQualification {

    private TrainingDatasetTransferQualification() {}

    /** Upload one hash-pinned prepared archive and retain a privacy-safe timing receipt. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "expected CREDENTIAL ARCHIVE EXPECTED_SHA256 EVIDENCE");
        }
        Path archive = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        if (Files.exists(output) || !Files.isDirectory(output.getParent())) {
            throw new IllegalArgumentException(
                    "evidence must be a fresh file in an existing directory");
        }
        String expected = CanonicalCodec.requireSha256(args[2], "expected_sha256");
        JsonObject evidence = new JsonObject();
        evidence.addProperty("probe", "phase8-java-training-dataset-transfer");
        evidence.addProperty("captured_at", Instant.now().toString());
        evidence.addProperty("success", false);
        evidence.addProperty(
                "cleanup", "not attempted; dataset retained for separate verification");
        Exception failure = null;
        try {
            long started = System.nanoTime();
            if (!sha256(archive).equals(expected)) {
                throw new IOException("archive digest differs from the expected capture");
            }
            evidence.addProperty("local_hash_seconds", elapsed(started));
            evidence.addProperty("archive_sha256", expected);
            evidence.addProperty("archive_bytes", Files.size(archive));
            evidence.add(
                    "client_build",
                    BuildIdentityCodec.toJson(ClientBuildIdentityProvider.capture()));
            try (var input =
                    TrainingDatasetTransferQualification.class.getResourceAsStream(
                            "TrainingDatasetTransferQualification.class")) {
                evidence.addProperty(
                        "probe_class_sha256", CanonicalCodec.sha256(input.readAllBytes()));
            }
            GatewayTransport transport =
                    new GatewayTransport(new DeploymentStore(Path.of(args[0])));
            started = System.nanoTime();
            GatewayCapabilities capabilities = new ModuleGatewayClient(transport).capabilities();
            evidence.addProperty("capabilities_seconds", elapsed(started));
            evidence.add("gateway_capabilities", GatewayCapabilitiesCodec.toJson(capabilities));
            PreparedTrainingDataset prepared = readPrepared(archive, expected, capabilities);
            evidence.addProperty("dataset_digest", prepared.manifest().datasetDigest());
            TrainingDatasetGatewayClient client =
                    new TrainingDatasetGatewayClient(transport, capabilities);
            measure(prepared, client, evidence);
            evidence.addProperty("success", true);
        } catch (Exception error) {
            failure = error;
            // Remote error text may contain private paths. Preserve only typed diagnostics.
            evidence.addProperty("failure_type", error.getClass().getName());
            if (error instanceof InferenceException inference) {
                evidence.addProperty("http_status", inference.statusCode());
                inference
                        .gatewayError()
                        .ifPresent(
                                value -> evidence.addProperty("error_code", value.code().name()));
            }
        }
        evidence.addProperty("evidence_digest", CanonicalCodec.canonicalDigest(evidence));
        Files.write(output, CanonicalCodec.canonicalBytes(evidence), StandardOpenOption.CREATE_NEW);
        System.out.println("PHASE8_JAVA_DATASET_TRANSFER_EVIDENCE=" + output);
        if (failure != null) {
            throw new IllegalStateException(
                    "Training dataset transfer failed; inspect the evidence file.");
        }
    }

    /** Keep timing semantics separate from HTTP adaptation and the uploader's replay policy. */
    static void measure(
            PreparedTrainingDataset prepared,
            TrainingDatasetGatewayClient client,
            JsonObject evidence)
            throws IOException, InterruptedException, InferenceException {
        Optional<String> cursor = Optional.empty();
        do {
            var page = client.page(client.limits().maxPageSize(), cursor);
            for (var item : page.items()) {
                if (item.record() instanceof TrainingDatasetRecord record
                        && record.datasetDigest().equals(prepared.manifest().datasetDigest())) {
                    throw new IllegalArgumentException(
                            "dataset already exists; reuse is not a transfer benchmark");
                }
            }
            cursor = page.nextCursor();
        } while (cursor.isPresent());

        JsonArray progress = new JsonArray();
        evidence.add("upload_progress", progress);
        evidence.addProperty(
                "progress_timing",
                "first interval includes reservation; later intervals include local read, hashing, HTTP and durable acknowledgement, including any uploader replay");
        long started = System.nanoTime();
        long[] previous = {started};
        TrainingDatasetRecord result =
                new TrainingDatasetUploader(client, client.limits())
                        .upload(
                                prepared,
                                () -> false,
                                bytes -> {
                                    long now = System.nanoTime();
                                    JsonObject point = new JsonObject();
                                    point.addProperty("uploaded_bytes", bytes);
                                    point.addProperty(
                                            "elapsed_seconds", (now - started) / 1_000_000_000.0);
                                    point.addProperty(
                                            "interval_seconds",
                                            (now - previous[0]) / 1_000_000_000.0);
                                    progress.add(point);
                                    previous[0] = now;
                                    System.out.println(
                                            "PHASE8_UPLOAD "
                                                    + bytes
                                                    + "/"
                                                    + prepared.archiveBytes());
                                });
        evidence.addProperty("upload_and_seal_seconds", elapsed(started));
        evidence.addProperty("after_last_progress_seconds", elapsed(previous[0]));
        evidence.addProperty(
                "after_last_progress_includes",
                "seal request, archive assembly, validation, publication and temporary-upload cleanup; not separately timed here");
        evidence.add(
                "retained_dataset", TrainingDatasetTransferCodec.toJson(result, client.limits()));
        int expectedChunks =
                TrainingDatasetTransferCodec.chunkCount(
                        prepared.archiveBytes(), TrainingDatasetUploader.CHUNK_SIZE_BYTES);
        if (progress.size() != expectedChunks) {
            throw new IOException("upload reused a dataset instead of transferring every chunk");
        }
        long readbackStarted = System.nanoTime();
        if (!client.get(result.datasetId()).record().equals(result)) {
            throw new IOException("dataset readback differs from the sealed receipt");
        }
        evidence.addProperty("readback_seconds", elapsed(readbackStarted));
        evidence.addProperty("readback_verified", true);
    }

    private static PreparedTrainingDataset readPrepared(
            Path archive, String digest, GatewayCapabilities capabilities) throws IOException {
        long maximum = capabilities.trainingApiLimits().dataset().maxJsonMemberBytes();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry("dataset.json");
            if (entry == null || entry.getSize() <= 0 || entry.getSize() > maximum) {
                throw new IOException("prepared archive has no bounded dataset manifest");
            }
            try (var input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(Math.toIntExact(maximum + 1));
                if (bytes.length > maximum) {
                    throw new IOException("prepared manifest exceeds its byte limit");
                }
                var manifest =
                        TrainingDatasetCodec.parseManifest(
                                CanonicalCodec.parseJson(bytes), capabilities.trainingApiLimits());
                return new PreparedTrainingDataset(archive, Files.size(archive), digest, manifest);
            }
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] block = new byte[1024 * 1024];
            int size;
            while ((size = input.read(block)) >= 0) {
                digest.update(block, 0, size);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static double elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000_000.0;
    }
}
