package qupath.ext.histoforge.export;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationEvidenceCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceCaptureCodec;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.pipeline.ImageLifecycle;

/** Grouped immutable contracts for one fail-closed metrics workbook transaction. */
public final class ExportTypes {

    public static final int MANIFEST_SCHEMA_VERSION = 3;

    private ExportTypes() {}

    /** Closed, deliberately narrow statement made by a published workbook. */
    public enum ExportClaim {
        CAPTURED_HIERARCHY_METRICS
    }

    /** One exact executable module and its nonempty metric-region selection. */
    public record ExportModuleSelection(
            ModuleSnapshot module,
            ActionSelectionCodec.RegionSelection regions,
            Optional<String> annotationClass) {
        public ExportModuleSelection(
                ModuleSnapshot module, ActionSelectionCodec.RegionSelection regions) {
            this(module, regions, Optional.empty());
        }

        public ExportModuleSelection {
            module = Objects.requireNonNull(module, "module");
            regions = Objects.requireNonNull(regions, "regions");
            annotationClass = Objects.requireNonNull(annotationClass, "annotationClass");
            if (module.metrics().isEmpty()) {
                throw new IllegalArgumentException("export module has no metrics.groovy");
            }
        }
    }

    /** One project image's explicit module selections and study fields. */
    public record ExportImageSelection(
            String imageId, List<ExportModuleSelection> modules, Map<String, String> studyFields) {
        public ExportImageSelection {
            imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
            modules = immutableUnique(modules, selection -> selection.module().module(), "modules");
            studyFields = immutableStrings(studyFields, "study_fields");
        }
    }

    /** Destination behavior when the requested workbook path already exists. */
    public enum ExportOverwritePolicy {
        FAIL_IF_EXISTS,
        REPLACE_EXISTING
    }

    /** Complete immutable intent for one atomic workbook publication. */
    public record ExportRequest(
            UUID exportId,
            Path destination,
            ExportOverwritePolicy overwrite,
            List<ExportImageSelection> images) {
        public ExportRequest {
            exportId = Objects.requireNonNull(exportId, "exportId");
            destination = Objects.requireNonNull(destination, "destination").toAbsolutePath();
            overwrite = Objects.requireNonNull(overwrite, "overwrite");
            images = immutableUnique(images, ExportImageSelection::imageId, "images");
        }
    }

    /** Current attributed-object and successfully linked-object counts. */
    public record ProvenanceAuditSummary(long attributedObjects, long generationLinkedObjects) {
        public ProvenanceAuditSummary {
            attributedObjects =
                    CanonicalCodec.requireNonnegativeSafeInteger(
                            attributedObjects, "attributed_objects");
            generationLinkedObjects =
                    CanonicalCodec.requireNonnegativeSafeInteger(
                            generationLinkedObjects, "generation_linked_objects");
            if (generationLinkedObjects > attributedObjects) {
                throw new IllegalArgumentException(
                        "generation-linked object count exceeds attributed object count");
            }
        }
    }

    /** Bounded normalized generation evidence linked by current attributed objects. */
    public record ExportGenerationEvidence(
            Map<String, RasterInputCodec.ActionSourceRevision> sourceRevisions,
            Map<String, ExecutionScopeCodec.TargetRegion> targetRegions,
            Map<String, BuildIdentityCodec.ClientBuildIdentity> clientBuilds,
            Map<String, InferenceIdentityCodec.BackendModelIdentity> backendIdentities,
            List<GenerationEvidenceCodec.GenerationRecord> linkedGenerationRecords,
            Set<String> missingGenerationRecordIds,
            String evidenceDigest) {
        public ExportGenerationEvidence {
            sourceRevisions = immutableMap(sourceRevisions, "source_revisions");
            targetRegions = immutableMap(targetRegions, "target_regions");
            clientBuilds = immutableMap(clientBuilds, "client_builds");
            backendIdentities = immutableMap(backendIdentities, "backend_identities");
            linkedGenerationRecords =
                    immutableUniqueAllowEmpty(
                            linkedGenerationRecords,
                            GenerationEvidenceCodec.GenerationRecord::outputGenerationId,
                            "linked_generation_records");
            List<GenerationEvidenceCodec.GenerationRecord> sorted =
                    new ArrayList<>(linkedGenerationRecords);
            sorted.sort(
                    Comparator.comparing(
                            GenerationEvidenceCodec.GenerationRecord::outputGenerationId));
            linkedGenerationRecords = List.copyOf(sorted);
            Objects.requireNonNull(missingGenerationRecordIds, "missingGenerationRecordIds");
            Set<String> missing = new TreeSet<>();
            for (String value : missingGenerationRecordIds) {
                missing.add(
                        CanonicalCodec.requireCanonicalUuid(
                                value, "missing_generation_record_ids item"));
            }
            missingGenerationRecordIds = Collections.unmodifiableSet(new LinkedHashSet<>(missing));
            evidenceDigest = CanonicalCodec.requireSha256(evidenceDigest, "evidence_digest");
        }
    }

