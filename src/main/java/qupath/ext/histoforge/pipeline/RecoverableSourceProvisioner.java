package qupath.ext.histoforge.pipeline;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import qupath.ext.histoforge.client.InferenceException;
import qupath.ext.histoforge.client.ModelGatewayExecutor;
import qupath.ext.histoforge.client.SourceTransferProgress;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.GatewayErrorCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactRef;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactSealed;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec.SourceArtifactSealRequest;
import qupath.ext.histoforge.large.HierarchyTransactionDigester;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionHandleStore.ResumableAction;
import qupath.ext.histoforge.large.LargeActionTypes.HierarchyTransactionIdentity;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.large.LargeActionTypes.LocalFileRecord;
import qupath.ext.histoforge.large.LargeActionTypes.SourceUploadRef;
import qupath.ext.histoforge.large.RecoverySourceFiles;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;
import qupath.ext.histoforge.spatial.SourceInputPlan;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;
import qupath.lib.images.ImageData;

/**
 * Captures one recoverable target's source and prepares its image delivery.
 *
 * <p>The handle store owns durable state and local recovery files, the source writer owns exact
 * bytes, and the gateway owns upload/seal. A complete source file is sealed before model execution.
 * An incremental source captures its sampling geometry first; RunImageDelivery then records and
 * supplies each bounded image alongside its accepted model request.
 */
final class RecoverableSourceProvisioner {

    /** The exact backend source record can no longer continue under its retained artifact ID. */
    static final class SourceArtifactUnavailableException extends InferenceException {

        SourceArtifactUnavailableException(InferenceException cause) {
            super(cause.statusCode(), cause.gatewayError().orElseThrow());
            initCause(cause);
        }
    }

    private final LargeActionHandleStore handles;
    private final ModelGatewayExecutor gateway;
    private final SourceArtifactWriter writer;
    private final Clock clock;
    private final Supplier<String> artifactIds;
    private final Consumer<ImageSourceProgress> sourceProgress;
    private final PreparedImageBudget preparedImages;
    private final SourcePlanningLimits limits;

    /** Reconstruct sampling geometry without requiring historical uploaded image bytes. */
    static RecipeExecutor.AvailableSource retainedSource(LargeActionHandle handle) {
        if (handle.sourcePlan() instanceof SourceInputPlan.IncrementalSourcePlan captured) {
            return new RecipeExecutor.CapturedSource(
                    captured.capture(
                            handle.baseline().pixelSource(), handle.clientBuild().extension()));
        }
        return new RecipeExecutor.PublishedSource(
                handle.source().orElseThrow().sealedArtifact().orElseThrow());
    }

    RecoverableSourceProvisioner(
            LargeActionHandleStore handles,
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            SourcePlanningLimits limits) {
        this(
                handles,
                gateway,
                writer,
                limits,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                ignored -> {},
                PreparedImageBudget.APPLICATION);
    }

    RecoverableSourceProvisioner(
            LargeActionHandleStore handles,
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            SourcePlanningLimits limits,
            Consumer<ImageSourceProgress> sourceProgress) {
        this(
                handles,
                gateway,
                writer,
                limits,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                sourceProgress,
                PreparedImageBudget.APPLICATION);
    }

    RecoverableSourceProvisioner(
            LargeActionHandleStore handles,
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            SourcePlanningLimits limits,
            Clock clock,
            Supplier<String> artifactIds) {
        this(handles, gateway, writer, limits, clock, artifactIds, ignored -> {});
    }

    RecoverableSourceProvisioner(
            LargeActionHandleStore handles,
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            SourcePlanningLimits limits,
            Clock clock,
            Supplier<String> artifactIds,
            Consumer<ImageSourceProgress> sourceProgress) {
        this(
                handles,
                gateway,
                writer,
                limits,
                clock,
                artifactIds,
                sourceProgress,
                new PreparedImageBudget());
    }

    RecoverableSourceProvisioner(
            LargeActionHandleStore handles,
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            SourcePlanningLimits limits,
            Clock clock,
            Supplier<String> artifactIds,
            Consumer<ImageSourceProgress> sourceProgress,
            PreparedImageBudget preparedImages) {
        this.handles = Objects.requireNonNull(handles, "handles");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.artifactIds = Objects.requireNonNull(artifactIds, "artifactIds");
        this.sourceProgress = Objects.requireNonNull(sourceProgress, "sourceProgress");
        this.preparedImages = Objects.requireNonNull(preparedImages);
        this.limits = Objects.requireNonNull(limits);
    }

