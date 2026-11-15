package qupath.ext.histoforge.large;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.BackendExecutionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ModelRunRequestCodec;
import qupath.ext.histoforge.contract.SourceArtifactCodec;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionHandle;
import qupath.ext.histoforge.large.LargeActionTypes.LargeActionPhase;
import qupath.ext.histoforge.large.LargeActionTypes.LocalFileRecord;
import qupath.ext.histoforge.pipeline.RunResults;

/** Sole atomic filesystem owner for project-scoped recoverable large-action state. */
public final class LargeActionHandleStore {

    private static final Logger logger = LoggerFactory.getLogger(LargeActionHandleStore.class);
    private static final int HASH_BUFFER_BYTES = 1024 * 1024;
    private static final String HANDLE_SUFFIX = ".json";

    private final Path root;
    private final Path handles;
    private final BuildIdentityCodec.ClientBuildIdentity runningBuild;
    private final Clock clock;
    private final AtomicPublisher publisher;
    private final Consumer<LargeActionHandle> onSaved;
    private final RecoveryResultFiles resultFiles;

    /** Deterministic loss or corruption that makes this handle generation impossible to resume. */
    public static final class UnrecoverableRecoveryStateException extends IOException {

        public UnrecoverableRecoveryStateException(String message) {
            super(message);
        }

        public UnrecoverableRecoveryStateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Readable recovery records disagree on the action, source, or input-plan identity. */
    public static final class RecoveryReferenceConflictException extends IOException {

        public RecoveryReferenceConflictException(String message) {
            super(message);
        }

        public RecoveryReferenceConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One canonical model input and the exact durable result allowed to realize it. */
    public record AuthorizedModelInput(
            ModelRunRequestCodec.ModelRunInput input,
            InferenceIdentityCodec.BackendModelIdentity backend,
            Optional<BackendExecutionCodec.DurableRun> execution) {
        public AuthorizedModelInput {
            input = Objects.requireNonNull(input, "input");
            backend = Objects.requireNonNull(backend, "backend");
            execution = Objects.requireNonNull(execution, "execution");
        }
    }

    /** One verified canonical request shard and its accepted terminal execution, if observed. */
    public record AuthorizedModelRun(
            String stepId,
            int shardIndex,
            ModelRunRequestCodec.ModelRunRequest request,
            Optional<BackendExecutionCodec.DurableRun> execution) {
        public AuthorizedModelRun {
            stepId = qupath.ext.histoforge.modules.ModuleContract.requireModelId(stepId, "step_id");
            shardIndex = CanonicalCodec.requireNonnegativeInt32(shardIndex, "shard_index");
            request = Objects.requireNonNull(request, "request");
            execution = Objects.requireNonNull(execution, "execution");
            if (!stepId.equals(request.producer().stepId())) {
                throw new IllegalArgumentException(
                        "authorized run step does not match its request producer");
            }
            if (execution.isPresent()
                    && !execution.orElseThrow().modelRunId().equals(request.modelRunId())) {
                throw new IllegalArgumentException(
                        "authorized execution does not match its model run");
            }
        }
    }

    /** Verified process-local handle plus its canonical run and generation authority. */
    public record ResumableAction(
            LargeActionHandle handle,
            Optional<SourceArtifactCodec.SourceArtifactStartRequest> sourceStartRequest,
            Map<String, AuthorizedModelInput> authorizedInputs,
            List<AuthorizedModelRun> authorizedRuns) {
        public ResumableAction {
            handle = Objects.requireNonNull(handle, "handle");
            sourceStartRequest = Objects.requireNonNull(sourceStartRequest, "sourceStartRequest");
            if (sourceStartRequest.isPresent() != handle.source().isPresent()) {
                throw new IllegalArgumentException(
                        "validated source request presence must match the retained handle");
            }
            Objects.requireNonNull(authorizedInputs, "authorizedInputs");
            Map<String, AuthorizedModelInput> copy = new LinkedHashMap<>();
            authorizedInputs.forEach(
                    (generationId, input) -> {
                        String key =
                                CanonicalCodec.requireCanonicalUuid(
                                        generationId, "output_generation_id");
                        AuthorizedModelInput value =
                                Objects.requireNonNull(input, "authorized input");
                        if (!key.equals(value.input().outputGenerationId())) {
                            throw new IllegalArgumentException(
                                    "authorized input key does not match its generation ID");
                        }
                        copy.put(key, value);
                    });
            authorizedInputs = Map.copyOf(copy);
            authorizedRuns = List.copyOf(Objects.requireNonNull(authorizedRuns, "authorizedRuns"));
            int position = 0;
            for (var step : handle.steps()) {
                for (var run : step.runs()) {
                    if (position >= authorizedRuns.size()) {
                        throw new IllegalArgumentException(
                                "authorized runs do not cover every retained request");
                    }
                    AuthorizedModelRun authorized = authorizedRuns.get(position++);
                    if (!authorized.stepId().equals(step.stepId())
                            || authorized.shardIndex() != run.shardIndex()
                            || !authorized.request().modelRunId().equals(run.modelRunId())) {
                        throw new IllegalArgumentException(
                                "authorized runs do not match retained handle order");
                    }
                }
            }
            if (position != authorizedRuns.size()) {
                throw new IllegalArgumentException(
                        "authorized runs contain requests absent from the handle");
            }
        }
    }

