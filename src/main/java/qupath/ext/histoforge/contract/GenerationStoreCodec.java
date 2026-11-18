package qupath.ext.histoforge.contract;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** Canonical normalized production evidence persisted once per QuPath image. */
public final class GenerationStoreCodec {

    public static final int SCHEMA_VERSION = 2;

    private static final Set<String> GENERATION_STORE_FIELDS =
            Set.of(
                    "schema_version",
                    "source_revisions",
                    "target_regions",
                    "client_builds",
                    "backend_identities",
                    "current_generation_records");

    private GenerationStoreCodec() {}

    /** Return the canonical empty current-evidence store. */
    public static GenerationStore emptyStore() {
        return new GenerationStore(
                SCHEMA_VERSION, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Bounded normalized evidence and current generation records for one image. */
    public record GenerationStore(
            // Version of the canonical generation-store JSON schema.
            int schemaVersion,
            // Deduplicated source revisions keyed by canonical digest.
            Map<String, RasterInputCodec.ActionSourceRevision> sourceRevisions,
            // Deduplicated complete target regions keyed by canonical digest.
            Map<String, ExecutionScopeCodec.TargetRegion> targetRegions,
            // Deduplicated client build identities keyed by canonical digest.
            Map<String, BuildIdentityCodec.ClientBuildIdentity> clientBuilds,
            // Deduplicated backend model identities keyed by canonical digest.
            Map<String, InferenceIdentityCodec.BackendModelIdentity> backendIdentities,
            // Current generation records keyed by output-generation UUID.
            Map<String, GenerationEvidenceCodec.GenerationRecord> currentGenerationRecords) {
        public GenerationStore {
            if (schemaVersion != 1 && schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported generation store schema_version");
            }
            sourceRevisions = immutableMap(sourceRevisions, "source_revisions");
            targetRegions = immutableMap(targetRegions, "target_regions");
            clientBuilds = immutableMap(clientBuilds, "client_builds");
            backendIdentities = immutableMap(backendIdentities, "backend_identities");
            currentGenerationRecords =
                    immutableMap(currentGenerationRecords, "current_generation_records");
            // Keep committed schema-1 bytes readable without claiming they recorded file bindings.
            if (schemaVersion == 1
                    && currentGenerationRecords.values().stream()
                            .anyMatch(
                                    record ->
                                            record.inputEvidence().sourceView()
                                                    instanceof SourceCaptureCodec.View)) {
                throw new IllegalArgumentException(
                        "captured source evidence requires generation store schema 2");
            }
            validateStore(
                    sourceRevisions,
                    targetRegions,
                    clientBuilds,
                    backendIdentities,
                    currentGenerationRecords);
        }
    }

    /** Swept store plus object-referenced generation IDs missing from that store. */
    public record SweepResult(
            // Store retaining only records referenced by the supplied complete object population.
            GenerationStore store,
            // Referenced output-generation UUIDs with no current generation record.
            Set<String> missingGenerationRecordIds) {
        public SweepResult {
            store = Objects.requireNonNull(store, "store");
            if (missingGenerationRecordIds == null) {
                throw new IllegalArgumentException(
                        "missing_generation_record_ids must not be null");
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String value : missingGenerationRecordIds) {
                normalized.add(
                        CanonicalCodec.requireCanonicalUuid(
                                value, "missing_generation_record_ids item"));
            }
            missingGenerationRecordIds = Set.copyOf(normalized);
        }
    }

    /**
     * Parse current evidence or a sealed-source schema-1 store, retaining its canonical version.
     */
    public static GenerationStore parseGenerationStore(JsonElement value) {
        JsonObject object = requireExactObject(value, GENERATION_STORE_FIELDS, "generation store");
        return new GenerationStore(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                parseDigestMap(
                        object.get("source_revisions"),
                        "source_revisions",
                        RasterInputCodec::parseActionSourceRevision,
                        RasterInputCodec::actionSourceRevisionDigest),
                parseDigestMap(
                        object.get("target_regions"),
                        "target_regions",
                        ExecutionScopeCodec::parseTargetRegion,
                        ExecutionScopeCodec::targetRegionDigest),
                parseDigestMap(
                        object.get("client_builds"),
                        "client_builds",
                        BuildIdentityCodec::parseClientBuildIdentity,
                        BuildIdentityCodec::clientBuildIdentityDigest),
                parseDigestMap(
                        object.get("backend_identities"),
                        "backend_identities",
                        InferenceIdentityCodec::parseBackendModelIdentity,
                        InferenceIdentityCodec::backendModelIdentityDigest),
                parseGenerationRecords(object.get("current_generation_records")));
    }

    /** Encode one complete normalized generation store. */
    public static JsonObject toJson(GenerationStore store) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", store.schemaVersion());
        value.add("source_revisions", encodeMap(store.sourceRevisions(), RasterInputCodec::toJson));
        value.add("target_regions", encodeMap(store.targetRegions(), ExecutionScopeCodec::toJson));
        value.add("client_builds", encodeMap(store.clientBuilds(), BuildIdentityCodec::toJson));
        value.add(
                "backend_identities",
                encodeMap(store.backendIdentities(), InferenceIdentityCodec::toJson));
        value.add(
                "current_generation_records",
                encodeMap(store.currentGenerationRecords(), GenerationEvidenceCodec::toJson));
        return value;
    }

    /** Return the exact canonical property bytes for one validated store. */
    public static byte[] canonicalBytes(GenerationStore store) {
        return CanonicalCodec.canonicalBytes(toJson(store));
    }

    /** Merge normalized stores, accepting only identical value reuse under one identity key. */
    public static GenerationStore merge(Collection<GenerationStore> stores) {
        if (stores == null) {
            throw new IllegalArgumentException("generation stores must not be null");
        }
        Map<String, RasterInputCodec.ActionSourceRevision> sources = new LinkedHashMap<>();
        Map<String, ExecutionScopeCodec.TargetRegion> targets = new LinkedHashMap<>();
        Map<String, BuildIdentityCodec.ClientBuildIdentity> clients = new LinkedHashMap<>();
        Map<String, InferenceIdentityCodec.BackendModelIdentity> backends = new LinkedHashMap<>();
        Map<String, GenerationEvidenceCodec.GenerationRecord> records = new LinkedHashMap<>();
        for (GenerationStore store : stores) {
            Objects.requireNonNull(store, "generation store item");
            mergeEntries(sources, store.sourceRevisions(), "source_revisions");
            mergeEntries(targets, store.targetRegions(), "target_regions");
            mergeEntries(clients, store.clientBuilds(), "client_builds");
            mergeEntries(backends, store.backendIdentities(), "backend_identities");
            mergeEntries(records, store.currentGenerationRecords(), "current_generation_records");
        }
        return new GenerationStore(SCHEMA_VERSION, sources, targets, clients, backends, records);
    }

    /** Sweep against the complete current attributed-object population. */
    public static SweepResult markAndSweep(
            GenerationStore store,
            Collection<ProvenanceCodec.ObjectProvenance> currentObjectProvenance) {
        Objects.requireNonNull(store, "store");
        if (currentObjectProvenance == null) {
            throw new IllegalArgumentException("current object provenance must not be null");
        }

        Set<String> referenced = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        for (ProvenanceCodec.ObjectProvenance provenance : currentObjectProvenance) {
            Objects.requireNonNull(provenance, "current object provenance item");
            GenerationEvidenceCodec.GenerationRecord record =
                    store.currentGenerationRecords().get(provenance.outputGenerationId());
            if (record == null) {
                missing.add(provenance.outputGenerationId());
                continue;
            }
            if (!record.output().equals(provenance.output())) {
                throw new IllegalArgumentException(
                        "object provenance output does not match its generation record");
            }
            referenced.add(record.outputGenerationId());
        }

        Map<String, GenerationEvidenceCodec.GenerationRecord> records = new LinkedHashMap<>();
        referenced.forEach(
                generationId ->
                        records.put(
                                generationId, store.currentGenerationRecords().get(generationId)));
        return new SweepResult(subset(store, records), missing);
    }

    private static GenerationStore subset(
            GenerationStore store, Map<String, GenerationEvidenceCodec.GenerationRecord> records) {
        Map<String, RasterInputCodec.ActionSourceRevision> sources = new LinkedHashMap<>();
        Map<String, ExecutionScopeCodec.TargetRegion> targets = new LinkedHashMap<>();
        Map<String, BuildIdentityCodec.ClientBuildIdentity> clients = new LinkedHashMap<>();
        Map<String, InferenceIdentityCodec.BackendModelIdentity> backends = new LinkedHashMap<>();
        for (GenerationEvidenceCodec.GenerationRecord record : records.values()) {
            GenerationEvidenceCodec.CommittedInputEvidence evidence = record.inputEvidence();
            sources.put(
                    evidence.sourceRevisionDigest(),
                    store.sourceRevisions().get(evidence.sourceRevisionDigest()));
            targets.put(
                    evidence.targetRegionDigest(),
                    store.targetRegions().get(evidence.targetRegionDigest()));
            clients.put(
                    record.clientBuildDigest(),
                    store.clientBuilds().get(record.clientBuildDigest()));
            backends.put(
                    record.backendIdentityDigest(),
                    store.backendIdentities().get(record.backendIdentityDigest()));
        }
        return new GenerationStore(SCHEMA_VERSION, sources, targets, clients, backends, records);
    }

    private static void validateStore(
            Map<String, RasterInputCodec.ActionSourceRevision> sources,
            Map<String, ExecutionScopeCodec.TargetRegion> targets,
            Map<String, BuildIdentityCodec.ClientBuildIdentity> clients,
            Map<String, InferenceIdentityCodec.BackendModelIdentity> backends,
            Map<String, GenerationEvidenceCodec.GenerationRecord> records) {
        validateDigestKeys(
                sources, RasterInputCodec::actionSourceRevisionDigest, "source_revisions");
        validateDigestKeys(targets, ExecutionScopeCodec::targetRegionDigest, "target_regions");
        validateDigestKeys(clients, BuildIdentityCodec::clientBuildIdentityDigest, "client_builds");
        validateDigestKeys(
                backends, InferenceIdentityCodec::backendModelIdentityDigest, "backend_identities");

        Set<String> referencedSources = new LinkedHashSet<>();
        Set<String> referencedTargets = new LinkedHashSet<>();
        Set<String> referencedClients = new LinkedHashSet<>();
        Set<String> referencedBackends = new LinkedHashSet<>();
        for (Map.Entry<String, GenerationEvidenceCodec.GenerationRecord> entry :
                records.entrySet()) {
            String key =
                    CanonicalCodec.requireCanonicalUuid(
                            entry.getKey(), "current_generation_records key");
            GenerationEvidenceCodec.GenerationRecord record = entry.getValue();
            if (!key.equals(record.outputGenerationId())) {
                throw new IllegalArgumentException(
                        "generation record key does not match output_generation_id");
            }
            validateRecordMembership(record, sources, targets, clients, backends);
            referencedSources.add(record.inputEvidence().sourceRevisionDigest());
            referencedTargets.add(record.inputEvidence().targetRegionDigest());
            referencedClients.add(record.clientBuildDigest());
            referencedBackends.add(record.backendIdentityDigest());
        }
        requireExactReferences(sources.keySet(), referencedSources, "source_revisions");
        requireExactReferences(targets.keySet(), referencedTargets, "target_regions");
        requireExactReferences(clients.keySet(), referencedClients, "client_builds");
        requireExactReferences(backends.keySet(), referencedBackends, "backend_identities");
    }

    private static void validateRecordMembership(
            GenerationEvidenceCodec.GenerationRecord record,
            Map<String, RasterInputCodec.ActionSourceRevision> sources,
            Map<String, ExecutionScopeCodec.TargetRegion> targets,
            Map<String, BuildIdentityCodec.ClientBuildIdentity> clients,
            Map<String, InferenceIdentityCodec.BackendModelIdentity> backends) {
        GenerationEvidenceCodec.CommittedInputEvidence evidence = record.inputEvidence();
        RasterInputCodec.ActionSourceRevision source =
                requireReferenced(sources, evidence.sourceRevisionDigest(), "source revision");
        ExecutionScopeCodec.TargetRegion target =
                requireReferenced(targets, evidence.targetRegionDigest(), "target region");
        requireReferenced(clients, record.clientBuildDigest(), "client build");
        InferenceIdentityCodec.BackendModelIdentity backend =
                requireReferenced(backends, record.backendIdentityDigest(), "backend identity");
        requireInputContext(record.output(), evidence, source, target, backend);
    }

    /** Validate complete evidence against the normalized values that will accompany it. */
    static void requireInputContext(
            ExecutionScopeCodec.OutputKey output,
            GenerationEvidenceCodec.CommittedInputEvidence evidence,
            RasterInputCodec.ActionSourceRevision source,
            ExecutionScopeCodec.TargetRegion target,
            InferenceIdentityCodec.BackendModelIdentity backend) {
        if (!evidence.sourceRevisionDigest()
                        .equals(RasterInputCodec.actionSourceRevisionDigest(source))
                || !evidence.targetRegionDigest()
                        .equals(ExecutionScopeCodec.targetRegionDigest(target))) {
            throw new IllegalArgumentException(
                    "input evidence does not match its source revision and target");
        }
        requireOutputContext(output, target, evidence.parentGeometryDigest().isPresent(), backend);
        boolean parentScope = output.scope() instanceof ExecutionScopeCodec.ParentScope;

        if (evidence.sourceView() instanceof RasterInputCodec.SourceView sealed
                && !sealed.viewDigest().equals(RasterInputCodec.sourceViewDigest(sealed))) {
            throw new IllegalArgumentException(
                    "source view digest does not match committed input evidence");
        }
        if (evidence.sourceView() instanceof SourceCaptureCodec.View captured
                && !captured.capture().source().equals(source.pixelSource())) {
            throw new IllegalArgumentException(
                    "captured image does not match committed source revision");
        }
        String modelRasterInputDigest =
                RasterInputCodec.modelRasterInputDigest(
                        evidence.sourceView().viewDigest(),
                        evidence.rasterFrameDigest(),
                        backend.rasterizer());
        if (!evidence.modelRasterInputDigest().equals(modelRasterInputDigest)) {
            throw new IllegalArgumentException(
                    "model raster input digest does not match committed input evidence");
        }
        if (!parentScope) {
            String scopeAcceptanceDigest =
                    ExecutionScopeCodec.scopeAcceptanceDigest(
                            new ExecutionScopeCodec.ScopeAcceptance(target, Optional.empty()));
            if (!evidence.scopeAcceptanceDigest().equals(scopeAcceptanceDigest)) {
                throw new IllegalArgumentException(
                        "field scope acceptance digest does not match target region");
            }
        }

        RasterInputCodec.requireTransformMatchesSource(evidence.rasterTransform(), source);
        SourceIdentityCodec.ImagePlaneIndex plane = evidence.rasterTransform().raster().plane();
        if (!plane.equals(evidence.sourceView().plane()) || !plane.equals(target.plane())) {
            throw new IllegalArgumentException(
                    "source view, raster transform, and target region planes must match");
        }
    }

    /** Validate the same output context before uploaded-file evidence is available. */
    static void requirePlannedInputContext(
            ExecutionScopeCodec.OutputKey output,
            RasterInputCodec.ModelRasterInput input,
            ExecutionScopeCodec.ScopeAcceptance acceptance,
            InferenceIdentityCodec.BackendModelIdentity backend) {
        requireOutputContext(
                output, acceptance.target(), acceptance.parentGeometrySlide().isPresent(), backend);
        if (!input.frame().region().equals(acceptance.target().key())
                || !input.sourceView().plane().equals(acceptance.target().plane())
                || !input.rasterizer().equals(backend.rasterizer())) {
            throw new IllegalArgumentException(
                    "planned input does not match its target and backend");
        }
        // The parser recomputes the nested frame/view/input digests, unlike record constructors.
        RasterInputCodec.parseModelRasterInput(RasterInputCodec.toJson(input));
    }

    private static void requireOutputContext(
            ExecutionScopeCodec.OutputKey output,
            ExecutionScopeCodec.TargetRegion target,
            boolean hasParentGeometry,
            InferenceIdentityCodec.BackendModelIdentity backend) {
        ExecutionScopeCodec.RegionKey outputRegion = ExecutionScopeCodec.regionOf(output.scope());
        if (!outputRegion.equals(target.key())) {
            throw new IllegalArgumentException(
                    "generation output scope does not match its target region");
        }
        boolean parentScope = output.scope() instanceof ExecutionScopeCodec.ParentScope;
        if (parentScope != hasParentGeometry) {
            throw new IllegalArgumentException(
                    "parent_geometry_digest must be present exactly for a parent scope");
        }
        if (!output.producer().moduleRef().equals(backend.module().moduleRef())
                || !output.producer().stepId().equals(backend.modelId())) {
            throw new IllegalArgumentException(
                    "generation output producer does not match backend identity");
        }
    }

    private static <T> Map<String, T> parseDigestMap(
            JsonElement value,
            String path,
            Function<JsonElement, T> parser,
            Function<T, String> digest) {
        JsonObject object = requiredObject(value, path);
        Map<String, T> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = CanonicalCodec.requireSha256(entry.getKey(), path + " key");
            T parsed = parser.apply(entry.getValue());
            if (!key.equals(digest.apply(parsed))) {
                throw new IllegalArgumentException(path + " key does not match value digest");
            }
            result.put(key, parsed);
        }
        return Map.copyOf(result);
    }