    /** Captured sampling geometry or a sealed file, with the latest persisted action state. */
    record ProvisionedSource(ResumableAction action, RecipeExecutor.AvailableSource source) {
        ProvisionedSource {
            action = Objects.requireNonNull(action, "action");
            source = Objects.requireNonNull(source, "source");
            if (!retainedSource(action.handle()).equals(source)) {
                throw new IllegalArgumentException(
                        "source does not match the retained large action");
            }
        }
    }

    /** No pixels are prepared until an accepted run asks for its processing images. */
    RunImageDelivery imageDelivery(
            ImageLifecycle.WorkingImage working, PreparedImageBudget.Reservation initial) {
        return new RunImageDelivery(
                new RecoverableImageUploads(
                        handles,
                        gateway,
                        writer,
                        working,
                        limits,
                        preparedImages,
                        initial,
                        sourceProgress,
                        artifactIds));
    }

    /** Persist the captured source before model submission; this does not upload an image. */
    static ProvisionedSource capture(
            ResumableAction action,
            ImageExecutionPlan plan,
            SourceInputPlan.TargetPlanningResult target,
            ImageLifecycle.WorkingImage working,
            LargeActionHandleStore handles)
            throws IOException {
        var current =
                handles.requireResumable(
                        action.handle().actionId(), action.handle().handleGenerationId());
        if (!current.equals(action) || current.handle().phase() != LargeActionPhase.PREPARING)
            throw new IllegalStateException("source capture requires the latest PREPARING action");
        var source = requireSourcePlan(current, plan, target, working);
        if (!(source.artifact() instanceof SourceInputPlan.IncrementalSourcePlan))
            throw new IllegalArgumentException(
                    "source capture requires incremental image delivery");
        requireCurrentBaseline(current.handle(), working);
        working.sourceRevisionVerifier().requireCurrent(source.sourceRevision().pixelSource());
        var handle = current.handle();
        var next =
                new LargeActionHandle(
                        handle.schemaVersion(),
                        handle.actionId(),
                        handle.handleGenerationId(),
                        handle.clientBuild(),
                        handle.imageId(),
                        handle.module(),
                        handle.action(),
                        handle.target(),
                        handle.baseline(),
                        handle.sourcePlan(),
                        Optional.empty(),
                        handle.steps(),
                        handle.expectedCommittedHierarchy(),
                        LargeActionPhase.EXECUTING,
                        handle.failure(),
                        handle.createdAt(),
                        handle.updatedAt(),
                        handle.selectionRegions());
        handles.save(next);
        var saved = handles.requireResumable(next.actionId(), next.handleGenerationId());
        return new ProvisionedSource(saved, retainedSource(saved.handle()));
    }

    /** Complete local source publication ready for network IO outside the image lease. */
    record PreparedUpload(
            ResumableAction action,
            PreparedSourceArtifact source,
            PreparedImageBudget.Reservation preparation)
            implements AutoCloseable {
        PreparedUpload {
            action = Objects.requireNonNull(action, "action");
            source = Objects.requireNonNull(source, "source");
            SourceUploadRef retained = action.handle().source().orElseThrow();
            if (action.handle().phase() != LargeActionPhase.UPLOADING
                    || retained.sealedArtifact().isPresent()
                    || !retained.artifactId().equals(source.startRequest().artifactId())
                    || !action.sourceStartRequest().orElseThrow().equals(source.startRequest())) {
                throw new IllegalArgumentException(
                        "prepared upload does not match the retained large action");
            }
        }

        @Override
        public void close() {
            if (preparation != null) preparation.close();
        }
    }

    PreparedImageBudget.Reservation reserveNewImage(
            SourceInputPlan.TargetPlanningResult target, BooleanSupplier shouldStop)
            throws IOException, InterruptedException {
        preparedImages.register(handles);
        var artifact = target.sourcePlan().orElseThrow().artifact();
        return artifact instanceof SourceInputPlan.OriginalArtifactPlan
                        && !artifact.ownsUploadFile()
                ? null
                : preparedImages.acquire(shouldStop);
    }

