package qupath.ext.histoforge.roi;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.GenerationStoreCodec;
import qupath.ext.histoforge.contract.ImageDataStateCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.HierarchyPolicy;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** One logical HistoForge-ROI removal, including output cleanup and evidence sweep. */
final class RoiDeletionCleanup {

    private RoiDeletionCleanup() {}

    /** Counts and nonfatal missing-generation audit IDs produced by one cleanup. */
    record Result(
            int removedRois,
            int removedAttributedObjects,
            int liftedPreservedObjects,
            Set<String> missingGenerationRecordIds) {
        Result {
            missingGenerationRecordIds = Set.copyOf(missingGenerationRecordIds);
        }
    }

    /**
     * Apply the cleanup without firing an event; {@link RoiManager} publishes the one final event.
     *
     * <p>The supplied ROI snapshots may still be current (the HistoForge delete path) or already
     * detached (the generic-QuPath listener fallback). All selection is by logical provenance,
     * never by ancestry or geometry.
     */
    static Result apply(ImageData<?> imageData, Collection<PathObject> roiSnapshots) {
        Objects.requireNonNull(imageData, "imageData");
        if (roiSnapshots == null || roiSnapshots.isEmpty()) {
            throw new IllegalArgumentException("ROI deletion requires at least one ROI");
        }

        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        PathObject root = hierarchy.getRootObject();
        RoiRegistry currentRois = RoiRegistry.fromHierarchy(hierarchy);

        Map<String, PathObject> targets = validatedTargets(roiSnapshots);
        Map<UUID, PathObject> currentObjects = currentObjects(hierarchy);
        Map<UUID, PathObject> universe = new LinkedHashMap<>(currentObjects);
        for (PathObject target : targets.values()) {
            collectTree(target, universe);
        }

        Map<UUID, Optional<ProvenanceCodec.ObjectProvenance>> provenance = readProvenance(universe);
        Set<UUID> targetRoiIds = new LinkedHashSet<>();
        targets.keySet().forEach(id -> targetRoiIds.add(UUID.fromString(id)));
        boolean hostAlreadyRemovedTarget =
                targetRoiIds.stream().anyMatch(id -> !currentObjects.containsKey(id));
        Set<UUID> attributedRemovalIds = new LinkedHashSet<>();
        for (Map.Entry<UUID, Optional<ProvenanceCodec.ObjectProvenance>> entry :
                provenance.entrySet()) {
            entry.getValue()
                    .filter(value -> ownsAnyTarget(value, targets.keySet()))
                    .ifPresent(ignored -> attributedRemovalIds.add(entry.getKey()));
        }
        attributedRemovalIds.removeAll(targetRoiIds);
        Set<UUID> removalIds = new LinkedHashSet<>(targetRoiIds);
        removalIds.addAll(attributedRemovalIds);

        Map<UUID, PathObject> parentAssignments = new LinkedHashMap<>();
        for (Map.Entry<UUID, PathObject> entry : universe.entrySet()) {
            UUID objectId = entry.getKey();
            if (removalIds.contains(objectId)) {
                continue;
            }
            PathObject object = entry.getValue();
            Optional<ProvenanceCodec.ObjectProvenance> objectProvenance = provenance.get(objectId);
            boolean detachedOrDirectParentRemoved =
                    requiresLift(object, objectId, currentObjects.keySet(), removalIds, universe);
            boolean attributedAtRoot =
                    hostAlreadyRemovedTarget
                            && objectProvenance.isPresent()
                            && object.getParent() != null
                            && object.getParent().isRootObject();
            if (!detachedOrDirectParentRemoved && !attributedAtRoot) {
                continue;
            }
            Optional<UUID> parentId =
                    HierarchyPolicy.preservedObjectParent(
                            object, objectProvenance, removalIds, universe, currentRois::findRoi);
            PathObject parent =
                    parentId.isPresent()
                            ? Objects.requireNonNull(
                                    universe.get(parentId.orElseThrow()),
                                    "preserved object parent is absent from the cleanup universe")
                            : root;
            if (object.getParent() != parent) {
                parentAssignments.put(objectId, parent);
            }
        }
        List<HierarchyDelta.ParentAssignment> validatedAssignments =
                parentAssignments.entrySet().stream()
                        .map(
                                entry ->
                                        new HierarchyDelta.ParentAssignment(
                                                entry.getKey(),
                                                entry.getValue().isRootObject()
                                                        ? Optional.empty()
                                                        : Optional.of(
                                                                Objects.requireNonNull(
                                                                        entry.getValue().getID(),
                                                                        "persistent parent UUID"))))
                        .toList();
        HierarchyPolicy.requireAcyclicAssignments(
                universe.values(), removalIds, List.of(), validatedAssignments);

        OutputIndex outputIndex = OutputIndex.fromHierarchy(hierarchy);
        for (Map.Entry<UUID, PathObject> entry : universe.entrySet()) {
            if (!currentObjects.containsKey(entry.getKey())
                    && !removalIds.contains(entry.getKey())
                    && provenance.get(entry.getKey()).isPresent()) {
                outputIndex.add(entry.getValue());
            }
        }
        removalIds.forEach(outputIndex::remove);

        Optional<GenerationStoreCodec.GenerationStore> currentStore =
                ImageDataStateCodec.readGenerationStore(imageData);
        GenerationStoreCodec.SweepResult sweep =
                GenerationStoreCodec.markAndSweep(
                        currentStore.orElseGet(GenerationStoreCodec::emptyStore),
                        outputIndex.currentProvenance());
        // Validate the complete replacement before the first hierarchy mutation.
        currentStore.ifPresent(ignored -> ImageDataStateCodec.encodeGenerationStore(sweep.store()));

        parentAssignments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entry.getValue().addChildObject(universe.get(entry.getKey())));

