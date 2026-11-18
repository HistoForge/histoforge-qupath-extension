package qupath.ext.histoforge.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceFeatureCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.data.MetricPopulationCapture.ObjectFields;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectType;
import qupath.ext.histoforge.data.MetricTypes.MetricPreparation;
import qupath.ext.histoforge.data.MetricTypes.MetricSchema;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;
import qupath.ext.histoforge.modules.ModuleSnapshot;
import qupath.ext.histoforge.modules.ScriptHeaderParser;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.NativeRoiGeometry;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.interfaces.ROI;

/** Captures complete detached metric populations and derives parent-only normalization. */
public final class MetricSnapshotBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MetricSnapshotBuilder.class);

    private MetricSnapshotBuilder() {}

    /**
     * Whether live outlines have exactly the representation Metrics would read after saving.
     *
     * <p>Check area outlines and point coordinates, including unclassified selected boundaries.
     * Unproven or legacy representations use the existing public project reload; this never
     * rewrites live geometry.
     */
    public static boolean canCaptureWithoutReload(PathObjectHierarchy hierarchy) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        long started = System.nanoTime();
        logger.debug("Metrics saved-image geometry eligibility started");
        int examined = 0;
        PathObject root = hierarchy.getRootObject();
        for (PathObject object : hierarchy.getFlattenedObjectList(null)) {
            if (object == root) {
                continue;
            }
            examined++;
            ROI roi = object.getROI();
            if (roi != null
                    && (roi.isArea() || roi.isPoint())
                    && !MetricRoiPersistence.isUnchangedBySave(roi)) {
                logger.debug(
                        "Metrics saved-image geometry eligibility finished in {} ms; eligible: false, {} objects examined",
                        elapsedMillis(started),
                        examined);
                return false;
            }
        }
        logger.debug(
                "Metrics saved-image geometry eligibility finished in {} ms; eligible: true, {} objects examined",
                elapsedMillis(started),
                examined);
        return true;
    }

    /** A captured metric population contains output attributed to a deleted logical ROI. */
    public static final class MissingLogicalRoiException extends IllegalArgumentException {
        public MissingLogicalRoiException(UUID objectId, String roiId) {
            super("metric object " + objectId + " references missing logical ROI " + roiId);
        }
    }

    /** A captured unattributed object spatially belongs to multiple current ROIs. */
    public static final class AmbiguousRoiMembershipException extends IllegalArgumentException {
        public AmbiguousRoiMembershipException(UUID objectId, Set<String> roiIds) {
            super("metric object " + objectId + " has ambiguous ROI membership " + roiIds);
        }
    }

    /** Detached metric preparation plus attribution facts captured from the same hierarchy. */
    public record CapturedPreparation(
            MetricPreparation preparation,
            Map<UUID, ProvenanceCodec.ObjectProvenance> attributedObjects) {
        public CapturedPreparation {
            preparation = Objects.requireNonNull(preparation, "preparation");
            Objects.requireNonNull(attributedObjects, "attributedObjects");
            if (attributedObjects.isEmpty()) {
                attributedObjects = Map.of();
            } else {
                Set<UUID> capturedIds = new HashSet<>();
                preparation.snapshot().metricInput().regions().stream()
                        .flatMap(region -> region.objects().stream())
                        .forEach(object -> capturedIds.add(object.objectId()));
                Map<UUID, ProvenanceCodec.ObjectProvenance> copy = new LinkedHashMap<>();
                attributedObjects.forEach(
                        (objectId, provenance) -> {
                            UUID id = Objects.requireNonNull(objectId, "attributed object ID");
                            if (!capturedIds.contains(id)) {
                                throw new IllegalArgumentException(
                                        "attribution references an object outside the metric snapshot");
                            }
                            copy.put(id, Objects.requireNonNull(provenance, "object provenance"));
                        });
                attributedObjects = Collections.unmodifiableMap(copy);
            }
        }
    }

    /**
     * Copy one module's complete selected object population from the caller's working hierarchy.
     *
     * <p>Geometry, classes, UUIDs and parents are captured before returning. Metric workers
     * reconstruct private QuPath objects from these snapshots; they never receive live objects.
     */
    public static MetricPreparation prepare(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            ModuleSnapshot module,
            List<ExecutionScopeCodec.TargetRegion> targets,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            PathObjectHierarchy hierarchy) {
        return capture(clientBuild, module, targets, sourceRevision, hierarchy, targets, false)
                .preparation();
    }

    public static MetricPreparation prepare(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            ModuleSnapshot module,
            List<ExecutionScopeCodec.TargetRegion> targets,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        return capture(
                        clientBuild,
                        module,
                        targets,
                        sourceRevision,
                        hierarchy,
                        selectionRegions,
                        false)
                .preparation();
    }

    /**
     * Return a conservative, non-mutating population that may affect one metric region.
     *
     * <p>This is deliberately separate from metric preparation. It includes objects that a valid
     * capture would read or reparent, plus malformed or ambiguous same-plane objects whose metric
     * preparation may fail. Large-action rebase can therefore fence relevant user edits without
     * turning a metric-only data error into failure of otherwise valid model output.
     */
    public static Set<UUID> transactionRegionObjectIds(
            ExecutionScopeCodec.TargetRegion target, PathObjectHierarchy hierarchy) {
        return transactionRegionObjectIds(target, hierarchy, List.of(target));
    }

    public static Set<UUID> transactionRegionObjectIds(
            ExecutionScopeCodec.TargetRegion target,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hierarchy, "hierarchy");
        RoiRegistry registry = RoiRegistry.forTargets(hierarchy, selectionRegions);
        Set<UUID> result = new LinkedHashSet<>();
        for (PathObject object : hierarchy.getAllObjects(false)) {
            if (!isBiologicalObject(object)
                    || registry.isBoundary(object)
                    || object.getROI().getZ() != target.plane().z()
                    || object.getROI().getT() != target.plane().t()) {
                continue;
            }
            if (mayAffectMetricRegion(object, target, registry)) {
                result.add(Objects.requireNonNull(object.getID(), "persistent object UUID"));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean mayAffectMetricRegion(
            PathObject object, ExecutionScopeCodec.TargetRegion target, RoiRegistry registry) {
        if (target.key() instanceof ExecutionScopeCodec.Slide) {
            return spatiallyCoveredOrUncertain(object, target.geometrySlide());
        }

        ExecutionScopeCodec.Roi targetRoi = (ExecutionScopeCodec.Roi) target.key();
        final Optional<ProvenanceCodec.ObjectProvenance> provenance;
        try {
            provenance =
                    object.hasMetadata()
                            ? ProvenanceCodec.readMetadata(object.getMetadata())
                            : Optional.empty();
        } catch (IllegalArgumentException malformed) {
            return true;
        }
        if (provenance.isPresent()) {
            ExecutionScopeCodec.RegionKey region =
                    ExecutionScopeCodec.regionOf(provenance.orElseThrow().output().scope());
            if (region instanceof ExecutionScopeCodec.Roi logicalRoi) {
                if (logicalRoi.roiId().equals(targetRoi.roiId())) {
                    return true;
                }
                if (registry.findRoi(logicalRoi.roiId()).isPresent()) {
                    return false;
                }
            }
        }
        return spatiallyCoveredOrUncertain(object, target.geometrySlide());
    }

    private static boolean spatiallyCoveredOrUncertain(PathObject object, Geometry target) {
        try {
            Geometry geometry =
                    NativeRoiGeometry.convert(
                            Objects.requireNonNull(object.getROI(), "metric object ROI"));
            return target.covers(geometry.getInteriorPoint());
        } catch (RuntimeException invalidGeometry) {
            return true;
        }
    }

    /**
     * Capture the same metric input with current object provenance for Export.
     *
     * <p>Export uses the attribution map to link current objects to image-owned generation
     * evidence. Attribution is deliberately not part of the metric-input digest because metric
     * scripts do not receive it.
     */
    public static CapturedPreparation capture(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            ModuleSnapshot module,
            List<ExecutionScopeCodec.TargetRegion> targets,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            PathObjectHierarchy hierarchy) {
        return capture(clientBuild, module, targets, sourceRevision, hierarchy, targets, true);
    }

    private static CapturedPreparation capture(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            ModuleSnapshot module,
            List<ExecutionScopeCodec.TargetRegion> targets,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions,
            boolean includeAttribution) {
        return captureInput(
                        clientBuild,
                        module,
                        targets,
                        sourceRevision,
                        hierarchy,
                        selectionRegions,
                        includeAttribution)
                .prepare();
    }

    /** Copy authoritative fields now; the returned input can be prepared on a worker thread. */
    public static MetricPopulationCapture captureForCalculation(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            ModuleSnapshot module,
            List<ExecutionScopeCodec.TargetRegion> targets,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        return captureInput(
                clientBuild, module, targets, sourceRevision, hierarchy, selectionRegions, false);
    }

    static MetricPopulationCapture captureInput(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            ModuleSnapshot module,
            List<ExecutionScopeCodec.TargetRegion> targets,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions,
            boolean includeAttribution) {
        Objects.requireNonNull(clientBuild, "clientBuild");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(hierarchy, "hierarchy");

        ModuleSnapshot.MetricScriptSnapshot captured =
                module.metrics()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "module has no captured metrics script"));
        MetricSchema parsed =
                MetricSchema.from(ScriptHeaderParser.parseMetrics(captured.script().source()));
        MetricSchema retained = MetricSchema.from(captured.schema());
        if (!parsed.equals(retained)) {
            throw new IllegalArgumentException(
                    "captured metric schema does not match the exact script source");
        }
        MetricScriptSnapshot script =
                new MetricScriptSnapshot(
                        captured.script().source(), captured.script().digest(), retained);

        long started = System.nanoTime();
        logger.debug("Metrics ROI registry construction started");
        List<PathObject> objects = hierarchy.getFlattenedObjectList(null);
        RoiRegistry registry =
                RoiRegistry.forTargetsFromHierarchyObjects(objects, selectionRegions);
        logger.debug("Metrics ROI registry construction finished in {} ms", elapsedMillis(started));
        PathObject root = Objects.requireNonNull(hierarchy.getRootObject(), "hierarchy root");
        started = System.nanoTime();
        logger.debug("Metrics detached population capture started; {} regions", targets.size());
        List<RegionCapture> captures = new ArrayList<>(targets.size());
        Map<String, RegionCapture> roiCaptures = new LinkedHashMap<>();
        List<RegionCapture> slideCaptures = new ArrayList<>();
        var provenanceReader = new ProvenanceCodec.MetadataReader();
        Set<String> validatedClasses = new HashSet<>();
        for (ExecutionScopeCodec.TargetRegion target : targets) {
            RegionCapture capture =
                    new RegionCapture(target, registry, includeAttribution, validatedClasses);
            captures.add(capture);
            if (target.key() instanceof ExecutionScopeCodec.Roi roi) {
                if (roiCaptures.put(roi.roiId(), capture) != null) {
                    throw new IllegalArgumentException("repeated metric ROI target");
                }
            } else {
                slideCaptures.add(capture);
            }
        }
        for (PathObject object : objects) {
            if (!isBiologicalObject(object) || registry.isBoundary(object)) continue;
            // ROI mode resolves provenance before geometry, including objects outside the
            // requested targets. The complete selection still owns spatial fallback.
            if (!roiCaptures.isEmpty()) {
                var evidence = registry.membershipEvidence(object, provenanceReader);
                RoiRegistry.RoiMembership membership = evidence.membership();
                if (membership instanceof RoiRegistry.MissingLogicalRoi missing) {
                    Geometry geometry = objectGeometry(object);
                    for (RegionCapture capture : roiCaptures.values()) {
                        if (capture.covers(object.getROI(), geometry)) {
                            throw new MissingLogicalRoiException(object.getID(), missing.roiId());
                        }
                    }
                } else if (membership instanceof RoiRegistry.Ambiguous ambiguous) {
                    if (ambiguous.roiIds().stream().anyMatch(roiCaptures::containsKey)) {
                        throw new AmbiguousRoiMembershipException(
                                object.getID(), ambiguous.roiIds());
                    }
                } else if (membership instanceof RoiRegistry.Member member) {
                    RegionCapture capture = roiCaptures.get(member.roiId());
                    if (capture != null) {
                        if (object.getROI().getZ() != capture.target.plane().z()
                                || object.getROI().getT() != capture.target.plane().t()) {
                            throw new IllegalArgumentException(
                                    "logical ROI metric object lies on another image plane");
                        }
                        capture.add(object, root, objectGeometry(object), evidence.provenance());
                    }
                }
            }
            // Slide mode deliberately validates geometry before filtering plane/coverage,
            // and only decodes provenance for covered objects.
            if (!slideCaptures.isEmpty()) {
                Geometry geometry = objectGeometry(object);
                for (RegionCapture capture : slideCaptures) {
                    if (!capture.covers(object.getROI(), geometry)) continue;
                    var evidence =
                            registry.membershipEvidenceUsingValidatedGeometry(
                                    object, geometry, provenanceReader);
                    requireValidMembership(object, evidence.membership());
                    capture.add(object, root, geometry, evidence.provenance());
                }
            }
        }
        List<MetricPopulationCapture.Region> regions =
                captures.stream().map(RegionCapture::finish).toList();
        MetricPopulationCapture result =
                new MetricPopulationCapture(
                        clientBuild, sourceRevision, module.module(), script, regions);
        logger.debug(
                "Metrics detached population capture finished in {} ms; {} regions",
                elapsedMillis(started),
                regions.size());
        return result;
    }

    /** Owner-thread accumulators only; finish() returns no registry or live object references. */
    private static final class RegionCapture {
        private final ExecutionScopeCodec.TargetRegion target;
        private final Geometry targetGeometry;
        private final ImagePlane plane;
        private final Optional<UUID> fallbackParent;
        private final Set<UUID> administrativeParents;
        private final List<ObjectFields> selected = new ArrayList<>();
        private final Map<UUID, ProvenanceCodec.ObjectProvenance> attributed;
        private final boolean includeAttribution;
        private final Set<String> validatedClasses;

        private RegionCapture(
                ExecutionScopeCodec.TargetRegion target,
                RoiRegistry registry,
                boolean includeAttribution,
                Set<String> validatedClasses) {
            this.target = Objects.requireNonNull(target, "target");
            this.targetGeometry = target.geometrySlide();
            this.plane = ImagePlane.getPlane(target.plane().z(), target.plane().t());
            this.fallbackParent = fallbackParent(target, registry);
            this.administrativeParents =
                    validAdministrativeParents(target, registry, fallbackParent);
            this.includeAttribution = includeAttribution;
            this.attributed = includeAttribution ? new LinkedHashMap<>() : Map.of();
            this.validatedClasses = validatedClasses;
        }

        private boolean covers(ROI roi, Geometry geometry) {
            return coveredByTarget(roi, geometry, target, targetGeometry);
        }

        private void add(
                PathObject object,
                PathObject root,
                Geometry geometry,
                Optional<ProvenanceCodec.ObjectProvenance> provenance) {
            UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
            PathObject parent = object.getParent();
            Optional<UUID> currentParent =
                    parent == null || parent == root
                            ? Optional.empty()
                            : Optional.of(
                                    Objects.requireNonNull(
                                            parent.getID(), "persistent parent UUID"));
            selected.add(
                    new ObjectFields(
                            objectId,
                            object.isAnnotation()
                                    ? MetricObjectType.ANNOTATION
                                    : MetricObjectType.DETECTION,
                            validatedClass(object.getPathClass().toString()),
                            MetricObjectGeometry.takeValidatedGeometry(
                                    geometry, plane, object.getROI()),
                            currentParent,
                            detectionScore(object)));
            // Membership checks already validate provenance. Export also keeps it to identify
            // which operation produced each object.
            if (includeAttribution) {
                provenance.ifPresent(value -> attributed.put(objectId, value));
            }
        }

        private MetricPopulationCapture.Region finish() {
            return new MetricPopulationCapture.Region(
                    target, selected, fallbackParent, administrativeParents, attributed);
        }

        private String validatedClass(String text) {
            if (!validatedClasses.contains(text)) {
                CanonicalCodec.requirePathClassName(text, "metric object PathClass");
                // Bound capture-local retention to at most 1 Mi UTF-16 characters. Larger
                // or additional class names remain valid and are checked on every occurrence.
                if (validatedClasses.size() < 256 && text.length() <= 4096) {
                    validatedClasses.add(text);
                }
            }
            return text;
        }
    }

    private static Optional<UUID> fallbackParent(
            ExecutionScopeCodec.TargetRegion target, RoiRegistry registry) {
        if (target.key() instanceof ExecutionScopeCodec.Slide) {
            return Optional.empty();
        }
        ExecutionScopeCodec.Roi roi = (ExecutionScopeCodec.Roi) target.key();
        PathObject anchor =
                registry.findRoi(roi.roiId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "selected HistoForge ROI is missing"));
        String currentDigest =
                CanonicalGeometryCodec.canonicalDigest(NativeRoiGeometry.convert(anchor.getROI()));
        if (!target.geometryDigest().equals(currentDigest)) {
            throw new IllegalArgumentException(
                    "selected HistoForge ROI geometry changed after planning");
        }
        if (anchor.getROI().getZ() != target.plane().z()
                || anchor.getROI().getT() != target.plane().t()) {
            throw new IllegalArgumentException(
                    "selected HistoForge ROI plane changed after planning");
        }
        return Optional.of(Objects.requireNonNull(anchor.getID(), "ROI UUID"));
    }

    private static Set<UUID> validAdministrativeParents(
            ExecutionScopeCodec.TargetRegion target,
            RoiRegistry registry,
            Optional<UUID> fallbackParent) {
        if (target.key() instanceof ExecutionScopeCodec.Roi) {
            return Set.of(fallbackParent.orElseThrow());
        }
        Set<UUID> result = new LinkedHashSet<>();
        for (PathObject anchor : registry.rois()) {
            result.add(Objects.requireNonNull(anchor.getID(), "ROI UUID"));
        }
        return Set.copyOf(result);
    }

    private static void requireValidMembership(
            PathObject object, RoiRegistry.RoiMembership membership) {
        if (membership instanceof RoiRegistry.MissingLogicalRoi missing) {
            throw new MissingLogicalRoiException(object.getID(), missing.roiId());
        }
        if (membership instanceof RoiRegistry.Ambiguous ambiguous) {
            throw new AmbiguousRoiMembershipException(object.getID(), ambiguous.roiIds());
        }
    }

    private static boolean coveredByTarget(
            ROI roi,
            Geometry geometry,
            ExecutionScopeCodec.TargetRegion target,
            Geometry targetGeometry) {
        return roi.getZ() == target.plane().z()
                && roi.getT() == target.plane().t()
                && targetGeometry.covers(geometry.getInteriorPoint());
    }

    private static boolean isBiologicalObject(PathObject object) {
        return object != null
                && (object.isDetection() || object.isAnnotation())
                && !RoiRegistry.isAdministrativeRoi(object)
                && object.getPathClass() != null
                && object.getROI() != null
                && (object.getROI().isArea()
                        || object.getROI().isPoint() && object.getROI().getNumPoints() == 1);
    }

    private static OptionalDouble detectionScore(PathObject object) {
        if (!object.getROI().isPoint()
                || !object.hasMeasurements()
                || !object.getMeasurementList()
                        .containsKey(InferenceFeatureCodec.DETECTION_SCORE_MEASUREMENT)) {
            return OptionalDouble.empty();
        }
        if (object.getROI().getNumPoints() != 1) {
            throw new IllegalArgumentException("a detection score requires a single point");
        }
        return OptionalDouble.of(
                InferenceFeatureCodec.requireDetectionScore(
                        object.getMeasurementList()
                                .get(InferenceFeatureCodec.DETECTION_SCORE_MEASUREMENT)));
    }

    private static Geometry objectGeometry(PathObject object) {
        ROI roi = Objects.requireNonNull(object.getROI(), "metric object ROI");
        if (roi.isPoint()) {
            Geometry geometry = NativeRoiGeometry.convert(roi);
            CanonicalGeometryCodec.validatePointGeometry(geometry);
            return geometry;
        }
        Geometry geometry = NativeRoiGeometry.convertValidatedPolygonal(roi);
        if (!(geometry.getArea() > 0.0)) {
            throw new IllegalArgumentException("metric object geometry must have positive area");
        }
        return geometry;
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