    /**
     * Materialize one new source and persist its exact UPLOADING state.
     *
     * <p>The caller holds the short image lease only for this method. Any failure before the
     * UPLOADING transition leaves PREPARING. Once this returns, the exact start request and any
     * owned upload file are durable and network publication can run without the lease.
     */
    PreparedUpload prepare(
            ResumableAction action,
            ImageExecutionPlan plan,
            SourceInputPlan.TargetPlanningResult targetSource,
            ImageLifecycle.WorkingImage working,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException {
        ResumableAction current = requireLatest(action, LargeActionPhase.PREPARING);
        requireSourcePlan(current, plan, targetSource, working);
        // A failed PREPARING attempt has no retained upload. Remove its incomplete image before
        // reserving capacity, so continuing that attempt cannot be blocked by its own leftover.
        handles.discardUnreferencedDirectory(RecoverySourceFiles.directory(current.handle()));
        return prepare(
                current,
                plan,
                targetSource,
                working,
                shouldStop,
                reserveNewImage(targetSource, shouldStop));
    }

    PreparedUpload prepare(
            ResumableAction action,
            ImageExecutionPlan plan,
            SourceInputPlan.TargetPlanningResult targetSource,
            ImageLifecycle.WorkingImage working,
            BooleanSupplier shouldStop,
            PreparedImageBudget.Reservation preparation)
            throws IOException {
        try {
            return prepareNewSource(
                    requireLatest(action, LargeActionPhase.PREPARING),
                    plan,
                    targetSource,
                    working,
                    shouldStop,
                    preparation);
        } catch (IOException | RuntimeException | Error failure) {
            if (preparation != null) preparation.close();
            throw failure;
        }
    }

    /** Replace an unavailable source only before this handle retains any model-run request. */
    PreparedUpload replaceUnavailable(
            ResumableAction action,
            ImageExecutionPlan plan,
            SourceInputPlan.TargetPlanningResult targetSource,
            ImageLifecycle.WorkingImage working,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException {
        ResumableAction current = requireLatest(action);
        if ((current.handle().phase() != LargeActionPhase.UPLOADING
                        && current.handle().phase() != LargeActionPhase.EXECUTING)
                || current.handle().hasRetainedModelRunRequest()) {
            throw new IllegalStateException(
                    "source replacement requires an unreferenced UPLOADING or EXECUTING source");
        }
        var source = requireSourcePlan(current, plan, targetSource, working);
        requireCurrentBaseline(current.handle(), working);
        requireRunning(shouldStop);
        var retainedFile = current.handle().source().orElseThrow().ownedUploadFile();
        if (retainedFile.isPresent()
                && !Files.notExists(
                        handles.root()
                                .resolve(retainedFile.orElseThrow().projectRelativePosixPath()),
                        LinkOption.NOFOLLOW_LINKS)) {
            Path path = handles.requireFile(retainedFile.orElseThrow());
            preparedImages.register(handles);
            var preparation = preparedImages.resume(path);
            try {
                working.sourceRevisionVerifier()
                        .requireCurrent(source.sourceRevision().pixelSource());
                var previous = current.sourceStartRequest().orElseThrow();
                var start =
                        new SourceArtifactCodec.SourceArtifactStartRequest(
                                previous.schemaVersion(),
                                previous.creationActionId(),
                                artifactIds.get(),
                                previous.container(),
                                previous.totalBytes(),
                                previous.chunkSizeBytes(),
                                previous.origin(),
                                previous.requiredLevels());
                var prepared =
                        new PreparedSourceArtifact(
                                path,
                                start,
                                new SourceArtifactSealRequest(retainedFile.orElseThrow().sha256()));
                return retainUpload(current, prepared, retainedFile, preparation, shouldStop);
            } catch (IOException | RuntimeException | Error failure) {
                preparation.close();
                throw failure;
            }
        }
        var preparation = reserveNewImage(targetSource, shouldStop);
        try {
            return prepareNewSource(current, plan, targetSource, working, shouldStop, preparation);
        } catch (IOException | RuntimeException | Error failure) {
            if (preparation != null) preparation.close();
            throw failure;
        }
    }

    private PreparedUpload prepareNewSource(
            ResumableAction current,
            ImageExecutionPlan plan,
            SourceInputPlan.TargetPlanningResult targetSource,
            ImageLifecycle.WorkingImage working,
            BooleanSupplier shouldStop,
            PreparedImageBudget.Reservation preparation)
            throws IOException {
        SourceInputPlan.TargetSourcePlan source =
                requireSourcePlan(current, plan, targetSource, working);
        requireCurrentBaseline(current.handle(), working);
        requireRunning(shouldStop);

        String directory = RecoverySourceFiles.directory(current.handle());
        String artifactId =
                CanonicalCodec.requireCanonicalUuid(artifactIds.get(), "source_artifact_id");
        String sourceRelative = directory + "/" + artifactId + sourceSuffix(source.artifact());
        Path sourcePath =
                !source.artifact().ownsUploadFile()
                        ? working.unchangedOriginalPath()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "selected original source has no unchanged local file"))
                        : handles.prepareFilePath(sourceRelative);
        if (source.artifact().ownsUploadFile())
            Objects.requireNonNull(preparation, "prepared-image reservation").bind(sourcePath);

        ImageData<BufferedImage> image = working.imageData();
        progress(current.handle()).accept(SourceTransferProgress.preparing());
        PreparedSourceArtifact prepared =
                writer.materialize(
                        source,
                        image.getServer(),
                        sourcePath,
                        working.unchangedOriginalPath().orElse(null),
                        current.handle().actionId(),
                        artifactId,
                        working.sourceRevisionVerifier());
        requirePrepared(prepared, current.handle(), source, sourcePath, artifactId);

        Optional<LocalFileRecord> ownedUploadFile =
                source.artifact().ownsUploadFile()
                        ? Optional.of(handles.recordExistingFile(sourceRelative))
                        : Optional.empty();
        ownedUploadFile.ifPresent(file -> requireRecordedSource(prepared, file));
        return retainUpload(current, prepared, ownedUploadFile, preparation, shouldStop);
    }

