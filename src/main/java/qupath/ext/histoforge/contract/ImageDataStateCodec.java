package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import qupath.lib.images.ImageData;

/** Canonical persisted HistoForge state stored as plain ImageData strings. */
public final class ImageDataStateCodec {

    public static final int ACTION_COMMIT_WITNESS_SCHEMA_VERSION = 1;

    /** Current supported plugin-build cap for the canonical generation-store property. */
    public static final long SUPPORTED_MAX_GENERATION_STORE_UTF8_BYTES = 32L * 1024 * 1024;

    private static final Set<String> ACTION_COMMIT_WITNESS_FIELDS =
            Set.of("schema_version", "action_id", "handle_generation_id");
    private static final long MAX_SAFE_COUNT = 9_007_199_254_740_991L;
    private static final long MAX_PHASE_VALID_SUPPRESSED_COUNT = 1_000_000_000_000_000L;
    private static final long MAX_PHASE_VALID_COMMITTED_COUNT =
            MAX_SAFE_COUNT - MAX_PHASE_VALID_SUPPRESSED_COUNT;
    private static final String PROJECTION_MODEL_RUN_ID = "ffffffff-ffff-4fff-bfff-ffffffffffff";
    private static final String PROJECTION_MANIFEST_DIGEST = "f".repeat(64);
    private static final Instant PROJECTION_COMMITTED_AT =
            Instant.parse("9999-12-31T23:59:59.999Z");

    private ImageDataStateCodec() {}

    /** Small save witness that resolves one ambiguous large-action commit. */
    public record ActionCommitWitness(
            int schemaVersion, String actionId, String handleGenerationId) {
        public ActionCommitWitness {
            if (schemaVersion != ACTION_COMMIT_WITNESS_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported action commit witness schema_version");
            }
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            handleGenerationId =
                    CanonicalCodec.requireCanonicalUuid(handleGenerationId, "handle_generation_id");
        }
    }

    /** Expected execution family, used to size saved evidence and validate completed results. */
    public enum ProjectionExecutionType {
        FINITE_CALL,
        DURABLE_RUN
    }

    /** Nonpersisted facts known before dispatch for one possible generation record. */
    public record GenerationStoreReservation(
            // Planned output-generation UUID, already allocated by the action.
            String outputGenerationId,
            // User-action UUID that owns the planned input.
            String actionId,
            // Captured same-class duplicate-removal policy for this action.
            boolean removeOverlappingSameClassDuplicates,
            // Exact deployment identity captured during module activation.
            String deploymentId,
            // Logical producer and scope that would own committed output.
            ExecutionScopeCodec.OutputKey output,
            // Stable action-local logical input position.
            int inputId,
            // Finite or durable execution shape whose maximum encoding is reserved.
            ProjectionExecutionType executionType,
            // Fixed image/grid/rasterizer identity, independent of later uploaded files.
            String modelRasterInputDigest,
            // Exact selected target and optional parent geometry.
            String scopeAcceptanceDigest,
            // Conservative bound on the completed input evidence, including source bindings.
            long maxInputEvidenceUtf8Bytes,
            // Normalized source revision referenced by the input evidence.
            RasterInputCodec.ActionSourceRevision sourceRevision,
            // Normalized target region referenced by the input evidence.
            ExecutionScopeCodec.TargetRegion targetRegion,
            // Exact client build captured by the action.
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            // Exact backend identity captured for the model step.
            InferenceIdentityCodec.BackendModelIdentity backendIdentity,
            // Expected compact durable plan facts, reserved before model dispatch.
            Optional<ProcessingEvidenceCodec.ProcessingEvidence> processingEvidence) {
        /**
         * Reserve before image delivery. Use this factory again on recovery, even if files have
         * since arrived, so the reservation remains identical throughout execution.
         */
        public static GenerationStoreReservation forInput(
                String outputGenerationId,
                String actionId,
                boolean removeOverlappingSameClassDuplicates,
                String deploymentId,
                ExecutionScopeCodec.OutputKey output,
                int inputId,
                ProjectionExecutionType executionType,
                RasterInputCodec.ModelRasterInput input,
                ExecutionScopeCodec.ScopeAcceptance acceptance,
                int unitCount,
                BuildIdentityCodec.ClientBuildIdentity clientBuild,
                InferenceIdentityCodec.BackendModelIdentity backendIdentity,
                Optional<ProcessingEvidenceCodec.ProcessingEvidence> processingEvidence) {
            GenerationStoreCodec.requirePlannedInputContext(
                    output, input, acceptance, backendIdentity);
            return new GenerationStoreReservation(
                    outputGenerationId,
                    actionId,
                    removeOverlappingSameClassDuplicates,
                    deploymentId,
                    output,
                    inputId,
                    executionType,
                    input.inputDigest(),
                    ExecutionScopeCodec.scopeAcceptanceDigest(acceptance),
                    GenerationEvidenceCodec.maximumInputEvidenceUtf8Size(
                            input, acceptance, unitCount),
                    input.frame().sourceRevision(),
                    acceptance.target(),
                    clientBuild,
                    backendIdentity,
                    processingEvidence);
        }

        /** Check actual completed evidence without substituting an estimated upload list. */
        public void requireCompletedEvidence(
                GenerationEvidenceCodec.CommittedInputEvidence evidence) {
            if (!modelRasterInputDigest.equals(evidence.modelRasterInputDigest())
                    || !scopeAcceptanceDigest.equals(evidence.scopeAcceptanceDigest())
                    || CanonicalCodec.canonicalBytes(GenerationEvidenceCodec.toJson(evidence))
                                    .length
                            > maxInputEvidenceUtf8Bytes) {
                throw new IllegalArgumentException(
                        "completed input evidence does not match its reservation");
            }
            GenerationStoreCodec.requireInputContext(
                    output, evidence, sourceRevision, targetRegion, backendIdentity);
        }

        public GenerationStoreReservation {
            outputGenerationId =
                    CanonicalCodec.requireCanonicalUuid(outputGenerationId, "output_generation_id");
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
            output = Objects.requireNonNull(output, "output");
            inputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            executionType = Objects.requireNonNull(executionType, "execution_type");
            modelRasterInputDigest =
                    CanonicalCodec.requireSha256(
                            modelRasterInputDigest, "model_raster_input_digest");
            scopeAcceptanceDigest =
                    CanonicalCodec.requireSha256(scopeAcceptanceDigest, "scope_acceptance_digest");
            maxInputEvidenceUtf8Bytes =
                    CanonicalCodec.requirePositiveSafeInteger(
                            maxInputEvidenceUtf8Bytes, "max_input_evidence_utf8_bytes");
            sourceRevision = Objects.requireNonNull(sourceRevision, "source_revision");
            targetRegion = Objects.requireNonNull(targetRegion, "target_region");
            clientBuild = Objects.requireNonNull(clientBuild, "client_build");
            backendIdentity = Objects.requireNonNull(backendIdentity, "backend_identity");
            processingEvidence = Objects.requireNonNull(processingEvidence, "processing_evidence");
            if ((executionType == ProjectionExecutionType.DURABLE_RUN)
                    != processingEvidence.isPresent()) {
                throw new IllegalArgumentException(
                        "durable reservation requires processing evidence; finite calls omit it");
            }
        }
    }