    private static Map<String, GenerationEvidenceCodec.GenerationRecord> parseGenerationRecords(
            JsonElement value) {
        JsonObject object = requiredObject(value, "current_generation_records");
        Map<String, GenerationEvidenceCodec.GenerationRecord> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key =
                    CanonicalCodec.requireCanonicalUuid(
                            entry.getKey(), "current_generation_records key");
            GenerationEvidenceCodec.GenerationRecord record =
                    GenerationEvidenceCodec.parseGenerationRecord(entry.getValue());
            if (!key.equals(record.outputGenerationId())) {
                throw new IllegalArgumentException(
                        "generation record key does not match output_generation_id");
            }
            result.put(key, record);
        }
        return Map.copyOf(result);
    }

    private static <T> JsonObject encodeMap(
            Map<String, T> values, Function<T, JsonObject> encoder) {
        JsonObject result = new JsonObject();
        new TreeMap<>(values).forEach((key, value) -> result.add(key, encoder.apply(value)));
        return result;
    }

    private static <T> void validateDigestKeys(
            Map<String, T> values, Function<T, String> digest, String path) {
        for (Map.Entry<String, T> entry : values.entrySet()) {
            String key = CanonicalCodec.requireSha256(entry.getKey(), path + " key");
            if (!key.equals(digest.apply(entry.getValue()))) {
                throw new IllegalArgumentException(path + " key does not match value digest");
            }
        }
    }

    private static <T> T requireReferenced(Map<String, T> values, String key, String path) {
        T value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException(path + " digest is missing from generation store");
        }
        return value;
    }

    private static void requireExactReferences(
            Set<String> actual, Set<String> referenced, String path) {
        if (!actual.equals(referenced)) {
            throw new IllegalArgumentException(path + " contains missing or unreferenced values");
        }
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values, String path) {
        if (values == null) {
            throw new IllegalArgumentException(path + " must not be null");
        }
        Map<String, T> result = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException(path + " keys and values must not be null");
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static <T> void mergeEntries(
            Map<String, T> destination, Map<String, T> source, String path) {
        for (Map.Entry<String, T> entry : source.entrySet()) {
            T existing = destination.putIfAbsent(entry.getKey(), entry.getValue());
            if (existing != null && !existing.equals(entry.getValue())) {
                throw new IllegalArgumentException(path + " contains a conflicting identity key");
            }
        }
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
}