    private PreparedUpload retainUpload(
            ResumableAction current,
            PreparedSourceArtifact prepared,
            Optional<LocalFileRecord> ownedUploadFile,
            PreparedImageBudget.Reservation preparation,
            BooleanSupplier shouldStop)
            throws IOException {
        String artifactId = prepared.startRequest().artifactId();
        String startRelative =
                RecoverySourceFiles.directory(current.handle()) + "/" + artifactId + ".start.json";
        LocalFileRecord startFile =
                handles.publishImmutable(
                        startRelative,
                        CanonicalCodec.canonicalBytes(
                                SourceArtifactCodec.toJson(prepared.startRequest())));
        SourceUploadRef uploadingSource =
                new SourceUploadRef(artifactId, startFile, ownedUploadFile, Optional.empty());
        current = saveSource(current, uploadingSource, LargeActionPhase.UPLOADING);

        requireRunning(shouldStop);
        return new PreparedUpload(current, prepared, preparation);
    }

    /** Publish one fully prepared source without retaining a QuPath image lease. */
    ProvisionedSource publish(PreparedUpload upload, BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        PreparedUpload supplied = Objects.requireNonNull(upload, "upload");
        try (supplied) {
            ResumableAction current = requireLatest(supplied.action(), LargeActionPhase.UPLOADING);
            SourceUploadRef retained = current.handle().source().orElseThrow();
            if (!current.equals(supplied.action())
                    || !current.sourceStartRequest()
                            .orElseThrow()
                            .equals(supplied.source().startRequest())) {
                throw new IllegalStateException(
                        "prepared upload differs from the latest persisted action state");
            }
            Path expectedSource =
                    retained.ownedUploadFile().isPresent()
                            ? handles.requireFile(retained.ownedUploadFile().orElseThrow())
                            : supplied.source().path();
            if (!expectedSource.equals(supplied.source().path())) {
                throw new IllegalArgumentException(
                        "prepared source path differs from retained recovery state");
            }
            requireRunning(shouldStop);
            SourceArtifactSealed sealed;
            try {
                sealed =
                        gateway.publishSource(
                                supplied.source(),
                                handles.requireFile(retained.startRequestFile()),
                                progress(current.handle()));
            } catch (InferenceException failure) {
                throw classifySourceFailure(failure);
            }
            return retainSealed(current, sealed);
        }
    }

