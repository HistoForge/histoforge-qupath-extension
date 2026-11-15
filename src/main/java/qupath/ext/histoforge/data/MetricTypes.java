package qupath.ext.histoforge.data;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.locationtech.jts.geom.Geometry;
import qupath.ext.histoforge.client.dto.MetricInfo;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.contract.SourceIdentityCodec;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.interfaces.ROI;

/** Grouped immutable contracts for detached metric input, results, cache, and aggregation. */
public final class MetricTypes {

    private MetricTypes() {}

    /** Closed per-metric result shape declared by the script header. */
    public enum MetricType {
        SCALAR,
        DISTRIBUTION
    }

    /** Closed schema-owned aggregation rule. */
    public enum AggregationType {
        SUM,
        MEAN,
        POOL
    }

    /** Complete validated definition of one metric. */
    public record MetricDefinition(
            String name,
            String label,
            MetricType type,
            Optional<String> unit,
            int decimalPlaces,
            AggregationType aggregation) {
        public MetricDefinition {
            name = CanonicalCodec.requireNonblank(name, "metric name");
            label = CanonicalCodec.requireNonblank(label, "metric label");
            type = Objects.requireNonNull(type, "type");
            unit = Objects.requireNonNull(unit, "unit");
            unit = unit.map(value -> CanonicalCodec.requireNonblank(value, "metric unit"));
            decimalPlaces = CanonicalCodec.requireNonnegativeInt32(decimalPlaces, "decimal_places");
            aggregation = Objects.requireNonNull(aggregation, "aggregation");
            if (type == MetricType.SCALAR && aggregation == AggregationType.POOL) {
                throw new IllegalArgumentException("scalar metrics cannot use POOL");
            }
            if (type == MetricType.DISTRIBUTION && aggregation != AggregationType.POOL) {
                throw new IllegalArgumentException("distribution metrics require POOL");
            }
        }

        /** Convert the strict module-header projection into the typed runtime contract. */
        public static MetricDefinition from(MetricInfo info) {
            Objects.requireNonNull(info, "info");
            MetricType type =
                    switch (info.type()) {
                        case "scalar" -> MetricType.SCALAR;
                        case "distribution" -> MetricType.DISTRIBUTION;
                        default ->
                                throw new IllegalArgumentException(
                                        "unsupported metric type " + info.type());
                    };
            AggregationType aggregation =
                    switch (info.aggregation()) {
                        case "sum" -> AggregationType.SUM;
                        case "mean" -> AggregationType.MEAN;
                        case "pool" -> AggregationType.POOL;
                        default ->
                                throw new IllegalArgumentException(
                                        "unsupported metric aggregation " + info.aggregation());
                    };
            return new MetricDefinition(
                    info.name(),
                    info.label(),
                    type,
                    Optional.ofNullable(info.unit()),
                    info.decimals(),
                    aggregation);
        }
    }

    /** Ordered unique metric definitions for one exact metric script. */
    public record MetricSchema(List<MetricDefinition> definitions) {
        public MetricSchema {
            if (definitions == null || definitions.isEmpty()) {
                throw new IllegalArgumentException("metric schema must be nonempty");
            }
            List<MetricDefinition> values = new ArrayList<>(definitions.size());
            Set<String> names = new HashSet<>();
            for (MetricDefinition definition : definitions) {
                MetricDefinition value = Objects.requireNonNull(definition, "metric definition");
                if (!names.add(value.name())) {
                    throw new IllegalArgumentException("metric names must be unique");
                }
                values.add(value);
            }
            definitions = List.copyOf(values);
        }

        /** Build the typed schema from the strict module-header projection. */
        public static MetricSchema from(List<MetricInfo> definitions) {
            Objects.requireNonNull(definitions, "definitions");
            return new MetricSchema(definitions.stream().map(MetricDefinition::from).toList());
        }
    }

