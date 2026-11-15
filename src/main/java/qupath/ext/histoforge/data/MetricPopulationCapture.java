package qupath.ext.histoforge.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.histoforge.contract.BuildIdentityCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.InferenceIdentityCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.contract.RasterInputCodec;
import qupath.ext.histoforge.data.MetricTypes.MetricInputDraft;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricObjectType;
import qupath.ext.histoforge.data.MetricTypes.MetricRegionSnapshot;
import qupath.ext.histoforge.data.MetricTypes.MetricScriptSnapshot;
import qupath.ext.histoforge.spatial.HierarchyDelta;

/** Detached object fields; parent calculation and input hashing need no access to the viewer. */
public final class MetricPopulationCapture {
    private static final Logger logger = LoggerFactory.getLogger(MetricPopulationCapture.class);

    private final BuildIdentityCodec.ClientBuildIdentity clientBuild;
    private final RasterInputCodec.ActionSourceRevision sourceRevision;
    private final InferenceIdentityCodec.ModuleContentIdentity module;
    private final MetricScriptSnapshot script;
    private final List<Region> regions;

    MetricPopulationCapture(
            BuildIdentityCodec.ClientBuildIdentity clientBuild,
            RasterInputCodec.ActionSourceRevision sourceRevision,
            InferenceIdentityCodec.ModuleContentIdentity module,
            MetricScriptSnapshot script,
            List<Region> regions) {
        this.clientBuild = clientBuild;
        this.sourceRevision = sourceRevision;
        this.module = module;
        this.script = script;
        this.regions = List.copyOf(regions);
    }

    /** Normalize captured parents and derive the exact input identity on the calling worker. */
    public MetricSnapshotBuilder.CapturedPreparation prepare() {
        long started = System.nanoTime();
        logger.debug("Metrics parent calculation and snapshot assembly started");
        List<MetricRegionSnapshot> prepared = new ArrayList<>(regions.size());
        List<HierarchyDelta.ParentAssignment> assignments = new ArrayList<>();
        Map<UUID, ProvenanceCodec.ObjectProvenance> attributed = new LinkedHashMap<>();
        Set<UUID> selectedIds = new HashSet<>();
        for (Region region : regions) {
            // One region already checks adjacent UUIDs during sorted parent normalization.
            // Populate this additional set only when it must also compare different regions.
            if (regions.size() > 1) {
                for (ObjectFields object : region.objects()) {
                    if (!selectedIds.add(object.objectId())) {
                        throw new IllegalArgumentException(
                                "one metric preparation cannot select an object into multiple regions");
                    }
                }
            }
            prepared.add(prepareRegion(region, assignments));
            region.attributedObjects()
                    .forEach(
                            (id, provenance) -> {
                                if (attributed.putIfAbsent(id, provenance) != null) {
                                    throw new IllegalArgumentException(
                                            "metric attribution occurs in multiple regions");
                                }
                            });
        }
        logger.debug(
                "Metrics parent calculation and snapshot assembly finished in {} ms",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        started = System.nanoTime();
        logger.debug("Metrics input hashing started");
        MetricInputDraft draft =
                MetricInputDraft.build(
                        clientBuild,
                        sourceRevision.pixelSource().calibration(),
                        module,
                        script,
                        prepared);
        logger.debug(
                "Metrics input hashing finished in {} ms",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return new MetricSnapshotBuilder.CapturedPreparation(
                new MetricTypes.MetricPreparation(
                        new MetricTypes.MetricImageSnapshot(
                                draft, sourceRevision.imageDataRevision()),
                        assignments),
                attributed);
    }

    private static MetricRegionSnapshot prepareRegion(
            Region region, List<HierarchyDelta.ParentAssignment> assignments) {
        List<ObjectFields> selected = new ArrayList<>(region.objects());
        selected.sort(Comparator.comparing(ObjectFields::objectId));
        List<HierarchyDelta.ParentAssignment> changes =
                MetricParentNormalizer.normalize(
                        selected, region.fallbackParent(), region.administrativeParents());
        assignments.addAll(changes);
        List<MetricObjectSnapshot> snapshots = new ArrayList<>(selected.size());
        int changeIndex = 0;
        // Objects and changes are UUID-ordered. An omitted change keeps the captured parent.
        for (ObjectFields object : selected) {
            Optional<UUID> parent = object.currentParentId();
            if (changeIndex < changes.size()) {
                var change = changes.get(changeIndex);
                if (change.objectId().equals(object.objectId())) {
                    parent = change.parentObjectId();
                    changeIndex++;
                }
            }
            snapshots.add(
                    MetricObjectSnapshot.fromCapturedGeometry(
                            object.objectId(),
                            object.objectType(),
                            object.pathClass(),
                            object.geometry(),
                            // Normalization returns only biological or administrative parents.
                            // Root is already absent; administrative anchors become the target.
                            parent.filter(id -> !region.administrativeParents().contains(id))));
        }
        return new MetricRegionSnapshot(region.target(), snapshots);
    }

    // These values never retain native PathObjects, a hierarchy, or a live parent lookup.
    record ObjectFields(
            UUID objectId,
            MetricObjectType objectType,
            String pathClass,
            MetricObjectGeometry geometry,
            Optional<UUID> currentParentId) {
        ObjectFields {
            objectId = Objects.requireNonNull(objectId, "object_id");
            objectType = Objects.requireNonNull(objectType, "object_type");
            pathClass = Objects.requireNonNull(pathClass, "path_class");
            geometry = Objects.requireNonNull(geometry, "geometry");
            currentParentId = Objects.requireNonNull(currentParentId, "current_parent_id");
            if (currentParentId.filter(objectId::equals).isPresent()) {
                throw new IllegalArgumentException("an object cannot be its own parent");
            }
        }
    }

    record Region(
            ExecutionScopeCodec.TargetRegion target,
            List<ObjectFields> objects,
            Optional<UUID> fallbackParent,
            Set<UUID> administrativeParents,
            Map<UUID, ProvenanceCodec.ObjectProvenance> attributedObjects) {
        Region {
            objects = List.copyOf(objects);
            administrativeParents = Set.copyOf(administrativeParents);
            attributedObjects = Map.copyOf(attributedObjects);
        }
    }
}
