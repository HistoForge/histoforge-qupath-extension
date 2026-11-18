package qupath.ext.histoforge.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Objects;
import java.util.Optional;
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
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec.SourceArtifactSealRequest;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec.IntRect;
import qupath.ext.histoforge.large.LargeActionHandleStore;
import qupath.ext.histoforge.large.LargeActionTypes.LocalFileRecord;
import qupath.ext.histoforge.large.LargeActionTypes.SourceUploadRef;
import qupath.ext.histoforge.large.RecoverySourceFiles;
import qupath.ext.histoforge.large.RunImageSources;
import qupath.ext.histoforge.modules.ModuleSnapshot.ModelSnapshot;
import qupath.ext.histoforge.spatial.SourceArtifactPlanning;
import qupath.ext.histoforge.spatial.SourceArtifactWriter;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;
import qupath.ext.histoforge.spatial.SourcePlanningInputs.SourcePlanningLimits;

/**
 * Prepares and uploads one bounded image at a time, retaining its exact bytes until binding. The
 * caller owns the working image and run journal. This object owns only the preparation slot;
 * closing it releases the slot, not unacknowledged files needed by a later recovery attempt.
 */
final class RecoverableImageUploads implements AutoCloseable {
    private final LargeActionHandleStore handles;
    private final ModelGatewayExecutor gateway;
    private final SourceArtifactWriter writer;
    private final ImageLifecycle.WorkingImage working;
    private final SourcePlanningLimits limits;
    private final PreparedImageBudget budget;
    private final Consumer<ImageSourceProgress> progress;
    private final Supplier<String> artifactIds;
    private PreparedImageBudget.Reservation reservation;

    RecoverableImageUploads(
            LargeActionHandleStore handles,
            ModelGatewayExecutor gateway,
            SourceArtifactWriter writer,
            ImageLifecycle.WorkingImage working,
            SourcePlanningLimits limits,
            PreparedImageBudget budget,
            PreparedImageBudget.Reservation initial,
            Consumer<ImageSourceProgress> progress,
            Supplier<String> artifactIds) {
        this.handles = Objects.requireNonNull(handles);
        this.gateway = Objects.requireNonNull(gateway);
        this.writer = Objects.requireNonNull(writer);
        this.working = Objects.requireNonNull(working);
        this.limits = Objects.requireNonNull(limits);
        this.budget = Objects.requireNonNull(budget);
        this.reservation = initial;
        this.progress = Objects.requireNonNull(progress);
        this.artifactIds = Objects.requireNonNull(artifactIds);
    }

    RunImageSources.Upload provide(
            DurableStepJournal journal,
            int shardIndex,
            String stepId,
            int inputId,
            int unitId,
            SourceCaptureCodec.Capture capture,
            IntRect required,
            ModelSnapshot model,
            BooleanSupplier stop)
            throws IOException, InterruptedException, InferenceException {
        requireRunning(stop);
        var retained =
                journal.run(stepId, shardIndex).imageSources().orElseThrow().uploads().stream()
                        .filter(value -> value.inputId() == inputId && value.unitId() == unitId)
                        .findFirst();
        if (retained.isPresent()) {
            var upload = retained.orElseThrow();
            if (upload.source().sealedArtifact().isPresent()) return upload;
            var path = handles.requireFile(upload.source().ownedUploadFile().orElseThrow());
            budget.register(handles);
            reservation = budget.resume(path);
            try {
                var sealed =
                        gateway.resumeSource(
                                path,
                                handles.requireFile(upload.source().startRequestFile()),
                                transferProgress(journal));
                return retainSealed(journal, stepId, shardIndex, upload, sealed.artifact());
            } catch (InferenceException failure) {
                if (!unavailable(failure)) throw failure;
                return renew(journal, stepId, shardIndex, upload, stop);
            }
        }

        // A prior input/model may already have uploaded all required pixels. Reuse that exact
        // sealed file, without changing its sampling origin or assembling pieces of other files.
        for (var source : RunImageSources.sources(journal.currentAction().handle()).toList()) {
            if (source.sealedArtifact().isEmpty()
                    || !model.capabilities()
                            .sourceContainers()
                            .contains(source.sealedArtifact().orElseThrow().container())) continue;
            var start = start(source);
            if (!(start.origin() instanceof SourceArtifactCodec.DerivedRegion origin)) continue;
            if (!origin.equals(SourceCaptureCodec.imageOrigin(capture, origin.sourceRectAtLevel())))
                continue;
            var rectangle = origin.sourceRectAtLevel();
            if (!contains(rectangle, required)) continue;
            var upload = new RunImageSources.Upload(inputId, unitId, source, false);
            journal.recordImage(stepId, shardIndex, upload);
            return upload;
        }

        return prepare(
                journal, shardIndex, stepId, inputId, unitId, capture, required, model, stop);
    }

