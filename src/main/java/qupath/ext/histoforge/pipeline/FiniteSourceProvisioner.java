package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.client.SourceTransferProgress;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactSealed;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.lib.images.ImageData;

/** One image action's source preparation, publication, release, and temporary-file cleanup. */
final class FiniteSourceProvisioner {

    private static final Logger logger = LoggerFactory.getLogger(FiniteSourceProvisioner.class);

    private final ModelGatewayExecutor gateway;
    private final SourceArtifactWriter writer;
    private final Supplier<String> artifactIds;
    private final Path workspaceRoot;
    private final Consumer<ImageSourceProgress> sourceProgress;
    private final PreparedImageBudget preparedImages;

    FiniteSourceProvisioner(ModelGatewayExecutor gateway, SourceArtifactWriter writer) {
        this(
                gateway,
                writer,
                () -> UUID.randomUUID().toString(),
                null,
                ignored -> {},
                PreparedImageBudget.APPLICATION);
    }

    FiniteSourceProvisioner(
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            Consumer<ImageSourceProgress> sourceProgress) {
        this(
                gateway,
                writer,
                () -> UUID.randomUUID().toString(),
                null,
                sourceProgress,
                PreparedImageBudget.APPLICATION);
    }

    FiniteSourceProvisioner(
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            Supplier<String> artifactIds,
            Path workspaceRoot) {
        this(gateway, writer, artifactIds, workspaceRoot, ignored -> {});
    }

    FiniteSourceProvisioner(
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            Supplier<String> artifactIds,
            Path workspaceRoot,
            Consumer<ImageSourceProgress> sourceProgress) {
        this(
                gateway,
                writer,
                artifactIds,
                workspaceRoot,
                sourceProgress,
                new PreparedImageBudget());
    }

