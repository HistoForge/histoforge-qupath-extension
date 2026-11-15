package qupath.ext.histoforge.client;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.FiniteInferenceCodec.FiniteInferenceRequest;
import qupath.ext.histoforge.contract.FiniteInferenceCodec.InferenceResult;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec;
import qupath.ext.histoforge.contract.GatewayCapabilitiesCodec.GatewayCapabilities;
import qupath.ext.histoforge.contract.RunnerCapabilitiesCodec.RunnerCapabilities;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec.ChunkRange;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactAccepted;
import qupath.ext.histoforge.contract.SourceArtifactCodec.SourceArtifactStartRequest;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactRef;
import qupath.ext.histoforge.contract.SourceArtifactPublicationCodec.SourceArtifactSealed;
import qupath.ext.histoforge.contract.SourceArtifactStatusCodec;
import qupath.ext.histoforge.contract.SourceArtifactStatusCodec.SourceArtifactStatus;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec.SourceArtifactSealRequest;
import qupath.ext.histoforge.contract.SourceArtifactTransferCodec.SourceByteChunkCommit;
import qupath.ext.histoforge.spatial.SourceArtifactWriter.PreparedSourceArtifact;

/**
 * Owns model-source publication and bounded finite calls over one captured gateway deployment.
 *
 * <p>The writer owns image capture; this owner durably publishes the exact canonical start request,
 * transfers only server-reported missing ranges with bounded concurrency, resolves an ambiguous
 * synchronous seal through status, and returns the immutable sealed reference. It does not own
 * source selection, recipe orchestration, large-action state, or final file retention.
 */
public final class ModelGatewayExecutor implements AutoCloseable {

    /** Prepared source bytes no longer match the immutable artifact being published. */
    public static final class PreparedSourceCorruptException extends IOException {

        public PreparedSourceCorruptException(String message) {
            super(message);
        }
    }

    static final int MAX_CONCURRENT_SOURCE_CHUNKS = 5;
    static final Duration CHUNK_HTTP_TIMEOUT = Duration.ofSeconds(300);
    static final Duration SEAL_HTTP_TIMEOUT = Duration.ofSeconds(330);
    static final Duration RELEASE_HTTP_TIMEOUT = Duration.ofSeconds(330);
    private static final Duration FINITE_POLL_INTERVAL = Duration.ofSeconds(2);

    private final GatewayTransport transport;
    private final SourceArtifactGatewayClient sourceGateway;
    private final FiniteCallExecutor finiteCalls;
    private final GatewayCapabilities capabilities;
    private final BackendAdmission admission;
    private final ExecutorService sourceTransfers;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Bind source publication to one exact authenticated deployment capability capture. */
    public ModelGatewayExecutor(GatewayTransport transport, GatewayCapabilities capabilities) {
        this(transport, capabilities, MAX_CONCURRENT_SOURCE_CHUNKS);
    }

