package qupath.ext.histoforge.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.client.ModuleRef;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionLimitsCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.data.MetricTypes.Distribution;
import qupath.ext.histoforge.data.MetricTypes.MetricDefinition;
import qupath.ext.histoforge.data.MetricTypes.MetricImageResult;
import qupath.ext.histoforge.data.MetricTypes.MetricInputDraft;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricPreparation;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionResult;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricType;
import qupath.ext.histoforge.data.MetricTypes.MetricValue;
import qupath.ext.histoforge.data.MetricTypes.Missing;
import qupath.ext.histoforge.data.MetricTypes.Scalar;
import qupath.ext.histoforge.pipeline.RunResults;
import qupath.ext.histoforge.pipeline.ScriptCalibrationGate;
import qupath.ext.histoforge.spatial.HierarchyDelta;

/** Detached metric execution, strict result validation, and exact-key process-local reuse. */
public final class MetricsEngine {

    private static final Logger logger = LoggerFactory.getLogger(MetricsEngine.class);
    private final ScriptInvoker scriptInvoker;
    private final Map<ImageModuleKey, CacheEntry> latestComplete = new ConcurrentHashMap<>();

    /** Use compiled exact-source Groovy with a fresh script instance for every region. */
    public MetricsEngine() {
        this(new MetricScriptRunner());
    }

    /** Test seam that preserves engine-owned validation and cache behavior. */
    MetricsEngine(ScriptInvoker scriptInvoker) {
        this.scriptInvoker = Objects.requireNonNull(scriptInvoker, "scriptInvoker");
    }

    @FunctionalInterface
    interface ScriptInvoker {
        /** Complete any image-wide prerequisite before regional failure isolation begins. */
        default void prepare(MetricScriptSnapshot script) {}

        Object run(
                MetricScriptSnapshot script,
                MetricRegionSnapshot region,
                String imageId,
                double pixelWidthMicrons,
                double pixelHeightMicrons);
    }

    /** Detached, uncommitted values that must still be bound to persistence truth. */
    public record Evaluation(
            MetricResultContext context,
            Map<ExecutionScopeCodec.RegionKey, MetricRegionResult> regionResults,
            Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> regionFailures,
            boolean cacheHit) {
        public Evaluation {
            context = Objects.requireNonNull(context, "context");
            regionResults = immutableMap(regionResults, "region_results");
            regionFailures = immutableMap(regionFailures, "region_failures");
            Set<ExecutionScopeCodec.RegionKey> requested = new HashSet<>();
            context.regions().forEach(target -> requested.add(target.key()));
            Set<ExecutionScopeCodec.RegionKey> observed = new HashSet<>(regionResults.keySet());
            if (!Collections.disjoint(observed, regionFailures.keySet())) {
                throw new IllegalArgumentException("metric results and failures must be disjoint");
            }
            observed.addAll(regionFailures.keySet());
            if (!observed.equals(requested)) {
                throw new IllegalArgumentException(
                        "metric results and failures must cover every requested region exactly");
            }
            if (cacheHit && !regionFailures.isEmpty()) {
                throw new IllegalArgumentException("an incomplete metric result cannot be reused");
            }
        }

        /** True exactly when every requested region produced schema-valid values. */
        public boolean complete() {
            return regionFailures.isEmpty();
        }

        /** Materialize the typed result bound to the authoritative revision captured for input. */
        public MetricImageResult result() {
            return new MetricImageResult(
                    context.metricInputDigest(),
                    context.sourceRevision(),
                    regionResults,
                    regionFailures);
        }
    }