    /** Exact trusted metric source, digest, and parsed schema. */
    public record MetricScriptSnapshot(String source, String digest, MetricSchema schema) {
        public MetricScriptSnapshot {
            source = Objects.requireNonNull(source, "source");
            digest = CanonicalCodec.requireSha256(digest, "metrics_script_digest");
            if (!digest.equals(CanonicalCodec.sha256(CanonicalCodec.utf8Bytes(source)))) {
                throw new IllegalArgumentException("metric script digest does not match source");
            }
            schema = Objects.requireNonNull(schema, "schema");
        }
    }

    /** Preserves whether a detached metric object was an annotation or detection. */
    public enum MetricObjectType {
        ANNOTATION,
        DETECTION
    }

    /** Detached supported object fields; never a reference to a live PathObject. */
    public static final class MetricObjectSnapshot {
        private final UUID objectId;
        private final MetricObjectType objectType;
        private final String pathClass;
        private final MetricObjectGeometry geometry;
        private final Optional<UUID> parentObjectId;

        /** Public construction preserves arbitrary validated JTS geometry without conversion. */
        public MetricObjectSnapshot(
                UUID objectId,
                MetricObjectType objectType,
                String pathClass,
                Geometry geometrySlide,
                Optional<UUID> parentObjectId) {
            this.objectId = Objects.requireNonNull(objectId, "object_id");
            this.objectType = Objects.requireNonNull(objectType, "object_type");
            this.pathClass = CanonicalCodec.requirePathClassName(pathClass, "path_class");
            this.geometry = MetricObjectGeometry.copyOf(geometrySlide);
            this.parentObjectId = Objects.requireNonNull(parentObjectId, "parent_object_id");
            if (parentObjectId.filter(objectId::equals).isPresent()) {
                throw new IllegalArgumentException("metric object cannot be its own parent");
            }
        }

        private MetricObjectSnapshot(
                UUID objectId,
                MetricObjectType objectType,
                String pathClass,
                MetricObjectGeometry geometry,
                Optional<UUID> parentObjectId) {
            this.objectId = Objects.requireNonNull(objectId, "object_id");
            this.objectType = Objects.requireNonNull(objectType, "object_type");
            // Owner-thread capture has already validated the exact canonical class text.
            this.pathClass = Objects.requireNonNull(pathClass, "path_class");
            this.geometry = Objects.requireNonNull(geometry, "geometry");
            this.parentObjectId = Objects.requireNonNull(parentObjectId, "parent_object_id");
            if (parentObjectId.filter(objectId::equals).isPresent()) {
                throw new IllegalArgumentException("metric object cannot be its own parent");
            }
        }

        /** Share a validated outline without retaining an editable object or hierarchy. */
        static MetricObjectSnapshot fromCapturedGeometry(
                UUID objectId,
                MetricObjectType objectType,
                String pathClass,
                MetricObjectGeometry geometry,
                Optional<UUID> parentObjectId) {
            return new MetricObjectSnapshot(
                    objectId, objectType, pathClass, geometry, parentObjectId);
        }

        public UUID objectId() {
            return objectId;
        }

        public MetricObjectType objectType() {
            return objectType;
        }

        public String pathClass() {
            return pathClass;
        }

        public Geometry geometrySlide() {
            return geometry.copy();
        }

        public Optional<UUID> parentObjectId() {
            return parentObjectId;
        }

        ROI scriptRoi(ImagePlane plane) {
            return geometry.scriptRoi(plane);
        }

        void writeOutline(DataOutputStream out, ImagePlane plane) throws IOException {
            geometry.writeInput(out, plane);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof MetricObjectSnapshot object
                    && objectId.equals(object.objectId)
                    && objectType == object.objectType
                    && pathClass.equals(object.pathClass)
                    && geometry.equals(object.geometry)
                    && parentObjectId.equals(object.parentObjectId);
        }

        @Override
        public int hashCode() {
            int hash = objectId.hashCode();
            hash = 31 * hash + objectType.hashCode();
            hash = 31 * hash + pathClass.hashCode();
            hash = 31 * hash + geometry.hashCode();
            return 31 * hash + parentObjectId.hashCode();
        }

        @Override
        public String toString() {
            return ("MetricObjectSnapshot[objectId=%s, objectType=%s, pathClass=%s, "
                            + "geometrySlide=%s, parentObjectId=%s]")
                    .formatted(objectId, objectType, pathClass, geometrySlide(), parentObjectId);
        }
    }

