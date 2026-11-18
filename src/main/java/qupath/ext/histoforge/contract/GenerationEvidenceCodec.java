package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Canonical production-input, count, and committed-generation records. */
public final class GenerationEvidenceCodec {

    private static final Set<String> GENERATION_COUNT_FIELDS =
            Set.of(
                    "backend_features",
                    "scope_accepted",
                    "candidates_suppressed_by_unattributed_overlap",
                    "committed_objects");
    private static final Set<String> INPUT_EVIDENCE_FIELDS =
            Set.of(
                    "model_raster_input_digest",
                    "source_revision_digest",
                    "target_region_digest",
                    "source_view",
                    "raster_transform",
                    "inclusion_mask_digest",
                    "scope_acceptance_digest",
                    "raster_frame_digest");
    private static final Set<String> INPUT_EVIDENCE_WITH_PARENT_FIELDS =
            Set.of(
                    "model_raster_input_digest",
                    "source_revision_digest",
                    "target_region_digest",
                    "parent_geometry_digest",
                    "source_view",
                    "raster_transform",
                    "inclusion_mask_digest",
                    "scope_acceptance_digest",
                    "raster_frame_digest");
    private static final Set<String> GENERATION_RECORD_FIELDS =
            Set.of(
                    "output_generation_id",
                    "action_id",
                    "remove_overlapping_same_class_duplicates",
                    "client_build_digest",
                    "deployment_id",
                    "output",
                    "input_id",
                    "backend_identity_digest",
                    "execution",
                    "input_evidence",
                    "counts",
                    "committed_at");

    private GenerationEvidenceCodec() {}

    /** Auditable counts through backend output, acceptance, suppression, and commit. */
    public record GenerationCounts(
            // Features emitted by the backend before scope acceptance.
            long backendFeatures,
            // Scope-admitted native-persistence survivors, before overlap suppression.
            long scopeAccepted,
            // Candidates suppressed by qualifying unattributed objects.
            long candidatesSuppressedByUnattributedOverlap,
            // New attributed objects that reached the committed hierarchy.
            long committedObjects) {
        public GenerationCounts {
            backendFeatures =
                    CanonicalCodec.requireNonnegativeSafeInteger(
                            backendFeatures, "backend_features");
            scopeAccepted =
                    CanonicalCodec.requireNonnegativeSafeInteger(scopeAccepted, "scope_accepted");
            candidatesSuppressedByUnattributedOverlap =
                    CanonicalCodec.requireNonnegativeSafeInteger(
                            candidatesSuppressedByUnattributedOverlap,
                            "candidates_suppressed_by_unattributed_overlap");
            committedObjects =
                    CanonicalCodec.requireNonnegativeSafeInteger(
                            committedObjects, "committed_objects");
            if (scopeAccepted > backendFeatures) {
                throw new IllegalArgumentException("scope_accepted cannot exceed backend_features");
            }
            if (candidatesSuppressedByUnattributedOverlap > scopeAccepted
                    || committedObjects
                            != scopeAccepted - candidatesSuppressedByUnattributedOverlap) {
                throw new IllegalArgumentException(
                        "committed_objects must equal accepted candidates after suppression");
            }
        }
    }

