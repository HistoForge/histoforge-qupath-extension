package qupath.ext.histoforge.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.export.ExportTypes.ExportClaim;
import qupath.ext.histoforge.export.ExportTypes.ExportGenerationEvidence;
import qupath.ext.histoforge.export.ExportTypes.ExportImageEvidence;
import qupath.ext.histoforge.export.ExportTypes.ExportManifest;
import qupath.ext.histoforge.export.ExportTypes.ExportModuleEvidence;
import qupath.ext.histoforge.export.ExportTypes.ProvenanceAuditSummary;
import qupath.ext.histoforge.pipeline.ImageLifecycle;

/** Sole canonical JSON owner for the typed evidence represented in an export workbook. */
public final class ExportManifestCodec {

    private static final Set<String> MANIFEST_FIELDS =
            Set.of("schema_version", "export_id", "created_at", "client_build", "claim", "images");
    private static final Set<String> IMAGE_FIELDS =
            Set.of("captured_at", "materialization", "source_revision", "study_fields", "modules");
    private static final Set<String> MODULE_FIELDS =
            Set.of(
                    "metric_input_digest",
                    "module",
                    "metrics_script_digest",
                    "regions",
                    "provenance",
                    "generation_evidence");
    private static final Set<String> PROVENANCE_FIELDS =
            Set.of("attributed_objects", "generation_linked_objects");
    private static final Set<String> GENERATION_FIELDS =
            Set.of(
                    "source_revisions",
                    "target_regions",
                    "client_builds",
                    "backend_identities",
                    "linked_generation_records",
                    "missing_generation_record_ids",
                    "evidence_digest");

    private ExportManifestCodec() {}