    /** One detached target and its complete UUID-sorted biological population. */
    public record MetricRegionSnapshot(
            ExecutionScopeCodec.TargetRegion target, List<MetricObjectSnapshot> objects) {
        public MetricRegionSnapshot {
            target = Objects.requireNonNull(target, "target");
            Objects.requireNonNull(objects, "objects");
            List<MetricObjectSnapshot> values = new ArrayList<>(objects.size());
            UUID previous = null;
            boolean sorted = true;
            for (MetricObjectSnapshot object : objects) {
                MetricObjectSnapshot value = Objects.requireNonNull(object, "metric object");
                if (previous != null && previous.compareTo(value.objectId()) > 0) {
                    sorted = false;
                }
                previous = value.objectId();
                values.add(value);
            }
            if (!sorted) {
                values.sort(Comparator.comparing(MetricObjectSnapshot::objectId));
            }
            previous = null;
            for (MetricObjectSnapshot object : values) {
                if (object.objectId().equals(previous)) {
                    throw new IllegalArgumentException("metric region object UUIDs must be unique");
                }
                previous = object.objectId();
                if (object.parentObjectId()
                        .filter(parent -> !containsObject(values, parent))
                        .isPresent()) {
                    throw new IllegalArgumentException(
                            "detached metric parent must be another region object");
                }
            }
            // This list is private to construction; freeze it without another reference array.
            objects = Collections.unmodifiableList(values);
        }

        private static boolean containsObject(List<MetricObjectSnapshot> objects, UUID id) {
            int low = 0;
            int high = objects.size() - 1;
            while (low <= high) {
                int middle = low + (high - low) / 2;
                int comparison = objects.get(middle).objectId().compareTo(id);
                if (comparison == 0) return true;
                if (comparison < 0) low = middle + 1;
                else high = middle - 1;
            }
            return false;
        }
    }

    /** Complete immutable metric input, with its SHA-256 derived after sorting the regions. */
    public static final class MetricInputDraft {
        private final String metricInputDigest;
        private final BuildIdentityCodec.ClientBuildIdentity clientBuild;
        private final SourceIdentityCodec.PixelCalibration calibration;
        private final InferenceIdentityCodec.ModuleContentIdentity module;
        private final MetricScriptSnapshot script;
        private final List<MetricRegionSnapshot> regions;

        /** Construct an input and reject a supplied digest that does not describe its contents. */
        public MetricInputDraft(
                String metricInputDigest,
                BuildIdentityCodec.ClientBuildIdentity clientBuild,
                SourceIdentityCodec.PixelCalibration calibration,
                InferenceIdentityCodec.ModuleContentIdentity module,
                MetricScriptSnapshot script,
                List<MetricRegionSnapshot> regions) {
            this(
                    clientBuild,
                    calibration,
                    module,
                    script,
                    regions,
                    Optional.of(
                            CanonicalCodec.requireSha256(
                                    metricInputDigest, "metric_input_digest")));
        }

        private MetricInputDraft(
                BuildIdentityCodec.ClientBuildIdentity clientBuild,
                SourceIdentityCodec.PixelCalibration calibration,
                InferenceIdentityCodec.ModuleContentIdentity module,
                MetricScriptSnapshot script,
                List<MetricRegionSnapshot> regions,
                Optional<String> suppliedDigest) {
            this.clientBuild = Objects.requireNonNull(clientBuild, "client_build");
            this.calibration = Objects.requireNonNull(calibration, "calibration");
            this.module = Objects.requireNonNull(module, "module");
            this.script = Objects.requireNonNull(script, "script");
            this.regions = normalizedRegions(regions);
            // Derive the digest once here; neither construction path can accept an unchecked value.
            this.metricInputDigest =
                    MetricInputEncoding.digest(
                            this.clientBuild,
                            this.calibration,
                            this.module,
                            this.script,
                            this.regions);
            if (suppliedDigest.isPresent()
                    && !suppliedDigest.orElseThrow().equals(this.metricInputDigest)) {
                throw new IllegalArgumentException(
                        "metric_input_digest does not match normalized input");
            }
        }