    /** Bind one store to a trusted project-scoped HistoForge recovery directory. */
    public LargeActionHandleStore(
            Path recoveryRoot, BuildIdentityCodec.ClientBuildIdentity runningBuild)
            throws IOException {
        this(recoveryRoot, runningBuild, ignored -> {});
    }

    /** Report saved transitions to the display without making UI success part of persistence. */
    public LargeActionHandleStore(
            Path recoveryRoot,
            BuildIdentityCodec.ClientBuildIdentity runningBuild,
            Consumer<LargeActionHandle> onSaved)
            throws IOException {
        this(
                recoveryRoot,
                runningBuild,
                Clock.systemUTC(),
                LargeActionHandleStore::publishAtomically,
                onSaved);
    }

    LargeActionHandleStore(
            Path recoveryRoot, BuildIdentityCodec.ClientBuildIdentity runningBuild, Clock clock)
            throws IOException {
        this(recoveryRoot, runningBuild, clock, LargeActionHandleStore::publishAtomically);
    }

    LargeActionHandleStore(
            Path recoveryRoot,
            BuildIdentityCodec.ClientBuildIdentity runningBuild,
            Clock clock,
            AtomicPublisher publisher)
            throws IOException {
        this(recoveryRoot, runningBuild, clock, publisher, ignored -> {});
    }

    private LargeActionHandleStore(
            Path recoveryRoot,
            BuildIdentityCodec.ClientBuildIdentity runningBuild,
            Clock clock,
            AtomicPublisher publisher,
            Consumer<LargeActionHandle> onSaved)
            throws IOException {
        this.runningBuild = Objects.requireNonNull(runningBuild, "runningBuild");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.onSaved = Objects.requireNonNull(onSaved, "onSaved");
        root = initializeRoot(recoveryRoot);
        handles = ensureDirectory(root.resolve("handles"));
        resultFiles = new RecoveryResultFiles(this);
    }

    /** Result-file publication shares this runtime's project-wide capacity boundary. */
    public RecoveryResultFiles resultFiles() {
        return resultFiles;
    }

    Optional<Path> findRegularFile(String relative) throws IOException {
        try {
            return Optional.of(resolveForRead(relative));
        } catch (IOException failure) {
            if (failure.getCause() instanceof NoSuchFileException) return Optional.empty();
            throw failure;
        }
    }

    /** Persist the initial PREPARING handle before any source encoding begins. */
    public LargeActionHandle create(LargeActionHandle handle) throws IOException {
        LargeActionHandle value = Objects.requireNonNull(handle, "handle");
        if (value.phase() != LargeActionPhase.PREPARING) {
            throw new IllegalArgumentException("new large-action handle must be PREPARING");
        }
        LargeActionHandleValidator.requireRunningBuild(value, runningBuild);
        Path file = handlePath(value.actionId(), value.handleGenerationId());
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(file.toString());
        }
        publisher.publish(file, validatedHandleBytes(value), false);
        notifySaved(value);
        return value;
    }

    /** Load one exact handle by its action and handle-generation identities. */
    public LargeActionHandle load(String actionId, String handleGenerationId) throws IOException {
        return read(handlePath(actionId, handleGenerationId));
    }

    /**
     * Current operational handles only; older records remain visible through {@link #scanRecords}.
     */
    public List<LargeActionHandle> list() throws IOException {
        return scanRecords().currentHandles();
    }

