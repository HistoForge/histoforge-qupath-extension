package qupath.ext.histoforge.training;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec.ClientExecutionLimits;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.SourceArtifactLimits;
import qupath.ext.histoforge.contract.RasterInputCodec.ActionSourceRevision;
import qupath.ext.histoforge.contract.TrainingCapabilityCodec.ModelTrainingCapability;
import qupath.ext.histoforge.contract.TrainingClassSchema.Initialization;
import qupath.ext.histoforge.contract.TrainingLimitsCodec.TrainingApiLimits;
import qupath.ext.histoforge.contract.TrainingTaskCodec.TrainingTask;
import qupath.ext.histoforge.data.GroupManager;
import qupath.ext.histoforge.modules.InstalledModule;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.spatial.ImageSourceCapture;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourceInputPlan.OriginalArtifactPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner;
import qupath.ext.histoforge.spatial.TrainingSourcePlanner.TrainingRoiGeometry;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PlannedImage;
import qupath.ext.histoforge.training.TrainingDatasetPackageWriter.PreparedTrainingDataset;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

/** Captures and materializes one current QuPath project through the training package contract. */
public final class TrainingProjectDatasetBuilder {

    private static final int DETERMINISTIC_OME_WRITER_THREADS = 1;
    // Training sources must fit the dataset-package budget, independently of inference uploads.
    private static final long MAX_TRAINING_SOURCE_BYTES = 8L << 30;
    private static final long MAX_TRAINING_EXPORT_BYTES = 1L << 30;

    public static final String NO_TRAINING_ROIS_GUIDANCE =
            "No training ROIs found. Use the HistoForge ROI tool to draw rectangular training"
                    + " regions, annotate the target objects inside them, then refresh. Ordinary"
                    + " tissue-mask annotations are not training ROIs.";

    public enum Phase {
        CAPTURING,
        ENCODING
    }

    public record Progress(Phase phase, int completed, int total, String imageName) {
        public Progress {
            phase = Objects.requireNonNull(phase, "phase");
            if (completed < 0 || total < 1 || completed > total) {
                throw new IllegalArgumentException("invalid training preparation progress");
            }
            imageName = Objects.requireNonNull(imageName, "imageName");
        }
    }

    /** Detached project snapshot. It owns no open QuPath image or source server. */
    public record ProjectPlan(List<PlannedImage> images, Map<String, String> imageNames) {
        public ProjectPlan {
            List<PlannedImage> ordered = new ArrayList<>(Objects.requireNonNull(images, "images"));
            ordered.forEach(image -> Objects.requireNonNull(image, "planned image"));
            ordered.sort(
                    java.util.Comparator.comparing(
                            image -> image.capture().projectEntryIdDigest()));
            if (ordered.isEmpty()) {
                throw new IllegalArgumentException("training project contains no HistoForge ROIs");
            }
            Map<String, String> names =
                    Map.copyOf(Objects.requireNonNull(imageNames, "imageNames"));
            java.util.Set<String> imageIds = new java.util.HashSet<>();
            for (PlannedImage image : ordered) {
                String imageId = image.capture().projectEntryId();
                if (!imageIds.add(imageId)) {
                    throw new IllegalArgumentException("training project image IDs must be unique");
                }
                String name = names.get(imageId);
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException(
                            "training project image name is missing for " + imageId);
                }
            }
            if (!names.keySet().equals(imageIds)) {
                throw new IllegalArgumentException(
                        "training project image names must match captured images");
            }
            images = List.copyOf(ordered);
            imageNames = names;
        }