        /** Construct an input and derive its canonical identity. */
        public static MetricInputDraft build(
                BuildIdentityCodec.ClientBuildIdentity clientBuild,
                SourceIdentityCodec.PixelCalibration calibration,
                InferenceIdentityCodec.ModuleContentIdentity module,
                MetricScriptSnapshot script,
                List<MetricRegionSnapshot> regions) {
            return new MetricInputDraft(
                    clientBuild, calibration, module, script, regions, Optional.empty());
        }

        public String metricInputDigest() {
            return metricInputDigest;
        }

        public BuildIdentityCodec.ClientBuildIdentity clientBuild() {
            return clientBuild;
        }

        public SourceIdentityCodec.PixelCalibration calibration() {
            return calibration;
        }

        public InferenceIdentityCodec.ModuleContentIdentity module() {
            return module;
        }

        public MetricScriptSnapshot script() {
            return script;
        }

        public List<MetricRegionSnapshot> regions() {
            return regions;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof MetricInputDraft input
                    && metricInputDigest.equals(input.metricInputDigest)
                    && clientBuild.equals(input.clientBuild)
                    && calibration.equals(input.calibration)
                    && module.equals(input.module)
                    && script.equals(input.script)
                    && regions.equals(input.regions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    metricInputDigest, clientBuild, calibration, module, script, regions);
        }

        @Override
        public String toString() {
            return ("MetricInputDraft[metricInputDigest=%s, clientBuild=%s, calibration=%s, "
                            + "module=%s, script=%s, regions=%s]")
                    .formatted(
                            metricInputDigest, clientBuild, calibration, module, script, regions);
        }
    }

    /** Metric input bound to the semantic image revision represented by its hierarchy capture. */
    public record MetricImageSnapshot(
            MetricInputDraft metricInput, RasterInputCodec.ImageRevisionToken sourceRevision) {
        public MetricImageSnapshot {
            metricInput = Objects.requireNonNull(metricInput, "metric_input");
            sourceRevision = Objects.requireNonNull(sourceRevision, "source_revision");
        }
    }

    /** Prepared snapshot plus the real parent-only repairs derived from it. */
    public record MetricPreparation(
            MetricImageSnapshot snapshot,
            List<HierarchyDelta.ParentAssignment> parentNormalization) {
        public MetricPreparation {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            parentNormalization = immutableUniqueAssignments(parentNormalization);
        }
    }

    /** Present scalar, present distribution, or explicit missing value. */
    public sealed interface MetricValue permits Scalar, Distribution, Missing {}

    /** One present finite scalar; numeric zero remains present. */
    public record Scalar(double value) implements MetricValue {
        public Scalar {
            value = CanonicalCodec.requireFiniteDouble(value, "metric scalar");
        }
    }

    /** One present bounded finite list; an empty list remains present-empty. */
    public record Distribution(List<Double> values) implements MetricValue {
        public Distribution {
            Objects.requireNonNull(values, "values");
            List<Double> normalized = new ArrayList<>(values.size());
            for (Double value : values) {
                double number =
                        CanonicalCodec.requireFiniteDouble(
                                Objects.requireNonNull(value, "distribution value"),
                                "distribution value");
                // Double is immutable; only negative zero needs a replacement after validation.
                if (Double.doubleToRawLongBits(number) == Double.doubleToRawLongBits(value)) {
                    normalized.add(value);
                } else {
                    normalized.add(number);
                }
            }
            values = Collections.unmodifiableList(normalized);
        }
    }

    /** Explicit missing value; never converted to zero or empty. */
    public record Missing() implements MetricValue {}