    /** Compact production-input facts retained after complete runtime validation. */
    public record CommittedInputEvidence(
            // Backend-verified digest of the complete submitted model-raster input.
            String modelRasterInputDigest,
            // Digest key of the normalized captured action source revision.
            String sourceRevisionDigest,
            // Digest key of the normalized complete target region.
            String targetRegionDigest,
            // Source-parent geometry digest, present exactly for a parent scope.
            Optional<String> parentGeometryDigest,
            // Fixed source view used to produce the model raster.
            RasterInputCodec.RasterSourceView sourceView,
            // Target-raster description and mapping into slide coordinates.
            RasterInputCodec.RasterTransform rasterTransform,
            // Digest of the exact runtime raster-space inclusion mask.
            String inclusionMaskDigest,
            // Digest binding target and optional parent acceptance geometry.
            String scopeAcceptanceDigest,
            // Canonical digest of the complete runtime raster frame.
            String rasterFrameDigest,
            // Present only for a captured view: every unit's exact accepted source file.
            List<SourceCaptureCodec.UnitBinding> sourceBindings) {
        public CommittedInputEvidence(
                String modelRasterInputDigest,
                String sourceRevisionDigest,
                String targetRegionDigest,
                Optional<String> parentGeometryDigest,
                RasterInputCodec.RasterSourceView sourceView,
                RasterInputCodec.RasterTransform rasterTransform,
                String inclusionMaskDigest,
                String scopeAcceptanceDigest,
                String rasterFrameDigest) {
            this(
                    modelRasterInputDigest,
                    sourceRevisionDigest,
                    targetRegionDigest,
                    parentGeometryDigest,
                    sourceView,
                    rasterTransform,
                    inclusionMaskDigest,
                    scopeAcceptanceDigest,
                    rasterFrameDigest,
                    List.of());
        }

        public CommittedInputEvidence {
            modelRasterInputDigest =
                    CanonicalCodec.requireSha256(
                            modelRasterInputDigest, "model_raster_input_digest");
            sourceRevisionDigest =
                    CanonicalCodec.requireSha256(sourceRevisionDigest, "source_revision_digest");
            targetRegionDigest =
                    CanonicalCodec.requireSha256(targetRegionDigest, "target_region_digest");
            parentGeometryDigest =
                    Objects.requireNonNull(parentGeometryDigest, "parent_geometry_digest")
                            .map(
                                    digest ->
                                            CanonicalCodec.requireSha256(
                                                    digest, "parent_geometry_digest"));
            sourceView = Objects.requireNonNull(sourceView, "source_view");
            rasterTransform = Objects.requireNonNull(rasterTransform, "raster_transform");
            inclusionMaskDigest =
                    CanonicalCodec.requireSha256(inclusionMaskDigest, "inclusion_mask_digest");
            scopeAcceptanceDigest =
                    CanonicalCodec.requireSha256(scopeAcceptanceDigest, "scope_acceptance_digest");
            rasterFrameDigest =
                    CanonicalCodec.requireSha256(rasterFrameDigest, "raster_frame_digest");
            sourceBindings = List.copyOf(sourceBindings);
            if (sourceView instanceof SourceCaptureCodec.View captured) {
                sourceBindings = SourceCaptureCodec.requireCompleteBindings(sourceBindings);
                sourceBindings.forEach(
                        binding -> binding.source().requireCapture(captured.capture()));
            } else if (!sourceBindings.isEmpty()) {
                throw new IllegalArgumentException(
                        "sealed source evidence cannot carry source_bindings");
            }
        }
    }

