package qupath.ext.histoforge.training;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.TrainingDatasetCodec;
import qupath.ext.histoforge.contract.TrainingDatasetCodec.TrainingDatasetManifest;
import qupath.ext.histoforge.contract.TrainingDatasetTransferCodec.TrainingDatasetRecord;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PreparedTrainingDataset;
import qupath.ext.histoforge.training.TrainingProjectDatasetBuilder.ProjectPlan;
import qupath.lib.projects.Project;

/** Synchronous orchestration for Train-tab capture, export, and dataset publication. */
public final class TrainingDatasetWorkflow {

    public enum Phase {
        CAPTURING,
        REVIEWING_LABELS,
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
    private final List<String> newClasses;
    private final BiPredicate<TrainingOutlineOverlaps.Review, BooleanSupplier> overlapConfirmation;

    /**
     * @param newClasses the target model's classes that start without learned weights; export and
     *     upload refuse a dataset whose Training rows hold no example of one
     */
    public TrainingDatasetWorkflow(
            TrainingProjectDatasetBuilder builder,
            TrainingDatasetUploader uploader,
            Path temporaryDirectory,
            List<String> newClasses) {
        this(builder, uploader, temporaryDirectory, newClasses, null);
    }

    /** A supplied confirmation permits one retry of a repairable ROI label rejection. */
    public TrainingDatasetWorkflow(
            TrainingProjectDatasetBuilder builder,
            TrainingDatasetUploader uploader,
            Path temporaryDirectory,
            List<String> newClasses,
            BiPredicate<TrainingOutlineOverlaps.Review, BooleanSupplier> overlapConfirmation) {
        this.builder = Objects.requireNonNull(builder, "builder");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
        this.temporaryDirectory = normalize(temporaryDirectory, "temporaryDirectory");
        this.newClasses = List.copyOf(newClasses);
        this.overlapConfirmation = overlapConfirmation;
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
            try {
                return seal(prepared, current.assignment(), cancelled, progress);
            } catch (InferenceException rejection) {
                return retryOutlineOverlap(
                        rejection, prepared, project, current, cancelled, progress);
            }
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

    private SealedDataset seal(
            PreparedTrainingDataset prepared,
            TrainingAssignment assignment,
            BooleanSupplier cancelled,
            Consumer<Progress> progress)
            throws IOException, InterruptedException, InferenceException {
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
        return new SealedDataset(record, prepared.manifest(), assignment);
    }

    private SealedDataset retryOutlineOverlap(
            InferenceException rejection,
            PreparedTrainingDataset prepared,
            Project<BufferedImage> project,
            CurrentCapture current,
            BooleanSupplier cancelled,
            Consumer<Progress> progress)
            throws IOException, InterruptedException, InferenceException {
        int index = rejectedRoi(rejection, prepared.manifest());
        if (overlapConfirmation == null
                || index < 0
                || !TrainingOutlineOverlaps.supports(builder.task().outputType())) {
            throw uploadValidationMessage(rejection, prepared.manifest(), current.project());
        }
        progress.accept(new Progress(Phase.REVIEWING_LABELS, 0, 1, "Checking training outlines"));
        var plan =
                TrainingOutlineOverlaps.prepare(
                        current.project(), prepared.manifest().rois().get(index), cancelled);
        if (plan.isEmpty()) {
            throw uploadValidationMessage(rejection, prepared.manifest(), current.project());
        }
        var repair = plan.orElseThrow();
        if (!overlapConfirmation.test(repair.review(), cancelled)) {
            throw new CancellationException("Training outline trims were declined.");
        }
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Training data preparation cancelled.");
        }
        Files.delete(prepared.archivePath());
        var retried =
                builder.write(
                        prepared.archivePath(),
                        project,
                        repair.project(),
                        cancelled,
                        item -> progress.accept(adapt(item)));
        try {
            return seal(retried, current.assignment(), cancelled, progress);
        } catch (InferenceException failure) {
            throw uploadValidationMessage(failure, retried.manifest(), repair.project());
        }
    }

    private static int rejectedRoi(InferenceException rejection, TrainingDatasetManifest manifest) {
        var error = rejection.gatewayError().orElse(null);
        if (error == null || error.code() != GatewayErrorCodec.Code.VALIDATION_ERROR) return -1;
        String resource = error.resourceId().orElse("");
        for (int i = 0; i < manifest.rois().size(); i++) {
            if (resource.equals(TrainingDatasetCodec.roiGeometryPath(i))) return i;
        }
        return -1;
    }

    private static InferenceException uploadValidationMessage(
            InferenceException rejection, TrainingDatasetManifest manifest, ProjectPlan project) {
        var error = rejection.gatewayError().orElse(null);
        if (error == null || error.code() != GatewayErrorCodec.Code.VALIDATION_ERROR) {
            return rejection;
        }
        int index = rejectedRoi(rejection, manifest);
        if (index >= 0) {
            int imageIndex = manifest.rois().get(index).imageIndex();
            // The archive uses the same canonical image order as the detached project.
            String entryId = project.images().get(imageIndex).capture().projectEntryId();
            return rejection.withDisplayMessage(
                    project.imageNames().get(entryId) + "\n\n" + error.message());
        }
        return rejection.withDisplayMessage(error.message());
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
        TrainingAssignment refreshed = expected.refresh(index);
        // This read is the dataset itself, so the check cannot rely on an earlier one.
        String problem = refreshed.trainingExamplesProblem(newClasses);
        if (!problem.isEmpty()) {
            throw new IllegalStateException(problem);
        }
        return new CurrentCapture(current, refreshed);
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