    private RunImageSources.Upload prepare(
            DurableStepJournal journal,
            int shardIndex,
            String stepId,
            int inputId,
            int unitId,
            SourceCaptureCodec.Capture capture,
            IntRect required,
            ModelSnapshot model,
            BooleanSupplier stop)
            throws IOException, InterruptedException, InferenceException {
        // An interrupted encoder may have left an unreferenced staging file. The handle store
        // removes only such leftovers, never another retained upload or an original slide.
        handles.discardInterruptedSourceImages(journal.currentAction());
        budget.register(handles);
        // The fresh action reserved its first slot before creating the handle. Do not acquire
        // another slot while holding it: two concurrent actions would then wait for each other.
        if (reservation == null) reservation = budget.acquire(stop);
        var plan =
                SourceArtifactPlanning.capturedImage(
                        capture, required, model.capabilities().sourceContainers(), limits);
        var handle = journal.currentAction().handle();
        String id = CanonicalCodec.requireCanonicalUuid(artifactIds.get(), "artifact_id");
        String relative =
                RecoverySourceFiles.directory(handle)
                        + "/"
                        + id
                        + (plan.container() == SourceArtifactCodec.SourceContainer.PNG
                                ? ".png"
                                : ".ome.tif");
        var path = handles.prepareFilePath(relative);
        reservation.bind(path);
        transferProgress(journal).accept(SourceTransferProgress.preparing());
        var prepared =
                writer.materializeCapturedImage(
                        capture,
                        plan,
                        working.imageData().getServer(),
                        path,
                        handle.actionId(),
                        id,
                        working.sourceRevisionVerifier());
        var file = handles.recordExistingFile(relative);
        if (file.sizeBytes() != prepared.startRequest().totalBytes()
                || !file.sha256().equals(prepared.sealRequest().bytesDigest()))
            throw new IOException("Prepared image differs from the recorded upload bytes");
        var source =
                new SourceUploadRef(
                        id,
                        recordStart(journal, prepared.startRequest()),
                        Optional.of(file),
                        Optional.empty());
        var upload = new RunImageSources.Upload(inputId, unitId, source, false);
        journal.recordImage(stepId, shardIndex, upload);
        requireRunning(stop);
        var sealed =
                gateway.publishSource(
                        prepared,
                        handles.requireFile(source.startRequestFile()),
                        transferProgress(journal));
        return retainSealed(journal, stepId, shardIndex, upload, sealed.artifact());
    }

    /**
     * Only an unbound upload may receive a new ID. Reuse retained bytes; if a previously shared
     * file's local copy was already released, read the same rectangle from the captured source.
     */
    RunImageSources.Upload renew(
            DurableStepJournal journal,
            String stepId,
            int shardIndex,
            RunImageSources.Upload upload,
            BooleanSupplier stop)
            throws IOException, InterruptedException, InferenceException {
        if (upload.bindingAccepted())
            throw new IllegalStateException("An accepted image cannot be replaced");
        requireRunning(stop);
        var source = upload.source();
        var file = source.ownedUploadFile().orElseThrow();
        if (Files.notExists(
                handles.root().resolve(file.projectRelativePosixPath()),
                LinkOption.NOFOLLOW_LINKS)) {
            var capture =
                    ((RecipeExecutor.CapturedSource)
                                    RecoverableSourceProvisioner.retainedSource(
                                            journal.currentAction().handle()))
                            .capture();
            var origin = (SourceArtifactCodec.DerivedRegion) start(source).origin();
            return prepare(
                    journal,
                    shardIndex,
                    stepId,
                    upload.inputId(),
                    upload.unitId(),
                    capture,
                    origin.sourceRectAtLevel(),
                    journal.currentAction().handle().module().activatedModels().get(stepId),
                    stop);
        }
        var path = handles.requireFile(file);
        budget.register(handles);
        if (reservation == null) reservation = budget.resume(path);
        var previous = start(source);
        var next =
                new SourceArtifactCodec.SourceArtifactStartRequest(
                        previous.schemaVersion(),
                        previous.creationActionId(),
                        artifactIds.get(),
                        previous.container(),
                        previous.totalBytes(),
                        previous.chunkSizeBytes(),
                        previous.origin(),
                        previous.requiredLevels());
        var renewed =
                new SourceUploadRef(
                        next.artifactId(),
                        recordStart(journal, next),
                        Optional.of(file),
                        Optional.empty());
        var replacement =
                new RunImageSources.Upload(upload.inputId(), upload.unitId(), renewed, false);
        journal.recordImage(stepId, shardIndex, replacement);
        var sealed =
                gateway.publishSource(
                        new PreparedSourceArtifact(
                                path, next, new SourceArtifactSealRequest(file.sha256())),
                        handles.requireFile(renewed.startRequestFile()),
                        transferProgress(journal));
        return retainSealed(journal, stepId, shardIndex, replacement, sealed.artifact());
    }

