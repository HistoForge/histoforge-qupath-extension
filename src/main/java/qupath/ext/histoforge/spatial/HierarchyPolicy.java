package qupath.ext.histoforge.spatial;

import java.util.ArrayList;
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
import java.util.function.Function;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.contract.ProvenanceCodec;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Pure explicit-parent policy for model additions and descendants preserved across replacement. */
public final class HierarchyPolicy {

    private HierarchyPolicy() {}

    /** Assign every candidate to its exact source parent, ROI anchor, or hierarchy root. */
    public static List<HierarchyDelta.ParentAssignment> modelAssignments(
            CandidateGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        Optional<UUID> parent = expectedParent(generation.output().scope());
        return generation.candidates().stream()
                .map(
                        candidate ->
                                new HierarchyDelta.ParentAssignment(
                                        UUID.fromString(candidate.candidateId()), parent))
                .toList();
    }

    /** Return the exact parent UUID required by one model-output scope; empty means root. */
    public static Optional<UUID> expectedParent(ExecutionScopeCodec.ExecutionScope executionScope) {
        Objects.requireNonNull(executionScope, "executionScope");
        if (executionScope instanceof ExecutionScopeCodec.ParentScope parent) {
            return Optional.of(UUID.fromString(parent.parentId()));
        }
        ExecutionScopeCodec.RegionKey region =
                ((ExecutionScopeCodec.FieldScope) executionScope).region();
        if (region instanceof ExecutionScopeCodec.Roi roi) {
            return Optional.of(UUID.fromString(roi.roiId()));
        }
        return Optional.empty();
    }

    /**
     * Lift only current objects whose direct parent is removed, without adopting them into new
     * output.
     *
     * <p>Unattributed objects keep the nearest surviving ancestor. Attributed objects keep their
     * exact surviving source parent when possible, otherwise their own ROI anchor or the root.
     */
    public static List<HierarchyDelta.ParentAssignment> preservedDescendantAssignments(
            PathObjectHierarchy hierarchy,
            Set<UUID> removalIds,
            Function<String, Optional<PathObject>> roiLookup) {
        Objects.requireNonNull(hierarchy, "hierarchy");
        Set<UUID> removals = immutableIds(removalIds);
        Function<String, Optional<PathObject>> currentRois =
                Objects.requireNonNull(roiLookup, "roiLookup");
        if (removals.isEmpty()) {
            return List.of();
        }

        Map<UUID, PathObject> objects = currentObjects(hierarchy);
        List<HierarchyDelta.ParentAssignment> result = new ArrayList<>();
        for (Map.Entry<UUID, PathObject> entry :
                objects.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            UUID objectId = entry.getKey();
            PathObject object = entry.getValue();
            if (removals.contains(objectId)
                    || object.getParent() == null
                    || object.getParent().isRootObject()
                    || !removals.contains(object.getParent().getID())) {
                continue;
            }
            Optional<ProvenanceCodec.ObjectProvenance> provenance =
                    object.hasMetadata()
                            ? ProvenanceCodec.readMetadata(object.getMetadata())
                            : Optional.empty();
            Optional<UUID> parent =
                    preservedObjectParent(object, provenance, removals, objects, currentRois);
            result.add(new HierarchyDelta.ParentAssignment(objectId, parent));
        }
        return List.copyOf(result);
    }

    /**
     * Resolve one preserved object's nearest permitted surviving parent; empty means root.
     *
     * <p>This is shared by model-output replacement and scoped HistoForge ROI deletion. A missing
     * ROI anchor is valid only when that exact ROI is part of the explicit removal set.
     */
    public static Optional<UUID> preservedObjectParent(
            PathObject object,
            Optional<ProvenanceCodec.ObjectProvenance> provenance,
            Set<UUID> removalIds,
            Map<UUID, PathObject> objectUniverse,
            Function<String, Optional<PathObject>> roiLookup) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(provenance, "provenance");
        Set<UUID> removals = Objects.requireNonNull(removalIds, "removalIds");
        Map<UUID, PathObject> objects = Objects.requireNonNull(objectUniverse, "objectUniverse");
        Function<String, Optional<PathObject>> currentRois =
                Objects.requireNonNull(roiLookup, "roiLookup");
        if (provenance.isEmpty()) {
            return nearestSurvivingAncestor(object.getParent(), removals, objects);
        }

