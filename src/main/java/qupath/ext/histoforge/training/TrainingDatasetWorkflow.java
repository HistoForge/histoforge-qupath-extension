package qupath.ext.histoforge.training;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetManifest;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PreparedTrainingDataset;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder.ProjectPlan;
import qupath.lib.projects.Project;

/** Synchronous orchestration for Train-tab capture, export, and dataset publication. */
public final class TrainingDatasetWorkflow {

    public enum Phase {
        CAPTURING,
        ENCODING,
        UPLOADING
    }

    public record Progress(Phase phase, long completed, long total, String detail) {
        public Progress {
            phase = Objects.requireNonNull(phase, "phase");
            if (completed < 0 || total < 1 || completed > total) {
                throw new IllegalArgumentException("invalid training dataset progress");
            }
            detail = Objects.requireNonNull(detail, "detail");
        }
    }

    public record ExportPaths(Path archive, Path splitSidecar) {
        public ExportPaths {
            archive = normalize(archive, "archive");
            splitSidecar = normalize(splitSidecar, "splitSidecar");
            if (archive.equals(splitSidecar)) {
                throw new IllegalArgumentException("archive and split sidecar paths must differ");
            }
        }
    }

    public record ExportedDataset(
            PreparedTrainingDataset dataset, TrainingAssignment assignment, ExportPaths paths) {
        public ExportedDataset {
            dataset = Objects.requireNonNull(dataset, "dataset");
            assignment = Objects.requireNonNull(assignment, "assignment");
            paths = Objects.requireNonNull(paths, "paths");
        }
    }

    public record SealedDataset(
            TrainingDatasetRecord record,
            TrainingDatasetManifest manifest,
            TrainingAssignment assignment) {
        public SealedDataset {
            record = Objects.requireNonNull(record, "record");
            manifest = Objects.requireNonNull(manifest, "manifest");
            assignment = Objects.requireNonNull(assignment, "assignment");
            if (!record.datasetDigest().equals(manifest.datasetDigest())) {
                throw new IllegalArgumentException("sealed record and manifest identities differ");
            }
        }
    }

    private final TrainingProjectDatasetBuilder builder;
    private final TrainingDatasetUploader uploader;
    private final Path temporaryDirectory;

    public TrainingDatasetWorkflow(
            TrainingProjectDatasetBuilder builder,
            TrainingDatasetUploader uploader,
            Path temporaryDirectory) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
        this.temporaryDirectory = normalize(temporaryDirectory, "temporaryDirectory");
    }

    /** Re-capture, materialize, and export the package plus its exact current split sidecar. */
    public ExportedDataset export(
            Path selectedBase,
            Project<BufferedImage> project,
            TrainingAssignment assignment,
            BooleanSupplier cancelled,
            Consumer<Progress> progress)
            throws IOException {
        ExportPaths paths = exportPaths(selectedBase);
        CurrentCapture current = recapture(project, assignment, cancelled, progress);
        PreparedTrainingDataset prepared =
                builder.write(
                        paths.archive(),
                        project,
                        current.project(),
                        cancelled,
                        item -> progress.accept(adapt(item)));
        TrainingSplitSidecarWriter.write(
                paths.splitSidecar(),
                prepared.manifest(),
                current.assignment().requirePlan(),
                builder.apiLimits());
        return new ExportedDataset(prepared, current.assignment(), paths);
    }

    /** Re-capture, materialize to private local scratch, publish, then remove the local archive. */
    public SealedDataset upload(
            Project<BufferedImage> project,
            TrainingAssignment assignment,
            BooleanSupplier cancelled,
            Consumer<Progress> progress)
            throws IOException, InterruptedException, InferenceException {
        Files.createDirectories(temporaryDirectory);
        Path archive =
                Files.createTempFile(temporaryDirectory, ".histoforge-training-upload-", ".zip");
        Throwable failure = null;
        try {
            CurrentCapture current = recapture(project, assignment, cancelled, progress);
            PreparedTrainingDataset prepared =
                    builder.write(
                            archive,
                            project,
                            current.project(),
                            cancelled,
                            item -> progress.accept(adapt(item)));
            TrainingDatasetRecord record =
                    uploader.upload(
                            prepared,
                            cancelled,
                            uploaded ->
                                    progress.accept(
                                            new Progress(
                                                    Phase.UPLOADING,
                                                    uploaded,
                                                    prepared.archiveBytes(),
                                                    "Uploading training data")));
            return new SealedDataset(record, prepared.manifest(), current.assignment());
        } catch (IOException
                | InterruptedException
                | InferenceException
                | RuntimeException
                | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException cleanupFailure) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    public static ExportPaths exportPaths(Path selectedBase) {
        Path selected = normalize(selectedBase, "selectedBase");
        String name = selected.getFileName().toString();
        String base =
                name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                        ? name.substring(0, name.length() - 4)
                        : name;
        if (base.isBlank()) {
            throw new IllegalArgumentException("training export base name must not be blank");
        }
        Path parent = Objects.requireNonNull(selected.getParent(), "training export parent");
        return new ExportPaths(parent.resolve(base + ".zip"), parent.resolve(base + ".split.json"));
    }

    private CurrentCapture recapture(
            Project<BufferedImage> project,
            TrainingAssignment assignment,
            BooleanSupplier cancelled,
            Consumer<Progress> progress)
            throws IOException {
        TrainingAssignment expected = Objects.requireNonNull(assignment, "assignment");
        if (!expected.isValid()) {
            throw new IllegalStateException(
                    "training and validation must both contain at least one unit");
        }
        ProjectPlan current = capture(project, cancelled, progress);
        TrainingProjectIndex index =
                TrainingProjectIndex.from(current, expected.projectIndex().unit());
        return new CurrentCapture(current, expected.refresh(index));
    }

    private ProjectPlan capture(
            Project<BufferedImage> project, BooleanSupplier cancelled, Consumer<Progress> progress)
            throws IOException {
        Consumer<Progress> sink = Objects.requireNonNull(progress, "progress");
        return builder.capture(project, cancelled, item -> sink.accept(adapt(item)));
    }

    private static Progress adapt(TrainingProjectDatasetBuilder.Progress progress) {
        return new Progress(
                progress.phase() == TrainingProjectDatasetBuilder.Phase.CAPTURING
                        ? Phase.CAPTURING
                        : Phase.ENCODING,
                progress.completed(),
                progress.total(),
                progress.imageName());
    }

    private static Path normalize(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }

    private record CurrentCapture(ProjectPlan project, TrainingAssignment assignment) {}
}
