package qupath.ext.histoforge.large;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import qupath.ext.histoforge.contract.CanonicalCodec;
import qupath.ext.histoforge.contract.ExecutionScopeCodec;
import qupath.ext.histoforge.data.MetricSnapshotBuilder;
import qupath.ext.histoforge.large.LargeActionTypes.HierarchyTransactionIdentity;
import qupath.ext.histoforge.roi.RoiRegistry;
import qupath.ext.histoforge.spatial.HierarchyDelta;
import qupath.ext.histoforge.spatial.HierarchyPolicy;
import qupath.ext.histoforge.spatial.TargetHierarchyPopulation;
import qupath.ext.histoforge.state.ImageDataRevisionDigester;
import qupath.ext.histoforge.state.OutputIndex;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Pure owner of the exact hierarchy transaction identity used by large-action rebase. */
public final class HierarchyTransactionDigester {

    private static final int CONTENT_SCHEMA_VERSION = 1;

    private HierarchyTransactionDigester() {}

    /** Capture the upload-time transaction and derive its out-of-domain guarded descendants. */
    public static HierarchyTransactionIdentity captureBaseline(
            ExecutionScopeCodec.TargetRegion target,
            Set<ExecutionScopeCodec.OutputKey> replacementClosure,
            boolean includeMetricPopulation,
            PathObjectHierarchy hierarchy) {
        return captureBaseline(
                target, replacementClosure, includeMetricPopulation, hierarchy, List.of(target));
    }

    public static HierarchyTransactionIdentity captureBaseline(
            ExecutionScopeCodec.TargetRegion target,
            Set<ExecutionScopeCodec.OutputKey> replacementClosure,
            boolean includeMetricPopulation,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        Projection projection =
                project(
                        target,
                        replacementClosure,
                        includeMetricPopulation,
                        hierarchy,
                        selectionRegions);
        return identity(
                target,
                replacementClosure,
                projection.guardedObjectIds(),
                projection.objects(),
                hierarchy);
    }

    /**
     * Recapture the upload-time projection immediately before commit.
     *
     * <p>The guarded set is derived again rather than trusted from the handle. A changed set is a
     * transaction conflict even if every previously guarded object still exists.
     */
    public static HierarchyTransactionIdentity captureForRebase(
            ExecutionScopeCodec.TargetRegion target,
            HierarchyTransactionIdentity baseline,
            boolean includeMetricPopulation,
            PathObjectHierarchy hierarchy) {
        return captureForCommit(target, baseline, includeMetricPopulation, hierarchy).identity();
    }

    public static HierarchyTransactionIdentity captureForRebase(
            ExecutionScopeCodec.TargetRegion target,
            HierarchyTransactionIdentity baseline,
            boolean includeMetricPopulation,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        return captureForCommit(
                        target, baseline, includeMetricPopulation, hierarchy, selectionRegions)
                .identity();
    }

    /** Capture the verified pre-commit identity and the exact existing-object mutation domain. */
    public static RebaseCapture captureForCommit(
            ExecutionScopeCodec.TargetRegion target,
            HierarchyTransactionIdentity baseline,
            boolean includeMetricPopulation,
            PathObjectHierarchy hierarchy) {
        return captureForCommit(
                target, baseline, includeMetricPopulation, hierarchy, List.of(target));
    }

    public static RebaseCapture captureForCommit(
            ExecutionScopeCodec.TargetRegion target,
            HierarchyTransactionIdentity baseline,
            boolean includeMetricPopulation,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        Objects.requireNonNull(baseline, "baseline");
        Projection projection =
                project(
                        target,
                        baseline.replacementClosure(),
                        includeMetricPopulation,
                        hierarchy,
                        selectionRegions);
        if (!projection.guardedObjectIds().equals(baseline.guardedObjectIds())) {
            throw new IllegalArgumentException(
                    "target transaction guarded-object set changed after capture");
        }
        return new RebaseCapture(
                identity(
                        target,
                        baseline.replacementClosure(),
                        projection.guardedObjectIds(),
                        projection.objects(),
                        hierarchy),
                projection.modelObjectIds(),
                projection.replacementObjectIds(),
                projection.preservedDescendantAssignments(),
                projection.metricObjectIds());
    }

    /** Capture a post-delta or recovery projection with the baseline's exact transaction shape. */
    public static HierarchyTransactionIdentity captureExpected(
            ExecutionScopeCodec.TargetRegion target,
            HierarchyTransactionIdentity baseline,
            boolean includeMetricPopulation,
            PathObjectHierarchy hierarchy) {
        return captureExpected(
                target, baseline, includeMetricPopulation, hierarchy, List.of(target));
    }

