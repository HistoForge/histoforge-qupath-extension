package qupath.ext.histoforge.training;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetManifest;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingSplitCodec;
import qupath.ext.histoforge.contract.TrainingSplitCodec.TrainingSplitPlan;
import qupath.ext.histoforge.contract.TrainingSplitCodec.TrainingSplitSidecar;

/** Atomically writes the split-only companion for one exported training archive. */
public final class TrainingSplitSidecarWriter {

    private TrainingSplitSidecarWriter() {}

    public static void write(
            Path destination,
            TrainingDatasetManifest manifest,
            TrainingSplitPlan splitPlan,
            TrainingApiLimits limits)
            throws IOException {
        Path target =
                Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        Path parent = Objects.requireNonNull(target.getParent(), "sidecar parent");
        Files.createDirectories(parent);
        byte[] content =
                CanonicalCodec.canonicalBytes(
                        TrainingSplitCodec.toJson(
                                new TrainingSplitSidecar(
                                        Objects.requireNonNull(manifest, "manifest")
                                                .datasetDigest(),
                                        Objects.requireNonNull(splitPlan, "splitPlan")),
                                manifest,
                                Objects.requireNonNull(limits, "limits")));
        Path incomplete =
                Files.createTempFile(parent, "." + target.getFileName() + ".", ".incomplete");
        try {
            Files.write(
                    incomplete,
                    content,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(incomplete, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(
                    incomplete,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(incomplete);
        }
    }
}