    /** Immutable production facts for one output generation that reached a successful save. */
    public record GenerationRecord(
            // Stable identity of the logical output generation.
            String outputGenerationId,
            // User action that produced and committed the generation.
            String actionId,
            // Whether same-class suppression against unattributed objects ran.
            boolean removeOverlappingSameClassDuplicates,
            // Digest key of the normalized client build identity.
            String clientBuildDigest,
            // Backend deployment captured with the executable module snapshot.
            String deploymentId,
            // Logical producer and scope that own replacement of this generation.
            ExecutionScopeCodec.OutputKey output,
            // Stable logical model-input position within its action-local plan.
            int inputId,
            // Digest key of the normalized immutable backend model identity.
            String backendIdentityDigest,
            // Accepted finite call or immutable durable result manifest.
            BackendExecutionCodec.BackendExecutionRef execution,
            // Compact immutable evidence for the production input.
            CommittedInputEvidence inputEvidence,
            // Auditable feature counts through acceptance, suppression, and commit.
            GenerationCounts counts,
            // Millisecond UTC time at which this generation reached persistence.
            Instant committedAt,
            // Absent for finite calls and older retained records; never stores processing windows.
            Optional<ProcessingEvidenceCodec.ProcessingEvidence> processingEvidence) {
        public GenerationRecord(
                String outputGenerationId,
                String actionId,
                boolean removeOverlappingSameClassDuplicates,
                String clientBuildDigest,
                String deploymentId,
                ExecutionScopeCodec.OutputKey output,
                int inputId,
                String backendIdentityDigest,
                BackendExecutionCodec.BackendExecutionRef execution,
                CommittedInputEvidence inputEvidence,
                GenerationCounts counts,
                Instant committedAt) {
            this(
                    outputGenerationId,
                    actionId,
                    removeOverlappingSameClassDuplicates,
                    clientBuildDigest,
                    deploymentId,
                    output,
                    inputId,
                    backendIdentityDigest,
                    execution,
                    inputEvidence,
                    counts,
                    committedAt,
                    Optional.empty());
        }

        public GenerationRecord {
            outputGenerationId =
                    CanonicalCodec.requireCanonicalUuid(outputGenerationId, "output_generation_id");
            actionId = CanonicalCodec.requireCanonicalUuid(actionId, "action_id");
            clientBuildDigest =
                    CanonicalCodec.requireSha256(clientBuildDigest, "client_build_digest");
            deploymentId = CanonicalCodec.requireNonblank(deploymentId, "deployment_id");
            output = Objects.requireNonNull(output, "output");
            inputId = CanonicalCodec.requireNonnegativeInt32(inputId, "input_id");
            backendIdentityDigest =
                    CanonicalCodec.requireSha256(backendIdentityDigest, "backend_identity_digest");
            execution = Objects.requireNonNull(execution, "execution");
            inputEvidence = Objects.requireNonNull(inputEvidence, "input_evidence");
            counts = Objects.requireNonNull(counts, "counts");
            if (counts.committedObjects() == 0) {
                throw new IllegalArgumentException(
                        "generation records require at least one committed object");
            }
            if (!removeOverlappingSameClassDuplicates
                    && counts.candidatesSuppressedByUnattributedOverlap() != 0) {
                throw new IllegalArgumentException(
                        "suppressed candidates require overlap suppression to be enabled");
            }
            committedAt = Objects.requireNonNull(committedAt, "committed_at");
            CanonicalCodec.rfc3339Millis(committedAt, "committed_at");
            processingEvidence = Objects.requireNonNull(processingEvidence, "processing_evidence");
            if (processingEvidence.isPresent()
                    && !(execution instanceof BackendExecutionCodec.DurableRun)) {
                throw new IllegalArgumentException(
                        "processing evidence requires a durable execution");
            }
        }
    }