    /** Parse one exact schema-1 action commit witness. */
    public static ActionCommitWitness parseActionCommitWitness(JsonElement value) {
        JsonObject object =
                requireExactObject(value, ACTION_COMMIT_WITNESS_FIELDS, "action commit witness");
        return new ActionCommitWitness(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                requiredString(object, "action_id"),
                requiredString(object, "handle_generation_id"));
    }

    /** Encode one witness as its exact persisted ImageData String value. */
    public static String encodeActionCommitWitness(ActionCommitWitness witness) {
        return new String(canonicalBytes(witness), StandardCharsets.UTF_8);
    }

    /** Decode a persisted String and reject noncanonical or wrong-typed property values. */
    public static ActionCommitWitness decodeActionCommitWitnessProperty(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("action commit witness property must be a string");
        }
        ActionCommitWitness witness = parseActionCommitWitness(CanonicalCodec.parseJson(text));
        if (!encodeActionCommitWitness(witness).equals(text)) {
            throw new IllegalArgumentException(
                    "action commit witness property must be canonical JSON");
        }
        return witness;
    }

    /** Encode one generation store as a bounded canonical ImageData String value. */
    public static String encodeGenerationStore(
            GenerationStoreCodec.GenerationStore store,
            ExecutionLimitsCodec.ClientExecutionLimits limits) {
        Objects.requireNonNull(limits, "client execution limits");
        return encodeGenerationStore(store, limits.maxGenerationStoreUtf8Bytes());
    }

    /** Decode one bounded generation-store property and require exact canonical text. */
    public static GenerationStoreCodec.GenerationStore decodeGenerationStoreProperty(
            Object value, ExecutionLimitsCodec.ClientExecutionLimits limits) {
        Objects.requireNonNull(limits, "client execution limits");
        return decodeGenerationStoreProperty(value, limits.maxGenerationStoreUtf8Bytes());
    }

    /**
     * Read the optional generation store from one ImageData through the supported-build cap.
     *
     * <p>Action admission still uses its captured client limits. This overload is for a non-growing
     * lifecycle operation such as mark-and-sweep: a valid existing property is at most the
     * supported-build cap, and its canonical swept replacement can only be smaller.
     */
    public static Optional<GenerationStoreCodec.GenerationStore> readGenerationStore(
            ImageData<?> imageData) {
        Objects.requireNonNull(imageData, "imageData");
        if (!imageData.getProperties().containsKey(HistoforgePersistenceKeys.GENERATION_STORE)) {
            return Optional.empty();
        }
        return Optional.of(
                decodeGenerationStoreProperty(
                        imageData.getProperty(HistoforgePersistenceKeys.GENERATION_STORE),
                        SUPPORTED_MAX_GENERATION_STORE_UTF8_BYTES));
    }

    /** Encode one non-growing lifecycle replacement through the supported-build cap. */
    public static String encodeGenerationStore(GenerationStoreCodec.GenerationStore store) {
        return encodeGenerationStore(store, SUPPORTED_MAX_GENERATION_STORE_UTF8_BYTES);
    }

    /** Write one canonical, non-growing generation-store replacement to ImageData. */
    public static void writeGenerationStore(
            ImageData<?> imageData, GenerationStoreCodec.GenerationStore store) {
        Objects.requireNonNull(imageData, "imageData");
        imageData.setProperty(
                HistoforgePersistenceKeys.GENERATION_STORE, encodeGenerationStore(store));
    }

    /** Write one action-growing generation store through that action's captured byte limit. */
    public static void writeGenerationStore(
            ImageData<?> imageData,
            GenerationStoreCodec.GenerationStore store,
            ExecutionLimitsCodec.ClientExecutionLimits limits) {
        Objects.requireNonNull(imageData, "imageData");
        imageData.setProperty(
                HistoforgePersistenceKeys.GENERATION_STORE, encodeGenerationStore(store, limits));
    }

    /**
     * Require the conservative pre-dispatch generation-store projection to fit.
     *
     * <p>The current store is first swept against the complete current attributed-object
     * population. Exact already-staged stores are combined with maximum record sizes and the
     * reserved input-evidence bytes. Upload identities need not exist yet. This is a size
     * calculation, not a generation store that could be saved.
     */
    public static long requireGenerationStoreProjectionFits(
            GenerationStoreCodec.GenerationStore currentStore,
            Collection<ProvenanceCodec.ObjectProvenance> currentObjectProvenance,
            Collection<GenerationStoreCodec.GenerationStore> stagedStores,
            Collection<GenerationStoreReservation> plannedInputs,
            ExecutionLimitsCodec.ClientExecutionLimits limits) {
        Objects.requireNonNull(currentStore, "current_store");
        if (stagedStores == null) {
            throw new IllegalArgumentException("staged stores must not be null");
        }
        if (plannedInputs == null) {
            throw new IllegalArgumentException("planned inputs must not be null");
        }

        List<GenerationStoreCodec.GenerationStore> stores = new ArrayList<>();
        stores.add(
                GenerationStoreCodec.markAndSweep(currentStore, currentObjectProvenance).store());
        for (GenerationStoreCodec.GenerationStore staged : stagedStores) {
            stores.add(Objects.requireNonNull(staged, "staged store item"));
        }
        GenerationStoreCodec.GenerationStore stagedProjection = GenerationStoreCodec.merge(stores);
        JsonObject projection = GenerationStoreCodec.toJson(stagedProjection);
        JsonObject records = projection.getAsJsonObject("current_generation_records");
        long inputEvidenceBytes = 0;
        for (GenerationStoreReservation reservation : plannedInputs) {
            GenerationStoreReservation planned =
                    Objects.requireNonNull(reservation, "planned input item");
            String sourceDigest =
                    RasterInputCodec.actionSourceRevisionDigest(planned.sourceRevision());
            String targetDigest = ExecutionScopeCodec.targetRegionDigest(planned.targetRegion());
            String clientDigest =
                    BuildIdentityCodec.clientBuildIdentityDigest(planned.clientBuild());
            String backendDigest =
                    InferenceIdentityCodec.backendModelIdentityDigest(planned.backendIdentity());
            mergeProjectionEntry(
                    projection,
                    "source_revisions",
                    sourceDigest,
                    RasterInputCodec.toJson(planned.sourceRevision()));
            mergeProjectionEntry(
                    projection,
                    "target_regions",
                    targetDigest,
                    ExecutionScopeCodec.toJson(planned.targetRegion()));
            mergeProjectionEntry(
                    projection,
                    "client_builds",
                    clientDigest,
                    BuildIdentityCodec.toJson(planned.clientBuild()));
            mergeProjectionEntry(
                    projection,
                    "backend_identities",
                    backendDigest,
                    InferenceIdentityCodec.toJson(planned.backendIdentity()));
            if (records.has(planned.outputGenerationId())) {
                throw new IllegalArgumentException(
                        "projection contains a duplicate output generation ID");
            }
            records.add(
                    planned.outputGenerationId(),
                    maximumReservationRecord(planned, clientDigest, backendDigest));
            // The size-only record uses a one-byte zero in place of input_evidence.
            inputEvidenceBytes =
                    Math.addExact(inputEvidenceBytes, planned.maxInputEvidenceUtf8Bytes() - 1L);
        }
        long projectedBytes =
                Math.addExact(CanonicalCodec.canonicalBytes(projection).length, inputEvidenceBytes);
        Objects.requireNonNull(limits, "client execution limits");
        requireGenerationStoreSize(projectedBytes, limits.maxGenerationStoreUtf8Bytes());
        return projectedBytes;
    }

    /** Return the exact canonical JCS bytes for one validated witness. */
    public static byte[] canonicalBytes(ActionCommitWitness witness) {
        return CanonicalCodec.canonicalBytes(toJson(witness));
    }

    /** Serialize one witness with its frozen lower-snake field names. */
    public static JsonObject toJson(ActionCommitWitness witness) {
        if (witness == null) {
            throw new IllegalArgumentException("action commit witness must not be null");
        }
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", witness.schemaVersion());
        value.addProperty("action_id", witness.actionId());
        value.addProperty("handle_generation_id", witness.handleGenerationId());
        return value;
    }

    private static String encodeGenerationStore(
            GenerationStoreCodec.GenerationStore store, long maxUtf8Bytes) {
        byte[] encoded = GenerationStoreCodec.canonicalBytes(store);
        requireGenerationStoreSize(encoded.length, maxUtf8Bytes);
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static GenerationStoreCodec.GenerationStore decodeGenerationStoreProperty(
            Object value, long maxUtf8Bytes) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("generation store property must be a string");
        }
        byte[] source = CanonicalCodec.utf8Bytes(text);
        requireGenerationStoreSize(source.length, maxUtf8Bytes);
        GenerationStoreCodec.GenerationStore store =
                GenerationStoreCodec.parseGenerationStore(CanonicalCodec.parseJson(source));
        if (!encodeGenerationStore(store, maxUtf8Bytes).equals(text)) {
            throw new IllegalArgumentException("generation store property must be canonical JSON");
        }
        return store;
    }

    private static void requireGenerationStoreSize(long utf8Bytes, long maxUtf8Bytes) {
        if (utf8Bytes > maxUtf8Bytes) {
            throw new IllegalArgumentException(
                    "generation store exceeds max_generation_store_utf8_bytes");
        }
    }

    private static JsonObject maximumReservationRecord(
            GenerationStoreReservation reservation, String clientDigest, String backendDigest) {
        BackendExecutionCodec.BackendExecutionRef execution =
                switch (reservation.executionType()) {
                    case FINITE_CALL ->
                            new BackendExecutionCodec.FiniteCall(
                                    BackendExecutionCodec.maximumCanonicalSizeBackendCallId());
                    case DURABLE_RUN ->
                            new BackendExecutionCodec.DurableRun(
                                    PROJECTION_MODEL_RUN_ID, PROJECTION_MANIFEST_DIGEST);
                };
        GenerationEvidenceCodec.GenerationCounts counts =
                reservation.removeOverlappingSameClassDuplicates()
                        ? new GenerationEvidenceCodec.GenerationCounts(
                                MAX_SAFE_COUNT,
                                MAX_SAFE_COUNT,
                                MAX_PHASE_VALID_SUPPRESSED_COUNT,
                                MAX_PHASE_VALID_COMMITTED_COUNT)
                        : new GenerationEvidenceCodec.GenerationCounts(
                                MAX_SAFE_COUNT, MAX_SAFE_COUNT, 0, MAX_SAFE_COUNT);
        return GenerationEvidenceCodec.generationRecordJson(
                reservation.outputGenerationId(),
                reservation.actionId(),
                reservation.removeOverlappingSameClassDuplicates(),
                clientDigest,
                reservation.deploymentId(),
                reservation.output(),
                reservation.inputId(),
                backendDigest,
                execution,
                new JsonPrimitive(0),
                counts,
                PROJECTION_COMMITTED_AT,
                reservation.processingEvidence());
    }

    private static void mergeProjectionEntry(
            JsonObject projection, String table, String key, JsonObject value) {
        JsonObject values = projection.getAsJsonObject(table);
        JsonElement existing = values.get(key);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalArgumentException(table + " contains a conflicting identity key");
        }
        values.add(key, value);
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject object = value.getAsJsonObject();
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " fields mismatch");
        }
        return object;
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return value.getAsString();
    }
}