    FiniteSourceProvisioner(
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            Supplier<String> artifactIds,
            Path workspaceRoot,
            Consumer<ImageSourceProgress> sourceProgress,
            PreparedImageBudget preparedImages) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.artifactIds = Objects.requireNonNull(artifactIds, "artifactIds");
        this.sourceProgress = Objects.requireNonNull(sourceProgress, "sourceProgress");
        this.preparedImages = Objects.requireNonNull(preparedImages);
        this.workspaceRoot =
                workspaceRoot == null ? null : workspaceRoot.toAbsolutePath().normalize();
    }

    Session open() throws IOException {
        Path workspace;
        if (workspaceRoot == null) {
            workspace = Files.createTempDirectory("histoforge-action-");
        } else {
            Files.createDirectories(workspaceRoot);
            workspace = Files.createTempDirectory(workspaceRoot, "histoforge-action-");
        }
        return new Session(workspace);
    }

    /** One action-wide unchanged original prepared under an image lease for later upload. */
    record PreparedOriginal(PreparedSourceArtifact source, Path startRequestFile) {
        PreparedOriginal {
            source = Objects.requireNonNull(source, "source");
            startRequestFile =
                    Objects.requireNonNull(startRequestFile, "startRequestFile")
                            .toAbsolutePath()
                            .normalize();
            if (!(source.startRequest().origin() instanceof SourceArtifactCodec.OriginalWsi)) {
                throw new IllegalArgumentException(
                        "shared source preparation requires an unchanged original");
            }
        }
    }

    /**
     * Caller-owned action session; close is best-effort because backend expiry is authoritative.
     */
    final class Session implements AutoCloseable {

        private final Path workspace;
        private final Set<String> reservations = new LinkedHashSet<>();
        private boolean provisioned;
        private boolean closed;
        private PreparedOriginal preparedOriginal;
        private PreparedImageBudget.Reservation originalPreparation;
        private FiniteInputImageUploads inputImages;

        private Session(Path workspace) {
            this.workspace = workspace;
        }

        Map<ExecutionScopeCodec.RegionKey, RecipeExecutor.SourceProvision> provision(
                ImageExecutionPlan plan,
                SourceInputPlan sourcePlan,
                ImageData<BufferedImage> image,
                ImageLifecycle.WorkingImage working,
                SourcePlanningLimits limits,
                BooleanSupplier shouldStop)
                throws IOException, InterruptedException {
            return provision(
                    plan, sourcePlan, image, working, Optional.empty(), limits, shouldStop);
        }

        /** Reuse a coordinator-owned original without taking over its backend lifecycle. */
        Map<ExecutionScopeCodec.RegionKey, RecipeExecutor.SourceProvision> provision(
                ImageExecutionPlan plan,
                SourceInputPlan sourcePlan,
                ImageData<BufferedImage> image,
                ImageLifecycle.WorkingImage working,
                Optional<SealedOriginalSource> sharedOriginal,
                SourcePlanningLimits limits,
                BooleanSupplier shouldStop)
                throws IOException, InterruptedException {
            if (closed || provisioned) {
                throw new IllegalStateException("source session may provision exactly once");
            }
            provisioned = true;
            Optional<SealedOriginalSource> shared =
                    Objects.requireNonNull(sharedOriginal, "sharedOriginal");
            shared.ifPresent(value -> value.requireAction(plan, working.sourceRevision()));
            requireCapturedPlan(plan, sourcePlan, working);
            Set<qupath.ext.histoforge.contract.SourceCaptureCodec.Capture> captures =
                    new LinkedHashSet<>();
            for (var target : sourcePlan.targets()) {
                target.sourcePlan()
                        .ifPresent(
                                source -> {
                                    if (source.artifact()
                                            instanceof
                                            SourceInputPlan.IncrementalSourcePlan incremental) {
                                        captures.add(
                                                incremental.capture(
                                                        source.sourceRevision().pixelSource(),
                                                        plan.clientBuild().extension()));
                                    }
                                });
            }
            inputImages =
                    new FiniteInputImageUploads(
                            gateway,
                            writer,
                            image.getServer(),
                            working.sourceRevisionVerifier(),
                            limits,
                            plan.actionId(),
                            workspace,
                            artifactIds,
                            progress(plan),
                            captures,
                            preparedImages);
            Map<ExecutionScopeCodec.RegionKey, RecipeExecutor.SourceProvision> result =
                    new LinkedHashMap<>();
            Set<String> issuedIds = new LinkedHashSet<>();
            SourceInputPlan.TargetSourcePlan publishedOriginalPlan = null;
            RecipeExecutor.SourceProvision publishedOriginal = null;
            for (SourceInputPlan.TargetPlanningResult target : sourcePlan.targets()) {
                if (target.sourcePlan().isEmpty()) {
                    continue;
                }
                SourceInputPlan.TargetSourcePlan source = target.sourcePlan().orElseThrow();
                if (source.protocol() != SourceInputPlan.ExecutionProtocol.FINITE) {
                    UnsupportedOperationException cause =
                            new UnsupportedOperationException(
                                    "finite source execution cannot consume a recoverable target");
                    result.put(
                            target.target().key(),
                            new RecipeExecutor.FailedSource(
                                    cause,
                                    ImageResultReducer.failure(
                                            RunResults.FailureType.UNSUPPORTED_MODULE, cause)));
                    continue;
                }

                requireRunning(shouldStop);
                if (source.artifact()
                        instanceof SourceInputPlan.IncrementalSourcePlan incremental) {
                    if (shared.isPresent()) {
                        throw new IllegalArgumentException(
                                "shared original cannot satisfy incremental image delivery");
                    }
                    result.put(
                            target.target().key(),
                            new RecipeExecutor.CapturedSource(
                                    incremental.capture(
                                            source.sourceRevision().pixelSource(),
                                            plan.clientBuild().extension())));
                } else if (source.artifact() instanceof SourceInputPlan.OriginalArtifactPlan) {
                    shared.ifPresent(value -> value.requireTargetPlan(source));
                    if (publishedOriginalPlan == null) {
                        publishedOriginalPlan = source;
                        publishedOriginal =
                                shared.isPresent()
                                        ? new RecipeExecutor.PublishedSource(
                                                shared.orElseThrow().artifact())
                                        : publish(
                                                plan,
                                                source,
                                                image,
                                                working,
                                                shouldStop,
                                                issuedIds);
                    } else if (!publishedOriginalPlan
                                    .sourceRevision()
                                    .equals(source.sourceRevision())
                            || !publishedOriginalPlan.artifact().equals(source.artifact())) {
                        throw new IllegalStateException(
                                "one action cannot reuse different original source plans");
                    }
                    result.put(target.target().key(), publishedOriginal);
                } else {
                    if (shared.isPresent()) {
                        throw new IllegalArgumentException(
                                "shared original cannot satisfy a derived source plan");
                    }
                    result.put(
                            target.target().key(),
                            publish(plan, source, image, working, shouldStop, issuedIds));
                }
            }
            return Collections.unmodifiableMap(result);
        }

        RecipeExecutor.InputImageUpload imageUploads() {
            return Objects.requireNonNull(inputImages, "source provision has not been captured");
        }

        /**
         * Hash and bind the one original selected by the complete image action.
         *
         * <p>Only local file work occurs here. The caller may release its short image lease before
         * {@link #publishOriginal} performs network IO.
         */
        PreparedOriginal prepareOriginal(
                ImageExecutionPlan plan,
                SourceInputPlan sourcePlan,
                ImageData<BufferedImage> image,
                ImageLifecycle.WorkingImage working,
                BooleanSupplier shouldStop)
                throws IOException, InterruptedException {
            if (closed || provisioned) {
                throw new IllegalStateException("source session may provision exactly once");
            }
            provisioned = true;
            requireRunning(shouldStop);
            ImageExecutionPlan action = Objects.requireNonNull(plan, "plan");
            SourceInputPlan sources = Objects.requireNonNull(sourcePlan, "sourcePlan");
            if (!sources.targets().stream()
                    .map(SourceInputPlan.TargetPlanningResult::target)
                    .toList()
                    .equals(action.targetRegions())) {
                throw new IllegalArgumentException(
                        "source plan targets differ from the complete image action");
            }
            List<SourceInputPlan.TargetSourcePlan> nonempty =
                    sources.targets().stream()
                            .flatMap(target -> target.sourcePlan().stream())
                            .toList();
            if (nonempty.isEmpty()) {
                throw new IllegalArgumentException(
                        "an action without model source work has no original to publish");
            }
            SourceInputPlan.TargetSourcePlan representative = nonempty.getFirst();
            if (!(representative.artifact() instanceof SourceInputPlan.OriginalArtifactPlan)
                    || nonempty.stream()
                            .anyMatch(
                                    source ->
                                            !source.sourceRevision()
                                                            .pixelSource()
                                                            .equals(
                                                                    representative
                                                                            .sourceRevision()
                                                                            .pixelSource())
                                                    || !source.artifact()
                                                            .equals(representative.artifact()))) {
                throw new IllegalArgumentException(
                        "complete action did not select one shared unchanged original");
            }

            String artifactId =
                    CanonicalCodec.requireCanonicalUuid(artifactIds.get(), "source_artifact_id");
            Path original =
                    working.unchangedOriginalPath()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "selected original source has no unchanged local file"));
            progress(action).accept(SourceTransferProgress.preparing());
            Path upload = original;
            if (representative.artifact().ownsUploadFile()) {
                upload = workspace.resolve(artifactId + ".zip");
                originalPreparation = preparedImages.acquire(shouldStop);
                originalPreparation.bind(upload);
            }
            PreparedSourceArtifact prepared =
                    writer.materialize(
                            representative,
                            Objects.requireNonNull(image, "image").getServer(),
                            upload,
                            original,
                            action.actionId(),
                            artifactId,
                            working.sourceRevisionVerifier());
            reservations.add(artifactId);
            preparedOriginal =
                    new PreparedOriginal(prepared, workspace.resolve(artifactId + ".start.json"));
            return preparedOriginal;
        }

        /** Upload the prepared action-wide original while retaining lifecycle ownership here. */
        SealedOriginalSource publishOriginal(
                ImageExecutionPlan plan,
                RasterInputCodec.ActionSourceRevision sourceRevision,
                PreparedOriginal prepared,
                BooleanSupplier shouldStop)
                throws IOException, InterruptedException, InferenceException {
            if (closed || preparedOriginal == null || !preparedOriginal.equals(prepared)) {
                throw new IllegalStateException(
                        "shared original publication requires this session's preparation");
            }
            requireRunning(shouldStop);
            ImageExecutionPlan action = Objects.requireNonNull(plan, "plan");
            RasterInputCodec.ActionSourceRevision captured =
                    Objects.requireNonNull(sourceRevision, "sourceRevision");
            SourceArtifactSealed sealed;
            Throwable failure = null;
            try {
                sealed =
                        gateway.publishSource(
                                prepared.source(), prepared.startRequestFile(), progress(action));
            } catch (IOException
                    | InterruptedException
                    | InferenceException
                    | RuntimeException
                    | Error stopped) {
                failure = stopped;
                throw stopped;
            } finally {
                if (originalPreparation != null) {
                    try {
                        Files.deleteIfExists(prepared.source().path());
                    } catch (IOException cleanupFailure) {
                        if (failure == null) throw cleanupFailure;
                        failure.addSuppressed(cleanupFailure);
                    } finally {
                        originalPreparation.close();
                    }
                }
            }
            return new SealedOriginalSource(
                    action.actionId(),
                    action.imageId(),
                    action.clientBuild(),
                    action.module().deploymentId(),
                    action.module().module(),
                    captured.pixelSource(),
                    sealed.artifact(),
                    prepared.source().startRequest());
        }

        private RecipeExecutor.SourceProvision publish(
                ImageExecutionPlan plan,
                SourceInputPlan.TargetSourcePlan source,
                ImageData<BufferedImage> image,
                ImageLifecycle.WorkingImage working,
                BooleanSupplier shouldStop,
                Set<String> issuedIds)
                throws IOException, InterruptedException {
            String artifactId =
                    CanonicalCodec.requireCanonicalUuid(artifactIds.get(), "source_artifact_id");
            if (!issuedIds.add(artifactId)) {
                throw new IllegalStateException(
                        "source artifact IDs must be unique within one action");
            }
            Path sourceFile =
                    !source.artifact().ownsUploadFile()
                            ? working.unchangedOriginalPath()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "selected original source has no"
                                                                    + " unchanged local file"))
                            : workspace.resolve(artifactId + suffix(source.artifact()));
            Path startRequestFile = workspace.resolve(artifactId + ".start.json");
            var preparation =
                    source.artifact().ownsUploadFile() ? preparedImages.acquire(shouldStop) : null;
            if (preparation != null) preparation.bind(sourceFile);
            Throwable failure = null;
            try {
                final PreparedSourceArtifact prepared;
                try {
                    progress(plan).accept(SourceTransferProgress.preparing());
                    prepared =
                            writer.materialize(
                                    source,
                                    image.getServer(),
                                    sourceFile,
                                    working.unchangedOriginalPath().orElse(null),
                                    plan.actionId(),
                                    artifactId,
                                    working.sourceRevisionVerifier());
                } catch (CancellationException cancellation) {
                    throw cancellation;
                } catch (IOException | RuntimeException extractionFailure) {
                    failure = extractionFailure;
                    clearProgress(plan);
                    return new RecipeExecutor.FailedSource(
                            extractionFailure,
                            ImageResultReducer.failure(
                                    RunResults.FailureType.EXTRACTION, extractionFailure));
                }

                requireRunning(shouldStop);
                reservations.add(artifactId);
                try {
                    var sealed = gateway.publishSource(prepared, startRequestFile, progress(plan));
                    return new RecipeExecutor.PublishedSource(sealed.artifact());
                } catch (CancellationException cancellation) {
                    throw cancellation;
                } catch (IOException | InferenceException publicationFailure) {
                    failure = publicationFailure;
                    clearProgress(plan);
                    return new RecipeExecutor.FailedSource(
                            publicationFailure, RecipeFailures.detail(publicationFailure));
                } catch (RuntimeException publicationFailure) {
                    failure = publicationFailure;
                    clearProgress(plan);
                    return new RecipeExecutor.FailedSource(
                            publicationFailure,
                            ImageResultReducer.failure(
                                    RunResults.FailureType.SUBMISSION, publicationFailure));
                }
            } catch (InterruptedException | RuntimeException | Error stopped) {
                failure = stopped;
                throw stopped;
            } finally {
                // Finite work is retried as a new action, so neither success nor failure needs
                // this exported image. Never delete the user's original slide.
                if (source.artifact().ownsUploadFile()) {
                    try {
                        Files.deleteIfExists(sourceFile);
                    } catch (IOException cleanupFailure) {
                        if (failure == null) throw cleanupFailure;
                        failure.addSuppressed(cleanupFailure);
                    } finally {
                        preparation.close();
                    }
                }
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (inputImages != null) inputImages.close();
            releaseSources();
            deleteWorkspace();
            if (originalPreparation != null) originalPreparation.close();
        }

        private void releaseSources() {
            boolean restoreInterrupt = Thread.interrupted();
            for (String artifactId : reservations) {
                try {
                    gateway.releaseSource(artifactId);
                } catch (InterruptedException interrupted) {
                    restoreInterrupt = true;
                    logger.warn("Interrupted while releasing source artifact {}", artifactId);
                } catch (IOException | InferenceException | RuntimeException failure) {
                    logger.warn("Failed to release source artifact {}", artifactId, failure);
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }

        private void deleteWorkspace() {
            try (Stream<Path> paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException failure) {
                logger.warn("Failed to delete finite-action workspace {}", workspace, failure);
                registerDeleteOnExit(workspace);
            }
        }
    }

    private Consumer<SourceTransferProgress> progress(ImageExecutionPlan plan) {
        return transfer ->
                sourceProgress.accept(
                        new ImageSourceProgress(plan.actionId(), plan.imageId(), transfer));
    }

    private void clearProgress(ImageExecutionPlan plan) {
        sourceProgress.accept(
                new ImageSourceProgress(plan.actionId(), plan.imageId(), Optional.empty()));
    }

    private static void registerDeleteOnExit(Path workspace) {
        try (Stream<Path> paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().deleteOnExit());
        } catch (IOException ignored) {
            workspace.toFile().deleteOnExit();
        }
    }

    private static String suffix(SourceInputPlan.SourceArtifactPlan artifact) {
        if (artifact instanceof SourceInputPlan.OriginalArtifactPlan) {
            return ".zip";
        }
        return ((SourceInputPlan.DerivedArtifactPlan) artifact).container()
                        == SourceArtifactCodec.SourceContainer.PNG
                ? ".png"
                : ".ome.tif";
    }

    private static void requireCapturedPlan(
            ImageExecutionPlan plan,
            SourceInputPlan sourcePlan,
            ImageLifecycle.WorkingImage working) {
        List<ExecutionScopeCodec.TargetRegion> targets =
                sourcePlan.targets().stream()
                        .map(SourceInputPlan.TargetPlanningResult::target)
                        .toList();
        if (!targets.equals(plan.targetRegions())) {
            throw new IllegalArgumentException(
                    "finite source-plan targets differ from the captured execution plan");
        }
        for (SourceInputPlan.TargetPlanningResult target : sourcePlan.targets()) {
            target.sourcePlan()
                    .ifPresent(
                            source -> {
                                if (!source.sourceRevision().equals(working.sourceRevision())) {
                                    throw new ImageActionSubsetPlanner.CapturedPlanConflict(
                                            "pixel source changed after action-wide capture");
                                }
                            });
        }
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("image action cancelled");
        }
    }
}