        ExecutionScopeCodec.ExecutionScope scope = provenance.orElseThrow().output().scope();
        if (scope instanceof ExecutionScopeCodec.ParentScope parentScope) {
            UUID exactParent = UUID.fromString(parentScope.parentId());
            if (objects.containsKey(exactParent) && !removals.contains(exactParent)) {
                return Optional.of(exactParent);
            }
        }
        ExecutionScopeCodec.RegionKey region = ExecutionScopeCodec.regionOf(scope);
        if (region instanceof ExecutionScopeCodec.Roi roi) {
            UUID roiId = UUID.fromString(roi.roiId());
            Optional<PathObject> anchor =
                    Objects.requireNonNull(currentRois.apply(roi.roiId()), "ROI lookup result");
            if (anchor.isPresent()) {
                UUID anchorId =
                        Objects.requireNonNull(anchor.orElseThrow().getID(), "ROI persistent UUID");
                if (!removals.contains(anchorId)) {
                    return Optional.of(anchorId);
                }
            }
            if (removals.contains(roiId)) {
                return Optional.empty();
            }
            throw new IllegalArgumentException(
                    "attributed object references a missing HistoForge ROI");
        }
        return Optional.empty();
    }

    /** Validate the complete post-delta parent graph before any hierarchy mutation. */
    public static void requireAcyclicAssignments(
            Collection<PathObject> currentObjects,
            Set<UUID> removals,
            Collection<HierarchyDelta.ObjectDraft> additions,
            Collection<HierarchyDelta.ParentAssignment> assignments) {
        Objects.requireNonNull(currentObjects, "currentObjects");
        Set<UUID> removed = immutableIds(removals);
        Map<UUID, Optional<UUID>> parents = new LinkedHashMap<>();
        for (PathObject object : currentObjects) {
            UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
            if (removed.contains(objectId)) {
                continue;
            }
            PathObject parent = object.getParent();
            parents.put(
                    objectId,
                    parent == null || parent.isRootObject()
                            ? Optional.empty()
                            : Optional.of(
                                    Objects.requireNonNull(
                                            parent.getID(), "persistent parent UUID")));
        }
        for (HierarchyDelta.ObjectDraft draft : additions) {
            if (parents.putIfAbsent(draft.objectId(), Optional.empty()) != null) {
                throw new IllegalArgumentException(
                        "added object UUID already exists in the hierarchy");
            }
        }
        for (HierarchyDelta.ParentAssignment assignment : assignments) {
            if (!parents.containsKey(assignment.objectId())) {
                throw new IllegalArgumentException(
                        "parent assignment child does not exist in the post-delta hierarchy");
            }
            assignment
                    .parentObjectId()
                    .ifPresent(
                            parent -> {
                                if (!parents.containsKey(parent)) {
                                    throw new IllegalArgumentException(
                                            "parent assignment target does not exist in the post-delta hierarchy");
                                }
                            });
            parents.put(assignment.objectId(), assignment.parentObjectId());
        }

        for (Map.Entry<UUID, Optional<UUID>> entry : parents.entrySet()) {
            entry.getValue()
                    .ifPresent(
                            parent -> {
                                if (!parents.containsKey(parent)) {
                                    throw new IllegalArgumentException(
                                            "a preserved object would remain under removed output");
                                }
                            });
        }

        for (UUID start : parents.keySet()) {
            Set<UUID> visited = new LinkedHashSet<>();
            UUID current = start;
            while (current != null) {
                if (!visited.add(current)) {
                    throw new IllegalArgumentException("parent assignments create a cycle");
                }
                current = parents.getOrDefault(current, Optional.empty()).orElse(null);
            }
        }
    }

    private static Optional<UUID> nearestSurvivingAncestor(
            PathObject removedParent, Set<UUID> removals, Map<UUID, PathObject> objects) {
        PathObject current = removedParent == null ? null : removedParent.getParent();
        while (current != null && !current.isRootObject()) {
            UUID currentId = Objects.requireNonNull(current.getID(), "persistent ancestor UUID");
            if (!removals.contains(currentId) && objects.containsKey(currentId)) {
                return Optional.of(currentId);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static Map<UUID, PathObject> currentObjects(PathObjectHierarchy hierarchy) {
        Map<UUID, PathObject> result = new LinkedHashMap<>();
        for (PathObject object :
                hierarchy.getAllObjects(false).stream()
                        .sorted(Comparator.comparing(PathObject::getID))
                        .toList()) {
            UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
            if (result.putIfAbsent(objectId, object) != null) {
                throw new IllegalArgumentException(
                        "hierarchy contains duplicate object UUID " + objectId);
            }
        }
        return result;
    }

    private static Set<UUID> immutableIds(Set<UUID> values) {
        if (values == null) {
            throw new IllegalArgumentException("removal IDs must not be null");
        }
        Set<UUID> result = new LinkedHashSet<>();
        for (UUID value : values) {
            result.add(Objects.requireNonNull(value, "removal ID"));
        }
        return Set.copyOf(result);
    }
}