    /** Parse one exact generation count record. */
    public static GenerationCounts parseGenerationCounts(JsonElement value) {
        JsonObject object = requireExactObject(value, GENERATION_COUNT_FIELDS, "generation counts");
        return new GenerationCounts(
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("backend_features"), "backend_features"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("scope_accepted"), "scope_accepted"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("candidates_suppressed_by_unattributed_overlap"),
                        "candidates_suppressed_by_unattributed_overlap"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("committed_objects"), "committed_objects"));
    }

    /** Parse one compact committed-input evidence record. */
    public static CommittedInputEvidence parseCommittedInputEvidence(JsonElement value) {
        JsonObject object = requiredObject(value, "committed input evidence");
        var sourceView = RasterInputCodec.parseRasterSourceView(object.get("source_view"));
        var fields =
                new HashSet<>(
                        object.has("parent_geometry_digest")
                                ? INPUT_EVIDENCE_WITH_PARENT_FIELDS
                                : INPUT_EVIDENCE_FIELDS);
        if (sourceView instanceof SourceCaptureCodec.View) fields.add("source_bindings");
        requireFields(object, fields, "committed input evidence");
        return new CommittedInputEvidence(
                requiredString(object, "model_raster_input_digest"),
                requiredString(object, "source_revision_digest"),
                requiredString(object, "target_region_digest"),
                object.has("parent_geometry_digest")
                        ? Optional.of(requiredString(object, "parent_geometry_digest"))
                        : Optional.empty(),
                sourceView,
                RasterInputCodec.parseRasterTransform(object.get("raster_transform")),
                requiredString(object, "inclusion_mask_digest"),
                requiredString(object, "scope_acceptance_digest"),
                requiredString(object, "raster_frame_digest"),
                sourceView instanceof SourceCaptureCodec.View
                        ? SourceCaptureCodec.parseCompleteBindings(object.get("source_bindings"))
                        : List.of());
    }

    /** Parse one exact current generation record. */
    public static GenerationRecord parseGenerationRecord(JsonElement value) {
        JsonObject object = requiredObject(value, "generation record");
        Set<String> fields = new java.util.HashSet<>(GENERATION_RECORD_FIELDS);
        if (object.has("processing_evidence")) fields.add("processing_evidence");
        requireFields(object, fields, "generation record");
        return new GenerationRecord(
                requiredString(object, "output_generation_id"),
                requiredString(object, "action_id"),
                requiredBoolean(object, "remove_overlapping_same_class_duplicates"),
                requiredString(object, "client_build_digest"),
                requiredString(object, "deployment_id"),
                ExecutionScopeCodec.parseOutputKey(object.get("output")),
                CanonicalCodec.requireNonnegativeInt32(object.get("input_id"), "input_id"),
                requiredString(object, "backend_identity_digest"),
                BackendExecutionCodec.parse(object.get("execution")),
                parseCommittedInputEvidence(object.get("input_evidence")),
                parseGenerationCounts(object.get("counts")),
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "committed_at"), "committed_at"),
                object.has("processing_evidence")
                        ? Optional.of(
                                ProcessingEvidenceCodec.parse(object.get("processing_evidence")))
                        : Optional.empty());
    }

    /** Encode one generation count record. */
    public static JsonObject toJson(GenerationCounts counts) {
        JsonObject value = new JsonObject();
        value.addProperty("backend_features", counts.backendFeatures());
        value.addProperty("scope_accepted", counts.scopeAccepted());
        value.addProperty(
                "candidates_suppressed_by_unattributed_overlap",
                counts.candidatesSuppressedByUnattributedOverlap());
        value.addProperty("committed_objects", counts.committedObjects());
        return value;
    }

    /** Encode one compact committed-input evidence record. */
    public static JsonObject toJson(CommittedInputEvidence evidence) {
        JsonObject value =
                inputEvidenceJson(
                        evidence.modelRasterInputDigest(),
                        evidence.sourceRevisionDigest(),
                        evidence.targetRegionDigest(),
                        evidence.parentGeometryDigest(),
                        evidence.sourceView(),
                        evidence.rasterTransform(),
                        evidence.inclusionMaskDigest(),
                        evidence.scopeAcceptanceDigest(),
                        evidence.rasterFrameDigest());
        if (evidence.sourceView() instanceof SourceCaptureCodec.View) {
            value.add(
                    "source_bindings", SourceCaptureCodec.bindingsJson(evidence.sourceBindings()));
        }
        return value;
    }

    /** Fixed input facts plus space for every eventual source binding; no file is invented. */
    static long maximumInputEvidenceUtf8Size(
            RasterInputCodec.ModelRasterInput input,
            ExecutionScopeCodec.ScopeAcceptance acceptance,
            int unitCount) {
        JsonObject value =
                inputEvidenceJson(
                        input.inputDigest(),
                        RasterInputCodec.actionSourceRevisionDigest(input.frame().sourceRevision()),
                        ExecutionScopeCodec.targetRegionDigest(acceptance.target()),
                        acceptance
                                .parentGeometrySlide()
                                .map(CanonicalGeometryCodec::canonicalDigest),
                        input.sourceView(),
                        input.frame().transform(),
                        input.frame().inclusionMaskDigest(),
                        ExecutionScopeCodec.scopeAcceptanceDigest(acceptance),
                        input.frame().frameDigest());
        long bindingsSize = 0;
        if (input.sourceView() instanceof SourceCaptureCodec.View) {
            value.add("source_bindings", new com.google.gson.JsonArray());
            bindingsSize = SourceCaptureCodec.maximumBindingsUtf8Size(unitCount) - 2L;
        }
        return Math.addExact(CanonicalCodec.canonicalBytes(value).length, bindingsSize);
    }

    private static JsonObject inputEvidenceJson(
            String inputDigest,
            String sourceDigest,
            String targetDigest,
            Optional<String> parentDigest,
            RasterInputCodec.RasterSourceView sourceView,
            RasterInputCodec.RasterTransform transform,
            String maskDigest,
            String acceptanceDigest,
            String frameDigest) {
        JsonObject value = new JsonObject();
        value.addProperty("model_raster_input_digest", inputDigest);
        value.addProperty("source_revision_digest", sourceDigest);
        value.addProperty("target_region_digest", targetDigest);
        parentDigest.ifPresent(digest -> value.addProperty("parent_geometry_digest", digest));
        value.add("source_view", RasterInputCodec.toJson(sourceView));
        value.add("raster_transform", RasterInputCodec.toJson(transform));
        value.addProperty("inclusion_mask_digest", maskDigest);
        value.addProperty("scope_acceptance_digest", acceptanceDigest);
        value.addProperty("raster_frame_digest", frameDigest);
        return value;
    }

    /** Encode one current generation record. */
    public static JsonObject toJson(GenerationRecord record) {
        return generationRecordJson(
                record.outputGenerationId(),
                record.actionId(),
                record.removeOverlappingSameClassDuplicates(),
                record.clientBuildDigest(),
                record.deploymentId(),
                record.output(),
                record.inputId(),
                record.backendIdentityDigest(),
                record.execution(),
                toJson(record.inputEvidence()),
                record.counts(),
                record.committedAt(),
                record.processingEvidence());
    }

    // Shared encoding keeps size-only reservations aligned with the actual persisted record.
    static JsonObject generationRecordJson(
            String generationId,
            String actionId,
            boolean suppressDuplicates,
            String clientDigest,
            String deploymentId,
            ExecutionScopeCodec.OutputKey output,
            int inputId,
            String backendDigest,
            BackendExecutionCodec.BackendExecutionRef execution,
            JsonElement inputEvidence,
            GenerationCounts counts,
            Instant committedAt,
            Optional<ProcessingEvidenceCodec.ProcessingEvidence> processingEvidence) {
        JsonObject value = new JsonObject();
        value.addProperty("output_generation_id", generationId);
        value.addProperty("action_id", actionId);
        value.addProperty("remove_overlapping_same_class_duplicates", suppressDuplicates);
        value.addProperty("client_build_digest", clientDigest);
        value.addProperty("deployment_id", deploymentId);
        value.add("output", ExecutionScopeCodec.toJson(output));
        value.addProperty("input_id", inputId);
        value.addProperty("backend_identity_digest", backendDigest);
        value.add("execution", BackendExecutionCodec.toJson(execution));
        value.add("input_evidence", inputEvidence);
        value.add("counts", toJson(counts));
        value.addProperty(
                "committed_at", CanonicalCodec.rfc3339Millis(committedAt, "committed_at"));
        processingEvidence.ifPresent(
                evidence ->
                        value.add("processing_evidence", ProcessingEvidenceCodec.toJson(evidence)));
        return value;
    }

    private static JsonObject requireExactObject(
            JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        requireFields(object, fields, path);
        return object;
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static void requireFields(JsonObject value, Set<String> fields, String path) {
        if (!value.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
    }

    private static String requiredString(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return item.getAsString();
    }

    private static boolean requiredBoolean(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return item.getAsBoolean();
    }
}