    /** Resume one exact UPLOADING source without rebuilding its completed upload file. */
    ProvisionedSource resume(
            ResumableAction action,
            Optional<Path> unchangedOriginalSource,
            BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        ResumableAction current = requireLatest(action, LargeActionPhase.UPLOADING);
        SourceUploadRef source = current.handle().source().orElseThrow();
        Path sourceFile = sourceFile(source, unchangedOriginalSource);
        preparedImages.register(handles);
        try (var preparation =
                source.ownedUploadFile().isPresent() ? preparedImages.resume(sourceFile) : null) {
            requireRunning(shouldStop);
            SourceArtifactSealed sealed;
            try {
                sealed =
                        gateway.resumeSource(
                                sourceFile,
                                handles.requireFile(source.startRequestFile()),
                                progress(current.handle()));
            } catch (InferenceException failure) {
                throw classifySourceFailure(failure);
            }
            return retainSealed(current, sealed);
        }
    }

    /** Revalidate the saved cloud identity without requiring an already-uploaded local image. */
    ProvisionedSource verifySealed(ResumableAction action, BooleanSupplier shouldStop)
            throws IOException, InterruptedException, InferenceException {
        ResumableAction current = requireLatest(action, LargeActionPhase.EXECUTING);
        SourceUploadRef source = current.handle().source().orElseThrow();
        SourceArtifactRef expected = source.sealedArtifact().orElseThrow();
        requireRunning(shouldStop);
        try {
            gateway.verifySealedSource(
                    current.sourceStartRequest().orElseThrow(),
                    expected,
                    progress(current.handle()));
        } catch (InferenceException failure) {
            throw classifySourceFailure(failure);
        }
        handles.discardSealedSourceImage(current);
        return new ProvisionedSource(current, new RecipeExecutor.PublishedSource(expected));
    }