    /**
     * One read of the existing directory, separating execution authority from read-only history.
     */
    public RecordListing scanRecords() throws IOException {
        List<LargeActionHandle> result = new ArrayList<>();
        List<HistoricalActionRecord> historical = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(handles, "*" + HANDLE_SUFFIX)) {
            for (Path file : files) {
                // macOS stores external-drive metadata beside the real file as ._<filename>.
                // These companions are not action records and must never be decoded as handles.
                if (file.getFileName().toString().startsWith("._")) continue;
                if (Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("large-action handle directory contains invalid state");
                }
                byte[] bytes = readHandleBytes(file);
                try {
                    var object = LargeActionCodec.handleObject(CanonicalCodec.parseJson(bytes));
                    int version =
                            CanonicalCodec.requireInt32(
                                    object.get("schema_version"), "schema_version");
                    if (version == HistoricalActionRecord.SCHEMA_VERSION) {
                        if (!Arrays.equals(bytes, CanonicalCodec.canonicalBytes(object)))
                            throw new IllegalArgumentException(
                                    "historical action must be canonical JSON");
                        var record = HistoricalActionRecord.parse(object);
                        requireMatchingFilename(
                                file, record.actionId(), record.handleGenerationId());
                        historical.add(record);
                    } else if (version == LargeActionTypes.SCHEMA_VERSION) {
                        var handle = LargeActionCodec.decodeCanonical(bytes);
                        requireMatchingFilename(
                                file, handle.actionId(), handle.handleGenerationId());
                        result.add(handle);
                    } else {
                        throw new IllegalArgumentException(
                                "unsupported large-action schema_version: " + version);
                    }
                } catch (IllegalArgumentException error) {
                    throw unrecoverableHandle(
                            "large-action record is invalid or unsupported", error);
                }
            }
        }
        result.sort(
                java.util.Comparator.comparing(LargeActionHandle::createdAt)
                        .thenComparing(LargeActionHandle::actionId)
                        .thenComparing(LargeActionHandle::handleGenerationId));
        historical.sort(
                Comparator.comparing(HistoricalActionRecord::createdAt)
                        .thenComparing(HistoricalActionRecord::actionId)
                        .thenComparing(HistoricalActionRecord::handleGenerationId));
        return new RecordListing(result, historical);
    }

    public record RecordListing(
            List<LargeActionHandle> currentHandles,
            List<HistoricalActionRecord> historicalActions) {
        public RecordListing {
            currentHandles = List.copyOf(currentHandles);
            historicalActions = List.copyOf(historicalActions);
        }

        public boolean hasUnfinishedHistory(String imageId) {
            return historicalActions.stream()
                    .anyMatch(
                            record ->
                                    record.imageId().equals(imageId) && !record.phase().terminal());
        }

        /**
         * Historical records may be inspected, but no current stop/clear operation may own them.
         */
        public void requireMutableAction(String actionId, Optional<String> imageId) {
            if (historicalActions.stream()
                    .anyMatch(
                            record ->
                                    record.actionId().equals(actionId)
                                            && imageId.map(record.imageId()::equals).orElse(true)))
                throw new IllegalStateException(
                        "Historical analysis is read-only in this client build");
        }

        Set<String> protectedPaths() {
            Set<String> paths = new HashSet<>();
            for (var record : historicalActions) {
                String suffix = record.actionId() + "/" + record.handleGenerationId();
                paths.add("actions/" + suffix);
                paths.add("results/" + suffix);
                record.protectedFiles().forEach(file -> paths.add(file.projectRelativePosixPath()));
            }
            return paths;
        }
    }

    /** Atomically publish one valid transition from the current persisted handle. */
    public LargeActionHandle save(LargeActionHandle next) throws IOException {
        LargeActionHandle value = Objects.requireNonNull(next, "next");
        Path file = handlePath(value.actionId(), value.handleGenerationId());
        LargeActionHandle current = read(file);
        LargeActionHandleValidator.requireRunningBuild(current, runningBuild);
        return saveTransition(file, current, value);
    }

    private LargeActionHandle saveTransition(
            Path file, LargeActionHandle current, LargeActionHandle value) throws IOException {
        LargeActionHandleValidator.requireSameHandle(current, value);
        LargeActionHandleValidator.requireTransition(current, value);
        if (!value.phase().terminal()) {
            LargeActionHandleValidator.requireRequestPlanContinuity(
                    current, value, this::readRecorded);
            var existingImages =
                    RunImageSources.sources(current)
                            .flatMap(source -> source.ownedUploadFile().stream())
                            .collect(java.util.stream.Collectors.toSet());
            for (var image :
                    RunImageSources.sources(value)
                            .flatMap(source -> source.ownedUploadFile().stream())
                            .distinct()
                            .toList()) {
                if (!existingImages.contains(image)) verifyRecorded(image);
            }
            LargeActionHandleValidator.validateRecoveryReferences(value, this::readRecorded);
        }
        publisher.publish(file, validatedHandleBytes(value), true);
        notifySaved(value);
        return value;
    }