    /** Exact metric input and current audit evidence for one selected module. */
    public record ExportModuleEvidence(
            String metricInputDigest,
            InferenceIdentityCodec.ModuleContentIdentity module,
            String metricsScriptDigest,
            List<ExecutionScopeCodec.TargetRegion> regions,
            ProvenanceAuditSummary provenance,
            ExportGenerationEvidence generationEvidence) {
        public ExportModuleEvidence {
            metricInputDigest =
                    CanonicalCodec.requireSha256(metricInputDigest, "metric_input_digest");
            module = Objects.requireNonNull(module, "module");
            metricsScriptDigest =
                    CanonicalCodec.requireSha256(metricsScriptDigest, "metrics_script_digest");
            if (regions == null || regions.isEmpty()) {
                throw new IllegalArgumentException("export evidence regions must be nonempty");
            }
            List<ExecutionScopeCodec.TargetRegion> sorted = new ArrayList<>();
            regions.forEach(region -> sorted.add(Objects.requireNonNull(region, "target region")));
            sorted.sort(
                    Comparator.comparing(
                            region ->
                                    CanonicalCodec.sha256(
                                            CanonicalCodec.canonicalBytes(
                                                    ExecutionScopeCodec.toJson(region)))));
            regions = List.copyOf(sorted);
            provenance = Objects.requireNonNull(provenance, "provenance");
            generationEvidence = Objects.requireNonNull(generationEvidence, "generationEvidence");
        }
    }

    /** One truthful authoritative image capture and every selected module evaluated from it. */
    public record ExportImageEvidence(
            Instant capturedAt,
            ImageLifecycle.ImageDataMaterialization materialization,
            RasterInputCodec.ImageRevisionToken sourceRevision,
            Map<String, String> studyFields,
            List<ExportModuleEvidence> modules) {
        public ExportImageEvidence {
            capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
            materialization = Objects.requireNonNull(materialization, "materialization");
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            studyFields = immutableStrings(studyFields, "study_fields");
            modules = immutableUnique(modules, ExportModuleEvidence::module, "modules");
        }
    }

    /** Long-lived evidence represented entirely by normalized workbook cells. */
    public record ExportManifest(
            int schemaVersion,
            UUID exportId,
            Instant createdAt,
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            ExportClaim claim,
            List<ExportImageEvidence> images) {
        public ExportManifest {
            if (schemaVersion < 1 || schemaVersion > MANIFEST_SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported export manifest schema_version");
            }
            exportId = Objects.requireNonNull(exportId, "exportId");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            clientBuild = Objects.requireNonNull(clientBuild, "clientBuild");
            claim = Objects.requireNonNull(claim, "claim");
            images = immutableUnique(images, i -> i.sourceRevision().imageId(), "images");
            // The export embeds generation records directly, without the generation-store version.
            if (schemaVersion == 1
                    && images.stream()
                            .flatMap(image -> image.modules().stream())
                            .flatMap(
                                    module ->
                                            module
                                                    .generationEvidence()
                                                    .linkedGenerationRecords()
                                                    .stream())
                            .anyMatch(
                                    record ->
                                            record.inputEvidence().sourceView()
                                                    instanceof SourceCaptureCodec.View)) {
                throw new IllegalArgumentException(
                        "captured source evidence requires export manifest schema 2");
            }
        }
    }

    /** Closed workbook bytes plus their final destination and typed evidence source. */
    public record ExportArtifact(
            Path destination, long sizeBytes, String sha256, ExportManifest manifest) {
        public ExportArtifact {
            destination = Objects.requireNonNull(destination, "destination").toAbsolutePath();
            sizeBytes = CanonicalCodec.requireNonnegativeSafeInteger(sizeBytes, "size_bytes");
            sha256 = CanonicalCodec.requireSha256(sha256, "sha256");
            manifest = Objects.requireNonNull(manifest, "manifest");
        }
    }

    /** Explicit cell semantic preserving numeric zero, present-empty, and missing. */
    public enum WorkbookValueStatus {
        PRESENT,
        PRESENT_EMPTY,
        MISSING
    }

    private static Map<String, String> immutableStrings(Map<String, String> values, String path) {
        Objects.requireNonNull(values, path);
        Map<String, String> copy = new TreeMap<>();
        values.forEach(
                (key, value) ->
                        copy.put(
                                CanonicalCodec.requireNonblank(key, path + " key"),
                                Objects.requireNonNull(value, path + " value")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values, String path) {
        Objects.requireNonNull(values, path);
        Map<String, T> copy = new TreeMap<>();
        values.forEach(
                (key, value) ->
                        copy.put(
                                CanonicalCodec.requireSha256(key, path + " key"),
                                Objects.requireNonNull(value, path + " value")));
        return Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    private static <T, K> List<T> immutableUnique(List<T> values, Function<T, K> key, String path) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(path + " must be nonempty");
        }
        return immutableUniqueAllowEmpty(values, key, path);
    }

    private static <T, K> List<T> immutableUniqueAllowEmpty(
            List<T> values, Function<T, K> key, String path) {
        Objects.requireNonNull(values, path);
        List<T> copy = new ArrayList<>();
        Set<K> keys = new HashSet<>();
        for (T value : values) {
            T item = Objects.requireNonNull(value, path + " item");
            if (!keys.add(Objects.requireNonNull(key.apply(item), path + " key"))) {
                throw new IllegalArgumentException(path + " must have unique keys");
            }
            copy.add(item);
        }
        return List.copyOf(copy);
    }
}
