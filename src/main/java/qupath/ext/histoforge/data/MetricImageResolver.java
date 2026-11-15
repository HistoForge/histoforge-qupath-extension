package qupath.ext.histoforge.data;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.ActionSelectionCodec;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.data.MetricSnapshotBuilder.CapturedPreparation;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.pipeline.ImageActionPlanner;
import qupath.ext.histoforge.pipeline.ImageActionRegistry;
import qupath.ext.histoforge.pipeline.ImageLifecycle;
import qupath.ext.histoforge.roi.AnnotationRoiSelection;
import qupath.lib.images.ImageData;
import qupath.lib.projects.ProjectImageEntry;

/** One-snapshot metric preparation and exact cache-or-compute resolution for a project image. */
public final class MetricImageResolver {

    private static final Logger logger = LoggerFactory.getLogger(MetricImageResolver.class);
    private final ImageActionRegistry actionRegistry;
    private final ImageLifecycle imageLifecycle;
    private final MetricsEngine metricsEngine;
    private final Clock clock;

    /** Project display needs exact inputs, but not the per-object export evidence. */
    public enum CapturePurpose {
        PROJECT_CACHE,
        EXPORT
    }

    public MetricImageResolver(
            ImageActionRegistry actionRegistry,
            ImageLifecycle imageLifecycle,
            MetricsEngine metricsEngine) {
        this(actionRegistry, imageLifecycle, metricsEngine, Clock.systemUTC());
    }