    private void notifySaved(LargeActionHandle handle) {
        try {
            onSaved.accept(handle);
        } catch (RuntimeException failure) {
            logger.warn("Could not display saved inference progress", failure);
        }
    }

    /**
     * Read and validate the retained control requests for status inspection. Unlike resuming,
     * inspection does not require the original plugin build or read the exported image pixels.
     */
    public List<AuthorizedModelRun> inspectModelRuns(LargeActionHandle handle) throws IOException {
        return LargeActionHandleValidator.validateRecoveryReferences(
                        Objects.requireNonNull(handle, "handle"), this::readRecorded)
                .runs();
    }

    /** Inspect local availability without reading large feature streams or contacting the cloud. */
    public Set<String> locallyAvailableRuns(LargeActionHandle handle) throws IOException {
        if (handle.phase().hasCompletedSave()) return Set.of();
        Map<String, LargeActionTypes.ModelRunRef> recorded = new LinkedHashMap<>();
        handle.steps()
                .forEach(step -> step.runs().forEach(run -> recorded.put(run.modelRunId(), run)));
        Set<String> available = new HashSet<>();
        for (var run : recorded.values()) {
            if (run.completeResultFiles().isEmpty()) continue;
            var files = run.completeResultFiles().orElseThrow();
            if (!resultFiles.appearsAvailable(files.manifestFile())) continue;
            boolean complete = true;
            for (var file : files.featureFiles()) {
                if (!resultFiles.appearsAvailable(file)) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                var request =
                        ModelRunRequestCodec.parseRequest(
                                CanonicalCodec.parseJson(readRecorded(run.requestFile())));
                if (files.featureFiles().size() == request.inputs().size()) {
                    available.add(run.modelRunId());
                }
            }
        }
        return Set.copyOf(available);
    }

    /** Require this exact running build before exposing a resumable operational handle. */
    public LargeActionHandle requireResumeCandidate(String actionId, String handleGenerationId)
            throws IOException {
        LargeActionHandle handle = load(actionId, handleGenerationId);
        if (!handle.resumableBy(runningBuild)) {
            throw new IllegalStateException(
                    handle.phase().terminal()
                            ? "terminal large-action handle cannot resume"
                            : "unfinished large action requires its exact client build");
        }
        return handle;
    }

    /**
     * Validate canonical recovery authority. Exported pixels are checked separately before upload;
     * replaying a complete local result does not require its old disposable source image.
     */
    public ResumableAction requireResumable(String actionId, String handleGenerationId)
            throws IOException {
        LargeActionHandle handle = requireResumeCandidate(actionId, handleGenerationId);
        LargeActionHandleValidator.ValidatedRecoveryReferences references =
                LargeActionHandleValidator.validateRecoveryReferences(handle, this::readRecorded);
        return new ResumableAction(
                handle, references.sourceStartRequest(), references.inputs(), references.runs());
    }

    /**
     * Require complete terminal model references before final hierarchy reconstruction may begin.
     */
    public ResumableAction requireCommitReady(String actionId, String handleGenerationId)
            throws IOException {
        ResumableAction action = requireResumable(actionId, handleGenerationId);
        LargeActionHandleValidator.requireCompleteModelWork(
                action.handle(), action.authorizedInputs());
        return action;
    }

    /** Atomically abandon an unfinished handle; build equality is deliberately not required. */
    public LargeActionHandle abandon(String actionId, String handleGenerationId)
            throws IOException {
        return terminalize(
                actionId,
                handleGenerationId,
                LargeActionPhase.CANCELLED,
                Optional.empty(),
                false,
                "terminal large-action handle cannot be abandoned");
    }

    /** Atomically record an unrecoverable execution failure on one unfinished handle. */
    public LargeActionHandle fail(
            String actionId, String handleGenerationId, RunResults.FailureDetail failure)
            throws IOException {
        return terminalize(
                actionId,
                handleGenerationId,
                LargeActionPhase.FAILED,
                Optional.of(Objects.requireNonNull(failure, "failure")),
                true,
                "terminal large-action handle cannot fail again");
    }