    /** Attach an already sealed original to another freshly planned PREPARING target. */
    ProvisionedSource attachOriginal(
            ResumableAction action,
            SealedOriginalSource sharedOriginal,
            ImageExecutionPlan plan,
            SourceInputPlan.TargetPlanningResult targetSource,
            ImageLifecycle.WorkingImage working)
            throws IOException {
        ResumableAction current = requireLatest(action, LargeActionPhase.PREPARING);
        SourceInputPlan.TargetSourcePlan expected =
                requireSourcePlan(current, plan, targetSource, working);
        requireCurrentBaseline(current.handle(), working);
        if (!(expected.artifact() instanceof SourceInputPlan.OriginalArtifactPlan)) {
            throw new IllegalArgumentException(
                    "shared original requires an original source plan for this target");
        }
        SealedOriginalSource shared = Objects.requireNonNull(sharedOriginal, "sharedOriginal");
        shared.requireAction(plan, working.sourceRevision());
        shared.requireTargetPlan(expected);
        SourceArtifactCodec.SourceArtifactStartRequest start = shared.startRequest();
        working.sourceRevisionVerifier().requireCurrent(expected.sourceRevision().pixelSource());
        String directory = RecoverySourceFiles.directory(current.handle());
        handles.discardUnreferencedDirectory(directory);
        String startRelative = directory + "/" + start.artifactId() + ".start.json";
        LocalFileRecord startFile =
                handles.publishImmutable(
                        startRelative,
                        CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(start)));
        SourceUploadRef source =
                new SourceUploadRef(
                        start.artifactId(),
                        startFile,
                        Optional.empty(),
                        Optional.of(shared.artifact()));
        ResumableAction retained = saveSource(current, source, LargeActionPhase.EXECUTING);
        return new ProvisionedSource(
                retained, new RecipeExecutor.PublishedSource(shared.artifact()));
    }

    private ProvisionedSource retainSealed(ResumableAction uploading, SourceArtifactSealed sealed)
            throws IOException {
        SourceUploadRef current = uploading.handle().source().orElseThrow();
        SourceArtifactRef artifact = Objects.requireNonNull(sealed, "sealed").artifact();
        SourceUploadRef retained =
                new SourceUploadRef(
                        current.artifactId(),
                        current.startRequestFile(),
                        current.ownedUploadFile(),
                        Optional.of(artifact));
        ResumableAction executing = saveSource(uploading, retained, LargeActionPhase.EXECUTING);
        handles.discardSealedSourceImage(executing);
        return new ProvisionedSource(executing, new RecipeExecutor.PublishedSource(artifact));
    }

    private ResumableAction saveSource(
            ResumableAction current, SourceUploadRef source, LargeActionPhase phase)
            throws IOException {
        LargeActionHandle handle = current.handle();
        boolean replacesArtifact =
                handle.source()
                        .filter(existing -> !existing.artifactId().equals(source.artifactId()))
                        .isPresent();
        LargeActionHandle next =
                new LargeActionHandle(
                        handle.schemaVersion(),
                        handle.actionId(),
                        handle.handleGenerationId(),
                        handle.clientBuild(),
                        handle.imageId(),
                        handle.module(),
                        handle.action(),
                        handle.target(),
                        handle.baseline(),
                        handle.sourcePlan(),
                        Optional.of(source),
                        replacesArtifact ? List.of() : handle.steps(),
                        handle.expectedCommittedHierarchy(),
                        phase,
                        handle.failure(),
                        handle.createdAt(),
                        now(handle.updatedAt()),
                        handle.selectionRegions());
        handles.save(next);
        return handles.requireResumable(next.actionId(), next.handleGenerationId());
    }

    private Path sourceFile(SourceUploadRef source, Optional<Path> unchangedOriginalSource)
            throws IOException {
        if (source.ownedUploadFile().isPresent()) {
            if (unchangedOriginalSource.isPresent()) {
                throw new IllegalArgumentException(
                        "owned upload recovery must not supply an original source path");
            }
            return handles.requireFile(source.ownedUploadFile().orElseThrow());
        }
        return Objects.requireNonNull(unchangedOriginalSource, "unchangedOriginalSource")
                .map(path -> path.toAbsolutePath().normalize())
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "original source recovery requires its unchanged local file"));
    }

    private static InferenceException classifySourceFailure(InferenceException failure) {
        boolean unavailable =
                failure.gatewayError()
                        .map(GatewayErrorCodec.Error::code)
                        .filter(
                                code ->
                                        code == GatewayErrorCodec.Code.ARTIFACT_NOT_FOUND
                                                || code == GatewayErrorCodec.Code.ARTIFACT_EXPIRED
                                                || code
                                                        == GatewayErrorCodec.Code
                                                                .ARTIFACT_PRECONDITION_FAILED)
                        .isPresent();
        return unavailable ? new SourceArtifactUnavailableException(failure) : failure;
    }

    private ResumableAction requireLatest(ResumableAction action, LargeActionPhase phase)
            throws IOException {
        ResumableAction current = requireLatest(action);
        if (current.handle().phase() != phase) {
            throw new IllegalArgumentException(
                    "recoverable source publication requires phase " + phase);
        }
        return current;
    }

    private ResumableAction requireLatest(ResumableAction action) throws IOException {
        ResumableAction supplied = Objects.requireNonNull(action, "action");
        ResumableAction current =
                handles.requireResumable(
                        supplied.handle().actionId(), supplied.handle().handleGenerationId());
        if (!current.equals(supplied)) {
            throw new IllegalStateException(
                    "recoverable source publication requires the latest persisted action state");
        }
        return current;
    }

    private static SourceInputPlan.TargetSourcePlan requireSourcePlan(
            ResumableAction action,
            ImageExecutionPlan plan,
            SourceInputPlan.TargetPlanningResult targetSource,
            ImageLifecycle.WorkingImage working) {
        LargeActionHandle handle = action.handle();
        ImageExecutionPlan executionPlan = Objects.requireNonNull(plan, "plan");
        SourceInputPlan.TargetPlanningResult target =
                Objects.requireNonNull(targetSource, "targetSource");
        if (!executionPlan.actionId().equals(handle.actionId())
                || !executionPlan.clientBuild().equals(handle.clientBuild())
                || !executionPlan.imageId().equals(handle.imageId())
                || !executionPlan.module().equals(handle.module())
                || !executionPlan.action().equals(handle.action())
                || !executionPlan.targetRegions().equals(List.of(handle.target()))
                || !executionPlan.selectionRegions().equals(handle.selectionRegions())
                || !target.target().equals(handle.target())
                || target.sourcePlan().isEmpty()) {
            throw new IllegalArgumentException(
                    "recoverable source plan belongs to another large action");
        }
        SourceInputPlan.TargetSourcePlan source = target.sourcePlan().orElseThrow();
        if (!source.artifact().equals(handle.sourcePlan())) {
            throw new IllegalArgumentException("source plan differs from the captured choice");
        }
        if (source.protocol() != SourceInputPlan.ExecutionProtocol.RECOVERABLE
                || !source.sourceRevision()
                        .imageDataRevision()
                        .imageId()
                        .equals(working.sourceRevision().imageDataRevision().imageId())
                || !source.sourceRevision()
                        .pixelSource()
                        .equals(working.sourceRevision().pixelSource())
                || !source.sourceRevision().imageDataRevision().imageId().equals(handle.imageId())
                || !source.sourceRevision().pixelSource().equals(handle.baseline().pixelSource())) {
            throw new IllegalArgumentException(
                    "recoverable source plan does not match the retained image revision");
        }
        return source;
    }

    /** Reject target-transaction drift before source replay or additional durable work. */
    static void requireCurrentBaseline(
            LargeActionHandle handle, ImageLifecycle.WorkingImage working) {
        final HierarchyTransactionIdentity current;
        try {
            current =
                    HierarchyTransactionDigester.captureBaseline(
                            handle.target(),
                            handle.baseline().hierarchy().replacementClosure(),
                            handle.module().metrics().isPresent(),
                            working.imageData().getHierarchy(),
                            handle.selectionRegions());
        } catch (IllegalArgumentException failure) {
            throw new LargeActionTransactionValidator.Conflict(
                    "recoverable source hierarchy baseline cannot be recaptured", failure);
        }
        if (!current.equals(handle.baseline().hierarchy())) {
            throw new LargeActionTransactionValidator.Conflict(
                    "recoverable source hierarchy changed after PREPARING capture");
        }
    }

    private static void requirePrepared(
            PreparedSourceArtifact prepared,
            LargeActionHandle handle,
            SourceInputPlan.TargetSourcePlan source,
            Path expectedPath,
            String artifactId) {
        PreparedSourceArtifact value = Objects.requireNonNull(prepared, "prepared");
        if (!value.path().equals(expectedPath.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                    "materialized source does not match the recoverable source plan");
        }
        requireStartMatchesPlan(value.startRequest(), handle, source, artifactId);
    }

    private static void requireStartMatchesPlan(
            SourceArtifactCodec.SourceArtifactStartRequest start,
            LargeActionHandle handle,
            SourceInputPlan.TargetSourcePlan source,
            String artifactId) {
        source.artifact().requireMatchingStart(start);
        var pixelSource =
                switch (start.origin()) {
                    case SourceArtifactCodec.OriginalWsi original -> original.source();
                    case SourceArtifactCodec.DerivedRegion derived -> derived.source();
                };
        if (!start.creationActionId().equals(handle.actionId())
                || !start.artifactId().equals(artifactId)
                || !pixelSource.equals(source.sourceRevision().pixelSource())
                || (start.origin() instanceof SourceArtifactCodec.DerivedRegion derived
                        && !derived.writerBuild().equals(handle.clientBuild().extension()))) {
            throw new IllegalArgumentException(
                    "materialized source does not match the recoverable source plan");
        }
    }

    private static void requireRecordedSource(
            PreparedSourceArtifact prepared, LocalFileRecord recorded) {
        if (recorded.sizeBytes() != prepared.startRequest().totalBytes()
                || !recorded.sha256().equals(prepared.sealRequest().bytesDigest())) {
            throw new IllegalArgumentException(
                    "recorded upload file differs from the materialized source");
        }
    }

    private Instant now(Instant notBefore) {
        Instant value = Instant.ofEpochMilli(clock.instant().toEpochMilli());
        return value.isBefore(notBefore) ? notBefore : value;
    }

    private static String sourceSuffix(SourceInputPlan.SourceArtifactPlan artifact) {
        if (artifact instanceof SourceInputPlan.OriginalArtifactPlan) {
            return ".zip";
        }
        return ((SourceInputPlan.DerivedArtifactPlan) artifact).container()
                        == SourceArtifactCodec.SourceContainer.PNG
                ? ".png"
                : ".ome.tif";
    }

    private Consumer<SourceTransferProgress> progress(LargeActionHandle handle) {
        return transfer ->
                sourceProgress.accept(
                        new ImageSourceProgress(handle.actionId(), handle.imageId(), transfer));
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (Objects.requireNonNull(shouldStop, "shouldStop").getAsBoolean()) {
            throw new CancellationException("recoverable source publication cancelled");
        }
    }
}