    /**
     * Parse current evidence or a historical schema-1/2 export, retaining its version and opaque
     * Metrics fingerprint. Historical fingerprints are not recomputed with the current algorithm.
     */
    public static ExportManifest parse(JsonElement value) {
        JsonObject object = exactObject(value, MANIFEST_FIELDS, "export manifest");
        JsonArray images = requiredArray(object.get("images"), "images");
        List<ExportImageEvidence> parsedImages = new ArrayList<>(images.size());
        images.forEach(item -> parsedImages.add(parseImage(item)));
        return new ExportManifest(
                CanonicalCodec.requireInt32(object.get("schema_version"), "schema_version"),
                UUID.fromString(
                        CanonicalCodec.requireCanonicalUuid(
                                requiredString(object, "export_id"), "export_id")),
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "created_at"), "created_at"),
                BuildIdentityCodec.parseClientBuildIdentity(object.get("client_build")),
                parseEnum(ExportClaim.class, requiredString(object, "claim"), "claim"),
                parsedImages);
    }

    /** Encode one manifest with exact lower-snake field names and declared enum spellings. */
    public static JsonObject toJson(ExportManifest manifest) {
        JsonObject value = new JsonObject();
        value.addProperty("schema_version", manifest.schemaVersion());
        value.addProperty("export_id", manifest.exportId().toString());
        value.addProperty(
                "created_at", CanonicalCodec.rfc3339Millis(manifest.createdAt(), "created_at"));
        value.add("client_build", BuildIdentityCodec.toJson(manifest.clientBuild()));
        value.addProperty("claim", manifest.claim().name());
        JsonArray images = new JsonArray();
        manifest.images().forEach(image -> images.add(toJson(image)));
        value.add("images", images);
        return value;
    }

    /** Canonical JCS bytes whose tree is represented by the evidence worksheet. */
    public static byte[] canonicalBytes(ExportManifest manifest) {
        return CanonicalCodec.canonicalBytes(toJson(manifest));
    }

    /** Build and digest a closed normalized generation-evidence value. */
    public static ExportGenerationEvidence generationEvidence(
            GenerationStoreCodec.GenerationStore store, Set<String> missingGenerationRecordIds) {
        List<GenerationEvidenceCodec.GenerationRecord> records =
                store.currentGenerationRecords().values().stream()
                        .sorted(
                                java.util.Comparator.comparing(
                                        GenerationEvidenceCodec.GenerationRecord
                                                ::outputGenerationId))
                        .toList();
        String digest =
                generationEvidenceDigest(
                        store.sourceRevisions(),
                        store.targetRegions(),
                        store.clientBuilds(),
                        store.backendIdentities(),
                        records,
                        missingGenerationRecordIds);
        return new ExportGenerationEvidence(
                store.sourceRevisions(),
                store.targetRegions(),
                store.clientBuilds(),
                store.backendIdentities(),
                records,
                missingGenerationRecordIds,
                digest);
    }

    /** Recompute and require the self-digest on one generation-evidence value. */
    public static void requireValid(ExportGenerationEvidence evidence) {
        String expected =
                generationEvidenceDigest(
                        evidence.sourceRevisions(),
                        evidence.targetRegions(),
                        evidence.clientBuilds(),
                        evidence.backendIdentities(),
                        evidence.linkedGenerationRecords(),
                        evidence.missingGenerationRecordIds());
        if (!expected.equals(evidence.evidenceDigest())) {
            throw new IllegalArgumentException(
                    "evidence_digest does not match normalized generation evidence");
        }
        new GenerationStoreCodec.GenerationStore(
                GenerationStoreCodec.SCHEMA_VERSION,
                evidence.sourceRevisions(),
                evidence.targetRegions(),
                evidence.clientBuilds(),
                evidence.backendIdentities(),
                recordsById(evidence.linkedGenerationRecords()));
    }

    private static ExportImageEvidence parseImage(JsonElement value) {
        JsonObject object = exactObject(value, IMAGE_FIELDS, "export image evidence");
        JsonArray modules = requiredArray(object.get("modules"), "modules");
        List<ExportModuleEvidence> parsedModules = new ArrayList<>(modules.size());
        modules.forEach(item -> parsedModules.add(parseModule(item)));
        return new ExportImageEvidence(
                CanonicalCodec.requireRfc3339Millis(
                        requiredString(object, "captured_at"), "captured_at"),
                parseEnum(
                        ImageLifecycle.ImageDataMaterialization.class,
                        requiredString(object, "materialization"),
                        "materialization"),
                RasterInputCodec.parseImageRevisionToken(object.get("source_revision")),
                parseStringMap(object.get("study_fields"), "study_fields"),
                parsedModules);
    }

    private static ExportModuleEvidence parseModule(JsonElement value) {
        JsonObject object = exactObject(value, MODULE_FIELDS, "export module evidence");
        JsonArray regions = requiredArray(object.get("regions"), "regions");
        List<ExecutionScopeCodec.TargetRegion> parsedRegions = new ArrayList<>(regions.size());
        regions.forEach(item -> parsedRegions.add(ExecutionScopeCodec.parseTargetRegion(item)));
        return new ExportModuleEvidence(
                CanonicalCodec.requireSha256(
                        requiredString(object, "metric_input_digest"), "metric_input_digest"),
                InferenceIdentityCodec.parseModuleContentIdentity(object.get("module")),
                CanonicalCodec.requireSha256(
                        requiredString(object, "metrics_script_digest"), "metrics_script_digest"),
                parsedRegions,
                parseProvenance(object.get("provenance")),
                parseGenerationEvidence(object.get("generation_evidence")));
    }

    private static ProvenanceAuditSummary parseProvenance(JsonElement value) {
        JsonObject object = exactObject(value, PROVENANCE_FIELDS, "provenance audit summary");
        return new ProvenanceAuditSummary(
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("attributed_objects"), "attributed_objects"),
                CanonicalCodec.requireNonnegativeSafeInteger(
                        object.get("generation_linked_objects"), "generation_linked_objects"));
    }

    private static ExportGenerationEvidence parseGenerationEvidence(JsonElement value) {
        JsonObject object = exactObject(value, GENERATION_FIELDS, "generation evidence");
        Map<String, RasterInputCodec.ActionSourceRevision> sources =
                parseMap(
                        object.get("source_revisions"),
                        "source_revisions",
                        RasterInputCodec::parseActionSourceRevision);
        Map<String, ExecutionScopeCodec.TargetRegion> targets =
                parseMap(
                        object.get("target_regions"),
                        "target_regions",
                        ExecutionScopeCodec::parseTargetRegion);
        Map<String, BuildIdentityCodec.ClientBuildIdentity> clients =
                parseMap(
                        object.get("client_builds"),
                        "client_builds",
                        BuildIdentityCodec::parseClientBuildIdentity);
        Map<String, InferenceIdentityCodec.BackendModelIdentity> backends =
                parseMap(
                        object.get("backend_identities"),
                        "backend_identities",
                        InferenceIdentityCodec::parseBackendModelIdentity);
        JsonArray records =
                requiredArray(object.get("linked_generation_records"), "linked_generation_records");
        List<GenerationEvidenceCodec.GenerationRecord> parsedRecords =
                new ArrayList<>(records.size());
        records.forEach(
                item -> parsedRecords.add(GenerationEvidenceCodec.parseGenerationRecord(item)));
        JsonArray missing =
                requiredArray(
                        object.get("missing_generation_record_ids"),
                        "missing_generation_record_ids");
        Set<String> parsedMissing = new LinkedHashSet<>();
        missing.forEach(
                item ->
                        parsedMissing.add(
                                CanonicalCodec.requireCanonicalUuid(
                                        requiredString(item, "missing generation ID"),
                                        "missing_generation_record_ids item")));
        ExportGenerationEvidence evidence =
                new ExportGenerationEvidence(
                        sources,
                        targets,
                        clients,
                        backends,
                        parsedRecords,
                        parsedMissing,
                        requiredString(object, "evidence_digest"));
        requireValid(evidence);
        return evidence;
    }

    private static JsonObject toJson(ExportImageEvidence evidence) {
        JsonObject value = new JsonObject();
        value.addProperty(
                "captured_at", CanonicalCodec.rfc3339Millis(evidence.capturedAt(), "captured_at"));
        value.addProperty("materialization", evidence.materialization().name());
        value.add("source_revision", RasterInputCodec.toJson(evidence.sourceRevision()));
        value.add("study_fields", toJson(evidence.studyFields()));
        JsonArray modules = new JsonArray();
        evidence.modules().forEach(module -> modules.add(toJson(module)));
        value.add("modules", modules);
        return value;
    }

    private static JsonObject toJson(ExportModuleEvidence evidence) {
        JsonObject value = new JsonObject();
        value.addProperty("metric_input_digest", evidence.metricInputDigest());
        value.add("module", InferenceIdentityCodec.toJson(evidence.module()));
        value.addProperty("metrics_script_digest", evidence.metricsScriptDigest());
        JsonArray regions = new JsonArray();
        evidence.regions().forEach(region -> regions.add(ExecutionScopeCodec.toJson(region)));
        value.add("regions", regions);
        value.add("provenance", toJson(evidence.provenance()));
        value.add("generation_evidence", toJson(evidence.generationEvidence()));
        return value;
    }

    private static JsonObject toJson(ProvenanceAuditSummary summary) {
        JsonObject value = new JsonObject();
        value.addProperty("attributed_objects", summary.attributedObjects());
        value.addProperty("generation_linked_objects", summary.generationLinkedObjects());
        return value;
    }

    private static JsonObject toJson(ExportGenerationEvidence evidence) {
        requireValid(evidence);
        JsonObject value = generationEvidencePreimage(evidence);
        value.addProperty("evidence_digest", evidence.evidenceDigest());
        return value;
    }

    private static String generationEvidenceDigest(
            Map<String, RasterInputCodec.ActionSourceRevision> sources,
            Map<String, ExecutionScopeCodec.TargetRegion> targets,
            Map<String, BuildIdentityCodec.ClientBuildIdentity> clients,
            Map<String, InferenceIdentityCodec.BackendModelIdentity> backends,
            List<GenerationEvidenceCodec.GenerationRecord> records,
            Set<String> missing) {
        return CanonicalCodec.canonicalDigest(
                generationEvidencePreimage(
                        new ExportGenerationEvidence(
                                sources,
                                targets,
                                clients,
                                backends,
                                records,
                                missing,
                                "0".repeat(64))));
    }

    private static JsonObject generationEvidencePreimage(ExportGenerationEvidence evidence) {
        JsonObject value = new JsonObject();
        value.add("source_revisions", toJson(evidence.sourceRevisions(), RasterInputCodec::toJson));
        value.add("target_regions", toJson(evidence.targetRegions(), ExecutionScopeCodec::toJson));
        value.add("client_builds", toJson(evidence.clientBuilds(), BuildIdentityCodec::toJson));
        value.add(
                "backend_identities",
                toJson(evidence.backendIdentities(), InferenceIdentityCodec::toJson));
        JsonArray records = new JsonArray();
        evidence.linkedGenerationRecords()
                .forEach(record -> records.add(GenerationEvidenceCodec.toJson(record)));
        value.add("linked_generation_records", records);
        JsonArray missing = new JsonArray();
        evidence.missingGenerationRecordIds().forEach(missing::add);
        value.add("missing_generation_record_ids", missing);
        return value;
    }

    private static Map<String, GenerationEvidenceCodec.GenerationRecord> recordsById(
            List<GenerationEvidenceCodec.GenerationRecord> records) {
        Map<String, GenerationEvidenceCodec.GenerationRecord> result = new LinkedHashMap<>();
        records.forEach(record -> result.put(record.outputGenerationId(), record));
        return result;
    }

    private static <T> JsonObject toJson(Map<String, T> values, Function<T, JsonObject> encoder) {
        JsonObject result = new JsonObject();
        values.forEach((key, value) -> result.add(key, encoder.apply(value)));
        return result;
    }

    private static JsonObject toJson(Map<String, String> values) {
        JsonObject result = new JsonObject();
        values.forEach(result::addProperty);
        return result;
    }

    private static <T> Map<String, T> parseMap(
            JsonElement value, String path, Function<JsonElement, T> parser) {
        JsonObject object = requiredObject(value, path);
        Map<String, T> result = new LinkedHashMap<>();
        object.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            String key =
                                    CanonicalCodec.requireSha256(entry.getKey(), path + " key");
                            result.put(key, parser.apply(entry.getValue()));
                        });
        return result;
    }

    private static Map<String, String> parseStringMap(JsonElement value, String path) {
        JsonObject object = requiredObject(value, path);
        Map<String, String> result = new LinkedHashMap<>();
        object.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry ->
                                result.put(
                                        CanonicalCodec.requireNonblank(
                                                entry.getKey(), path + " key"),
                                        requiredString(entry.getValue(), path + " value")));
        return result;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String path) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(path + " has an unknown enum value", failure);
        }
    }

    private static JsonObject exactObject(JsonElement value, Set<String> fields, String path) {
        JsonObject object = requiredObject(value, path);
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException(path + " has missing or unknown fields");
        }
        return object;
    }

    private static JsonObject requiredObject(JsonElement value, String path) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonElement value, String path) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String field) {
        return requiredString(object.get(field), field);
    }

    private static String requiredString(JsonElement value, String path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return value.getAsString();
    }
}