    public static HierarchyTransactionIdentity captureExpected(
            ExecutionScopeCodec.TargetRegion target,
            HierarchyTransactionIdentity baseline,
            boolean includeMetricPopulation,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        HierarchyTransactionIdentity transaction = Objects.requireNonNull(baseline, "baseline");
        Set<ExecutionScopeCodec.OutputKey> outputs = transaction.replacementClosure();
        Projection projection =
                project(target, outputs, includeMetricPopulation, hierarchy, selectionRegions);
        Set<String> guarded = immutableIds(transaction.guardedObjectIds());
        if (!guarded.containsAll(projection.guardedObjectIds())) {
            throw new IllegalArgumentException(
                    "post-delta transaction acquired a new out-of-domain guarded object");
        }
        if (target.key() instanceof ExecutionScopeCodec.Slide && !guarded.isEmpty()) {
            throw new IllegalArgumentException(
                    "whole-slide transactions cannot have out-of-domain guarded objects");
        }
        if (guarded.isEmpty()) {
            return identity(target, outputs, guarded, projection.objects(), hierarchy);
        }
        Map<UUID, PathObject> objects = new LinkedHashMap<>(projection.objects());
        for (String objectId : guarded) {
            UUID id = UUID.fromString(objectId);
            PathObject object = projection.currentObjects().get(id);
            if (object == null) {
                throw new IllegalArgumentException(
                        "guarded transaction object is no longer present: " + objectId);
            }
            objects.put(id, object);
        }
        return identity(target, outputs, guarded, objects, hierarchy);
    }