    /**
     * Resolve one detached input from an exact cache hit or execute every eligible region.
     *
     * <p>{@code preexistingFailures} is used by model actions whose target transaction failed;
     * those regions are deliberately not recomputed from unchanged old hierarchy state.
     */
    public Evaluation evaluate(
            MetricPreparation preparation,
            Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> preexistingFailures,
            boolean allowCacheLookup,
            BooleanSupplier shouldStop) {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(preexistingFailures, "preexistingFailures");
        Objects.requireNonNull(shouldStop, "shouldStop");
        MetricTypes.MetricImageSnapshot snapshot = preparation.snapshot();
        MetricInputDraft input = snapshot.metricInput();
        ScriptCalibrationGate.requireSupported(
                input.calibration().mppX(), input.calibration().mppY());

        Set<ExecutionScopeCodec.RegionKey> requested = regionKeys(input);
        if (!requested.containsAll(preexistingFailures.keySet())) {
            throw new IllegalArgumentException(
                    "preexisting metric failures contain an unrequested region");
        }
        ImageModuleKey owner = owner(snapshot);
        if (allowCacheLookup && preexistingFailures.isEmpty()) {
            Optional<MetricImageResult> cached = exactComplete(preparation);
            if (cached.isPresent()) {
                return new Evaluation(
                        MetricResultContext.from(snapshot),
                        cached.orElseThrow().regionResults(),
                        Map.of(),
                        true);
            }
        }

        latestComplete.remove(owner);
        Map<ExecutionScopeCodec.RegionKey, MetricRegionResult> results = new LinkedHashMap<>();
        Map<ExecutionScopeCodec.RegionKey, RunResults.FailureDetail> failures =
                new LinkedHashMap<>(preexistingFailures);
        ExecutionLimitsCodec.ClientExecutionLimits limits = input.clientBuild().limits();
        long imageValues = 0L;

        List<MetricRegionSnapshot> regions = input.regions();
        if (regions.stream()
                .map(region -> region.target().key())
                .anyMatch(key -> !failures.containsKey(key))) {
            requireRunning(shouldStop);
            scriptInvoker.prepare(input.script());
        }
        for (int index = 0; index < regions.size(); index++) {
            requireRunning(shouldStop);
            MetricRegionSnapshot region = regions.get(index);
            ExecutionScopeCodec.RegionKey key = region.target().key();
            if (failures.containsKey(key)) {
                continue;
            }
            try {
                Object raw =
                        scriptInvoker.run(
                                input.script(),
                                region,
                                snapshot.sourceRevision().imageId(),
                                input.calibration().mppX(),
                                input.calibration().mppY());
                long validationStarted = System.nanoTime();
                logger.debug("Metrics returned-value validation started for {}", key);
                ValidatedRegion validated =
                        validateRegion(
                                raw,
                                input.script().schema(),
                                limits.maxMetricValuesPerRegion(),
                                Math.subtractExact(limits.maxMetricValuesPerImage(), imageValues));
                logger.debug(
                        "Metrics returned-value validation finished in {} ms for {}",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - validationStarted),
                        key);
                results.put(key, validated.result());
                imageValues = Math.addExact(imageValues, validated.valueCount());
            } catch (ImageMetricLimitExceeded limit) {
                RunResults.FailureDetail failure =
                        failure(RunResults.FailureType.LOCAL_CAPACITY, limit);
                failures.put(key, failure);
                for (int remaining = index + 1; remaining < regions.size(); remaining++) {
                    ExecutionScopeCodec.RegionKey remainingKey =
                            regions.get(remaining).target().key();
                    if (!results.containsKey(remainingKey)) {
                        failures.putIfAbsent(remainingKey, failure);
                    }
                }
                break;
            } catch (RegionMetricLimitExceeded limit) {
                failures.put(key, failure(RunResults.FailureType.LOCAL_CAPACITY, limit));
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                failures.put(key, failure(RunResults.FailureType.METRICS, failure));
            }
        }
        return new Evaluation(MetricResultContext.from(snapshot), results, failures, false);
    }

    /**
     * Bind one evaluation to the authoritative final revision and retain only complete results.
     *
     * <p>The caller invokes this only after a successful save or unchanged-revision proof.
     */
    public MetricImageResult publish(
            Evaluation evaluation, RasterInputCodec.ImageRevisionToken finalRevision) {
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(finalRevision, "finalRevision");
        if (!evaluation.context().sourceRevision().imageId().equals(finalRevision.imageId())) {
            throw new IllegalArgumentException("final metric revision belongs to another image");
        }
        MetricImageResult result =
                new MetricImageResult(
                        evaluation.context().metricInputDigest(),
                        finalRevision,
                        evaluation.regionResults(),
                        evaluation.regionFailures());
        ImageModuleKey owner =
                new ImageModuleKey(
                        finalRevision.imageId(), evaluation.context().module().moduleRef());
        if (evaluation.complete()) {
            latestComplete.put(
                    owner,
                    new CacheEntry(
                            new MetricTypes.MetricCacheKey(
                                    finalRevision, evaluation.context().metricInputDigest()),
                            result));
        } else {
            latestComplete.remove(owner);
        }
        return result;
    }

    /** Remove the latest result after a global preparation/evaluation failure. */
    public void evict(String imageId, ModuleRef moduleRef) {
        latestComplete.remove(
                new ImageModuleKey(
                        CanonicalCodec.requireNonblank(imageId, "image_id"),
                        Objects.requireNonNull(moduleRef, "moduleRef")));
    }

    /**
     * Cost hint for UI refresh: absence proves there is nothing to display without opening the
     * image; presence is never treated as currency and still requires {@link #exactComplete} after
     * an authoritative capture.
     */
    public boolean hasCachedResult(String imageId, ModuleRef moduleRef) {
        return latestComplete.containsKey(
                new ImageModuleKey(
                        CanonicalCodec.requireNonblank(imageId, "image_id"),
                        Objects.requireNonNull(moduleRef, "moduleRef")));
    }

    /**
     * Return a complete result only after exact revision, normalized-input, and region validation.
     *
     * <p>This is the UI's lookup-only path. It performs no metric execution and removes a
     * mismatched old entry so the process retains only the latest reusable truth for that image and
     * module.
     */
    public Optional<MetricImageResult> exactComplete(MetricPreparation preparation) {
        Objects.requireNonNull(preparation, "preparation");
        MetricTypes.MetricImageSnapshot snapshot = preparation.snapshot();
        MetricInputDraft input = snapshot.metricInput();
        ScriptCalibrationGate.requireSupported(
                input.calibration().mppX(), input.calibration().mppY());
        ImageModuleKey owner = owner(snapshot);
        CacheEntry cached = latestComplete.get(owner);
        if (cached == null) {
            return Optional.empty();
        }
        MetricTypes.MetricCacheKey key =
                new MetricTypes.MetricCacheKey(
                        snapshot.sourceRevision(), input.metricInputDigest());
        Set<ExecutionScopeCodec.RegionKey> requested = regionKeys(input);
        if (!cached.key().equals(key)
                || !cached.result().complete()
                || !cached.result().regionFailures().isEmpty()
                || !cached.result().regionResults().keySet().equals(requested)) {
            latestComplete.remove(owner, cached);
            return Optional.empty();
        }
        return Optional.of(cached.result());
    }

    /** Return only parent repairs owned by metric regions that produced valid values. */
    public static List<HierarchyDelta.ParentAssignment> successfulParentNormalization(
            MetricPreparation preparation, Evaluation evaluation) {
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(evaluation, "evaluation");
        if (preparation.parentNormalization().isEmpty()) {
            return List.of();
        }
        // Capture owns population-wide UUID uniqueness. Resolve only repair IDs here using the
        // snapshots' UUID order, without allocating a lookup entry for every biological object.
        List<HierarchyDelta.ParentAssignment> result = new ArrayList<>();
        for (HierarchyDelta.ParentAssignment assignment : preparation.parentNormalization()) {
            ExecutionScopeCodec.RegionKey owner = null;
            for (MetricRegionSnapshot region : preparation.snapshot().metricInput().regions()) {
                if (containsObject(region.objects(), assignment.objectId())) {
                    if (owner != null) {
                        throw new IllegalArgumentException(
                                "metric object occurs in multiple selected regions");
                    }
                    owner = region.target().key();
                }
            }
            if (owner == null) {
                throw new IllegalArgumentException(
                        "parent normalization references an uncaptured metric object");
            }
            if (evaluation.regionResults().containsKey(owner)) {
                result.add(assignment);
            }
        }
        return List.copyOf(result);
    }

    private static boolean containsObject(List<MetricObjectSnapshot> objects, UUID objectId) {
        int low = 0;
        int high = objects.size() - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int comparison = objects.get(middle).objectId().compareTo(objectId);
            if (comparison == 0) {
                return true;
            }
            if (comparison < 0) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return false;
    }

    int cacheSize() {
        return latestComplete.size();
    }

    private static ValidatedRegion validateRegion(
            Object raw,
            MetricTypes.MetricSchema schema,
            long regionLimit,
            long remainingImageLimit) {
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("metric script must return a Map");
        }
        Set<String> expected = new HashSet<>();
        schema.definitions().forEach(definition -> expected.add(definition.name()));
        Set<String> actual = new HashSet<>();
        for (Object key : values.keySet()) {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("metric script keys must be strings");
            }
            if (!actual.add(name)) {
                throw new IllegalArgumentException("metric script keys must be unique");
            }
        }
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "metric script keys must exactly match the declared schema");
        }

        Map<String, MetricValue> result = new LinkedHashMap<>();
        long count = 0L;
        for (MetricDefinition definition : schema.definitions()) {
            long started = System.nanoTime();
            logger.debug("Metrics metric-value validation started: {}", definition.name());
            Object value = values.get(definition.name());
            if (value == null) {
                result.put(definition.name(), new Missing());
                logger.debug(
                        "Metrics metric-value validation finished in {} ms: {}; missing, 0 values",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        definition.name());
                continue;
            }
            if (definition.type() == MetricType.SCALAR) {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException(
                            "scalar metric returned a non-number: " + definition.name());
                }
                count = requireCapacity(count, 1L, regionLimit, remainingImageLimit);
                result.put(definition.name(), new Scalar(number.doubleValue()));
                logger.debug(
                        "Metrics metric-value validation finished in {} ms: {}; scalar, 1 value",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        definition.name());
                continue;
            }
            if (!(value instanceof List<?> list)) {
                throw new IllegalArgumentException(
                        "distribution metric returned a non-list: " + definition.name());
            }
            long members = list.size();
            count = requireCapacity(count, members, regionLimit, remainingImageLimit);
            List<Double> distribution = new ArrayList<>(list.size());
            for (Object member : list) {
                if (!(member instanceof Number number)) {
                    throw new IllegalArgumentException(
                            "distribution metric contains a non-number: " + definition.name());
                }
                distribution.add(number.doubleValue());
            }
            result.put(definition.name(), new Distribution(distribution));
            logger.debug(
                    "Metrics metric-value validation finished in {} ms: {}; distribution, {} values",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    definition.name(),
                    members);
        }
        return new ValidatedRegion(new MetricRegionResult(result), count);
    }

    private static long requireCapacity(
            long current, long added, long regionLimit, long remainingImageLimit) {
        long next = Math.addExact(current, added);
        if (next > regionLimit) {
            throw new RegionMetricLimitExceeded("metric region exceeds its value limit");
        }
        if (next > remainingImageLimit) {
            throw new ImageMetricLimitExceeded("metric image exceeds its value limit");
        }
        return next;
    }

    private static Set<ExecutionScopeCodec.RegionKey> regionKeys(MetricInputDraft input) {
        Set<ExecutionScopeCodec.RegionKey> result = new HashSet<>();
        input.regions().forEach(region -> result.add(region.target().key()));
        return Set.copyOf(result);
    }

    private static ImageModuleKey owner(MetricTypes.MetricImageSnapshot snapshot) {
        return new ImageModuleKey(
                snapshot.sourceRevision().imageId(), snapshot.metricInput().module().moduleRef());
    }

    private static RunResults.FailureDetail failure(
            RunResults.FailureType type, Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return new RunResults.FailureDetail(type, message);
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("metric evaluation cancelled");
        }
    }

    private static <T> Map<ExecutionScopeCodec.RegionKey, T> immutableMap(
            Map<ExecutionScopeCodec.RegionKey, T> values, String path) {
        Objects.requireNonNull(values, path);
        Map<ExecutionScopeCodec.RegionKey, T> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) ->
                        result.put(
                                Objects.requireNonNull(key, path + " key"),
                                Objects.requireNonNull(value, path + " value")));
        return Collections.unmodifiableMap(result);
    }

    private record ImageModuleKey(String imageId, ModuleRef moduleRef) {
        private ImageModuleKey {
            imageId = CanonicalCodec.requireNonblank(imageId, "image_id");
            moduleRef = Objects.requireNonNull(moduleRef, "module_ref");
        }
    }

    private record CacheEntry(MetricTypes.MetricCacheKey key, MetricImageResult result) {
        private CacheEntry {
            key = Objects.requireNonNull(key, "key");
            result = Objects.requireNonNull(result, "result");
        }
    }

    private record ValidatedRegion(MetricRegionResult result, long valueCount) {}

    private static class RegionMetricLimitExceeded extends IllegalArgumentException {
        private RegionMetricLimitExceeded(String message) {
            super(message);
        }
    }

    private static final class ImageMetricLimitExceeded extends RegionMetricLimitExceeded {
        private ImageMetricLimitExceeded(String message) {
            super(message);
        }
    }
}