        public int roiCount() {
            return images.stream().mapToInt(image -> image.capture().rois().size()).sum();
        }
    }

    private final ImageLifecycle imageLifecycle;
    private final TrainingTask task;
    private final ModelTrainingCapability capability;
    private final TrainingApiLimits apiLimits;
    private final ClientExecutionLimits clientLimits;
    private final SourcePlanningLimits sourcePlanningLimits;
    private final SourceArtifactWriter artifactWriter;

    TrainingProjectDatasetBuilder(
            ImageLifecycle imageLifecycle,
            TrainingTask task,
            ModelTrainingCapability capability,
            TrainingApiLimits apiLimits,
            ClientExecutionLimits clientLimits,
            SourcePlanningLimits sourcePlanningLimits,
            SourceArtifactWriter artifactWriter) {
        this.imageLifecycle = Objects.requireNonNull(imageLifecycle, "imageLifecycle");
        this.task = Objects.requireNonNull(task, "task");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.apiLimits = Objects.requireNonNull(apiLimits, "apiLimits");
        this.clientLimits = Objects.requireNonNull(clientLimits, "clientLimits");
        this.sourcePlanningLimits =
                Objects.requireNonNull(sourcePlanningLimits, "sourcePlanningLimits");
        this.artifactWriter = Objects.requireNonNull(artifactWriter, "artifactWriter");
    }

    /** Bind preparation to one exact installed model, gateway, and extension build. */
    public static TrainingProjectDatasetBuilder forModel(
            ImageLifecycle imageLifecycle,
            InstalledModule module,
            String modelId,
            TrainingTask task,
            Initialization initialization,
            ClientBuildIdentity clientBuild) {
        InstalledModule installed = Objects.requireNonNull(module, "module");
        String selectedModel = Objects.requireNonNull(modelId, "modelId");
        ClientBuildIdentity build = Objects.requireNonNull(clientBuild, "clientBuild");
        var gateway = installed.gateway();
        TrainingApiLimits limits = gateway.trainingApiLimits();
        ModelTrainingCapability capability =
                TrainingRunDraft.capability(installed, selectedModel, task, initialization);
        SourcePlanningLimits sourceLimits =
                sourceLimits(gateway.sourceArtifactLimits(), build.limits());
        return new TrainingProjectDatasetBuilder(
                imageLifecycle,
                task,
                capability,
                limits,
                build.limits(),
                sourceLimits,
                new SourceArtifactWriter(
                        build.extension(),
                        Math.toIntExact(gateway.sourceArtifactLimits().maxChunkBytes()),
                        DETERMINISTIC_OME_WRITER_THREADS,
                        sourceLimits.maxDerivedSourceArtifactBytes(),
                        sourceLimits.maxSourceArtifactBytes()));
    }

    /** Keep training package limits independent of the larger inference-upload policy. */
    static SourcePlanningLimits sourceLimits(
            SourceArtifactLimits gateway, ClientExecutionLimits client) {
        long maxSourceBytes = Math.min(MAX_TRAINING_SOURCE_BYTES, gateway.maxArtifactBytes());
        long maxDerivedBytes =
                Math.min(
                        MAX_TRAINING_EXPORT_BYTES,
                        Math.min(maxSourceBytes, client.maxLocalSourceArtifactBytes()));
        return new SourcePlanningLimits(
                gateway.maxCompleteDecodePixels(), maxDerivedBytes, maxSourceBytes);
    }

    public TrainingTask task() {
        return task;
    }

    public ModelTrainingCapability capability() {
        return capability;
    }

    public TrainingApiLimits apiLimits() {
        return apiLimits;
    }

    /** Capture labels and source plans without reading training image pixels. */
    public ProjectPlan capture(
            Project<BufferedImage> project, BooleanSupplier cancelled, Consumer<Progress> progress)
            throws IOException {
        return captureIfRoisPresent(project, cancelled, progress)
                .orElseThrow(() -> new IllegalArgumentException(NO_TRAINING_ROIS_GUIDANCE));
    }

    /** A refresh can validly find no training ROIs; export and upload still require a dataset. */
    public Optional<ProjectPlan> captureIfRoisPresent(
            Project<BufferedImage> project, BooleanSupplier cancelled, Consumer<Progress> progress)
            throws IOException {
        Project<BufferedImage> sourceProject = Objects.requireNonNull(project, "project");
        BooleanSupplier cancellation = Objects.requireNonNull(cancelled, "cancelled");
        Consumer<Progress> progressSink = Objects.requireNonNull(progress, "progress");
        List<ProjectImageEntry<BufferedImage>> entries = List.copyOf(sourceProject.getImageList());
        checkCancelled(cancellation);
        if (entries.isEmpty()) {
            return Optional.empty();
        }

        List<PlannedImage> images = new ArrayList<>();
        Map<String, String> imageNames = new HashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            checkCancelled(cancellation);
            ProjectImageEntry<BufferedImage> entry = entries.get(index);
            captureProjectImage(entry, cancellation)
                    .ifPresent(
                            image -> {
                                images.add(image);
                                imageNames.put(entry.getID(), entry.getImageName());
                            });
            progressSink.accept(
                    new Progress(Phase.CAPTURING, index + 1, entries.size(), entry.getImageName()));
        }
        checkCancelled(cancellation);
        if (images.isEmpty()) return Optional.empty();
        TrainingProjectCapture.requireWithinLimits(
                images.stream().map(PlannedImage::capture).toList(), apiLimits.dataset());
        return Optional.of(new ProjectPlan(images, imageNames));
    }

    private Optional<PlannedImage> captureProjectImage(
            ProjectImageEntry<BufferedImage> entry, BooleanSupplier cancelled) throws IOException {
        try {
            return imageLifecycle
                    .captureReadOnlyIf(
                            entry,
                            entry.getID(),
                            clientLimits,
                            image -> {
                                if (!TrainingProjectCapture.hasRoiMarkers(image.getHierarchy()))
                                    return false;
                                TrainingRoiPreflight.requireWithinObjectLimit(
                                        entry.getID(),
                                        image.getHierarchy(),
                                        image.getServer().getWidth(),
                                        image.getServer().getHeight(),
                                        task,
                                        apiLimits.dataset().maxObjectsPerRoi(),
                                        () -> checkCancelled(cancelled));
                                return true;
                            },
                            () -> checkCancelled(cancelled),
                            (imageData, sourceRevision, source) ->
                                    TrainingProjectCapture.captureImage(
                                                    entry.getID(),
                                                    GroupManager.getGroup(entry),
                                                    sourceRevision,
                                                    imageData.getHierarchy(),
                                                    task)
                                            .map(
                                                    image ->
                                                            new PlannedImage(
                                                                    image,
                                                                    TrainingSourcePlanner.plan(
                                                                            source.bind(
                                                                                    sourceRevision
                                                                                            .imageDataRevision()),
                                                                            image.rois().stream()
                                                                                    .map(
                                                                                            roi ->
                                                                                                    new TrainingRoiGeometry(
                                                                                                            roi
                                                                                                                    .sourceRoiUuid(),
                                                                                                            roi
                                                                                                                    .geometry()))
                                                                                    .toList(),
                                                                            task,
                                                                            capability,
                                                                            ImageSourceCapture
                                                                                    .eligibleTrainingOriginal(
                                                                                            imageData
                                                                                                    .getServer(),
                                                                                            source),
                                                                            sourcePlanningLimits))))
                    .flatMap(ImageLifecycle.ReadOnlyImageCapture::value);
        } catch (CancellationException | TrainingProjectCapture.RoiObjectLimitException error) {
            throw error;
        } catch (IOException error) {
            throw new IOException(imageReadFailure(entry, error), error);
        } catch (RuntimeException error) {
            throw new IllegalStateException(imageReadFailure(entry, error), error);
        }
    }

    private static String imageReadFailure(
            ProjectImageEntry<BufferedImage> entry, Exception error) {
        String message = "Could not read training image \"" + entry.getImageName() + "\"";
        return error.getMessage() == null || error.getMessage().isBlank()
                ? message
                : message + ": " + error.getMessage();
    }

    /** Materialize the exact archive represented by a detached project plan. */
    public PreparedTrainingDataset write(
            Path destination,
            Project<BufferedImage> project,
            ProjectPlan plan,
            BooleanSupplier cancelled,
            Consumer<Progress> progress)
            throws IOException {
        Project<BufferedImage> sourceProject = Objects.requireNonNull(project, "project");
        ProjectPlan captured = Objects.requireNonNull(plan, "plan");
        BooleanSupplier cancellation = Objects.requireNonNull(cancelled, "cancelled");
        Consumer<Progress> progressSink = Objects.requireNonNull(progress, "progress");
        Map<String, ProjectImageEntry<BufferedImage>> entries = new HashMap<>();
        for (ProjectImageEntry<BufferedImage> entry : sourceProject.getImageList()) {
            if (entries.put(entry.getID(), entry) != null) {
                throw new IllegalArgumentException("project image IDs must be unique");
            }
        }
        for (PlannedImage image : captured.images()) {
            if (!entries.containsKey(image.capture().projectEntryId())) {
                throw new IllegalArgumentException(
                        "training project changed before dataset materialization");
            }
        }

        int totalArtifacts =
                captured.images().stream()
                        .mapToInt(
                                image ->
                                        image.sourcePlan().usesOriginal()
                                                ? 1
                                                : image.sourcePlan().rois().size())
                        .sum();
        AtomicInteger completed = new AtomicInteger();
        return TrainingDatasetPackageWriter.write(
                destination,
                captured.images(),
                apiLimits,
                image -> {
                    checkCancelled(cancellation);
                    ProjectImageEntry<BufferedImage> entry =
                            entries.get(image.capture().projectEntryId());
                    ImageLifecycle.ReadOnlySourceSession source =
                            openProjectImageSource(entry, image.capture().sourceRevision());
                    return new TrainingDatasetPackageWriter.SourceSession() {
                        @Override
                        public SourceArtifactWriter.MaterializedSourceArtifact materialize(
                                TrainingSourcePlanner.TrainingRoiSourcePlan sourcePlan,
                                Path artifactDestination)
                                throws IOException {
                            checkCancelled(cancellation);
                            Path original =
                                    sourcePlan.artifact() instanceof OriginalArtifactPlan
                                            ? source.unchangedOriginalPath()
                                                    .orElseThrow(
                                                            () ->
                                                                    new IOException(
                                                                            "planned original source is no longer eligible"))
                                            : null;
                            var materialized =
                                    artifactWriter.materializePackageCopy(
                                            image.capture().sourceRevision(),
                                            source.sourcePyramid().levelZeroPixelsToSlide(),
                                            sourcePlan.sourceLevel(),
                                            sourcePlan.sourceEnvelopeAtLevel(),
                                            sourcePlan.artifact(),
                                            source.server(),
                                            artifactDestination,
                                            original,
                                            source.sourceRevisionVerifier());
                            checkCancelled(cancellation);
                            progressSink.accept(
                                    new Progress(
                                            Phase.ENCODING,
                                            completed.incrementAndGet(),
                                            totalArtifacts,
                                            entry.getImageName()));
                            return materialized;
                        }

                        @Override
                        public void close() throws IOException {
                            source.close();
                        }
                    };
                });
    }

    private ImageLifecycle.ReadOnlySourceSession openProjectImageSource(
            ProjectImageEntry<BufferedImage> entry, ActionSourceRevision sourceRevision)
            throws IOException {
        try {
            return imageLifecycle.openStoredSourceSession(entry, entry.getID(), sourceRevision);
        } catch (CancellationException error) {
            throw error;
        } catch (IOException error) {
            throw new IOException(imageReadFailure(entry, error), error);
        } catch (RuntimeException error) {
            throw new IllegalStateException(imageReadFailure(entry, error), error);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new CancellationException("training dataset preparation cancelled");
        }
    }
}