    /** Atomically record a deterministic recovery/transaction mismatch. */
    public LargeActionHandle conflict(
            String actionId, String handleGenerationId, RunResults.FailureDetail failure)
            throws IOException {
        RunResults.FailureDetail detail = Objects.requireNonNull(failure, "failure");
        if (detail.type() != RunResults.FailureType.REVISION_CONFLICT) {
            throw new IllegalArgumentException("large-action conflict requires REVISION_CONFLICT");
        }
        return terminalize(
                actionId,
                handleGenerationId,
                LargeActionPhase.CONFLICT,
                Optional.of(detail),
                true,
                "terminal large-action handle cannot conflict again");
    }

    /**
     * Clear terminal history and its unshared temporary files after its local worker has stopped.
     * The caller must confirm cloud cancellation before dismissing a cancelled target.
     */
    public void dismiss(String actionId, String handleGenerationId) throws IOException {
        Path file = handlePath(actionId, handleGenerationId);
        LargeActionHandle current = read(file);
        if (!current.phase().terminal()) {
            throw new IllegalStateException("unresolved large-action handle cannot be dismissed");
        }
        deleteOwnedDirectory(RecoveryResultFiles.directory(current));
        var retained = list();
        // Preserve control records if an interrupted image cannot be safely removed.
        discardUnreferencedSourceImages(current, retained);
        deleteRecoveryFiles(RecoveryFileCleanup.dismissedFiles(current, retained));
        Files.delete(file);
    }

    /** Called after an action's workers stop. Keep small control records for Jobs/history. */
    public void cleanupSavedFiles() throws IOException {
        cleanupStoppedFiles(java.util.Set.of());
    }

    /** Called under result-publication serialization when an active batch needs storage space. */
    void cleanupSavedResultFiles() throws IOException {
        for (var handle : list()) {
            if (handle.phase().hasCompletedSave()) {
                deleteOwnedDirectory(RecoveryResultFiles.directory(handle));
            }
        }
    }