    /** Local bytes are disposable only after both cloud acceptance and its local record. */
    void discardAccepted(DurableStepJournal journal) throws IOException {
        handles.discardBoundSourceImages(journal.currentAction());
        close();
    }

    static SourceCaptureCodec.Binding binding(
            LargeActionHandleStore handles,
            SourceCaptureCodec.Capture capture,
            SourceUploadRef source)
            throws IOException {
        var start =
                SourceArtifactCodec.parseStartRequest(
                        CanonicalCodec.parseJson(
                                Files.readAllBytes(
                                        handles.requireFile(source.startRequestFile()))));
        var origin = (SourceArtifactCodec.DerivedRegion) start.origin();
        var binding =
                new SourceCaptureCodec.Binding(
                        capture.captureDigest(),
                        source.sealedArtifact().orElseThrow(),
                        origin.sourceRectAtLevel());
        binding.requireCapture(capture);
        return binding;
    }

    static boolean unavailable(InferenceException failure) {
        return failure.gatewayError()
                .map(GatewayErrorCodec.Error::code)
                .filter(
                        code ->
                                code == GatewayErrorCodec.Code.ARTIFACT_NOT_FOUND
                                        || code == GatewayErrorCodec.Code.ARTIFACT_EXPIRED
                                        || code
                                                == GatewayErrorCodec.Code
                                                        .ARTIFACT_PRECONDITION_FAILED)
                .isPresent();
    }

    private SourceArtifactCodec.SourceArtifactStartRequest start(SourceUploadRef source)
            throws IOException {
        return SourceArtifactCodec.parseStartRequest(
                CanonicalCodec.parseJson(
                        Files.readAllBytes(handles.requireFile(source.startRequestFile()))));
    }

    private LocalFileRecord recordStart(
            DurableStepJournal journal, SourceArtifactCodec.SourceArtifactStartRequest start)
            throws IOException {
        return handles.publishImmutable(
                RecoverySourceFiles.directory(journal.currentAction().handle())
                        + "/"
                        + start.artifactId()
                        + ".start.json",
                CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(start)));
    }

    private RunImageSources.Upload retainSealed(
            DurableStepJournal journal,
            String stepId,
            int shardIndex,
            RunImageSources.Upload upload,
            SourceArtifactRef sealed)
            throws IOException {
        var prior = upload.source();
        var next =
                new RunImageSources.Upload(
                        upload.inputId(),
                        upload.unitId(),
                        new SourceUploadRef(
                                prior.artifactId(),
                                prior.startRequestFile(),
                                prior.ownedUploadFile(),
                                Optional.of(sealed)),
                        false);
        journal.recordImage(stepId, shardIndex, next);
        return next;
    }

    private Consumer<SourceTransferProgress> transferProgress(DurableStepJournal journal) {
        var handle = journal.currentAction().handle();
        return transfer ->
                progress.accept(
                        new ImageSourceProgress(handle.actionId(), handle.imageId(), transfer));
    }

    private static boolean contains(IntRect outer, IntRect inner) {
        return outer.x() <= inner.x()
                && outer.y() <= inner.y()
                && (long) outer.x() + outer.width() >= (long) inner.x() + inner.width()
                && (long) outer.y() + outer.height() >= (long) inner.y() + inner.height();
    }

    static void requireRunning(BooleanSupplier stop) throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Image delivery interrupted");
        if (stop.getAsBoolean()) throw new CancellationException("Image delivery cancelled");
    }

    @Override
    public void close() {
        if (reservation != null) reservation.close();
        reservation = null;
    }
}