    /** Test seam for a smaller bounded transfer pool. */
    ModelGatewayExecutor(
            GatewayTransport transport,
            GatewayCapabilities capabilities,
            int maxConcurrentSourceChunks) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.admission = new BackendAdmission(transport, capabilities);
        if (!GatewayCapabilitiesCodec.supports(capabilities)) {
            throw new IllegalArgumentException("gateway capabilities are unsupported");
        }
        int concurrency =
                CanonicalCodec.requirePositiveInt32(
                        maxConcurrentSourceChunks, "max_concurrent_source_chunks");
        if (concurrency > MAX_CONCURRENT_SOURCE_CHUNKS) {
            throw new IllegalArgumentException(
                    "source upload concurrency exceeds the client build limit");
        }
        sourceGateway =
                new SourceArtifactGatewayClient(
                        transport,
                        capabilities.deploymentId(),
                        CHUNK_HTTP_TIMEOUT,
                        SEAL_HTTP_TIMEOUT,
                        RELEASE_HTTP_TIMEOUT);
        finiteCalls = new FiniteCallExecutor(transport, capabilities, admission);
        sourceTransfers =
                Executors.newFixedThreadPool(
                        concurrency,
                        Thread.ofVirtual().name("histoforge-source-upload-", 0).factory());
    }

    /**
     * Publish a newly prepared file after forcing its exact canonical start request locally.
     *
     * @param prepared exact bytes and requests returned by {@code SourceArtifactWriter}
     * @param startRequestFile caller-owned durable path retained until the action resolves
     */
    public SourceArtifactSealed publishSource(
            PreparedSourceArtifact prepared, Path startRequestFile)
            throws IOException, InterruptedException, InferenceException {
        return publishSource(prepared, startRequestFile, ignored -> {});
    }

    /** Publish with serialized progress callbacks on the calling thread, never upload workers. */
    public SourceArtifactSealed publishSource(
            PreparedSourceArtifact prepared,
            Path startRequestFile,
            Consumer<SourceTransferProgress> progress)
            throws IOException, InterruptedException, InferenceException {
        requireOpen();
        Objects.requireNonNull(progress, "progress").accept(SourceTransferProgress.preparing());
        PreparedSourceArtifact source = Objects.requireNonNull(prepared, "prepared");
        byte[] canonicalStart =
                CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(source.startRequest()));
        validateStartLimits(source.startRequest(), canonicalStart.length);
        SourceArtifactLocalFiles.publishCanonicalStart(startRequestFile, canonicalStart);
        SourceArtifactLocalFiles.requireExactSourceBytes(source);
        admission.require();
        return publishPrepared(source, progress);
    }

    /**
     * Resume from the exact forced request file and current source bytes after process loss.
     *
     * <p>A missing, noncanonical, or corrupt request fails before any network call. The whole-file
     * digest is reconstructed from the retained derived file or reopened original source. Recovery
     * reads status before mutation; an expired or absent record is not recreated under its old ID.
     */
    public SourceArtifactSealed resumeSource(Path sourceFile, Path startRequestFile)
            throws IOException, InterruptedException, InferenceException {
        return resumeSource(sourceFile, startRequestFile, ignored -> {});
    }

    /** Resume with byte counts reconstructed from gateway status, not local progress state. */
    public SourceArtifactSealed resumeSource(
            Path sourceFile, Path startRequestFile, Consumer<SourceTransferProgress> progress)
            throws IOException, InterruptedException, InferenceException {
        requireOpen();
        Objects.requireNonNull(progress, "progress").accept(SourceTransferProgress.preparing());
        long requestLimit =
                Math.min(
                        capabilities.sourceArtifactLimits().maxStartCanonicalBytes(),
                        capabilities.limits().maxJsonRequestBytes());
        SourceArtifactStartRequest start =
                SourceArtifactLocalFiles.readCanonicalStart(startRequestFile, requestLimit);
        validateStartLimits(
                start, CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(start)).length);
        Path source = SourceArtifactLocalFiles.normalized(sourceFile, "sourceFile");
        SourceArtifactSealRequest seal =
                new SourceArtifactSealRequest(SourceArtifactLocalFiles.sha256Stable(source, start));
        admission.require();
        return resumePrepared(new PreparedSourceArtifact(source, start, seal), progress);
    }

    /**
     * Check a previously sealed upload using its saved identity, without reading local pixels or
     * uploading again. The caller must load the start request from its validated recovery record.
     */
    public void verifySealedSource(
            SourceArtifactStartRequest start,
            SourceArtifactRef expected,
            Consumer<SourceTransferProgress> progress)
            throws IOException, InterruptedException, InferenceException {
        requireOpen();
        if (!expected.artifactId().equals(start.artifactId())
                || !expected.creationActionId().equals(start.creationActionId())
                || !expected.startRequestDigest()
                        .equals(SourceArtifactCodec.startRequestDigest(start))
                || expected.container() != start.container()) {
            throw new IllegalArgumentException(
                    "saved source identity differs from its start request");
        }
        requireDeployment();
        progress.accept(
                new SourceTransferProgress(
                        SourceTransferProgress.Phase.VERIFYING,
                        start.totalBytes(),
                        start.totalBytes()));
        var recovered =
                sourceGateway.recoverStatus(
                        start, new SourceArtifactSealRequest(expected.bytesDigest()));
        if (recovered.status().phase() != SourceArtifactStatusCodec.Phase.SEALED
                || !recovered.status().sealedArtifact().orElseThrow().equals(expected)) {
            throw new InferenceException("Cloud source no longer matches the saved sealed upload");
        }
        requireDeployment();
        progress.accept(
                new SourceTransferProgress(
                        SourceTransferProgress.Phase.READY,
                        start.totalBytes(),
                        start.totalBytes()));
    }

    /** Idempotently release one source reservation after all client consumers finish. */
    public void releaseSource(String artifactId)
            throws IOException, InterruptedException, InferenceException {
        requireOpen();
        requireDeployment();
        sourceGateway.release(artifactId);
    }

    /**
     * Submit one already sealed, finite ROI input without replay after transport ambiguity.
     *
     * <p>An ambiguous POST failure is returned to the caller and is never replayed here. The
     * enclosing action must abandon that output generation before creating a fresh one.
     */
    public FiniteCallHandle submitFinite(
            FiniteInferenceRequest request,
            RunnerCapabilities runnerCapabilities,
            Collection<String> declaredClasses)
            throws IOException, InterruptedException, InferenceException {
        requireOpen();
        return finiteCalls.submit(request, runnerCapabilities, declaredClasses);
    }

    /** Validated result and exact backend call identity for one completed finite input. */
    public record CompletedFiniteCall(
            InferenceResult result, BackendExecutionCodec.FiniteCall execution) {
        public CompletedFiniteCall {
            result = Objects.requireNonNull(result, "result");
            execution = Objects.requireNonNull(execution, "execution");
        }
    }

    /** Failure after the gateway accepted a finite call, retaining its exact call identity. */
    public static final class AcceptedFiniteCallFailure extends Exception {

        private final BackendExecutionCodec.FiniteCall execution;
        private final Exception originalFailure;

        public AcceptedFiniteCallFailure(
                BackendExecutionCodec.FiniteCall execution, Exception originalFailure) {
            super(Objects.requireNonNull(originalFailure, "originalFailure"));
            this.execution = Objects.requireNonNull(execution, "execution");
            this.originalFailure = originalFailure;
        }

        public BackendExecutionCodec.FiniteCall execution() {
            return execution;
        }

        public Exception originalFailure() {
            return originalFailure;
        }
    }

    /** Cancellation after finite-call acceptance, retaining the abandoned call identity. */
    public static final class AcceptedFiniteCallCancellation extends CancellationException {

        private final BackendExecutionCodec.FiniteCall execution;

        public AcceptedFiniteCallCancellation(
                BackendExecutionCodec.FiniteCall execution, CancellationException cause) {
            super(cause.getMessage());
            this.execution = Objects.requireNonNull(execution, "execution");
            initCause(Objects.requireNonNull(cause, "cause"));
            for (Throwable suppressed : cause.getSuppressed()) {
                addSuppressed(suppressed);
            }
        }

        public BackendExecutionCodec.FiniteCall execution() {
            return execution;
        }
    }

    /**
     * Submit and poll one finite input until completion or explicit client cancellation.
     *
     * <p>Polling is safely repeatable. Cancellation is best effort: failure to deliver the cancel
     * request is attached to the cancellation, while the abandoned output generation remains the
     * correctness fence for any late result.
     */
    public CompletedFiniteCall executeFinite(
            FiniteInferenceRequest request,
            RunnerCapabilities runnerCapabilities,
            Collection<String> declaredClasses,
            BooleanSupplier shouldStop)
            throws IOException,
                    InterruptedException,
                    InferenceException,
                    AcceptedFiniteCallFailure {
        requireOpen();
        BooleanSupplier stop = Objects.requireNonNull(shouldStop, "shouldStop");
        if (stop.getAsBoolean()) {
            throw new CancellationException("finite inference cancelled before submission");
        }
        FiniteCallHandle handle = submitFinite(request, runnerCapabilities, declaredClasses);
        try {
            while (true) {
                if (stop.getAsBoolean()) {
                    cancelAndThrow(handle);
                }
                Optional<InferenceResult> result = pollFinite(handle);
                if (result.isPresent()) {
                    if (stop.getAsBoolean()) {
                        cancelAndThrow(handle);
                    }
                    return new CompletedFiniteCall(
                            result.orElseThrow(), handle.executionReference());
                }
                if (stop.getAsBoolean()) {
                    cancelAndThrow(handle);
                }
                Thread.sleep(FINITE_POLL_INTERVAL);
            }
        } catch (AcceptedFiniteCallCancellation cancellation) {
            throw cancellation;
        } catch (CancellationException cancellation) {
            throw new AcceptedFiniteCallCancellation(handle.executionReference(), cancellation);
        } catch (InterruptedException | IOException | InferenceException failure) {
            if (failure instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                // Java IO may preserve the interrupt flag while throwing IOException. Clear it
                // for the same best-effort cancellation, then restore it for the caller.
                Thread.interrupted();
                CancellationException cancelled =
                        new CancellationException("finite inference interrupted after acceptance");
                cancelled.initCause(failure);
                try {
                    cancelFinite(handle);
                } catch (IOException | InterruptedException | InferenceException cancellation) {
                    cancelled.addSuppressed(cancellation);
                } finally {
                    Thread.currentThread().interrupt();
                }
                throw new AcceptedFiniteCallCancellation(handle.executionReference(), cancelled);
            }
            throw new AcceptedFiniteCallFailure(handle.executionReference(), failure);
        }
    }

    /** Poll once; an empty result is a validated nonterminal response and is safely retryable. */
    public Optional<InferenceResult> pollFinite(FiniteCallHandle handle)
            throws IOException, InterruptedException, InferenceException {
        requireOpen();
        return finiteCalls.poll(handle);
    }

    /** Request idempotent best-effort cancellation of one accepted finite call. */
    public void cancelFinite(FiniteCallHandle handle)
            throws IOException, InterruptedException, InferenceException {
        requireOpen();
        finiteCalls.cancel(handle);
    }

    private void cancelAndThrow(FiniteCallHandle handle) {
        CancellationException cancelled = new CancellationException("finite inference cancelled");
        try {
            cancelFinite(handle);
        } catch (IOException | InterruptedException | InferenceException failure) {
            cancelled.addSuppressed(failure);
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AcceptedFiniteCallCancellation(handle.executionReference(), cancelled);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            sourceTransfers.shutdownNow();
        }
    }

    private SourceArtifactSealed publishPrepared(
            PreparedSourceArtifact source, Consumer<SourceTransferProgress> progress)
            throws IOException, InterruptedException, InferenceException {
        requireDeployment();
        SourceArtifactStartRequest start = source.startRequest();
        byte[] canonicalStart = CanonicalCodec.canonicalBytes(SourceArtifactCodec.toJson(start));
        validateStartLimits(start, canonicalStart.length);
        SourceArtifactLocalFiles.requireLength(source.path(), start);

        SourceArtifactAccepted accepted = reserveWithReplay(start, canonicalStart);
        SourceArtifactStatus status = sourceGateway.status(start, accepted, source.sealRequest());
        return continuePublication(source, accepted, status, progress);
    }

    private SourceArtifactSealed resumePrepared(
            PreparedSourceArtifact source, Consumer<SourceTransferProgress> progress)
            throws IOException, InterruptedException, InferenceException {
        requireDeployment();
        var recovered = sourceGateway.recoverStatus(source.startRequest(), source.sealRequest());
        return continuePublication(source, recovered.accepted(), recovered.status(), progress);
    }

    private SourceArtifactSealed continuePublication(
            PreparedSourceArtifact source,
            SourceArtifactAccepted accepted,
            SourceArtifactStatus status,
            Consumer<SourceTransferProgress> progress)
            throws IOException, InterruptedException, InferenceException {
        SourceArtifactStartRequest start = source.startRequest();
        boolean alreadySealed = status.phase() == SourceArtifactStatusCodec.Phase.SEALED;
        if (!alreadySealed) {
            uploadMissing(source.path(), start, status.missingChunks(), progress);
        }
        progress.accept(
                new SourceTransferProgress(
                        SourceTransferProgress.Phase.VERIFYING,
                        start.totalBytes(),
                        start.totalBytes()));
        SourceArtifactSealed sealed;
        if (alreadySealed) {
            sealed = sourceGateway.sealedFromStatus(start, accepted, source.sealRequest(), status);
        } else {
            SourceArtifactLocalFiles.requireExactSourceBytes(source);
            sealed = sealWithRecovery(start, accepted, source.sealRequest());
        }
        requireDeployment();
        progress.accept(
                new SourceTransferProgress(
                        SourceTransferProgress.Phase.READY,
                        start.totalBytes(),
                        start.totalBytes()));
        return sealed;
    }

    private SourceArtifactAccepted reserveWithReplay(
            SourceArtifactStartRequest start, byte[] canonicalStart)
            throws IOException, InterruptedException, InferenceException {
        try {
            return sourceGateway.reserve(start, canonicalStart);
        } catch (IOException first) {
            try {
                return sourceGateway.reserve(start, canonicalStart);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private void uploadMissing(
            Path source,
            SourceArtifactStartRequest start,
            List<Integer> missing,
            Consumer<SourceTransferProgress> progress)
            throws IOException, InterruptedException, InferenceException {
        long acknowledgedBytes = start.totalBytes();
        for (int chunkId : missing) {
            acknowledgedBytes -=
                    SourceArtifactCodec.chunkRange(
                                    start.totalBytes(), start.chunkSizeBytes(), chunkId)
                            .size();
        }
        progress.accept(
                new SourceTransferProgress(
                        SourceTransferProgress.Phase.UPLOADING,
                        acknowledgedBytes,
                        start.totalBytes()));
        var completions = new ExecutorCompletionService<Integer>(sourceTransfers);
        List<Future<Integer>> transfers = new ArrayList<>(missing.size());
        try {
            for (int chunkId : missing) {
                transfers.add(completions.submit(() -> putChunkWithReplay(source, start, chunkId)));
            }
            for (int completed = 0; completed < missing.size(); completed++) {
                acknowledgedBytes += completions.take().get();
                progress.accept(
                        new SourceTransferProgress(
                                SourceTransferProgress.Phase.UPLOADING,
                                acknowledgedBytes,
                                start.totalBytes()));
            }
        } catch (ExecutionException error) {
            rethrowTransferFailure(error.getCause());
        } finally {
            // Workers never invoke the callback. Cancelled calls may finish remotely, but
            // cannot report late progress after this publication returns or fails.
            cancel(transfers);
        }
    }

    private int putChunkWithReplay(Path source, SourceArtifactStartRequest start, int chunkId)
            throws IOException, InterruptedException, InferenceException {
        ChunkRange range =
                SourceArtifactCodec.chunkRange(start.totalBytes(), start.chunkSizeBytes(), chunkId);
        byte[] body = SourceArtifactLocalFiles.readRange(source, start, range);
        SourceByteChunkCommit expected =
                new SourceByteChunkCommit(chunkId, CanonicalCodec.sha256(body));
        try {
            sourceGateway.putChunk(start, expected, body);
        } catch (IOException first) {
            try {
                sourceGateway.putChunk(start, expected, body);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
        return range.size();
    }

    private SourceArtifactSealed sealWithRecovery(
            SourceArtifactStartRequest start,
            SourceArtifactAccepted accepted,
            SourceArtifactSealRequest seal)
            throws IOException, InterruptedException, InferenceException {
        IOException firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return sourceGateway.seal(start, accepted, seal);
            } catch (IOException error) {
                if (firstFailure == null) {
                    firstFailure = error;
                } else {
                    error.addSuppressed(firstFailure);
                    firstFailure = error;
                }
                SourceArtifactStatus status;
                try {
                    status = sourceGateway.status(start, accepted, seal);
                } catch (IOException statusFailure) {
                    statusFailure.addSuppressed(firstFailure);
                    throw statusFailure;
                }
                if (status.phase() == SourceArtifactStatusCodec.Phase.SEALED) {
                    return sourceGateway.sealedFromStatus(start, accepted, seal, status);
                }
            }
        }
        throw Objects.requireNonNull(firstFailure, "firstFailure");
    }

    private void validateStartLimits(SourceArtifactStartRequest start, int canonicalBytes) {
        var sourceLimits = capabilities.sourceArtifactLimits();
        var gatewayLimits = capabilities.limits();
        if (canonicalBytes > sourceLimits.maxStartCanonicalBytes()
                || canonicalBytes > gatewayLimits.maxJsonRequestBytes()
                || start.totalBytes() > sourceLimits.maxArtifactBytes()
                || start.chunkSizeBytes() > sourceLimits.maxChunkBytes()
                || SourceArtifactCodec.chunkCount(start.totalBytes(), start.chunkSizeBytes())
                        > sourceLimits.maxChunks()) {
            throw new IllegalArgumentException("source artifact exceeds captured gateway limits");
        }
        Map<Integer, Integer> levelsBySeries = new HashMap<>();
        for (SourceArtifactCodec.SourceLevelRequirement level : start.requiredLevels()) {
            levelsBySeries.merge(level.seriesIndex(), 1, Integer::sum);
            if (level.width() > sourceLimits.maxLevelWidthPx()
                    || level.height() > sourceLimits.maxLevelHeightPx()) {
                throw new IllegalArgumentException(
                        "source level dimensions exceed captured gateway limits");
            }
        }
        if (levelsBySeries.size() > sourceLimits.maxImageSeries()
                || levelsBySeries.values().stream()
                        .anyMatch(count -> count > sourceLimits.maxPyramidLevelsPerSeries())) {
            throw new IllegalArgumentException(
                    "source level requirements exceed captured gateway limits");
        }
        if (start.container() == SourceArtifactCodec.SourceContainer.PNG) {
            var level = start.requiredLevels().getFirst();
            if ((long) level.width() * level.height() > sourceLimits.maxCompleteDecodePixels()) {
                throw new IllegalArgumentException(
                        "PNG source exceeds captured complete-decode limit");
            }
        }
    }

    private void requireDeployment() throws IOException {
        if (!transport.matchesCurrentDeployment(capabilities.deploymentId())) {
            throw new GatewayTransport.DeploymentChangedException(
                    "deployment changed during model gateway action; restart action");
        }
    }

    private static void cancel(List<? extends Future<?>> transfers) {
        transfers.forEach(transfer -> transfer.cancel(true));
    }

    private static void rethrowTransferFailure(Throwable cause)
            throws IOException, InterruptedException, InferenceException {
        if (cause instanceof IOException error) {
            throw error;
        }
        if (cause instanceof InterruptedException error) {
            throw error;
        }
        if (cause instanceof InferenceException error) {
            throw error;
        }
        if (cause instanceof RuntimeException error) {
            throw error;
        }
        throw new IOException("source chunk transfer failed", cause);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("model gateway executor is closed");
        }
    }
}