    MetricImageResolver(
            ImageActionRegistry actionRegistry,
            ImageLifecycle imageLifecycle,
            MetricsEngine metricsEngine,
            Clock clock) {
        this.actionRegistry = Objects.requireNonNull(actionRegistry, "actionRegistry");
        this.imageLifecycle = Objects.requireNonNull(imageLifecycle, "imageLifecycle");
        this.metricsEngine = Objects.requireNonNull(metricsEngine, "metricsEngine");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One exact module plus the regions evaluated against the shared image capture. */
    public record MetricSelection(
            ModuleSnapshot module,
            ActionSelectionCodec.RegionSelection regions,
            Optional<String> annotationClass) {
        public MetricSelection(
                ModuleSnapshot module, ActionSelectionCodec.RegionSelection regions) {
            this(module, regions, Optional.empty());
        }

        public MetricSelection {
            module = Objects.requireNonNull(module, "module");
            regions = Objects.requireNonNull(regions, "regions");
            annotationClass = Objects.requireNonNull(annotationClass, "annotationClass");
            if (module.metrics().isEmpty()) {
                throw new IllegalArgumentException("metric selection requires metrics.groovy");
            }
        }
    }

    /**
     * Result metadata and temporary export attribution, without the evaluated object population.
     */
    public static final class ResolvedModule {
        private final ModuleSnapshot module;
        private final MetricResultContext context;
        private final Map<UUID, ProvenanceCodec.ObjectProvenance> attributedObjects;
        private final Optional<MetricsEngine.Evaluation> evaluation;

        public ResolvedModule(
                ModuleSnapshot module,
                CapturedPreparation capture,
                Optional<MetricsEngine.Evaluation> evaluation) {
            this.module = Objects.requireNonNull(module, "module");
            Objects.requireNonNull(capture, "capture");
            this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
            this.context = MetricResultContext.from(capture.preparation().snapshot());
            // CapturedPreparation already owns an immutable attribution map. Keep those facts
            // for export evidence without retaining preparation or copying the map again.
            this.attributedObjects = capture.attributedObjects();
            if (!context.module().equals(module.module())) {
                throw new IllegalArgumentException(
                        "resolved metric input belongs to another module snapshot");
            }
            evaluation.ifPresent(
                    value -> {
                        if (!value.context().equals(context)) {
                            throw new IllegalArgumentException(
                                    "resolved metric evaluation belongs to another capture");
                        }
                    });
        }

        public ModuleSnapshot module() {
            return module;
        }

        public MetricResultContext context() {
            return context;
        }

        public Map<UUID, ProvenanceCodec.ObjectProvenance> attributedObjects() {
            return attributedObjects;
        }

        public Optional<MetricsEngine.Evaluation> evaluation() {
            return evaluation;
        }
    }

    /** Every module prepared from one truthful authoritative ImageData snapshot. */
    public record ResolvedImage(
            Instant capturedAt,
            ImageLifecycle.ImageDataMaterialization materialization,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            Optional<GenerationStoreCodec.GenerationStore> generationStore,
            List<ResolvedModule> modules) {
        public ResolvedImage {
            capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
            materialization = Objects.requireNonNull(materialization, "materialization");
            sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
            generationStore = Objects.requireNonNull(generationStore, "generationStore");
            modules = List.copyOf(modules);
            if (modules.isEmpty()) {
                throw new IllegalArgumentException("resolved image modules must be nonempty");
            }
            Set<Object> identities = new HashSet<>();
            for (ResolvedModule module : modules) {
                if (!module.context().sourceRevision().equals(sourceRevision.imageDataRevision())) {
                    throw new IllegalArgumentException(
                            "resolved metric module belongs to another image capture");
                }
                if (!identities.add(module.module().module())) {
                    throw new IllegalArgumentException(
                            "resolved image contains duplicate module identities");
                }
            }
        }
    }

    /**
     * Capture once, then either look up exact complete results or compute misses from detached
     * data.
     *
     * <p>Cache-only resolution never executes Groovy. Compute-on-miss resolution may return an
     * incomplete evaluation; its caller decides whether partial metrics are acceptable. Export
     * rejects it, while an explicit Metrics action already owns partial-result presentation.
     */
    public ResolvedImage resolve(
            ProjectImageEntry<BufferedImage> entry,
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            List<MetricSelection> selections,
            boolean computeOnMiss,
            BooleanSupplier shouldStop)
            throws IOException {
        return resolve(
                entry, clientBuild, selections, CapturePurpose.EXPORT, computeOnMiss, shouldStop);
    }

    public ResolvedImage resolve(
            ProjectImageEntry<BufferedImage> entry,
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            List<MetricSelection> selections,
            CapturePurpose purpose,
            boolean computeOnMiss,
            BooleanSupplier shouldStop)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(clientBuild, "clientBuild");
        Objects.requireNonNull(purpose, "purpose");
        if (purpose == CapturePurpose.PROJECT_CACHE && computeOnMiss) {
            throw new IllegalArgumentException("Project refresh must not compute missing metrics");
        }
        List<MetricSelection> normalized = normalizeSelections(selections);
        Objects.requireNonNull(shouldStop, "shouldStop");
        requireRunning(shouldStop);

        final ImageActionRegistry.Lease lease;
        try {
            lease = actionRegistry.acquireLease(entry.getID());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("metric image lease interrupted");
        }

        final ImageLifecycle.ReadOnlyImageCapture<CapturedImage> captured;
        try (lease) {
            requireRunning(shouldStop);
            long started = System.nanoTime();
            logger.debug("Metrics read-only image capture started for {}", entry.getID());
            captured =
                    imageLifecycle.captureReadOnly(
                            entry,
                            entry.getID(),
                            clientBuild.limits(),
                            () -> requireRunning(shouldStop),
                            (imageData, sourceRevision, ignoredSource) ->
                                    captureImage(
                                            imageData,
                                            sourceRevision,
                                            clientBuild,
                                            normalized,
                                            purpose));
            logger.debug(
                    "Metrics read-only image capture finished in {} ms for {}",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    entry.getID());
        }

        requireRunning(shouldStop);
        List<ResolvedModule> modules = new ArrayList<>(captured.value().modules().size());
        for (CapturedModule module : captured.value().modules()) {
            requireRunning(shouldStop);
            CapturedPreparation preparation = module.capture().prepare();
            requireRunning(shouldStop);
            Optional<MetricsEngine.Evaluation> evaluation =
                    metricsEngine
                            .exactComplete(preparation.preparation())
                            .map(
                                    result ->
                                            new MetricsEngine.Evaluation(
                                                    MetricResultContext.from(
                                                            preparation.preparation().snapshot()),
                                                    result.regionResults(),
                                                    Map.of(),
                                                    true));
            if (evaluation.isEmpty() && computeOnMiss) {
                evaluation =
                        Optional.of(
                                metricsEngine.evaluate(
                                        preparation.preparation(), Map.of(), false, shouldStop));
            }
            modules.add(new ResolvedModule(module.module(), preparation, evaluation));
        }
        return new ResolvedImage(
                captured.value().capturedAt(),
                captured.materialization(),
                captured.sourceRevision(),
                captured.value().generationStore(),
                modules);
    }