    private static Projection project(
            ExecutionScopeCodec.TargetRegion target,
            Set<ExecutionScopeCodec.OutputKey> replacementClosure,
            boolean includeMetricPopulation,
            PathObjectHierarchy hierarchy,
            List<ExecutionScopeCodec.TargetRegion> selectionRegions) {
        ExecutionScopeCodec.TargetRegion exact = Objects.requireNonNull(target, "target");
        Set<ExecutionScopeCodec.OutputKey> outputs = immutableOutputs(replacementClosure);
        PathObjectHierarchy currentHierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        Map<UUID, PathObject> current = currentObjects(currentHierarchy);
        Map<UUID, PathObject> selected = new LinkedHashMap<>();
        Set<UUID> modelObjectIds = new LinkedHashSet<>();
        Set<UUID> metricObjectIds = new LinkedHashSet<>();
        RoiRegistry nativeRois = RoiRegistry.fromHierarchy(currentHierarchy);
        if (exact.key() instanceof ExecutionScopeCodec.Slide) {
            selected.putAll(current);
        }
        // Even empty predictions depend on the unchanged native boundary and its lock state.
        if (exact.key() instanceof ExecutionScopeCodec.Roi roi) {
            PathObject boundary =
                    nativeRois
                            .findRoi(roi.roiId())
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Selected annotation is missing: "
                                                            + roi.roiId()));
            selected.put(boundary.getID(), boundary);
        }
        for (PathObject object :
                TargetHierarchyPopulation.select(exact, currentHierarchy, selectionRegions)) {
            UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
            selected.put(objectId, object);
            modelObjectIds.add(objectId);
        }
        if (includeMetricPopulation) {
            for (UUID objectId :
                    MetricSnapshotBuilder.transactionRegionObjectIds(
                            exact, currentHierarchy, selectionRegions)) {
                selected.put(
                        objectId,
                        Objects.requireNonNull(
                                current.get(objectId), "metric object absent from hierarchy"));
                metricObjectIds.add(objectId);
            }
        }

        OutputIndex outputIndex = OutputIndex.fromHierarchy(currentHierarchy);
        Set<UUID> removalObjectIds = new LinkedHashSet<>();
        for (ExecutionScopeCodec.OutputKey output : outputs) {
            for (PathObject object : outputIndex.objects(output)) {
                UUID objectId = Objects.requireNonNull(object.getID(), "persistent object UUID");
                selected.put(objectId, object);
                modelObjectIds.add(objectId);
                removalObjectIds.add(objectId);
            }
        }

        Set<String> guarded = new LinkedHashSet<>();
        List<HierarchyDelta.ParentAssignment> preservedAssignments =
                HierarchyPolicy.preservedDescendantAssignments(
                        currentHierarchy, Set.copyOf(removalObjectIds), nativeRois::findRoi);
        for (HierarchyDelta.ParentAssignment assignment : preservedAssignments) {
            if (!selected.containsKey(assignment.objectId())) {
                guarded.add(assignment.objectId().toString());
                selected.put(assignment.objectId(), current.get(assignment.objectId()));
            }
        }
        return new Projection(
                current,
                selected,
                guarded,
                modelObjectIds,
                metricObjectIds,
                removalObjectIds,
                preservedAssignments);
    }

    private static HierarchyTransactionIdentity identity(
            ExecutionScopeCodec.TargetRegion target,
            Set<ExecutionScopeCodec.OutputKey> outputs,
            Set<String> guarded,
            Map<UUID, PathObject> objects,
            PathObjectHierarchy hierarchy) {
        JsonObject preimage = new JsonObject();
        preimage.addProperty("schema_version", CONTENT_SCHEMA_VERSION);
        if (target.key() instanceof ExecutionScopeCodec.Slide) {
            preimage.addProperty("population", "whole-hierarchy");
            preimage.addProperty(
                    "hierarchy_semantic_sha256",
                    ImageDataRevisionDigester.hierarchySemanticDigest(hierarchy));
        } else {
            preimage.addProperty("population", "target-transaction");
            JsonArray values = new JsonArray();
            objects.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            entry -> {
                                JsonObject value = new JsonObject();
                                value.addProperty("object_id", entry.getKey().toString());
                                value.addProperty(
                                        "semantic_sha256",
                                        ImageDataRevisionDigester.objectTransactionSemanticDigest(
                                                entry.getValue()));
                                values.add(value);
                            });
            preimage.add("objects", values);
        }
        return new HierarchyTransactionIdentity(
                immutableOutputs(outputs),
                immutableIds(guarded),
                CanonicalCodec.canonicalDigest(preimage));
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

    private static Set<ExecutionScopeCodec.OutputKey> immutableOutputs(
            Set<ExecutionScopeCodec.OutputKey> outputs) {
        if (outputs == null) {
            throw new IllegalArgumentException("replacement closure must not be null");
        }
        Set<ExecutionScopeCodec.OutputKey> result = new LinkedHashSet<>();
        for (ExecutionScopeCodec.OutputKey output : outputs) {
            result.add(Objects.requireNonNull(output, "replacement-closure entry"));
        }
        return Set.copyOf(result);
    }

    private static Set<String> immutableIds(Set<String> objectIds) {
        if (objectIds == null) {
            throw new IllegalArgumentException("guarded object IDs must not be null");
        }
        Set<String> result = new LinkedHashSet<>();
        for (String objectId : objectIds) {
            result.add(CanonicalCodec.requireCanonicalUuid(objectId, "guarded_object_id"));
        }
        return Set.copyOf(result);
    }

    private record Projection(
            Map<UUID, PathObject> currentObjects,
            Map<UUID, PathObject> objects,
            Set<String> guardedObjectIds,
            Set<UUID> modelObjectIds,
            Set<UUID> metricObjectIds,
            Set<UUID> replacementObjectIds,
            List<HierarchyDelta.ParentAssignment> preservedDescendantAssignments) {
        private Projection {
            // Private to this synchronous capture. Reuse its validated UUID lookup when expected
            // state must reinclude baseline guards; never recapture or retain it between calls.
            currentObjects = Collections.unmodifiableMap(currentObjects);
            objects = Map.copyOf(Objects.requireNonNull(objects, "objects"));
            guardedObjectIds =
                    Set.copyOf(Objects.requireNonNull(guardedObjectIds, "guardedObjectIds"));
            modelObjectIds = Set.copyOf(Objects.requireNonNull(modelObjectIds, "modelObjectIds"));
            metricObjectIds =
                    Set.copyOf(Objects.requireNonNull(metricObjectIds, "metricObjectIds"));
            replacementObjectIds =
                    Set.copyOf(
                            Objects.requireNonNull(replacementObjectIds, "replacementObjectIds"));
            preservedDescendantAssignments =
                    List.copyOf(
                            Objects.requireNonNull(
                                    preservedDescendantAssignments,
                                    "preservedDescendantAssignments"));
        }
    }

    /** Process-local pre-commit capture; only the identity is persisted in the action handle. */
    public record RebaseCapture(
            HierarchyTransactionIdentity identity,
            Set<UUID> modelObjectIds,
            Set<UUID> replacementObjectIds,
            List<HierarchyDelta.ParentAssignment> preservedDescendantAssignments,
            Set<UUID> metricObjectIds) {
        public RebaseCapture {
            identity = Objects.requireNonNull(identity, "identity");
            modelObjectIds = Set.copyOf(Objects.requireNonNull(modelObjectIds, "modelObjectIds"));
            replacementObjectIds =
                    Set.copyOf(
                            Objects.requireNonNull(replacementObjectIds, "replacementObjectIds"));
            preservedDescendantAssignments =
                    List.copyOf(
                            Objects.requireNonNull(
                                    preservedDescendantAssignments,
                                    "preservedDescendantAssignments"));
            metricObjectIds =
                    Set.copyOf(Objects.requireNonNull(metricObjectIds, "metricObjectIds"));
        }
    }
}