        List<PathObject> removals =
                removalIds.stream()
                        .map(universe::get)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparingInt(RoiDeletionCleanup::depth).reversed())
                        .toList();
        for (PathObject removal : removals) {
            PathObject parent = removal.getParent();
            if (parent != null) {
                parent.removeChildObject(removal);
            }
        }

        hierarchy.getSelectionModel().deselectObjects(removals);
        currentStore.ifPresent(
                ignored -> ImageDataStateCodec.writeGenerationStore(imageData, sweep.store()));

        return new Result(
                targets.size(),
                attributedRemovalIds.size(),
                parentAssignments.size(),
                sweep.missingGenerationRecordIds());
    }

    private static Map<String, PathObject> validatedTargets(Collection<PathObject> snapshots) {
        Map<String, PathObject> targets = new LinkedHashMap<>();
        for (PathObject target : snapshots) {
            if (!RoiRegistry.isAdministrativeRoi(target)
                    || !target.isAnnotation()
                    || target.getROI() == null
                    || !target.getROI().isArea()) {
                throw new IllegalArgumentException(
                        "ROI deletion target must be a marked area annotation");
            }
            String roiId =
                    CanonicalCodec.requireCanonicalUuid(
                            Objects.requireNonNull(target.getID(), "ROI persistent UUID")
                                    .toString(),
                            "roi_id");
            PathObject previous = targets.putIfAbsent(roiId, target);
            if (previous != null && previous != target) {
                throw new IllegalArgumentException("ROI deletion contains a duplicate UUID");
            }
        }
        return targets;
    }

    private static Map<UUID, PathObject> currentObjects(PathObjectHierarchy hierarchy) {
        Map<UUID, PathObject> result = new LinkedHashMap<>();
        for (PathObject object : hierarchy.getAllObjects(false)) {
            putUnique(result, object);
        }
        return result;
    }

    private static void collectTree(PathObject object, Map<UUID, PathObject> result) {
        putUnique(result, object);
        for (PathObject child : object.getChildObjects()) {
            collectTree(child, result);
        }
    }

    private static void putUnique(Map<UUID, PathObject> objects, PathObject object) {
        UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
        PathObject previous = objects.putIfAbsent(objectId, object);
        if (previous != null && previous != object) {
            throw new IllegalArgumentException(
                    "hierarchy contains duplicate object UUID " + objectId);
        }
    }

    private static Map<UUID, Optional<ProvenanceCodec.ObjectProvenance>> readProvenance(
            Map<UUID, PathObject> objects) {
        Map<UUID, Optional<ProvenanceCodec.ObjectProvenance>> result = new LinkedHashMap<>();
        objects.forEach(
                (id, object) ->
                        result.put(
                                id,
                                object.hasMetadata()
                                        ? ProvenanceCodec.readMetadata(object.getMetadata())
                                        : Optional.empty()));
        return result;
    }

    private static boolean ownsAnyTarget(
            ProvenanceCodec.ObjectProvenance provenance, Set<String> targetRoiIds) {
        return ExecutionScopeCodec.regionOf(provenance.output().scope())
                        instanceof ExecutionScopeCodec.Roi roi
                && targetRoiIds.contains(roi.roiId());
    }

    private static boolean requiresLift(
            PathObject object,
            UUID objectId,
            Set<UUID> currentObjectIds,
            Set<UUID> removalIds,
            Map<UUID, PathObject> universe) {
        PathObject parent = object.getParent();
        if (parent != null && parent.isRootObject() && currentObjectIds.contains(objectId)) {
            return false;
        }
        if (parent == null) {
            return !currentObjectIds.contains(objectId);
        }
        UUID parentId = parent.getID();
        return parent.isRootObject()
                ? !currentObjectIds.contains(objectId)
                : parentId == null
                        || removalIds.contains(parentId)
                        || !universe.containsKey(parentId);
    }

    private static int depth(PathObject object) {
        int depth = 0;
        PathObject current = object.getParent();
        while (current != null) {
            depth++;
            current = current.getParent();
        }
        return depth;
    }
}