    /** Exact schema-name-to-typed-value mapping for one target region. */
    public record MetricRegionResult(Map<String, MetricValue> values) {
        public MetricRegionResult {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("metric region values must be nonempty");
            }
            Map<String, MetricValue> copy = new LinkedHashMap<>();
            values.forEach(
                    (name, value) -> {
                        String key = CanonicalCodec.requireNonblank(name, "metric name");
                        if (copy.putIfAbsent(key, Objects.requireNonNull(value, "metric value"))
                                != null) {
                            throw new IllegalArgumentException("metric names must be unique");
                        }
                    });
            values = Collections.unmodifiableMap(copy);
        }
    }

    /** One revision-bound image evaluation with disjoint results and failures. */
    public record MetricImageResult(
            String metricInputDigest,
            RasterInputCodec.ImageRevisionToken sourceRevision,
            Map<ExecutionScopeCodec.RegionKey, MetricRegionResult> regionResults,
            Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> regionFailures) {
        public MetricImageResult {
            metricInputDigest =
                    CanonicalCodec.requireSha256(metricInputDigest, "metric_input_digest");
            sourceRevision = Objects.requireNonNull(sourceRevision, "source_revision");
            regionResults = normalizedRegionMap(regionResults, "region_results");
            regionFailures = normalizedRegionMap(regionFailures, "region_failures");
            Set<ExecutionScopeCodec.RegionKey> overlap = new HashSet<>(regionResults.keySet());
            overlap.retainAll(regionFailures.keySet());
            if (!overlap.isEmpty()) {
                throw new IllegalArgumentException(
                        "metric region results and failures must be disjoint");
            }
            if (regionResults.isEmpty() && regionFailures.isEmpty()) {
                throw new IllegalArgumentException("metric image result must contain regions");
            }
        }

        /** True exactly when every requested region produced a result. */
        public boolean complete() {
            return regionFailures.isEmpty();
        }
    }

    /** Streaming Welford state for pooled mean and population variance. */
    public record DistributionStats(
            long sampleCount, Optional<Double> mean, Optional<Double> sumSquaredDeviations) {
        public DistributionStats {
            sampleCount = CanonicalCodec.requireNonnegativeSafeInteger(sampleCount, "sample_count");
            mean = finiteOptional(mean, "mean");
            sumSquaredDeviations = finiteOptional(sumSquaredDeviations, "sum_squared_deviations");
            if (sampleCount == 0 && (mean.isPresent() || sumSquaredDeviations.isPresent())) {
                throw new IllegalArgumentException("empty distribution stats have no moments");
            }
            if (sampleCount > 0 && (mean.isEmpty() || sumSquaredDeviations.isEmpty())) {
                throw new IllegalArgumentException("nonempty distribution stats require moments");
            }
            if (sumSquaredDeviations.orElse(0.0) < 0.0) {
                throw new IllegalArgumentException("sum_squared_deviations must be nonnegative");
            }
        }
    }

    /** Requested, present, missing, and failed region counts for one aggregate. */
    public record MetricCoverage(
            int requestedRegions, int presentRegions, int missingRegions, int failedRegions) {
        public MetricCoverage {
            requestedRegions = nonnegative(requestedRegions, "requested_regions");
            presentRegions = nonnegative(presentRegions, "present_regions");
            missingRegions = nonnegative(missingRegions, "missing_regions");
            failedRegions = nonnegative(failedRegions, "failed_regions");
            if (requestedRegions
                    != Math.addExact(
                            presentRegions, Math.addExact(missingRegions, failedRegions))) {
                throw new IllegalArgumentException("metric coverage counts do not close");
            }
        }
    }

    /** Closed aggregate family preserving scalar, distribution, and missing. */
    public sealed interface AggregatedMetricValue
            permits AggregatedScalar, AggregatedDistribution, AggregatedMissing {}

    /** Schema-directed scalar sum or unweighted regional mean. */
    public record AggregatedScalar(double value) implements AggregatedMetricValue {
        public AggregatedScalar {
            value = CanonicalCodec.requireFiniteDouble(value, "aggregated scalar");
        }
    }

    /** Pooled present distribution represented by streaming statistics. */
    public record AggregatedDistribution(DistributionStats statistics)
            implements AggregatedMetricValue {
        public AggregatedDistribution {
            statistics = Objects.requireNonNull(statistics, "statistics");
        }
    }

    /** No present values were available; never converted to zero. */
    public record AggregatedMissing() implements AggregatedMetricValue {}

    /** One named aggregate paired with its typed value and coverage. */
    public record AggregatedMetric(
            String name, AggregatedMetricValue value, MetricCoverage coverage) {
        public AggregatedMetric {
            name = CanonicalCodec.requireNonblank(name, "metric name");
            value = Objects.requireNonNull(value, "value");
            coverage = Objects.requireNonNull(coverage, "coverage");
        }
    }

    /** Process-local cache identity valid only after a fresh authoritative revision check. */
    public record MetricCacheKey(
            RasterInputCodec.ImageRevisionToken sourceRevision, String metricInputDigest) {
        public MetricCacheKey {
            sourceRevision = Objects.requireNonNull(sourceRevision, "source_revision");
            metricInputDigest =
                    CanonicalCodec.requireSha256(metricInputDigest, "metric_input_digest");
        }
    }

    private static List<MetricRegionSnapshot> normalizedRegions(
            List<MetricRegionSnapshot> regions) {
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException("metric regions must be nonempty");
        }
        List<MetricRegionSnapshot> values = new ArrayList<>(regions.size());
        Set<ExecutionScopeCodec.RegionKey> keys = new HashSet<>();
        for (MetricRegionSnapshot region : regions) {
            MetricRegionSnapshot value = Objects.requireNonNull(region, "metric region");
            if (!keys.add(value.target().key())) {
                throw new IllegalArgumentException("metric region keys must be unique");
            }
            values.add(value);
        }
        values.sort(
                (left, right) ->
                        java.util.Arrays.compareUnsigned(
                                CanonicalCodec.canonicalBytes(
                                        ExecutionScopeCodec.toJson(left.target().key())),
                                CanonicalCodec.canonicalBytes(
                                        ExecutionScopeCodec.toJson(right.target().key()))));
        return List.copyOf(values);
    }

    private static List<HierarchyDelta.ParentAssignment> immutableUniqueAssignments(
            List<HierarchyDelta.ParentAssignment> assignments) {
        Objects.requireNonNull(assignments, "parent_normalization");
        List<HierarchyDelta.ParentAssignment> values = new ArrayList<>(assignments.size());
        Set<UUID> objectIds = new HashSet<>();
        for (HierarchyDelta.ParentAssignment assignment : assignments) {
            HierarchyDelta.ParentAssignment value =
                    Objects.requireNonNull(assignment, "parent assignment");
            if (!objectIds.add(value.objectId())) {
                throw new IllegalArgumentException(
                        "parent normalization object IDs must be unique");
            }
            values.add(value);
        }
        values.sort(Comparator.comparing(HierarchyDelta.ParentAssignment::objectId));
        return List.copyOf(values);
    }

    private static <T> Map<ExecutionScopeCodec.RegionKey, T> normalizedRegionMap(
            Map<ExecutionScopeCodec.RegionKey, T> values, String path) {
        Objects.requireNonNull(values, path);
        List<Map.Entry<ExecutionScopeCodec.RegionKey, T>> entries =
                new ArrayList<>(values.entrySet());
        entries.sort(
                (left, right) ->
                        java.util.Arrays.compareUnsigned(
                                CanonicalCodec.canonicalBytes(
                                        ExecutionScopeCodec.toJson(left.getKey())),
                                CanonicalCodec.canonicalBytes(
                                        ExecutionScopeCodec.toJson(right.getKey()))));
        Map<ExecutionScopeCodec.RegionKey, T> result = new LinkedHashMap<>();
        for (Map.Entry<ExecutionScopeCodec.RegionKey, T> entry : entries) {
            ExecutionScopeCodec.RegionKey key =
                    Objects.requireNonNull(entry.getKey(), path + " key");
            T value = Objects.requireNonNull(entry.getValue(), path + " value");
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException(path + " keys must be unique");
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Optional<Double> finiteOptional(Optional<Double> value, String path) {
        Objects.requireNonNull(value, path);
        return value.map(number -> CanonicalCodec.requireFiniteDouble(number, path));
    }

    private static int nonnegative(int value, String path) {
        return CanonicalCodec.requireNonnegativeInt32(value, path);
    }
}