    private CapturedImage captureImage(
            ImageData<BufferedImage> imageData,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            List<MetricSelection> selections,
            CapturePurpose purpose) {
        Instant capturedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        // Full revision capture already validated the image-owned store. Retain it only
        // when the caller will publish export evidence from this same capture.
        Optional<GenerationStoreCodec.GenerationStore> store =
                purpose == CapturePurpose.EXPORT
                        ? Optional.of(
                                ImageDataStateCodec.readGenerationStore(imageData)
                                        .orElseGet(GenerationStoreCodec::emptyStore))
                        : Optional.empty();
        List<CapturedModule> modules = new ArrayList<>(selections.size());
        int width = imageData.getServer().getWidth();
        int height = imageData.getServer().getHeight();
        for (MetricSelection selection : selections) {
            long started = System.nanoTime();
            logger.debug(
                    "Metrics read-only region planning started for {}",
                    selection.module().module().moduleRef());
            var regions =
                    selection.annotationClass().isEmpty()
                            ? selection.regions()
                            : AnnotationRoiSelection.regions(
                                    imageData.getHierarchy(),
                                    selection.annotationClass().orElseThrow());
            List<ExecutionScopeCodec.TargetRegion> targets =
                    ImageActionPlanner.captureRegions(
                            regions, imageData.getHierarchy(), width, height);
            logger.debug(
                    "Metrics read-only region planning finished in {} ms for {}; {} regions",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    selection.module().module().moduleRef(),
                    targets.size());
            modules.add(
                    new CapturedModule(
                            selection.module(),
                            MetricSnapshotBuilder.captureInput(
                                    clientBuild,
                                    selection.module(),
                                    targets,
                                    sourceRevision,
                                    imageData.getHierarchy(),
                                    targets,
                                    purpose == CapturePurpose.EXPORT)));
        }
        return new CapturedImage(capturedAt, store, modules);
    }

    private static List<MetricSelection> normalizeSelections(List<MetricSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("metric selections must be nonempty");
        }
        Set<Object> modules = new HashSet<>();
        List<MetricSelection> result = new ArrayList<>();
        for (MetricSelection selection : selections) {
            MetricSelection item = Objects.requireNonNull(selection, "metric selection");
            if (!modules.add(item.module().module())) {
                throw new IllegalArgumentException("metric module selections must be unique");
            }
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static void requireRunning(BooleanSupplier shouldStop) {
        if (shouldStop.getAsBoolean()) {
            throw new CancellationException("metric resolution cancelled");
        }
    }

    private record CapturedImage(
            Instant capturedAt,
            Optional<GenerationStoreCodec.GenerationStore> generationStore,
            List<CapturedModule> modules) {
        private CapturedImage {
            capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
            generationStore = Objects.requireNonNull(generationStore, "generationStore");
            modules = List.copyOf(modules);
        }
    }

    private record CapturedModule(ModuleSnapshot module, MetricPopulationCapture capture) {
        private CapturedModule {
            module = Objects.requireNonNull(module, "module");
            capture = Objects.requireNonNull(capture, "capture");
        }
    }
}
