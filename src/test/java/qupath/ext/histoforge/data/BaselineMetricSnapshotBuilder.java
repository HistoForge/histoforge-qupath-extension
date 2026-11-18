// Frozen test oracle from 3a5f99fd464e1fe2cc15beb975ad843257fd0fe8; never used by production.
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.CanonicalGeometryCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
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
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.interfaces.ROI;

/** Captures complete detached metric populations and derives parent-only normalization. */
public final class BaselineMetricSnapshotBuilder {

    private static final Logger logger =
            LoggerFactory.getLogger(BaselineMetricSnapshotBuilder.class);

    private BaselineMetricSnapshotBuilder() {}

    /**
     * Whether live outlines have exactly the representation Metrics would read after saving.
     *
     * <p>Check all area outlines, including unclassified selected boundaries. Unproven or legacy
     * representations use the existing public project reload; this never rewrites live geometry.
     */
    public static boolean canCaptureWithoutReload(PathObjectHierarchy hierarchy) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        long started = System.nanoTime();
        logger.debug("Metrics saved-image geometry eligibility started");
        int examined = 0;
        for (PathObject object : hierarchy.getAllObjects(false)) {
            examined++;
            ROI roi = object.getROI();
            if (roi != null && roi.isArea() && !MetricRoiPersistence.isUnchangedBySave(roi)) {
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
            if (!isBiologicalAreaObject(object)
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
        var result =
                captureInput(
                                clientBuild,
                                module,
                                targets,
                                sourceRevision,
                                hierarchy,
                                selectionRegions,
                                includeAttribution)
                        .prepare();
        return new CapturedPreparation(result.preparation(), result.attributedObjects());
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

    private static MetricPopulationCapture captureInput(
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
        RoiRegistry registry = RoiRegistry.forTargets(hierarchy, selectionRegions);
        logger.debug("Metrics ROI registry construction finished in {} ms", elapsedMillis(started));
        PathObject root = Objects.requireNonNull(hierarchy.getRootObject(), "hierarchy root");
        started = System.nanoTime();
        logger.debug("Metrics biological population extraction started");
        List<PathObject> population =
                hierarchy.getAllObjects(false).stream()
                        .filter(BaselineMetricSnapshotBuilder::isBiologicalAreaObject)
                        .filter(object -> !registry.isBoundary(object))
                        .toList();
        logger.debug(
                "Metrics biological population extraction finished in {} ms; {} candidate objects",
                elapsedMillis(started),
                population.size());

        started = System.nanoTime();
        logger.debug("Metrics detached population capture started; {} regions", targets.size());
        List<MetricPopulationCapture.Region> regions = new ArrayList<>(targets.size());
        for (ExecutionScopeCodec.TargetRegion target : targets) {
            regions.add(captureRegion(target, registry, root, population, includeAttribution));
        }
        MetricPopulationCapture result =
                new MetricPopulationCapture(
                        clientBuild, sourceRevision, module.module(), script, regions);
        logger.debug(
                "Metrics detached population capture finished in {} ms; {} regions",
                elapsedMillis(started),
                regions.size());
        return result;
    }

    private static MetricPopulationCapture.Region captureRegion(
            ExecutionScopeCodec.TargetRegion target,
            RoiRegistry registry,
            PathObject root,
            List<PathObject> population,
            boolean includeAttribution) {
        Objects.requireNonNull(target, "target");
        long started = System.nanoTime();
        logger.debug(
                "Metrics detached region capture started for {}: {} candidate objects",
                target.key(),
                population.size());
        Optional<UUID> fallbackParent = fallbackParent(target, registry);
        Set<UUID> validAdministrativeParents =
                validAdministrativeParents(target, registry, fallbackParent);
        Geometry targetGeometry = target.geometrySlide();
        ImagePlane plane = ImagePlane.getPlane(target.plane().z(), target.plane().t());
        List<ObjectFields> selected = new ArrayList<>();
        Map<UUID, ProvenanceCodec.ObjectProvenance> attributed =
                includeAttribution ? new LinkedHashMap<>() : Map.of();
        boolean reportProgress = logger.isDebugEnabled();
        int examined = 0;
        for (PathObject object : population) {
            if (reportProgress && examined > 0 && examined % 100_000 == 0) {
                logger.debug(
                        "Metrics detached region capture progress for {}: {} of {} objects examined, {} captured; {} ms elapsed",
                        target.key(),
                        examined,
                        population.size(),
                        selected.size(),
                        elapsedMillis(started));
            }
            examined++;
            Optional<Geometry> selectedGeometry =
                    selectedGeometry(object, target, targetGeometry, registry);
            if (selectedGeometry.isEmpty()) {
                continue;
            }
            Geometry geometry = selectedGeometry.orElseThrow();
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
                            CanonicalCodec.requirePathClassName(
                                    object.getPathClass().toString(), "metric object PathClass"),
                            MetricObjectGeometry.takeValidatedGeometry(
                                    geometry, plane, object.getROI()),
                            currentParent));
            // Membership checks already validate provenance. Export also keeps it to identify
            // which operation produced each object.
            if (includeAttribution) {
                ProvenanceCodec.readMetadata(object.getMetadata())
                        .ifPresent(value -> attributed.put(objectId, value));
            }
        }
        MetricPopulationCapture.Region result =
                new MetricPopulationCapture.Region(
                        target, selected, fallbackParent, validAdministrativeParents, attributed);
        logger.debug(
                "Metrics detached region capture finished in {} ms for {}: {} objects examined, {} captured",
                elapsedMillis(started),
                target.key(),
                examined,
                selected.size());
        return result;
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

    private static Optional<Geometry> selectedGeometry(
            PathObject object,
            ExecutionScopeCodec.TargetRegion target,
            Geometry targetGeometry,
            RoiRegistry registry) {
        ROI roi = object.getROI();
        if (target.key() instanceof ExecutionScopeCodec.Slide) {
            Geometry geometry = areaGeometry(object);
            if (!coveredByTarget(roi, geometry, target, targetGeometry)) {
                return Optional.empty();
            }
            RoiRegistry.RoiMembership membership =
                    registry.membershipUsingValidatedGeometry(object, geometry);
            requireValidMembership(object, membership);
            return Optional.of(geometry);
        }

        ExecutionScopeCodec.Roi targetRoi = (ExecutionScopeCodec.Roi) target.key();
        RoiRegistry.RoiMembership membership = registry.membership(object);
        if (membership instanceof RoiRegistry.MissingLogicalRoi missing) {
            Geometry geometry = areaGeometry(object);
            if (coveredByTarget(roi, geometry, target, targetGeometry)) {
                throw new MissingLogicalRoiException(object.getID(), missing.roiId());
            }
            return Optional.empty();
        }
        if (membership instanceof RoiRegistry.Ambiguous ambiguous) {
            if (ambiguous.roiIds().contains(targetRoi.roiId())) {
                throw new AmbiguousRoiMembershipException(object.getID(), ambiguous.roiIds());
            }
            return Optional.empty();
        }
        if (!(membership instanceof RoiRegistry.Member member)
                || !member.roiId().equals(targetRoi.roiId())) {
            return Optional.empty();
        }
        if (roi.getZ() != target.plane().z() || roi.getT() != target.plane().t()) {
            throw new IllegalArgumentException(
                    "logical ROI metric object lies on another image plane");
        }
        return Optional.of(areaGeometry(object));
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

    private static boolean isBiologicalAreaObject(PathObject object) {
        return object != null
                && (object.isDetection() || object.isAnnotation())
                && !RoiRegistry.isAdministrativeRoi(object)
                && object.getPathClass() != null
                && object.getROI() != null
                && object.getROI().isArea();
    }

    private static Geometry areaGeometry(PathObject object) {
        ROI roi = Objects.requireNonNull(object.getROI(), "metric object ROI");
        Geometry geometry = NativeRoiGeometry.convert(roi);
        // The supported concrete PolygonROI returns a private geometry. Other implementations
        // may expose mutable storage, so their geometry still needs a defensive copy.
        if (roi.getClass() != PolygonROI.class) {
            geometry = geometry.copy();
        }
        if (!(geometry instanceof Polygon || geometry instanceof MultiPolygon)) {
            throw new IllegalArgumentException(
                    "metric object geometry must be Polygon or MultiPolygon");
        }
        CanonicalGeometryCodec.validatePolygonalGeometry(geometry);
        if (!(geometry.getArea() > 0.0)) {
            throw new IllegalArgumentException("metric object geometry must have positive area");
        }
        return geometry;
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