    /** Remove crash leftovers only after the existing owner has stopped the target. */
    private void discardUnreferencedSourceImages(
            LargeActionHandle handle, List<LargeActionHandle> retained) throws IOException {
        Set<String> historical = scanRecords().protectedPaths();
        for (Path path : RecoverySourceFiles.unreferencedImages(this, handle, retained)) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (protects(relative, historical)) continue;
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) deleteOwnedDirectory(relative);
            else Files.delete(resolveForRead(relative));
        }
    }

    /**
     * Remove interrupted image encodings before this action prepares its next image. The caller
     * owns the action's only writer; retained images and upload control records are preserved.
     */
    public void discardInterruptedSourceImages(ResumableAction action) throws IOException {
        var handle = action.handle();
        var current = requireResumable(handle.actionId(), handle.handleGenerationId());
        if (!current.equals(action)) {
            throw new IllegalStateException("image cleanup requires the latest saved action");
        }
        discardUnreferencedSourceImages(handle, list());
    }

    /**
     * The caller has stopped local workers and confirmed cancellation of these targets' cloud runs.
     */
    public void cleanupStoppedFiles(java.util.Set<String> stoppedHandleIds) throws IOException {
        var retained = list();
        deleteRecoveryFiles(RecoveryFileCleanup.completedImages(retained, stoppedHandleIds));
        for (var handle : retained) {
            if (handle.phase().hasCompletedSave()
                    || handle.phase().terminal()
                            && stoppedHandleIds.contains(handle.handleGenerationId())) {
                // Includes interrupted downloads and replaced-run files, all owned by this handle.
                deleteOwnedDirectory(RecoveryResultFiles.directory(handle));
                discardUnreferencedSourceImages(handle, retained);
            }
        }
    }

    /**
     * Delete only this action's prepared image after its sealed cloud identity is durably saved.
     * Keep the file's size and digest in the handle as evidence; recovery no longer needs its
     * bytes. Missing files are allowed so a restart can repeat deletion after an interrupted
     * cleanup.
     */
    public void discardSealedSourceImage(ResumableAction action) throws IOException {
        LargeActionHandle supplied = action.handle();
        ResumableAction current =
                requireResumable(supplied.actionId(), supplied.handleGenerationId());
        if (!current.equals(action)) {
            throw new IllegalStateException("source cleanup requires the latest saved action");
        }
        var source = current.handle().source().orElseThrow();
        if (source.sealedArtifact().isEmpty()) {
            throw new IllegalStateException("an unfinished upload still needs its prepared image");
        }
        deleteRecoveryFiles(source.ownedUploadFile().stream().toList());
    }

    /** Accepted bindings are saved before their disposable local pixels can be removed. */
    public void discardBoundSourceImages(ResumableAction action) throws IOException {
        var handle = action.handle();
        var current = requireResumable(handle.actionId(), handle.handleGenerationId());
        if (!current.equals(action)) {
            throw new IllegalStateException("image cleanup requires the latest saved action");
        }
        deleteRecoveryFiles(RecoveryFileCleanup.boundImages(handle, list()));
    }

    private void deleteRecoveryFiles(List<LocalFileRecord> records) throws IOException {
        List<Path> files = new ArrayList<>();
        Set<String> historical = scanRecords().protectedPaths();
        // Validate all paths before deleting any requests needed to inspect cloud work. Missing
        // files are allowed so cleanup can be retried after an interrupted deletion.
        for (LocalFileRecord record : records) {
            if (protects(record.projectRelativePosixPath(), historical)) continue;
            Path path = resolve(record.projectRelativePosixPath());
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            // Do not follow links or hash an entire exported TIFF just to remove an owned file.
            files.add(resolveForRead(record.projectRelativePosixPath()));
        }
        for (Path file : files) Files.deleteIfExists(file);
    }

    /** Atomically publish one immutable recovery file and return its verified record. */
    public LocalFileRecord publishImmutable(String projectRelativePath, byte[] source)
            throws IOException {
        byte[] bytes = Arrays.copyOf(Objects.requireNonNull(source, "source"), source.length);
        if (bytes.length == 0) {
            throw new IllegalArgumentException("recovery file must be nonempty");
        }
        Path target = resolveForWrite(projectRelativePath);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            LocalFileRecord current = record(projectRelativePath, target);
            if (current.sizeBytes() != bytes.length
                    || !current.sha256().equals(CanonicalCodec.sha256(bytes))) {
                throw new IOException("immutable recovery path contains different state");
            }
            return current;
        }
        publisher.publish(target, bytes, false);
        return record(projectRelativePath, target);
    }

    /** Resolve and verify one recorded recovery file before any caller reads it. */
    public Path requireFile(LocalFileRecord file) throws IOException {
        LocalFileRecord expected = Objects.requireNonNull(file, "file");
        Path path = resolveRecordedForRead(expected.projectRelativePosixPath());
        final long actualSize;
        final String actualDigest;
        try {
            actualSize = Files.size(path);
            actualDigest = sha256(path);
        } catch (NoSuchFileException error) {
            throw unrecoverableFile(expected.projectRelativePosixPath(), error);
        }
        if (actualSize != expected.sizeBytes() || !actualDigest.equals(expected.sha256())) {
            throw unrecoverableFile(expected.projectRelativePosixPath());
        }
        return path;
    }

    /** Project recovery root used only to form caller-owned immutable paths. */
    public Path root() {
        return root;
    }

    /**
     * Prepare one project-relative path for a caller that atomically publishes a large local file.
     *
     * <p>The store creates and validates every parent below its trusted root before returning the
     * path. The caller remains responsible for writing through a private incomplete file and an
     * atomic move; {@link #recordExistingFile(String)} closes the publication by recording the
     * final bytes for later verification.
     */
    public Path prepareFilePath(String projectRelativePath) throws IOException {
        return resolveForWrite(projectRelativePath);
    }

    /** Record one already-published immutable file without loading it into heap memory. */
    public LocalFileRecord recordExistingFile(String projectRelativePath) throws IOException {
        String relative = LargeActionTypes.requireProjectRelativePath(projectRelativePath);
        return record(relative, resolveForRead(relative));
    }

    /**
     * Discard one directory that the caller has proved is absent from every durable handle.
     *
     * <p>This is the PREPARING-restart boundary for incomplete source capture. The complete tree is
     * validated without following links before any entry is removed; unexpected files, links, or
     * concurrent changes fail closed.
     */
    public void discardUnreferencedDirectory(String projectRelativeDirectory) throws IOException {
        deleteOwnedDirectory(projectRelativeDirectory);
    }

    private void deleteOwnedDirectory(String projectRelativeDirectory) throws IOException {
        Path directory = resolve(projectRelativeDirectory);
        if (protects(projectRelativeDirectory, scanRecords().protectedPaths())) return;
        Path cursor = root;
        for (Path segment : root.relativize(directory)) {
            cursor = cursor.resolve(segment.toString());
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(cursor)
                    || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("unreferenced recovery path contains invalid state");
            }
        }

        List<Path> entries;
        try (Stream<Path> paths = Files.walk(directory)) {
            entries = new ArrayList<>(paths.toList());
        }
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)
                    || (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("unreferenced recovery directory contains invalid state");
            }
        }
        entries.sort(Comparator.reverseOrder());
        for (Path entry : entries) {
            Files.delete(entry);
        }
    }

    private static boolean protects(String path, Set<String> historical) {
        return historical.stream()
                .anyMatch(
                        protectedPath ->
                                protectedPath.equals(path)
                                        || protectedPath.startsWith(path + "/")
                                        || path.startsWith(protectedPath + "/"));
    }

    private byte[] readHandleBytes(Path file) throws IOException {
        final BasicFileAttributes attributes;
        try {
            attributes =
                    Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException error) {
            throw unrecoverableHandle("large-action handle is missing", error);
        }
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw unrecoverableHandle("large-action handle is corrupt");
        }
        final byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readAllBytes();
        } catch (NoSuchFileException error) {
            throw unrecoverableHandle("large-action handle is missing", error);
        }
        return bytes;
    }

    private LargeActionHandle read(Path file) throws IOException {
        byte[] bytes = readHandleBytes(file);
        try {
            var object = LargeActionCodec.handleObject(CanonicalCodec.parseJson(bytes));
            int version =
                    CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version");
            if (version == HistoricalActionRecord.SCHEMA_VERSION)
                throw new IllegalStateException(
                        "Historical analysis is read-only; unfinished work requires its original client build");
            if (version != LargeActionTypes.SCHEMA_VERSION)
                throw new IllegalArgumentException(
                        "unsupported large-action schema_version: " + version);
            LargeActionHandle handle = LargeActionCodec.decodeCanonical(bytes);
            requireMatchingFilename(file, handle.actionId(), handle.handleGenerationId());
            return handle;
        } catch (IllegalArgumentException error) {
            throw unrecoverableHandle("large-action handle is corrupt", error);
        }
    }

    private void requireMatchingFilename(Path file, String actionId, String generationId)
            throws IOException {
        if (!file.equals(handlePath(actionId, generationId)))
            throw unrecoverableHandle("large-action handle filename does not match its identity");
    }

    private static byte[] validatedHandleBytes(LargeActionHandle handle) {
        byte[] bytes = LargeActionCodec.canonicalBytes(handle);
        if (!LargeActionCodec.decodeCanonical(bytes).equals(handle)) {
            throw new IllegalArgumentException(
                    "large-action handle does not round-trip through its persistence contract");
        }
        return bytes;
    }

    private byte[] readRecorded(LocalFileRecord file) throws IOException {
        Path path = resolveRecordedForRead(file.projectRelativePosixPath());
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (NoSuchFileException error) {
            throw unrecoverableFile(file.projectRelativePosixPath(), error);
        }
        if (bytes.length != file.sizeBytes()
                || !CanonicalCodec.sha256(bytes).equals(file.sha256())) {
            throw unrecoverableFile(file.projectRelativePosixPath());
        }
        return bytes;
    }

    private void verifyRecorded(LocalFileRecord file) throws IOException {
        requireFile(file);
    }

    private Path handlePath(String actionId, String handleGenerationId) {
        String action = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
        String generation =
                CanonicalCodec.requireCanonicalUuid(handleGenerationId, "handle_generation_id");
        return handles.resolve(action + "-" + generation + HANDLE_SUFFIX);
    }

    private Path resolveForWrite(String value) throws IOException {
        Path path = resolve(value);
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("recovery file must have a parent directory");
        }
        ensureRelativeDirectories(parent);
        if (Files.isSymbolicLink(path)) {
            throw new IOException("recovery file path must not be a symbolic link");
        }
        return path;
    }

    private Path resolveForRead(String value) throws IOException {
        return resolveForRead(value, false);
    }

    private Path resolveRecordedForRead(String value) throws IOException {
        return resolveForRead(value, true);
    }

    private Path resolveForRead(String value, boolean persistedReference) throws IOException {
        Path path = resolve(value);
        Path cursor = root;
        for (Path segment : root.relativize(path)) {
            cursor = cursor.resolve(segment.toString());
            final BasicFileAttributes attributes;
            try {
                attributes =
                        Files.readAttributes(
                                cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException error) {
                if (persistedReference) {
                    throw unrecoverableFile(value, error);
                }
                throw new IOException("local recovery file is missing", error);
            }
            if (attributes.isSymbolicLink()
                    || (cursor.equals(path)
                            ? !attributes.isRegularFile()
                            : !attributes.isDirectory())) {
                if (persistedReference) {
                    throw unrecoverableFile(value);
                }
                throw new IOException("recovery path contains invalid state");
            }
        }
        return path;
    }

    private static UnrecoverableRecoveryStateException unrecoverableFile(String relative) {
        return new UnrecoverableRecoveryStateException(
                "local recovery file is missing or does not match its persisted record: "
                        + relative);
    }

    private static UnrecoverableRecoveryStateException unrecoverableFile(
            String relative, Throwable cause) {
        return new UnrecoverableRecoveryStateException(
                "local recovery file is missing or does not match its persisted record: "
                        + relative,
                cause);
    }

    private static UnrecoverableRecoveryStateException unrecoverableHandle(String message) {
        return new UnrecoverableRecoveryStateException(message);
    }

    private static UnrecoverableRecoveryStateException unrecoverableHandle(
            String message, Throwable cause) {
        return new UnrecoverableRecoveryStateException(message, cause);
    }

    private Path resolve(String value) {
        String relative = LargeActionTypes.requireProjectRelativePath(value);
        Path result = root;
        for (String segment : relative.split("/")) {
            result = result.resolve(segment);
        }
        Path normalized = result.normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("recovery path escapes its project root");
        }
        return normalized;
    }

    private void ensureRelativeDirectories(Path directory) throws IOException {
        Path relative = root.relativize(directory);
        Path cursor = root;
        for (Path segment : relative) {
            cursor = cursor.resolve(segment.toString());
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(cursor)
                        || !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("recovery directory path contains invalid state");
                }
            } else {
                Files.createDirectory(cursor);
            }
        }
    }

    private static Path initializeRoot(Path supplied) throws IOException {
        Path path = Objects.requireNonNull(supplied, "recoveryRoot").toAbsolutePath().normalize();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("recovery root must be a real directory");
            }
        } else {
            Files.createDirectories(path);
        }
        return path;
    }

    private static Path ensureDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("recovery store path must be a real directory");
            }
        } else {
            Files.createDirectory(path);
        }
        return path;
    }

    private static void requireRegularFile(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is missing or is not a regular file");
        }
    }

    private static LocalFileRecord record(String relative, Path path) throws IOException {
        requireRegularFile(path, "local recovery file");
        long size = Files.size(path);
        if (size <= 0) {
            throw new IOException("local recovery file must be nonempty");
        }
        return new LocalFileRecord(relative, size, sha256(path));
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        ByteBuffer buffer = ByteBuffer.allocate(HASH_BUFFER_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void publishAtomically(Path target, byte[] bytes, boolean replace)
            throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("atomic publication requires a parent directory");
        }
        Path temporary = Files.createTempFile(parent, "." + target.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (replace) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.instant().toEpochMilli());
    }

    private LargeActionHandle terminalize(
            String actionId,
            String handleGenerationId,
            LargeActionPhase phase,
            Optional<RunResults.FailureDetail> failure,
            boolean requireRunningBuild,
            String terminalMessage)
            throws IOException {
        LargeActionHandle current = load(actionId, handleGenerationId);
        if (requireRunningBuild) {
            LargeActionHandleValidator.requireRunningBuild(current, runningBuild);
        }
        if (current.phase().terminal()) {
            if (current.phase() == phase && current.failure().equals(failure)) {
                return current;
            }
            throw new IllegalStateException(terminalMessage);
        }
        Instant updated = now();
        if (updated.isBefore(current.updatedAt())) {
            updated = current.updatedAt();
        }
        LargeActionHandle terminal =
                new LargeActionHandle(
                        current.schemaVersion(),
                        current.actionId(),
                        current.handleGenerationId(),
                        current.clientBuild(),
                        current.imageId(),
                        current.module(),
                        current.action(),
                        current.target(),
                        current.baseline(),
                        current.sourcePlan(),
                        current.source(),
                        current.steps(),
                        current.expectedCommittedHierarchy(),
                        phase,
                        failure,
                        current.createdAt(),
                        updated,
                        current.selectionRegions());
        return saveTransition(
                handlePath(current.actionId(), current.handleGenerationId()), current, terminal);
    }

    @FunctionalInterface
    interface AtomicPublisher {
        void publish(Path target, byte[] bytes, boolean replace) throws IOException;
    }
}
